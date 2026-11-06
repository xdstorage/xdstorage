package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.Collection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Архитектурный ORM-тест: Проверка графа объектов с гетерогенными политиками и B+Tree")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class XdStorageObjectGraphPolicyAndIndexTest {

    private static IXdStorage storage = null;
    private static final String STORAGE_PATH = "./target/object_graph_policy_test_db";

    @BeforeAll
    public static void initStorage() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("graph_policy_test", STORAGE_PATH, 250);
    }

    @AfterAll
    public static void destroyStorage() {
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

    // =========================================================================
    // ЭТАП 1: СОХРАНЕНИЕ И ТРАНЗАКЦИОННЫЙ ДВУХФАЗНЫЙ КОММИТ ГРАФА
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("1. Вставка сложного графа: проверка StoreAsClassObjects и внедрения StoreWithParentObject")
    public void testGraph_InsertAndCommit_ShouldPersistAllPoliciesCleanly() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        try {
            // Строим корневой объект (StoreAsClassObjects)
            XdUniverse universe = new XdUniverse();
            universe.setId(UUID.randomUUID().toString());

            // Строим Галактику (StoreAsClassObjects + BTree t=10)
            XdGalaxy galaxy = new XdGalaxy();
            galaxy.setId(UUID.randomUUID().toString());

            // Внедряем Черную Дыру (StoreWithParentObject!)
            XdBlackHole hole = new XdBlackHole();
            hole.setId(UUID.randomUUID().toString());
            galaxy.setHole(hole);

            // Регистрируем связи в графе сущностей
            universe.addGalaxy(galaxy);

            // Сохраняем граф через транзакционный контекст фасада СУБД
            storage.save(universe, tx);
            storage.save(universe.getGalaxies(), tx);

            tx.commit();
        } catch (Throwable t) {
            tx.rollback();
            fail("Крах сохранения гетерогенного графа объектов: " + t.getMessage());
        }
    }

    // =========================================================================
    // ЭТАП 2: ЛЕНИВАЯ ПОДГРУЗКА И ВЕРИФИКАЦИЯ Б+ ДЕРЕВА
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("2. Ленивая загрузка графа: проверка извлечения через B+Tree индексы и ORM-обертки")
    public void testGraph_LazyLoadAndVerify_ShouldExtractObjectsCorrectly() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction(100L);

        try {
            // Вычитываем корни из независимой таблицы классов
            Collection<XdUniverse> universes = storage.load(XdUniverse.class, tx);
            assertEquals(1, universes.size(), "Корень вселенной обязан вычитаться из дисковой таблицы классов!");

            for (XdUniverse universeRef : universes) {
                // Выполняем подгрузку транзакционного прокси-контекста
                storage.load(universeRef, tx);

                Collection<XdGalaxy> galaxies = universeRef.getGalaxies();
                assertFalse(galaxies.isEmpty(), "Коллекция галактик не должна быть пустой!");

                for (XdGalaxy galaxyRef : galaxies) {
                    // АКТИВАЦИЯ ССЫЛКИ: Загружаем полноценный объект галактики через B+Tree первичных ключей!
                    XdGalaxy galaxy = storage.load(XdGalaxy.class, galaxyRef.getId(), tx);
                    assertNotNull(galaxy, "Галактика обязана успешно материализоваться через B+Tree индекс!");

                    // ПРOВЕРКА ПОЛИТИКИ StoreWithParentObject:
                    // Объект Черной Дыры обязан был десериализоваться из тела родительской галактики!
                    XdBlackHole hole = galaxy.getHole();
                    assertNotNull(hole, "Внедренный объект XdBlackHole (StoreWithParentObject) потерян при десериализации графа родителя!");
                    assertNotNull(hole.getId(), "Идентификатор внедренной черной дыры не должен быть null!");
                }
            }
            tx.commit();
        } catch (Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // ЭТАП 3: ИЗОЛЯЦИЯ ОТКАТА (ACID ATOMICITY)
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("3. Роллбэк грязных мутаций: проверка полной изоляции стейта при сбое")
    public void testGraph_RollbackDirtyMutation_ShouldKeepPreviousState() throws Exception {
        // Убеждаемся, что база содержит стабильное состояние с прошлых шагов
        IXdStorageTransaction txReadInit = storage.beginTransaction();
        Collection<XdUniverse> initialUniverses = storage.load(XdUniverse.class, txReadInit);
        String targetUniverseId = initialUniverses.iterator().next().getId();
        txReadInit.commit();

        // 1. Стартуем грязную транзакцию и пытаемся исказить граф (добавляем грязную планету)
        IXdStorageTransaction txDirty = storage.beginTransaction();
        try {
            XdUniverse universe = storage.load(XdUniverse.class, targetUniverseId, txDirty);
            storage.load(universe, txDirty);

            XdGalaxy galaxy = storage.load(XdGalaxy.class, universe.getGalaxies().iterator().next().getId(), txDirty);

            // Создаем грязную планету (StoreAsClassObjects + BTree t=50)
            XdPlanet dirtyPlanet = new XdPlanet();
            dirtyPlanet.setName("Grave_Gaston_99");
            dirtyPlanet.setWaterPercent(0);

            // Имитируем падение: бросаем транзакцию посреди операции вставки
            storage.save(dirtyPlanet, txDirty);

            // Жестко откатываем изменения!
            txDirty.rollback();
        } catch (Throwable t) {
            txDirty.rollback();
        }

        // 2. Стартуем чистую верификационную транзакцию и проверяем, что грязная планета аннигилирована
        IXdStorageTransaction txVerify = storage.beginTransaction();
        Collection<XdPlanet> planets = storage.load(XdPlanet.class, txVerify);

        // По правилам ACID-изоляции (Read Committed / Snapshot), грязная вставка не закоммичена,
        // и коллекция обязана остаться девственно пустой!
        assertTrue(planets.isEmpty(), "Критический баг MVCC роллбэка! Грязный объект просочился в базу данных после отката транзакции вставки!");
        txVerify.commit();
    }
}
