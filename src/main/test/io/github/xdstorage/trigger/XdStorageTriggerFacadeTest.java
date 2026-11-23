package io.github.xdstorage.trigger;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-3a (docs/tasks/2.1.0/06): the trigger contour through the public facade. Pins the
 * actual semantics of the committing-transaction trigger firing:
 *  - insert/update/delete triggers receive (old, new) per change and fire when the
 *    committing transaction's effects are already durable;
 *  - registration order is the invocation order;
 *  - triggers are dispatched per entity class;
 *  - a THROWING trigger does not fail the commit: the trigger contour runs after the
 *    resources are first-phase committed (XdStorageTransactionManager.fireChanges logs the
 *    failure and proceeds) — the "the transaction will roll back" remark in the manager's
 *    log message describes the manager-level rethrow, not the commit-phase behavior; both
 *    behaviors are pinned here at their respective levels.
 */
@DisplayName("T-3a: trigger contour through the facade")
public class XdStorageTriggerFacadeTest {

    @TempDir
    Path tempDir;

    private IXdFileStorage storage;

    private void open() {
        storage = XdStorageProvider.newOrGetFileStorage("trigger-facade-" + System.nanoTime(),
                tempDir.resolve("db").toString(), 512);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
    }

    // The engine's adapter bases route perform() to the single-state overload but leave
    // getType()/getClazz() abstract — every trigger supplies its type and class (this is
    // the registration contract, exercised through the adapters everywhere below).
    private abstract static class InsertAdapter<T> extends XdStorageAbstractInsertTrigger<T> {
        private final Class<T> cl;

        InsertAdapter(final Class<T> cl) {
            this.cl = cl;
        }

        @Override
        public XdStorageObjectOperationType getType() {
            return XdStorageObjectOperationType.Insert;
        }

        @Override
        public Class<T> getClazz() {
            return cl;
        }
    }

    // No update adapter base exists in the engine — the update trigger implements the
    // interface directly (this asymmetry with insert/delete is the engine's shape).
    private abstract static class UpdateAdapter<T> implements IXdStorageTrigger<T> {
        private final Class<T> cl;

        UpdateAdapter(final Class<T> cl) {
            this.cl = cl;
        }

        @Override
        public XdStorageObjectOperationType getType() {
            return XdStorageObjectOperationType.Update;
        }

        @Override
        public Class<T> getClazz() {
            return cl;
        }

        @Override
        public void perform(final T oldObject, final T newObject, final IXdStorageTransaction transaction) {
            performUpdate(oldObject, newObject, transaction);
        }

        protected abstract void performUpdate(T oldObject, T newObject, IXdStorageTransaction transaction);
    }

    private abstract static class DeleteAdapter<T> extends XdStorageAbstractDeleteTrigger<T> {
        private final Class<T> cl;

        DeleteAdapter(final Class<T> cl) {
            this.cl = cl;
        }

        @Override
        public XdStorageObjectOperationType getType() {
            return XdStorageObjectOperationType.Delete;
        }

        @Override
        public Class<T> getClazz() {
            return cl;
        }
    }

    private static final class RecordingTrigger extends InsertAdapter<TriggeredEntity> {
        final List<String> log;
        final String name;

        RecordingTrigger(final String name, final List<String> log) {
            super(TriggeredEntity.class);
            this.name = name;
            this.log = log;
        }

        @Override
        protected void perform(final TriggeredEntity object, final IXdStorageTransaction transaction) {
            log.add(name + ":" + object.getId());
        }
    }

    @Test
    @DisplayName("insert trigger fires after commit and sees the saved object")
    public void insertTriggerFiresAfterCommit() throws Exception {
        open();
        final List<String> fired = new ArrayList<>();
        storage.registerTrigger(new RecordingTrigger("ins", fired));

        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        storage.save(new TriggeredEntity("t-1", 7L), tx);
        assertTrue(fired.isEmpty(), "no trigger fires before the commit");
        storage.commitTransaction(tx);

        assertEquals(1, fired.size());
        assertEquals("ins:t-1", fired.get(0));
    }

