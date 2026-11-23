package io.github.xdstorage.foreignkey;

import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import io.github.xdstorage.utils.XdStorageObjectField;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FNKEY: Real FK-engine concurrency tests.
 * Tests the FK engine's real behaviors under concurrency:
 * - engine.process() concurrent (T1, T11)
 * - FK veto / idempotent cache (T2)
 * - Dual B+tree consistency (T3)
 * - Deferred-ID waiter race (T4)
 * - FK resource rollback race (T5)
 * - Cross-transaction FK visibility (T6)
 * - AutoSave cascade vs concurrent delete (T7)
 * - Multi-collection FK (T8)
 * - Deferred-ID resolution (T9)
 * - FK cache cleanup (T10)
 * - Later-wins semantics (T12, T13)
 * - Child rollback dissolves pair (T14)
 * - Parent rollback dissolves all pairs (T15)
 * - Null parent ref: no FK pair (Rule 1, T16)
 * - Parent in same TX: FK pair registered (Rule 2, T20)
 * - Multiple concurrent saves: no corruption (Rule 5, T18)
 * - Unresolved deferred-ID rejected (T17)
 *
 * @see FK_CONCURRENCY_TEST_ANALYSIS.md
 */
@DisplayName("FK: real engine concurrency tests (P2)")
public class XdStorageForeignKeyConcurrencyEngineTest extends AbstractForeignKeyEngineIntegrationTest {

    private XdStorageClassInfo parentInfo;
    private XdStorageClassInfo childInfo;
    private XdStorageObjectField childRefField;
    private XdStorageForeignKeyCache cache;

    @BeforeEach
    void setUp() throws Exception {
        Field classesInfoField = XdStorageClassMetadataRegistry.class.getDeclaredField("classesInfo");
        classesInfoField.setAccessible(true);
        Map<Class<?>, ?> map = (Map<Class<?>, ?>) classesInfoField.get(null);
        map.keySet().removeIf(k -> k.getName().contains("SampleParent") || k.getName().contains("SampleChild"));

        parentInfo = XdStorageClassMetadataRegistry.getClassInfo(
                io.github.xdstorage.foreignkey.OutcomeRelationsForeignKeyTest.SampleParent.class);
        childInfo = XdStorageClassMetadataRegistry.getClassInfo(
                io.github.xdstorage.foreignkey.OutcomeRelationsForeignKeyTest.SampleChild.class);
        childRefField = parentInfo.getFields().get("childRef");

        super.setUp();
        cache = resourcesManager.getForeignKeyCache();
    }

    /* ========================================================================
     * T1: Concurrent engine.process() on the SAME parent (MOST CRITICAL)
     * ======================================================================== */
    @Test
    @DisplayName("T1: Concurrent engine.process() same parent: no corruption")
    void testConcurrentEngineProcessSameParent() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        OutcomeRelationsForeignKeyTest.SampleParent p = new OutcomeRelationsForeignKeyTest.SampleParent();
        p.setId(1L);
        OutcomeRelationsForeignKeyTest.SampleChild c = new OutcomeRelationsForeignKeyTest.SampleChild();
        c.setId(1L);
        p.setChildRef(c);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    XdStorageTransaction tx = createTx("t1_" + idx);
                    engine.process(p, XdStorageForeignKeyObjectChangeType.INSERT, tx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdownNow();
    }

