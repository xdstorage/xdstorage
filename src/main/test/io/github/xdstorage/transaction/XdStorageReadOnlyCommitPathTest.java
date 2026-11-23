package io.github.xdstorage.transaction;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.metrics.XdStorageMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2 (docs/tasks/2.1.0/08): contract tests for the READ-ONLY transaction commit path.
 * A begin -> load -> commit cycle with an empty write-set must take the cheap release
 * path (no 2PC record iteration, zero file write-outs) while preserving every observable
 * contract: rollback-only still throws, nested read-only transactions are still waited
 * for by the parent, metrics count the commit but never a write-out, records/locks are
 * released so concurrent writers behave exactly as before.
 */
@DisplayName("B2: read-only transaction commit path")
public class XdStorageReadOnlyCommitPathTest {

    private static final int SEED_COUNT = 200;

    @TempDir
    Path tempDir;

    private IXdStorage storage;
    private String storageName;

    @BeforeEach
    public void setUp() {
        storageName = "b2-ro-" + System.nanoTime();
        storage = XdStorageProvider.newOrGetFileStorage(storageName, tempDir.resolve("db").toString(), 512);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    private void seedEntities() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            for (int i = 0; i < SEED_COUNT; i++) {
                B2ReadOnlyEntity e = new B2ReadOnlyEntity();
                e.setId("id-" + i);
                e.setPayload("v1-" + i);
                storage.save(e, tx);
            }
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Test
    @DisplayName("read-only commit counts transactionsCommitted, never resourceWriteOuts")
    public void readOnlyCommit_countsCommittedAndNeverWritesOut() throws Exception {
        seedEntities();
        final long committed0 = storage.getMetrics().get(XdStorageMetrics.TRANSACTIONS_COMMITTED);
        final long writeOuts0 = storage.getMetrics().get(XdStorageMetrics.RESOURCE_WRITE_OUTS);
        final long writeNanos0 = storage.getMetrics().get("resourceWriteOutNanos");

        for (int i = 0; i < 20; i++) {
            IXdStorageTransaction readTx = storage.beginTransaction(60000L);
            try {
                B2ReadOnlyEntity e = storage.load(B2ReadOnlyEntity.class, "id-" + i, readTx);
                assertNotNull(e);
                storage.commitTransaction(readTx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(readTx);
                throw e;
            }
        }

        assertEquals(committed0 + 20, storage.getMetrics().get(XdStorageMetrics.TRANSACTIONS_COMMITTED),
                "every read-only commit must still count as a committed transaction");
        assertEquals(writeOuts0, storage.getMetrics().get(XdStorageMetrics.RESOURCE_WRITE_OUTS),
                "a no-change commit must not produce a single file write-out");
        assertEquals(writeNanos0, storage.getMetrics().get("resourceWriteOutNanos"));
        assertEquals(0L, storage.getMetrics().get(XdStorageMetrics.ACTIVE_TRANSACTIONS),
                "no transaction state may leak after the short-path commits");
    }

    @Test
    @DisplayName("records are released: a writer can mutate the object a read tx loaded, concurrently and after")
    public void readOnlyCommit_releasesRecords_writerVisibilityUnchanged() throws Exception {
        seedEntities();
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        // 1. concurrent writer while the read tx is open: snapshot isolation for the reader,
        //    the writer is neither blocked nor poisoned by the read transaction's presence
        IXdStorageTransaction readTx = storage.beginTransaction(60000L);
        B2ReadOnlyEntity loaded = storage.load(B2ReadOnlyEntity.class, "id-1", readTx);
        assertEquals("v1-1", loaded.getPayload());

        Future<?> writer = executor.submit(() -> {
            IXdStorageTransaction wtx = storage.beginTransaction(60000L);
            try {
                B2ReadOnlyEntity w = storage.load(B2ReadOnlyEntity.class, "id-1", wtx);
                w.setPayload("v2-1");
                storage.update(w, wtx);
                storage.commitTransaction(wtx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(wtx);
                throw e;
            }
            return null;
        });
        writer.get(30, TimeUnit.SECONDS);

        // 2. the open read tx still sees its own snapshot
        B2ReadOnlyEntity fromSnapshot = storage.load(B2ReadOnlyEntity.class, "id-1", readTx);
        assertEquals("v1-1", fromSnapshot.getPayload(), "open read tx must keep its MVCC snapshot");

        // 3. the read-only commit itself stays clean while a newer committed version exists
        storage.commitTransaction(readTx);

        // 4. after the read tx is gone, a fresh read sees the writer's version
        IXdStorageTransaction freshTx = storage.beginTransaction(60000L);
        B2ReadOnlyEntity fresh = storage.load(B2ReadOnlyEntity.class, "id-1", freshTx);
        assertEquals("v2-1", fresh.getPayload(), "the writer's version must be visible after the read tx commits");
        storage.commitTransaction(freshTx);

        executor.shutdownNow();
    }

    @Test
    @DisplayName("rollback-only detection still throws on commit of a read-only transaction")
    public void rollbackOnly_stillThrows_onReadOnlyCommit() throws Exception {
        seedEntities();
        final long committed0 = storage.getMetrics().get(XdStorageMetrics.TRANSACTIONS_COMMITTED);

        IXdStorageTransaction readTx = storage.beginTransaction(60000L);
        assertNotNull(storage.load(B2ReadOnlyEntity.class, "id-2", readTx));
        readTx.markRollbackOnly();

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(readTx),
                "commit of a rollback-only transaction must throw even with an empty write-set");
        assertEquals(committed0, storage.getMetrics().get(XdStorageMetrics.TRANSACTIONS_COMMITTED),
                "the thrown commit must not be counted as committed");
    }

    @Test
    @DisplayName("parent commit waits for an open nested read-only transaction")
    public void nestedReadOnlyTx_isStillWaitedByParent() throws Exception {
        seedEntities();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final long committed0 = storage.getMetrics().get(XdStorageMetrics.TRANSACTIONS_COMMITTED);
        final AtomicLong nestedDoneAt = new AtomicLong();
        final AtomicLong parentDoneAt = new AtomicLong();

        // parent + nested on the SAME thread (nested binds by thread), read inside the nested
        IXdStorageTransaction parent = storage.beginTransaction(60000L);
        IXdStorageTransaction nested = storage.beginTransaction(60000L);
        assertNotNull(storage.load(B2ReadOnlyEntity.class, "id-3", nested));

        // the parent commit comes from ANOTHER thread: it must block in
        // skipOrWaitForInternalTransactions until the nested read-only tx finishes
        final Future<?> parentCommit = executor.submit(() -> {
            storage.commitTransaction(parent);
            return null;
        });
        Thread.sleep(400);
        assertFalse(parentCommit.isDone(),
                "parent commit must not finish while its nested read-only transaction is still open");

        nested.commit();
        nestedDoneAt.set(System.nanoTime());
        parentCommit.get(30, TimeUnit.SECONDS);
        parentDoneAt.set(System.nanoTime());

        assertTrue(nestedDoneAt.get() <= parentDoneAt.get(),
                "the parent may only finish after the nested read-only tx committed");
        assertEquals(committed0 + 2, storage.getMetrics().get(XdStorageMetrics.TRANSACTIONS_COMMITTED),
                "nested + parent both count as committed");
        assertEquals(0L, storage.getMetrics().get(XdStorageMetrics.ACTIVE_TRANSACTIONS));
        executor.shutdownNow();
    }

    @Test
    @DisplayName("concurrent read-only transactions from many threads: all commit, zero write-outs")
    public void concurrentReadOnlyTxns_allCommit_zeroWriteOuts() throws Exception {
        seedEntities();
        final long committed0 = storage.getMetrics().get(XdStorageMetrics.TRANSACTIONS_COMMITTED);
        final long writeOuts0 = storage.getMetrics().get(XdStorageMetrics.RESOURCE_WRITE_OUTS);
        final int threads = 4;
        final int perThread = 25;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);

        final Future<?>[] futures = new Future[threads];
        for (int t = 0; t < threads; t++) {
            final int offset = t * 1000;
            futures[t] = executor.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    IXdStorageTransaction readTx = storage.beginTransaction(60000L);
                    try {
                        assertNotNull(storage.load(B2ReadOnlyEntity.class, "id-" + ((offset + i) % SEED_COUNT),
                                readTx));
                        storage.commitTransaction(readTx);
                    } catch (final RuntimeException e) {
                        storage.rollbackTransaction(readTx);
                        throw e;
                    }
                }
                return null;
            });
        }
        for (final Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        assertEquals(committed0 + threads * perThread, storage.getMetrics().get(XdStorageMetrics
                .TRANSACTIONS_COMMITTED));
        assertEquals(writeOuts0, storage.getMetrics().get(XdStorageMetrics.RESOURCE_WRITE_OUTS),
                "concurrent no-change commits must not write out either");
        assertEquals(0L, storage.getMetrics().get(XdStorageMetrics.ACTIVE_TRANSACTIONS));
        executor.shutdownNow();
    }
}
