package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.RangeOpsEntity;
import io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JRNLNAME (docs/tasks/2.1.0/24): the journal naming + placement contract with legacy
 * read-compatibility.
 *
 * <p>Journals live in the {@code journal} SUBDIRECTORY of their resource's directory
 * ({@code data/<Class>/journal/<base>-journal.yaml}, {@code structure/journal/...}) — a
 * class folder holds only bases. Read-compatibility resolves in priority order: the
 * journal-directory file wins; the 2.1.0-dev sibling spelling (next to the base) and the
 * 2.0.0-era stacked name ({@code <base>.yaml.journal.yaml}) are replay sources — after a
 * clean replay the file migrates into the journal directory, and an additional superseded
 * twin is removed (WARN).
 *
 * <p>Lifecycle note: since W4a (task 32) a clean shutdown checkpoints every journal into its
 * base and drops the file, so journals are observable only WHILE the storage is open — every
 * file-state assertion here runs against a while-open folder capture or an open storage.
 * Folder-copy fixtures take over the original path before reopening (the class-structure
 * metadata persists absolute data-resource paths).
 */
@DisplayName("JRNLNAME: journal placement + naming + legacy migration")
public class XdStorageJournalNamingTest {

    private static final long TX_TIMEOUT = 60_000L;

    @TempDir
    Path tempDir;

    private IXdFileStorage open(final Path dir, final String name) {
        return open(dir, name, XdStorageConfig.defaults());
    }