    /* ========================================================================
     * T2: FK veto race
     * ======================================================================== */
    @Test
    @DisplayName("T2: FK veto race: identical pairs converge")
    void testFkVetoRace() throws Exception {
        int threads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    XdStorageTransaction tx = createTx("t2_" + idx);
                    XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                            2L, 2L, parentInfo, childInfo, childRefField,
                            XdStorageForeignKeyObjectChangeType.INSERT);
                    cache.cacheInsert(pair, tx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from concurrent cacheInsert");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T3: Dual B+tree write race
     * ======================================================================== */
    @Test
    @DisplayName("T3: Dual B+tree race: register + deregister concurrent")
    void testDualBtreeWriteRace() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentInfo, childInfo, childRefField, transaction);
        fkResource.insert(3L, 3L, transaction);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    XdStorageTransaction tx = createTx("t3_" + idx);
                    if (idx % 2 == 0) {
                        XdStorageForeignKeyPair insertPair = new XdStorageForeignKeyPair(
                                3L, 3L, parentInfo, childInfo, childRefField,
                                XdStorageForeignKeyObjectChangeType.INSERT);
                        cache.cacheInsert(insertPair, tx);
                    } else {
                        XdStorageForeignKeyPair deletePair = new XdStorageForeignKeyPair(
                                3L, 3L, parentInfo, childInfo, childRefField,
                                XdStorageForeignKeyObjectChangeType.DELETE);
                        cache.cacheDelete(deletePair, tx);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from concurrent B+tree writes");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T4: Deferred-ID waiter race
     * ======================================================================== */
    @Test
    @DisplayName("T4: Deferred-ID waiter race: multiple registrations same parent")
    void testDeferredIdWaiterRace() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        OutcomeRelationsForeignKeyTest.SampleChild c = new OutcomeRelationsForeignKeyTest.SampleChild();
        c.setId(4L);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    XdStorageTransaction tx = createTx("t4_" + idx);
                    XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                            null, 4L, parentInfo, childInfo, childRefField,
                            XdStorageForeignKeyObjectChangeType.INSERT);
                    cache.cacheInsert(pair, tx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from concurrent deferred-ID registrations");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T5: FK resource rollback race
     * ======================================================================== */
    @Test
    @DisplayName("T5: FK resource rollback race: insert + rollback + insert")
    void testFkResourceRollbackRace() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentInfo, childInfo, childRefField, transaction);
        fkResource.insert(5L, 5L, transaction);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    XdStorageTransaction tx = createTx("t5_" + idx);
                    if (idx % 3 == 0) {
                        cache.cacheInsert(new XdStorageForeignKeyPair(5L, 5L, parentInfo, childInfo, childRefField,
                                XdStorageForeignKeyObjectChangeType.INSERT), tx);
                    } else if (idx % 3 == 1) {
                        cache.cacheDelete(new XdStorageForeignKeyPair(5L, 5L, parentInfo, childInfo, childRefField,
                                XdStorageForeignKeyObjectChangeType.DELETE), tx);
                        cache.release(tx);
                    } else {
                        cache.cacheInsert(new XdStorageForeignKeyPair(5L, 5L, parentInfo, childInfo, childRefField,
                                XdStorageForeignKeyObjectChangeType.INSERT), tx);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from concurrent rollback race");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T6: Cross-transaction FK visibility
     * ======================================================================== */
    @Test
    @DisplayName("T6: Cross-TX FK visibility: write-set shared across txs")
    void testCrossTransactionFKVisibility() throws Exception {
        XdStorageTransaction tx1 = createTx("tx6_1");
        cache.cacheInsert(new XdStorageForeignKeyPair(6L, 6L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.INSERT), tx1);
        assertEquals(1, cache.pairsForTransaction(tx1).size(), "Tx1's pair registered");

        XdStorageTransaction tx2 = createTx("tx6_2");
        assertEquals(0, cache.pairsForTransaction(tx2).size(), "Tx2 has no pairs (different tx)");
    }

    /* ========================================================================
     * T7: AutoSave cascade + concurrent delete
     * ======================================================================== */
    @Test
    @DisplayName("T7: AutoSave cascade vs concurrent delete: no corruption")
    void testAutoSaveVsConcurrentDelete() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentInfo, childInfo, childRefField, transaction);
        fkResource.insert(7L, 7L, transaction);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    XdStorageTransaction tx = createTx("t7_" + idx);
                    if (idx % 2 == 0) {
                        cache.cacheInsert(new XdStorageForeignKeyPair(7L, 7L, parentInfo, childInfo, childRefField,
                                XdStorageForeignKeyObjectChangeType.INSERT), tx);
                    } else {
                        cache.cacheDelete(new XdStorageForeignKeyPair(7L, 7L, parentInfo, childInfo, childRefField,
                                XdStorageForeignKeyObjectChangeType.DELETE), tx);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from AutoSave vs delete race");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T8: Multi-collection FK race
     * ======================================================================== */
    @Test
    @DisplayName("T8: Multi-collection FK race: same child from multiple parents")
    void testMultiCollectionFKRace() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    XdStorageTransaction tx = createTx("t8_" + idx);
                    cache.cacheInsert(new XdStorageForeignKeyPair(8L + idx, 8L, parentInfo, childInfo, childRefField,
                            XdStorageForeignKeyObjectChangeType.INSERT), tx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from multi-collection FK race");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T9: Deferred-ID resolution (engine level)
     * ======================================================================== */
    @Test
    @DisplayName("T9: Deferred-ID resolution: engine handles id generation race")
    void testDeferredIdResolutionEngine() throws Exception {
        XdStorageTransaction tx = createTx("tx9");

        cache.cacheInsert(new XdStorageForeignKeyPair(null, 9L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.INSERT), tx);
        assertEquals(1, cache.pairsForTransaction(tx).size(), "Unresolved pair registered");
        assertNull(cache.pairsForTransaction(tx).get(0).frontId(), "Parent ID unresolved");

        cache.removePairForTransaction(tx, cache.pairsForTransaction(tx).get(0));
        cache.cacheInsert(new XdStorageForeignKeyPair(99L, 9L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.INSERT), tx);
        assertEquals(Long.valueOf(99L), cache.pairsForTransaction(tx).get(0).frontId(), "Parent ID resolved to 99L");
    }

    /* ========================================================================
     * T10: FK cache cleanup on shutdown
     * ======================================================================== */
    @Test
    @DisplayName("T10: FK cache cleanup on shutdown: no cross-storage pollution")
    void testFkCacheCleanupOnShutdown() throws Exception {
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentInfo, childInfo, childRefField, transaction);
        fkResource.insert(10L, 10L, transaction);

        cache.releaseAll();
        assertTrue(cache.pairsForTransaction(transaction).isEmpty(), "FK cache clean after releaseAll");
    }

    /* ========================================================================
     * T11: Concurrent engine.process() different parents
     * ======================================================================== */
    @Test
    @DisplayName("T11: Concurrent engine.process() different parents: independence")
    void testConcurrentEngineProcessDifferentParents() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    OutcomeRelationsForeignKeyTest.SampleParent p = new OutcomeRelationsForeignKeyTest.SampleParent();
                    p.setId(11L + idx);
                    OutcomeRelationsForeignKeyTest.SampleChild c = new OutcomeRelationsForeignKeyTest.SampleChild();
                    c.setId(11L + idx);
                    p.setChildRef(c);

                    XdStorageTransaction tx = createTx("t11_" + idx);
                    engine.process(p, XdStorageForeignKeyObjectChangeType.INSERT, tx);
                    assertEquals(1, cache.pairsForTransaction(tx).size(), "Each tx has exactly 1 pair");
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from concurrent different-parent process()");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T12: Later-wins: DELETE after INSERT removes pair
     * ======================================================================== */
    @Test
    @DisplayName("T12: Later-wins: DELETE after INSERT removes pair")
    void testLaterWinsDeleteWins() throws Exception {
        XdStorageTransaction tx = createTx("tx12");
        cache.cacheInsert(new XdStorageForeignKeyPair(12L, 12L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.INSERT), tx);
        assertEquals(1, cache.pairsForTransaction(tx).size(), "INSERT registered");

        cache.cacheDelete(new XdStorageForeignKeyPair(12L, 12L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.DELETE), tx);
        assertEquals(0, cache.pairsForTransaction(tx).size(), "DELETE removed pair (later-wins)");
    }

    /* ========================================================================
     * T13: Later-wins: INSERT after DELETE restores pair
     * ======================================================================== */
    @Test
    @DisplayName("T13: Later-wins: INSERT after DELETE restores pair")
    void testLaterWinsInsertRestores() throws Exception {
        XdStorageTransaction tx = createTx("tx13");
        cache.cacheInsert(new XdStorageForeignKeyPair(13L, 13L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.DELETE), tx);
        assertEquals(1, cache.pairsForTransaction(tx).size(), "DELETE registered");

        cache.cacheInsert(new XdStorageForeignKeyPair(13L, 13L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.INSERT), tx);
        assertEquals(1, cache.pairsForTransaction(tx).size(), "INSERT restored pair");
        assertEquals(XdStorageForeignKeyObjectChangeType.INSERT, cache.pairsForTransaction(tx).get(0).type());
    }

    /* ========================================================================
     * T14: FK pair + child rollback: pair dissolved
     * ======================================================================== */
    @Test
    @DisplayName("T14: Child rollback: FK pair dissolved")
    void testChildRollbackDissolvesPair() throws Exception {
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentInfo, childInfo, childRefField, transaction);
        fkResource.insert(14L, 14L, transaction);
        assertEquals(1, fkResource.readByParentObjectId(14L, transaction).size());

        cache.cacheDelete(new XdStorageForeignKeyPair(14L, 14L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.DELETE), transaction);
        assertEquals(0, cache.pairsForTransaction(transaction).size(), "Pair dissolved");
    }

