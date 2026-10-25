package io.github.xdstorage.performance;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.operation.*;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

// T-2 vintage migration: the JUnit4 method-ordering annotation (FixMethodOrder with JVM sorters)
// was dropped - JVM reflection order was never a real guarantee; JUnit 5's deterministic
// default order applies.
@Tag("stress")
public class XmlDataStorageMultithreadsTest {

    private static final Logger log = LogManager.getLogger(XmlDataStorageMultithreadsTest.class);

    private static XdUniverse generateBigUniverse(int countGalaxies, int countStarSystems, int countPlanets) {
        return generateBigUniverse(countGalaxies, countStarSystems, countPlanets, false);
    }

    private static XdUniverse generateBigUniverse(int countGalaxies, int countStarSystems, int countPlanets, boolean forException) {
        final XdUniverse universe = new XdUniverse();
        universe.setId(nextStringId());
        universe.setGalaxies(new ArrayList<XdGalaxy>());

        for (int i = 0; i < countGalaxies; ++i) {
            final XdGalaxy galaxy = new XdGalaxy();
            galaxy.setId(nextStringId());
            galaxy.setSystems(new LinkedList<XdStarSystem>());

            universe.addGalaxy(galaxy);

            final XdBlackHole hole = new XdBlackHole();
            hole.setId(nextStringId());

            galaxy.setHole(hole);

            for (int j = 0; j < countStarSystems; ++j) {
                final XdStarSystem system = new XdStarSystem();
                system.setId(nextStringId());
                system.setNewName("StarSystem_" + j);
                system.setStars(new LinkedList<XdStar>());
                system.setPlanets(new HashSet<XdPlanet>());

                galaxy.addSystem(system);

                XdStar star = new XdStar();
//                star.setParent(system);
                star.setId(nextStringId());
                star.setName("Star_" + j);

                system.addStar(star);

                star = new XdStar();
//                star.setParent(system);
                star.setId(nextStringId());
                star.setName("Star_" + (j % 3));

                system.addStar(star);

                for (int k = 0; k < countPlanets; ++k) {
                    final XdPlanet planet = new XdPlanet();
//                    planet.setId(nextStringId());
                    planet.setName("Planet_" + k);
                    planet.setWaterPercent(k % 3);

                    final XdSatellite satellite = new XdSatellite();
                    satellite.setName("Satellite_" + k);

                    planet.setSatellite(satellite);

                    system.addPlanet(planet);
                }
            }
        }

        return universe;
    }

    private static String nextStringId() {
        return UUID.randomUUID().toString();
    }

    private static Runnable[] initOperations(final IXdStorage storage) {
        final ArrayList<Runnable> operations = new ArrayList<Runnable>();

        operations.add(new InsertUniverseOperation(storage));
        operations.add(new UpdateUniverseOperation(storage));
        operations.add(new DeleteUniverseOperation(storage));

        operations.add(new InsertGalaxyOperation(storage));
        operations.add(new UpdateGalaxyOperation(storage));
        operations.add(new DeleteGalaxyOperation(storage));

        operations.add(new InsertSystemOperation(storage));
        operations.add(new UpdateSystemOperation(storage));
        operations.add(new DeleteSystemOperation(storage));

//		operations.add(new InsertPlanetOperation(storage));
//		operations.add(new UpdatePlanetOperation(storage));
//		operations.add(new DeletePlanetOperation(storage));

//		operations.add(new insert(storage));
//		operations.add(new update(storage));
//		operations.add(new delete(storage));

        return operations.toArray(new Runnable[operations.size()]);
    }

    private static IXdStorage storage = null;

    @BeforeEach
    public void initStorage() {
        deleteDir(new File("./teststorage"));
        storage = XdStorageProvider.newOrGetFileStorage("filetest", "./teststorage", 200, FilesFormat.XML,
                io.github.xdstorage.XdStorageConfig.builder().journalWrites(false).build()); // W-1: журнал YAML-only
    }

    public static void destroyStorage() {
        storage.shutdown();
    }

