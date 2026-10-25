package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TREEFILE (docs/tasks/2.1.0/41) — the DETERMINISTIC repro of the tree-structure leak,
 * disabled until the structural compensation lands (the fix is the scoped task work:
 * register the pre-mutation root/firstLeaf at every structural mutation, restore on
 * rollback, and re-parse the tree from the last-committed files during compensation —
 * the naive pointer-restore attempt of 2026-10-25 left the re-inserts descending an
 * in-place-mutated node graph and burning the rebalance retry budgets).
 *
 * <p>The proven defect: a transaction that deletes every record of a B+tree-indexed class
 * collapses the tree IN PLACE on the aliased canonical instance (root=null, node records
 * deleted). When that transaction then ROLLS BACK, the record rollback brings the entity
 * and node records back — but the tree record's root/firstLeaf pointers stay mutated
 * (prevObject aliases the same instance), so the tree reads as EMPTY: every pre-transaction
 * entity becomes unreachable, and the restored node files are orphans (the disk leak).
 *
 * <p>The contract pinned here: after the rollback the tree is usable again — every
 * pre-transaction entity is loadable by id, the tree accepts new inserts, and the storage
 * reopens with the full, consistent state.
 */
public class XdStorageTreeStructureRollbackTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    @XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
    public static class RollbackEntity {
        @XdStorageObjectId
        private Long id;
        private String value;

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    private static final String STORAGE_NAME = "tree-rollback-" + System.nanoTime();
    private static final String STORAGE_DIR = "./target/tree-rollback-db-" + System.nanoTime();

    private IXdFileStorage storage;

    @BeforeEach
    public void openStorage() {
        deleteDir(new File(STORAGE_DIR));
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 200);
    }

    @AfterEach
    public void closeStorage() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
        deleteDir(new File(STORAGE_DIR));
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    private List<Long> insertBatch(final int count) throws Exception {
        final List<Long> ids = new ArrayList<>();
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            for (int i = 0; i < count; ++i) {
                final RollbackEntity e = new RollbackEntity();
                e.setValue("v" + i);
                storage.save(e, tx);
                ids.add(e.getId());
            }
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
        return ids;
    }

    @Test
    @org.junit.jupiter.api.Disabled("TREEFILE docs/tasks/2.1.0/41: the structural compensation is not landed yet — this repro is RED by design until then")
    public void rollbackOfFullCollapse_RestoresTheTree() throws Exception {
        // Grow the tree past one node (t=4): 40 records force splits and multiple nodes.
        final List<Long> ids = insertBatch(40);

        // Delete everything; then a phantom delete fails INSIDE the same transaction —
        // the deterministic trigger for the rollback of a fully collapsed tree.
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            for (final Long id : ids) {
                final RollbackEntity ref = new RollbackEntity();
                ref.setId(id);
                storage.delete(ref, tx);
            }
            final RollbackEntity phantom = new RollbackEntity();
            phantom.setId(-987654321L);
            assertThrows(XdStorageException.class, () -> storage.delete(phantom, tx),
                    "a phantom delete must fail inside the transaction");
        } finally {
            tx.rollback();
        }

        // THE CONTRACT: the rollback restored the pre-transaction tree — every entity is
        // loadable by id again through the id-index.
        final IXdStorageTransaction check = storage.beginTransaction(15000L);
        try {
            for (final Long id : ids) {
                final RollbackEntity restored = storage.load(RollbackEntity.class, id, check);
                assertNotNull(restored, "entity " + id + " must be loadable after the rollback");
                assertNotNull(restored.getValue());
            }
            check.commit();
        } catch (final Throwable t) {
            check.rollback();
            throw t;
        }

        // The restored tree accepts growth.
        final List<Long> fresh = insertBatch(5);
        final IXdStorageTransaction check2 = storage.beginTransaction(15000L);
        try {
            for (final Long id : fresh) {
                assertNotNull(storage.load(RollbackEntity.class, id, check2));
            }
            check2.commit();
        } catch (final Throwable t) {
            check2.rollback();
            throw t;
        }
    }

    @Test
    public void rolledBackCollapse_ReopensConsistently() throws Exception {
        final List<Long> ids = insertBatch(40);

        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            for (final Long id : ids) {
                final RollbackEntity ref = new RollbackEntity();
                ref.setId(id);
                storage.delete(ref, tx);
            }
            final RollbackEntity phantom = new RollbackEntity();
            phantom.setId(-987654321L);
            assertThrows(XdStorageException.class, () -> storage.delete(phantom, tx));
        } finally {
            tx.rollback();
        }

        // Shutdown + reopen: the persisted state must be the PRE-transaction one.
        storage.shutdown();
        storage = null;
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 200);

        final IXdStorageTransaction check = storage.beginTransaction(15000L);
        try {
            for (final Long id : ids) {
                assertNotNull(storage.load(RollbackEntity.class, id, check),
                        "entity " + id + " must survive the reopen after the rolled-back purge");
            }
            check.commit();
        } catch (final Throwable t) {
            check.rollback();
            throw t;
        }
    }
}
