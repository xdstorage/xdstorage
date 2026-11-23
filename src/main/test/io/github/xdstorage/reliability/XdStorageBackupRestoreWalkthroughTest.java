package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.RangeOpsEntity;
import io.github.xdstorage.entities.XdStar;
import io.github.xdstorage.entities.XdStarSystem;
import io.github.xdstorage.entities.map.ObjectsMapObject;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKUP (docs/tasks/2.1.0/19): "a folder you can back up with cp -r while closed" — the
 * operability sentence from DECISION-GUIDE § wins, made a tested contract.
 *
 * <p>The walkthrough: build a storage with content under all three storage policies (class
 * fragments with a numeric-keyed search index and a String-keyed one, a single-object entity,
 * a parent object with a StoreWithParentObject child), shut it down, deep-copy the folder with
 * plain file operations, and open the COPY under a new storage name — every read must answer
 * from the copy exactly as from the original.
 *
 * <p><b>The lock contract:</b> the lock FILE (`.xdstorage.lock`) exists while the storage is
 * open and REMAINS after a clean {@code shutdown()} — what shutdown releases is the exclusive
 * OS file lock. A copied folder therefore travels with a harmless lock file and opens cleanly
 * (pinned). Copying an OPEN folder is unsupported: same-machine copies are blocked by the OS
 * lock (S-3), and a copied-while-open folder has no defined content state. The procedure is
 * close → copy → open the copy; the negative side is pinned by asserting the lock file's
 * presence while open, and the release by re-opening the original folder.
 *
 * <p>No engine code: this test composes guarantees that already exist (atomic rename
 * write-outs, EOF sentinels, the format marker, the folder lock) under the one operation
 * every ops engineer will perform.
 */
@DisplayName("BACKUP: cp -r while closed is a tested guarantee")
public class XdStorageBackupRestoreWalkthroughTest {

    @TempDir
    Path tempDir;

