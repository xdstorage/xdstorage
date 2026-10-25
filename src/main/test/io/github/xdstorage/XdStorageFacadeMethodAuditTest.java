package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-3b (docs/tasks/2.1.0/07): the facade-method audit's gap fills. Every method here
 * covers a thin spot of the {@code IXdStorage}/{@code IXdFileStorage}/{@code
 * XdStorageProvider} matrix that the specialized suites (CRUD, search, reliability)
 * leave to their own layers — the getters, the predicate load, the watch facade, the
 * no-transaction failure paths, and the verify overloads. The full matrix lives in the
 * task file.
 */
public class XdStorageFacadeMethodAuditTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    public static class AuditEntity {
        @XdStorageObjectId
        private Long id;
        private String value;

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    private static final String STORAGE_NAME = "facade-audit-" + System.nanoTime();
    private static final String STORAGE_DIR = "./target/facade-audit-db-" + System.nanoTime();

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

    private Long insertOne(final String value) throws Exception {
        final AuditEntity e = new AuditEntity();
        e.setValue(value);
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            storage.save(e, tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
        return e.getId();
    }

    @Test
    public void getVersion_ReturnsLibraryVersion() {
        // the facade default method delegates to the same filtered resource the
        // provider reports — semver or "unknown" without Maven filtering, never null.
        final String version = storage.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        assertEquals(XdStorageProvider.getVersion(), version);
    }

    @Test
    public void getName_MatchesOpenedName() {
        assertEquals(STORAGE_NAME, storage.getName());
    }

    @Test
    public void getTransactionManager_BeginCommitRoundTrip() throws Exception {
        assertNotNull(storage.getTransactionManager());
        // the manager is the lower-level handle of the SAME transaction lifecycle the
        // facade drives: a transaction begun through it must commit through it.
        final IXdStorageTransaction tx = storage.getTransactionManager().beginTransaction(15000L);
        assertNotNull(tx);
        tx.commit();
        assertFalse(storage.getTransactionManager().isTransactionAlive(tx));
    }

    @Test
    public void getMetrics_TransactionCountersMove() throws Exception {
        final long begunBefore = storage.getMetrics().get("transactionsBegun");
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        tx.commit();
        assertTrue(storage.getMetrics().get("transactionsBegun") >= begunBefore + 1,
                "the facade metrics getter must expose the live per-storage counter set");
    }

    @Test
    public void has_TrueForStored_FalseForMissing() throws Exception {
        final Long id = insertOne("present");
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            assertTrue(storage.has(AuditEntity.class, id, tx));
            assertFalse(storage.has(AuditEntity.class, -12345L, tx));
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    @Test
    public void has_OutsideTransaction_FailsLoudly() {
        // the thread-bound transaction contract: the no-explicit-tx overloads refuse.
        assertThrows(XdStorageException.class, () -> storage.has(AuditEntity.class, 1L));
    }

    @Test
    public void load_ByPredicate_FiltersInSnapshot() throws Exception {
        insertOne("alpha");
        insertOne("beta");
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            final java.util.Collection<AuditEntity> alphas = storage.load(
                    AuditEntity.class, e -> "alpha".equals(e.getValue()), tx);
            assertEquals(1, alphas.size());
            assertEquals("alpha", alphas.iterator().next().getValue());
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    @Test
    public void load_MissingId_ReturnsNull_AuditFinding() throws Exception {
        // T-3b AUDIT FINDING (filed in the task file): the facade javadoc says load-by-id
        // throws when no object exists — the code actually RETURNS NULL (only load-by-
        // REFERENCE throws "cannot load by reference"). The two load families have
        // different missing-key semantics. Pinned here as observed; the javadoc/code
        // alignment decision is the filed follow-up, not a silent fix.
        insertOne("only");
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            assertNull(storage.load(AuditEntity.class, -987654321L, tx));
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    @Test
    public void watch_Facade_FeedsSnapshotObjects() throws Exception {
        insertOne("w1");
        insertOne("w2");
        final AtomicInteger delivered = new AtomicInteger();
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            storage.watch(AuditEntity.class, e -> delivered.incrementAndGet(), tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
        assertEquals(2, delivered.get(), "the watch facade must feed both snapshot objects");
    }

    @Test
    public void executeStructureUpdate_OutsideTransaction_FailsLoudly() {
        // schema evolution runs inside the caller's transaction — without one, loud.
        assertThrows(XdStorageException.class, () -> storage.executeStructureUpdate(AuditEntity.class));
    }

    @Test
    public void verify_FileAndPathOverloads_MatchTheStringForm() {
        // the three verify overloads are one walk — the folder forms must agree with it.
        final XdStorageVerifyReport byString = XdStorageProvider.verify(STORAGE_DIR);
        final XdStorageVerifyReport byFile = XdStorageProvider.verify(new File(STORAGE_DIR));
        final XdStorageVerifyReport byPath = XdStorageProvider.verify(Paths.get(STORAGE_DIR));

        // the report's fields are public final by contract (no accessors)
        assertEquals(byString.verdict, byFile.verdict);
        assertEquals(byString.verdict, byPath.verdict);
        assertEquals(byString.files.size(), byFile.files.size());
        assertEquals(byString.files.size(), byPath.files.size());
        assertTrue(Files.exists(Paths.get(STORAGE_DIR)));
    }
}
