package io.github.xdstorage.search.query;

import io.github.xdstorage.utils.XdStoragePair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XdStorageSearchQuery: накопление критериев запроса")
public class XdStorageSearchQueryTest {

    @Test
    @DisplayName("Новый запрос не содержит критериев")
    public void testNewQueryIsEmpty() {
        final XdStorageSearchQuery query = new XdStorageSearchQuery();

        assertTrue(query.getCriterions().isEmpty());
    }

    @Test
    @DisplayName("Конструктор с парой критериев добавляет единственную пару")
    public void testConstructorAddsSinglePair() {
        final XdStoragePrimaryCriterion primary = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS,
                "alpha");
        final XdStorageCriterion criterion = new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.EQUALS, 100);

        final XdStorageSearchQuery query = new XdStorageSearchQuery(primary, criterion);

        final List<XdStoragePair<IXdStoragePrimaryCriterion, IXdStorageCriterion>> criterions = query.getCriterions();
        assertEquals(1, criterions.size());
        assertSame(primary, criterions.get(0).a);
        assertSame(criterion, criterions.get(0).b);
    }

    @Test
    @DisplayName("or() накапливает пары в порядке добавления")
    public void testOrAccumulatesPairsInOrder() {
        final XdStoragePrimaryCriterion first = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS,
                "alpha");
        final XdStoragePrimaryCriterion second = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS,
                "beta");
        final XdStoragePrimaryCriterion third = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS,
                "gamma");

        final XdStorageSearchQuery query = new XdStorageSearchQuery(first, null);
        query.or(second, null);
        query.or(third, new XdStorageCriterion("name", IXdStorageCriterionOperator.IS_NOT_NULL));

        final List<XdStoragePair<IXdStoragePrimaryCriterion, IXdStorageCriterion>> criterions = query.getCriterions();
        assertEquals(3, criterions.size());
        assertSame(first, criterions.get(0).a);
        assertSame(second, criterions.get(1).a);
        assertSame(third, criterions.get(2).a);
        assertNull(criterions.get(0).b);
        assertNotNull(criterions.get(2).b);
    }
}
