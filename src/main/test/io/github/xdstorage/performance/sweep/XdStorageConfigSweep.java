package io.github.xdstorage.performance.sweep;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.metrics.XdStorageMetrics;
import io.github.xdstorage.search.query.IXdStorageChildCriterionOperator;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStorageChildCriterion;
import io.github.xdstorage.search.query.XdStorageCriterion;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Конфигурационный свип-харнесс СУБД XdStorage (формат YAML): замеряет влияние
 * (а) параметра t B+Дерева id-индекса, (б) размера фрагмента (объектов на файл
 * класса), (в) параметра t поискового индекса — на сквозные фазы жизненного
 * цикла данных через публичный фасад.
 *
 * МЕТОДОЛОГИЯ (сознательно не JMH):
 *  - у каждой конфигурации свой каталог хранилища и уникальное имя (реестр
 *    провайдера держит инстанс до shutdown); фазы строго серийные, однопоточные —
 *    конкурентное поведение уже покрыто JMH-бенчмарками и стресс-тестами, здесь
 *    нужен чистый сигнал "структура хранения -> стоимость операций";
 *  - данные предзагружаются СЕРИАЛЬНО пакетными коммитами (параллельная
 *    предзагрузка ловит известный "рассинхрон структуры" валидатора Б+Дерева);
 *  - чтения: прогрев + несколько повторов, берется МЕДИАНА повтора;
 *  - каждая фаза получает дельту счетчиков XdStorageMetrics хранилища
 *    (resourceWriteOuts / resourceWriteOutNanos и др.);
 *  - отдельная фаза reopen: shutdown + открытие, холодный первый поиск и
 *    холодная партия поисков (ленивая догрузка узлов дерева/фрагментов);
 *  - JIT-прогрев движка выравнивается между конфигурациями одинаковым
 *    warmup-повтором фазы чтения; абсолютные числа — ballpark этой машины,
 *    сравнивать нужно ОТНОСИТЕЛЬНЫЕ значения между конфигурациями.
 *
 * Запуск:
 *   mvn test-compile
 *   java -cp target/test-classes:target/classes:$(mvn -q dependency:build-classpath \
 *        -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=test) \
 *        io.github.xdstorage.performance.sweep.XdStorageConfigSweep [smoke|tsweep|fragsweep|search|all] [N]
 *
 * Результаты: target/sweep/<tag>.json + <tag>.csv (инкрементально после каждой конфигурации).
 */
public final class XdStorageConfigSweep {

    private static final long TX_TIMEOUT = 120_000L;
    private static final int BATCH = 500;
    private static final int FIND_REPS = 5;
    private static final int FIND_OPS = 3000;
    private static final int COLD_FIND_OPS = 1000;
    private static final int TXFIND_OPS = 1000;
    private static final int SCAN_REPS = 3;
    private static final long SEED = 42L;
    private static final int PAYLOAD_LEN = 240;

    private static final List<String> DELTA_COUNTERS = Arrays.asList(
            XdStorageMetrics.RESOURCE_WRITE_OUTS,
            XdStorageMetrics.RESOURCE_WRITE_OUT_NANOS,
            XdStorageMetrics.TRANSACTIONS_BEGUN,
            XdStorageMetrics.TRANSACTIONS_COMMITTED,
            XdStorageMetrics.ROLLBACK_ONLY_MARKS,
            XdStorageMetrics.TIMEOUT_SWEPT_ROLLBACKS);

    private static final long START = System.nanoTime();
    private static final Random SHARED_RND = new Random(SEED);
    private static long blackhole;

    /** Один результат конфигурации: фазы (ms|ops/s|µs/op), файловая статистика, дельты метрик. */
    public static final class SweepResult {
        public final String suite;
        public final String entity;
        public final int t;
        public final int frag;
        public final int n;
        public final String jvm = System.getProperty("java.version") + " / " + System.getProperty("os.name");
        public final Map<String, Double> phases = new LinkedHashMap<>();
        public final Map<String, Long> stats = new LinkedHashMap<>();
        public final Map<String, Map<String, Long>> metricDeltas = new LinkedHashMap<>();
        public final Map<String, Long> engineEvents = new TreeMap<>();
        public double heapUsedMb;
        public long wallTotalMs;
        public String error;

        SweepResult(final String suite, final String entity, final int t, final int frag, final int n) {
            this.suite = suite;
            this.entity = entity;
            this.t = t;
            this.frag = frag;
            this.n = n;
        }
    }

