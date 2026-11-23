package io.github.xdstorage.search.query;

import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XdStoragePrimaryCriterion: критерий по первичному индексному значению")
public class XdStoragePrimaryCriterionTest {

    private static XdStorageSearchIndexRecord recordWithPrimary(final Object primaryValue) {
        final XdStorageSearchIndexRecord record = new XdStorageSearchIndexRecord();
        record.setPrimaryIndexValue(primaryValue);
        return record;
    }

    @Test
    @DisplayName("Хранит оператор и значение")
    public void testExposesOperatorAndValue() {
        final XdStoragePrimaryCriterion criterion = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS,
                "alpha");

        assertSame(IXdStorageCriterionOperator.EQUALS, criterion.getOperator());
        assertEquals("alpha", criterion.getValue());
    }

    @Test
    @DisplayName("passed применяется к primaryIndexValue записи")
    public void testPassedAgainstPrimaryValue() {
        final XdStoragePrimaryCriterion equals = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS,
                "alpha");

        assertTrue(equals.passed(recordWithPrimary("alpha")));
        assertFalse(equals.passed(recordWithPrimary("beta")));
        assertFalse(equals.passed(recordWithPrimary(null)));
    }

    @Test
    @DisplayName("EQUALS с null-значением отбирает записи с null-первичным значением")
    public void testEqualsNullPrimary() {
        final XdStoragePrimaryCriterion equalsNull = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS,
                null);

        assertTrue(equalsNull.passed(recordWithPrimary(null)));
        assertFalse(equalsNull.passed(recordWithPrimary("alpha")));
    }

    @Test
    @DisplayName("NOT_EQUALS / IS_NOT_NULL работают по primaryIndexValue")
    public void testOtherOperators() {
        final XdStoragePrimaryCriterion notEquals = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator
                .NOT_EQUALS, "alpha");
        final XdStoragePrimaryCriterion isNotNull = new XdStoragePrimaryCriterion(IXdStorageCriterionOperator
                .IS_NOT_NULL, null);

        assertTrue(notEquals.passed(recordWithPrimary("beta")));
        assertFalse(notEquals.passed(recordWithPrimary("alpha")));

        assertTrue(isNotNull.passed(recordWithPrimary("beta")));
        assertFalse(isNotNull.passed(recordWithPrimary(null)));
    }
}
