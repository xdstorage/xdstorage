package io.github.xdstorage.search.query;

import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XdStorageCriterion / XdStorageCriterionNot: обертки над операторами")
public class XdStorageCriterionTest {

    private static XdStorageSearchIndexRecord recordWithName(final String value) {
        final XdStorageSearchIndexRecord record = new XdStorageSearchIndexRecord();
        record.setFieldValue("name", value);
        return record;
    }

    @Test
    @DisplayName("Хранит имя поля, оператор и значение")
    public void testExposesFields() {
        final XdStorageCriterion criterion = new XdStorageCriterion("name", IXdStorageCriterionOperator.EQUALS,
                "alpha");

        assertEquals("name", criterion.fieldName);
        assertSame(IXdStorageCriterionOperator.EQUALS, criterion.operator);
        assertEquals("alpha", criterion.value);
    }

    @Test
    @DisplayName("passed делегирует оператору с именем поля и значением критерия")
    public void testPassedDelegatesToOperator() {
        final XdStorageCriterion equals = new XdStorageCriterion("name", IXdStorageCriterionOperator.EQUALS, "alpha");

        assertTrue(equals.passed(recordWithName("alpha")));
        assertFalse(equals.passed(recordWithName("beta")));
        assertFalse(equals.passed(new XdStorageSearchIndexRecord()));
    }

    @Test
    @DisplayName("Конструктор без значения: критерии IS_NULL / IS_NOT_NULL")
    public void testNullaryConstructors() {
        final XdStorageCriterion isNull = new XdStorageCriterion("name", IXdStorageCriterionOperator.IS_NULL);
        final XdStorageCriterion isNotNull = new XdStorageCriterion("name", IXdStorageCriterionOperator.IS_NOT_NULL);

        assertNull(isNull.value);

        assertTrue(isNull.passed(new XdStorageSearchIndexRecord()));
        assertFalse(isNull.passed(recordWithName("alpha")));

        assertFalse(isNotNull.passed(new XdStorageSearchIndexRecord()));
        assertTrue(isNotNull.passed(recordWithName("alpha")));
    }

    @Test
    @DisplayName("XdStorageCriterionNot инвертирует вложенный критерий")
    public void testNotInverts() {
        final XdStorageCriterion equals = new XdStorageCriterion("name", IXdStorageCriterionOperator.EQUALS, "alpha");
        final XdStorageCriterionNot not = new XdStorageCriterionNot(equals);

        assertFalse(not.passed(recordWithName("alpha")));
        assertTrue(not.passed(recordWithName("beta")));
        assertTrue(not.passed(new XdStorageSearchIndexRecord()));
    }

    @Test
    @DisplayName("Двойное отрицание возвращает исходный результат")
    public void testDoubleNegation() {
        final XdStorageCriterion equals = new XdStorageCriterion("name", IXdStorageCriterionOperator.EQUALS, "alpha");
        final XdStorageCriterionNot notNot = new XdStorageCriterionNot(new XdStorageCriterionNot(equals));

        assertTrue(notNot.passed(recordWithName("alpha")));
        assertFalse(notNot.passed(recordWithName("beta")));
    }
}
