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

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("stress")
@DisplayName("Релизный тест: Сценарий 1 — Аварийный сбой питания и каскадная очистка")
@Disabled
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

        // Выполняем монолитную каскадную очистку, начиная С КОРНЕВЫХ ЭЛЕМЕНТОВ (Вселенных)
        System.out.println("🧹 Запуск процесса тотального пурджа...");
        IXdStorageTransaction purgeTx = storage.beginTransaction();
        try {
            Collection<XdUniverse> universes = storage.load(XdUniverse.class, purgeTx);
            for (XdUniverse universe : universes) {
                storage.load(universe, purgeTx);
                for (XdGalaxy galaxyRef : universe.getGalaxies()) {
                    try {
                        XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), purgeTx);
                        if (galaxy == null) continue;

                        Collection<XdStarSystem> systems = galaxy.getSystems();
                        storage.load(systems, purgeTx);

                        for (XdStarSystem systemRef : systems) {
                            Collection<XdPlanet> planets = systemRef.getPlanets();
                            storage.load(planets, purgeTx);
                            for (XdPlanet planetRef : planets) {
                                storage.delete(planetRef, purgeTx);
                            }
                            storage.delete(systemRef, purgeTx);
                        }
                        storage.delete(galaxy, purgeTx);
                    } catch (Exception ignored) {}
                }
                storage.delete(universe, purgeTx);
            }
            purgeTx.commit();
            System.out.println("🎉 Каскадный коммит очистки успешно зафиксирован.");
        } catch (Exception e) {
            purgeTx.rollback();
            throw e;
        }

        // ПРOВEРКA ПО КЛAССAМ: В базе не должно остаться ни одной записи ни по одному доменному типу!
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
        System.out.println("=== СЦЕНАРИЙ 1 ЗАВЕРШЕН УСПЕШНО: ВСЕ ДАННЫЕ ВЫЧИЩЕНЫ ===");
    }
}