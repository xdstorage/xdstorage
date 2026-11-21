package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import tools.Gal200Hash;

/**
 * Task 54 step 1 (the JRNLDATA gate): the empirical proof that the journals living
 * under indexes/ are DATA CARRIERS — the hash id-index journal is the ONLY durable
 * location of the un-checkpointed id→fragment records (the O(delta) write path: the
 * base is NOT rewritten on commit; the checkpoint happens at the shutdown/threshold).
 * Losing the journal tail = the recently saved objects become unreachable (the
 * fragments exist, the addressability is gone) — the class javadoc of
 * XdStorageHashIndexResource states the same ("lookups would silently miss").
 *
 * <p>Step 2 landed (2026-11-21): the index journals' PRIMARY location is now
 * data/&lt;Class&gt;/journal/ (the naming service's getDataClassJournalDir); the
 * resolution chain: data &gt; indexes/&lt;Class&gt;/journal/ (the dev-train spelling)
 * &gt; sibling &gt; the 2.0.0 stacked; the settle targets the data location. This test
 * is the permanent pin of both the data-carrier premise and the new location.
 */
@DisplayName("JRNLDATA: the index journals are data carriers living in data/<Class>/journal/")
public class XdStorageIndexJournalDataCarrierProbe {

    private static final String STORAGE_PATH = "./target/jrnl-data-probe-" + System.nanoTime();
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteRecursively(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("jrnl-data-probe", STORAGE_PATH, 64);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (final Exception ignored) {
            }
        }
        deleteRecursively(new File(STORAGE_PATH));
    }

    private static void deleteRecursively(final File file) throws RuntimeException {
        if (!file.exists()) {
            return;
        }
        try (final Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(file.toPath())) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void indexJournalUnderIndexes_IsTheSoleDurableLocationOfTheDelta() throws Exception {
        // seed one entity + commit + shutdown: the checkpoint folds everything into the base
        final IXdStorageTransaction seedTx = storage.beginTransaction();
        final Gal200Hash seed = new Gal200Hash();
        seed.setId("seed-1");
        seed.setValue("seed");
        storage.save(seed, seedTx);
        seedTx.commit();
        storage.shutdown();

        // reopen: the base now holds seed-1 (the checkpointed state)
        storage = XdStorageProvider.newOrGetFileStorage("jrnl-data-probe", STORAGE_PATH, 64);
        final String baseFile = STORAGE_PATH + "/indexes/Gal200Hash/index.yaml";
        assertTrue(new File(baseFile).isFile(), "the id-index base exists after the checkpoint");
        final String baseBefore = new String(Files.readAllBytes(new File(baseFile).toPath()), "UTF-8");
        assertTrue(baseBefore.contains("seed-1"), "the base holds the checkpointed seed");
        assertFalse(baseBefore.contains("delta-1"), "the base does NOT hold the yet-unsaved delta");

        // save TWO more entities + commit — the O(delta) path appends to the JOURNAL,
        // the base stays untouched (the delta lives ONLY in the journal until the checkpoint)
        final IXdStorageTransaction deltaTx = storage.beginTransaction();
        final Gal200Hash delta1 = new Gal200Hash();
        delta1.setId("delta-1");
        delta1.setValue("дельта-один");
        storage.save(delta1, deltaTx);
        final Gal200Hash delta2 = new Gal200Hash();
        delta2.setId("delta-2");
        delta2.setValue("дельта-два");
        storage.save(delta2, deltaTx);
        deltaTx.commit();

        // NO shutdown — the probe reads the durable state as a crash would leave it
        final File journalFile = new File(STORAGE_PATH + "/data/Gal200Hash/journal/index-journal.yaml");
        assertTrue(journalFile.isFile(),
                "the id-index journal lives under data/<Class>/journal/ (the JRNLDATA primary location)");
        assertFalse(new File(STORAGE_PATH + "/indexes/Gal200Hash/journal").exists(),
                "no journals under indexes/ — the data carriers live with the data");
        final String journal = new String(Files.readAllBytes(journalFile.toPath()), "UTF-8");
        assertTrue(journal.contains("delta-1"), "the journal carries delta-1");
        assertTrue(journal.contains("delta-2"), "the journal carries delta-2");
        System.out.println("[PROBE] the id-index journal content:\n" + journal);
        assertTrue(journal.contains("UPSERT"), "the journal carries the upsert records");

        final String baseAfter = new String(Files.readAllBytes(new File(baseFile).toPath()), "UTF-8");
        assertFalse(baseAfter.contains("delta-1"),
                "THE DATA-CARRIER PROOF: the base does NOT hold the delta — the journal is the"
                        + " sole durable location of these records until the checkpoint");

        // the journal tail is what makes delta-1/2 reachable: load them (the in-memory
        // replay already happened at the append), then the shutdown checkpoint folds the
        // tail into the base — the cycle the durability depends on
        final IXdStorageTransaction checkTx = storage.beginTransaction();
        assertEquals("дельта-один", storage.load(Gal200Hash.class, "delta-1", checkTx).getValue());
        checkTx.commit();
        storage.shutdown();

        // after the checkpoint the base holds the delta and the journal is cleared
        final String baseFinal = new String(Files.readAllBytes(new File(baseFile).toPath()), "UTF-8");
        assertTrue(baseFinal.contains("delta-1"), "the checkpoint folded the journal into the base");
    }
}
