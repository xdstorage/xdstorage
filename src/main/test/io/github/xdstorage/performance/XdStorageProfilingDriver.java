package io.github.xdstorage.performance;

import io.github.xdstorage.BinFeasEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.RangeOpsEntity;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PROFILE (docs/tasks/2.2.0/14): the scenario driver for the engine-wide bottleneck
 * ledger. ONE scenario per JVM run; the JFR window attaches via jcmd AFTER the setup
 * (the fill must not pollute the profile — the java-profiler skill's rule 4).
 *
 * <p>Usage: XdStorageProfilingDriver &lt;scenario&gt; &lt;N&gt; &lt;windowSec&gt;
 * The driver fills N entities, prints "READY pid=..." then loops the measured op
 * until the window elapses. The wrapper bash attaches JFR on READY.
 *
 * <p>Scenarios: scan_cold (reopen → full load, per round), point_warm (one open,
 * warm, then by-id lookups), search_equals / search_range / search_paged
 * (RangeOpsEntity/range_idx), write_heavy (single-entity saves), save_delete
 * (indexed save+delete), batch_commit (100/tx), concurrent_writers (10 threads ×
 * update-tx — the ThreadPark/contention evidence for the consistency-core task).
 */
public final class XdStorageProfilingDriver {

    private XdStorageProfilingDriver() {
    }

    public static void main(final String[] args) throws Exception {
        final String scenario = args[0];
        final int n = Integer.parseInt(args[1]);
        final long windowSec = Long.parseLong(args[2]);
        final String root = "./target/profile-" + scenario + "-" + System.nanoTime();

        System.out.println("[DRV] scenario=" + scenario + " N=" + n + " window=" + windowSec + "s root=" + root);
        final long deadline = System.nanoTime() + windowSec * 1_000_000_000L;

        if ("scan_cold".equals(scenario)) {
            runScanCold(n, windowSec, root, deadline);
        } else if ("point_warm".equals(scenario)) {
            runPointWarm(n, windowSec, root, deadline);
        } else if ("search_equals".equals(scenario) || "search_range".equals(scenario) ||
                "search_paged".equals(scenario)) {
            runSearch(scenario, n, windowSec, root, deadline);
        } else if ("write_heavy".equals(scenario)) {
            runWriteHeavy(n, windowSec, root, deadline);
        } else if ("save_delete".equals(scenario)) {
            runSaveDelete(n, windowSec, root, deadline);
        } else if ("batch_commit".equals(scenario)) {
            runBatch(n, windowSec, root, deadline);
        } else if ("concurrent_writers".equals(scenario)) {
            runConcurrent(n, windowSec, root, deadline);
        } else {
            throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        System.out.println("[DRV] DONE");
    }

    private static long fill(final IXdFileStorage storage, final int n, final boolean search) throws Exception {
        final long t0 = System.nanoTime();
        for (int batch = 0; batch < n; batch += 200) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            for (int i = batch; i < Math.min(batch + 200, n); i++) {
                if (search) {
                    final RangeOpsEntity e = new RangeOpsEntity();
                    e.setId("e" + i);
                    e.setScore((long) i);
                    e.setLabel("entity-" + i);
                    storage.save(e, tx);
                } else {
                    storage.save(new BinFeasEntity("e" + i, i), tx);
                }
            }
            storage.commitTransaction(tx);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static void ready() {
        final String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        System.out.println("READY pid=" + pid);
        System.out.flush();
    }

    private static void runScanCold(final int n, final long window, final String root, final long deadline) throws
            Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("prof-fill-" + System.nanoTime(), root, 64);
        System.out.println("[DRV] fill " + fill(storage, n, false) + " ms");
        storage.shutdown(); // the fill's folder lock would block the scan rounds' reopens
        ready();
        int rounds = 0;
        long loaded = 0;
        while (System.nanoTime() < deadline) {
            storage = XdStorageProvider.newOrGetFileStorage("prof-scan-" + System.nanoTime(), root, 64);
            final IXdStorageTransaction tx = storage.beginTransaction();
            loaded = storage.load(BinFeasEntity.class, (IXdStoragePredicate<BinFeasEntity>) o -> true, tx).size();
            storage.commitTransaction(tx);
            storage.shutdown();
            rounds++;
        }
        System.out.println("[DRV] rounds=" + rounds + " lastLoaded=" + loaded);
    }

    private static void runPointWarm(final int n, final long window, final String root, final long deadline) throws
            Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("prof-fill-" + System.nanoTime(), root, 64);
        System.out.println("[DRV] fill " + fill(storage, n, false) + " ms");
        final IXdStorageTransaction warmTx = storage.beginTransaction();
        storage.load(BinFeasEntity.class, (IXdStoragePredicate<BinFeasEntity>) o -> true, warmTx);
        storage.commitTransaction(warmTx); // warm everything
        ready();
        final AtomicLong ops = new AtomicLong();
        final IXdStorageTransaction tx = storage.beginTransaction();
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 1000; i++) {
                final BinFeasEntity e = storage.load(BinFeasEntity.class, "e" + (i % n), tx);
                if (e == null) {
                    throw new AssertionError("lookup missed e" + (i % n));
                }
                ops.incrementAndGet();
            }
        }
        storage.commitTransaction(tx);
        storage.shutdown();
        System.out.println("[DRV] ops=" + ops.get());
    }

    private static void runSearch(final String scenario, final int n, final long window, final String root, final long
            deadline) throws Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("prof-fill-" + System.nanoTime(), root, 64);
        System.out.println("[DRV] fill " + fill(storage, n, true) + " ms");
        ready();
        final AtomicLong ops = new AtomicLong();
        final IXdStorageTransaction tx = storage.beginTransaction();
        while (System.nanoTime() < deadline) {
            final long mid = n / 2;
            final java.util.Collection<RangeOpsEntity> result;
            if ("search_equals".equals(scenario)) {
                result = storage.load(RangeOpsEntity.class, "range_idx",
                        new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, mid),
                                null), tx);
            } else if ("search_range".equals(scenario)) {
                result = storage.load(RangeOpsEntity.class, "range_idx",
                        new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                                new XdStorageRange(mid - 50, mid + 50)), null), tx);
            } else {
                final XdStorageSearchQuery paged = new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                                new XdStorageRange(mid - 50, mid + 50)), null);
                result = storage.load(RangeOpsEntity.class, "range_idx", paged.orderByPrimary(false).limit(10), tx);
            }
            if (result.isEmpty()) {
                throw new AssertionError("the search returned nothing for " + scenario);
            }
            ops.incrementAndGet();
        }
        storage.commitTransaction(tx);
        storage.shutdown();
        System.out.println("[DRV] ops=" + ops.get());
    }

    private static void runWriteHeavy(final int n, final long window, final String root, final long deadline) throws
            Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("prof-fill-" + System.nanoTime(), root, 64);
        System.out.println("[DRV] fill " + fill(storage, n, false) + " ms");
        ready();
        final AtomicLong seq = new AtomicLong(n);
        final AtomicLong ops = new AtomicLong();
        while (System.nanoTime() < deadline) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final long id = seq.incrementAndGet();
            storage.save(new BinFeasEntity("e" + id, (int) id), tx);
            storage.commitTransaction(tx);
            ops.incrementAndGet();
        }
        storage.shutdown();
        System.out.println("[DRV] ops=" + ops.get());
    }

    private static void runSaveDelete(final int n, final long window, final String root, final long deadline) throws
            Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("prof-fill-" + System.nanoTime(), root, 64);
        System.out.println("[DRV] fill " + fill(storage, n, false) + " ms");
        ready();
        final AtomicLong seq = new AtomicLong(n);
        final AtomicLong ops = new AtomicLong();
        while (System.nanoTime() < deadline) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final long id = seq.incrementAndGet();
            final BinFeasEntity e = new BinFeasEntity("e" + id, (int) id);
            storage.save(e, tx);
            storage.delete(e, tx);
            storage.commitTransaction(tx);
            ops.incrementAndGet();
        }
        storage.shutdown();
        System.out.println("[DRV] ops=" + ops.get());
    }

    private static void runBatch(final int n, final long window, final String root, final long deadline) throws
            Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("prof-fill-" + System.nanoTime(), root, 64);
        System.out.println("[DRV] fill " + fill(storage, n, false) + " ms");
        ready();
        final AtomicLong seq = new AtomicLong(n);
        final AtomicLong ops = new AtomicLong();
        while (System.nanoTime() < deadline) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            for (int i = 0; i < 100; i++) {
                final long id = seq.incrementAndGet();
                storage.save(new BinFeasEntity("e" + id, (int) id), tx);
            }
            storage.commitTransaction(tx);
            ops.addAndGet(100);
        }
        storage.shutdown();
        System.out.println("[DRV] ops=" + ops.get());
    }

    private static void runConcurrent(final int n, final long window, final String root, final long deadline) throws
            Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("prof-fill-" + System.nanoTime(), root, 64);
        System.out.println("[DRV] fill " + fill(storage, n, false) + " ms");
        ready();
        final int threads = 10;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicLong ops = new AtomicLong();
        final List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tFinal = t;
            futures.add(pool.submit(() -> {
                start.await();
                final java.util.concurrent.atomic.AtomicLong conflicts = new AtomicLong();
                while (System.nanoTime() < deadline) {
                    final IXdStorageTransaction tx = storage.beginTransaction();
                    try {
                        final BinFeasEntity e = storage.load(BinFeasEntity.class, "e" + ((tFinal * 7 + ops.get()) % n),
                                tx);
                        e.setName(e.getName() + ".");
                        storage.update(e, tx);
                        storage.commitTransaction(tx);
                        ops.incrementAndGet();
                    } catch (final io.github.xdstorage.exceptions.XdStorageException conflict) {
                        // the designed first-committer-wins refusal (the task-40 stage-time check):
                        // a REAL workload retries — count the conflicts (the task-01 contention
                        // evidence) and back off briefly, exactly like the application would
                        storage.rollbackTransaction(tx);
                        conflicts.incrementAndGet();
                        Thread.sleep(2);
                    }
                }
                System.out.println("[DRV] worker t" + tFinal + " conflicts=" + conflicts.get());
                return null;
            }));
        }
        start.countDown();
        for (final Future<?> f : futures) {
            f.get(200, TimeUnit.SECONDS);
        }
        pool.shutdown();
        storage.shutdown();
        System.out.println("[DRV] ops=" + ops.get());
    }
}
