package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.SearchEngineCrewMember;
import io.github.xdstorage.search.SearchEngineShip;
import io.github.xdstorage.search.query.IXdStorageChildCriterionOperator;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageChildCriterion;
import io.github.xdstorage.search.query.XdStorageCriterion;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMH-бенчмарк поискового движка СУБД СТРОГО через публичный фасад XdStorage
 * (save/load/update/delete + load по индексу с XdStorageSearchQuery).
 *
 * Измеряемые сценарии:
 *  - базовая линия: load по идентификатору через id-индекс (без поискового движка);
 *  - чтения: EQUALS по primary (путь Б+Дерева), EQUALS + критерий поля, EQUALS +
 *    дочерний критерий, OR-объединение, сканирующие пути (NOT_EQUALS и критерий без primary);
 *  - запись: сохранение объекта с индексацией дочерних коллекций + удаление,
 *    перенос ключа при переименовании (update со сменой primary-значения).
 *
 * Запуск: main из IDE, либо
 *   mvn test-compile && java -cp target/test-classes:target/classes:... \
 *        io.github.xdstorage.performance.XdStorageSearchEngineBenchmark [quick]
 * Аргумент "quick" - прогрев/замер по 1 итерации в 1 секунду для дымовой проверки.
 *
 * Набор данных: MAX_THREADS диапазонов по SHIPS_PER_THREAD кораблей с экипажем
 * предзагружаются СЕРИАЛЬНО до старта потоков (параллельная предзагрузка ловит
 * известный "рассинхрон структуры" валидатора Б+Дерева); сканирующие бенчмарки
 * однопоточны и обходят предзагруженный набор (не пустую базу!).
 * СУЩНОСТИ ОБЯЗАНЫ быть верхнего уровня (см. SearchEngineShip - codegen оберток СУБД).
 * Имя и каталог хранилища уникальны на прогон (R-50: реестр провайдера и устаревший диск).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 2)
@Threads(4)
public class XdStorageSearchEngineBenchmark {

    private static final int SHIPS_PER_THREAD = 100;
    private static final String ID_INDEX_NAME = "ship_idx";
    private static final long TX_TIMEOUT = 15000L;

    /** Состояние потока: свой диапазон идентификаторов и свои корабли - без конфликтов ключей. */
    @State(Scope.Thread)
    public static class ThreadState {
        private int threadIdx;
        private long idBase;
        private long nextFreshId;
        private final List<Long> sampleIds = new ArrayList<>();
        private final List<String> sampleNames = new ArrayList<>();
        private SearchEngineShip renameShip;
        private boolean renameFlip;
        private int cursor;

        @Setup(Level.Trial)
        public void setUp(BenchmarkSharedState shared) {
            // ЧИСТО детерминированное состояние: данные диапазона уже предзагружены
            // СЕРИАЛЬНО в BenchmarkSharedState.setUp до старта рабочих потоков
            // (параллельная предзагрузка ловит известный "рассинхрон структуры" валидатора)
            threadIdx = shared.threadAllocator.getAndIncrement();
            idBase = (long) threadIdx * 1_000_000L;
            nextFreshId = idBase + 10_000_000L;

            for (int i = 0; i < SHIPS_PER_THREAD; i++) {
                sampleIds.add(idBase + i);
                sampleNames.add("Ship-" + threadIdx + "-" + i);
            }
            renameShip = BenchmarkSharedState.newShip(idBase + 500, "Rename-A-" + threadIdx, 7, idBase + 99000);
        }

        private int nextIndex() {
            cursor = (cursor + 1) % SHIPS_PER_THREAD;
            return cursor;
        }

        private long sampleId() {
            return sampleIds.get(nextIndex());
        }

        private String sampleName() {
            return sampleNames.get(cursor);
        }

        private int sampleTonnage() {
            // тоннаж корабля с индексом i при предзагрузке = i % 50
            return cursor % 50;
        }

        private String sampleName(final int i) {
            return sampleNames.get(i);
        }
    }

