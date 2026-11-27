package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.FilesFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.Stream;
import io.github.xdstorage.helpers.StorageFolderCopy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W-1 (WRITE-PATH-DESIGN.md): append-only commit journal за флагом journalWrites
 * (default OFF = прежняя полная перезапись файла). Данные класса живут в
 * {@code <base-without-ext>-journal.yaml (JRNLNAME)}; prepare() реплеит журнал поверх базы идемпотентно;
 * оборванный хвост после последнего сентинела отбрасывается с WARN.
 */
@DisplayName("W-1: журнал коммитов (append-only, за флагом)")
public class XdStorageW1JournalTest {

    private static final String NAME = "w1-journal-db";
    private static final String DATA_DIR = "data/BenchmarkEntity";

    @Test
    @DisplayName("Данные живут в журнале до чекпоинта; W-4c: чистый shutdown чекпоинтит (база пишется, журнал пуст)")
    public void testJournalOnlyRoundTrip() throws Exception {
        final Path dir = Files.createTempDirectory("w1_roundtrip");
        try {
            // W-4c (task 32): commits live in the journal UNTIL a checkpoint — and a clean
            // shutdown IS a checkpoint now. The journal-only state is asserted before the
            // shutdown; after it the base is authoritative and the journal is empty.
            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, config());
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                storage.save(new BenchmarkEntity("j-1", "alpha"), tx);
                storage.save(new BenchmarkEntity("j-2", "beta"), tx);
                storage.save(new BenchmarkEntity("j-3", "gamma"), tx);
                tx.commit();

                assertNull(findDataBaseFile(dir), "base file must NOT be written before the checkpoint");
                final Path journalPath = findJournalFile(dir);
                assertNotNull(journalPath, "commit journal must exist");
                final String journal = new String(Files.readAllBytes(journalPath), StandardCharsets.UTF_8);
                assertTrue(journal.contains("# xdstorage-eof"), "each append ends with the EOF sentinel");
                assertTrue(journal.contains("UPSERT"));
            } finally {
                storage.shutdown();
            }

            assertNotNull(findDataBaseFile(dir), "W-4c: the shutdown checkpoint writes the base");
            assertNull(findJournalFile(dir), "W-4c: the shutdown checkpoint empties the journal");
            assertEquals(3, loadCount(dir, config()), "data loads from the checkpointed base");
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("DELETE и UPDATE реплеятся: удаление исчезает, обновление видно после переоткрытия")
    public void testDeleteAndUpdateReplay() throws Exception {
        final Path dir = Files.createTempDirectory("w1_delete_update");
        try {
            openSaveShutdown(dir, config(), saver -> {
                saver.save(new BenchmarkEntity("d-1", "keep"));
                saver.save(new BenchmarkEntity("d-2", "obsolete"));
                saver.save(new BenchmarkEntity("d-3", "old-payload"));
            });

            IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, config());
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                storage.delete(new BenchmarkEntity("d-2", "obsolete"), tx);
                storage.update(new BenchmarkEntity("d-3", "new-payload"), tx);
                tx.commit();
            } finally {
                storage.shutdown();
            }

