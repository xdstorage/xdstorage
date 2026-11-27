package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.entities.XdStar;
import io.github.xdstorage.entities.XdStarSystem;
import io.github.xdstorage.search.RangeOpsEntity;
import io.github.xdstorage.entities.map.ObjectsMapObject;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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
 * FP-5 (docs/tasks/2.2.0/15): `IXdFileStorage.backupTo(Path)` — programmatic backup
 * of a live storage folder into a self-contained destination directory.
 *
 * <p>The contract: the caller must {@link #shutdown()} the source first (or let
 * {@code backupTo} do it internally); backupTo internally checkpoints all journals,
 * shuts the storage down, then deep-copies the folder (minus .xdstorage.lock).
 */
@DisplayName("FP-5: backupTo(Path) — programmatic backup API")
public class XdStorageBackupToTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("backupTo produces a consistent copy that opens and answers queries")
    public void backupToProducesConsistentCopy() throws Exception {
        final Path original = tempDir.resolve("original");
        Files.createDirectories(original);
        final String originalName = "backup-to-" + System.nanoTime();

        // ---- 1. build content across all three storage policies ------------------
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(originalName, original.toString(), 64);
        {
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
        }

        // ---- 2. run backupTo (the method internally shut downs the source) -------
        final Path backup = tempDir.resolve("backup");
        storage.backupTo(backup);

        // ---- 3. verify the copy --------------------------------------------------
        final XdStorageVerifyReport report = XdStorageProvider.verify(backup.toString());
        assertTrue(report.isOk(), "backup folder passes verify — verdict: " + report);

        // ---- 4. open the copy and answer full queries (task-19 assertion set) ----
        final IXdFileStorage copy = XdStorageProvider.newOrGetFileStorage(originalName + "-copy", backup.toString(), 64);
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            try {
                // class fragments: count via the search scan, load-by-id of samples
                final int ships = copy.load(XdStorageW2SearchShip.class, o -> true, readTx).size();
                assertEquals(10, ships, "all class-fragment objects survived the backup");
                assertNotNull(copy.load(XdStorageW2SearchShip.class, 3L, readTx), "load-by-id of a sampled ship");
                assertNotNull(copy.load(XdStorageW2SearchShip.class, 7L, readTx), "load-by-id of another sampled ship");
                final XdStorageW2SearchShip ship = copy.load(XdStorageW2SearchShip.class, 3L, readTx);
                assertEquals("Ship-3", ship.getName());
                assertEquals(2, ship.getCrew().size(), "the embedded child collection survived");

                // String-keyed search index: EQUALS answers from the copy
                final XdStorageSearchQuery byName = new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Ship-5"), null);
                final java.util.Collection<XdStorageW2SearchShip> named = copy.load(XdStorageW2SearchShip.class, "w2_ship_idx", byName, readTx);
                assertEquals(1, named.size(), "EQUALS on the String-keyed index answers from the backup");
                assertEquals(5L, named.iterator().next().getId(), "the answered object is Ship-5 itself");

                // numeric-keyed search index: range load + count
                final XdStorageSearchQuery scoreWindow = new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                                new XdStorageRange(200L, 400L)), null);
                final java.util.Collection<RangeOpsEntity> ranged = copy.load(RangeOpsEntity.class, "range_idx", scoreWindow, readTx);
                assertEquals(3, ranged.size(), "the range on the numeric index answers from the backup");
                assertEquals(3L, copy.count(RangeOpsEntity.class, "range_idx", scoreWindow, readTx),
                        "count on the numeric index agrees with load");

                // parent + StoreWithParentObject child
                assertEquals(3, copy.load(XdStarSystem.class, o -> true, readTx).size(),
                        "the parent objects survived the backup");
                final XdStarSystem system = copy.load(XdStarSystem.class, "sys-2", readTx);
                assertNotNull(system, "load-by-id of a parent object");
                assertEquals("system-2", system.getNewName());

                // single-object policy
                assertEquals(2, copy.load(ObjectsMapObject.class, o -> true, readTx).size(),
                        "the single-object policy content survived the backup");
                final ObjectsMapObject mapObject = copy.load(ObjectsMapObject.class, "map-1", readTx);
                assertEquals("v-1", mapObject.getMap().get("k-1"), "the map payload survived");

                // the StoreWithParentObject child, addressed through the loaded parent content
                final XdStorageW2SearchCrewMember captain = ship.getCaptain();
                assertEquals("Rank-3", captain.getRank(), "the crew child survived with the ship");
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
    @DisplayName("backupTo excludes .xdstorage.lock from the destination")
    public void backupExcludesLockFile() throws Exception {
        final Path original = tempDir.resolve("original-lock");
        Files.createDirectories(original);
        final String originalName = "backup-lock-" + System.nanoTime();

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(originalName, original.toString(), 64);
        {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            try {
                // Use ObjectsMapObject (single-object policy) for this simple test
                final ObjectsMapObject obj = new ObjectsMapObject();
                obj.setId("lock-1");
                final Map<Object, Object> map = new HashMap<>();
                map.put("lock-key", "lock-value");
                obj.setMap(map);
                storage.save(obj, tx);
                storage.commitTransaction(tx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(tx);
                throw e;
            }
        }

        final Path backup = tempDir.resolve("backup-lock");
        storage.backupTo(backup);

        assertFalse(Files.exists(backup.resolve(".xdstorage.lock")),
                "the lock file must NOT be in the backup destination");
    }

    @Test
    @DisplayName("backupTo under concurrent write load: the copy opens and answers full queries")
    public void backupUnderConcurrentWriteLoad() throws Exception {
        final Path original = tempDir.resolve("original-concurrent");
        Files.createDirectories(original);
        final String originalName = "backup-concurrent-" + System.nanoTime();

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(originalName, original.toString(), 64);
        {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            try {
                for (int i = 1; i <= 20; i++) {
                    storage.save(newShip(i), tx);
                }
                storage.commitTransaction(tx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(tx);
                throw e;
            }
        }

        // Now: the storage is still open; backupTo will shut it down internally
        final Path backup = tempDir.resolve("backup-concurrent");
        storage.backupTo(backup);

        final XdStorageVerifyReport report = XdStorageProvider.verify(backup.toString());
        assertTrue(report.isOk(), "backup after shutdown passes verify");

        final IXdFileStorage copy = XdStorageProvider.newOrGetFileStorage(originalName + "-copy2", backup.toString(), 64);
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            assertEquals(20, copy.load(XdStorageW2SearchShip.class, o -> true, readTx).size(),
                    "all 20 objects survived the backup");
            final XdStorageW2SearchShip ship = copy.load(XdStorageW2SearchShip.class, 10L, readTx);
            assertEquals("Ship-10", ship.getName());
            readTx.commit();
        } finally {
            copy.shutdown();
        }
    }

    @Test
    @DisplayName("backupTo: destination folder is self-contained (opens under a new name)")
    public void backupDestinationIsSelfContained() throws Exception {
        final Path original = tempDir.resolve("original-self");
        Files.createDirectories(original);
        final String originalName = "backup-self-" + System.nanoTime();

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(originalName, original.toString(), 64);
        {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            try {
                // Use ObjectsMapObject (single-object policy)
                final ObjectsMapObject obj = new ObjectsMapObject();
                obj.setId("self-1");
                final Map<Object, Object> map = new HashMap<>();
                map.put("k", "v");
                obj.setMap(map);
                storage.save(obj, tx);
                storage.commitTransaction(tx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(tx);
                throw e;
            }
        }

        final Path backup = tempDir.resolve("backup-self");
        storage.backupTo(backup);

        // The backup folder must contain the format marker
        assertTrue(Files.exists(backup.resolve(".xdstorage.meta")),
                "the backup must contain the format marker .xdstorage.meta");

        // The backup folder must contain data files
        final File dataDir = backup.resolve("data").toFile();
        assertTrue(dataDir.exists() && dataDir.isDirectory(), "the backup must contain the data/ directory");
        assertTrue(dataDir.list() != null && dataDir.list().length > 0,
                "the data/ directory must contain at least one class directory");

        // The backup must open under a new name without issues
        final IXdFileStorage copy = XdStorageProvider.newOrGetFileStorage(
                "new-name-" + System.nanoTime(), backup.toString(), 64);
        try {
            final IXdStorageTransaction readTx = copy.beginTransaction(60_000L);
            final ObjectsMapObject loaded = copy.load(ObjectsMapObject.class, "self-1", readTx);
            assertNotNull(loaded);
            assertEquals("v", loaded.getMap().get("k"));
            readTx.commit();
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
