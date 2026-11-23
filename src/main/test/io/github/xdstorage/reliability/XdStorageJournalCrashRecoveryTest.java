package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL (docs/tasks/2.2.0/06) on REAL crash folders: the state is produced by one child process
 * that commits in every folder and is killed with SIGKILL ({@link XdStorageCrashCommitter});
 * then a journal is damaged the way a crash or bit rot damages it, and the folder is reopened.
 *
 * <p>What is pinned: a torn tail is discarded and the earlier commits stay readable; a
 * corruption with complete batches after it makes the reopen refuse — loudly, by name —
 * instead of dropping the rest of the journal behind one WARN.</p>
 */
@DisplayName("WAL (2.2.0/06): настоящий сбой процесса — оборванный хвост и повреждение середины журнала")
public class XdStorageJournalCrashRecoveryTest {

    private static final String CLASS_DIR = "BenchmarkEntity";
    private static final String CLASS_NAME = "io.github.xdstorage.BenchmarkEntity";

    @TempDir
    static Path root;

    private static Path control;
    private static Path cutBeforeTrailer;
    private static Path cutInsideRecord;
    private static Path corrupted;
    private static Path updated;
    private static Path verified;

    /** ONE child process leaves all six folders in the post-crash state. */
    @BeforeAll
    static void crashOnce() throws Exception {
        control = root.resolve("control");
        cutBeforeTrailer = root.resolve("cut-before-trailer");
        cutInsideRecord = root.resolve("cut-inside-record");
        corrupted = root.resolve("corrupted");
        updated = root.resolve("updated");
        verified = root.resolve("verified");
        XdStorageCrashedStorages.crashAfterCommits(root,
                control + "|" + XdStorageCrashCommitter.THREE_COMMITS,
                cutBeforeTrailer + "|" + XdStorageCrashCommitter.THREE_COMMITS,
                cutInsideRecord + "|" + XdStorageCrashCommitter.THREE_COMMITS,
                corrupted + "|" + XdStorageCrashCommitter.THREE_COMMITS,
                updated + "|" + XdStorageCrashCommitter.UPDATE_TWICE,
                verified + "|" + XdStorageCrashCommitter.THREE_COMMITS);
    }

