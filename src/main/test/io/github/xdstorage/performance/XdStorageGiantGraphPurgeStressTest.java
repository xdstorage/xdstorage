package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Экстремальный ORM-тест: Каскадный пурдж гигантского случайного графа")
@Tag("stress")
public class XdStorageGiantGraphPurgeStressTest {

    private static IXdStorage storage = null;
    private static final String STORAGE_PATH = "./target/giant_graph_purge_test_db";
    private static final Random random = new Random(2026); // Фиксированный сид для воспроизводимости

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("giant_graph_test", STORAGE_PATH, 500);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_PATH));
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

    /**
     * Генерация нелинейного случайного графа с перемешиванием вложенных структур.
     * Здесь используются сущности с гетерогенными политиками: StoreAsClassObjects и StoreWithParentObject.
     */
    private XdUniverse generateGiantRandomGraph(int numGalaxies, int maxSystemsPerGalaxy, int maxPlanetsPerSystem) {
        XdUniverse universe = new XdUniverse();
        universe.setId(UUID.randomUUID().toString());

        for (int g = 0; g < numGalaxies; g++) {
            XdGalaxy galaxy = new XdGalaxy();
            galaxy.setId(UUID.randomUUID().toString());

            // Внедряем объект политики StoreWithParentObject
            XdBlackHole hole = new XdBlackHole();
            hole.setId(UUID.randomUUID().toString());
            galaxy.setHole(hole);

            int systemsCount = random.nextInt(maxSystemsPerGalaxy) + 1;
            for (int s = 0; s < systemsCount; s++) {
                XdStarSystem system = new XdStarSystem();
                system.setId(UUID.randomUUID().toString());
                system.setNewName("RandomSystem_" + g + "_" + s);

                // Добавляем звезды (StoreWithParentObject)
                int starsCount = random.nextInt(2) + 1;
                for (int st = 0; st < starsCount; st++) {
                    XdStar star = new XdStar();
                    star.setId(UUID.randomUUID().toString());
                    star.setName("Star_" + g + "_" + s + "_" + st);
                    system.addStar(star);
                }

                // Добавляем планеты (StoreAsClassObjects + собственный B+Tree индекс t=50)
                int planetsCount = random.nextInt(maxPlanetsPerSystem) + 1;
                for (int p = 0; p < planetsCount; p++) {
                    XdPlanet planet = new XdPlanet();
                    planet.setName("Planet_" + g + "_" + s + "_" + p);
                    planet.setWaterPercent(random.nextInt(3));

                    // Внедряем спутники (StoreWithParentObject вглубь планеты)
                    XdSatellite satellite = new XdSatellite();
                    satellite.setName("Satellite_" + g + "_" + s + "_" + p);
                    planet.setSatellite(satellite);

                    system.addPlanet(planet);
                }
                galaxy.addSystem(system);
            }
            universe.addGalaxy(galaxy);
        }
        return universe;
    }

    @Test
    @DisplayName("Штурм СУБД: Вставка гигантского случайного графа и тотальное каскадное удаление в одну транзакцию")
    public void testGiantGraph_MassiveInsertAndTotalPurge_ShouldNotDropAnyExceptions() throws Exception {
        // Наполняем базу большим развесистым графом (всего сгенерируется более 1000+ связанных объектов)
        int numGalaxies = 15;
        int maxSystemsPerGalaxy = 10;
        int maxPlanetsPerSystem = 15;

        System.out.println("⏳ [1/5] Генерация развесистого случайного графа в памяти...");
        XdUniverse giantUniverse = generateGiantRandomGraph(numGalaxies, maxSystemsPerGalaxy, maxPlanetsPerSystem);

        // =========================================================================
        // ФАЗА 1: МАССОВАЯ ТРАНЗАКЦИОННАЯ ВСТАВКА ГРАФА
        // =========================================================================
        System.out.println("⏳ [2/5] Сохранение графа на диск и фиксация транзакции...");
        IXdStorageTransaction txInsert = storage.beginTransaction();

        storage.save(giantUniverse, txInsert);
        storage.save(giantUniverse.getGalaxies(), txInsert);
        for (XdGalaxy galaxy : giantUniverse.getGalaxies()) {
            storage.save(galaxy.getSystems(), txInsert);
            for (XdStarSystem system : galaxy.getSystems()) {
                if (system != null) {
                    storage.save(system.getPlanets(), txInsert);
                }
            }
        }
        txInsert.commit();
        System.out.println("✅ Граф успешно сериализован и сохранен в B+Tree индексы СУБД.");

        // =========================================================================
        // ФАЗА 1.5: ИНСАЙТ-ВЕРИФИКАЦИЯ ВСТАВКИ И БАЛАНСИРОВКИ (Новая фаза!)
        // Проверяем полную загрузку всего графа до начала удаления.
        // Если упадет здесь — значит, баг сидит в алгоритмах расщепления split!
        // =========================================================================
        System.out.println("⏳ [2.5/5] ТОТАЛЬНАЯ ВЕРИФИКАЦИЯ СТРУКТУРЫ ВСТАВКИ (Диагностика сплитов)...");
        IXdStorageTransaction txVerifyInsert = storage.beginTransaction();
        try {
            Collection<XdUniverse> universes = storage.load(XdUniverse.class, txVerifyInsert);
            for (XdUniverse universe : universes) {
                storage.load(universe, txVerifyInsert);
                for (XdGalaxy galaxyRef : universe.getGalaxies()) {
                    XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), txVerifyInsert);
                    assertNotNull(galaxy, "Ошибка вставки: Галактика не найдена в индексе Б+ Дерева!");

                    storage.load(galaxy.getSystems(), txVerifyInsert);
                    for (XdStarSystem systemRef : galaxy.getSystems()) {
                        XdStarSystem system = storage.load(XdStarSystem.class, systemRef.getId(), txVerifyInsert);
                        assertNotNull(system, "Ошибка вставки: Звездная система потеряна в индексе Б+ Дерева!");

                        // Пытаемся лениво материализовать все до единой планеты
                        Collection<XdPlanet> planets = system.getPlanets();
                        storage.load(planets, txVerifyInsert);
                        for (XdPlanet planetRef : planets) {
                            XdPlanet planet = storage.load(XdPlanet.class, planetRef.getId(), txVerifyInsert);
                            assertNotNull(planet, "Ошибка вставки: Планета потеряна из-за неверного сплита Б+ Дерева!");
                        }
                    }
                }
            }
            txVerifyInsert.commit();
            System.out.println("⭐ ФАЗА ВСТАВКИ ИДЕАЛЬНА! Все ноды дерева сбалансированы правильно. Баг сидит строго " +
                    "в слиянии (Delete/Join)!");
        } catch (Throwable t) {
            txVerifyInsert.rollback();
            System.err.println("🚨 КРАХ НА ФАЗЕ ВСТАВКИ! Балансировка при разделении страниц повреждает разделители " +
                    "Кнута!");
            throw t;
        }

        // =========================================================================
        // ФАЗА 2: ТОТАЛЬНОЕ КАСКАДНОЕ УДАЛЕНИЕ В ОДНУ МОНОЛИТНУЮ ТРАНЗАКЦИЮ
        // =========================================================================
        System.out.println("⏳ [3/5] Стартуем монолитную каскадную зачистку всего графа объектов...");
        IXdStorageTransaction txPurge = storage.beginTransaction();

        Collection<XdUniverse> universesToClean = storage.load(XdUniverse.class, txPurge);
        assertFalse(universesToClean.isEmpty(), "База данных пуста перед пурджем!");

        for (XdUniverse universe : universesToClean) {
            storage.load(universe, txPurge);
            Collection<XdGalaxy> galaxies = universe.getGalaxies();
            storage.load(galaxies, txPurge);

            for (XdGalaxy galaxyRef : galaxies) {
                XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), txPurge);
                if (galaxy == null) continue;

                Collection<XdStarSystem> systems = galaxy.getSystems();
                storage.load(systems, txPurge);

                for (XdStarSystem systemRef : systems) {
                    XdStarSystem system = storage.load(XdStarSystem.class, systemRef.getId(), txPurge);
                    if (system == null) continue;

                    Collection<XdPlanet> planets = system.getPlanets();
                    storage.load(planets, txPurge);

                    for (XdPlanet planetRef : planets) {
                        storage.delete(planetRef, txPurge);
                        System.err.println("DELETED PLANET " + planetRef.getId());
                    }
                    storage.delete(system, txPurge);
                }
                storage.delete(galaxy, txPurge);
            }
            storage.delete(universe, txPurge);
        }

        txPurge.commit();
        System.out.println("✅ Монолитный каскадный коммит пурджа завершен успешно.");

        // =========================================================================
        // ФАЗА 3: ВЕРИФИКАЦИЯ ФИНАЛЬНОГО ИНВАРЕАНТА (ЧИСТАЯ СУБД)
        // =========================================================================
        System.out.println("⏳ [4/5] Верификация финального инварианта пустой базы данных...");
        IXdStorageTransaction txVerify = storage.beginTransaction();

        Collection<XdUniverse> checkUniverses = storage.load(XdUniverse.class, txVerify);
        Collection<XdGalaxy> checkGalaxies = storage.load(XdGalaxy.class, txVerify);
        Collection<XdPlanet> checkPlanets = storage.load(XdPlanet.class, txVerify);

        txVerify.commit();

        assertTrue(checkUniverses.isEmpty(), "Таблица XdUniverse не очищена после каскадного пурджа!");
        assertTrue(checkGalaxies.isEmpty(), "Таблица XdGalaxy не очищена после каскадного пурджа!");
        assertTrue(checkPlanets.isEmpty(), "Таблица XdPlanet не очищена после каскадного пурджа!");

        System.out.println("🎉 ПОЛНЫЙ ТРИУМФ! Огромный случайный граф каскадно вычищен в одну транзакцию без единой " +
                "ошибки!");
    }
}
