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

@Tag("stress")
@DisplayName("Релизный тест: Сценарий 2 — Пошаговое наполнение кусочками и тотальный пурдж")
@Disabled
public class XdStorageMassFillAndCleanStressTest {

    private static final String STORAGE_PATH = "./teststorage";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        // Инициализируем хранилище в YAML-формате с размером фрагмента 200, как в базовых тестах
        storage = XdStorageProvider.newOrGetFileStorage("fill_clean_test", STORAGE_PATH, 200, FilesFormat.YAML);
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
    @DisplayName("Конкурентное поблочное наполнение базы в 10 потоков и верификация чистоты дискового пространства")
    public void testScenario2_MassFillAndClean() throws Exception {
        System.out.println("=== СТАРТ СЦЕНАРИЯ 2: МАССОВОЕ ПОБЛОЧНОЕ НАПОЛНЕНИЕ ===");

        // ТРЕБОВАНИЕ: Засеиваем базу стартовыми стабильными вселенными, к которым
        // параллельные воркеры будут гарантированно привязывать новые кусочки данных
        IXdStorageTransaction txInit = storage.beginTransaction();
        List<String> universeIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            XdUniverse u = new XdUniverse();
            u.setId(UUID.randomUUID().toString());
            storage.save(u, txInit);
            universeIds.add(u.getId());
        }
        txInit.commit();
        System.out.println(" -> Стартовые вселенные закоммичены. Запуск 10 параллельных потоков...");

        int totalThreads = 10; // Строго 10 параллельных потоков по ТЗ
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        Random rand = new Random();

