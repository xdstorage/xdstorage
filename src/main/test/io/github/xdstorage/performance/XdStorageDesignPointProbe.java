package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N50K (docs/tasks/2.1.0/20): the design-point probe — the boundary measurement behind the
 * published "10⁴–10⁵ objects per class" claim (DECISION-GUIDE § positioning).
 *
 * <p>Run via {@code scripts/run-benchmarks.sh n50k} — the script owns task 13's conditions
 * protocol (quiet-machine gate, canonical logging config, results directory + run manifest);
 * this class owns the phases:
 * <ul>
 *   <li>bulk load N objects (batched commits, 1000/tx), measured;</li>
 *   <li><b>heap-after-load</b> — MXBean used-heap snapshots (baseline → after open → after
 *       load, gc-quiesced): the number that makes the heap≈working-set ceiling concrete;</li>
 *   <li>the headline scenarios at N: point read by id, read-only transaction, indexed EQUALS,
 *       selective range, save+delete — each bounded by an op target AND a wall-clock budget;
 *       a budget overrun is RECORDED AS THE FINDING (that is the ceiling data), never hidden.</li>
 * </ul>
 * Plain-Java single-run probe (the sweep-harness methodology, PERF §2): heap snapshots and
 * exact phase control are the point here, JMH-grade error bars are not — results are labeled
 * probe-mode and the quiet-machine gate applies.
 */
public final class XdStorageDesignPointProbe {

    private static final Logger log = LoggerFactory.getLogger(XdStorageDesignPointProbe.class);

    private static final int FRAGMENT_SIZE = 128;   // disclosed config (task: fragment size 128)
    private static final int BULK_BATCH = 1000;
    private static final long TX_TIMEOUT = 60_000L;
    private static final long SCENARIO_BUDGET_SEC = 120L;

    private static final int POINT_READ_OPS = 20_000;
    private static final int READ_ONLY_TX_OPS = 5_000;
    private static final int EQUALS_OPS = 3_000;
    private static final int RANGE_OPS = 3_000;
    private static final int SAVE_DELETE_OPS = 1_000;

    private XdStorageDesignPointProbe() {
    }

    private static final class ScenarioResult {
        final String name;
        final long ops;
        final double seconds;
        final boolean timedOut;

        ScenarioResult(final String name, final long ops, final double seconds, final boolean timedOut) {
            this.name = name;
            this.ops = ops;
            this.seconds = seconds;
            this.timedOut = timedOut;
        }

        Map<String, Object> toMap() {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("ops", ops);
            m.put("seconds", round(seconds));
            m.put("opsPerSec", round(seconds > 0 ? ops / seconds : 0));
            m.put("status", timedOut ? "TIMEOUT (budget " + SCENARIO_BUDGET_SEC + "s exceeded — the ceiling finding)" :
                    "ok");
            return m;
        }
    }

    private static double round(final double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static void gcQuiesce() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    public static void main(final String[] args) throws Exception {
        final String outDir = args.length > 0 ? args[0] : "target/design-point";
        final long[] sizes = args.length > 1 ? parseSizes(args[1]) : new long[]{5_000L, 50_000L};
        Files.createDirectories(Paths.get(outDir));

        final List<Map<String, Object>> runs = new ArrayList<>();
        for (final long n : sizes) {
            System.out.println("[design-point] === N=" + n + " ===");
            runs.add(runSize(n, outDir));
        }

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("probe", "design-point");
        result.put("dateUtc", new Date().toString());
        result.put("jdk", System.getProperty("java.version"));
        result.put("fragmentSize", FRAGMENT_SIZE);
        result.put("scenarioBudgetSec", SCENARIO_BUDGET_SEC);
        result.put("mode", "probe (plain-Java single run, quiet-machine gated by the wrapper script)");
        result.put("runs", runs);

        final String json = toLooseJson(result);
        final Path out = Paths.get(outDir, "design-point.json");
        Files.write(out, json.getBytes("UTF-8"));
        System.out.println("[design-point] written " + out);
    }

    private static long[] parseSizes(final String spec) {
        final String[] parts = spec.split(",");
        final long[] sizes = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            sizes[i] = Long.parseLong(parts[i].trim());
        }
        return sizes;
    }