    @Test
    public void copiedFolderRestoresContentIndexesAndLockContract() throws Exception {
        final Path original = tempDir.resolve("original");
        Files.createDirectories(original);
        final String originalName = "backup-walkthrough-" + System.nanoTime();

        // ---- 1. build content across all three storage policies ------------------------
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(originalName, original.toString(), 64);
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            for (int i = 1; i <= 10; i++) {
                storage.save(newShip(i), tx);           // class fragments + crew child + String-keyed index
            }
            for (int i = 1; i <= 5; i++) {
                storage.save(new RangeOpsEntity("ro-" + i, (long) i * 100, "label-" + i), tx); // numeric-keyed index
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

        // ---- 2. the lock contract --------------------------------------------------------
        // The lock FILE exists while open and REMAINS after a clean shutdown — what shutdown
        // releases is the OS file lock. So a copied folder MAY carry the lock file (it is
        // harmless — pinned below by opening the copy) while an OPEN folder is protected by
        // the exclusive OS lock itself. Proof of release: the original folder re-opens.
        final Path lockFile = original.resolve(".xdstorage.lock");
        assertTrue(Files.exists(lockFile), "the folder lock file exists while the storage is open "
                + "(copying an open folder is unsupported — this is the visible marker)");
        storage.shutdown();

        // the release proof: the same folder re-opens (an exclusive OS lock would reject it)
        final IXdFileStorage reopenOriginal = XdStorageProvider.newOrGetFileStorage(
                originalName + "-recheck", original.toString(), 64);
        reopenOriginal.shutdown();

        // ---- 3. the backup: a plain file copy of the closed folder ----------------------
        final Path backup = tempDir.resolve("backup");
        try (Stream<Path> walk = Files.walk(original)) {
            for (final Path src : walk.collect(Collectors.toList())) {
                final Path dest = backup.resolve(original.relativize(src).toString());
                if (src.toFile().isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // ---- 4. open the COPY under a new storage name; everything must answer ----------
        assertTrue(Files.exists(backup.resolve(".xdstorage.lock")),
                "the lock file travels with the backup — and is harmless (released)");
        final IXdFileStorage copy = XdStorageProvider.newOrGetFileStorage(
                originalName + "-copy", backup.toString(), 64);
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            try {
                // class fragments: count via the search scan, load-by-id of samples
                final int ships = copy.load(XdStorageW2SearchShip.class, o -> true, readTx).size();
                assertEquals(10, ships, "all class-fragment objects survived the copy");
                assertNotNull(copy.load(XdStorageW2SearchShip.class, 3L, readTx), "load-by-id of a sampled ship");
                assertNotNull(copy.load(XdStorageW2SearchShip.class, 7L, readTx), "load-by-id of another sampled ship");
                final XdStorageW2SearchShip ship = copy.load(XdStorageW2SearchShip.class, 3L, readTx);
                assertEquals("Ship-3", ship.getName());
                assertEquals(2, ship.getCrew().size(), "the embedded child collection traveled");

                // String-keyed search index: EQUALS answers from the copy
                final XdStorageSearchQuery byName = new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Ship-5"), null);
                final java.util.Collection<XdStorageW2SearchShip> named = copy.load(XdStorageW2SearchShip.class,
                        "w2_ship_idx", byName, readTx);
                assertEquals(1, named.size(), "EQUALS on the String-keyed index answers from the copy");
                assertEquals(5L, named.iterator().next().getId(), "the answered object is Ship-5 itself");

                // numeric-keyed search index: range load + count (BUG-E semantics travel too)
                final XdStorageSearchQuery scoreWindow = new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                                new XdStorageRange(200L, 400L)), null);
                final java.util.Collection<RangeOpsEntity> ranged = copy.load(RangeOpsEntity.class, "range_idx",
                        scoreWindow, readTx);
                assertEquals(3, ranged.size(), "the range on the numeric index answers from the copy");
                assertEquals(3L, copy.count(RangeOpsEntity.class, "range_idx", scoreWindow, readTx),
                        "count on the numeric index agrees with load");

                // parent + StoreWithParentObject child
                assertEquals(3, copy.load(XdStarSystem.class, o -> true, readTx).size(),
                        "the parent objects survived the copy");
                final XdStarSystem system = copy.load(XdStarSystem.class, "sys-2", readTx);
                assertNotNull(system, "load-by-id of a parent object");
                assertEquals("system-2", system.getNewName());

                // single-object policy
                assertEquals(2, copy.load(ObjectsMapObject.class, o -> true, readTx).size(),
                        "the single-object policy content survived the copy");
                final ObjectsMapObject mapObject = copy.load(ObjectsMapObject.class, "map-1", readTx);
                assertEquals("v-1", mapObject.getMap().get("k-1"), "the map payload traveled");

                // the StoreWithParentObject child, addressed through the loaded parent content
                final XdStorageW2SearchCrewMember captain = ship.getCaptain();
                assertEquals("Rank-3", captain.getRank(), "the crew child content traveled with the ship");
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
    @DisplayName("a stale lock file does not strand the copied folder (the recovery contract)")
    public void staleLockInCopyDoesNotStrandTheFolder() throws Exception {
        final Path original = tempDir.resolve("original2");
        Files.createDirectories(original);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "backup-stale-" + System.nanoTime(), original.toString(), 64);
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            storage.save(newShip(1), tx);
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
        storage.shutdown();

        final Path backup = tempDir.resolve("backup2");
        try (Stream<Path> walk = Files.walk(original)) {
            for (final Path src : walk.collect(Collectors.toList())) {
                final Path dest = backup.resolve(original.relativize(src).toString());
                if (src.toFile().isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // an operator might copy a lock file left by an unclean shutdown — the copy must
        // still open (the S-3 lock is the OS file lock, not the file's mere presence)
        Files.write(backup.resolve(".xdstorage.lock"), "stale".getBytes());

        final IXdFileStorage copy = XdStorageProvider.newOrGetFileStorage(
                "backup-stale-copy-" + System.nanoTime(), backup.toString(), 64);
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            final XdStorageW2SearchShip loaded = copy.load(XdStorageW2SearchShip.class, 1L, readTx);
            copy.commitTransaction(readTx);
            assertNotNull(loaded, "the copied folder opens and answers despite a stale lock file");
        } finally {
            copy.shutdown();
        }
    }

    // ---- fixtures ----

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
        // a mutable ArrayList: the engine's cloner needs a no-arg-constructible collection
        system.setStars(new java.util.ArrayList<>(Arrays.asList(star)));
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