        // Запускаем 10 воркеров на 5 секунд агрессивного прессинга
        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                while (!stopFlag.get()) {
                    int op = rand.nextInt(4); // 0,1 - запись кусочками, 2 - чтение, 3 - удаление
                    String targetUni = universeIds.get(rand.nextInt(universeIds.size()));

                    try {
                        if (op <= 1) {
                            // ТРЕБОВАНИЕ: Каждый раз открывается НОВАЯ транзакция
                            IXdStorageTransaction tx = storage.beginTransaction();

                            // Вытаскиваем существующий корень из MVCC Snapshot-снимка текущей TX
                            XdUniverse u = storage.load(XdUniverse.class, targetUni, tx);
                            if (u != null) {
                                // Кусочек 1: Вселенная + Галактика (Связывание с существующей вселенной)
                                XdGalaxy g = new XdGalaxy();
                                g.setId(UUID.randomUUID().toString());
                                u.addGalaxy(g);
                                storage.save(g, tx);
                                storage.update(u, tx);

                                // Кусочек 2: Галактика + Черная Дыра + Звездная система
                                XdBlackHole hole = new XdBlackHole();
                                hole.setId(UUID.randomUUID().toString());
                                hole.setMass(rand.nextInt(1000000));
                                g.setHole(hole);
                                storage.update(g, tx);

                                XdStarSystem sys = new XdStarSystem();
                                sys.setId(UUID.randomUUID().toString());
                                sys.setNewName("System_" + rand.nextInt(1000));
                                g.addSystem(sys);
                                storage.save(sys, tx);
                                storage.update(g, tx);

                                // Кусочек 3: Звездная система + Планеты + Спутники
                                XdPlanet p = new XdPlanet();
                                p.setName("Planet_" + rand.nextInt(1000));
                                XdSatellite s = new XdSatellite();
                                s.setName("Sat_" + rand.nextInt(1000));
                                p.setSatellite(s);

                                sys.addPlanet(p);
                                storage.save(p, tx);
                                storage.update(sys, tx);
                            }
                            tx.commit();
                        } else if (op == 2) {
                            // Периодическое чтение
                            IXdStorageTransaction tx = storage.beginTransaction();
                            storage.load(XdUniverse.class, tx);
                            storage.load(XdPlanet.class, tx);
                            tx.commit();
                        } else {
                            // Периодическое точечное удаление
                            IXdStorageTransaction tx = storage.beginTransaction();
                            Collection<XdPlanet> planets = storage.load(XdPlanet.class, tx);
                            if (!planets.isEmpty()) {
                                storage.delete(planets.iterator().next(), tx);
                            }
                            tx.commit();
                        }
                    } catch (Throwable ignored) {
                        // Оптимистичные коллизии под перекрестным огнем 10 потоков легитимны
                    }
                }
            });
        }

        // Держим базу под жестким прессингом наполнения кусочками
        Thread.sleep(5000);
        stopFlag.set(true);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("🧹 Наполнение завершено. Запускаем каскадную очистку С КОРНЕВЫХ ЭЛЕМЕНТОВ...");

        // ТРЕБОВАНИЕ: Очистка должна производится с корневых элементов — то есть с вселенных
        IXdStorageTransaction cleanTx = storage.beginTransaction();
        try {
            Collection<XdUniverse> universes = storage.load(XdUniverse.class, cleanTx);
            System.out.println("   -> Обнаружено вселенных для пурджа: " + universes.size());

            for (XdUniverse universe : universes) {
                storage.load(universe, cleanTx);
                for (XdGalaxy galaxyRef : universe.getGalaxies()) {
                    try {
                        XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), cleanTx);
                        if (galaxy == null) continue;

                        Collection<XdStarSystem> systems = galaxy.getSystems();
                        storage.load(systems, cleanTx);

                        for (XdStarSystem systemRef : systems) {
                            Collection<XdPlanet> planets = systemRef.getPlanets();
                            storage.load(planets, cleanTx);

                            for (XdPlanet planetRef : planets) {
                                // Вычищаем подчиненные спутники, если они есть
                                storage.delete(planetRef, cleanTx);
                            }
                            storage.delete(systemRef, cleanTx);
                        }
                        storage.delete(galaxy, cleanTx);
                    } catch (Exception e) {
                        System.err.println("   -> Пропуск элемента графа при очистке: " + e.getMessage());
                    }
                }
                storage.delete(universe, cleanTx);
            }
            cleanTx.commit();
            System.out.println("🧹 Единый атомарный коммит очистки зафиксирован успешно.");
        } catch (Exception e) {
            cleanTx.rollback();
            System.err.println("🚨 Крах транзакции каскадного пурджа!");
            throw e;
        }

        // ТРЕБОВАНИЕ: Проверка что все элементы вычищены — запрос по классам
        System.out.println("🔍 Запуск тотальной верификации по классам...");
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        try {
            Assertions.assertTrue(storage.load(XdUniverse.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdUniverse!");
            Assertions.assertTrue(storage.load(XdGalaxy.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdGalaxy!");
            Assertions.assertTrue(storage.load(XdStarSystem.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdStarSystem!");
            Assertions.assertTrue(storage.load(XdPlanet.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdPlanet!");
            verifyTx.commit();
            System.out.println("🎉 Верификация по классам успешна: в памяти СУБД чисто.");
        } catch (Exception e) {
            verifyTx.rollback();
            throw e;
        }

        // Перед побайтовой дисковой проверкой тушим хранилище, чтобы сбросить дескрипторы файлов OS
        storage.shutdown();

        // ТРЕБОВАНИЕ: Проверка, что файлов с мусором не остается после полной очистки
        File storageDir = new File(STORAGE_PATH);
        int remainingDataFiles = 0;
        if (storageDir.exists() && storageDir.isDirectory()) {
            System.out.println("💾 Запущена прецизионная инспекция дисковых файлов...");
            remainingDataFiles = checkDataFilesCount(storageDir);
        }
        Assertions.assertEquals(0, remainingDataFiles,
                "🚨 ДИСКОВАЯ УТЕЧКА! После полной каскадной очистки на диске остались невычищенные файлы пользовательских данных!");

        System.out.println("=== СЦЕНАРИЙ 2 ЗАВЕРШЕН ТРИУМФАЛЬНО: БАЗА И ДИСК АБСОЛЮТНО ЧИСТЫ ===");
    }

    private static int checkDataFilesCount(File dir) {
        File[] files = dir.listFiles();
        int count = 0;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().equals("structure")) {
                    count += checkDataFilesCount(file);
                    continue;
                }
                String name = file.getName();

                // Пропускаем легитимные системные конфигурации метаданных и замки ядра
                if (name.equals("structure") || name.equals("classes.xml") || name.equals("structure.xml")
                        || name.equals("references.yaml") || name.endsWith(".lock") || name.endsWith(".meta")
                        || name.endsWith("id_index.yaml") || name.endsWith("idx.yaml")) {
                    continue;
                }

                count++;
                System.err.println("  -> Обнаружен невычищенный мусорный файл данных: " + file.getAbsolutePath());
            }
        }
        return count;
    }
}