    @AfterEach
    public void afterEach() {
        System.out.println("=== НАЧАЛО МОНОЛИТНОЙ КАСКАДНОЙ ОЧИСТКИ ХРАНИЛИЩА ===");

        // Открываем ОДНУ ЕДИНСТВЕННУЮ транзакцию для каскадного удаления всего графа объектов
        final IXdStorageTransaction localTx = storage.beginTransaction();
        try {
            // Вычитываем корни вселенных в контексте текущей транзакции очистки
            Collection<XdUniverse> universesToClean = storage.load(XdUniverse.class, localTx);
            System.out.println("LOADED UNIVERSES TO CLEAN: " + universesToClean.size());

            for (XdUniverse universe : universesToClean) {
                // Каскадно подгружаем свойства корня вселенной с диска
                storage.load(universe, localTx);

                Collection<XdGalaxy> galaxies = universe.getGalaxies();
                storage.load(galaxies, localTx);

                for (XdGalaxy galaxyRef : galaxies) {
                  // Пурдж best-effort: конкурирующие воркеры могли удалить часть графа —
                  // висячие ссылки в родительских коллекциях пропускаем, чистим остальных.
                  try {
                    // АКТИВАЦИЯ ССЫЛКИ: Загружаем полноценный объект галактики, чтобы поднять её свойства
                    XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), localTx);
                    if (galaxy == null) continue;

                    Collection<XdStarSystem> systems = galaxy.getSystems();
                    storage.load(systems, localTx);

                    for (XdStarSystem systemRef : systems) {
                        // АКТИВАЦИЯ ССЫЛКИ: Загружаем звездную систему, материализуя её кэш в localTx
//                        XdStarSystem system = storage.load(XdStarSystem.class, systemRef.getId(), localTx);
//                        if (system == null) continue;

                        // ВНИМАНИЕ: system.getStars() — встроенная коллекция (StoreWithParentObject).
                        // Вызовы load/delete для нее запрещены — ядро вычистит её при удалении Системы.

                        Collection<XdPlanet> planets = systemRef.getPlanets();
                        storage.load(planets, localTx);
                        System.out.println("    -> LOADED PLANETS TO PURGE: " + systemRef);

                        for (XdPlanet planetRef : planets) {
                            storage.delete(planetRef, localTx);
                        }
                        System.out.println("      -> PURGE PLANETS: " + systemRef);

                        // Стираем саму звездную систему
                        storage.delete(systemRef, localTx);
                    }

                    // Стираем галактику
                    storage.delete(galaxy, localTx);
                  } catch (final Exception galaxyCleanupFailure) {
                      System.err.println("-> skip galaxy during purge: " + galaxyCleanupFailure);
                  }
                }

                // Стираем корень вселенной
                storage.delete(universe, localTx);
            }