    public static void main(final String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "all";
        final int n = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        final Path out = Paths.get("target", "sweep");
        Files.createDirectories(out);
        log("mode=" + mode + " n=" + n + " jvm=" + System.getProperty("java.version"));

        if ("smoke".equals(mode)) {
            runTSweep(out, "smoke-tsweep", Arrays.asList(2, 500), Math.min(n, 300));
            runFragSweep(out, "smoke-fragsweep", Arrays.asList(32, 2048), Math.min(n, 300));
            runSearchSweep(out, "smoke-search", Arrays.asList(8, 500), Math.max(Math.min(n, 100), 50));
            log("SMOKE DONE");
            return;
        }
        if ("tsweep".equals(mode) || "all".equals(mode))
            runTSweep(out, "tsweep", Arrays.asList(2, 4, 8, 16, 32, 64, 128, 500), n);
        if ("fragsweep".equals(mode) || "all".equals(mode))
            runFragSweep(out, "fragsweep", Arrays.asList(8, 32, 128, 512, 2048, 8192), n);
        if ("search".equals(mode) || "all".equals(mode))
            runSearchSweep(out, "search", Arrays.asList(8, 64, 500), Math.max(n / 2, 500));
        log("DONE mode=" + mode);
    }

    // =========================================================================
    // СЮИТЫ
    // =========================================================================

    private static void runTSweep(final Path out, final String tag, final List<Integer> tValues, final int n) throws
            Exception {
        final List<SweepResult> results = new ArrayList<>();
        for (final int t : tValues) {
            final Class<?> cl = Class.forName("io.github.xdstorage.performance.sweep.SweepBTreeT" + t + "Entity");
            final SweepResult r = new SweepResult("tsweep", cl.getSimpleName(), t, 512, n);
            runConfig(cl, n, 512, r, false);
            results.add(r);
            writeJson(out, tag, results);
            writeCsv(out, tag, results);
        }
        printTable(tag, results);
    }

    private static void runFragSweep(final Path out, final String tag, final List<Integer> fragValues, final int n)
            throws Exception {
        final List<SweepResult> results = new ArrayList<>();
        for (final int frag : fragValues) {
            final SweepResult r = new SweepResult("fragsweep", SweepHashEntity.class.getSimpleName(), 0, frag, n);
            runConfig(SweepHashEntity.class, n, frag, r, true);
            results.add(r);
            writeJson(out, tag, results);
            writeCsv(out, tag, results);
        }
        printTable(tag, results);
    }

    private static void runSearchSweep(final Path out, final String tag, final List<Integer> tValues, final int n)
            throws Exception {
        final List<SweepResult> results = new ArrayList<>();
        for (final int t : tValues) {
            final Class<?> cl = Class.forName("io.github.xdstorage.performance.sweep.SweepSearchShipT" + t);
            final SweepResult r = new SweepResult("search", cl.getSimpleName(), t, 512, n);
            runSearchConfig(cl, n, r);
            results.add(r);
            writeJson(out, tag, results);
            writeCsv(out, tag, results);
        }
        printTable(tag, results);
    }

    // =========================================================================
    // ОДНА КОНФИГУРАЦИЯ: id-индекс (Hash или BTree t) + фрагментированные данные
    // =========================================================================

