package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W-4a/W-4c acceptance (docs/tasks/2.1.0/32, W4-DESIGN.md): the hash id-index commit
 * journal — round-trip with NUMERIC ids (the BUG-C replay-typing lesson), the
 * flag-off→flag-on upgrade path, replay without a clean shutdown (the crash proxy, via a
 * folder copy — task 19's semantics), torn-tail discard, delete replay, and the W-4c
 * shutdown checkpoint emptying every journal.
 */
@DisplayName("W-4a/W-4c: the hash id-index commit journal")
public class XdStorageIndexJournalTest {

    private static final XdStorageConfig JOURNAL_ON = XdStorageConfig.builder().indexJournalWrites(true).build();
    private static final XdStorageConfig JOURNAL_OFF = XdStorageConfig.defaults();   // indexJournalWrites=false

    @TempDir
    Path tempDir;

    /** The current storage so @AfterEach can always close it (shutdown is idempotent). */
    private IXdFileStorage storage;

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
    }

    private IXdFileStorage open(final String name, final Path folder, final XdStorageConfig config) {
        final IXdFileStorage opened = XdStorageProvider.newOrGetFileStorage(name, folder.toString(), 64, config);
        storage = opened;
        return opened;
    }

    private void save(final IXdFileStorage storage, final long id, final String name) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            storage.save(new IndexJournalEntity(id, name), tx);
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private IndexJournalEntity load(final IXdFileStorage storage, final long id) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            final IndexJournalEntity entity = storage.load(IndexJournalEntity.class, id, tx);
            storage.commitTransaction(tx);
            return entity;
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private long countJournals(final Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            // JRNLNAME: the suffix spelling (the legacy stacked name is migration-only)
            return walk.filter(p -> p.getFileName().toString()
                    .endsWith(io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.JOURNAL_SUFFIX))
                            .count();
        }
    }

    private void copyFolder(final Path from, final Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            walk.forEach(source -> {
                try {
                    final Path target = to.resolve(from.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    @DisplayName("round trip: journal written, numeric ids replayable, shutdown checkpoint empties it")
    public void journalRoundTripWithNumericIds() throws Exception {
        final String name = "idx-journal-rt-" + System.nanoTime();
        final Path db = tempDir.resolve("db");
        IXdFileStorage storage = open(name, db, JOURNAL_ON);
        for (long id = 100; id < 130; id++) {
            save(storage, id, "entity-" + id);
        }
        assertTrue(countJournals(db) > 0, "the commit journals exist during the session");

        storage.shutdown();
        assertEquals(0L, countJournals(db), "W-4c: a clean shutdown checkpoints (empties) every journal");
        storage = null;

        storage = open(name, db, JOURNAL_ON);
        for (long id = 100; id < 130; id++) {
            final IndexJournalEntity entity = load(storage, id);
            assertNotNull(entity, "id " + id + " addressable after shutdown+reopen");
            assertEquals("entity-" + id, entity.getName());
        }
    }

    @Test
    @DisplayName("upgrade: a journal-off folder opens under the flag and keeps journaling")
    public void upgradeFromJournalOff() throws Exception {
        final String name = "idx-journal-up-" + System.nanoTime();
        final Path db = tempDir.resolve("db");
        IXdFileStorage storage = open(name, db, JOURNAL_OFF);
        for (long id = 1; id <= 10; id++) {
            save(storage, id, "e-" + id);
        }
        storage.shutdown();
        storage = null;

        storage = open(name, db, JOURNAL_ON);
        for (long id = 1; id <= 10; id++) {
            assertNotNull(load(storage, id), "base-only data survives the flag flip");
        }
        for (long id = 11; id <= 20; id++) {
            save(storage, id, "e-" + id);
        }
        storage.shutdown();
        storage = null;

        storage = open(name, db, JOURNAL_ON);
        for (long id = 1; id <= 20; id++) {
            final IndexJournalEntity entity = load(storage, id);
            assertNotNull(entity, "id " + id + " addressable after the upgrade cycle");
        }
    }

    @Test
    @DisplayName("crash proxy: a copied folder (no clean shutdown) replays the index journal")
    public void replayWithoutCleanShutdown() throws Exception {
        final String name = "idx-journal-crash-" + System.nanoTime();
        final Path db = tempDir.resolve("db");
        IXdFileStorage storage = open(name, db, JOURNAL_ON);
        for (long id = 1; id <= 15; id++) {
            save(storage, id, "e-" + id);
        }
        // no shutdown — the journals hold the commits; copy the folder as the crash proxy
        final Path copy = tempDir.resolve("copy");
        copyFolder(db, copy);
        assertTrue(countJournals(copy) > 0, "the copied folder carries un-checkpointed journals");
        storage.shutdown();   // W-4a soak hygiene: a leaked storage keeps its non-daemon pool alive
        storage = null;

        final IXdFileStorage reopened = open(name + "-copy", copy, JOURNAL_ON);
        try {
            for (long id = 1; id <= 15; id++) {
                final IndexJournalEntity entity = load(reopened, id);
                assertNotNull(entity, "id " + id + " replayed from the journal");
                assertEquals("e-" + id, entity.getName());
            }
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    @DisplayName("torn tail: garbage after the last sentinel is discarded at replay")
    public void tornTailDiscardedOnReplay() throws Exception {
        final String name = "idx-journal-torn-" + System.nanoTime();
        final Path db = tempDir.resolve("db");
        IXdFileStorage storage = open(name, db, JOURNAL_ON);
        for (long id = 1; id <= 10; id++) {
            save(storage, id, "e-" + id);
        }
        final Path copy = tempDir.resolve("copy");
        copyFolder(db, copy);
        storage.shutdown();   // W-4a soak hygiene: a leaked storage keeps its non-daemon pool alive
        storage = null;
        // tear the tail of every journal in the copy (simulates a crash mid-append)
        try (Stream<Path> walk = Files.walk(copy)) {
            walk.filter(p -> p.getFileName().toString()
                    .endsWith(io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.JOURNAL_SUFFIX))
                            .forEach(journal -> {
                try {
                    Files.write(journal, "\n- record:\n    serial: 999\n    op: UPSERT\n    id: 'torn'".getBytes(),
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        final IXdFileStorage reopened = open(name + "-copy", copy, JOURNAL_ON);
        try {
            for (long id = 1; id <= 10; id++) {
                assertNotNull(load(reopened, id), "id " + id + " survives the torn tail");
            }
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    @DisplayName("delete replay: a deleted id stays deleted after a journal replay")
    public void deleteReplayed() throws Exception {
        final String name = "idx-journal-del-" + System.nanoTime();
        final Path db = tempDir.resolve("db");
        IXdFileStorage storage = open(name, db, JOURNAL_ON);
        for (long id = 1; id <= 10; id++) {
            save(storage, id, "e-" + id);
        }
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            storage.delete(storage.load(IndexJournalEntity.class, 3L, tx), tx);
            storage.delete(storage.load(IndexJournalEntity.class, 7L, tx), tx);
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
        final Path copy = tempDir.resolve("copy");
        copyFolder(db, copy);
        storage.shutdown();   // W-4a soak hygiene: a leaked storage keeps its non-daemon pool alive
        storage = null;

        final IXdFileStorage reopened = open(name + "-copy", copy, JOURNAL_ON);
        try {
            assertNull(load(reopened, 3L), "deleted id 3 stays deleted after the replay");
            assertNull(load(reopened, 7L), "deleted id 7 stays deleted after the replay");
            assertNotNull(load(reopened, 4L));
        } finally {
            reopened.shutdown();
        }
    }
}
