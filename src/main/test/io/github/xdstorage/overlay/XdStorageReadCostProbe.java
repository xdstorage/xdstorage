package io.github.xdstorage.overlay;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.FilesFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W-4 READ-COST PROBE (a measurement, not a pin): the wall-clock cost of the by-id
 * read path with and without the eager pre-tx snapshot capture (the role-split's
 * read-time clone). The A/B is run by patching the capture site; this probe measures
 * BOTH the cold-by-id loads (fresh tx per load) and the warm-cache re-reads of the
 * same id (the canonical materialized).
 *
 * <p>THE A/B RESULTS (2026-11-21, the decision record): with the eager snapshot
 * (A) vs without it (B, the mark-time fallback only) — small entities (3 children):
 * cold ~21K vs ~23-25K loads/s, warm ~28-31K vs ~22-28K (within noise, ~10-15%);
 * large entities (30 children): cold ~13K vs ~17K (+33%), warm ~17K vs ~20.5K (+20%).
 * VERDICT: the eager capture costs ~10-33% depending on the graph size — NOT the
 * feared ~2x. DECISION: ACCEPT and DOCUMENT the delta (it is the correctness net for
 * the task-40 alias channels — the in-place nested-element mutation through the aliased
 * observable wrappers lands BEFORE the mark, so the mark-time fallback would snapshot
 * the already-mutated canonical); the generated-setter lazy capture stays in the 2.2.0
 * ledger with the collection-getter bypass caveat (getChildren().add(...) bypasses the
 * setters entirely — the interception design must cover the live container references).
 *
 * <p>@Disabled by default — enabled explicitly for the measurement runs.
 */
@DisplayName("W-4 PROBE: the by-id read path cost (the snapshot A/B measurement)")
public class XdStorageReadCostProbe {

    private static final String STORAGE_PATH = "./target/read-cost-probe-" + System.nanoTime();
    private static final int ENTITIES = 300;
    private static final int ROUNDS = 3;
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        final XdStorageConfig config = new XdStorageConfig.Builder()
                .journalWrites(true)
                .setEnableForeignKeys(true)
                .build();
        storage = XdStorageProvider.newOrGetFileStorage(
                "read_cost_probe_" + System.nanoTime(), STORAGE_PATH, 200, FilesFormat.YAML, config);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (final Exception ignored) {
            }
        }
        deleteDir(new File(STORAGE_PATH));
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    @Test
    @Disabled("the measurement probe — enabled explicitly for the measurement runs")
    public void measureReadCost_ColdAndWarm() throws Exception {
        // seed
        for (int i = 0; i < ENTITIES; i++) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final OvParent parent = new OvParent();
            parent.setId("probe-" + i);
            for (int c = 0; c < 30; c++) {
                final OvChild child = new OvChild();
                child.setId("probe-" + i + "-c" + c);
                parent.addChild(child);
            }
            storage.save(parent, tx);
            tx.commit();
        }

        // COLD: a fresh tx per load (the memoized wrapper never reused)
        for (int round = 0; round < ROUNDS; round++) {
            final long start = System.nanoTime();
            int loads = 0;
            for (int i = 0; i < ENTITIES; i++) {
                final IXdStorageTransaction tx = storage.beginTransaction();
                final OvParent p = storage.load(OvParent.class, "probe-" + i, tx);
                if (p.getChildren().size() == 30) {
                    loads++;
                }
                tx.commit();
            }
            final long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println("[COST] cold round " + round + ": " + loads + " loads in " + ms + " ms -> "
                    + (loads * 1000L / Math.max(1, ms)) + " loads/s");
        }

        // WARM: re-read the same id many times (each in a fresh tx — the canonical/materialized cache hit)
        final String hotId = "probe-150";
        for (int round = 0; round < ROUNDS; round++) {
            final long start = System.nanoTime();
            final int iterations = 500;
            for (int i = 0; i < iterations; i++) {
                final IXdStorageTransaction tx = storage.beginTransaction();
                final OvParent p = storage.load(OvParent.class, hotId, tx);
                assertTrue(p.getChildren().size() == 30);
                tx.commit();
            }
            final long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println("[COST] warm round " + round + ": " + iterations + " loads in " + ms + " ms -> "
                    + (iterations * 1000L / Math.max(1, ms)) + " loads/s");
        }
    }
}