    /** Общие ресурсы: хранилище и распределитель диапазонов идентификаторов потоков. */
    @State(Scope.Benchmark)
    public static class BenchmarkSharedState {
        private static final int MAX_THREADS = 4;

        private final AtomicInteger threadAllocator = new AtomicInteger();
        private IXdFileStorage storage;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            // R-50: уникальные имя и каталог на прогон - реестр провайдера и устаревший диск не мешают
            final String unique = Long.toString(System.nanoTime(), Character.MAX_RADIX);
            storage = XdStorageProvider.newOrGetFileStorage("search-bench-" + unique, "./target/search_bench_" + unique, 512);

            // СЕРИАЛЬНАЯ предзагрузка всех диапазонов до старта рабочих потоков:
            // один транзакционный коммит на диапазон, нулевая конкуренция за Б+Деревья
            for (int t = 0; t < MAX_THREADS; t++) {
                final long idBase = (long) t * 1_000_000L;
                final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
                try {
                    for (int i = 0; i < SHIPS_PER_THREAD; i++) {
                        storage.save(newShip(idBase + i, "Ship-" + t + "-" + i, i % 50, idBase + 1000 + i * 10), tx);
                    }
                    storage.save(newShip(idBase + 500, "Rename-A-" + t, 7, idBase + 99000), tx);
                    storage.commitTransaction(tx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(tx);
                    throw e;
                }
            }
        }

        private static SearchEngineShip newShip(final long id, final String name, final int tonnage, final long crewBase) {
            final SearchEngineShip ship = new SearchEngineShip();
            ship.setId(id);
            ship.setName(name);
            ship.setTonnage(tonnage);
            ship.setCaptain(new SearchEngineCrewMember(crewBase, "Rank-" + (id % 5)));
            ship.addCrewMember(new SearchEngineCrewMember(crewBase + 1, "Rank-3"));
            ship.addCrewMember(new SearchEngineCrewMember(crewBase + 2, "Rank-" + (id % 5)));
            return ship;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (storage != null) {
                storage.shutdown();
            }
        }
    }

    // =========================================================================
    // ЧТЕНИЕ
    // =========================================================================