    private static <T> void runConfig(final Class<T> cl, final int n, final int frag,
                                      final SweepResult r, final boolean scanPhase) throws Exception {
        final String runTag = r.suite + "-" + cl.getSimpleName() + "-f" + frag + "-" + System.nanoTime();
        final Path dir = Paths.get("target", "sweep", "run-" + runTag);
        deleteRecursively(dir);
        final String storageName = "sweep-" + runTag;
        final long wall0 = System.nanoTime();
        IXdFileStorage storage = null;
        try {
            long t0 = System.nanoTime();
            storage = XdStorageProvider.newOrGetFileStorage(storageName, dir.toString(), frag);
            r.phases.put("openMs", ms(System.nanoTime() - t0));

            final List<String> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                ids.add(String.format("id-%08d", i));
            final List<String> shuffled = new ArrayList<>(ids);
            Collections.shuffle(shuffled, new Random(SEED));

            // --- ФАЗА: вставка (пакетные коммиты по BATCH) -------------------
            Map<String, Long> mPrev = metrics(storage);
            final List<Long> commitNanos = new ArrayList<>();
            long ph0 = System.nanoTime();
            for (int from = 0; from < n; from += BATCH) {
                final int to = Math.min(from + BATCH, n);
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                try {
                    for (int i = from; i < to; i++)
                        storage.save(newEntity(cl, ids.get(i), "p", i), tx);
                    final long c0 = System.nanoTime();
                    storage.commitTransaction(tx);
                    commitNanos.add(System.nanoTime() - c0);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
            }
            final long insertNanos = System.nanoTime() - ph0;
            r.phases.put("insertMs", ms(insertNanos));
            r.phases.put("insertOpsPerS", n / (insertNanos / 1e9));
            r.phases.put("commitMeanMs", mean(commitNanos));
            r.phases.put("commitMaxMs", max(commitNanos));
            Map<String, Long> mNow = metrics(storage);
            r.metricDeltas.put("insert", delta(mNow, mPrev));
            mPrev = mNow;
            r.stats.put("bytesAfterInsert", dirSizeBytes(dir));

            // --- ФАЗА: пакетные чтения (медиана повторов; rep -1 = JIT-прогрев)
            final List<Long> findNanos = new ArrayList<>();
            for (int rep = -1; rep < FIND_REPS; rep++) {
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                long f0 = System.nanoTime();
                try {
                    for (int i = 0; i < FIND_OPS; i++) {
                        final Object o = storage.load(cl, shuffled.get(SHARED_RND.nextInt(n)), tx);
                        blackhole += System.identityHashCode(o);
                    }
                    storage.commitTransaction(tx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
                findNanos.add(System.nanoTime() - f0);
            }
            final long findMedian = median(findNanos);
            r.phases.put("findBatchUsPerOp", findMedian / (double) FIND_OPS / 1000.0);
            r.phases.put("findBatchOpsPerS", FIND_OPS / (findMedian / 1e9));

            // --- ФАЗА: чтение с begin/commit на операцию (реалистичный API-паттерн)
            ph0 = System.nanoTime();
            for (int i = 0; i < TXFIND_OPS; i++) {
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                try {
                    final Object o = storage.load(cl, shuffled.get(SHARED_RND.nextInt(n)), tx);
                    blackhole += System.identityHashCode(o);
                    storage.commitTransaction(tx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
            }
            r.phases.put("findTxPerOpOpsPerS", TXFIND_OPS / ((System.nanoTime() - ph0) / 1e9));
            r.heapUsedMb = probeHeapMb();

            // --- пик файловой статистики: сразу после вставки (полный набор данных)
            fillStats(r, dir, cl.getSimpleName(), "peak");

            // --- ФАЗА: обновление всех (перезапись фрагментов/узлов)
            ph0 = System.nanoTime();
            for (int from = 0; from < n; from += BATCH) {
                final int to = Math.min(from + BATCH, n);
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                try {
                    for (int i = from; i < to; i++) {
                        final Object o = storage.load(cl, ids.get(i), tx);
                        setPayload(o, payload(ids.get(i), "upd"));
                        storage.update(o, tx);
                    }
                    storage.commitTransaction(tx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
            }
            r.phases.put("updateAllMs", ms(System.nanoTime() - ph0));
            mNow = metrics(storage);
            r.metricDeltas.put("update", delta(mNow, mPrev));
            mPrev = mNow;

            // --- ФАЗА: reopen (shutdown + открытие) + холодные чтения (полный набор данных!)
            storage.shutdown();
            storage = null;
            ph0 = System.nanoTime();
            storage = XdStorageProvider.newOrGetFileStorage(storageName, dir.toString(), frag);
            r.phases.put("reopenMs", ms(System.nanoTime() - ph0));

            if (scanPhase) {
                // холодный полный проход (первая загрузка всех файлов фрагментов)
                ph0 = System.nanoTime();
                IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                try {
                    final Collection<T> all = scanAll(storage, cl, tx);
                    blackhole += all.size();
                    storage.commitTransaction(tx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
                r.phases.put("coldScanMs", ms(System.nanoTime() - ph0));

                final List<Long> scanNanos = new ArrayList<>();
                for (int rep = 0; rep < SCAN_REPS; rep++) {
                    tx = storage.beginTransaction(TX_TIMEOUT);
                    final long s0 = System.nanoTime();
                    try {
                        final Collection<T> all = scanAll(storage, cl, tx);
                        blackhole += all.size();
                        storage.commitTransaction(tx);
                    } catch (final Exception e) {
                        storage.rollbackTransaction(tx);
                        throw e;
                    }
                    scanNanos.add(System.nanoTime() - s0);
                }
                r.phases.put("warmScanMs", ms(median(scanNanos)));
            }

            // холодный первый point-lookup + холодная партия
            ph0 = System.nanoTime();
            IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
            try {
                final Object o = storage.load(cl, shuffled.get(0), tx);
                blackhole += System.identityHashCode(o);
                storage.commitTransaction(tx);
            } catch (final Exception e) {
                storage.rollbackTransaction(tx);
                throw e;
            }
            r.phases.put("coldFirstFindMs", ms(System.nanoTime() - ph0));

            ph0 = System.nanoTime();
            tx = storage.beginTransaction(TX_TIMEOUT);
            try {
                for (int i = 0; i < COLD_FIND_OPS; i++) {
                    final Object o = storage.load(cl, shuffled.get(SHARED_RND.nextInt(n)), tx);
                    blackhole += System.identityHashCode(o);
                }
                storage.commitTransaction(tx);
            } catch (final Exception e) {
                storage.rollbackTransaction(tx);
                throw e;
            }
            r.phases.put("coldFindUsPerOp", (System.nanoTime() - ph0) / (double) COLD_FIND_OPS / 1000.0);

            // --- ФАЗА: удаление всех (merge ребалансировки для B+Дерева).
            // Идет ПОСЛЕ reopen: метрики per-storage у нового инстанса свои — свежий снапшот.
            // Нюанс: у B+Дерева после reopen загрузки дают null (дефект read-after-reopen
            // BTree-структур, см. PERFORMANCE-RECOMMENDATIONS) — такие ключи считаем
            // в deleteLoadMisses, чтобы конфигурация завершилась и отдала статистику.
            mPrev = metrics(storage);
            long deleteMisses = 0;
            ph0 = System.nanoTime();
            for (int from = 0; from < n; from += BATCH) {
                final int to = Math.min(from + BATCH, n);
                final IXdStorageTransaction txDel = storage.beginTransaction(TX_TIMEOUT);
                try {
                    for (int i = from; i < to; i++) {
                        final Object o = storage.load(cl, ids.get(i), txDel);
                        if (o == null) {
                            deleteMisses++;
                            continue;
                        }
                        storage.delete(o, txDel);
                    }
                    storage.commitTransaction(txDel);
                } catch (final Exception e) {
                    storage.rollbackTransaction(txDel);
                    throw e;
                }
            }
            r.phases.put("deleteAllMs", ms(System.nanoTime() - ph0));
            r.stats.put("deleteLoadMisses", deleteMisses);
            mNow = metrics(storage);
            r.metricDeltas.put("delete", delta(mNow, mPrev));

            // --- файловая статистика (после удаления) + аномалии движка
            fillStats(r, dir, cl.getSimpleName(), "final");
            fillEngineEvents(r);
        } catch (final Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.out.println("[ERROR] config " + r.entity + " frag=" + frag + ": " + r.error);
            e.printStackTrace(System.out);
        } finally {
            if (storage != null) {
                try {
                    storage.shutdown();
                } catch (final Exception e) {
                    System.out.println("[WARN] shutdown failed: " + e);
                }
            }
            r.wallTotalMs = (System.nanoTime() - wall0) / 1_000_000L;
            deleteRecursively(dir);
        }
        log(String.format("config %s (t=%d frag=%d) done in %.1fs %s",
                r.entity, r.t, r.frag, r.wallTotalMs / 1000.0, r.error == null ? "" : "ERROR=" + r.error));
    }

    // =========================================================================
    // ОДНА КОНФИГУРАЦИЯ: поисковый индекс (t варьируется)
    // =========================================================================

    private static void runSearchConfig(final Class<?> cl, final int n, final SweepResult r) throws Exception {
        final String runTag = r.suite + "-" + cl.getSimpleName() + "-" + System.nanoTime();
        final Path dir = Paths.get("target", "sweep", "run-" + runTag);
        deleteRecursively(dir);
        final String storageName = "sweep-" + runTag;
        final int frag = 512;
        final long wall0 = System.nanoTime();
        IXdFileStorage storage = null;
        try {
            long t0 = System.nanoTime();
            storage = XdStorageProvider.newOrGetFileStorage(storageName, dir.toString(), frag);
            r.phases.put("openMs", ms(System.nanoTime() - t0));

            final List<Long> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                ids.add((long) i);
            final List<Long> shuffled = new ArrayList<>(ids);
            Collections.shuffle(shuffled, new Random(SEED));

            // --- предзагрузка индексированных сущностей (пакетные коммиты)
            Map<String, Long> mPrev = metrics(storage);
            final List<Long> commitNanos = new ArrayList<>();
            long ph0 = System.nanoTime();
            for (int from = 0; from < n; from += BATCH) {
                final int to = Math.min(from + BATCH, n);
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                try {
                    for (int i = from; i < to; i++)
                        storage.save(newShip(cl, (long) i), tx);
                    final long c0 = System.nanoTime();
                    storage.commitTransaction(tx);
                    commitNanos.add(System.nanoTime() - c0);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
            }
            final long insertNanos = System.nanoTime() - ph0;
            r.phases.put("preloadMs", ms(insertNanos));
            r.phases.put("preloadOpsPerS", n / (insertNanos / 1e9));
            r.phases.put("commitMeanMs", mean(commitNanos));
            Map<String, Long> mNow = metrics(storage);
            r.metricDeltas.put("preload", delta(mNow, mPrev));
            r.stats.put("bytesAfterPreload", dirSizeBytes(dir));

            // --- EQUALS по primary (точный путь Б+Дерева поискового индекса)
            final List<Long> findNanos = new ArrayList<>();
            for (int rep = -1; rep < FIND_REPS; rep++) {
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                final long f0 = System.nanoTime();
                try {
                    for (int i = 0; i < FIND_OPS; i++) {
                        final String name = "Ship-" + SHARED_RND.nextInt(n);
                        final Object found = search(storage, cl, equalsQuery(name), tx);
                        blackhole += System.identityHashCode(found);
                    }
                    storage.commitTransaction(tx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
                findNanos.add(System.nanoTime() - f0);
            }
            final long findMedian = median(findNanos);
            r.phases.put("searchEqualsUsPerOp", findMedian / (double) FIND_OPS / 1000.0);
            r.phases.put("searchEqualsOpsPerS", FIND_OPS / (findMedian / 1e9));

            // --- EQUALS + дочерний критерий (оценка по уже прочитанной записи индекса)
            ph0 = System.nanoTime();
            final IXdStorageTransaction txChild = storage.beginTransaction(TX_TIMEOUT);
            try {
                for (int i = 0; i < 1000; i++) {
                    final String name = "Ship-" + SHARED_RND.nextInt(n);
                    final Object found = search(storage, cl, childQuery(name), txChild);
                    blackhole += System.identityHashCode(found);
                }
                storage.commitTransaction(txChild);
            } catch (final Exception e) {
                storage.rollbackTransaction(txChild);
                throw e;
            }
            r.phases.put("searchChildUsPerOp", (System.nanoTime() - ph0) / 1000.0 / 1000.0);

            // --- сканирующий запрос (NOT_EQUALS: полный обход цепи листьев)
            final List<Long> scanNanos = new ArrayList<>();
            for (int rep = -1; rep < 1; rep++) {
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                final long s0 = System.nanoTime();
                try {
                    final Object found = search(storage, cl, scanQuery(), tx);
                    blackhole += System.identityHashCode(found);
                    storage.commitTransaction(tx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
                if (rep >= 0)
                    scanNanos.add(System.nanoTime() - s0);
            }
            r.phases.put("searchScanMs", ms(median(scanNanos)));
            r.heapUsedMb = probeHeapMb();

            // --- reopen + холодный поиск
            storage.shutdown();
            storage = null;
            ph0 = System.nanoTime();
            storage = XdStorageProvider.newOrGetFileStorage(storageName, dir.toString(), frag);
            r.phases.put("reopenMs", ms(System.nanoTime() - ph0));

            ph0 = System.nanoTime();
            final IXdStorageTransaction txCold = storage.beginTransaction(TX_TIMEOUT);
            try {
                final Object found = search(storage, cl, equalsQuery("Ship-" + shuffled.get(0)), txCold);
                blackhole += System.identityHashCode(found);
                storage.commitTransaction(txCold);
            } catch (final Exception e) {
                storage.rollbackTransaction(txCold);
                throw e;
            }
            r.phases.put("coldFirstSearchMs", ms(System.nanoTime() - ph0));

            ph0 = System.nanoTime();
            final IXdStorageTransaction txColdBatch = storage.beginTransaction(TX_TIMEOUT);
            try {
                for (int i = 0; i < COLD_FIND_OPS; i++) {
                    final Object found = search(storage, cl, equalsQuery("Ship-" + SHARED_RND.nextInt(n)), txColdBatch);
                    blackhole += System.identityHashCode(found);
                }
                storage.commitTransaction(txColdBatch);
            } catch (final Exception e) {
                storage.rollbackTransaction(txColdBatch);
                throw e;
            }
            r.phases.put("coldSearchUsPerOp", (System.nanoTime() - ph0) / (double) COLD_FIND_OPS / 1000.0);

            fillStats(r, dir, cl.getSimpleName(), "peak");
            fillEngineEvents(r);
        } catch (final Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.out.println("[ERROR] search config " + r.entity + ": " + r.error);
            e.printStackTrace(System.out);
        } finally {
            if (storage != null) {
                try {
                    storage.shutdown();
                } catch (final Exception e) {
                    System.out.println("[WARN] shutdown failed: " + e);
                }
            }
            r.wallTotalMs = (System.nanoTime() - wall0) / 1_000_000L;
            deleteRecursively(dir);
        }
        log(String.format("search config %s (t=%d) done in %.1fs %s",
                r.entity, r.t, r.wallTotalMs / 1000.0, r.error == null ? "" : "ERROR=" + r.error));
    }

    // =========================================================================
    // ХЕЛПЕРЫ
    // =========================================================================

    private static <T> Collection<T> scanAll(final IXdFileStorage storage, final Class<T> cl,
                                             final IXdStorageTransaction tx) throws Exception {
        return storage.load(cl, (IXdStoragePredicate<T>) o -> true, tx);
    }

    private static Object newEntity(final Class<?> cl, final String id, final String tag, final int i) throws Exception
            {
        final Object o = cl.getDeclaredConstructor().newInstance();
        cl.getMethod("setId", String.class).invoke(o, id);
        cl.getMethod("setPayload", String.class).invoke(o, payload(id, tag));
        cl.getMethod("setNumber", long.class).invoke(o, (long) i);
        return o;
    }

    private static void setPayload(final Object o, final String value) throws Exception {
        o.getClass().getMethod("setPayload", String.class).invoke(o, value);
    }

    private static String payload(final String id, final String tag) {
        final String base = tag + "-" + id + "-";
        final StringBuilder sb = new StringBuilder(PAYLOAD_LEN);
        while (sb.length() < PAYLOAD_LEN)
            sb.append(base);
        sb.setLength(PAYLOAD_LEN);
        return sb.toString();
    }

    private static Object newShip(final Class<?> cl, final long i) throws Exception {
        final Object ship = cl.getDeclaredConstructor().newInstance();
        cl.getMethod("setId", Long.class).invoke(ship, i);
        cl.getMethod("setName", String.class).invoke(ship, "Ship-" + i);
        cl.getMethod("setTonnage", int.class).invoke(ship, (int) (i % 50));
        final Class<?> crewCl = Class.forName("io.github.xdstorage.performance.sweep.SweepSearchCrewMember");
        final Object captain = crewCl.getDeclaredConstructor(Long.class, String.class).newInstance(i * 10, "Rank-" + (i
                % 5));
        cl.getMethod("setCaptain", crewCl).invoke(ship, captain);
        final Method add = cl.getMethod("addCrewMember", crewCl);
        add.invoke(ship, crewCl.getDeclaredConstructor(Long.class, String.class).newInstance(i * 10 + 1, "Rank-3"));
        add.invoke(ship, crewCl.getDeclaredConstructor(Long.class, String.class).newInstance(i * 10 + 2, "Rank-" + (i %
                5)));
        return ship;
    }

    private static XdStorageSearchQuery equalsQuery(final String name) {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, name), null);
    }

    private static XdStorageSearchQuery childQuery(final String name) {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, name),
                new XdStorageChildCriterion("crew", SweepSearchCrewMember.class,
                        "rank", IXdStorageChildCriterionOperator.CONTAINS, "Rank-3"));
    }

    private static XdStorageSearchQuery scanQuery() {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS,
                "@@none@@"), null);
    }

    @SuppressWarnings("unchecked")
    private static Object search(final IXdFileStorage storage, final Class<?> cl, final XdStorageSearchQuery query,
                                 final IXdStorageTransaction tx) throws Exception {
        return storage.load(cl, "sweep_idx", query, tx);
    }

    private static Map<String, Long> metrics(final IXdFileStorage storage) {
        return new TreeMap<>(storage.getMetrics().snapshot());
    }

    private static Map<String, Long> delta(final Map<String, Long> now, final Map<String, Long> before) {
        final Map<String, Long> d = new TreeMap<>();
        for (final String name : DELTA_COUNTERS)
            d.put(name, now.getOrDefault(name, 0L) - before.getOrDefault(name, 0L));
        final Long outs = d.get(XdStorageMetrics.RESOURCE_WRITE_OUTS);
        final Long nanos = d.get(XdStorageMetrics.RESOURCE_WRITE_OUT_NANOS);
        if (outs != null && nanos != null)
            d.put("writeOutAvgUsPerFile", outs <= 0 ? 0L : nanos / outs / 1000L);
        return d;
    }

    private static void fillEngineEvents(final SweepResult r) {
        final Map<String, Long> snap = new TreeMap<>(XdStorageMetrics.engineEvents().snapshot());
        for (final Map.Entry<String, Long> e : snap.entrySet())
            r.engineEvents.put(e.getKey(), e.getValue());
    }

    private static void fillStats(final SweepResult r, final Path dir, final String entitySimpleName,
                                  final String prefix) throws IOException {
        long filesTotal = 0;
        long bytesTotal = 0;
        long entityFiles = 0;
        long entityBytes = 0;
        long treeFiles = 0;
        long treeBytes = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            final List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(files::add);
            for (final Path f : files) {
                final String name = f.getFileName().toString();
                if (name.endsWith(".lock"))
                    continue;
                final long size = Files.size(f);
                filesTotal++;
                bytesTotal += size;
                final Path rel = dir.relativize(f);
                final List<String> parts = new ArrayList<>();
                for (int i = 0; i < rel.getNameCount(); i++)
                    parts.add(rel.getName(i).toString());
                // structure: <root>/data/<Class>/<file> or <root>/structure/...
                if (parts.size() >= 3 && "data".equals(parts.get(0))) {
                    final String owner = parts.get(1);
                    if (entitySimpleName.equals(owner)) {
                        entityFiles++;
                        entityBytes += size;
                    } else if ("XdStorageBTree".equals(owner) || "XdStorageBTreeNode".equals(owner)) {
                        treeFiles++;
                        treeBytes += size;
                    }
                }
            }
        }
        r.stats.put(prefix + "FilesTotal", filesTotal);
        r.stats.put(prefix + "BytesTotal", bytesTotal);
        r.stats.put(prefix + "EntityFiles", entityFiles);
        r.stats.put(prefix + "EntityBytes", entityBytes);
        r.stats.put(prefix + "TreeFiles", treeFiles);
        r.stats.put(prefix + "TreeBytes", treeBytes);
    }

    private static long dirSizeBytes(final Path dir) throws IOException {
        long total = 0;
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (final Path f : files)
            total += Files.size(f);
        return total;
    }

    private static double probeHeapMb() {
        try {
            System.gc();
            Thread.sleep(150);
            System.gc();
            Thread.sleep(150);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0;
    }

    private static double ms(final long nanos) {
        return nanos / 1e6;
    }

    private static long median(final List<Long> values) {
        final List<Long> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy.get(copy.size() / 2);
    }

    private static double mean(final List<Long> values) {
        long sum = 0;
        for (final long v : values)
            sum += v;
        return values.isEmpty() ? 0 : sum / (double) values.size() / 1e6;
    }

    private static double max(final List<Long> values) {
        long m = 0;
        for (final long v : values)
            m = Math.max(m, v);
        return m / 1e6;
    }

    private static void deleteRecursively(final Path dir) {
        if (!Files.exists(dir))
            return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException e) {
                    System.out.println("[WARN] cannot delete " + p + ": " + e);
                }
            });
        } catch (final IOException e) {
            System.out.println("[WARN] walk failed for " + dir + ": " + e);
        }
    }

    private static void log(final String message) {
        System.out.printf("[%7.1fs] %s%n", (System.nanoTime() - START) / 1e9, message);
        System.out.flush();
    }

    // =========================================================================
    // ВЫВОД: JSON + CSV + консольная таблица
    // =========================================================================

    private static void writeJson(final Path out, final String tag, final List<SweepResult> results) throws IOException
            {
        final StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\n");
        sb.append("  \"tag\": \"").append(tag).append("\",\n");
        sb.append("  \"recorded\": \"").append(ZonedDateTime.now()).append("\",\n");
        sb.append("  \"format\": \"YAML\",\n");
        sb.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            appendResultJson(sb, results.get(i), i == results.size() - 1);
        }
        sb.append("  ]\n}\n");
        Files.write(out.resolve(tag + ".json"), sb.toString().getBytes("UTF-8"));
    }

    private static void appendResultJson(final StringBuilder sb, final SweepResult r, final boolean last) {
        sb.append("    {\n");
        sb.append("      \"suite\": \"").append(r.suite).append("\",\n");
        sb.append("      \"entity\": \"").append(r.entity).append("\",\n");
        sb.append("      \"t\": ").append(r.t).append(",\n");
        sb.append("      \"frag\": ").append(r.frag).append(",\n");
        sb.append("      \"n\": ").append(r.n).append(",\n");
        sb.append("      \"jvm\": \"").append(r.jvm).append("\",\n");
        sb.append("      \"error\": ").append(r.error == null ? "null" : "\"" + r.error.replace("\"", "'") + "\"")
                .append(",\n");
        sb.append("      \"heapUsedMb\": ").append(round(r.heapUsedMb)).append(",\n");
        sb.append("      \"wallTotalMs\": ").append(r.wallTotalMs).append(",\n");
        sb.append("      \"phases\": {").append(mapJson(r.phases, false)).append("},\n");
        sb.append("      \"stats\": {").append(mapJsonLong(r.stats, false)).append("},\n");
        sb.append("      \"metricDeltas\": {");
        final List<String> names = new ArrayList<>(r.metricDeltas.keySet());
        for (int i = 0; i < names.size(); i++) {
            final String phase = names.get(i);
            sb.append("\n        \"").append(phase).append("\": {").append(mapJsonLong(r.metricDeltas.get(phase), true))
                    .append("}");
            sb.append(i == names.size() - 1 ? "\n      " : ",");
        }
        sb.append("},\n");
        sb.append("      \"engineEvents\": {").append(mapJsonLong(r.engineEvents, true)).append("}\n");
        sb.append("    }").append(last ? "\n" : ",\n");
    }

    private static String mapJson(final Map<String, Double> map, final boolean inline) {
        final StringBuilder sb = new StringBuilder();
        final List<String> keys = new ArrayList<>(map.keySet());
        for (int i = 0; i < keys.size(); i++) {
            sb.append(inline ? "" : "\n        ");
            sb.append('"').append(keys.get(i)).append("\": ").append(round(map.get(keys.get(i))));
            if (i < keys.size() - 1)
                sb.append(",");
        }
        if (!inline && !keys.isEmpty())
            sb.append("\n      ");
        return sb.toString();
    }

    private static String mapJsonLong(final Map<String, Long> map, final boolean inline) {
        final StringBuilder sb = new StringBuilder();
        final List<String> keys = new ArrayList<>(map.keySet());
        for (int i = 0; i < keys.size(); i++) {
            sb.append(inline ? "" : "\n        ");
            sb.append('"').append(keys.get(i)).append("\": ").append(map.get(keys.get(i)));
            if (i < keys.size() - 1)
                sb.append(",");
        }
        return sb.toString();
    }

    private static double round(final double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static void writeCsv(final Path out, final String tag, final List<SweepResult> results) throws IOException {
        final List<String> phaseKeys = new ArrayList<>();
        final List<String> statKeys = new ArrayList<>();
        final List<String> engineKeys = new ArrayList<>();
        for (final SweepResult r : results) {
            for (final String k : r.phases.keySet())
                if (!phaseKeys.contains(k))
                    phaseKeys.add(k);
            for (final String k : r.stats.keySet())
                if (!statKeys.contains(k))
                    statKeys.add(k);
            for (final String k : r.engineEvents.keySet())
                if (!engineKeys.contains(k))
                    engineKeys.add(k);
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("suite,entity,t,frag,n,jvm,error,heapUsedMb,wallTotalMs");
        for (final String k : phaseKeys)
            sb.append(',').append(k);
        for (final String k : statKeys)
            sb.append(',').append(k);
        for (final String k : engineKeys)
            sb.append(',').append(k);
        sb.append('\n');
        for (final SweepResult r : results) {
            sb.append(r.suite).append(',').append(r.entity).append(',').append(r.t).append(',')
                    .append(r.frag).append(',').append(r.n).append(',')
                    .append('"').append(r.jvm).append('"').append(',')
                    .append(r.error == null ? "" : r.error.replace(",", ";")).append(',')
                    .append(round(r.heapUsedMb)).append(',').append(r.wallTotalMs);
            for (final String k : phaseKeys)
                sb.append(',').append(r.phases.containsKey(k) ? round(r.phases.get(k)) : "");
            for (final String k : statKeys)
                sb.append(',').append(r.stats.containsKey(k) ? r.stats.get(k) : "");
            for (final String k : engineKeys)
                sb.append(',').append(r.engineEvents.containsKey(k) ? r.engineEvents.get(k) : "");
            sb.append('\n');
        }
        Files.write(out.resolve(tag + ".csv"), sb.toString().getBytes("UTF-8"));
    }

    private static void printTable(final String tag, final List<SweepResult> results) {
        System.out.println();
        System.out.println("=== " + tag + " ===");
        System.out.printf("%-24s %5s %6s | %9s %10s %10s | %10s %10s | %8s %10s | %7s%n",
                "entity", "t", "frag", "insertMs", "insOps/s", "commitMs",
                "findUsOp", "findTx/s", "files", "bytes(MB)", "heapMB");
        for (final SweepResult r : results) {
            System.out.printf("%-24s %5d %6d | %9.0f %10.0f %10.1f | %10.2f %10.0f | %8d %10.2f | %7.0f %s%n",
                    r.entity, r.t, r.frag,
                    first(d(r, "insertMs"), d(r, "preloadMs")),
                    first(d(r, "insertOpsPerS"), d(r, "preloadOpsPerS")), d(r, "commitMeanMs"),
                    first(d(r, "findBatchUsPerOp"), d(r, "searchEqualsUsPerOp")),
                    first(d(r, "findBatchOpsPerS"), d(r, "searchEqualsOpsPerS")),
                    r.stats.get("peakFilesTotal"), r.stats.get("peakBytesTotal") / 1024.0 / 1024.0,
                    r.heapUsedMb, r.error == null ? "" : " ERROR");
        }
        System.out.println();
    }

    private static double d(final SweepResult r, final String key) {
        final Double v = r.phases.get(key);
        return v == null ? Double.NaN : v;
    }

    private static double first(final double a, final double b) {
        return Double.isNaN(a) ? b : a;
    }
}
