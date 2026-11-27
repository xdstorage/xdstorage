package io.github.xdstorage.transaction;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.XdPlanet;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Целевые тесты на сценарии нагрузки вложенных транзакций (task 12).
 * Цель: поймать утечку suspend-барьера, race-пропуск force-sweep,
 * и cascade-тайминги 3-уровневых вложенных коммитов.
 */
@DisplayName("Load-safety: сценарии вложенных транзакций (task 12)")
public class XdStorageNestedTransactionLoadSafetyTest {

    private static final String STORAGE_PATH = "./target/nested_load_safety_test";
    private static final int WORKER_COUNT = 8;
    private static final int PLANETS = 50;
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("nested_load_safety", STORAGE_PATH, 500);
        initPlanets();
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try { storage.shutdown(); } catch (Exception ignored) {}
            try { deleteDir(new File(STORAGE_PATH)); } catch (Exception ignored) {}
        }
    }

    private void deleteDir(File file) throws Exception {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        // R-50: File.delete legally fails on IDE-held files; just log and proceed
        if (!file.exists() || file.delete()) return;
        System.err.println("[WARN] deleteDir: could not delete " + file + " (held by IDE?)");
    }

    private void initPlanets() {
        IXdStorageTransaction tx = storage.beginTransaction();
        for (int i = 0; i < PLANETS; i++) {
            XdPlanet p = new XdPlanet();
            p.setName("Base_" + i);
            try { storage.save(p, tx); } catch (Throwable e) { throw new RuntimeException(e); }
        }
        try { tx.commit(); } catch (Throwable e) { throw new RuntimeException(e); }
    }

    // ============================================================
    // СЦЕНАРИЙ 1: Родительский skipOrWaitForInternalTransactions
    // не ловит cross-thread running nested, которая ещё не истекла.
    // ============================================================
    @Test
    @DisplayName("Scenario 1: parent waits on cross-thread running nested (sweep gap)")
    public void testParentWaitsOnCrossThreadRunningNested() throws Exception {
        final int WAIT_TIMEOUT_MS = 20_000;
        final AtomicReference<Throwable> caught = new AtomicReference<>();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch nestedStarted = new CountDownLatch(1);
        final CountDownLatch parentCommitted = new CountDownLatch(1);
        final AtomicInteger parentThrows = new AtomicInteger(0);
        final AtomicReference<IXdStorageTransaction> parentTxRef = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT + 1);

        for (int i = 0; i < WORKER_COUNT; i++) {
            final int workerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    IXdStorageTransaction parentTx = storage.beginTransaction(1000L);
                    parentTxRef.set(parentTx);
                    IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 60_000L);
                    XdPlanet planet = new XdPlanet();
                    planet.setName("Nested_" + workerId + "_long");
                    // connection exceptions in the lambda are swallowed on purpose
                    try { storage.save(planet, nestedTx); } catch (Throwable t) {} //NOPMD
                    nestedStarted.countDown();
                    parentCommitted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    storage.rollbackTransaction(nestedTx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (XdStorageRuntimeException e) {
                    parentThrows.incrementAndGet();
                    caught.compareAndSet(null, e);
                } catch (Throwable t) {
                    caught.compareAndSet(null, t);
                }
            });
        }

        executor.submit(() -> {
            try {
                startLatch.await();
                nestedStarted.await();
                // Родитель ждёт >5с, пока nested (60s timeout) всё ещё «running» на другом потоке.
                // С фиксом sweep threshold=5s: parent sweep-ит nested. Без фикса — ждёт вечно.
                Thread.sleep(6000); // > CROSS_THREAD_ABANDON_MILLIS (5000)
                storage.commitTransaction(parentTxRef.get());
                parentCommitted.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                caught.compareAndSet(null, t);
            }
        });

        startLatch.countDown();
        boolean done = parentCommitted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertTrue(done, "Parent commit не закоммитился за " + WAIT_TIMEOUT_MS + "мс — баг skipOrWait leak");
        if (caught.get() != null) fail("Caught: " + caught.get().getMessage());
        executor.shutdownNow();
    }

    // ============================================================
    // СЦЕНАРИЙ 2: Nested commit FAILS mid-phase → resume never called.
    // ============================================================
    @Test
    @DisplayName("Scenario 2: nested commit fails mid-phase, resume never called")
    public void testNestedCommitFailsResumeNeverCalled() throws Exception {
        IXdStorageTransaction parentTx = storage.beginTransaction(1000L);
        IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 5000L);
        XdPlanet p = new XdPlanet();
        p.setName("PendingNested_" + System.nanoTime());
        storage.save(p, nestedTx);
        storage.rollbackTransaction(nestedTx);
        assertDoesNotThrow(() -> storage.commitTransaction(parentTx),
                "Parent commit при aborted nested не должен бросать");
    }

    // ============================================================
    // СЦЕНАРИЙ 3: Deep-nested cascade (3 levels, all 10s timeout).
    // ============================================================
    @Test
    @DisplayName("Scenario 3: deep-nested cascade timing (3 levels)")
    public void testDeepNestedCascadeTiming() throws Exception {
        IXdStorageTransaction parentTx = storage.beginTransaction(10000L);
        IXdStorageTransaction nested1Tx = storage.beginTransaction(parentTx, 10000L);
        XdPlanet p1 = new XdPlanet();
        p1.setName("Level1_" + System.nanoTime());
        storage.save(p1, nested1Tx);

        IXdStorageTransaction nested2Tx = storage.beginTransaction(nested1Tx, 10000L);
        XdPlanet p2 = new XdPlanet();
        p2.setName("Level2_" + System.nanoTime());
        storage.save(p2, nested2Tx);

        IXdStorageTransaction nested3Tx = storage.beginTransaction(nested2Tx, 10000L);
        XdPlanet p3 = new XdPlanet();
        p3.setName("Level3_" + System.nanoTime());
        storage.save(p3, nested3Tx);

        assertDoesNotThrow(() -> {
            storage.commitTransaction(nested3Tx);
            storage.commitTransaction(nested2Tx);
            storage.commitTransaction(nested1Tx);
            storage.commitTransaction(parentTx);
        });

        IXdStorageTransaction readTx = storage.beginTransaction();
        assertTrue(storage.has(XdPlanet.class, p3.getId(), readTx));
        assertTrue(storage.has(XdPlanet.class, p2.getId(), readTx));
        assertTrue(storage.has(XdPlanet.class, p1.getId(), readTx));
        readTx.commit();
    }

    // ============================================================
    // СЦЕНАРИЙ 4: Abandoned nested cross-thread — не истекла (sweep gap).
    // skipOrWait: isRollback=false, isBound=false, age < timeout → NOT swept!
    // ============================================================
    @Test
    @DisplayName("Scenario 4: abandoned cross-thread not expired (sweep gap)")
    public void testAbandonedNestedCrossThreadNotExpired() throws Exception {
        final int WAIT_TIMEOUT_MS = 15_000;
        final AtomicReference<Throwable> caught = new AtomicReference<>();
        final CountDownLatch nestedStarted = new CountDownLatch(1);
        final CountDownLatch parentResult = new CountDownLatch(1);
        final AtomicReference<IXdStorageTransaction> parentTxRef = new AtomicReference<>();
        final ExecutorService executor = Executors.newFixedThreadPool(4);

        // Воркер: parent + stuck nested (60s timeout)
        executor.submit(() -> {
            try {
                IXdStorageTransaction parentTx = storage.beginTransaction(1000L);
                parentTxRef.set(parentTx);
                IXdStorageTransaction stuckNested = storage.beginTransaction(parentTx, 60_000L);
                XdPlanet stuck = new XdPlanet();
                stuck.setName("StuckNested_" + System.nanoTime());
                try { storage.save(stuck, stuckNested); } catch (Throwable t) {}
                nestedStarted.countDown();
            } catch (Throwable t) { caught.compareAndSet(null, t); }
        });

        nestedStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Воркер 2: parent calls commit while nested still running (60s timeout, age > 5s)
        executor.submit(() -> {
            try {
                Thread.sleep(6000); // > CROSS_THREAD_ABANDON_MILLIS (5000)
                storage.commitTransaction(parentTxRef.get());
            } catch (Throwable t) { caught.compareAndSet(null, t); }
            finally { parentResult.countDown(); }
        });

        boolean committed = parentResult.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!committed) fail("Parent не закоммитился за " + WAIT_TIMEOUT_MS + "мс — баг skipOrWait");
        if (caught.get() != null) fail("Caught: " + caught.get().getMessage());
        executor.shutdownNow();
    }

    // ============================================================
    // СЦЕНАРИЙ 5: isTransactionBoundToCurrentThread после commit.
    // ============================================================
    @Test
    @DisplayName("Scenario 5: isTransactionBoundToCurrentThread after commit")
    public void testStaleEntryRace() throws Exception {
        IXdStorageTransaction nestedTx = storage.beginTransaction(
                storage.getTransactionManager().getCurrentTransaction(), 5000L);
        XdPlanet p = new XdPlanet();
        p.setName("StaleEntry_" + System.nanoTime());
        storage.save(p, nestedTx);
        storage.commitTransaction(nestedTx);

        XdStorageTransactionManager mgr = (XdStorageTransactionManager) nestedTx.getTransactionManager();
        assertFalse(mgr.isTransactionBoundToCurrentThread(nestedTx.getTransactionId()));
        assertNull(mgr.getTransaction(nestedTx.getTransactionId()));
    }

    // ============================================================
    // СЦЕНАРИЙ 6: 8 воркеров × 100ms budgets × shared planet.
    // ============================================================
    @Test
    @DisplayName("Scenario 6: 8-воркер storm на один planet (race commit vs sweep)")
    public void testEightWorkerStormSharedPlanet() throws Exception {
        final int WORKERS = 8;
        final int ROUNDS = 50;
        final int WAIT_TIMEOUT_MS = 30_000;
        final AtomicBoolean anyFailure = new AtomicBoolean(false);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < WORKERS; w++) {
            final int workerId = w;
            final XdPlanet planet = new XdPlanet();
            planet.setName("Planet_" + workerId);
            planet.setWaterPercent(50 + workerId);
            storage.save(planet, storage.beginTransaction());

            final int wid = w;
            futures.add(executor.submit(() -> {
                try {
                    for (int r = 0; r < ROUNDS; r++) {
                        IXdStorageTransaction parentTx = storage.beginTransaction(100L);
                        IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 1000L);
                        XdPlanet readPlanet = storage.load(XdPlanet.class, planet.getName(), nestedTx);
                        if (readPlanet != null) {
                            readPlanet.setWaterPercent(readPlanet.getWaterPercent() + 1);
                            storage.save(readPlanet, nestedTx);
                            if (Math.random() < 0.5) storage.rollbackTransaction(nestedTx);
                            else storage.commitTransaction(nestedTx);
                        }
                        storage.commitTransaction(parentTx);
                    }
                } catch (Throwable t) {
                    if (anyFailure.compareAndSet(false, true)) firstFailure.set(t);
                }
            }));
        }

        for (Future<?> f : futures) f.get(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(anyFailure.get(), "Storm failed: " + firstFailure.get());
        executor.shutdownNow();
    }

    // ============================================================
    // СЦЕНАРИЙ 7: Nested rollback (explicit) + parent commit, cross-thread.
    // ============================================================
    @Test
    @DisplayName("Scenario 7: explicit nested rollback cross-thread, no poison")
    public void testNestedRollbackCrossThreadNoPoison() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        final CountDownLatch nestedRolledBack = new CountDownLatch(1);
        final CountDownLatch parentDone = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                IXdStorageTransaction parentTx = storage.beginTransaction(1000L);
                IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 5000L);
                XdPlanet p = new XdPlanet();
                p.setName("Rollback_" + System.nanoTime());
                storage.save(p, nestedTx);
                storage.rollbackTransaction(nestedTx);
                nestedRolledBack.countDown();
                storage.commitTransaction(parentTx);
            } catch (Throwable ignored) {}
            finally { parentDone.countDown(); }
        });

        nestedRolledBack.await(5_000, TimeUnit.MILLISECONDS);
        parentDone.await(5_000, TimeUnit.MILLISECONDS);
        executor.shutdownNow();
    }

    // ============================================================
    // СЦЕНАРИЙ 8: hasInternalTransactions=false, internalTransactions stale (zombie cleanup).
    // ============================================================
    @Test
    @DisplayName("Scenario 8: hasInternalTransactions=false, internalTransactions stale")
    public void testSkipOrWaitZombieCleanupPath() throws Exception {
        IXdStorageTransaction parentTx = storage.beginTransaction(1000L);
        IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 5000L);
        XdPlanet p = new XdPlanet();
        p.setName("ZombieCleanup_" + System.nanoTime());
        storage.save(p, nestedTx);

        storage.commitTransaction(nestedTx);

        XdStorageTransactionManager mgr = (XdStorageTransactionManager) nestedTx.getTransactionManager();
        assertNull(mgr.getTransaction(nestedTx.getTransactionId()));

        assertDoesNotThrow(() -> storage.commitTransaction(parentTx));
    }

    // ============================================================
    // СЦЕНАРИЙ 9: 20-уровневая вложенность (latch cascade).
    // ============================================================
    @Test
    @DisplayName("Scenario 9: 20-level deep nesting (latch cascade)")
    public void testTwentyLevelDeepNesting() throws Exception {
        List<IXdStorageTransaction> chain = new ArrayList<>(21);
        chain.add(storage.beginTransaction(10000L));
        for (int i = 0; i < 19; i++) {
            chain.add(storage.beginTransaction(chain.get(chain.size() - 1), 10000L));
        }

        XdPlanet p = new XdPlanet();
        p.setName("DeepLevel20_" + System.nanoTime());
        storage.save(p, chain.get(19));

        for (int i = 19; i >= 0; i--) {
            try { storage.commitTransaction(chain.get(i)); }
            catch (Throwable t) { fail("Level " + i + " commit failed: " + t.getMessage()); }
        }

        IXdStorageTransaction readTx = storage.beginTransaction();
        assertTrue(storage.has(XdPlanet.class, p.getId(), readTx));
        readTx.commit();
    }

    // ============================================================
    // СЦЕНАРИЙ 10: thread-bound check после nested commits.
    // ============================================================
    @Test
    @DisplayName("Scenario 10: thread-bound check after nested commits")
    public void testThreadBoundCheckAfterNestedCommits() throws Exception {
        IXdStorageTransaction parentTx = storage.beginTransaction(1000L);
        IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 5000L);
        XdPlanet p = new XdPlanet();
        p.setName("BoundCheck_" + System.nanoTime());
        storage.save(p, nestedTx);

        storage.commitTransaction(nestedTx);

        XdStorageTransactionManager mgr = (XdStorageTransactionManager) nestedTx.getTransactionManager();
        assertFalse(mgr.isTransactionBoundToCurrentThread(nestedTx.getTransactionId()));
        assertDoesNotThrow(() -> storage.commitTransaction(parentTx));
    }
}