    @Benchmark
    public SearchEngineShip loadById_indexBaseline(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final SearchEngineShip ship = shared.storage.load(SearchEngineShip.class, ts.sampleId(), tx);
            shared.storage.commitTransaction(tx);
            return ship;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryEquals(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, ts.sampleName()), null);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryEqualsWithFieldCriterion(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        // критерий согласован с выборкой: тоннаж выбранного корабля = его индекс % 50
        final String name = ts.sampleName();
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, name),
                new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.EQUALS, ts.sampleTonnage()));
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryEqualsWithChildCriterion(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, ts.sampleName()),
                new XdStorageChildCriterion("crew", SearchEngineCrewMember.class,
                        "rank", IXdStorageChildCriterionOperator.CONTAINS, "Rank-3"));
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByOrTwoPrimaries(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        final int i = ts.cursor % SHIPS_PER_THREAD;
        final int j = (i + 1) % SHIPS_PER_THREAD;
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, ts.sampleName(i)), null);
        query.or(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, ts.sampleName(j)), null);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    @Threads(1)
    public java.util.Collection<SearchEngineShip> searchByScanNotEqualsPrimary(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "@@none@@"), null);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    @Threads(1)
    public java.util.Collection<SearchEngineShip> searchByScanNullPrimaryFieldCriterion(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        final XdStorageSearchQuery query = new XdStorageSearchQuery(null,
                new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.EQUALS, 7));
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    // =========================================================================
    // ЗАПИСЬ (однопоточно: коммиты переписывают файлы ресурсов целиком -
    // параллельные писатели дали бы драку блокировок, а не метрику движка)
    // =========================================================================

    // =========================================================================
    // FP-1a (docs/tasks/2.1.0/09): range operators on the ordered search index.
    // The bounded walk visits the chain from the first leaf and TERMINATES past the
    // upper bound, so a selective upper-bounded range is sub-linear vs the scan.
    // =========================================================================

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryBetweenSelective(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        // lexicographic window [Ship-{t}-0 .. Ship-{t}-2]: a small prefix of the thread's
        // own 100 names; the walk aborts early past the upper bound
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new XdStorageRange("Ship-" + ts.threadIdx + "-0", "Ship-" + ts.threadIdx + "-2")), null);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryBetweenWide(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        // nearly the thread's whole band: the upper bound sits at the end of the range,
        // so the walk cost approaches the scan — the honest "wide range" row
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new XdStorageRange("Ship-" + ts.threadIdx + "-0", "Ship-" + ts.threadIdx + "-99")), null);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryGT(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        // lower bound only: no early termination — the honest "no-win without an upper
        // bound" row (cost equals the scan; the operator contract is what is exercised)
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GT, "Ship-" + ts.threadIdx + "-95"), null);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryBetweenPagedLimit50(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        // FP-1b (docs/tasks/2.1.0/10): the SAME wide window as searchByPrimaryBetweenWide with
        // LIMIT 50 — the limit is pushed down into the ordered walk, which terminates as soon
        // as the page is complete: 50 objects fetched instead of 100, the walk-termination win
        // of combining a range with pagination
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new XdStorageRange("Ship-" + ts.threadIdx + "-0", "Ship-" + ts.threadIdx + "-99")), null)
                .limit(50);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public java.util.Collection<SearchEngineShip> searchByPrimaryIn(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        // IN = ONE membership-filtered walk (not k point lookups)
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN,
                        java.util.Arrays.asList("Ship-" + ts.threadIdx + "-0", "Ship-" + ts.threadIdx + "-25",
                                "Ship-" + ts.threadIdx + "-50", "Ship-" + ts.threadIdx + "-75", "Ship-" + ts.threadIdx + "-99")), null);
        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            final java.util.Collection<SearchEngineShip> found =
                    shared.storage.load(SearchEngineShip.class, ID_INDEX_NAME, query, tx);
            shared.storage.commitTransaction(tx);
            return found;
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    @Threads(1)
    public void saveAndDeleteIndexedShipWithCrew(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        final long freshId = ts.nextFreshId++;
        final SearchEngineShip ship = new SearchEngineShip();
        ship.setId(freshId);
        ship.setName("Fresh-" + freshId);
        ship.setTonnage((int) (freshId % 50));
        ship.setCaptain(new SearchEngineCrewMember(freshId * 10, "Rank-" + (freshId % 5)));
        ship.addCrewMember(new SearchEngineCrewMember(freshId * 10 + 1, "Rank-3"));

        final IXdStorageTransaction saveTx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            shared.storage.save(ship, saveTx);
            shared.storage.commitTransaction(saveTx);
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(saveTx);
            throw e;
        }

        final IXdStorageTransaction deleteTx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            shared.storage.delete(ship, deleteTx);
            shared.storage.commitTransaction(deleteTx);
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(deleteTx);
            throw e;
        }
    }

    @Benchmark
    @Threads(1)
    public void updateShipRenameSearchKey(final BenchmarkSharedState shared, final ThreadState ts) throws Exception {
        ts.renameFlip = !ts.renameFlip;
        ts.renameShip.setName((ts.renameFlip ? "Rename-B-" : "Rename-A-") + ts.threadIdx);

        final IXdStorageTransaction tx = shared.storage.beginTransaction(TX_TIMEOUT);
        try {
            shared.storage.update(ts.renameShip, tx);
            shared.storage.commitTransaction(tx);
        } catch (final Exception e) {
            shared.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    public static void main(final String[] args) throws Exception {
        final boolean quick = args.length > 0 && "quick".equalsIgnoreCase(args[0]);
        final ChainedOptionsBuilder builder = new OptionsBuilder()
                .include(XdStorageSearchEngineBenchmark.class.getSimpleName());
        final Options opt;
        if (quick) {
            opt = builder.warmupIterations(1).warmupTime(TimeValue.seconds(1))
                    .measurementIterations(1).measurementTime(TimeValue.seconds(1)).build();
        } else {
            opt = builder.build();
        }
        new Runner(opt).run();
    }
}
