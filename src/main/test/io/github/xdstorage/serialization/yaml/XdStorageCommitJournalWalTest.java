package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.Replay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * WAL (docs/tasks/2.2.0/06) — the commit journal's own contract. Three things are pinned:
 * the batch trailer (the commit serial and the transaction at every sentinel, written so
 * that an older reader is unaffected); the torn tail (discarded, with the exact serials
 * lost); and the two failures the old reader used to answer identically — a truncation is
 * still discarded, but a corruption with further complete batches after it now refuses.
 */
@DisplayName("WAL (2.2.0/06): трейлер партии, оборванный хвост, повреждение середины журнала")
public class XdStorageCommitJournalWalTest {

    /** The record body the journal carries. */
    public static class WalEntity {
        @XdStorageObjectId
        private String id;
        private String note;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    private static final String TX = "7d1c3a52-0000-4000-8000-000000000001";

    @TempDir
    Path folder;

    private XdStorageServicesLocator services;
    private XdStorageYamlIOFactory factory;
    private Path journal;

    @BeforeEach
    public void setUp() {
        services = mock(XdStorageServicesLocator.class);
        factory = new XdStorageYamlIOFactory(services, new XdStorageDefaultSimpleTypeHelper(),
                mock(IXdStorageIdGenerator.class));
        journal = folder.resolve("WalEntity-1-journal.yaml");
    }

    // === fixtures ===

    private static WalEntity entity(final String id, final String note) {
        final WalEntity e = new WalEntity();
        e.setId(id);
        e.setNote(note);
        return e;
    }

    private static XdStorageObjectChange insert(final String id, final String note) {
        return new XdStorageObjectChange(XdStorageObjectOperationType.Insert, id, null, entity(id, note));
    }

    /** One batch — one append, as a resource writes it for one commit. */
    private void append(final long serial, final String tx, final XdStorageObjectChange... changes) throws Exception {
        XdStorageYamlCommitJournal.appendRedo(journal, serial, tx, Arrays.asList(changes), services, factory, false);
    }

    private Replay replay() throws Exception {
        return XdStorageYamlCommitJournal.readForReplay(journal, String.class, factory);
    }

    private List<String> lines() throws Exception {
        return new ArrayList<>(Files.readAllLines(journal, StandardCharsets.UTF_8));
    }

    private static void writeLines(final Path file, final List<String> lines) throws Exception {
        Files.write(file, (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static int lineWith(final List<String> lines, final String fragment, final int occurrence) {
        int seen = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().contains(fragment) && ++seen == occurrence) {
                return i;
            }
        }
        throw new IllegalStateException("line #" + occurrence + " with '" + fragment + "' not found in " + lines);
    }

    private static WalEntity only(final List<Object> upserts) {
        assertEquals(1, upserts.size(), "upserts: " + upserts);
        return (WalEntity) upserts.get(0);
    }

    // === the batch trailer ===

    @Test
    @DisplayName("Запись партии: записи, трейлер с номером и транзакцией, затем обычный сантинел")
    public void appendWritesTheTrailerThenThePlainSentinel() throws Exception {
        append(17L, TX, insert("a", "alpha"), insert("b", "beta"));

        final List<String> lines = lines();
        final int trailer = lineWith(lines, XdStorageYamlTokenizer.TRAILER_PREFIX, 1);
        assertEquals("# xdstorage-meta serial=17 tx=" + TX, lines.get(trailer).trim(), "the trailer line");
        assertEquals("# xdstorage-eof", lines.get(trailer + 1).trim(), "the plain sentinel follows at once");
        assertEquals(trailer + 1, lastNonBlank(lines), "the sentinel is the last line of the file");
        assertEquals(2, countOf(lines, "- record:"), "both records of the commit");
        assertEquals(1, countStartingWith(lines, "# xdstorage-meta "), "in ONE batch — one trailer");
        assertEquals(1, countOf(lines, "# xdstorage-eof"), "one sentinel");
    }

    @Test
    @DisplayName("Журнал этой версии читается старым читателем: комментарии он пропускает, сантинел узнаёт")
    public void everyCommentLineIsSkippableByAnOldReader() throws Exception {
        append(17L, TX, insert("a", "alpha"));
        XdStorageYamlCommitJournal.appendCompensation(journal,
                Collections.singletonList(new XdStorageObjectChange(XdStorageObjectOperationType.Insert, "a",
                        entity("a", "before"), null)), services, factory, false);
        append(19L, null, insert("c", "gamma"));

        // an older tokenizer: a line starting with '#' is skipped unless it is the exact sentinel
        int sentinels = 0;
        for (final String line : lines()) {
            final String trimmed = line.trim();
            if (!trimmed.startsWith("#")) {
                continue;
            }
            if (XdStorageYamlTokenizer.EOF_SENTINEL_LINE.equals(trimmed)) {
                sentinels++;
            } else {
                assertTrue(trimmed.startsWith(XdStorageYamlTokenizer.TRAILER_PREFIX),
                        "a comment line an old reader does not know: '" + trimmed + "'");
            }
        }
        assertEquals(3, sentinels, "every batch is closed by a sentinel the old reader recognizes");
    }

    @Test
    @DisplayName("Партия без транзакции (компенсация): в трейлере только serial=0")
    public void aCompensationBatchNamesNoTransaction() throws Exception {
        // the rollback of an inserted record: the compensation is its DELETE
        XdStorageYamlCommitJournal.appendCompensation(journal, Collections.singletonList(
                new XdStorageObjectChange(XdStorageObjectOperationType.Insert, "a", null, entity("a", "new"))),
                services, factory, false);
        assertTrue(lines().contains("# xdstorage-meta serial=0"), "the compensation trailer: " + lines());
        final Replay replay = replay();
        assertEquals(Collections.singletonList("a"), replay.deletes, "the delete compensation replays");
        assertTrue(replay.replayedTransactions.isEmpty(), "a compensation belongs to no committed transaction");
        assertEquals(0L, replay.lastReplayedSerial, "a compensation is not a commit");
    }

    @Test
    @DisplayName("Реплей отдаёт трейлер: число партий, наивысший serial, транзакции")
    public void replayExposesTheTrailer() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX + "2", insert("b", "beta"));

