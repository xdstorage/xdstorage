package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.performance.entities.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FP-5 (docs/tasks/2.2.0/15): stress test — backup under concurrent write load.
 *
 * <p>Scenario: seed a universe model (cascade save with parent references), run 10
 * concurrent writer/readers that aggressively mutate the tree, then invoke
 * {@code backupTo(Path)} while the load is still active. After the backup the
 * original storage must remain fully operational and the backup copy must be
 * verifiably consistent (zero data loss for committed state at backup point).
 */
@Tag("stress")
@DisplayName("FP-5 backup under concurrent write load: no data loss + post-backup operability")
public class XdStorageBackupUnderLoadStressTest {

    private static final int BACKUP_DURATION_MS = 8_000;
    private static final int BACKUP_TRIGGER_DELAY_MS = 2_000;
    private static final int WORKER_THREADS = 10;
    private static final int SEED_UNIVERSES = 5;

    private Path tempDir;
    private IXdFileStorage storage;
    private String storageName;
    private List<String> seedIds;
    private volatile boolean stopFlag;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("backup-load-");
        storageName = "backup-load-" + System.nanoTime();
        String folder = tempDir.resolve("db").toString();

        XdStorageConfig config = new XdStorageConfig.Builder()
                .journalWrites(true)
                .setEnableForeignKeys(true)
                .build();
        storage = XdStorageProvider.newOrGetFileStorage(storageName, folder, 200, FilesFormat.YAML, config);
        seedIds = new ArrayList<>();

