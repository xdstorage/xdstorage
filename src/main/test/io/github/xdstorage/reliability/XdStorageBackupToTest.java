package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.entities.XdStar;
import io.github.xdstorage.entities.XdStarSystem;
import io.github.xdstorage.entities.map.ObjectsMapObject;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.search.RangeOpsEntity;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionManager;
import io.github.xdstorage.utils.FilesCopy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BACKUP (docs/tasks/2.2.0/15): {@code IXdFileStorage.backupTo(Path)}.
 *
 * <p>Two things are held here beyond "the copy opens". First, that the copy STANDS ON ITS
 * OWN: a storage folder names its files by path, so a folder copied by hand keeps reading
 * the original's files — the source is moved away before every copy is opened here, and no
 * file of the copy may name the source. Second, that taking a copy costs the rest of the
 * application nothing: the first version put a global lock around {@code beginTransaction}
 * and {@code checkpoint()}, and a save with a generated id racing a checkpoint deadlocked
 * on it — pinned by {@link #aTransactionBeginsWhileAnotherThreadCheckpoints}.</p>
 */
@DisplayName("BACKUP (2.2.0/15): backupTo(Path) — копия, которая стоит сама")
class XdStorageBackupToTest {

    @TempDir
    Path tempDir;

    // === the copy stands on its own ===

    @Test
    @DisplayName("1. Копия открывается и отвечает на все запросы — когда исходник убран")
    void theCopyAnswersWhenTheSourceIsGone() throws Exception {
        final Path original = tempDir.resolve("original");
        Files.createDirectories(original);
        final IXdFileStorage storage = open("backup-full-" + System.nanoTime(), original.toString());
        writeEveryPolicy(storage);

        final Path backup = tempDir.resolve("backup");
        storage.backupTo(backup);
        Files.move(original, tempDir.resolve("original-moved-away"));

        final XdStorageVerifyReport report = XdStorageProvider.verify(backup.toString());
        assertTrue(report.isOk(), "the copy passes verify — " + report);
        assertNoFileNames(backup, original.toAbsolutePath().toString());

        final IXdFileStorage copy = open("backup-full-copy-" + System.nanoTime(), backup.toString());
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            try {
                assertEveryPolicy(copy, readTx);
                readTx.commit();
            } catch (final RuntimeException e) {
                copy.rollbackTransaction(readTx);
                throw e;
            }
        } finally {
            copy.shutdown();
        }
    }

    @Test
    @DisplayName("2. Исходник открыт по относительному пути: копия называет свои файлы собственным путём")
    void aRelativeFolderIsCopiedToItsOwnAbsolutePath() throws Exception {
        final Path relative = Paths.get("target", "backup-relative-" + System.nanoTime());
        Files.createDirectories(relative);
        final IXdFileStorage storage = open("backup-relative-" + System.nanoTime(), relative.toString());
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(newMapObject("rel-1"), tx);
        storage.commitTransaction(tx);

        final Path backup = tempDir.resolve("backup-of-relative");
        storage.backupTo(backup);

        FilesCopy.deleteRecursively(relative);
        assertFalse(Files.exists(relative), "the source is gone");
        final IXdFileStorage copy = open("backup-relative-copy-" + System.nanoTime(), backup.toString());
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            assertEquals("v-1", copy.load(ObjectsMapObject.class, "rel-1", readTx).getMap().get("k-1"),
                    "the copy answers without the source and regardless of the working directory");
            readTx.commit();
        } finally {
            copy.shutdown();
        }
    }

    @Test
    @DisplayName("3. Файл блокировки в копию не попадает")
    void theLockFileIsNotCopied() throws Exception {
        final Path original = tempDir.resolve("original-lock");
        Files.createDirectories(original);
        final IXdFileStorage storage = open("backup-lock-" + System.nanoTime(), original.toString());
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(newMapObject("lock-1"), tx);
        storage.commitTransaction(tx);

        final Path backup = tempDir.resolve("backup-lock");
        storage.backupTo(backup);

        assertFalse(Files.exists(backup.resolve(FilesCopy.LOCK_FILE_NAME)), "the lock file must not travel");
        assertTrue(Files.exists(backup.resolve(".xdstorage.meta")), "the format marker must");
    }

    // === the contract ===

    @Test
    @DisplayName("4. backupTo закрывает исходник: копия отвечает, исходник отказывает до переоткрытия")
    void backupToClosesTheSource() throws Exception {
        final Path original = tempDir.resolve("original-open");
        Files.createDirectories(original);
        final IXdFileStorage storage = open("backup-open-" + System.nanoTime(), original.toString());
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        for (int i = 1; i <= 20; i++) {
            storage.save(newShip(i), tx);
        }
        storage.commitTransaction(tx);

        final Path backup = tempDir.resolve("backup-of-open");
        storage.backupTo(backup);

        final XdStorageRuntimeException closed = assertThrows(XdStorageRuntimeException.class,
                storage::beginTransaction, "the storage is shut down by the call");
        assertTrue(closed.getMessage().contains("is shut down"), closed.getMessage());
        final IXdFileStorage copy = open("backup-open-copy-" + System.nanoTime(), backup.toString());
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            assertEquals(20, copy.load(XdStorageW2SearchShip.class, o -> true, readTx).size());
            assertEquals("Ship-10", copy.load(XdStorageW2SearchShip.class, 10L, readTx).getName());
            readTx.commit();
        } finally {
            copy.shutdown();
        }
    }

    @Test
    @DisplayName("5. Назначение внутри папки хранилища: отказ, хранилище остаётся открытым")
    void refusesADestinationInsideTheStorageFolder() throws Exception {
        final Path original = tempDir.resolve("original-inside");
        Files.createDirectories(original);
        final IXdFileStorage storage = open("backup-inside-" + System.nanoTime(), original.toString());

        final XdStorageRuntimeException refused = assertThrows(XdStorageRuntimeException.class,
                () -> storage.backupTo(original.resolve("backup")));

        assertTrue(refused.getMessage().contains("is inside the storage folder"), refused.getMessage());
        storage.rollbackTransaction(storage.beginTransaction(60_000L));
        storage.shutdown();
    }

    @Test
    @DisplayName("6. Назначение существует и не пусто: отказ, ничего в нём не тронуто")
    void refusesANonEmptyDestination() throws Exception {
        final Path original = tempDir.resolve("original-nonempty");
        Files.createDirectories(original);
        final IXdFileStorage storage = open("backup-nonempty-" + System.nanoTime(), original.toString());
        final Path destination = tempDir.resolve("taken");
        Files.createDirectories(destination);
        Files.write(destination.resolve("mine.txt"), "keep me".getBytes(StandardCharsets.UTF_8));

        final XdStorageRuntimeException refused = assertThrows(XdStorageRuntimeException.class,
                () -> storage.backupTo(destination));

        assertTrue(refused.getMessage().contains("not empty"), refused.getMessage());
        assertEquals("keep me", new String(Files.readAllBytes(destination.resolve("mine.txt")),
                StandardCharsets.UTF_8));
        try (Stream<Path> entries = Files.list(destination)) {
            assertEquals(1L, entries.count(), "nothing was added to the destination");
        }
        storage.rollbackTransaction(storage.beginTransaction(60_000L));
        storage.shutdown();
    }

    @Test
    @DisplayName("7. Уже закрытое хранилище: отказ")
    void refusesOnAShutDownStorage() throws Exception {
        final Path original = tempDir.resolve("original-closed");
        Files.createDirectories(original);
        final IXdFileStorage storage = open("backup-closed-" + System.nanoTime(), original.toString());
        storage.shutdown();

        assertThrows(XdStorageRuntimeException.class, () -> storage.backupTo(tempDir.resolve("backup-of-closed")));
        assertFalse(Files.exists(tempDir.resolve("backup-of-closed")));
    }

    // === what taking a copy costs the rest of the application ===

    @Test
    @DisplayName("8. Транзакция начинается, пока другой поток делает checkpoint — без ожидания")
    void aTransactionBeginsWhileAnotherThreadCheckpoints() throws Exception {
        final Path original = tempDir.resolve("original-checkpoint");
        Files.createDirectories(original);
        final IXdFileStorage storage = open("backup-checkpoint-" + System.nanoTime(), original.toString());
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            storage.save(newMapObject("cp-1"), tx);
            storage.commitTransaction(tx);
            final IXdStorageTransaction open = storage.beginTransaction(60_000L);
            final XdStorageTransactionManager manager = (XdStorageTransactionManager) storage.getTransactionManager();
            // what every facade operation does around its body: count itself as in flight
            manager.incrementActiveThreads(open.getTransactionId());
            final Thread checkpoint = new Thread(storage::checkpoint);
            checkpoint.start();
            Thread.sleep(300L);

            final CountDownLatch begun = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread saver = new Thread(() -> {
                try {
                    // the call the id generator makes inside a save
                    storage.beginTransaction(open, 1_000L);
                } catch (final Throwable t) {
                    failure.set(t);
                }
                begun.countDown();
            });
            saver.start();
            // the first version held a global lock from checkpoint() until its drain was done —
            // and the drain waited for exactly the operation this thread is inside of
            assertTrue(begun.await(5L, TimeUnit.SECONDS), "beginTransaction blocked behind the checkpoint");
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            manager.decrementActiveThreads(open.getTransactionId());
            checkpoint.join(TimeUnit.SECONDS.toMillis(10L));
            assertFalse(checkpoint.isAlive(), "the checkpoint finished");
            storage.rollbackTransaction(open);
        } finally {
            storage.shutdown();
        }
    }

    // ---- what test 1 writes and reads back: every storage policy and index kind ----

    private static void writeEveryPolicy(final IXdFileStorage storage) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            for (int i = 1; i <= 10; i++) {
                storage.save(newShip(i), tx);           // class fragments + crew child + String-keyed index
            }
            for (int i = 1; i <= 5; i++) {
                storage.save(new RangeOpsEntity("ro-" + i, (long) i * 100, "label-" + i), tx);
            }
            for (int i = 1; i <= 3; i++) {
                storage.save(newSystem(i), tx);          // parent + StoreWithParentObject child
            }
            for (int i = 1; i <= 2; i++) {
                storage.save(newMapObject("map-" + i), tx); // single-object policy
            }
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private static void assertEveryPolicy(final IXdFileStorage copy, final IXdStorageTransaction readTx)
            throws Exception {
        assertEquals(10, copy.load(XdStorageW2SearchShip.class, o -> true, readTx).size(),
                "all class-fragment objects are in the copy");
        assertNotNull(copy.load(XdStorageW2SearchShip.class, 3L, readTx), "load-by-id of a sampled ship");
        assertNotNull(copy.load(XdStorageW2SearchShip.class, 7L, readTx), "load-by-id of another sampled ship");
        final XdStorageW2SearchShip ship = copy.load(XdStorageW2SearchShip.class, 3L, readTx);
        assertEquals("Ship-3", ship.getName());
        assertEquals(2, ship.getCrew().size(), "the embedded child collection is in the copy");

        final XdStorageSearchQuery byName = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Ship-5"), null);
        final Collection<XdStorageW2SearchShip> named = copy.load(XdStorageW2SearchShip.class, "w2_ship_idx",
                byName, readTx);
        assertEquals(1, named.size(), "EQUALS on the String-keyed index answers from the copy");
        assertEquals(5L, named.iterator().next().getId(), "the answered object is Ship-5 itself");

        final XdStorageSearchQuery scoreWindow = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(200L, 400L)),
                null);
        final Collection<RangeOpsEntity> ranged = copy.load(RangeOpsEntity.class, "range_idx", scoreWindow, readTx);
        assertEquals(3, ranged.size(), "the range on the numeric index answers from the copy");
        assertEquals(3L, copy.count(RangeOpsEntity.class, "range_idx", scoreWindow, readTx),
                "count on the numeric index agrees with load");

        assertEquals(3, copy.load(XdStarSystem.class, o -> true, readTx).size(), "the parent objects are in the copy");
        final XdStarSystem system = copy.load(XdStarSystem.class, "sys-2", readTx);
        assertNotNull(system, "load-by-id of a parent object");
        assertEquals("system-2", system.getNewName());

        assertEquals(2, copy.load(ObjectsMapObject.class, o -> true, readTx).size(),
                "the single-object policy content is in the copy");
        final ObjectsMapObject mapObject = copy.load(ObjectsMapObject.class, "map-1", readTx);
        assertEquals("v-1", mapObject.getMap().get("k-1"), "the map payload is in the copy");

        final XdStorageW2SearchCrewMember captain = ship.getCaptain();
        assertEquals("Rank-3", captain.getRank(), "the crew child is in the copy with the ship");
    }

    /** No file of the copy may name the source folder's path. */
    private static void assertNoFileNames(final Path copy, final String source) throws IOException {
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(copy)) {
            for (final Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(source)) {
                    offenders.add(copy.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "files of the copy that still name the source: " + offenders);
    }

    // ---- fixtures ----

    private static IXdFileStorage open(final String name, final String folder) {
        return XdStorageProvider.newOrGetFileStorage(name, folder, 64);
    }

    private static XdStorageW2SearchShip newShip(final long i) {
        final XdStorageW2SearchShip ship = new XdStorageW2SearchShip();
        ship.setId(i);
        ship.setName("Ship-" + i);
        ship.setTonnage((int) (i % 50));
        ship.setCaptain(new XdStorageW2SearchCrewMember(i * 10, "Rank-" + (i % 5)));
        ship.addCrewMember(new XdStorageW2SearchCrewMember(i * 10 + 1, "Rank-3"));
        ship.addCrewMember(new XdStorageW2SearchCrewMember(i * 10 + 2, "Rank-4"));
        return ship;
    }

    private static XdStarSystem newSystem(final int i) {
        final XdStarSystem system = new XdStarSystem();
        system.setId("sys-" + i);
        system.setNewName("system-" + i);
        final XdStar star = new XdStar();
        star.setId("star-" + i);
        star.setName("Star-" + i);
        star.setParent(system);
        system.setStars(new ArrayList<>(Arrays.asList(star)));
        return system;
    }

    private static ObjectsMapObject newMapObject(final String id) {
        final ObjectsMapObject o = new ObjectsMapObject();
        o.setId(id);
        final Map<Object, Object> map = new HashMap<>();
        map.put("k-1", "v-1");
        map.put("k-2", "v-2");
        o.setMap(map);
        return o;
    }
}
