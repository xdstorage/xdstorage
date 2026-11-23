package io.github.xdstorage.search.query;

import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;

/** A criterion evaluated through a child-class index: the record's child collection
 * (joined via {@code @XdStorageObjectChildSearchIndex}) must contain at least one child
 * for which the operator passes. */
public class XdStorageChildCriterion implements IXdStorageCriterion {

    /** The parent's child-collection field the criterion reaches through. */
    public final String childFieldName;

    /** The child class declaring the joined index. */
    public final Class<?> cl;

    /** The child's indexed field to compare. */
    public final String fieldName;

    /** The child comparison operator (e.g. {@link IXdStorageChildCriterionOperator#CONTAINS}). */
    public final IXdStorageChildCriterionOperator operator;

    /** The criterion value (may be {@code null}). */
    public final Object value;

    /**
     * A valueless child criterion.
     *
     * @param childFieldName the parent's child-collection field
     * @param cl             the child class
     * @param fieldName      the child's field to compare
     * @param operator       the child operator
     */
    public XdStorageChildCriterion(final String childFieldName, final Class<?> cl, final String fieldName, final
            IXdStorageChildCriterionOperator operator) {
        this(childFieldName, cl, fieldName, operator, null);
    }

    /**
     * A child criterion with a value.
     *
     * @param childFieldName the parent's child-collection field
     * @param cl             the child class
     * @param fieldName      the child's field to compare
     * @param operator       the child operator
     * @param value          the criterion value
     */
    public XdStorageChildCriterion(final String childFieldName, final Class<?> cl, final String fieldName, final
            IXdStorageChildCriterionOperator operator, final Object value) {
        this.childFieldName = childFieldName;
        this.cl = cl;
        this.fieldName = fieldName;
        this.operator = operator;
        this.value = value;
    }

    /** Delegates to the child operator with this criterion's names and value. */
    @Override
    public boolean passed(final XdStorageSearchIndexRecord record) {
        return operator.passed(record, childFieldName, cl, fieldName, value);
    }
}
