package io.github.xdstorage.search.query;

import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XdStorageChildCriterion / IXdStorageChildCriterionOperator: критерии по дочерним объектам")
public class XdStorageChildCriterionTest {

    private static final Class<?> CHILD_CLASS = String.class;

    private static XdStorageSearchIndexRecord recordWithChild(final String childFieldName, final Object childId,
                                                              final String fieldName, final Object value) {
        final XdStorageSearchIndexRecord record = new XdStorageSearchIndexRecord();
        record.addChildFieldValue(childFieldName, CHILD_CLASS, childId, fieldName, value);
        return record;
    }

    @Test
    @DisplayName("Хранит все параметры критерия")
    public void testExposesFields() {
        final XdStorageChildCriterion criterion =
                new XdStorageChildCriterion("crew", CHILD_CLASS, "rank", IXdStorageChildCriterionOperator.CONTAINS,
                        "Captain");

        assertEquals("crew", criterion.childFieldName);
        assertSame(CHILD_CLASS, criterion.cl);
        assertEquals("rank", criterion.fieldName);
        assertSame(IXdStorageChildCriterionOperator.CONTAINS, criterion.operator);
        assertEquals("Captain", criterion.value);
    }

    @Test
    @DisplayName("passed делегирует оператору")
    public void testPassedDelegates() {
        final XdStorageChildCriterion criterion =
                new XdStorageChildCriterion("crew", CHILD_CLASS, "rank", IXdStorageChildCriterionOperator.CONTAINS,
                        "Captain");

        assertTrue(criterion.passed(recordWithChild("crew", 1L, "rank", "Captain")));
        assertFalse(criterion.passed(recordWithChild("crew", 1L, "rank", "Doctor")));
    }

    @Test
    @DisplayName("CONTAINS (несмотря на имя) требует ТОЧНОГО совпадения строки")
    public void testContainsOperatorIsExactEquals() {
        final XdStorageSearchIndexRecord record = recordWithChild("crew", 1L, "rank", "Captain");

        assertTrue(IXdStorageChildCriterionOperator.CONTAINS.passed(record, "crew", CHILD_CLASS, "rank", "Captain"));
        // подстрока не проходит — оператор сравнивает на равенство
        assertFalse(IXdStorageChildCriterionOperator.CONTAINS.passed(record, "crew", CHILD_CLASS, "rank", "Capt"));
        assertFalse(IXdStorageChildCriterionOperator.CONTAINS.passed(record, "crew", CHILD_CLASS, "rank", "captain"));
        assertFalse(IXdStorageChildCriterionOperator.CONTAINS.passed(record, "crew", CHILD_CLASS, "rank", null));
    }

    @Test
    @DisplayName("CONTAINS: сопоставление по всем дочерним объектам поля")
    public void testContainsScansAllChildObjects() {
        final XdStorageSearchIndexRecord record = new XdStorageSearchIndexRecord();
        record.addChildFieldValue("crew", CHILD_CLASS, 1L, "rank", "Pilot");
        record.addChildFieldValue("crew", CHILD_CLASS, 2L, "rank", "Doctor");

        assertTrue(IXdStorageChildCriterionOperator.CONTAINS.passed(record, "crew", CHILD_CLASS, "rank", "Doctor"));
        assertFalse(IXdStorageChildCriterionOperator.CONTAINS.passed(record, "crew", CHILD_CLASS, "rank", "Engineer"));
    }

    @Test
    @DisplayName("CONTAINS: отсутствие дочерних значений или нестроковые значения дают false")
    public void testContainsMissingOrNonStringValues() {
        // ни одного дочернего объекта
        assertFalse(IXdStorageChildCriterionOperator.CONTAINS.passed(
                new XdStorageSearchIndexRecord(), "crew", CHILD_CLASS, "rank", "Captain"));

        // дочернее значение null
        final XdStorageSearchIndexRecord nullValue = recordWithChild("crew", 1L, "rank", null);
        assertFalse(IXdStorageChildCriterionOperator.CONTAINS.passed(nullValue, "crew", CHILD_CLASS, "rank",
                "Captain"));

        // нестроковое значение потомка
        final XdStorageSearchIndexRecord numeric = recordWithChild("crew", 1L, "rank", 42);
        assertFalse(IXdStorageChildCriterionOperator.CONTAINS.passed(numeric, "crew", CHILD_CLASS, "rank", "42"));
    }
}