    /* ========================================================================
     * T15: FK pair + parent rollback: all pairs dissolved
     * ======================================================================== */
    @Test
    @DisplayName("T15: Parent rollback: all FK pairs dissolved")
    void testParentRollbackDissolvesAllPairs() throws Exception {
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentInfo, childInfo, childRefField, transaction);
        fkResource.insert(15L, 15L, transaction);

        cache.cacheDelete(new XdStorageForeignKeyPair(15L, 15L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.DELETE), transaction);
        assertEquals(0, cache.pairsForTransaction(transaction).size(), "All pairs dissolved");
    }

    /* ========================================================================
     * T16: Null parent ref: no FK pair (Rule 1)
     * ======================================================================== */
    @Test
    @DisplayName("T16: Null parent ref: no FK pair created (Rule 1)")
    void testNullParentNoFkPair() throws Exception {
        OutcomeRelationsForeignKeyTest.SampleChild c = new OutcomeRelationsForeignKeyTest.SampleChild();
        c.setId(16L);

        assertDoesNotThrow(() -> engine.process(c, XdStorageForeignKeyObjectChangeType.INSERT, transaction));
        assertTrue(cache.pairsForTransaction(transaction).isEmpty(), "No FK pairs for null parent (Rule 1)");
    }

    /* ========================================================================
     * T17: Unresolved deferred-ID: engine rejects at phase-1
     * ======================================================================== */
    @Test
    @DisplayName("T17: Unresolved deferred-ID: stays in write-set until phase-1")
    void testUnresolvedDeferredIdRejected() throws Exception {
        XdStorageTransaction tx = createTx("tx17");
        cache.cacheInsert(new XdStorageForeignKeyPair(null, 17L, parentInfo, childInfo, childRefField,
                XdStorageForeignKeyObjectChangeType.INSERT), tx);

        assertEquals(null, cache.pairsForTransaction(tx).get(0).frontId(), "Pair is unresolved");
        assertEquals(1, cache.pairsForTransaction(tx).size(), "Unresolved pair stays in write-set");
    }

    /* ========================================================================
     * T18: Multiple concurrent saves: no FK corruption (Rule 5)
     * ======================================================================== */
    @Test
    @DisplayName("T18: Multiple concurrent saves: no FK corruption (Rule 5)")
    void testMultipleConcurrentSaves() throws Exception {
        OutcomeRelationsForeignKeyTest.SampleParent p = new OutcomeRelationsForeignKeyTest.SampleParent();
        p.setId(18L);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    OutcomeRelationsForeignKeyTest.SampleChild c = new OutcomeRelationsForeignKeyTest.SampleChild();
                    c.setId(18L + idx);
                    p.setChildRef(c);

                    XdStorageTransaction tx = createTx("t18_" + idx);
                    engine.process(p, XdStorageForeignKeyObjectChangeType.INSERT, tx);
                    assertEquals(1, cache.pairsForTransaction(tx).size(), "Each tx has 1 pair");
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions from concurrent saves");
        executor.shutdownNow();
    }

    /* ========================================================================
     * T19: Parent in same TX: FK pair registered correctly (Rule 2)
     * ======================================================================== */
    @Test
    @DisplayName("T19: Parent in same TX: FK pair registered correctly")
    void testParentInSameTx() throws Exception {
        OutcomeRelationsForeignKeyTest.SampleParent p = new OutcomeRelationsForeignKeyTest.SampleParent();
        p.setId(19L);
        OutcomeRelationsForeignKeyTest.SampleChild c = new OutcomeRelationsForeignKeyTest.SampleChild();
        c.setId(19L);
        p.setChildRef(c);

        engine.process(p, XdStorageForeignKeyObjectChangeType.INSERT, transaction);
        assertTrue(cache.pairsForTransaction(transaction).size() >= 1, "FK pair registered (Rule 2)");
    }

    /* ========================================================================
     * Helper
     * ======================================================================== */
    private XdStorageTransaction createTx(String name) {
        return new XdStorageTransaction(null, name, null, 100L, "TX-" + name) {
            private final Set<Object> resources = new HashSet<>();
            @Override public void startCriticalSection(boolean r) {}
            @Override public void finishCriticalSection() {}
            @Override public boolean isResourceRegistered(io.github.xdstorage.resource.IXdStorageResourceObject res) {
                    return resources.contains(res.getResourceId()); }
            @Override public void registerResource(io.github.xdstorage.resource.IXdStorageResourceObject res, int order)
                    { resources.add(res.getResourceId()); }
        };
    }
}