    private IXdFileStorage open(final Path dir, final String name, final XdStorageConfig config) {
        return XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 64, config);
    }

    private void saveFive(final IXdFileStorage storage) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
        try {
            for (int i = 1; i <= 5; i++) {
                storage.save(new RangeOpsEntity("jr-" + i, (long) i * 100, "label-" + i), tx);
            }
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private void updateThird(final IXdFileStorage storage) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
        try {
            storage.update(new RangeOpsEntity("jr-3", 300L, "label-3-UPGRADED"), tx);
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    /** a fresh insert — forces the class's data resource to prepare (and replay its journal) */
    private void saveSixth(final IXdFileStorage storage) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
        try {
            storage.save(new RangeOpsEntity("jr-6", 600L, "label-6"), tx);
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private List<Path> journals(final Path root, final String endsWith) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(endsWith))
                    .collect(Collectors.toList());
        }
    }

    private static void copyTree(final Path from, final Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (final Path src : walk.collect(Collectors.toList())) {
                final Path dest = to.resolve(from.relativize(src).toString());
                if (src.toFile().isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Moves every journal-directory file out to the base's dir under the 2.0.0-era stacked name. */
    private void renameJournalsToLegacySpelling(final Path root) throws Exception {
        for (final Path journal : journals(root, XdStorageYamlCommitJournal.JOURNAL_SUFFIX)) {
            Files.move(journal, legacySpellingOf(journal), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Moves every journal-directory file out to the base's dir under the 2.1.0-dev sibling spelling. */
    private void renameJournalsToSiblingSpelling(final Path root) throws Exception {
        for (final Path journal : journals(root, XdStorageYamlCommitJournal.JOURNAL_SUFFIX)) {
            Files.move(journal, siblingSpellingOf(journal), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** journal/<base>-journal.yaml → <base>.yaml.journal.yaml, in the base's directory */
    private static Path legacySpellingOf(final Path journal) {
        final String baseNoExt = journal.getFileName().toString()
                .replace(XdStorageYamlCommitJournal.JOURNAL_SUFFIX, "");
        return journal.getParent().getParent().resolve(baseNoExt + ".yaml" + XdStorageYamlCommitJournal
                .LEGACY_JOURNAL_SUFFIX);
    }

    /** journal/<base>-journal.yaml → <base>-journal.yaml, in the base's directory */
    private static Path siblingSpellingOf(final Path journal) {
        final String baseNoExt = journal.getFileName().toString()
                .replace(XdStorageYamlCommitJournal.JOURNAL_SUFFIX, "");
        return journal.getParent().getParent().resolve(baseNoExt + XdStorageYamlCommitJournal.JOURNAL_SUFFIX);
    }

    @Test
    @DisplayName("JRNLDATA: индексный журнал живёт в data/<class>/journal/ (носитель адресуемости); дев-спеллинг под " +
            "indexes/ оседает туда же")
    public void indexJournalLivesInDataJournalDir() throws Exception {
        // a) while open: the id-index journal is in data/<Class>/journal/; none under indexes/
        final Path dir = tempDir.resolve("idx-jrnl");
        final IXdFileStorage storage = open(dir, "jrnl-idx-" + System.nanoTime());
        saveFive(storage);
        assertTrue(Files.exists(dir.resolve("data/RangeOpsEntity/journal/index-journal.yaml")),
                "the id-index journal's primary location: data/<Class>/journal/ (the addressability data carrier)");
        assertTrue(journals(dir.resolve("indexes"), XdStorageYamlCommitJournal.JOURNAL_SUFFIX).isEmpty(),
                "no journals under indexes/ — the data carriers live with the data");
        // capture the while-open state for the dev-spelling simulation
        copyTree(dir, tempDir.resolve("idx-staged"));
        storage.shutdown();
        // the shutdown checkpoint folded the journal into the base
        assertFalse(Files.exists(dir.resolve("data/RangeOpsEntity/journal/index-journal.yaml")),
                "the checkpoint folds the journal into the base (task 48)");

        // b) the dev-train spelling (indexes/<class>/journal/) settles into data/ at prepare:
        //    the id-index base is zeroed — only the journal can restore the five records
        final Path staged = tempDir.resolve("idx-staged");
        Files.createDirectories(staged.resolve("indexes/RangeOpsEntity/journal"));
        Files.move(staged.resolve("data/RangeOpsEntity/journal/index-journal.yaml"),
                staged.resolve("indexes/RangeOpsEntity/journal/index-journal.yaml"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.write(staged.resolve("indexes/RangeOpsEntity/index.yaml"), new byte[0]);
        // the fixture takeover (the structure metadata pins the absolute paths)
        Files.move(dir, tempDir.resolve("idx-retired"));
        Files.move(staged, dir);

        final IXdFileStorage second = open(dir, "jrnl-idx-open-" + System.nanoTime());
        try {
            saveSixth(second);
            final IXdStorageTransaction tx = second.beginTransaction(TX_TIMEOUT);
            assertEquals(6, second.load(RangeOpsEntity.class, o -> true, tx).size(),
                    "the id-index journal replay restored the addressability (all six reachable)");
            second.commitTransaction(tx);
            assertTrue(Files.exists(dir.resolve("data/RangeOpsEntity/journal/index-journal.yaml")),
                    "the dev-train journal settled into data/<Class>/journal/ at the prepare");
            assertFalse(Files.exists(dir.resolve("indexes/RangeOpsEntity/journal/index-journal.yaml")),
                    "the dev-spelling location is gone after the settle");
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("while open: journals live in the journal/ subdirectory; no stray names anywhere")
    public void newNamingWhileOpen() throws Exception {
        final Path dir = tempDir.resolve("new");
        final IXdFileStorage storage = open(dir, "jrnl-new-" + System.nanoTime());
        saveFive(storage);

        final List<Path> suffixNamed = journals(dir, XdStorageYamlCommitJournal.JOURNAL_SUFFIX);
        assertFalse(suffixNamed.isEmpty(), "the journals exist while the storage is open");
        for (final Path journal : suffixNamed) {
            assertEquals(XdStorageYamlCommitJournal.JOURNAL_DIR, journal.getParent().getFileName().toString(),
                    "every journal rides the journal/ subdirectory of its resource directory: " + journal);
        }
        assertTrue(journals(dir, XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX).isEmpty(),
                "no stacked-extension journal names on a fresh storage");
        storage.shutdown();
    }

    @Test
    @DisplayName("a 2.0.0-era folder: the stacked-named journal is replayed (commits survive) and migrates into " +
            "journal/")
    public void legacyJournalReplayedAndMigrated() throws Exception {
        final Path dir = tempDir.resolve("legacy");
        final IXdFileStorage first = open(dir, "jrnl-legacy-" + System.nanoTime());
        saveFive(first);
        // capture WHILE OPEN: the copy carries the un-checkpointed journals — exactly the
        // 2.0.0-era mid-commit folder shape (a clean shutdown would checkpoint them away)
        copyTree(dir, tempDir.resolve("staged"));
        first.shutdown();

        final Path staged = tempDir.resolve("staged");
        renameJournalsToLegacySpelling(staged);
        // make the replay VISIBLE: the base is lost (empty) — only the legacy journal can
        // restore the five objects, so a passing load proves the legacy file was replayed
        Files.write(staged.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1.yaml"), new byte[0]);
        // upgrade-folder debris of the old era: a stale base .tmp — S-5 sweeps it at open
        Files.write(staged.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1.yaml.tmp"),
                "torn base write of the old era".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(journals(staged, XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX).isEmpty(),
                "the fixture holds stacked-named journals");

        // the class structure metadata persists absolute data-resource paths, so the reopened
        // storage must find the fixture at the ORIGINAL path: the fixture takes it over
        Files.move(dir, tempDir.resolve("legacy-retired"));
        Files.move(staged, dir);

        final IXdFileStorage second = open(dir, "jrnl-migrated-" + System.nanoTime());
        try {
            // a write forces the data resource's prepare: the stacked-named journal is
            // replayed over the empty base and migrates into the journal directory (settle)
            saveSixth(second);
            final IXdStorageTransaction tx = second.beginTransaction(TX_TIMEOUT);
            assertEquals(6, second.load(RangeOpsEntity.class, o -> true, tx).size(),
                    "the un-checkpointed commits survived the upgrade (replayed from the stacked name) + the new " +
                            "object");
            second.commitTransaction(tx);

            assertFalse(Files.exists(dir.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1.yaml.journal.yaml")),
                    "the stacked-named data journal was migrated at replay");
            assertTrue(Files.exists(dir.resolve("data/RangeOpsEntity/journal/RangeOpsEntity-jr-1-journal.yaml")),
                    "the data journal lives in the journal/ subdirectory after the migration");
            assertFalse(Files.exists(dir.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1.yaml.tmp")),
                    "the stale upgrade debris was swept at open (S-5)");
        } finally {
            second.shutdown();
        }
        // the user-data journals (data/ — the entity fragment and the id index) are migrated
        // by the end of the session; an engine-bookkeeping resource that was never prepared
        // (the structure counter) migrates lazily at its next prepare — content-safe either way
        assertTrue(journals(dir.resolve("data"), XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX).isEmpty(),
                "no stacked-named journals remain under data/ after the session");
    }

    @Test
    @DisplayName("the 2.1.0-dev sibling spelling (journal next to the base) also migrates into journal/")
    public void siblingJournalMigrated() throws Exception {
        final Path dir = tempDir.resolve("sibling");
        final IXdFileStorage first = open(dir, "jrnl-sibling-" + System.nanoTime());
        saveFive(first);
        copyTree(dir, tempDir.resolve("staged"));
        first.shutdown();

        final Path staged = tempDir.resolve("staged");
        renameJournalsToSiblingSpelling(staged);
        Files.write(staged.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1.yaml"), new byte[0]);
        assertFalse(journals(staged, XdStorageYamlCommitJournal.JOURNAL_SUFFIX).isEmpty(),
                "the fixture holds sibling-spelled journals");

        Files.move(dir, tempDir.resolve("sibling-retired"));
        Files.move(staged, dir);

        final IXdFileStorage second = open(dir, "jrnl-sibling-open-" + System.nanoTime());
        try {
            // the write forces the data resource's prepare: the sibling-named journal is
            // replayed and migrates into the journal/ subdirectory — visible WHILE open
            // (a clean shutdown checkpoints the journal away afterwards)
            saveSixth(second);
            assertTrue(Files.exists(dir.resolve("data/RangeOpsEntity/journal/RangeOpsEntity-jr-1-journal.yaml")),
                    "the sibling-named journal migrated into the journal/ subdirectory");
            assertFalse(Files.exists(dir.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1-journal.yaml")),
                    "no journal stays next to the base after the migration");
            final IXdStorageTransaction tx = second.beginTransaction(TX_TIMEOUT);
            assertEquals(6, second.load(RangeOpsEntity.class, o -> true, tx).size(),
                    "the commits survived (replayed from the sibling spelling) + the new object");
            second.commitTransaction(tx);
        } finally {
            second.shutdown();
        }
        assertFalse(Files.exists(dir.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1-journal.yaml")),
                "no journal ever comes back next to the base");
    }

    @Test
    @DisplayName("both namings present: the journal-directory file wins (the updated body over the twin's), the twin " +
            "is removed")
    public void bothPresentNewWins() throws Exception {
        final Path dir = tempDir.resolve("both");
        final IXdFileStorage first = open(dir, "jrnl-both-" + System.nanoTime());
        saveFive(first);
        copyTree(dir, tempDir.resolve("five"));   // captured while open: jr-3's journal holds the insert only
        updateThird(first);                        // an UPDATE on jr-3 appends a second record to its journal
        copyTree(dir, tempDir.resolve("six"));    // captured while open: jr-3's journal holds insert + update
        first.shutdown();

        // plant the both-present anomaly with DISTINCT contents: "six" keeps the journal
        // directory (jr-3: insert + update); next to it the "five" journal under the LEGACY
        // stacked spelling (jr-3: the pre-update insert only). The base is lost (empty) —
        // the REPLAYED journal alone decides what the load serves.
        final Path six = tempDir.resolve("six");
        renameJournalsToLegacySpelling(tempDir.resolve("five"));
        copyTree(tempDir.resolve("five"), six);
        Files.write(six.resolve("data/RangeOpsEntity/RangeOpsEntity-jr-1.yaml"), new byte[0]);
        assertFalse(journals(six, XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX).isEmpty(),
                "fixture: legacy twins planted");
        assertFalse(journals(six, XdStorageYamlCommitJournal.JOURNAL_SUFFIX).isEmpty(),
                "fixture: journal-directory files present");

        // the fixture takes over the original path (absolute paths in the structure metadata)
        Files.move(dir, tempDir.resolve("both-retired"));
        Files.move(six, dir);

        final IXdFileStorage second = open(dir, "jrnl-both-open-" + System.nanoTime());
        try {
            // a write forces the class's data resource to prepare: with both namings present
            // the resolution must pick the journal-directory file (jr-3's update record lives
            // only there)
            saveSixth(second);
            final IXdStorageTransaction tx = second.beginTransaction(TX_TIMEOUT);
            final Collection<RangeOpsEntity> loaded = second.load(RangeOpsEntity.class, o -> true, tx);
            second.commitTransaction(tx);
            assertEquals(6, loaded.size(), "the object set is intact (5 replayed + the new object)");
            final RangeOpsEntity jr3 = loaded.stream()
                    .filter(o -> "jr-3".equals(o.getId()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("jr-3 missing"));
            assertEquals("label-3-UPGRADED", jr3.getLabel(),
                    "the journal-directory file won: its update record is served, not the twin's pre-update body");
        } finally {
            second.shutdown();
        }
        final List<Path> remainingTwins = journals(dir.resolve("data"), XdStorageYamlCommitJournal
                .LEGACY_JOURNAL_SUFFIX);
        assertTrue(remainingTwins.isEmpty(),
                "the superseded stacked-named twin was removed after the clean replay — remaining: " + remainingTwins);
    }

    @Test
    @DisplayName("an empty (already-checkpointed) stacked-named journal still migrates — renamed, not dropped")
    public void emptyLegacyJournalStillMigrates() throws Exception {
        final Path dir = tempDir.resolve("empty");
        final IXdFileStorage first = open(dir, "jrnl-empty-" + System.nanoTime());
        saveFive(first);
        copyTree(dir, tempDir.resolve("staged"));
        first.shutdown();

        // the legacy era's checkpointed shape: the data journal was already emptied into its
        // base — 0 bytes under the stacked name; migration must still move it
        final Path staged = tempDir.resolve("staged");
        final Path dataJournal = journals(staged, XdStorageYamlCommitJournal.JOURNAL_SUFFIX).stream()
                .filter(f -> f.getFileName().toString().startsWith("RangeOpsEntity"))
                .findFirst().orElseThrow(() -> new IllegalStateException("no data journal in the fixture"));
        Files.write(dataJournal, new byte[0]);
        final Path legacyName = legacySpellingOf(dataJournal);
        Files.move(dataJournal, legacyName);

        // the fixture takes over the original path (absolute paths in the structure metadata)
        Files.move(dir, tempDir.resolve("empty-retired"));
        Files.move(staged, dir);

        final IXdFileStorage second = open(dir, "jrnl-empty-open-" + System.nanoTime());
        try {
            final IXdStorageTransaction tx = second.beginTransaction(TX_TIMEOUT);
            second.load(RangeOpsEntity.class, o -> true, tx);
            second.commitTransaction(tx);
        } finally {
            second.shutdown();
        }
        assertFalse(Files.exists(legacyName), "the empty stacked-named journal was migrated, not dropped");
        final Path newName = dir.resolve("data/RangeOpsEntity/journal/RangeOpsEntity-jr-1-journal.yaml");
        assertTrue(Files.exists(newName) && Files.size(newName) == 0,
                "the migrated file lives in the journal/ subdirectory, still empty");
    }

    @Test
    @DisplayName("checkpoint reset drops the journal in the journal/ subdirectory; the base carries the content")
    public void checkpointResetUnderNewName() throws Exception {
        final Path dir = tempDir.resolve("ckpt");
        final XdStorageConfig config = XdStorageConfig.builder().journalCheckpointBytes(1L).build();
        final IXdFileStorage storage = open(dir, "jrnl-ckpt-" + System.nanoTime(), config);
        saveFive(storage);   // journal (KBs) >> 1 byte → the size checkpoint fires at the commit

        assertTrue(journals(dir, XdStorageYamlCommitJournal.JOURNAL_SUFFIX).isEmpty(),
                "the size checkpoint reset the journal (base authoritative)");
        assertTrue(journals(dir, XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX).isEmpty());
        final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
        assertEquals(5, storage.load(RangeOpsEntity.class, o -> true, tx).size(),
                "post-checkpoint reads come from the base");
        storage.commitTransaction(tx);
        storage.shutdown();

        final IXdFileStorage second = open(dir, "jrnl-ckpt-reopen-" + System.nanoTime());
        try {
            final IXdStorageTransaction tx2 = second.beginTransaction(TX_TIMEOUT);
            assertEquals(5, second.load(RangeOpsEntity.class, o -> true, tx2).size(),
                    "the journal-free folder serves everything after the reopen");
            second.commitTransaction(tx2);
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("naming is content-matched for every base extension; the journal rides the journal/ subdirectory")
    public void journalNameIsContentMatchedForEveryBaseExtension() {
        // XML-format storages refuse journalWrites loudly at open (the W-1 scope guard), so no
        // XML journal exists in practice — but the naming rule is a pure function of the base
        // name: the journal rides the extension-less base with the content-matched .yaml suffix.
        final Path forYaml = XdStorageYamlCommitJournal.journalFileFor("/db/data/Order/Order-1.yaml");
        assertEquals("Order-1" + XdStorageYamlCommitJournal.JOURNAL_SUFFIX, forYaml.getFileName().toString(),
                "a .yaml base keeps its name, the journal rides as a suffix");
        assertEquals(XdStorageYamlCommitJournal.JOURNAL_DIR, forYaml.getParent().getFileName().toString(),
                "the journal lives in the journal/ subdirectory of the base's directory");
        assertEquals("Order" + XdStorageYamlCommitJournal.JOURNAL_SUFFIX,
                XdStorageYamlCommitJournal.journalFileFor("/db/data/Order/Order.xml").getFileName().toString(),
                "an .xml base is stripped: no Order.xml.journal.yaml triple-mixed name");
        assertEquals("/db/data/Order/Order-1" + XdStorageYamlCommitJournal.JOURNAL_SUFFIX,
                XdStorageYamlCommitJournal.siblingJournalFileFor("/db/data/Order/Order-1.yaml").toString(),
                "the sibling spelling stays next to the base (read-compatible migration source)");
        assertEquals("/db/data/Order/Order-1.yaml" + XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX,
                XdStorageYamlCommitJournal.legacyJournalFileFor("/db/data/Order/Order-1.yaml").toString(),
                "the stacked spelling stays exactly the 2.0.0 name (read-compatible migration source)");
        assertEquals("Order", XdStorageYamlCommitJournal.stripFormatExtension("Order.xml"));
        assertEquals("Order-1", XdStorageYamlCommitJournal.stripFormatExtension("Order-1.yaml"));
        assertEquals("X", XdStorageYamlCommitJournal.stripFormatExtension("X.json"));
        assertEquals("X", XdStorageYamlCommitJournal.stripFormatExtension("X"));
    }
}