    @Test
    @DisplayName("update trigger receives the old and the new state; delete trigger the old state")
    public void updateAndDeleteTriggersReceiveBothStates() throws Exception {
        open();
        final List<String> updateFired = new ArrayList<>();
        final List<String> deleteFired = new ArrayList<>();
        storage.registerTrigger(new UpdateAdapter<TriggeredEntity>(TriggeredEntity.class) {
            @Override
            protected void performUpdate(final TriggeredEntity oldObject, final TriggeredEntity newObject,
                                         final IXdStorageTransaction transaction) {
                updateFired.add(oldObject.getValue() + "->" + newObject.getValue());
            }
        });
        storage.registerTrigger(new DeleteAdapter<TriggeredEntity>(TriggeredEntity.class) {
            @Override
            protected void perform(final TriggeredEntity oldObject, final IXdStorageTransaction transaction) {
                deleteFired.add("del:" + oldObject.getId());
            }
        });

        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        storage.save(new TriggeredEntity("u-1", 1L), tx);
        storage.commitTransaction(tx);

        final IXdStorageTransaction upx = storage.beginTransaction(60000L);
        final TriggeredEntity loaded = storage.load(TriggeredEntity.class, "u-1", upx);
        loaded.setValue(2L);
        storage.update(loaded, upx);
        storage.commitTransaction(upx);
        assertEquals(1, updateFired.size());
        assertEquals("1->2", updateFired.get(0), "the update trigger sees both states");

        final IXdStorageTransaction delx = storage.beginTransaction(60000L);
        final TriggeredEntity toDelete = storage.load(TriggeredEntity.class, "u-1", delx);
        storage.delete(toDelete, delx);
        storage.commitTransaction(delx);
        assertEquals(1, deleteFired.size());
        assertEquals("del:u-1", deleteFired.get(0));
    }

    @Test
    @DisplayName("registration order is the invocation order; dispatch is per entity class")
    public void registrationOrderAndPerClassDispatch() throws Exception {
        open();
        final List<String> fired = new ArrayList<>();
        final AtomicInteger otherClassFired = new AtomicInteger();
        storage.registerTrigger(new RecordingTrigger("second", fired));
        storage.registerTrigger(new RecordingTrigger("first", fired));
        // a trigger for ANOTHER class must never fire for TriggeredEntity changes
        storage.registerTrigger(new InsertAdapter<OtherEntity>(OtherEntity.class) {
            @Override
            protected void perform(final OtherEntity object, final IXdStorageTransaction transaction) {
                otherClassFired.incrementAndGet();
            }
        });

        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        storage.save(new TriggeredEntity("o-1", 1L), tx);
        storage.commitTransaction(tx);

        assertEquals(Arrays.asList("second:o-1", "first:o-1"), fired,
                "the CopyOnWriteArrayList preserves registration order");
        assertEquals(0, otherClassFired.get(), "no cross-class dispatch");
    }

    @Test
    @DisplayName("a throwing trigger does not fail the commit (post-commit firing; failure is logged)")
    public void throwingTriggerDoesNotFailTheCommit() throws Exception {
        open();
        storage.registerTrigger(new InsertAdapter<TriggeredEntity>(TriggeredEntity.class) {
            @Override
            protected void perform(final TriggeredEntity object, final IXdStorageTransaction transaction) {
                throw new IllegalStateException("boom from trigger");
            }
        });

        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        storage.save(new TriggeredEntity("boom-1", 5L), tx);
        assertDoesNotThrow(() -> storage.commitTransaction(tx),
                "the trigger contour fires after the resources are committed — a trigger failure"
                        + " is logged, not allowed to fail the (already durable) commit");

        // the committed effect is durable despite the trigger failure
        final IXdStorageTransaction readTx = storage.beginTransaction(60000L);
        final TriggeredEntity loaded = storage.load(TriggeredEntity.class, "boom-1", readTx);
        storage.commitTransaction(readTx);
        assertNotNull(loaded);
        assertEquals(5L, loaded.getValue());
    }

