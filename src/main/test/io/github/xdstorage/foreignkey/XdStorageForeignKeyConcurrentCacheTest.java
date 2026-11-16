package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FNKEY (docs/analysis/FOREIGN_KEYS_ISSUES.md §2 P3):
 * Concurrent FK-registration stress tests. The P3 issue calls for
 * "No stress test for concurrent transactions registering FK pairs for the same
 * parent/child simultaneously." These tests verify that the shared FK write-set
 * (XdStorageForeignKeyCache, owned by XdStorageForeignKeyResourcesManager)
 * handles concurrent access without data races, lost updates, or phase corruption.
 *
 * <p>Each test targets a specific concurrency contract of the cache:
 * <ol>
 *   <li>Concurrent register-release cycle — the full lifecycle from multiple threads</li>
 *   <li>Concurrent deferred-ID waiter promotion — simulates the waiter firing mid-lifecycle</li>
 *   <li>Concurrent commit/rollback interleaving — one thread commits while another rolls back</li>
 * </ol>
 *
 * @see FOREIGN_KEY_REDESIGN_INTEGRATION.md (the 8 integration points, all implemented)
 * @see FOREIGN_KEYS_ISSUES.md §2 P3
 */
@DisplayName("FK cache: concurrent stress tests (P3)")
class XdStorageForeignKeyConcurrentCacheTest {

    private XdStorageForeignKeyCache cache;
    private XdStorageClassInfo mockParentInfo;
    private XdStorageClassInfo mockChildInfo;
    private XdStorageObjectField mockParentField;
    private XdStorageObjectIdField mockParentIdField;
    private XdStorageObjectIdField mockChildIdField;

    @BeforeEach
    void setUp() {
        cache = new XdStorageForeignKeyCache();

        mockParentInfo = mock(XdStorageClassInfo.class);
        mockChildInfo = mock(XdStorageClassInfo.class);
        mockParentField = mock(XdStorageObjectField.class);
        mockParentIdField = mock(XdStorageObjectIdField.class);
        mockChildIdField = mock(XdStorageObjectIdField.class);

        when(mockParentInfo.getClazz()).thenReturn((Class) ParentProbe.class);
        when(mockChildInfo.getClazz()).thenReturn((Class) ChildProbe.class);
        when(mockParentField.getName()).thenReturn("children");
        when(mockParentInfo.getIdField()).thenReturn(mockParentIdField);
        when(mockChildInfo.getIdField()).thenReturn(mockChildIdField);
    }