    /** What a reader sees after the reopen: the records loadable by id with their payload intact. */
    private static List<String> visibleIds(final Path folder) throws Exception {
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("reopen-" + UUID.randomUUID(),
                folder.toString(), 128);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            final List<String> visible = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                try {
                    final BenchmarkEntity byId = storage.load(BenchmarkEntity.class, "e-" + i, tx);
                    if (byId != null && ("v-" + i).equals(byId.getPayload())) {
                        visible.add("e-" + i);
                    }
                } catch (final Exception unreadable) {
                    // not visible — an id whose answer cannot be read is not a readable record
                }
            }
            storage.rollbackTransaction(tx);
            return visible;
        } finally {
            storage.shutdown();
        }
    }

    private static String messagesOf(final Throwable failure) {
        final StringBuilder out = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            out.append(t.getMessage()).append(" | ");
        }
        return out.toString();
    }

    @Test
    @DisplayName("Контроль: после kill все три коммита читаются из журналов")
    public void control_AllThreeCommitsSurviveTheKill() throws Exception {
        final Path journal = XdStorageCrashedStorages.indexJournal(control, CLASS_DIR);
        assertEquals(3, XdStorageCrashedStorages.batches(journal), "one batch per commit");
        assertTrue(XdStorageCrashedStorages.read(journal).contains("# xdstorage-meta serial="),
                "every batch carries its trailer");
        assertEquals(3, visibleIds(control).size(), "every commit is visible after the reopen");
    }

    @Test
    @DisplayName("verify() понимает трейлер: журналы с трейлером проходят как целые")
    public void theVerifierIsAtHomeWithTheTrailer() throws Exception {
        // its own folder: the control folder is reopened by another test, and that reopen's
        // shutdown checkpoints the journals away
        final XdStorageVerifyReport report = XdStorageProvider.verify(verified);
        final List<String> journalStates = new ArrayList<>();
        for (final XdStorageVerifyReport.FileReport file : report.files) {
            if ("journal".equals(file.kind)) {
                journalStates.add(file.path + "=" + file.state);
            }
        }
        assertFalse(journalStates.isEmpty(), "the crashed folder holds journals: " + report);
        for (final String journalState : journalStates) {
            assertTrue(journalState.endsWith("=OK"), "a journal with trailers verifies whole: " + journalState);
        }
        assertEquals(XdStorageVerifyReport.Verdict.WARNINGS, report.verdict,
                "a crashed folder is a warnings folder — un-checkpointed journals, nothing broken: " + report);
    }

    @Test
    @DisplayName("Обрыв перед трейлером последней партии: два ранних коммита целы, третий отброшен")
    public void aTornTailBeforeTheLastTrailer_LosesOnlyTheLastCommit() throws Exception {
        cutBeforeBatchTrailer(XdStorageCrashedStorages.indexJournal(cutBeforeTrailer, CLASS_DIR), 3);

        assertEquals(2, visibleIds(cutBeforeTrailer).size(), "e-1 and e-2 stay readable; e-3 was never completed");
    }

    @Test
    @DisplayName("Обрыв внутри тела последней записи: то же — ранние коммиты целы")
    public void aTornTailInsideTheLastRecord_LosesOnlyTheLastCommit() throws Exception {
        truncateInsideLastBatch(XdStorageCrashedStorages.indexJournal(cutInsideRecord, CLASS_DIR));

        assertEquals(2, visibleIds(cutInsideRecord).size());
    }

    @Test
    @DisplayName("Повреждение в первой партии, дальше две целые: повторное открытие отказывает громко")
    public void aCorruptionWithLaterBatches_RefusesTheReopen() throws Exception {
        final Path journal = XdStorageCrashedStorages.indexJournal(corrupted, CLASS_DIR);
        final String text = XdStorageCrashedStorages.read(journal);
        final String indexRecord = "class: 'io.github.xdstorage.index.hash.XdStorageHashIndexRecord'";
        final int first = text.indexOf(indexRecord);
        assertTrue(first >= 0, "the id-index journal carries index records");
        Files.write(journal, (text.substring(0, first) + "class: 'com.example.gone.RemovedIndexRecord'"
                + text.substring(first + indexRecord.length())).getBytes(StandardCharsets.UTF_8));

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("reopen-corrupt-" + UUID.randomUUID(),
                corrupted.toString(), 128);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            final Exception refusal = assertThrows(Exception.class,
                    () -> storage.load(BenchmarkEntity.class, "e-1", tx),
                    "a damaged journal with later complete batches must not be replayed");
            final String messages = messagesOf(refusal);
            assertTrue(messages.contains("cannot be replayed"), messages);
            assertTrue(messages.contains("is CORRUPT in batch 1"), messages);
            assertTrue(messages.contains("2 later batch(es) with 2 further record(s) follow"), messages);
            assertTrue(messages.contains("Refusing to replay"), messages);
        } finally {
            storage.shutdown();
        }
    }

    @Test
    @DisplayName("Обновление одной записи дважды: две партии в одном журнале данных, читается вторая версия")
    public void twoBatchesOnOneRecord_ReplayToTheLatestVersion() throws Exception {
        final Path journal = XdStorageCrashedStorages.dataJournal(updated, CLASS_DIR, CLASS_NAME);
        assertEquals(2, XdStorageCrashedStorages.batches(journal), "one batch per commit");

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("reopen-update-" + UUID.randomUUID(),
                updated.toString(), 128);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            assertEquals("second", storage.load(BenchmarkEntity.class, "u-1", tx).getPayload());
            storage.commitTransaction(tx);
        } finally {
            storage.shutdown();
        }
    }

    // === the damage ===

    /** Removes everything from the given batch's trailer line on: its records stay, its sentinel goes. */
    private static void cutBeforeBatchTrailer(final Path journal, final int batch) throws Exception {
        final String text = XdStorageCrashedStorages.read(journal);
        int at = -1;
        for (int i = 0; i < batch; i++) {
            at = text.indexOf("# xdstorage-meta ", at + 1);
            assertTrue(at >= 0, "batch " + (i + 1) + " has a trailer");
        }
        Files.write(journal, text.substring(0, at).getBytes(StandardCharsets.UTF_8));
    }

    /** Keeps the first lines of the last batch's record only — the file ends inside the record's body. */
    private static void truncateInsideLastBatch(final Path journal) throws Exception {
        final List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        int lastTrailer = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("# xdstorage-meta ")) {
                lastTrailer = i;
            }
        }
        assertTrue(lastTrailer > 0, "a trailer line");
        int previousSentinel = -1;
        for (int i = lastTrailer - 1; i >= 0; i--) {
            if (lines.get(i).trim().equals("# xdstorage-eof")) {
                previousSentinel = i;
                break;
            }
        }
        assertTrue(previousSentinel >= 0, "two batches at least");
        // the last record starts right after that sentinel: "- record:", serial, op, id — then "object:"
        final String cut = String.join("\r\n", lines.subList(0, previousSentinel + 5));
        Files.write(journal, cut.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, XdStorageCrashedStorages.batches(journal), "two sentinels left, the third is gone");
    }
}
