package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * BENCH (docs/tasks/2.1.0/13): the canonical CORE scenarios — the re-runnable shape of the
 * numbers that previously shipped as one-off addenda. Part of the unified estate: run via
 * {@code scripts/run-benchmarks.sh [quick|full]} (the script owns the conditions protocol —
 * pinned JDK, quiet-load gate, WARN-console logging config, YAML format, JSON results +
 * run manifest); this class only owns the scenarios:
 *
 *  - readOnlyTx — begin → load(id) → commit: the B2 headline (docs/tasks/2.1.0/08),
 *    previously a one-off sweep run + BENCHMARKS addendum;
 *  - writeDataHeavy — journalWrites on/off A/B on a fragment-heavy pattern (wide entity,
 *    small fragments): the missing half of the W-3 verdict (the W-3 addendum measured an
 *    index-heavy pattern and honestly showed no win);
 *  - writeDurable — durableWrites on/off: the S-2 fsync cost, never canonically recorded;
 *  - commitBatch — 10/100/500/1000 objects per transaction: the commit-cost curve.
 *
 * Naming/config discipline: surefire-invisible (not a *Test class); YAML format; disclosed
 * config flags in the manifest and in BENCHMARKS.md (fragment size 25 = fragment-heavy);
 * FP-1 query shapes stay in XdStorageSearchEngineBenchmark — the baseline document absorbs
 * their rows into one canonical table set.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 2)
@Threads(1)
public class XdStorageCanonicalBenchmark {

    private static final long TX_TIMEOUT = 30000L;
    private static final int PRELOAD = 200;
    private static final int FRAGMENT_SIZE = 25; // fragment-heavy: many fragment files (disclosed config)

    @State(Scope.Thread)
    public static class Cursor {
        long nextId = PRELOAD + 1;
        int readCursor;
    }

    /** Fragment-heavy entity: many fields, long strings — the DATA-heavy write pattern. */
    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    public static class WideEntity {
        @XdStorageObjectId
        private Long id;
        private String name;
        private String description;
        private String payloadA;
        private String payloadB;
        private String tags;
        private String origin;
        private Long amountA;
        private Long amountB;
        private Long amountC;
        private Long amountD;
        private int countA;
        private int countB;
        private int countC;
        private int countD;
        private double ratioA;
        private double ratioB;
        private boolean flagA;
        private boolean flagB;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getPayloadA() { return payloadA; }
        public void setPayloadA(String payloadA) { this.payloadA = payloadA; }
        public String getPayloadB() { return payloadB; }
        public void setPayloadB(String payloadB) { this.payloadB = payloadB; }
        public String getTags() { return tags; }
        public void setTags(String tags) { this.tags = tags; }
        public String getOrigin() { return origin; }
        public void setOrigin(String origin) { this.origin = origin; }
        public Long getAmountA() { return amountA; }
        public void setAmountA(Long amountA) { this.amountA = amountA; }
        public Long getAmountB() { return amountB; }
        public void setAmountB(Long amountB) { this.amountB = amountB; }
        public Long getAmountC() { return amountC; }
        public void setAmountC(Long amountC) { this.amountC = amountC; }
        public Long getAmountD() { return amountD; }
        public void setAmountD(Long amountD) { this.amountD = amountD; }
        public int getCountA() { return countA; }
        public void setCountA(int countA) { this.countA = countA; }
        public int getCountB() { return countB; }
        public void setCountB(int countB) { this.countB = countB; }
        public int getCountC() { return countC; }
        public void setCountC(int countC) { this.countC = countC; }
        public int getCountD() { return countD; }
        public void setCountD(int countD) { this.countD = countD; }
        public double getRatioA() { return ratioA; }
        public void setRatioA(double ratioA) { this.ratioA = ratioA; }
        public double getRatioB() { return ratioB; }
        public void setRatioB(double ratioB) { this.ratioB = ratioB; }
        public boolean isFlagA() { return flagA; }
        public void setFlagA(boolean flagA) { this.flagA = flagA; }
        public boolean isFlagB() { return flagB; }
        public void setFlagB(boolean flagB) { this.flagB = flagB; }
    }

    @State(Scope.Benchmark)
    public static class CanonicalState {
        protected IXdFileStorage storage;
        protected String dirName;

        protected void open(final String tag, final XdStorageConfig config) {
            dirName = "canonical-" + tag + "-" + System.nanoTime();
            storage = XdStorageProvider.newOrGetFileStorage(dirName, "./target/" + dirName, FRAGMENT_SIZE, config);
        }

        protected void close() {
            if (storage != null) {
                storage.shutdown();
                storage = null;
            }
        }