    private static Map<String, Object> runSize(final long n, final String outDir) throws Exception {
        final Map<String, Object> run = new LinkedHashMap<>();
        run.put("n", n);

        final String tag = "design-point-" + n + "-" + System.nanoTime();
        final Path dir = Paths.get("target", tag);
        Files.createDirectories(dir);

        gcQuiesce();
        final long heapBaseline = usedHeap();

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(tag, dir.toString(), FRAGMENT_SIZE);

        gcQuiesce();
        final long heapAfterOpen = usedHeap();

        // ---- bulk load (batched commits) ----
        final long loadStart = System.nanoTime();
        final long loadHeapStart = usedHeap();
        IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
        try {
            for (long i = 1; i <= n; i++) {
                storage.save(newEntity(i), tx);
                if (i % BULK_BATCH == 0) {
                    storage.commitTransaction(tx);
                    tx = storage.beginTransaction(TX_TIMEOUT);
                }
            }
            storage.commitTransaction(tx);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
        final double loadSec = (System.nanoTime() - loadStart) / 1e9;
        final Map<String, Object> bulkLoad = new LinkedHashMap<>();
        bulkLoad.put("durationSec", round(loadSec));
        bulkLoad.put("objectsPerSec", round(n / loadSec));
        run.put("bulkLoad", bulkLoad);

        // ---- heap-after-load (the ceiling number) ----
        gcQuiesce();
        final long heapAfterLoad = usedHeap();
        final Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("baselineBytes", heapBaseline);
        heap.put("afterOpenBytes", heapAfterOpen);
        heap.put("afterLoadBytes", heapAfterLoad);
        heap.put("loadDeltaBytes", heapAfterLoad - loadHeapStart);
        heap.put("bytesPerObject", round((double) (heapAfterLoad - loadHeapStart) / n));
        run.put("heap", heap);
        System.out.println("[design-point] heap/object = " + heap.get("bytesPerObject") + " bytes");

        // ---- headline scenarios ----
        final Map<String, Object> scenarios = new LinkedHashMap<>();
        scenarios.put("pointRead", scenario("pointRead", POINT_READ_OPS, () -> {
            final IXdStorageTransaction sTx = storage.beginTransaction(TX_TIMEOUT);
            try {
                final Object o = storage.load(XdStorageDesignPointEntity.class, 1 + (long) (Math.random() * n), sTx);
                sTx.commit();
                return o != null;
            } catch (final Exception e) {
                storage.rollbackTransaction(sTx);
                throw e;
            }
        }));
        scenarios.put("readOnlyTx", scenario("readOnlyTx", READ_ONLY_TX_OPS, () -> {
            final IXdStorageTransaction sTx = storage.beginTransaction(TX_TIMEOUT);
            try {
                storage.load(XdStorageDesignPointEntity.class, 1 + (long) (Math.random() * n), sTx);
                storage.commitTransaction(sTx);
                return true;
            } catch (final Exception e) {
                storage.rollbackTransaction(sTx);
                throw e;
            }
        }));
        scenarios.put("indexedEquals", scenario("indexedEquals", EQUALS_OPS, () -> {
            // equality on the indexed score of a known object: exactly one hit
            final long knownScore = (1 + (long) (Math.random() * n)) * 7 % 1_000_007L;
            final XdStorageSearchQuery q = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, knownScore), null);
            final IXdStorageTransaction sTx = storage.beginTransaction(TX_TIMEOUT);
            try {
                final java.util.Collection<XdStorageDesignPointEntity> found = storage.load(XdStorageDesignPointEntity
                        .class, "probe_idx", q, sTx);
                sTx.commit();
                return found != null && found.size() == 1;
            } catch (final Exception e) {
                storage.rollbackTransaction(sTx);
                throw e;
            }
        }));
        scenarios.put("selectiveRange", scenario("selectiveRange", RANGE_OPS, () -> {
            // a selective window in score space (score = id * 7, no wrap below id 142858);
            // an EMPTY result is a valid answer at small N — the probe measures query cost
            final XdStorageSearchQuery q = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                            new XdStorageRange(100_000L, 200_000L)), null);
            final IXdStorageTransaction sTx = storage.beginTransaction(TX_TIMEOUT);
            try {
                final java.util.Collection<XdStorageDesignPointEntity> found = storage.load(XdStorageDesignPointEntity
                        .class, "probe_idx", q, sTx);
                sTx.commit();
                return found != null;
            } catch (final Exception e) {
                storage.rollbackTransaction(sTx);
                throw e;
            }
        }));
        scenarios.put("saveDelete", scenario("saveDelete", SAVE_DELETE_OPS, () -> {
            final long freshId = n + 1 + (long) (Math.random() * 1_000_000L);
            final IXdStorageTransaction sTx = storage.beginTransaction(TX_TIMEOUT);
            try {
                storage.save(newEntity(freshId), sTx);
                storage.commitTransaction(sTx);
            } catch (final Exception e) {
                storage.rollbackTransaction(sTx);
                throw e;
            }
            final IXdStorageTransaction dTx = storage.beginTransaction(TX_TIMEOUT);
            try {
                storage.delete(newEntity(freshId), dTx);
                storage.commitTransaction(dTx);
                return true;
            } catch (final Exception e) {
                storage.rollbackTransaction(dTx);
                throw e;
            }
        }));
        run.put("scenarios", scenarios);

        // hit-count sanity (not timed): the window must be a real subset at this N
        final IXdStorageTransaction sanityTx = storage.beginTransaction(TX_TIMEOUT);
        try {
            final XdStorageSearchQuery window = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                            new XdStorageRange(100_000L, 200_000L)), null);
            final int hits = storage.load(XdStorageDesignPointEntity.class, "probe_idx", window, sanityTx).size();
            sanityTx.commit();
            System.out.println("[design-point] range window hits at N=" + n + ": " + hits);
            run.put("rangeWindowHits", hits);
        } catch (final Exception e) {
            storage.rollbackTransaction(sanityTx);
            throw e;
        }

        storage.shutdown();
        deleteQuietly(dir);
        return run;
    }

    private interface Ops {
        boolean op() throws Exception;
    }

    private static ScenarioResult scenario(final String name, final long targetOps, final Ops ops) {
        long done = 0;
        final long start = System.nanoTime();
        boolean timedOut = false;
        try {
            while (done < targetOps) {
                if ((System.nanoTime() - start) / 1_000_000_000L >= SCENARIO_BUDGET_SEC) {
                    timedOut = true;
                    break;
                }
                if (ops.op()) {
                    done++;
                }
            }
        } catch (final Exception e) {
            log.error("[design-point] scenario {} failed at op {}: {}", name, done, e.getMessage(), e);
            return new ScenarioResult(name + ":ERROR(" + e.getClass().getSimpleName() + ")", done,
                    (System.nanoTime() - start) / 1e9, false);
        }
        final double sec = (System.nanoTime() - start) / 1e9;
        System.out.println("[design-point] " + name + ": " + done + " ops in " + round(sec) + "s = "
                + round(done / sec) + " ops/s" + (timedOut ? " — TIMEOUT (the finding)" : ""));
        return new ScenarioResult(name, done, sec, timedOut);
    }

    private static XdStorageDesignPointEntity newEntity(final long id) {
        final XdStorageDesignPointEntity e = new XdStorageDesignPointEntity();
        e.setId(id);
        e.setName("Wide-" + id);
        e.setScore(id * 7 % 1_000_007L);
        e.setLabel("label-" + (id % 1000));
        e.setAmount(id * 13);
        return e;
    }

    private static void deleteQuietly(final Path dir) {
        try {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Exception ignored) {
        }
    }

    /** minimal JSON writer (values are maps/numbers/strings/lists — no escaping surprises in this payload) */
    private static String toLooseJson(final Object value) {
        final StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(final StringBuilder sb, final Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof ScenarioResult) {
            writeJson(sb, ((ScenarioResult) value).toMap());
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (final Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (final Object o : (Iterable<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, o);
            }
            sb.append(']');
        } else {
            sb.append('"').append(value.toString().replace("\"", "'")).append('"');
        }
    }
}
