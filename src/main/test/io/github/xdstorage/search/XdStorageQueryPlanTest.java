package io.github.xdstorage.search;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageQueryPlan;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXPLAIN (docs/tasks/2.1.0/25): план-сьют матрицы маршрутизации — план обязан показывать
 * ИМЕННО ТОТ путь, которым движок живет: EQUALS на поисковом индексе → INDEX_SEEK;
 * GT/BETWEEN/IN на упорядоченном пути → INDEX_RANGE_WALK с формой границ;
 * NOT_EQUALS/пустой запрос/неуникальные операторы → SCAN; деградация «range на Hash
 * id-индексе → SCAN» с причиной (контракт FP-1a, сделанный видимым); вторичные критерии —
 * постфильтры; численные сравнения после BUG-E планируются как seek. Плюс тест
 * согласия плана с исполнением: для выборки запросов результаты search() совпадают
 * с семантикой запланированного пути.
 */
@DisplayName("EXPLAIN: план запроса — путь, границы, постфильтры, деградации")
class XdStorageQueryPlanTest {

    @TempDir
    Path tempDir;

    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        final String unique = "explain-plan-" + System.nanoTime();
        storage = XdStorageProvider.newOrGetFileStorage(unique, tempDir.resolve("db").toString(), 512);
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            for (long i = 1; i <= 30; i++) {
                // Hash id-индекс (дефолт без аннотации): range-план деградирует в SCAN (FP-1a)
                storage.save(new RangeOpsEntity("pid-" + i, i, "label-" + (i % 3)), tx);
                // BTree id-индекс: range-план остается ordered walk (sub-linear)
                storage.save(new RangeOpsBTreeIdEntity("bid-" + i, i, "label-" + (i % 3)), tx);
            }
            storage.commitTransaction(tx);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    private XdStorageSearchQuery equalsQuery(final long value) {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, value), null);
    }

    private XdStorageSearchQuery gtQuery(final long value) {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GT, value), null);
    }

    private XdStorageSearchQuery notEqualsQuery(final long value) {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, value), null);
    }

    private XdStorageSearchQuery emptyQuery() {
        return new XdStorageSearchQuery();
    }

    @Test
    @DisplayName("EQUALS на поисковом индексе → INDEX_SEEK")
    void equalsPlansAsIndexSeek() throws Exception {
        final XdStorageQueryPlan plan = storage.explain(RangeOpsEntity.class, "range_idx", equalsQuery(7L));
        assertEquals(XdStorageQueryPlan.Path.INDEX_SEEK, plan.getPath());
        assertNull(plan.getFallbackReason());
        assertNotNull(plan.getSteps());
        assertTrue(plan.getSteps().get(0).contains("INDEX_SEEK"), "шаг = точный поиск");
    }

    @Test
    @DisplayName("Hash id-индекс: range → SCAN с причиной (контракт FP-1a, сделанный видимым)")
    void rangeOnHashIdIndexDegradesToScanWithReason() throws Exception {
        final XdStorageQueryPlan gt = storage.explain(RangeOpsEntity.class, "range_idx", gtQuery(10L));
        assertEquals(XdStorageQueryPlan.Path.SCAN, gt.getPath(), "range на Hash id-индексе — скан");
        assertEquals("Hash", gt.getIdIndexType());
        assertNotNull(gt.getFallbackReason(), "причина деградации заполнена");
        assertTrue(gt.getFallbackReason().contains("Hash"), "причина называет Hash id-индекс");
    }

    @Test
    @DisplayName("GT/BETWEEN/IN на BTree id-индексе → INDEX_RANGE_WALK с формой границ")
    void rangePlansAsOrderedWalkWithBoundShape() throws Exception {
        final XdStorageQueryPlan gt = storage.explain(RangeOpsBTreeIdEntity.class, "range_bt_idx", gtQuery(10L));
        assertEquals(XdStorageQueryPlan.Path.INDEX_RANGE_WALK, gt.getPath());
        assertEquals("BTree", gt.getIdIndexType());
        assertNull(gt.getFallbackReason(), "без деградации на BTree id-индексе");
        assertNotNull(gt.getBoundShape());
        assertTrue(gt.getBoundShape().contains("> 10"), "форма границ: primary > 10");

        final XdStorageSearchQuery between = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new io.github.xdstorage.search.query.XdStorageRange(5L, 15L)), null);
        final XdStorageQueryPlan betweenPlan = storage.explain(RangeOpsBTreeIdEntity.class, "range_bt_idx", between);
        assertEquals(XdStorageQueryPlan.Path.INDEX_RANGE_WALK, betweenPlan.getPath());
        assertTrue(betweenPlan.getBoundShape().contains("BETWEEN"), "форма границ BETWEEN");

        final XdStorageSearchQuery in = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN,
                        java.util.Arrays.asList(5L, 10L, 15L)), null);
        final XdStorageQueryPlan inPlan = storage.explain(RangeOpsBTreeIdEntity.class, "range_bt_idx", in);
        assertEquals(XdStorageQueryPlan.Path.INDEX_RANGE_WALK, inPlan.getPath());
        assertTrue(inPlan.getBoundShape().contains("IN"), "форма границ IN");
    }

    @Test
    @DisplayName("NOT_EQUALS и пустой запрос → SCAN")
    void notEqualsAndEmptyPlanAsScan() throws Exception {
        final XdStorageQueryPlan ne = storage.explain(RangeOpsEntity.class, "range_idx", notEqualsQuery(7L));
        assertEquals(XdStorageQueryPlan.Path.SCAN, ne.getPath());

        final XdStorageQueryPlan empty = storage.explain(RangeOpsEntity.class, "range_idx", emptyQuery());
        assertEquals(XdStorageQueryPlan.Path.SCAN, empty.getPath());
        assertNotNull(empty.getFallbackReason(), "пустой запрос: причина скана заполнена");
    }

    @Test
    @DisplayName("Вторичные критерии попадают в постфильтры плана")
    void secondaryCriteriaListedAsPostFilters() throws Exception {
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, 7L),
                new io.github.xdstorage.search.query.XdStorageCriterion("label",
                        io.github.xdstorage.search.query.IXdStorageCriterionOperator.EQUALS, "label-1"));
        final XdStorageQueryPlan plan = storage.explain(RangeOpsEntity.class, "range_idx", query);
        assertEquals(XdStorageQueryPlan.Path.INDEX_SEEK, plan.getPath());
        assertTrue(!plan.getPostFilters().isEmpty(), "вторичный критерий — постфильтр");
    }

    @Test
    @DisplayName("Численные сравнения после BUG-E планируются как seek (типы восстановлены)")
    void numericSeekPlansAsSeek() throws Exception {
        // числовой id/значение после реопена (BUG-E): тип восстанавливается один раз
        // на дереве; план обязан показать seek
        final XdStorageQueryPlan plan = storage.explain(RangeOpsEntity.class, "range_idx", equalsQuery(12L));
        assertEquals(XdStorageQueryPlan.Path.INDEX_SEEK, plan.getPath());
    }

    @Test
    @DisplayName("Согласие плана с исполнением: для выборки запросов результаты search() "
            + "совпадают с семантикой запланированного пути")
    void planAgreesWithExecution() throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            // INDEX_SEEK: ровно одна сущность с score == 7
            final XdStorageQueryPlan seekPlan = storage.explain(RangeOpsEntity.class, "range_idx", equalsQuery(7L), tx);
            assertEquals(XdStorageQueryPlan.Path.INDEX_SEEK, seekPlan.getPath());
            final Collection<RangeOpsEntity> seekResult = storage.load(RangeOpsEntity.class, "range_idx", equalsQuery(7L), tx);
            assertEquals(1, seekResult.size(), "SEEK: ровно одна запись score == 7");
            assertEquals("pid-7", ((RangeOpsEntity) seekResult.iterator().next()).getId());

            // INDEX_RANGE_WALK: все записи score > 25 (5 штук: 26..30)
            final XdStorageQueryPlan walkPlan = storage.explain(RangeOpsBTreeIdEntity.class, "range_bt_idx", gtQuery(25L), tx);
            assertEquals(XdStorageQueryPlan.Path.INDEX_RANGE_WALK, walkPlan.getPath());
            final Collection<RangeOpsBTreeIdEntity> walkResult =
                    storage.load(RangeOpsBTreeIdEntity.class, "range_bt_idx", gtQuery(25L), tx);
            assertEquals(5, walkResult.size(), "WALK: ровно пять записей score > 25");

            // SCAN: NOT_EQUALS возвращает все, кроме одной
            final XdStorageQueryPlan scanPlan = storage.explain(RangeOpsEntity.class, "range_idx", notEqualsQuery(7L), tx);
            assertEquals(XdStorageQueryPlan.Path.SCAN, scanPlan.getPath());
            final Collection<RangeOpsEntity> scanResult = storage.load(RangeOpsEntity.class, "range_idx", notEqualsQuery(7L), tx);
            assertEquals(29, scanResult.size(), "SCAN: все, кроме одной");
        } finally {
            storage.rollbackTransaction(tx);
        }
    }
}