            IXdFileStorage reader = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, config());
            try {
                final IXdStorageTransaction tx = reader.beginTransaction();
                final Collection<BenchmarkEntity> loaded = reader.load(BenchmarkEntity.class, tx);
                assertEquals(2, loaded.size(), "deleted record must not survive replay");
                tx.commit();
                final IXdStorageTransaction tx2 = reader.beginTransaction();
                final BenchmarkEntity updated = reader.load(BenchmarkEntity.class, "d-3", tx2);
                assertNotNull(updated);
                assertEquals("new-payload", updated.getPayload(), "update must be replayed");
                tx2.commit();
            } finally {
                reader.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("Чекпоинт по порогу: база переписывается целиком, журнал сбрасывается, данные целы")
    public void testCheckpointResetsJournal() throws Exception {
        final Path dir = Files.createTempDirectory("w1_checkpoint");
        try {
            final XdStorageConfig tinyThreshold = XdStorageConfig.builder()
                    .journalWrites(true)
                    .journalCheckpointBytes(100L)   // каждый коммит сразу превышает порог
                    .build();
            openSaveShutdown(dir, tinyThreshold, saver -> {
                saver.save(new BenchmarkEntity("c-1", "alpha"));
                saver.save(new BenchmarkEntity("c-2", "beta"));
            });

            assertNull(findJournalFile(dir), "checkpoint must reset the journal");
            assertNotNull(findDataBaseFile(dir), "checkpoint must write the base file");
            assertEquals(2, loadCount(dir, tinyThreshold), "post-checkpoint state must load from the base");
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("Оборванный хвост журнала отбрасывается с WARN (не роняет открытие) — crash proxy: копия при " +
            "открытом хранилище")
    public void testTornTailDiscarded() throws Exception {
        final Path dir = Files.createTempDirectory("w1_torn_tail");
        final Path copy = dir.resolveSibling("w1_torn_tail_copy");
        try {
            // W-4c: a torn journal only exists when NO clean shutdown happened — the crash
            // proxy is a folder copy taken while the storage is open (task 19 semantics):
            // the copy carries the journals exactly as the power loss left them.
            IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, config());
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                storage.save(new BenchmarkEntity("t-1", "alpha"), tx);
                storage.save(new BenchmarkEntity("t-2", "beta"), tx);
                tx.commit();

                // the folder lock's file stays behind: it is held while the storage is open
                StorageFolderCopy.copy(dir, copy);
            } finally {
                storage.shutdown();
                storage = null;
            }

            final Path journalPath = findJournalFile(copy);
            assertNotNull(journalPath, "the crash artifact carries the journal");
            // эмулируем сбой посреди аппенда: обрывок записи без сентинела
            Files.write(journalPath, "\r\n- record:\r\n\tserial: 99\r\n\top: UPS".getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);

            final IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage(NAME + "-copy", copy.toString(), 128,
                    config());
            try {
                final IXdStorageTransaction tx = reopened.beginTransaction();
                final int size = reopened.load(BenchmarkEntity.class, tx).size();
                tx.commit();
                assertEquals(2, size, "committed records (before the tear) must load; torn tail discarded");
            } finally {
                reopened.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("W-3: журнал по умолчанию — данные в журнале до чекпоинта; W-4c: shutdown чекпоинтит")
    public void testJournalOnByDefault() throws Exception {
        final Path dir = Files.createTempDirectory("w1_default_on");
        try {
            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128,
                    XdStorageConfig.defaults());
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                storage.save(new BenchmarkEntity("o-1", "alpha"), tx);
                tx.commit();

                assertNull(findDataBaseFile(dir),
                        "default (W-3): commits are journaled, base not written before checkpoint");
                assertNotNull(findJournalFile(dir), "default (W-3): journal file exists");
            } finally {
                storage.shutdown();
            }
            assertNotNull(findDataBaseFile(dir), "W-4c: the shutdown checkpoint writes the base");
            assertNull(findJournalFile(dir), "W-4c: the shutdown checkpoint empties the journal");
            assertEquals(1, loadCount(dir, XdStorageConfig.defaults()), "default (W-3): the data survives");
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("journalWrites для не-YAML формата — громкий отказ при открытии")
    public void testJournalRequiresYamlFormat() {
        final XdStorageConfig journalCfg = XdStorageConfig.builder().journalWrites(true).build();
        assertThrows(RuntimeException.class,
                () -> XdStorageProvider.newOrGetFileStorage("w1-xml", "./target/w1_xml_db", 128, FilesFormat.XML,
                        journalCfg),
                "journal is YAML-only in W-1");
    }

    /** фрагментное имя: BenchmarkEntity-<frag>.yaml — ищем, а не хардкодим. */
    private Path findDataBaseFile(final Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir.resolve(DATA_DIR))) {
            return files.filter(p -> {
                        final String n = p.getFileName().toString();
                        // JRNLNAME: journals ride the base name under BOTH spellings — neither is a base file
                        return n.startsWith("BenchmarkEntity") && n.endsWith(".yaml")
                                && !n.endsWith(".journal.yaml") && !n.endsWith("-journal.yaml");
                    })
                    .findFirst().orElse(null);
        }
    }

    private Path findJournalFile(final Path dir) throws Exception {
        // JRNLNAME: journals live in the journal/ subdirectory of the resource's directory
        try (Stream<Path> files = Files.walk(dir.resolve(DATA_DIR))) {
            return files.filter(p -> p.getFileName().toString()
                            .endsWith(io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.JOURNAL_SUFFIX))
                    .findFirst().orElse(null);
        }
    }

    private XdStorageConfig config() {
        return XdStorageConfig.builder().journalWrites(true).build();
    }

    private interface EntitySaver {
        void save(BenchmarkEntity entity) throws Exception;
    }

    private interface SaverBlock {
        void save(EntitySaver saver) throws Exception;
    }

    private void openSaveShutdown(final Path dir, final XdStorageConfig config, final SaverBlock block) throws Exception
            {
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, config);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final EntitySaver entitySaver = entity -> storage.save(entity, tx);
            block.save(entitySaver);
            tx.commit();
        } finally {
            storage.shutdown();
        }
    }

    private int loadCount(final Path dir, final XdStorageConfig config) {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, config);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final int size = storage.load(BenchmarkEntity.class, tx).size();
            tx.commit();
            return size;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        } finally {
            storage.shutdown();
        }
    }

    private void deleteQuietly(final Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Throwable ignored) {
        }
    }
}
