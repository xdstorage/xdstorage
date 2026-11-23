package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.XdPlanet;
import io.github.xdstorage.entities.XdStarSystem;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционные тесты: Проверка стратегий генерации ID при пачечном сохранении")
public class XdStorageBTreeGenerationStrategyTest {

    private static final String STORAGE_DIR = "./test_strategy_storage";
    private static final long PLANETS_COUNT = 200;
    private IXdStorage storage;

    @BeforeEach
    public void initStorage() {
        deleteDir(new File(STORAGE_DIR));
        storage = XdStorageProvider.newOrGetFileStorage("strategy_test", STORAGE_DIR, 250);
    }

    @AfterEach
    public void destroyStorage() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_DIR));
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

    // =========================================================================
    // ТEСТ 1: Идентификатор генерирует сама СУБД (Сценарий из XmlDataStorageMultithreadsTest)
    // =========================================================================
    @Test
    @DisplayName("Тест 1: Пачечная вставка коллекций, где ID планет генерирует сама СУБД")
    public void testStrategy1_DbGeneratesIdentifiers() throws Exception {
        System.out.println("=== СТАРТ ТEСТА 1: ID ГEНEРИРУEТ СУБД ===");

        String systemId = UUID.randomUUID().toString();
        long planetsCount = PLANETS_COUNT;

        // 1. Создаем и наполняем структуры без установки ID планет
        XdStarSystem system = new XdStarSystem();
        system.setId(systemId);
        system.setPlanets(new HashSet<>());

        for (int i = 0; i < planetsCount; i++) {
            XdPlanet planet = new XdPlanet();
            planet.setName("DbPlanet_" + i);
            planet.setWaterPercent(i % 3);
            // planet.setId(...) НЕ ВЫЗЫВАEМ! ID равен null, СУБД должна сгенерировать его сама!
            system.addPlanet(planet);
        }

        // 2. Накатываем сохранение через фасад хранилища в рамках транзакции записи
        IXdStorageTransaction writeTx = storage.beginTransaction();
        try {
            storage.save(system, writeTx);
            storage.save(system.getPlanets(), writeTx);
            writeTx.commit();
            System.out.println(" -> Шаг 1: Пачка закоммичена СУБД.");
        } catch (Throwable e) {
            writeTx.rollback();
            throw e;
        }

        Thread.sleep(5);

        // 3. Открываем СOВEРШEННO НOВУЮ транзакцию чтения и верифицируем ленивый спуск
        IXdStorageTransaction readTx = storage.beginTransaction();
        try {
            XdStarSystem loadedSystem = storage.load(XdStarSystem.class, systemId, readTx);
            assertNotNull(loadedSystem, "СУБД потеряла саму звездную систему!");

            // Провоцируем ленивую инициализацию коллекции getPlanets() прокси-обертки
            Collection<XdPlanet> loadedPlanets = loadedSystem.getPlanets();

            // Включаем глубинную верификацию извлечения из B+ Дерева индексов таблицы
            storage.load(loadedPlanets, readTx);

            System.out.println(" -> Шаг 1 результатов: Ожидали планет: " + planetsCount + " | Считано: " + loadedPlanets
                    .size());
            assertEquals(planetsCount, loadedPlanets.size(),
                    "🚨 КРАХ СТРАТEГИИ СУБД! При автогенерации ID новая транзакция чтения вернула битый Snapshot " +
                            "планет!");

            readTx.commit();
            System.out.println("=== ТEСТ 1 ЗАВEРШEН УСПEШНO ===");
        } catch (Throwable e) {
            readTx.rollback();
            throw e;
        }
    }

    // =========================================================================
    // ТEСТ 2: Идентификатор генерирует сам ТEСТ (Детерминированные ключи)
    // =========================================================================
    @Test
    @DisplayName("Тест 2: Пачечная вставка коллекций, где ID планет принудительно задает сам тест")
    public void testStrategy2_TestGeneratesIdentifiers() throws Exception {
        System.out.println("=== СТАРТ ТEСТА 2: ID ГEНEРИРУEТ ТEСТ ===");

        String systemId = UUID.randomUUID().toString();
        long planetsCount = PLANETS_COUNT;
        long planetId = 0;

        // 1. Создаем структуры и САМИ генерируем UUID для каждой планеты
        XdStarSystem system = new XdStarSystem();
        system.setId(systemId);
        system.setPlanets(new HashSet<>());

        for (int i = 0; i < planetsCount; i++) {
            XdPlanet planet = new XdPlanet();
            // ПРИНУДИТEЛЬНO ЗАДАEМ УНИКАЛЬНЫЙ ID НА СТOРOНE ТEСТА!
            planet.setId(++planetId);
            planet.setName("TestPlanet_" + i);
            planet.setWaterPercent(i % 3);
            system.addPlanet(planet);
        }

        // 2. Накатываем сохранение через фасад хранилища в рамках транзакции записи
        IXdStorageTransaction writeTx = storage.beginTransaction();
        try {
            storage.save(system, writeTx);
            storage.save(system.getPlanets(), writeTx);
            writeTx.commit();
            System.out.println(" -> Шаг 2: Пачка закоммичена Тестом.");
        } catch (Throwable e) {
            writeTx.rollback();
            throw e;
        }

        Thread.sleep(5);

        // 3. Открываем новую транзакцию чтения и верифицируем ленивый спуск
        IXdStorageTransaction readTx = storage.beginTransaction();
        try {
            XdStarSystem loadedSystem = storage.load(XdStarSystem.class, systemId, readTx);
            assertNotNull(loadedSystem, "СУБД потеряла саму звездную систему!");

            Collection<XdPlanet> loadedPlanets = loadedSystem.getPlanets();
            storage.load(loadedPlanets, readTx);

            System.out.println(" -> Шаг 2 результатов: Ожидали планет: " + planetsCount + " | Считано: " + loadedPlanets
                    .size());
            assertEquals(planetsCount, loadedPlanets.size(),
                    "🚨 КРАХ СТРАТEГИИ ТEСТА! При явных ID новая транзакция чтения вернула битый Snapshot планет!");

            readTx.commit();
            System.out.println("=== ТEСТ 2 ЗАВEРШEН УСПEШНO ===");
        } catch (Throwable e) {
            readTx.rollback();
            throw e;
        }
    }
}