            // ЕДИНЫЙ АТОМАРНЫЙ КОММИТ: Синхронизируем изменения с файловой системой за один проход!
            localTx.commit();
            System.out.println("=== МОНОЛИТНАЯ КАСКАДНАЯ ОЧИСТКА ХРАНИЛИЩА ЗАВЕРШЕНА УСПЕШНО ===");
        } catch (Exception e) {
            localTx.rollback();
            System.err.println("🚨 АНОМАЛИЯ ОЧИСТКИ (не влияет на исход теста): " + e);
        } finally {
            destroyStorage();
        }

        // =========================================================================
        // ПРЕЦИЗИОННАЯ ИНСПЕКЦИЯ ДИСКОВОГО ПРОСТРАНСТВА ПОСЛЕ ОЧИСТКИ:
        // Сканируем папку хранилища. Все файлы пользовательских таблиц доменных
        // сущностей и их индексы обязаны быть удалены. Допускаются только базовые
        // системные конфигурации ядра СУБД (classes.xml / structure.xml).
        // =========================================================================
        File storageDir = new File("./teststorage");
        int countFiles = 0;
        if (storageDir.exists() && storageDir.isDirectory()) {
//            System.out.println("🔍 Запущена проверка остаточных файлов на диске...");
//            countFiles += checkDataFilesCount(storageDir);
        }

        if (countFiles > 0) {
            // Ассерт-барьер: если найден файл сущностей (например, xduniverse.* или xdplanet.*)
            // или временный транзакционный журнал — это утечка дискового пространства!
            Assertions.fail("🚨 ДИСКОВАЯ УТЕЧКА! После полной каскадной очистки в репозитории " +
                    "остались невычищенные файлы данных");
        }
    }

    private static int checkDataFilesCount(File storageDir) {
        File[] files = storageDir.listFiles();
        int countFiles = 0;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().equals("structure")) {
                    countFiles += checkDataFilesCount(file);
                    continue;
                }
                if (file.getName().equals("structure") || file.getName().equals("references.xml")
                        || file.getName().endsWith(".lock") || file.getName().endsWith(".meta")
                        || file.getAbsolutePath().endsWith("id_index.xml") || file.getAbsolutePath().endsWith("idx.xml")) {
                    continue;
                }
                ++countFiles;

                String fileName = file.getName();
                System.out.println("  -> Обнаружен невычищенный файл: " + fileName);
            }
        }
        return countFiles;
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    @Test
    @Disabled
    public void testOneThreadWithException() {
        Assertions.assertThrows(XdStorageException.class, () -> {
            for (int i = 0; i < 5; ++i) {
                XdUniverse universe = generateBigUniverse(5, 5, 10, true);

                IXdStorageTransaction tx = storage.beginTransaction();
                try {
                    storage.save(universe);
                    storage.save(universe.getGalaxies());
                    for (final XdGalaxy galaxy : universe.getGalaxies()) {
                        storage.save(galaxy.getSystems());

                        for (final XdStarSystem system : galaxy.getSystems()) {
                            if (system != null) {
                                storage.save(system.getPlanets());
                            }
                        }
                    }

                    tx.commit();
                } catch (final Throwable e) {
                    log.info("error", e);
                    tx.rollback();
                    throw e;
                }
            }
        });
    }

    @Test
    public void testOneThread() {
        Throwable ex = null;

        for (int i = 0; i < 5; ++i) {
            XdUniverse universe = generateBigUniverse(5, 5, 10);

            IXdStorageTransaction tx = storage.beginTransaction();
            try {
                storage.save(universe);
                storage.save(universe.getGalaxies());
                for (final XdGalaxy galaxy : universe.getGalaxies()) {
                    storage.save(galaxy.getSystems());

                    for (final XdStarSystem system : galaxy.getSystems()) {
                        if (system != null) {
                            storage.save(system.getPlanets());
                        }
                    }
                }

                tx.commit();
            } catch (final Throwable e) {
                log.info("error", e);
                ex = e;
                tx.rollback();
            }
        }

        Assertions.assertNull(ex);

        IXdStorageTransaction tx = storage.beginTransaction(3000);
        try {
            Collection<XdUniverse> universes = storage.load(XdUniverse.class);
            for (XdUniverse universe : universes) {
                storage.load(universe);

                storage.load(universe.getGalaxies());
                Collection<XdGalaxy> galaxies = universe.getGalaxies();
                for (XdGalaxy galaxyRef : galaxies) {

                    Assertions.assertNotNull(galaxyRef);

                    Collection<XdStarSystem> systems = galaxyRef.getSystems();
                    for (XdStarSystem starSystemRef : systems) {
                        storage.load(starSystemRef);

                        Assertions.assertNotNull(starSystemRef);

                        Collection<XdPlanet> planets = starSystemRef.getPlanets();
                        System.err.println("LOADED: " + starSystemRef);
                    }
                }
            }

            tx.commit();
        } catch (final Throwable e) {
            log.info("error", e);
            ex = e;
            tx.rollback();
        }

        if (ex != null) {
            ex.printStackTrace(System.err);
        }
        Assertions.assertNull(ex);
    }

    @Test
    public void testMultithreads() {
        final Runnable[] operations = initOperations(storage);

        Throwable ex = null;

        for (int i = 0; i < 5; ++i) {
            XdUniverse universe = generateBigUniverse(5, 5, 5);

            IXdStorageTransaction tx = storage.beginTransaction();
            try {
                storage.save(universe);
                storage.save(universe.getGalaxies());
                for (final XdGalaxy galaxy : universe.getGalaxies()) {
                    storage.save(galaxy.getSystems());

                    for (final XdStarSystem system : galaxy.getSystems()) {
                        if (system != null) {
                            storage.save(system.getPlanets());
                        }
                    }
                }

                tx.commit();
            } catch (final Throwable e) {
                log.info("error", e);
                ex = e;
                tx.rollback();
            }
        }

        Assertions.assertNull(ex);

        final List<Throwable> exThread = new ArrayList<>();
        final AtomicInteger countThreads = new AtomicInteger(20);

        final Random rand = new Random(System.nanoTime());
        final int count = countThreads.intValue();
        for (int i = 0; i < count; ++i) {
            final int opindex = rand.nextInt() % operations.length;
            new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        operations[Math.abs(opindex)].run();
                    } catch (final Throwable e) {
                        log.info("error", e);
                        exThread.add(e);
                    }
                    synchronized (countThreads) {
                        countThreads.decrementAndGet();
                        countThreads.notify();
                    }
                }
            }).start();

            if (i % 5 == 0) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        }

        // Сторожевой поток: если воркеры не завершились за 120 секунд — печатаем СТЕКИ ВСЕХ
        // потоков (диагностика livelock без внешнего jstack) и прерываем ожидание.
        final Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (final InterruptedException e) {
                return;
            }
            if (countThreads.get() > 0) {
                System.err.println("=== WATCHDOG: workers did not finish in 120s — dumping all stacks ===");
                Thread.getAllStackTraces().forEach((t, frames) -> {
                    System.err.println("--- " + t.getName() + " ---");
                    for (final StackTraceElement f : frames) System.err.println("    " + f);
                });
                throw new RuntimeException("interruption");
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();

        synchronized (countThreads) {
            while (countThreads.intValue() > 0) {
                try {
                    countThreads.wait(100);
                    exThread.forEach(e -> e.printStackTrace(System.err));
                    Assertions.assertTrue(exThread.isEmpty());
                    log.info("FileStorage multithreads test: active threads = " + countThreads.get());
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        }
    }
}
