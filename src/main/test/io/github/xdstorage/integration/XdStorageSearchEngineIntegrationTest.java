package io.github.xdstorage.integration;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.search.SearchEngineCrewMember;
import io.github.xdstorage.search.SearchEngineProbe;
import io.github.xdstorage.search.SearchEngineShip;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.IXdStorageChildCriterionOperator;
import io.github.xdstorage.search.query.XdStorageChildCriterion;
import io.github.xdstorage.search.query.XdStorageCriterion;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционное тестирование поискового движка на реальном файловом хранилище")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
public class XdStorageSearchEngineIntegrationTest {

    private static final String STORAGE_NAME = "search-e2e-engine";

    private static IXdFileStorage storage;

    @BeforeAll
    public static void initRealStorage() {
        // R-50: имя и каталог уникальны на каждый прогон. Фиксированные значения ловят
        // двойную загрязненность: (1) реестр провайдера возвращает СТАРЫЙ инстанс по имени,
        // пока не вызван shutdown; (2) File.delete() может отказать на файлах, удерживаемых
        // внешними процессами (демоны IDE) - устаревший диск силком подмешивается в кэши
        // ("concurrent modification" на первой же вставке). Уникальный каталог делает тест
        // независимым от поведения файловой системы; устаревшие каталоги убирает mvn clean.
        final String unique = Long.toString(System.nanoTime(), Character.MAX_RADIX);
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME + "-" + unique,
                "./target/search_e2e_db_" + unique, 250);
    }

    @AfterAll
    public static void destroyRealStorage() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    private static IXdStorageTransaction beginTx() throws XdStorageException {
        return storage.beginTransaction(100L);
    }

    private static SearchEngineShip ship(final long id, final String name, final int tonnage) {
        final SearchEngineShip ship = new SearchEngineShip();
        ship.setId(id);
        ship.setName(name);
        ship.setTonnage(tonnage);
        return ship;
    }

    private static Collection<SearchEngineShip> searchShips(final IXdStorageTransaction tx,
                                                                         final XdStorageSearchQuery query) throws Exception {
        return storage.load(SearchEngineShip.class, "ship_idx", query, tx);
    }

    private static XdStorageSearchQuery equalsName(final Object name) {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, name), null);
    }

    private static long countAllShips(final IXdStorageTransaction tx) throws Exception {
        // сканирующий путь: primary NOT_EQUALS значения, которого нет ни у одной записи
        return searchShips(tx, new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "@@none@@"), null)).size();
    }

    // =========================================================================
    // Этап 1: пустое хранилище
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Поиск по пустому хранилищу возвращает пустую коллекцию; неизвестный индекс - исключение")
    public void test01_EmptyStorageAndUnknownIndex() throws Exception {
        final IXdStorageTransaction tx = beginTx();
        try {
            assertTrue(searchShips(tx, equalsName("Alpha")).isEmpty());
            assertThrows(XdStorageException.class, () ->
                    storage.load(SearchEngineShip.class, "missing_idx", equalsName("Alpha"), tx));
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 2: сохранение и поиск по EQUALS
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("Сохранение кораблей и поиск по точному совпадению первичного значения")
    public void test02_SaveAndSearchByEquals() throws Exception {
        final SearchEngineShip alpha = ship(1L, "Alpha", 100);
        alpha.setCaptain(new SearchEngineCrewMember(10L, "Captain"));
        alpha.addCrewMember(new SearchEngineCrewMember(11L, "Pilot"));
        final SearchEngineShip beta = ship(2L, "Beta", 200);
        final SearchEngineShip gamma = ship(3L, "Gamma", 300);

        final IXdStorageTransaction tx = beginTx();
        try {
            storage.save(alpha, tx);
            storage.save(beta, tx);
            storage.save(gamma, tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }

        final IXdStorageTransaction searchTx = beginTx();
        try {
            final Collection<SearchEngineShip> found = searchShips(searchTx, equalsName("Beta"));
            assertEquals(1, found.size());
            final SearchEngineShip loaded = found.iterator().next();
            assertEquals(2L, loaded.getId());
            assertEquals("Beta", loaded.getName());
            assertEquals(200, loaded.getTonnage());
            searchTx.commit();
        } catch (final Throwable t) {
            searchTx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 3: дочерние критерии
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("Поиск по критерию дочернего объекта (экипаж и капитан)")
    public void test03_ChildCriterion() throws Exception {
        final IXdStorageTransaction tx = beginTx();
        try {
            // primary EQUALS Alpha + дочерний критерий как дополнительный критерий той же пары
            final XdStorageSearchQuery singlePair = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Alpha"),
                    new XdStorageChildCriterion("crew", SearchEngineCrewMember.class,
                            "rank", IXdStorageChildCriterionOperator.CONTAINS, "Pilot"));

            final Collection<SearchEngineShip> found = searchShips(tx, singlePair);
            assertEquals(1, found.size());
            assertEquals("Alpha", found.iterator().next().getName());

            // экипаж без такого члена - корабль не отбирается
            final XdStorageSearchQuery noEngineer = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Alpha"),
                    new XdStorageChildCriterion("crew", SearchEngineCrewMember.class,
                            "rank", IXdStorageChildCriterionOperator.CONTAINS, "Engineer"));
            assertTrue(searchShips(tx, noEngineer).isEmpty());

            // одиночный дочерний объект (капитан)
            final XdStorageSearchQuery byCaptain = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Alpha"),
                    new XdStorageChildCriterion("captain", SearchEngineCrewMember.class,
                            "rank", IXdStorageChildCriterionOperator.CONTAINS, "Captain"));
            assertEquals(1, searchShips(tx, byCaptain).size());
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 4: OR-запросы и CONTAINS
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("OR-объединение нескольких EQUALS и оператор CONTAINS (регистр значим)")
    public void test04_OrAndContains() throws Exception {
        final IXdStorageTransaction tx = beginTx();
        try {
            final XdStorageSearchQuery orQuery = equalsName("Alpha");
            orQuery.or(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Gamma"), null);
            final Collection<SearchEngineShip> found = searchShips(tx, orQuery);
            assertEquals(2, found.size());
            assertTrue(found.stream().anyMatch(s -> "Alpha".equals(s.getName())));
            assertTrue(found.stream().anyMatch(s -> "Gamma".equals(s.getName())));

            final XdStorageSearchQuery contains = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Gamma"),
                    new XdStorageCriterion("name", IXdStorageCriterionOperator.CONTAINS, "amm"));
            assertEquals(1, searchShips(tx, contains).size());

            final XdStorageSearchQuery wrongCase = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Gamma"),
                    new XdStorageCriterion("name", IXdStorageCriterionOperator.CONTAINS, "AMM"));
            assertTrue(searchShips(tx, wrongCase).isEmpty());
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 5: сканирующие пути (NOT_EQUALS и критерии без primary)
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Сканирующий путь: NOT_EQUALS по primary и критерий поля без primary")
    public void test05_ScanPaths() throws Exception {
        final IXdStorageTransaction tx = beginTx();
        try {
            final Collection<SearchEngineShip> notAlpha = searchShips(tx, new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "Alpha"), null));
            assertEquals(2, notAlpha.size());
            assertTrue(notAlpha.stream().noneMatch(s -> "Alpha".equals(s.getName())));

            // без primary: критерий по вторичному полю tonnage
            final Collection<SearchEngineShip> byTonnage = searchShips(tx, new XdStorageSearchQuery(null,
                    new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.EQUALS, 200)));
            assertEquals(1, byTonnage.size());
            assertEquals("Beta", byTonnage.iterator().next().getName());
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 6: переименование (изменение первичного значения)
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("update со сменой первичного значения: ключ поиска переносится, дублей нет")
    public void test06_UpdateWithPrimaryValueChange() throws Exception {
        final SearchEngineShip renamed = ship(2L, "Beta-2", 200);

        final IXdStorageTransaction tx = beginTx();
        try {
            storage.update(renamed, tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }

        final IXdStorageTransaction checkTx = beginTx();
        try {
            assertTrue(searchShips(checkTx, equalsName("Beta")).isEmpty(), "старое имя не должно находиться");
            final Collection<SearchEngineShip> found = searchShips(checkTx, equalsName("Beta-2"));
            assertEquals(1, found.size());
            assertEquals(200, found.iterator().next().getTonnage());
            assertEquals(3, countAllShips(checkTx), "запись перенесена, а не продублирована");
            checkTx.commit();
        } catch (final Throwable t) {
            checkTx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 7: update без смены первичного значения
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("update с тем же первичным значением: поля записи обновляются на месте")
    public void test07_UpdateSamePrimaryValue() throws Exception {
        final SearchEngineShip retuned = ship(3L, "Gamma", 301);

        final IXdStorageTransaction tx = beginTx();
        try {
            storage.update(retuned, tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }

        final IXdStorageTransaction checkTx = beginTx();
        try {
            final XdStorageSearchQuery byNewTonnage = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Gamma"),
                    new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.EQUALS, 301));
            assertEquals(1, searchShips(checkTx, byNewTonnage).size());
            checkTx.commit();
        } catch (final Throwable t) {
            checkTx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 8: контракт вставки по существующему идентификатору
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("Повторный save уже сохраненного ид в новой транзакции отвергается (save - это insert)")
    public void test08_ResaveOfExistingIdIsRefused() throws Exception {
        final SearchEngineShip alphaAgain = ship(1L, "Alpha", 100);

        final IXdStorageTransaction tx = beginTx();
        try {
            // движок отказывает в повторной вставке существующего ид: save() - это insert,
            // обновление ранее сохраненного объекта выполняется методом update()
            assertThrows(XdStorageException.class, () -> storage.save(alphaAgain, tx));
            tx.rollback();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }

        // отказ вставки не оставил мусора в поисковых деревьях
        final IXdStorageTransaction checkTx = beginTx();
        try {
            assertEquals(1, searchShips(checkTx, equalsName("Alpha")).size());
            assertEquals(3, countAllShips(checkTx));
            checkTx.commit();
        } catch (final Throwable t) {
            checkTx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 9: удаление из индекса
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("delete убирает запись из обоих деревьев; новое сохранение работает после удаления")
    public void test09_DeleteRemovesFromIndex() throws Exception {
        final SearchEngineShip obsolete = ship(2L, "Beta-2", 200);

        final IXdStorageTransaction tx = beginTx();
        try {
            storage.delete(obsolete, tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }

        final IXdStorageTransaction checkTx = beginTx();
        try {
            assertTrue(searchShips(checkTx, equalsName("Beta-2")).isEmpty());
            assertEquals(2, countAllShips(checkTx));
            checkTx.commit();
        } catch (final Throwable t) {
            checkTx.rollback();
            throw t;
        }

        // после удаления объект с тем же именем индексируется заново
        final SearchEngineShip fresh = ship(4L, "Delta", 400);
        final IXdStorageTransaction resaveTx = beginTx();
        try {
            storage.save(fresh, resaveTx);
            resaveTx.commit();
        } catch (final Throwable t) {
            resaveTx.rollback();
            throw t;
        }

        final IXdStorageTransaction finalTx = beginTx();
        try {
            assertEquals(1, searchShips(finalTx, equalsName("Delta")).size());
            finalTx.commit();
        } catch (final Throwable t) {
            finalTx.rollback();
            throw t;
        }
    }

    // =========================================================================
    // Этап 10: политика StoreAsSingleObject
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("StoreAsSingleObject: сохранение, поиск, переименование и обновление полей")
    public void test10_SingleObjectPolicyLifecycle() throws Exception {
        final IXdStorageTransaction tx = beginTx();
        try {
            storage.save(new SearchEngineProbe(100L, "V1", 50), tx);
            storage.save(new SearchEngineProbe(101L, "V2", 80), tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }

        final IXdStorageTransaction searchTx = beginTx();
        try {
            final Collection<SearchEngineProbe> found = storage.load(
                    SearchEngineProbe.class, "probe_idx", equalsName("V1"), searchTx);
            assertEquals(1, found.size());
            assertEquals(50, found.iterator().next().getFuel());
            searchTx.commit();
        } catch (final Throwable t) {
            searchTx.rollback();
            throw t;
        }

        // переименование (перенос ключа) на политике одиночного объекта
        final SearchEngineProbe renamed = new SearchEngineProbe(100L, "W1", 50);
        final IXdStorageTransaction renameTx = beginTx();
        try {
            storage.update(renamed, renameTx);
            renameTx.commit();
        } catch (final Throwable t) {
            renameTx.rollback();
            throw t;
        }

        final IXdStorageTransaction verifyTx = beginTx();
        try {
            assertTrue(storage.load(SearchEngineProbe.class, "probe_idx", equalsName("V1"), verifyTx).isEmpty());
            assertEquals(1, storage.load(SearchEngineProbe.class, "probe_idx", equalsName("W1"), verifyTx).size());
            verifyTx.commit();
        } catch (final Throwable t) {
            verifyTx.rollback();
            throw t;
        }

        // обновление поля без смены ключа
        final SearchEngineProbe refueled = new SearchEngineProbe(100L, "W1", 55);
        final IXdStorageTransaction fuelTx = beginTx();
        try {
            storage.update(refueled, fuelTx);
            fuelTx.commit();
        } catch (final Throwable t) {
            fuelTx.rollback();
            throw t;
        }

        final IXdStorageTransaction finalTx = beginTx();
        try {
            final XdStorageSearchQuery byFuel = new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "W1"),
                    new XdStorageCriterion("fuel", IXdStorageCriterionOperator.EQUALS, 55));
            final Collection<SearchEngineProbe> found = storage.load(
                    SearchEngineProbe.class, "probe_idx", byFuel, finalTx);
            assertEquals(1, found.size());
            assertEquals(55, found.iterator().next().getFuel());
            finalTx.commit();
        } catch (final Throwable t) {
            finalTx.rollback();
            throw t;
        }
    }
}
