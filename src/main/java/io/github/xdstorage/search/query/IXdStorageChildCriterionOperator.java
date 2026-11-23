package io.github.xdstorage.search.query;

import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;

import java.util.Collection;

/** A criterion operator that reaches through a child field: the record's child
 * collection (declared via {@code @XdStorageObjectChildSearchIndex}) is consulted and
 * the operator passes when at least one child satisfies it. */
public interface IXdStorageChildCriterionOperator {

    /**
     * Passes when some child object of the record's child field has a String field
     * exactly equal to the criterion value (per-child equality; a non-String child
     * field value never matches).
     */
    IXdStorageChildCriterionOperator CONTAINS = new IXdStorageChildCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String childFieldName, final Class<?> cl,
                final String fieldName, final Object value) {
            boolean result = false;

            final Collection<Object> indexValue = record.getChildFieldValue(childFieldName, cl, fieldName);
            for (final Object objectValue : indexValue) {
                if (objectValue == null || !(objectValue instanceof String)) {
                    result = false;
                } else if (value == null || !(value instanceof String)) {
                    result = false;
                } else {
                    result = ((String) objectValue).equals((String) value);
                }
                if (result) {
                    break;
                }
            }
            return result;
        }
    };

    /**
     * Evaluates the operator against the record's child collection.
     *
     * @param record         the index record being filtered
     * @param childFieldName the parent's child-collection field to look through
     * @param cl             the child class
     * @param fieldName      the child's field to compare
     * @param value          the criterion value
     * @return {@code true} when at least one child satisfies the operator
     */
    boolean passed(XdStorageSearchIndexRecord record, final String childFieldName, Class<?> cl, String fieldName, Object
            value);
}