        final Replay replay = replay();

        assertEquals(2, replay.replayedBatches);
        assertEquals(42L, replay.lastReplayedSerial);
        assertEquals(Arrays.asList(TX, TX + "2"), replay.replayedTransactions);
        assertEquals(0, replay.discardedTailRecords);
        assertEquals("beta", ((WalEntity) replay.upserts.get(1)).getNote());
    }

    @Test
    @DisplayName("Журнал без трейлеров, записанный до этой версии, читается как раньше")
    public void aJournalWithoutTrailersReplaysAsBefore() throws Exception {
        Files.write(journal, (record(5L, "a", "alpha") + "\r\n# xdstorage-eof\r\n"
                + record(6L, "b", "beta") + "\r\n# xdstorage-eof\r\n").getBytes(StandardCharsets.UTF_8));

        final Replay replay = replay();

        assertEquals(2, replay.upserts.size());
        assertEquals(6L, replay.lastReplayedSerial, "the serial comes from the records themselves");
        assertTrue(replay.replayedTransactions.isEmpty(), "nothing to name on an older journal");
        assertEquals(2, replay.replayedBatches);
    }

    @Test
    @DisplayName("Трейлер на самой строке сантинела (serial=<n> tx=<id>) тоже читается")
    public void theEnrichedSentinelSpellingIsRead() throws Exception {
        Files.write(journal, (record(5L, "a", "alpha") + "\r\n# xdstorage-eof serial=5 tx=abc\r\n")
                .getBytes(StandardCharsets.UTF_8));

        final Replay replay = replay();

        assertEquals(1, replay.upserts.size());
        assertEquals(5L, replay.lastReplayedSerial);
        assertEquals(Collections.singletonList("abc"), replay.replayedTransactions);
    }

    @Test
    @DisplayName("Трейлер расходится с записями своей партии: отказ")
    public void aTrailerThatDisagreesWithItsBatchIsRefused() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        final List<String> lines = lines();
        lines.set(lineWith(lines, TRAILER_OF_41, 1), "# xdstorage-meta serial=99 tx=" + TX);
        writeLines(journal, lines);

        final XdStorageIOException refusal = assertThrows(XdStorageIOException.class, this::replay);
        assertTrue(refusal.getMessage().contains("corrupt in batch 1"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("declares serial=99")
                && refusal.getMessage().contains("up to serial 41"), refusal.getMessage());
    }

    // === the torn tail: discarded, and the loss named exactly ===