        protected static WideEntity newEntity(final long id, final int seed) {
            final WideEntity e = new WideEntity();
            e.setId(id);
            e.setName("Wide-" + id);
            e.setDescription("Description of the wide entity " + id + " with seed " + seed
                    + " — fragment-heavy payload for the canonical write scenarios");
            e.setPayloadA("payload-A/" + seed + "/" + id + "/" + "0123456789012345678901234567890123456789");
            e.setPayloadB("payload-B/" + seed + "/" + id + "/" + "9876543210987654321098765432109876543210");
            e.setTags("tag-" + (seed % 7) + ",tag-" + (seed % 13));
            e.setOrigin("origin-" + (seed % 5));
            e.setAmountA((long) seed * 100 + id);
            e.setAmountB((long) seed * 7);
            e.setAmountC((long) seed * 11);
            e.setAmountD((long) seed * 13);
            e.setCountA(seed % 101);
            e.setCountB(seed % 17);
            e.setCountC(seed % 31);
            e.setCountD(seed % 3);
            e.setRatioA((seed % 100) / 100.0);
            e.setRatioB((seed % 50) / 50.0);
            e.setFlagA(seed % 2 == 0);
            e.setFlagB(seed % 3 == 0);
            return e;
        }
    }

    /** Read-only tx + the two write A/B dimensions share ONE storage per trial (open once). */
    @State(Scope.Benchmark)
    public static class ReadOnlyState extends CanonicalState {
        @Setup(Level.Trial)
        public void setUp() throws Exception {
            open("ro-" + System.nanoTime(), XdStorageConfig.defaults());
            final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
            try {
                for (long i = 1; i <= PRELOAD; i++) {
                    storage.save(CanonicalState.newEntity(i, (int) i), tx);
                }
                storage.commitTransaction(tx);
            } catch (final Exception e) {
                storage.rollbackTransaction(tx);
                throw e;
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            close();
        }
    }

    @State(Scope.Benchmark)
    public static class JournalState extends CanonicalState {
        @Param({"true", "false"})
        public boolean journalWrites;

        @Setup(Level.Trial)
        public void setUp() {
            open("journal-" + journalWrites + "-" + System.nanoTime(),
                    XdStorageConfig.builder().journalWrites(journalWrites).build());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            close();
        }
    }

    @State(Scope.Benchmark)
    public static class DurableState extends CanonicalState {
        @Param({"true", "false"})
        public boolean durableWrites;

        @Setup(Level.Trial)
        public void setUp() {
            open("durable-" + durableWrites + "-" + System.nanoTime(),
                    XdStorageConfig.builder().durableWrites(durableWrites).build());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            close();
        }
    }

    @State(Scope.Benchmark)
    public static class BatchState extends CanonicalState {
        @Param({"10", "100", "500", "1000"})
        public int batchSize;

        @Setup(Level.Trial)
        public void setUp() {
            open("batch-" + batchSize + "-" + System.nanoTime(), XdStorageConfig.defaults());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            close();
        }
    }

    // =========================================================================
    // CORE: read-only transaction throughput — begin → load(id) → commit
    // =========================================================================

    @Benchmark
    public Long readOnlyTx(final ReadOnlyState state, final Cursor cursor) throws Exception {
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            cursor.readCursor = (cursor.readCursor + 1) % PRELOAD;
            final long id = 1 + cursor.readCursor;
            final WideEntity loaded = state.storage.load(WideEntity.class, id, tx);
            state.storage.commitTransaction(tx);
            return loaded != null ? loaded.getId() : null;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    // =========================================================================
    // CORE: journal on/off on the DATA-heavy write pattern (the W-3 missing half)
    // =========================================================================

    @Benchmark
    public Long writeDataHeavy(final JournalState state, final Cursor cursor) throws Exception {
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final long id = cursor.nextId++;
            final WideEntity e = CanonicalState.newEntity(id, (int) id);
            state.storage.save(e, tx);
            state.storage.commitTransaction(tx);
            return e.getId();
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    // =========================================================================
    // CORE: durableWrites on/off (the S-2 fsync cost)
    // =========================================================================

    @Benchmark
    public Long writeDurable(final DurableState state, final Cursor cursor) throws Exception {
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final long id = cursor.nextId++;
            final WideEntity e = CanonicalState.newEntity(id, (int) id);
            state.storage.save(e, tx);
            state.storage.commitTransaction(tx);
            return e.getId();
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    // =========================================================================
    // CORE: commit-batch scaling — 10/100/500/1000 objects per transaction
    // =========================================================================

    @Benchmark
    public int commitBatch(final BatchState state, final Cursor cursor) throws Exception {
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            for (int i = 0; i < state.batchSize; i++) {
                final long id = cursor.nextId++;
                state.storage.save(CanonicalState.newEntity(id, (int) id), tx);
            }
            state.storage.commitTransaction(tx);
            return state.batchSize;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }
}
