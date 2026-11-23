package io.github.xdstorage.properties;

import io.github.xdstorage.BinFeasEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.search.RangeOpsEntity;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AfterTry;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPTEST Этап 3 (docs/tasks/2.2.0/03 — beyond the task's scope): the FACADE-WIDE
 * contracts as properties:
 * <ul>
 *   <li>P3.1: EVERY transaction-bound facade method refuses without a transaction
 *       (XdStorageException or its runtime wrapper) — the generated sweep over the
 *       method table;</li>
 *   <li>P3.2: count(q) ≡ load(q).size() and the plan/exec consistency on random
 *       queries;</li>
 *   <li>P3.3+P3.4: after a RANDOM op sequence, the reopen reproduces the visible state
 *       AND verify() is never CORRUPT (the integrity net over every random shape).</li>
 * </ul>
 */
public class PropFacadeContractTest {

    private final List<File> folders = new ArrayList<>();

    @AfterTry
    void cleanup() {
        for (final File dir : folders) {
            deleteRecursively(dir);
        }
        folders.clear();
    }

    private static void deleteRecursively(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteRecursively(f);
            }
        }
        file.delete();
    }

    /** A Runnable that may throw the engine's checked exceptions. */
    private interface ThrowingCall {
        void run() throws Exception;
    }

    // ───────────────────────── P3.1: the no-transaction sweep ─────────────────────────

    @Property(tries = 10)
    void everyTxBoundMethod_RefusesWithoutTransaction(@ForAll final int seed) throws Exception {
        final File dir = new File("./target/pt-notx-" + System.nanoTime());
        folders.add(dir);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-notx-" + System.nanoTime(), dir.getPath(), 64);
        try {
            final BinFeasEntity entity = new BinFeasEntity("e0", seed);
            final List<BinFeasEntity> entities = new ArrayList<>(Arrays.asList(entity));
            final XdStorageSearchQuery query = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, 1L), null);

            final Map<String, ThrowingCall> methods = new HashMap<>();
            methods.put("save(obj)", () -> storage.save(entity));
            methods.put("save(coll)", () -> storage.save(entities));
            methods.put("update(obj)", () -> storage.update(entity));
            methods.put("update(coll)", () -> storage.update(entities));
            methods.put("delete(obj)", () -> storage.delete(entity));
            methods.put("delete(coll)", () -> storage.delete(entities));
            methods.put("load(id)", () -> storage.load(BinFeasEntity.class, "e0"));
            methods.put("load(all)", () -> storage.load(BinFeasEntity.class));
            methods.put("load(pred)", () -> storage.load(BinFeasEntity.class, o -> true));
            methods.put("load(pred,tx)", () -> storage.load(BinFeasEntity.class, o -> true, (IXdStorageTransaction)
                    null));
            methods.put("load(ref)", () -> storage.load((Object) entity));
            methods.put("load(refs)", () -> storage.load((java.util.Collection<?>) entities));
            methods.put("load(search)", () -> storage.load(RangeOpsEntity.class, "range_idx", query));
            methods.put("count", () -> storage.count(RangeOpsEntity.class, "range_idx", query));
            // FINDING (PROPTEST, task 03): explain works WITHOUT a transaction — the ONLY
            // tx-bound facade method that does (load/count refuse loudly; explain returns a
            // plan silently). The asymmetry is recorded in the findings compilation; the
            // property sweeps the CONTRACTED set (explain excluded until the contract
            // decision: either explain joins the refusals or the docs declare it tx-free).

            final List<String> silent = new ArrayList<>();
            for (final Map.Entry<String, ThrowingCall> entry : methods.entrySet()) {
                try {
                    entry.getValue().run();
                    silent.add(entry.getKey());
                } catch (final Exception refused) {
                    // the designed refusal: the checked XdStorageException, its runtime
                    // wrapper, or the null-tx NPE from the engine's null-guard — all LOUD
                    if (!(refused instanceof io.github.xdstorage.exceptions.XdStorageException)
                            && !(refused instanceof io.github.xdstorage.exceptions.XdStorageRuntimeException)
                            && !(refused instanceof NullPointerException)) {
                        silent.add(entry.getKey() + " threw " + refused.getClass().getSimpleName());
                    }
                }
            }
            assertTrue(silent.isEmpty(),
                    "the tx-bound methods must refuse without a transaction: silent = " + silent);
        } finally {
            storage.shutdown();
        }
    }

    // ───────────────────────── P3.2 + P3.3 + P3.4 ─────────────────────────

    @Property(tries = 20)
    void countEqualsLoadSize_AndReopenAndVerifyHold(@ForAll final long seed) throws Exception {
        final Random rnd = new Random(seed);
        final File dir = new File("./target/pt-face-" + System.nanoTime());
        folders.add(dir);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-face-" + System.nanoTime(), dir.getPath(), 64);
        try {
            final IXdStorageTransaction fill = storage.beginTransaction();
            for (int i = 0; i < 300; i++) {
                final RangeOpsEntity e = new RangeOpsEntity();
                e.setId("e" + i);
                e.setScore((long) rnd.nextInt(100));
                e.setLabel("l" + i);
                storage.save(e, fill);
            }
            storage.commitTransaction(fill);

            // P3.2: the random queries — count ≡ load.size over the operators
            final IXdStorageTransaction tx = storage.beginTransaction();
            for (int q = 0; q < 10; q++) {
                final long lo = rnd.nextInt(100);
                final long hi = lo + rnd.nextInt(20);
                final XdStorageSearchQuery range = new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(lo, hi)),
                                null);
                final XdStorageSearchQuery equals = new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, lo), null);
                assertEquals(storage.load(RangeOpsEntity.class, "range_idx", range, tx).size(),
                        storage.count(RangeOpsEntity.class, "range_idx", range, tx),
                        "count vs load.size on BETWEEN " + lo + ".." + hi);
                assertEquals(storage.load(RangeOpsEntity.class, "range_idx", equals, tx).size(),
                        storage.count(RangeOpsEntity.class, "range_idx", equals, tx),
                        "count vs load.size on EQUALS " + lo);
            }
            // the plan/exec consistency: the explain must not change the result
            final XdStorageSearchQuery stable = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(10L, 20L)),
                            null);
            final int before = storage.load(RangeOpsEntity.class, "range_idx", stable, tx).size();
            storage.explain(RangeOpsEntity.class, "range_idx", stable, tx);
            assertEquals(before, storage.load(RangeOpsEntity.class, "range_idx", stable, tx).size(),
                    "the explain changed the result set");

            // the random mutations (the integrity net's stress)
            for (int i = 0; i < 50; i++) {
                final IXdStorageTransaction mut = storage.beginTransaction();
                final RangeOpsEntity e = new RangeOpsEntity();
                e.setId("m" + i);
                e.setScore((long) rnd.nextInt(100));
                e.setLabel("m" + i);
                storage.save(e, mut);
                storage.commitTransaction(mut);
                if (rnd.nextBoolean()) {
                    final IXdStorageTransaction del = storage.beginTransaction();
                    final RangeOpsEntity victim = storage.load(RangeOpsEntity.class, "m" + i, del);
                    if (victim != null) {
                        storage.delete(victim, del);
                    }
                    storage.commitTransaction(del);
                }
            }

            // P3.4: the reopen reproduces the visible state exactly
            final IXdStorageTransaction preClose = storage.beginTransaction();
            final java.util.Collection<RangeOpsEntity> visibleNow = storage.load(RangeOpsEntity.class, "range_idx",
                    new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                            new XdStorageRange(0L, 1000L)), null), preClose);
            final Map<String, Long> model = new HashMap<>();
            for (final RangeOpsEntity e : visibleNow) {
                model.put(e.getId(), e.getScore());
            }
            storage.commitTransaction(preClose);
            storage.shutdown();

            final IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage(
                    "pt-face-re-" + System.nanoTime(), dir.getPath(), 64);
            final IXdStorageTransaction afterTx = reopened.beginTransaction();
            final java.util.Collection<RangeOpsEntity> visibleAfter = reopened.load(RangeOpsEntity.class, "range_idx",
                    new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                            new XdStorageRange(0L, 1000L)), null), afterTx);
            final Map<String, Long> afterModel = new HashMap<>();
            for (final RangeOpsEntity e : visibleAfter) {
                afterModel.put(e.getId(), e.getScore());
            }
            reopened.commitTransaction(afterTx);
            reopened.shutdown();

            assertEquals(model, afterModel, "the reopen diverges from the pre-shutdown visible state");

            // P3.3: the integrity verdict over the random-shape folder
            final XdStorageVerifyReport report = XdStorageProvider.verify(dir);
            assertNotEquals(XdStorageVerifyReport.Verdict.CORRUPT, report.verdict,
                    "the random-shape folder must never verify CORRUPT: " + report.findings);
        } finally {
            // the storages are closed in the phases
        }
    }
}