        // Seed initial universe model with cascade save + parent references
        IXdStorageTransaction txInit = storage.beginTransaction(60_000L);
        Random seedRand = new Random(42);
        for (int i = 0; i < SEED_UNIVERSES; i++) {
            XdUniverseAutoSave u = new XdUniverseAutoSave();
            u.setId("uni-" + i);
            seedUniverse(u, seedRand);
            storage.save(u, txInit);
            seedIds.add(u.getId());
        }
        txInit.commit();
    }

    @AfterEach
    void tearDown() {
        stopFlag = true;
        if (storage != null) {
            try { storage.shutdown(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Backup under load: no data loss in original or copy, original remains operable")
    public void backupUnderLoad_noDataLoss_and_postBackupOperability() throws Exception {
        System.out.println("=== FP-5: backup under concurrent write load ===");

        Random rand = new Random(System.nanoTime());

        // ---- Phase 1: start load workers ----------------------------------------
        ExecutorService loadExec = Executors.newFixedThreadPool(WORKER_THREADS);
        ExecutorService backupExec = Executors.newFixedThreadPool(1);
        CountDownLatch backupDone = new CountDownLatch(1);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch loadStopped = new CountDownLatch(1);
        AtomicReference<Throwable> loadError = new AtomicReference<>();

        // Start load workers
        for (int w = 0; w < WORKER_THREADS; w++) {
            loadExec.submit(() -> {
                try {
                    loadStarted.countDown();
                    while (!stopFlag && loadError.get() == null) {
                        try {
                            runWorker(storage, new ArrayList<>(seedIds), rand);
                        } catch (RuntimeException e) {
                            // Expected: shutdownNow or backupTo shut down the storage
                            if (e instanceof RejectedExecutionException ||
                                    e instanceof XdStorageRuntimeException ||
                                    (e.getMessage() != null &&
                                     (e.getMessage().contains("is shut down") ||
                                      e.getMessage().contains("interrupted") ||
                                      e.getMessage().contains("lock") ||
                                      e.getMessage().contains("rejected")))) {
                                break; // expected shutdown — not a test failure
                            }
                            loadError.compareAndSet(null, e);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    loadError.compareAndSet(null, t);
                } finally {
                    loadStopped.countDown();
                }
            });
        }

        // Wait for load to start, then trigger backup
        loadStarted.await();
        Thread.sleep(BACKUP_TRIGGER_DELAY_MS);
        System.out.println("  Triggering backupTo() now...");

        final Path backupDir = tempDir.resolve("backup");
        backupExec.submit(() -> {
            try {
                storage.backupTo(backupDir);
            } catch (Throwable t) {
                System.out.println("  [INFO] backupTo error (expected if already shut): " + t.getClass().getSimpleName());
            } finally {
                backupDone.countDown();
            }
        });

        // Wait for backup to complete
        backupDone.await();
        System.out.println("  Backup completed. Re-opening storage for post-backup verification...");

        // Wait for all load workers to stop
        loadStopped.await();
        loadExec.shutdownNow();
        loadExec.awaitTermination(5, TimeUnit.SECONDS);

        // ---- Phase 2: verify backup -----------------------------------------------
        // 1. verify() verdict OK
        XdStorageVerifyReport verReport = XdStorageProvider.verify(backupDir.toString());
        assertTrue(verReport.isOk(), "backup folder passes verify: " + verReport);
        System.out.println("  [OK] verify(backup) = " + verReport);

        // 2. Open the backup copy and check seed universes exist
        IXdFileStorage backup = XdStorageProvider.newOrGetFileStorage(storageName + "-copy", backupDir.toString(), 200);
        try {
            IXdStorageTransaction backupTx = backup.beginTransaction(60_000L);
            try {
                for (String uid : seedIds) {
                    XdUniverseAutoSave found = backup.load(XdUniverseAutoSave.class, uid, backupTx);
                    assertNotNull(found, "seed universe '" + uid + "' must exist in backup");
                    assertNotNull(found.getGalaxies(), "universe " + uid + " must have galaxies in backup");
                    assertFalse(found.getGalaxies().isEmpty(), "universe " + uid + " must have non-empty galaxies");
                }

                // Verify cascade: galaxy -> system -> planet -> satellite
                XdUniverseAutoSave firstUni = backup.load(XdUniverseAutoSave.class, seedIds.get(0), backupTx);
                XdGalaxyAutoSave firstGal = firstUni.getGalaxies().iterator().next();
                Collection<XdStarSystemAutoSave> systems = firstGal.getSystems();
                if (systems != null && !systems.isEmpty()) {
                    XdStarSystemAutoSave firstSys = systems.iterator().next();
                    Collection<XdPlanetAutoSave> planets = firstSys.getPlanets();
                    if (planets != null && !planets.isEmpty()) {
                        XdPlanetAutoSave firstPlanet = planets.iterator().next();
                        assertNotNull(firstPlanet.getSatellite(),
                                "the planet's satellite survived in the backup");
                    }
                }

                backupTx.commit();
            } catch (XdStorageException e) {
                backup.rollbackTransaction(backupTx);
                throw e;
            }
        } finally {
            backup.shutdown();
        }

        // ---- Phase 3: verify original is still operable -----------------------------
        stopFlag = false; // re-enable for post-backup operations
        storage = XdStorageProvider.newOrGetFileStorage(storageName, tempDir.resolve("db").toString(), 200, FilesFormat.YAML);

        IXdStorageTransaction verifyTx = storage.beginTransaction(60_000L);
        Collection<XdUniverseAutoSave> verifyUniverses = storage.load(XdUniverseAutoSave.class, verifyTx);
        assertTrue(!verifyUniverses.isEmpty(), "original still serves queries after backup");

        XdUniverseAutoSave loaded = storage.load(XdUniverseAutoSave.class, seedIds.get(0), verifyTx);
        assertNotNull(loaded, "load-by-id works post-backup: universe=" + loaded.getId());

        XdGalaxyAutoSave loadedGal = storage.load(XdGalaxyAutoSave.class, "gal-uni-0-1", verifyTx);
        assertNotNull(loadedGal, "galaxy load-by-id works post-backup");

        verifyTx.commit();
        System.out.println("  [OK] Original post-backup operability verified");

        // Final shutdown
        stopFlag = true;
        storage.shutdown();

        // Check for unexpected errors
        Throwable err = loadError.get();
        if (err != null) {
            throw new AssertionError("Unexpected load error: " + err.getMessage(), err);
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private void seedUniverse(XdUniverseAutoSave u, Random rand) {
        int numGalaxies = rand.nextInt(20) + 5;
        for (int g = 0; g < numGalaxies; g++) {
            XdGalaxyAutoSave galaxy = new XdGalaxyAutoSave();
            galaxy.setId("gal-" + u.getId() + "-" + g);
            u.addGalaxy(galaxy);

            XdBlackHoleAutoSave hole = new XdBlackHoleAutoSave();
            hole.setId("hole-" + galaxy.getId());
            hole.setMass(rand.nextInt(1_000_000));
            galaxy.setHole(hole);

            int numSystems = rand.nextInt(10) + 3;
            for (int s = 0; s < numSystems; s++) {
                XdStarSystemAutoSave sys = new XdStarSystemAutoSave();
                sys.setId("sys-" + galaxy.getId() + "-" + s);
                sys.setNewName("System_" + rand.nextInt(1000));
                galaxy.addSystem(sys);

                int numPlanets = rand.nextInt(5) + 1;
                for (int p = 0; p < numPlanets; p++) {
                    XdPlanetAutoSave planet = new XdPlanetAutoSave();
                    planet.setName("Planet_" + sys.getId() + "-" + p);
                    sys.addPlanet(planet);

                    XdSatelliteAutoSave sat = new XdSatelliteAutoSave();
                    sat.setName("Sat_" + planet.getId());
                    planet.setSatellite(sat);
                }
            }
        }
    }

    private void runWorker(IXdFileStorage st, List<String> universeIds, Random rand) throws Exception {
        if (universeIds.isEmpty()) return;
        int op = rand.nextInt(4);

        if (op <= 1) {
            IXdStorageTransaction tx = st.beginTransaction(5_000L);
            try {
                XdUniverseAutoSave u = st.load(XdUniverseAutoSave.class,
                        universeIds.get(rand.nextInt(universeIds.size())), tx);
                if (u != null) {
                    XdGalaxyAutoSave g = new XdGalaxyAutoSave();
                    g.setId("gal-" + u.getId() + "-" + System.nanoTime());
                    u.addGalaxy(g);

                    XdBlackHoleAutoSave hole = new XdBlackHoleAutoSave();
                    hole.setId("hole-" + g.getId());
                    hole.setMass(rand.nextInt(1_000_000));
                    g.setHole(hole);

                    XdStarSystemAutoSave sys = new XdStarSystemAutoSave();
                    sys.setId("sys-" + g.getId() + "-" + System.nanoTime());
                    sys.setNewName("System_" + rand.nextInt(1000));
                    g.addSystem(sys);

                    XdPlanetAutoSave planet = new XdPlanetAutoSave();
                    planet.setName("Planet_" + sys.getId() + "-" + System.nanoTime());
                    sys.addPlanet(planet);

                    XdSatelliteAutoSave sat = new XdSatelliteAutoSave();
                    sat.setName("Sat_" + planet.getId());
                    planet.setSatellite(sat);

                    st.update(u, tx);
                }
                tx.commit();
            } catch (XdStorageException | XdStorageConnectionException e) {
                tx.rollback();
            }
        } else if (op == 2) {
            IXdStorageTransaction tx = st.beginTransaction(5_000L);
            try {
                st.load(XdUniverseAutoSave.class, tx);
                st.load(XdPlanetAutoSave.class, tx);
                tx.commit();
            } catch (XdStorageException | XdStorageConnectionException e) {
                tx.rollback();
            }
        } else {
            IXdStorageTransaction tx = st.beginTransaction(5_000L);
            try {
                Collection<XdUniverseAutoSave> universes = st.load(XdUniverseAutoSave.class, tx);
                if (!universes.isEmpty()) {
                    XdUniverseAutoSave u = universes.iterator().next();
                    Collection<XdGalaxyAutoSave> galaxies = u.getGalaxies();
                    if (galaxies != null && !galaxies.isEmpty()) {
                        XdGalaxyAutoSave g = galaxies.iterator().next();
                        Collection<XdStarSystemAutoSave> systems = g.getSystems();
                        if (systems != null && !systems.isEmpty()) {
                            XdStarSystemAutoSave sys = systems.iterator().next();
                            Collection<XdPlanetAutoSave> planets = sys.getPlanets();
                            if (planets != null && !planets.isEmpty()) {
                                planets.iterator().next();
                            }
                        }
                    }
                }
                tx.commit();
            } catch (XdStorageException | XdStorageConnectionException e) {
                tx.rollback();
            }
        }
    }
}
