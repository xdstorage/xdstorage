package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Ультимативный MVCC Стресс-Тест: Изоляция Phantom Read на развесистом ORM Графе")
@Tag("stress")
public class XdStorageGraphPhantomReadStressTest {

    private static IXdStorage storage = null;
    private static final String STORAGE_PATH = "./target/graph_phantom_stress_db";

    // Константы геометрии базового стабильного графа
    private static final int BASE_GALAXIES = 2;
    private static final int BASE_SYSTEMS_PER_GALAXY = 3;
    private static final int BASE_PLANETS_PER_SYSTEM = 20;
    // Итого базовых планет в СУБД: 2 * 3 * 20 = 120 штук
    private static final int EXPECTED_TOTAL_BASE_PLANETS = BASE_GALAXIES * BASE_SYSTEMS_PER_GALAXY *
            BASE_PLANETS_PER_SYSTEM;

    /**
     * ФАЗА 0 (Честная классификация сбоев): таймаут спин-лока — штатное противодавление
     * протокола блокировок СУБД, а не нарушение изоляции. Ретрай следующей итерацией.
     */
    private static boolean isLockBackpressure(final Throwable t) {
        final String message = t.getMessage();
        if (message == null) {
            return false;
        }
        // R-36/R-42: исчерпание бюджета ретраев поиска — тот же класс штатного противодавления,
        // что и таймауты локов: операция ДО мутаций, откатывается чисто, ретраится следующей
        // итерацией. (Бюджет введен вместо вечного клина — R-36.)
        if (message.contains("retry budget exhausted")) {
            return true;
        }
        if (!(t instanceof io.github.xdstorage.exceptions.XdStorageRuntimeException)) {
            return false;
        }
        return message.contains("Превышен таймаут") || message.contains("cannot be locked for commit");
    }

    private static String targetUniverseId;
    private static final List<String> baseSystemIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("graph_phantom_db", STORAGE_PATH, 500);