    @Test
    @DisplayName("Обрыв перед трейлером последней партии: партия отброшена, потерянный serial назван точно")
    public void aTornTailBeforeItsTrailerReportsTheExactSerialLost() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"), insert("c", "gamma"));
        append(43L, TX, insert("d", "delta"));
        final List<String> lines = lines();
        writeLines(journal, lines.subList(0, lineWith(lines, "# xdstorage-meta serial=43", 1)));

        final Replay replay = replay();

        assertEquals(3, replay.upserts.size(), "the two complete batches replay in full");
        assertEquals(2, replay.replayedBatches);
        assertEquals(42L, replay.lastReplayedSerial);
        assertEquals(1, replay.discardedTailRecords, "the third batch's record");
        assertEquals(43L, replay.discardedSerialFrom);
        assertEquals(43L, replay.discardedSerialTo);
        assertEquals(0, replay.discardedCompensationRecords);
    }

    @Test
    @DisplayName("Обрыв внутри тела последней записи: запись посчитана, её serial назван")
    public void aCutInsideTheLastRecordStillCountsIt() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"));
        final List<String> lines = lines();
        final int serialLine = lineWith(lines, "serial: 42", 1);
        writeLines(journal, lines.subList(0, serialLine + 4));    // the object block, cut half-way

        final Replay replay = replay();

        assertEquals(1, replay.upserts.size(), "the first batch is intact");
        assertEquals(1, replay.discardedTailRecords, "the cut record is still a lost record");
        assertEquals(42L, replay.discardedSerialFrom);
        assertEquals(42L, replay.discardedSerialTo);
        assertEquals(0, replay.discardedUnnumberedRecords);
    }

    @Test
    @DisplayName("Обрыв до строки serial последней записи: запись посчитана, номер неизвестен")
    public void aCutBeforeTheSerialLineCountsAnUnnumberedRecord() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"));
        final List<String> lines = lines();
        writeLines(journal, lines.subList(0, lineWith(lines, "- record:", 2) + 1));    // only "- record:"

        final Replay replay = replay();

        assertEquals(1, replay.upserts.size());
        assertEquals(1, replay.discardedTailRecords);
        assertEquals(0L, replay.discardedSerialFrom, "nothing to name — the serial line was never written out");
        assertEquals(1, replay.discardedUnnumberedRecords);
    }

    @Test
    @DisplayName("Битое значение в последней партии, но партия закрыта сантинелом: это повреждение, отказ")
    public void aBrokenValueInsideACompleteLastBatchIsRefused() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"));
        final List<String> lines = lines();
        lines.set(lineWith(lines, "value: 'beta'", 1), "\t\t\t\tvalue: \"broken");
        writeLines(journal, lines);

        // the batch reached the disk whole — a crash cannot have written its sentinel after a
        // broken value; only damage can. Torn means "no sentinel after it", and this is not.
        final XdStorageIOException refusal = assertThrows(XdStorageIOException.class, this::replay);
        assertTrue(refusal.getMessage().contains("is CORRUPT in batch 2"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("closed by its own sentinel and 0 later batch(es)"),
                refusal.getMessage());
    }

    @Test
    @DisplayName("Обрыв, при котором значение не читается, но сантинела дальше нет: оборванный хвост, без отказа")
    public void anUnparseableValueWithNoSentinelAfterItIsATornTail() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"));
        final List<String> lines = lines();
        final int broken = lineWith(lines, "value: 'beta'", 1);
        lines.set(broken, "\t\t\t\tvalue: \"broken");
        writeLines(journal, lines.subList(0, broken + 2));    // the broken line, then the file ends

        final Replay replay = assertDoesNotThrow(this::replay, "nothing complete follows the failure");

        assertEquals("alpha", only(replay.upserts).getNote(), "the complete batch replays");
        assertEquals(1, replay.discardedTailRecords);
        assertEquals(42L, replay.discardedSerialFrom);
    }

    // === a corruption in the middle: refused ===

    @Test
    @DisplayName("Битое значение в СРЕДИНЕ журнала, дальше есть целые партии: реплей отказывает")
    public void aBrokenValueWithLaterBatchesIsRefused() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"));
        final List<String> lines = lines();
        lines.set(lineWith(lines, "value: 'alpha'", 1), "\t\t\t\tvalue: \"broken");
        writeLines(journal, lines);

        final XdStorageIOException refusal = assertThrows(XdStorageIOException.class, this::replay);
        final String message = refusal.getMessage();
        assertTrue(message.contains("is CORRUPT in batch 1"), message);
        assertTrue(message.contains("serial=41"), "the damaged batch is named by its own trailer: " + message);
        assertTrue(message.contains("not a torn tail"), message);
        assertTrue(message.contains("1 later batch(es) with 1 further record(s) follow"),
                "the unread remainder is counted, not just the pending list: " + message);
        assertTrue(message.contains("Refusing to replay"), message);
    }

    @Test
    @DisplayName("Неизвестный класс записи в середине журнала: реплей отказывает, а не теряет остаток")
    public void anUnknownClassWithLaterBatchesIsRefused() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"));
        final List<String> lines = lines();
        lines.set(lineWith(lines, WalEntity.class.getName(), 1),
                "\t\t\tclass: 'com.example.an.entity.that.is.gone.RemovedEntity'");
        writeLines(journal, lines);

        final XdStorageIOException refusal = assertThrows(XdStorageIOException.class, this::replay);
        assertTrue(refusal.getMessage().contains("is CORRUPT in batch 1"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("1 later batch(es) with 1 further record(s)"),
                refusal.getMessage());
    }

    @Test
    @DisplayName("Отказ в третьей партии из трёх: две целые партии названы как прочитанные")
    public void aFailureInTheThirdOfThreeBatchesNamesWhatWasRead() throws Exception {
        append(41L, TX, insert("a", "alpha"));
        append(42L, TX, insert("b", "beta"));
        append(43L, TX, insert("c", "gamma"));
        final List<String> lines = lines();
        lines.set(lineWith(lines, "value: 'beta'", 1), "\t\t\t\tvalue: \"broken");
        writeLines(journal, lines);

        final XdStorageIOException refusal = assertThrows(XdStorageIOException.class, this::replay);
        assertTrue(refusal.getMessage().contains("after 1 complete batch(es)"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("1 later batch(es) with 1 further record(s)"),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("1 upsert(s) / 0 delete(s) read before it"), refusal.getMessage());
    }

    // === the order of things in one journal ===

    @Test
    @DisplayName("Компенсация и redo в одном журнале: реплей в порядке файла, трейлеры различаются")
    public void compensationAndRedoReplayInFileOrder() throws Exception {
        XdStorageYamlCommitJournal.appendCompensation(journal,
                Collections.singletonList(new XdStorageObjectChange(XdStorageObjectOperationType.Insert, "a",
                        entity("a", "before"), null)), services, factory, false);
        append(7L, TX, insert("a", "after"));

        final Replay replay = replay();

        assertEquals(2, replay.upserts.size());
        assertEquals("before", ((WalEntity) replay.upserts.get(0)).getNote());
        assertEquals("after", ((WalEntity) replay.upserts.get(1)).getNote());
        assertEquals(2, replay.replayedBatches);
        assertEquals(7L, replay.lastReplayedSerial);
        assertEquals(Collections.singletonList(TX), replay.replayedTransactions);
        assertTrue(replay.deletes.isEmpty());
    }

    // === helpers ===

    private static final String TRAILER_OF_41 = "# xdstorage-meta serial=41";

    private static String record(final long serial, final String id, final String note) {
        return "- record:\r\n"
                + "\tserial: " + serial + "\r\n"
                + "\top: UPSERT\r\n"
                + "\tid: '" + id + "'\r\n"
                + "\tobject:\r\n"
                + "\t\tclass: '" + WalEntity.class.getName() + "'\r\n"
                + "\t\tfields:\r\n"
                + "\t\t\t- field:\r\n"
                + "\t\t\t\tname: 'note'\r\n"
                + "\t\t\t\ttype: 'java.lang.String'\r\n"
                + "\t\t\t\tvalue: '" + note + "'\r\n"
                + "\t\t\t- field:\r\n"
                + "\t\t\t\tname: 'id'\r\n"
                + "\t\t\t\ttype: 'java.lang.String'\r\n"
                + "\t\t\t\tvalue: '" + id + "'\r\n";
    }

    private static int countStartingWith(final List<String> lines, final String prefix) {
        int count = 0;
        for (final String line : lines) {
            if (line.trim().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static int lastNonBlank(final List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static int countOf(final List<String> lines, final String fragment) {
        int count = 0;
        for (final String line : lines) {
            if (line.trim().equals(fragment)) {
                count++;
            }
        }
        return count;
    }
}