    @Test
    @DisplayName("manager-level rethrow: a failing trigger surfaces to the performTriggers caller")
    public void managerLevelRethrowOnFailure() {
        final XdStorageTriggerManager manager = new XdStorageTriggerManager();
        manager.registerTrigger(new InsertAdapter<TriggeredEntity>(TriggeredEntity.class) {
            @Override
            protected void perform(final TriggeredEntity object, final IXdStorageTransaction transaction) {
                throw new IllegalStateException("direct boom");
            }
        });

        final List<XdStorageObjectChange> changes = new ArrayList<>();
        changes.add(new XdStorageObjectChange(
                XdStorageObjectOperationType.Insert, "d-1", null, new TriggeredEntity("d-1", 1L)));

        assertThrows(IllegalStateException.class, () ->
                        manager.performTriggers(null, changes),
                "at the manager level the failure rethrows to the caller");
    }

    @Test
    @DisplayName("the adapter base classes route to the single-state overloads")
    public void adapterBasesRouteToSingleStateOverloads() {
        final List<String> calls = new ArrayList<>();
        final InsertAdapter<TriggeredEntity> insertAdapter = new InsertAdapter<TriggeredEntity>(TriggeredEntity.class) {
            @Override
            protected void perform(final TriggeredEntity object, final IXdStorageTransaction transaction) {
                calls.add("insert:" + object.getId());
            }
        };
        insertAdapter.perform(null, new TriggeredEntity("a-1", 1L), null);
        assertTrue(calls.contains("insert:a-1"));

        final DeleteAdapter<TriggeredEntity> deleteAdapter = new DeleteAdapter<TriggeredEntity>(TriggeredEntity.class) {
            @Override
            protected void perform(final TriggeredEntity oldObject, final IXdStorageTransaction transaction) {
                calls.add("delete:" + oldObject.getId());
            }
        };
        deleteAdapter.perform(new TriggeredEntity("a-2", 2L), null, null);
        assertTrue(calls.contains("delete:a-2"));
    }

    @Test
    @DisplayName("a trigger doing storage work uses its OWN transaction (the IXdStorageTrigger contract)")
    public void triggerStorageWorkUsesItsOwnTransaction() throws Exception {
        open();
        final IXdFileStorage s = storage;
        storage.registerTrigger(new InsertAdapter<TriggeredEntity>(TriggeredEntity.class) {
            @Override
            protected void perform(final TriggeredEntity object, final IXdStorageTransaction transaction) {
                // REENTRANCY GUARD — the required discipline, not a nicety: triggers fire on
                // EVERY commit of their class, including the commits a trigger itself makes.
                // Without the guard an auditing trigger recurses forever and deadlocks the
                // commit executor's bounded pool (observed live during this suite's first run).
                if (object.getId().startsWith("audit-")) {
                    return;
                }
                try {
                    // IXdStorageTrigger's javadoc contract: "If this method works with storage,
                    // then this one must start and finish transaction." The committing
                    // transaction has already passed its first phase — writes through IT are
                    // lost; an own short transaction is the documented way.
                    final IXdStorageTransaction own = s.beginTransaction(60000L);
                    s.save(new TriggeredEntity("audit-" + object.getId(), object.getValue()), own);
                    s.commitTransaction(own);
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        storage.save(new TriggeredEntity("t-audit", 9L), tx);
        storage.commitTransaction(tx);

        final IXdStorageTransaction readTx = storage.beginTransaction(60000L);
        final TriggeredEntity original = storage.load(TriggeredEntity.class, "t-audit", readTx);
        final TriggeredEntity audit = storage.load(TriggeredEntity.class, "audit-t-audit", readTx);
        storage.commitTransaction(readTx);
        assertNotNull(original);
        assertNotNull(audit, "the audit row written by the trigger's own transaction is durable");
        assertEquals(9L, audit.getValue());
    }

    /** A second entity type: only used to prove there is no cross-class dispatch. */
    @io.github.xdstorage.annotations.XdStorageObjectPolicy(policy = io.github.xdstorage.XdStoragePolicy
            .StoreAsClassObjects)
    public static class OtherEntity {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private String id;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }
}
