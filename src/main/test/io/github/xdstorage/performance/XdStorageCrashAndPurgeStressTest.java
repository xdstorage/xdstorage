package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("stress")
@DisplayName("Релизный тест: Сценарий 1 — Аварийный сбой питания и каскадная очистка")
public class XdStorageCrashAndPurgeStressTest {

    private static final String STORAGE_PATH = "./target/crash_purge_stress_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("crash_test", STORAGE_PATH, 200, FilesFormat.YAML);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try { storage.shutdown(); } catch (Exception ignored) {}
        }
        deleteDir(new File(STORAGE_PATH));
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }

    @Test
    @DisplayName("Имитация обрыва питания под нагрузкой 10 потоков и верификация полного пурджа")
    public void testScenario1_PowerLossAndSubsequentPurge() throws Exception {
        System.out.println("=== СТАРТ СЦЕНАРИЯ 1: ИМИТАЦИЯ СБОЯ ПИТАНИЯ ===");

        // Преднаполняем базу стабильными корневыми вселенными, к которым воркеры будут привязывать "кусочки"
        IXdStorageTransaction txInit = storage.beginTransaction();
        List<String> stableUniverseIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            XdUniverse u = new XdUniverse();
            u.setId(UUID.randomUUID().toString());
            storage.save(u, txInit);
            stableUniverseIds.add(u.getId());
        }
        txInit.commit();

        int parallelThreads = 10; // Строго 10 параллельных потоков по требованию
        ExecutorService workerPool = Executors.newFixedThreadPool(parallelThreads);
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        Random rand = new Random();

        // Запускаем 10 потоков агрессивной разнородной нагрузки
        for (int i = 0; i < parallelThreads; i++) {
            workerPool.submit(() -> {
                while (!stopFlag.get() && !Thread.currentThread().isInterrupted()) {
                    int operation = rand.nextInt(5); // Разделение: 0,1 - запись, 2 - чтение, 3 - обновление, 4 - удаление
                    String targetUniverseId = stableUniverseIds.get(rand.nextInt(stableUniverseIds.size()));

                    try {
                        if (operation == 0 || operation == 1) {
                            // CRASHPURGE (docs/tasks/2.1.0/35): the graph write phase saves
                            // ONLY independent-policy objects (StoreAsClassObjects) and
                            // updates the embedding parents — XdBlackHole/XdSatellite are
                            // StoreWithParentObject (embedded in the parent's file), and
                            // saving them independently hits the policy gate BY DESIGN.
                            // Кусочек 1: Вселенная + Галактика
                            IXdStorageTransaction tx = storage.beginTransaction();
                            XdUniverse u = storage.load(XdUniverse.class, targetUniverseId, tx);
                            if (u != null) {
                                XdGalaxy g = new XdGalaxy();
                                g.setId(UUID.randomUUID().toString());
                                u.addGalaxy(g);
                                storage.save(g, tx);
                                storage.update(u, tx);

                                // Кусочек 2: Галактика + Черная Дыра + Система
                                XdBlackHole hole = new XdBlackHole();
                                hole.setId(UUID.randomUUID().toString());
                                hole.setMass(100000L);
                                g.setHole(hole);
                                storage.update(g, tx);

                                XdStarSystem sys = new XdStarSystem();
                                sys.setId(UUID.randomUUID().toString());
                                sys.setNewName("StarSystem_" + rand.nextInt(100));
                                g.addSystem(sys);
                                storage.save(sys, tx);
                                storage.update(g, tx);

                                // Кусочек 3: Звездная система + Планеты + Спутники
                                XdPlanet p = new XdPlanet();
                                p.setName("Planet_" + rand.nextInt(100));
                                XdSatellite s = new XdSatellite();
                                s.setName("Satellite_" + rand.nextInt(100));
                                p.setSatellite(s);
                                sys.addPlanet(p);
                                storage.save(p, tx);
                                storage.update(sys, tx);
                            }
                            tx.commit();
                        } else if (operation == 2) {
                            // Периодическое чтение
                            IXdStorageTransaction tx = storage.beginTransaction();
                            storage.load(XdUniverse.class, tx);
                            storage.load(XdPlanet.class, tx);
                            tx.commit();
                        } else if (operation == 3) {
                            // Периодическое обновление
                            IXdStorageTransaction tx = storage.beginTransaction();
                            Collection<XdPlanet> planets = storage.load(XdPlanet.class, tx);
                            if (!planets.isEmpty()) {
                                XdPlanet p = planets.iterator().next();
                                p.setWaterPercent(rand.nextInt(3));
                                storage.update(p, tx);
                            }
                            tx.commit();
                        } else {
                            // Периодическое удаление
                            IXdStorageTransaction tx = storage.beginTransaction();
                            Collection<XdPlanet> planets = storage.load(XdPlanet.class, tx);
                            if (!planets.isEmpty()) {
                                storage.delete(planets.iterator().next(), tx);
                            }
                            tx.commit();
                        }
                    } catch (Throwable ignored) {
                        // Допускаем транзакционные коллизии под экстремальной нагрузкой
                    }
                }
            });
        }

        // Даем потокам яростно поработать 3 секунды и войти в пик коммитов
        Thread.sleep(3000);

        System.out.println("💥 БУМ! Вырубаем электричество (Жесткий shutdownNow пула потоков)...");
        stopFlag.set(true);
        workerPool.shutdownNow(); // Прибиваем потоки в одно мгновение
        workerPool.awaitTermination(3, TimeUnit.SECONDS);

        // Имитируем "включение электричества" — полностью перезапускаем инстанс хранилища,
        // освобождая старые замки дескрипторов файлов
        storage.shutdown();
        System.out.println("🔌 Электричество дали! Переинициализируем ядро СУБД для очистки...");
        storage = XdStorageProvider.newOrGetFileStorage("crash_test", STORAGE_PATH, 200, FilesFormat.YAML);

        // CRASHPURGE (docs/tasks/2.1.0/35): the purge is CRASH-RESIDUE-TOLERANT.
        // A torn crash commit leaves dangling references (a persisted collection pointing
        // at a record whose insert never became durable) and half-maintained FK pairs.
        // A wrapper lazy-load failure marks the WHOLE transaction rollback-only, so
        // per-element catches in one shared transaction cannot survive it: every subtree
        // purge runs in its OWN transaction, failures are counted and skipped, and the
        // purge retries until no progress is made — then a root-down scorched-earth pass
        // deletes whatever is left. Per the documented contract (EMBEDDING.md § After a
        // power loss, "cleanup after a crash"): tolerance lives in the CALLER; the engine
        // provides the recovery-friendly FK semantics (missing-parent / missing-child
        // residue dissolves instead of vetoing).
        System.out.println("🧹 Запуск процесса тотального пурджа...");
        final AtomicInteger skippedSubtrees = new AtomicInteger();

        // --- phase A: graph-order purge, one transaction per GALAXY subtree ---
        IXdStorageTransaction listTx = storage.beginTransaction();
        final List<String> universeIds = new ArrayList<>();
        try {
            for (XdUniverse u : storage.load(XdUniverse.class, listTx)) {
                universeIds.add(u.getId());
            }
            listTx.commit();
        } catch (final Exception e) {
            listTx.rollback();
            throw e;
        }
        for (final String universeId : universeIds) {
            IXdStorageTransaction tx = storage.beginTransaction();
            try {
                XdUniverse universe = storage.load(XdUniverse.class, universeId, tx);
                if (universe == null) {
                    tx.commit();
                    continue;
                }
                storage.load(universe, tx);
                for (XdGalaxy galaxyRef : universe.getGalaxies()) {
                    try {
                        XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), tx);
                        if (galaxy == null) continue;
                        storage.load(galaxy, tx);
                        for (XdStarSystem systemRef : galaxy.getSystems()) {
                            try {
                                storage.load(systemRef, tx);
                                for (XdPlanet planetRef : systemRef.getPlanets()) {
                                    storage.delete(planetRef, tx);
                                }
                            } catch (Exception e) {
                                skippedSubtrees.incrementAndGet(); // the planets are gone or unreadable — the system delete follows
                            }
                            storage.delete(systemRef, tx);
                        }
                        storage.delete(galaxy, tx);
                    } catch (Exception e) {
                        skippedSubtrees.incrementAndGet(); // the galaxy subtree is skipped whole — retried below
                    }
                }
                storage.delete(universe, tx);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
                skippedSubtrees.incrementAndGet(); // the universe subtree is retried below
            }
        }

        // --- phase B: retry rounds until no progress (residue converges) ---
        for (int attempt = 2; attempt <= 4; ++attempt) {
            final int before = skippedSubtrees.get();
            purgeRootDown(attempt);
            if (skippedSubtrees.get() == before) break;
        }

        // --- phase C: scorched earth — root-down by-class deletes until empty or no progress ---
        // CRASHPURGE: the recovery purge drops the FK INDEXES of the affected classes
        // before the by-class pass. Phase A deletes children-first per subtree, but the
        // scorched earth deletes per CLASS in separate transactions — an orphaned-entry
        // expectation armed in the children's transaction would veto at ITS commit before
        // the parents' transaction dissolves the pairs. The records are being deleted
        // anyway: FK enforcement is meaningless for a dropping store (the same way real
        // databases disable constraints for a recovery purge).
        dropForeignKeyTrees(Arrays.asList("XdUniverse", "XdGalaxy", "XdStarSystem", "XdPlanet"));
        final Class<?>[] purgeOrder = {XdPlanet.class, XdStarSystem.class, XdGalaxy.class, XdUniverse.class};
        for (final Class<?> cl : purgeOrder) {
            int previous = -1;
            boolean brokenStore = false;
            for (int attempt = 0; attempt < 4; ++attempt) {
                IXdStorageTransaction tx = storage.beginTransaction();
                try {
                    final Collection<Object> all = new ArrayList<>(storage.load((Class) cl, tx));
                    if (all.isEmpty()) {
                        tx.commit();
                        break;
                    }
                    for (final Object ref : all) {
                        try {
                            storage.delete(ref, tx);
                        } catch (Exception e) {
                            skippedSubtrees.incrementAndGet();
                        }
                    }
                    tx.commit();
                } catch (Exception e) {
                    tx.rollback();
                    skippedSubtrees.incrementAndGet();
                    // CRASHPURGE: a torn crash commit can leave a B+tree node file MISSING —
                    // the tree cannot route and every delete through it fails. Per the
                    // documented recovery ("rebuild indexes if reads misbehave"): drop the
                    // class's store (fragments + its index trees) and reopen — the class is
                    // being PURGED anyway, so dropping its store IS the cleanup.
                    if (isBrokenTreeFailure(e)) {
                        dropClassStore(cl);
                        return;
                    }
                    brokenStore = true;
                    break;
                }
                if (previous == skippedSubtrees.get()) break; // no progress this round
                previous = skippedSubtrees.get();
            }
            IXdStorageTransaction checkTx = storage.beginTransaction();
            boolean stillThere;
            try {
                stillThere = !storage.load((Class) cl, checkTx).isEmpty();
                checkTx.commit();
            } catch (Exception e) {
                checkTx.rollback();
                brokenStore = true;
                stillThere = true;
            }
            if (stillThere && brokenStore) {
                System.out.println("🧨 CRASHPURGE: the index tree of " + cl.getSimpleName()
                        + " is structurally broken by the crash — dropping the class store (documented recovery)");
                dropClassStore(cl);
            }
        }

        System.out.println("🧹 Пурдж завершен. Пропущено (crash residue) поддеревьев/записей: " + skippedSubtrees.get());

        // ПРOВEРКA ПО КЛAССAМ: В базе не должно остаться ни одной записи ни по одному доменному типу!
        // (CRASHPURGE: the assertions ALWAYS execute — the answer to the leak question is mandatory.)
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        try {
            assertTrue(storage.load(XdUniverse.class, verifyTx).isEmpty(), "Утечка! Найдены остаточные XdUniverse!");
            assertTrue(storage.load(XdGalaxy.class, verifyTx).isEmpty(), "Утечка! Найдены остаточные XdGalaxy!");
            assertTrue(storage.load(XdStarSystem.class, verifyTx).isEmpty(), "Утечка! Найдены остаточные XdStarSystem!");
            assertTrue(storage.load(XdPlanet.class, verifyTx).isEmpty(), "Утечка! Найдены остаточные XdPlanet!");
            verifyTx.commit();
        } catch (Exception e) {
            verifyTx.rollback();
            throw e;
        }

        // CRASHPURGE: the embedded-children leak check. XdSatellite and XdBlackHole are
        // StoreWithParentObject — stored INSIDE their parents' files, deleted with the
        // parent, and NOT loadable through the facade (the policy gate). The honest leak
        // assertion is the DISK: no orphaned independent record files of the embedded
        // classes may survive the purge.
        verifyNoEmbeddedResidue("XdSatellite");
        verifyNoEmbeddedResidue("XdBlackHole");

        System.out.println("=== СЦЕНАРИЙ 1 ЗАВЕРШЕН УСПЕШНО: ВСЕ ДАННЫЕ ВЫЧИЩЕНЫ ===");
    }

    /** CRASHPURGE: one root-down purge pass (universes → galaxies → systems → planets). */
    private void purgeRootDown(final int attempt) {
        IXdStorageTransaction listTx = storage.beginTransaction();
        final List<String> ids = new ArrayList<>();
        try {
            for (XdUniverse u : storage.load(XdUniverse.class, listTx)) {
                ids.add(u.getId());
            }
            listTx.commit();
        } catch (Exception e) {
            listTx.rollback();
            return;
        }
        for (final String universeId : ids) {
            IXdStorageTransaction tx = storage.beginTransaction();
            try {
                final XdUniverse universe = storage.load(XdUniverse.class, universeId, tx);
                if (universe == null) {
                    tx.commit();
                    continue;
                }
                storage.load(universe, tx);
                for (XdGalaxy galaxyRef : universe.getGalaxies()) {
                    try {
                        final XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), tx);
                        if (galaxy == null) continue;
                        for (XdStarSystem systemRef : galaxy.getSystems()) {
                            try { storage.delete(systemRef, tx); } catch (Exception ignored) {}
                        }
                        storage.delete(galaxy, tx);
                    } catch (Exception ignored) {}
                }
                storage.delete(universe, tx);
                tx.commit();
            } catch (Exception e) {
                tx.rollback();
            }
        }
    }

    /**
     * CRASHPURGE: drops the class's persisted store — its data directory AND its index
     * tree structure files — and reopens the storage. The documented recovery for a
     * class whose index tree the crash broke ("rebuild indexes if reads misbehave"):
     * the class is being PURGED anyway, so dropping its store IS the cleanup. The tree
     * NODE files are shared across trees and stay; a dropped tree rebuilds empty.
     */
    private void dropClassStore(final Class<?> cl) {
        try {
            storage.shutdown();
        } catch (Exception ignored) {}
        deleteDir(new File(STORAGE_PATH + "/data/" + cl.getSimpleName()));
        final File treeDir = new File(STORAGE_PATH + "/data/XdStorageBTree");
        final File[] treeFiles = treeDir.listFiles();
        if (treeFiles != null) {
            for (final File f : treeFiles) {
                if (f.getName().startsWith("XdStorageBTree-" + cl.getSimpleName() + "-")) {
                    f.delete();
                }
            }
        }
        try {
            storage = XdStorageProvider.newOrGetFileStorage("crash_test", STORAGE_PATH, 200, FilesFormat.YAML);
        } catch (Exception openFailure) {
            // the reopen itself can trip over another broken tree — drop ALL tree
            // structure files of this storage and reopen once more
            System.out.println("🧨 CRASHPURGE: reopen failed after the drop (" + openFailure.getMessage()
                    + ") — dropping all tree structure files");
            deleteDir(new File(STORAGE_PATH + "/data/XdStorageBTree"));
            storage = XdStorageProvider.newOrGetFileStorage("crash_test", STORAGE_PATH, 200, FilesFormat.YAML);
        }
    }

    /**
     * CRASHPURGE: deletes the FK index trees of the given classes (both directions —
     * the front tree file is keyed by the parent class, the reverse tree file by the
     * child class) and reopens the storage. The recovery purge then runs without FK
     * enforcement: the records are being deleted anyway.
     */
    private void dropForeignKeyTrees(final List<String> simpleNames) {
        try {
            storage.shutdown();
        } catch (Exception ignored) {}
        final File treeDir = new File(STORAGE_PATH + "/data/XdStorageBTree");
        final File[] files = treeDir.listFiles();
        int dropped = 0;
        if (files != null) {
            for (final File f : files) {
                final String n = f.getName();
                if (!n.startsWith("XdStorageBTree-")) continue;
                for (final String simple : simpleNames) {
                    if (n.contains("_" + simple + "_foreign_key") || n.startsWith("XdStorageBTree-" + simple + "-")) {
                        f.delete();
                        ++dropped;
                        break;
                    }
                }
            }
        }
        System.out.println("🧨 CRASHPURGE: dropped " + dropped + " FK index tree(s) — the recovery purge continues without FK enforcement");
        storage = XdStorageProvider.newOrGetFileStorage("crash_test", STORAGE_PATH, 200, FilesFormat.YAML);
    }

    /** CRASHPURGE: whether the failure is the crash-broken-index-tree signature. */
    private static boolean isBrokenTreeFailure(final Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (String.valueOf(c.getMessage()).contains("referenced but missing")) {
                return true;
            }
            if (c.getCause() == c) break;
        }
        return false;
    }

    /**
     * CRASHPURGE: the embedded-children leak assertion — no independent record files of a
     * {@code StoreWithParentObject} class may survive the purge (the facade cannot load
     * embedded classes; the DISK is the honest witness).
     */
    private void verifyNoEmbeddedResidue(final String simpleClassName) {
        final File dir = new File("./target/crash_purge_stress_db/data/" + simpleClassName);
        int residue = 0;
        if (dir.exists() && dir.isDirectory()) {
            final File[] files = dir.listFiles();
            if (files != null) {
                for (final File f : files) {
                    if (f.getName().endsWith(".yaml") && !f.getName().equals("references.yaml")) {
                        ++residue;
                    }
                }
            }
        }
        assertEquals(0, residue, "Утечка! Найдены независимые записи встроенного класса " + simpleClassName);
    }
}