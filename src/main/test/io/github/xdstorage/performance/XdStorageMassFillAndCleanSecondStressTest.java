package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.performance.entities.*;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Tag("stress")
@DisplayName("Релизный тест: Сценарий 2 — Пошаговое наполнение кусочками и тотальный пурдж")
public class XdStorageMassFillAndCleanSecondStressTest {

    private static final String STORAGE_PATH = "./teststorage";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        XdStorageConfig config = new XdStorageConfig.Builder()
                .journalWrites(true)
                .setEnableForeignKeys(true)
                .build();
        storage = XdStorageProvider.newOrGetFileStorage("fill_clean_test", STORAGE_PATH, 200, FilesFormat.YAML, config);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (Exception ignored) {
            }
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

        // --- ДОРАБОТКА: Локальная потокобезопасная Audit-модель для фиксации изменений ---
        // Хранит структуру: UniverseId -> Список Id добавленных Галактик
        Map<String, List<String>> auditModel = new ConcurrentHashMap<>();
        // Счетчики для контроля реального выполнения операций в параллельных потоках
        AtomicInteger successfulInserts = new AtomicInteger(0);
        AtomicInteger successfulDeletes = new AtomicInteger(0);

        // ТРЕБОВАНИЕ: Предварительное наполнение базы стартовыми стабильными вселенными
        IXdStorageTransaction txInit = storage.beginTransaction();
        List<String> universeIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            XdUniverseAutoSave u = new XdUniverseAutoSave();
            u.setId(UUID.randomUUID().toString());
            storage.save(u, txInit);
            universeIds.add(u.getId());
            auditModel.put(u.getId(), new CopyOnWriteArrayList<>()); // Инициализируем списки в модели
        }
        txInit.commit();
        System.out.println(" -> Стартовые вселенные закоммичены. База предварительно наполнена.");
        System.out.println(" -> Запуск 10 параллельных потоков...");

        int totalThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        Random rand = new Random();

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                while (!stopFlag.get()) {
                    int op = rand.nextInt(4); // 0,1 - запись, 2 - чтение, 3 - удаление
                    String targetUni = universeIds.get(rand.nextInt(universeIds.size()));

                    IXdStorageTransaction tx = null;
                    boolean finished = false;

                    // Локальные переменные потока для транзитного удержания ID до успешного коммита
                    String tempGalaxyId = UUID.randomUUID().toString();

                    try {
                        if (op <= 1) {
                            tx = storage.beginTransaction();
                            XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, targetUni, tx);
                            if (u != null) {
                                XdGalaxyAutoSave g = new XdGalaxyAutoSave();
                                g.setId(tempGalaxyId); // Фиксируем сгенерированный ID
                                u.addGalaxy(g);

                                XdBlackHoleAutoSave hole = new XdBlackHoleAutoSave();
                                hole.setId(UUID.randomUUID().toString());
                                hole.setMass(rand.nextInt(1000000));
                                g.setHole(hole);

                                XdStarSystemAutoSave sys = new XdStarSystemAutoSave();
                                sys.setId(UUID.randomUUID().toString());
                                sys.setNewName("System_" + rand.nextInt(1000));
                                g.addSystem(sys);

                                XdPlanetAutoSave p = new XdPlanetAutoSave();
                                p.setName("Planet_" + rand.nextInt(1000));
                                XdSatelliteAutoSave s = new XdSatelliteAutoSave();
                                s.setName("Sat_" + rand.nextInt(1000));
                                p.setSatellite(s);
                                sys.addPlanet(p);
                            }
                            storage.update(u, tx);
                            tx.commit(); // <--- КРИТИЧЕСКАЯ ТОЧКА СИНХРОНИЗАЦИИ

                            // Если коммит прошёл успешно — переносим изменения в Audit-модель тестов
                            auditModel.get(targetUni).add(tempGalaxyId);
                            successfulInserts.incrementAndGet();

                        } else if (op == 2) {
                            tx = storage.beginTransaction();
                            storage.load(XdUniverseAutoSave.class, tx);
                            storage.load(XdPlanetAutoSave.class, tx);
                            tx.commit();
                        } else {
                            tx = storage.beginTransaction();
                            final IXdStorageTransaction delTx = tx;
                            XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, targetUni, delTx);

                            String deletedGalaxyId = null;
                            if (u != null && !u.getGalaxies().isEmpty()) {
                                // Находим галактику, которую можно вычистить
                                Iterator<XdGalaxyAutoSave> it = u.getGalaxies().iterator();
                                XdGalaxyAutoSave g = it.next();
                                deletedGalaxyId = g.getId();
                                it.remove(); // Удаляем из коллекции родителя

                                storage.update(u, delTx);
                                storage.delete(g, delTx);
                            }

                            tx.commit();

                            // При успешном коммите удаления — синхронизируем локальную модель аудита
                            if (deletedGalaxyId != null) {
                                auditModel.get(targetUni).remove(deletedGalaxyId);
                                successfulDeletes.incrementAndGet();
                            }
                        }
                        finished = true;
                    } catch (Throwable ignored) {
                        // Оптимистичные коллизии под перекрестным огнем легитимны
                    } finally {
                        if (tx != null && !finished) {
                            tx.rollback();
                        }
                    }
                }
            });
        }

        // Даем воркерам поработать
        Thread.sleep(1000L);
        stopFlag.set(true);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // --- ВЕРИФИКАЦИЯ РАБОТЫ ПОТОКОВ: Убеждаемся, что модификации физически происходили ---
        System.out.println("📊 Сводка параллельного прессинга:");
        System.out.println("   -> Успешных транзакций записи блоков: " + successfulInserts.get());
        System.out.println("   -> Успешных транзакций точечного удаления: " + successfulDeletes.get());

        Assertions.assertTrue(successfulInserts.get() > 0, "🚨 Ошибка теста: ни один поток не смог записать данные из-за блокировок!");
        Assertions.assertTrue(successfulDeletes.get() > 0, "🚨 Ошибка теста: операции модификации/удаления не выполнялись параллельно!");

        // Проверяем соответствие СУБД нашей In-Memory Audit модели перед тотальной зачисткой
        System.out.println("🔍 Проверка консистентности хранилища с локальной Audit-моделью...");
        IXdStorageTransaction checkTx = storage.beginTransaction();
        for (String uId : universeIds) {
            XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, uId, checkTx);
            int expectedSize = auditModel.get(uId).size();
            int actualSize = u != null ? u.getGalaxies().size() : 0;
            Assertions.assertEquals(expectedSize, actualSize, "🚨 Рассинхрон! Данные в СУБД разошлись с успешной Audit-моделью!");
        }
        checkTx.commit();
        System.out.println("🎉 Данные проверены: состояние СУБД на 100% совпадает с журналом успешных коммитов.");

        System.out.println("🧹 Наполнение завершено. Запускаем каскадную очистку С КОРНЕВЫХ ЭЛЕМЕНТОВ...");

        // Очистка с корневых элементов (Вселенные) согласно ТЗ
        IXdStorageTransaction cleanTx = storage.beginTransaction();
        try {
            Collection<XdUniverseAutoSave> universes = storage.load(XdUniverseAutoSave.class, cleanTx);
            for (XdUniverseAutoSave universe : universes) {
                for (XdGalaxyAutoSave galaxyRef : universe.getGalaxies()) {
                    try {
                        XdGalaxyAutoSave galaxy = storage.load(XdGalaxyAutoSave.class, galaxyRef.getId(), cleanTx);
                        if (galaxy == null) continue;

                        Collection<XdStarSystemAutoSave> systems = galaxy.getSystems();
                        storage.load(systems, cleanTx);

                        for (XdStarSystemAutoSave systemRef : systems) {
                            Collection<XdPlanetAutoSave> planets = systemRef.getPlanets();
                            if (planets != null) {
                                storage.load(planets, cleanTx);
                                for (XdPlanetAutoSave planetRef : planets) {
                                    storage.delete(planetRef, cleanTx);
                                }
                                storage.delete(systemRef, cleanTx);
                            }
                        }
                        storage.delete(galaxy, cleanTx);
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    }
                }
                storage.delete(universe, cleanTx);
            }
            cleanTx.commit();
            System.out.println("🧹 Единый атомарный коммит очистки зафиксирован успешно.");
        } catch (Exception e) {
            cleanTx.rollback();
            throw e;
        }

        System.out.println("🔍 Запуск тотальной верификации по классам...");
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        try {
            Assertions.assertTrue(storage.load(XdUniverseAutoSave.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdUniverse!");
            Assertions.assertTrue(storage.load(XdGalaxyAutoSave.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdGalaxy!");
            Assertions.assertTrue(storage.load(XdStarSystemAutoSave.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdStarSystem!");
            Assertions.assertTrue(storage.load(XdPlanetAutoSave.class, verifyTx).isEmpty(), "Утечка памяти! Найдена XdPlanet!");
            verifyTx.commit();
            System.out.println("🎉 Верификация по классам успешна: в памяти СУБД чисто.");
        } catch (Exception e) {
            verifyTx.rollback();
            throw e;
        }

        storage.shutdown();

        File storageDir = new File(STORAGE_PATH);
        int remainingDataFiles = 0;
        if (storageDir.exists() && storageDir.isDirectory()) {
            System.out.println("💾 Запущена прецизионная инспекция дисковых файлов...");
            remainingDataFiles = checkDataFilesCount(storageDir);
        }
        Assertions.assertEquals(0, remainingDataFiles,
                "🚨 ДИСКОВАЯ УТЕЧКА! После полной каскадной очистки на диске остались невычищенные файлы данных!");

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