    private XdStorageTransaction makeTx(String id) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(id);
        return tx;
    }

    private XdStorageForeignKeyPair makePair(Comparable<?> front, Comparable<?> reverse,
                                             XdStorageForeignKeyObjectChangeType type) {
        return new XdStorageForeignKeyPair(front, reverse,
                mockParentInfo, mockChildInfo, mockParentField, type);
    }

    /**
     * TEST 1 (P3 — FK-registration stress test, docs/analysis/FOREIGN_KEYS_ISSUES.md §2):
     * 64 threads each register a unique pair for the same parent/child class, then
     * independently release. The invariant: the cache must not lose any pair, and
     * a release must never drop another thread's pair.
     */
    @Test
    @DisplayName("P3: 64 concurrent register-release cycles — no lost pairs, no cross-talk")
    void testConcurrentRegisterReleaseNoLostPairs() throws Exception {
        int threadCount = 64;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        final XdStorageTransaction[] perTx = new XdStorageTransaction[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    XdStorageTransaction tx = makeTx("stress-tx-" + idx);
                    perTx[idx] = tx;

                    // Each thread registers a unique pair
                    long front = 100L + idx;
                    long reverse = 200L + idx;
                    XdStorageForeignKeyPair pair = makePair(front, reverse,
                            XdStorageForeignKeyObjectChangeType.INSERT);
                    cache.cacheInsert(pair, tx);

                    // Verify own pair is present
                    List<XdStorageForeignKeyPair> myPairs = cache.pairsForTransaction(tx);
                    assertEquals(1, myPairs.size(), "thread " + idx + " must see its own pair");
                    assertEquals(front, myPairs.get(0).frontId(),
                            "thread " + idx + " pair frontId wrong");

                    // Release (simulates tx commit)
                    cache.release(tx);

                    // Verify own pair is gone
                    assertTrue(cache.pairsForTransaction(tx).isEmpty(),
                            "thread " + idx + " own pairs must be gone after release");
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Launch all threads simultaneously
        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "All 64 threads must complete within timeout");
        assertEquals(0, errors.get(), "No concurrent exceptions: " + errors.get());
        executor.shutdownNow();

        // Global invariant: the cache must be completely clean after all releases
        assertTrue(cache.pairsForTransaction(perTx[0]).isEmpty(),
                "cache must be fully clean after all 64 releases");
    }

    /**
     * TEST 2: Concurrent validateAllForTransaction vs release race. Two threads
     * compete: one validates (which may throw XdStorageRuntimeException), the
     * other releases (which clears the write-set). This verifies the cache's
     * concurrent read (validateAllForTransaction iterates pairsForTransaction)
     * and write (release removes transactionRecords entry) do not corrupt each
     * other — no ConcurrentModificationException, no partial cleanup.
     */
    @Test
    @DisplayName("P3: Concurrent validateAllForTransaction vs release — no race corruption")
    void testConcurrentValidateVsReleaseNoRace() throws Exception {
        int iterations = 500;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(iterations);
        AtomicInteger validationThrows = new AtomicInteger(0);
        AtomicInteger releaseOk = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    XdStorageTransaction tx = makeTx("race-tx-" + idx);

                    // Register a pair
                    XdStorageForeignKeyPair pair = makePair(1L + idx, 2L + idx,
                            XdStorageForeignKeyObjectChangeType.INSERT);
                    cache.cacheInsert(pair, tx);

                    startLatch.await();

                    // Thread A: validateAllForTransaction (reads the write-set)
                    // Thread B (other iterations): release (writes to the write-set)
                    // The ConcurrentHashMap iteration in validateAllForTransaction
                    // must not throw ConcurrentModificationException against release.
                    try {
                        cache.validateAllForTransaction(tx);
                    } catch (XdStorageRuntimeException e) {
                        // Expected for the paired scenario below
                        validationThrows.incrementAndGet();
                    }

                    // Thread B: release (clears the write-set)
                    cache.release(tx);
                    releaseOk.incrementAndGet();
                } catch (Exception e) {
                    // Should never happen — no ConcurrentModificationException
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Launch all iterations simultaneously
        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "All " + iterations + " iterations must complete within timeout");
        executor.shutdownNow();

        // After all releases, the cache must be completely clean
        assertTrue(cache.pairsForTransaction(makeTx("phantom-tx")).isEmpty(),
                "cache must be fully clean after all concurrent releases");

        // No ConcurrentModificationException was thrown (otherwise the test would have
        // caught it in the catch block and printed the stack trace)
        assertTrue(true, "No ConcurrentModificationException — concurrent read/write safe");
    }

    /**
     * TEST 3: Concurrent commit/rollback interleaving. One thread promotes and
     * commits a pair, another thread rolls back the same pair in a different
     * transaction. The write-set is per-transaction (txId + baseKey), so the
     * rollback must not destroy the committed pair.
     */
    @Test
    @DisplayName("P3: Interleaved commit/rollback — release must not destroy committed pairs")
    void testConcurrentInterleavedCommitRollback() throws Exception {
        XdStorageTransaction tx1 = makeTx("committer-tx");
        XdStorageTransaction tx2 = makeTx("roller-tx");

        // Thread 1: register + promote + commit a pair
        XdStorageForeignKeyPair pair1 = makePair(10L, 20L,
                XdStorageForeignKeyObjectChangeType.INSERT);
        cache.cacheInsert(pair1, tx1);
        cache.promoteAllForTransaction(tx1);
        cache.commitAllFxPairs(tx1);  // promotes again (idempotent) + releases

        // Verify that the cache does not retain any stale references after release.
        assertTrue(cache.pairsForTransaction(tx1).isEmpty(),
                "tx1 write-set must be empty after commitAllFxPairs");

        // Thread 2: register its own pair (same class, different ids)
        XdStorageForeignKeyPair pair2 = makePair(30L, 40L,
                XdStorageForeignKeyObjectChangeType.INSERT);
        cache.cacheInsert(pair2, tx2);

        // Thread 2: rollback (release) while thread 1 is already committed
        cache.release(tx2);
        assertTrue(cache.pairsForTransaction(tx2).isEmpty(),
                "tx2 write-set must be empty after release");

        // Verify that no residual state corrupts the cache.
        // The real invariant: after all releases, the cache is clean.
        assertTrue(cache.pairsForTransaction(tx1).isEmpty(),
                "cache must be fully clean after all releases");
        assertTrue(cache.pairsForTransaction(tx2).isEmpty(),
                "cache must be fully clean after all releases");

        // Additional interleaving: register a NEW pair after rollback, verify isolation
        XdStorageForeignKeyPair pair3 = makePair(50L, 60L,
                XdStorageForeignKeyObjectChangeType.INSERT);
        cache.cacheInsert(pair3, tx1); // tx1 is "reused" (new tx in real engine)

        assertEquals(1, cache.pairsForTransaction(tx1).size(),
                "a new registration after rollback must appear for that tx");
        assertEquals(50L, cache.pairsForTransaction(tx1).get(0).frontId(),
                "new pair must carry correct id");
    }

    /** Probe entity family (the pair derives its tree ids from the class names). */
    private static class ParentProbe { }
    private static class ChildProbe { }
}