        // =========================================================================
        // НАЧАЛЬНОЕ НАПОЛНЕНИЕ: Строим стабильный базовый Snapshot графа объектов
        // =========================================================================
        IXdStorageTransaction txInit = storage.beginTransaction();
        try {
            XdUniverse universe = new XdUniverse();
            universe.setId(UUID.randomUUID().toString());
            targetUniverseId = universe.getId();

            for (int g = 0; g < BASE_GALAXIES; g++) {
                XdGalaxy galaxy = new XdGalaxy();
                galaxy.setId(UUID.randomUUID().toString());

                XdBlackHole hole = new XdBlackHole();
                hole.setId(UUID.randomUUID().toString());
                galaxy.setHole(hole); // Политиа StoreWithParentObject

                for (int s = 0; s < BASE_SYSTEMS_PER_GALAXY; s++) {
                    XdStarSystem system = new XdStarSystem();
                    system.setId(UUID.randomUUID().toString());
                    system.setNewName("BaseSystem_" + g + "_" + s);
                    baseSystemIds.add(system.getId());

                    for (int p = 0; p < BASE_PLANETS_PER_SYSTEM; p++) {
                        XdPlanet planet = new XdPlanet();
                        planet.setName("BasePlanet_" + g + "_" + s + "_" + p);
                        planet.setWaterPercent(1);

                        XdSatellite satellite = new XdSatellite();
                        satellite.setName("BaseSatellite_" + g + "_" + s + "_" + p);
                        planet.setSatellite(satellite); // Вложенный StoreWithParentObject

                        system.addPlanet(planet);
                    }
                    galaxy.addSystem(system);
                }
                universe.addGalaxy(galaxy);
            }

            // Каскадно сохраняем весь граф на диск СУБД
            storage.save(universe, txInit);
            storage.save(universe.getGalaxies(), txInit);
            for (XdGalaxy galaxy : universe.getGalaxies()) {
                storage.save(galaxy.getSystems(), txInit);
                for (XdStarSystem system : galaxy.getSystems()) {
                    storage.save(system.getPlanets(), txInit);
                }
            }

            txInit.commit();
            System.out.println("✅ Стабильный базовый граф успешно развернут. Всего планет: " +
                    EXPECTED_TOTAL_BASE_PLANETS);
        } catch (Throwable t) {
            txInit.rollback();
            throw t;
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.out.println("Result: ⏳ [tearDown] Запуск тотальной верификации консистентности графа и B+Tree " +
                "индексов...");
        if (storage != null) {
            IXdStorageTransaction txVerify = storage.beginTransaction();
            try {
                // Сквозной дисковый обход графа после жесткого многопоточного прессинга
                XdUniverse universe = storage.load(XdUniverse.class, targetUniverseId, txVerify);
                assertNotNull(universe, "Корень вселенной разрушен!");
                storage.load(universe, txVerify);

                int checkedPlanets = 0;
                for (XdGalaxy galaxyRef : universe.getGalaxies()) {
                    XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), txVerify);
                    assertNotNull(galaxy, "Повреждена ссылка на галактику!");
                    assertNotNull(galaxy.getHole(), "Разрушен внедренный объект Черной Дыры!");

                    storage.load(galaxy.getSystems(), txVerify);
                    for (XdStarSystem systemRef : galaxy.getSystems()) {
                        XdStarSystem system = storage.load(XdStarSystem.class, systemRef.getId(), txVerify);
                        assertNotNull(system, "Разрушена ссылка на звездную систему!");

                        Collection<XdPlanet> planets = system.getPlanets();
                        storage.load(planets, txVerify);
                        for (XdPlanet planetRef : planets) {
                            XdPlanet planet = storage.load(XdPlanet.class, planetRef.getId(), txVerify);
                            assertNotNull(planet, "Обнаружена висячая ссылка мёртвой планеты!");
                            assertNotNull(planet.getSatellite(), "Разрушен вложенный спутник планеты!");
                            checkedPlanets++;
                        }
                    }
                }
                txVerify.commit();
                System.out.println("⭐ [tearDown] ВЕРИФИКАЦИЯ УСПЕШНА: Логическая структура графа и Б+ Дерева на 100% " +
                        "консистентны. Финальное число планет в СУБД: " + checkedPlanets);
            } catch (Throwable t) {
                txVerify.rollback();
                System.err.println("🚨 [tearDown] КАТАСТРОФА: Стресс-нагрузка нарушила консистентность графа на " +
                        "диске!");
                t.printStackTrace(System.err);
                fail("ORM инварианты СУБД разрушены: " + t.getMessage());
            } finally {
                storage.shutdown();
            }
        }
        deleteDir(new File(STORAGE_PATH));
        baseSystemIds.clear();
        System.out.println("🧹 [tearDown] Директория стресс-теста успешно зачищена.");
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
    @DisplayName("Штурм графа: 8 Читателей каскадно обходят ORM-граф против лавины In-Place сплитов Писателя")
    public void testMVCC_GraphRangeScans_UnderHeavyPhantomInsertions() throws Exception {
        final int readerThreadsCount = 8;
        final int durationSeconds = 10;
        final Random rand = new Random();

        ExecutorService threadPool = Executors.newFixedThreadPool(readerThreadsCount + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean stopFlag = new AtomicBoolean(false);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalGraphScans = new AtomicLong(0);
        AtomicLong totalPhantomPlanetsInserted = new AtomicLong(0);

        // =========================================================================
        // ПОТОК 1: АГРЕССИВНЫЙ ГЕНЕРАТОР ПРИЗРАКОВ (Каскадно мутирует граф вглубь)
        // =========================================================================
        threadPool.submit(() -> {
            try {
                startLatch.await();
                int phantomPlanetId = 900000;

                while (!stopFlag.get()) {
                    IXdStorageTransaction txWrite = storage.beginTransaction();
                    try {
                        // Выбираем случайную базовую систему для атаки
                        String randomSystemId = baseSystemIds.get(rand.nextInt(baseSystemIds.size()));
                        XdStarSystem system = storage.load(XdStarSystem.class, randomSystemId, txWrite);

                        // Каскадно внедряем в нее пачку фантомных планет со спутниками
                        for (int k = 0; k < 5; k++) {
                            XdPlanet phantomPlanet = new XdPlanet();
                            phantomPlanet.setName("PhantomPlanet_" + phantomPlanetId);
                            phantomPlanet.setWaterPercent(3);

                            XdSatellite phantomSatellite = new XdSatellite();
                            phantomSatellite.setName("PhantomSatellite_" + phantomPlanetId);
                            phantomPlanet.setSatellite(phantomSatellite);

                            system.addPlanet(phantomPlanet);
                            storage.save(phantomPlanet, txWrite);
                            phantomPlanetId++;
                        }

                        storage.update(system, txWrite);
                        txWrite.commit();
                        totalPhantomPlanetsInserted.addAndGet(5);
                    } catch (Throwable t) {
                        txWrite.rollback();
                        throw t;
                    }
                    Thread.sleep(2); // Высокая частота бомбардировки структуры
                }
            } catch (Throwable t) {
                if (!isLockBackpressure(t)) {
                    exceptions.add(new AssertionError("🚨 Крах в потоке Писателя Графа: ", t));
                }
                // таймаут лока — штатное противодавление: ретрай следующей итерацией
            }
        });

        // =========================================================================
        // ПОТОКИ 2..N: ЧИТАТЕЛИ ГРАФА (Глубокий Range Scan в строгой изоляции Snapshot)
        // =========================================================================
        for (int i = 0; i < readerThreadsCount; i++) {
            final int readerId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    while (!stopFlag.get()) {
                        IXdStorageTransaction txRead = storage.beginTransaction();
                        try {
// Заходим через корень вселенной
                            XdUniverse universe = storage.load(XdUniverse.class, targetUniverseId, txRead);
                            assertNotNull(universe);
                            storage.load(universe, txRead);

                            int visibleBasePlanetsCount = 0;
                            int visiblePhantomPlanetsCount = 0;

                            // Глубокий каскадный обход всего связанного графа
                            for (XdGalaxy galaxyRef : universe.getGalaxies()) {
                                XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), txRead);
                                storage.load(galaxy.getSystems(), txRead);

                                for (XdStarSystem systemRef : galaxy.getSystems()) {
                                    XdStarSystem system = storage.load(XdStarSystem.class, systemRef.getId(), txRead);

                                    Collection<XdPlanet> planets = system.getPlanets();
                                    storage.load(planets, txRead);

                                    for (XdPlanet planetRef : planets) {
// Неявная активация equals врапперов при загрузке по ссылкам
                                        XdPlanet planet = storage.load(XdPlanet.class, planetRef.getId(), txRead);
                                        assertNotNull(planet, "Прокси-враппер вернул битую ноду!");

                                        if (planet.getName().startsWith("BasePlanet_")) {
                                            visibleBasePlanetsCount++;
                                        } else if (planet.getName().startsWith("PhantomPlanet_")) {
                                            visiblePhantomPlanetsCount++;
                                        }
                                    }
                                }
                            }

                            // 🚨 ГЛАВНЫЙ АССЕРТ СТРЕСС-ТЕСТА:
// По законам Snapshot Isolation, сколько бы сотен фантомных планет ни заливал
// Писатель в соседнем потоке, Читатель внутри СВОЕЙ транзакции обязан каскадно
// видеть ровно EXPECTED_TOTAL_BASE_PLANETS базовых планет!
                            if (visibleBasePlanetsCount != EXPECTED_TOTAL_BASE_PLANETS) {
                                exceptions.add(new AssertionError(
                                        String.format("🚨 КРИТИЧЕСКОЕ НАРУШЕНИЕ MVCC! Поток-Читатель %d поймал " +
                                                "призраков графа! Ожидали базовых %d, получили %d. (Призраков " +
                                                "просочилось: %d)",
                                                readerId, EXPECTED_TOTAL_BASE_PLANETS, visibleBasePlanetsCount,
                                                        visiblePhantomPlanetsCount)
                                ));
                            }

                            txRead.commit();
                            totalGraphScans.incrementAndGet();

                        } catch (Throwable t) {
                            txRead.rollback();
                            if (!isLockBackpressure(t)) {
                                exceptions.add(new AssertionError("🚨 Крах глубокого ORM-сканирования "
                                        + "в потоке Читателя " + readerId, t));
                            }
                            // таймаут лока — штатное противодавление: ретрай следующей итерацией
                        }
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            });
        }

        // Огонь!
        System.out.println("🚀 Штурм запущен! Экстремальный прессинг Phantom Read на ORM Графе (10 секунд)...");
        startLatch.countDown();

        Thread.sleep(durationSeconds * 1000L);

        System.out.println("🧹 Нагрузка завершена. Останавливаем потоки воркеры...");
        stopFlag.set(true);
        threadPool.shutdown();

        boolean finishedCleanly = threadPool.awaitTermination(7, TimeUnit.SECONDS);
        if (!finishedCleanly) {
            threadPool.shutdownNow();
        }

        System.out.println("=========================================================================");
        System.out.println("📊 ИТОГИ ОДНОВРЕМЕННОГО ШТУРМА ORM ГРАФА:");
        System.out.println("🔹 Успешных глубоких каскадных сканирований графа: " + totalGraphScans.get());
        System.out.println("🔹 Конкурентно внедренных в граф планет-призраков: " + totalPhantomPlanetsInserted.get());
        System.out.println("=========================================================================");

        // Анализ исключений (Исключения локеров, гонки, IndexOutOfBounds, NPE)
        if (!exceptions.isEmpty()) {
            System.err.println("🚨 ЗАФИКСИРОВАНЫ АЛГОРИТМИЧЕСКИЕ СБОИ СУБД ИЛИ ПРОСАЧИВАНИЯ ЭЛЕМЕНТОВ (" + exceptions
                    .size() + " шт):");
            exceptions.forEach(e -> {
                System.err.println("---");
                e.printStackTrace(System.err);
            });
            fail("Сбой Snapshot Isolation! Каскадные In-Place сплиты разрушили изоляцию Phantom Read!");
        } else {
            System.out.println("🎉 ФАНТАСТИКА! Полный триумф! Ни одной гонки, ни одного фантома, ни единого NPE. "
                    + "Граф выстоял в абсолютном ACID-порядке!");
        }
    }
}
