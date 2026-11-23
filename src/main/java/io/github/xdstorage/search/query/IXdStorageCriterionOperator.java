package io.github.xdstorage.search.query;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;

import java.util.Collection;

/** The comparison operators of the search-query criteria (FP-1a/FP-1b semantics,
 * pinned by the range/shaping suites).
 *
 * <p>Range-operator contract ({@code GT}, {@code GTE}, {@code LT}, {@code LTE},
 * {@code BETWEEN}, {@code IN}):
 * <ul>
 *   <li>{@code null} field values NEVER satisfy a range operator — {@code IS_NULL} /
 *       {@code IS_NOT_NULL} are the null tests;</li>
 *   <li>comparisons are per field type: numbers numerically, strings lexicographically,
 *       otherwise the {@code Comparable} contract;</li>
 *   <li>a comparable-type mismatch or a {@code null} criterion bound fails LOUDLY
 *       ({@code XdStorageRuntimeException}) — never a silently-empty result;</li>
 *   <li>on the ordered search-index tree the range family evaluates as a bounded ordered
 *       walk (early termination); on non-ordered surfaces it degrades to the scan with
 *       identical result semantics.</li>
 * </ul> */
public interface IXdStorageCriterionOperator {

    /** Passes when the indexed field value is {@code null}. The criterion value is ignored. */
    IXdStorageCriterionOperator IS_NULL = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return record.getFieldValue(fieldName) == null;
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            return objectValue == null;
        }
    };

    /** Passes when the indexed field value is not {@code null}. The criterion value is ignored. */
    IXdStorageCriterionOperator IS_NOT_NULL = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return record.getFieldValue(fieldName) != null;
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            return objectValue != null;
        }
    };

    /** Equality ({@code equals} semantics; {@code null} matches only {@code null}). */
    IXdStorageCriterionOperator EQUALS = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            boolean result;

            if (objectValue == null) {
                if (criterionValue == null) {
                    result = true;
                } else {
                    result = false;
                }
            } else {
                result = objectValue.equals(criterionValue);
            }

            return result;
        }
    };

    /** Negated equality: passes when the values differ, including a non-null field against a {@code null} criterion. */
    IXdStorageCriterionOperator NOT_EQUALS = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            boolean result;

            if (objectValue == null) {
                if (criterionValue == null) {
                    result = false;
                } else {
                    result = true;
                }
            } else {
                result = !objectValue.equals(criterionValue);
            }

            return result;
        }
    };

    /** Substring test for String fields: passes when the field value contains the criterion String (both must be
     * Strings). */
    IXdStorageCriterionOperator CONTAINS = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            boolean result;

            if (objectValue == null || !(objectValue instanceof String)) {
                result = false;
            } else if (criterionValue == null || !(criterionValue instanceof String)) {
                result = false;
            } else {
                result = ((String) objectValue).contains((String) criterionValue);
            }

            return result;
        }
    };

    // =========================================================================
    // FP-1a (docs/tasks/2.1.0/09): range operators. Comparison contract:
    //   - nulls NEVER satisfy a range operator (IS_NULL / IS_NOT_NULL are the null tests);
    //   - comparisons are per-field-type (numbers numerically, strings lexicographically,
    //     Comparable contract otherwise);
    //   - a comparable-type mismatch fails LOUDLY, never silently-empty;
    //   - a null/absent criterion bound fails loudly too — it is a query bug, not a filter.
    // On the ordered search-index tree GT/GTE/LT/LTE/BETWEEN evaluate as a bounded ordered
    // walk (early termination at the upper bound) and IN as one membership-filtered walk;
    // on any non-ordered surface they degrade to the scan with identical result semantics.
    // =========================================================================

    /** Strictly greater than the bound; {@code null} never passes; mixed types fail loudly. */
    IXdStorageCriterionOperator GT = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            return objectValue != null && compareForRange(objectValue, criterionValue, "GT") > 0;
        }
    };

    /** Greater than or equal to the bound; {@code null} never passes; mixed types fail loudly. */
    IXdStorageCriterionOperator GTE = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            return objectValue != null && compareForRange(objectValue, criterionValue, "GTE") >= 0;
        }
    };

    /** Strictly less than the bound; {@code null} never passes; mixed types fail loudly. */
    IXdStorageCriterionOperator LT = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            return objectValue != null && compareForRange(objectValue, criterionValue, "LT") < 0;
        }
    };

    /** Less than or equal to the bound; {@code null} never passes; mixed types fail loudly. */
    IXdStorageCriterionOperator LTE = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            return objectValue != null && compareForRange(objectValue, criterionValue, "LTE") <= 0;
        }
    };

    /** Inside the inclusive {@code [from, to]} interval — the criterion value must be an
     * {@link XdStorageRange} (anything else fails loudly); {@code null} never passes. */
    IXdStorageCriterionOperator BETWEEN = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            if (!(criterionValue instanceof XdStorageRange)) {
                throw new XdStorageRuntimeException("BETWEEN requires an " + XdStorageRange.class.getSimpleName()
                        + " (from, to) criterion value, got "
                        + (criterionValue == null ? "null" : criterionValue.getClass().getName()));
            }
            if (objectValue == null) {
                return false;
            }
            final XdStorageRange range = (XdStorageRange) criterionValue;
            // both bounds INCLUSIVE (pinned contract, docs/tasks/2.1.0/09)
            return compareForRange(objectValue, range.getFrom(), "BETWEEN lower") >= 0
                    && compareForRange(objectValue, range.getTo(), "BETWEEN upper") <= 0;
        }
    };

    /** Equals-membership against a Collection or array criterion value (anything else
     * fails loudly); {@code null} field values never pass. */
    IXdStorageCriterionOperator IN = new IXdStorageCriterionOperator() {
        @Override
        public boolean passed(final XdStorageSearchIndexRecord record, final String fieldName, final Object value) {
            return passed(record.getFieldValue(fieldName), value);
        }

        @Override
        public boolean passed(final Object objectValue, final Object criterionValue) {
            // nulls never satisfy the range-operator family (IN included)
            if (objectValue == null) {
                return false;
            }
            for (final Object candidate : valuesOf(criterionValue)) {
                if (objectValue.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    };

    /**
     * Comparison core of the range family: the caller guarantees a non-null
     * {@code objectValue} (null never satisfies — the operators short-circuit).
     *
     * @param objectValue the indexed field value (non-null)
     * @param bound       the criterion bound (must be non-null and comparable with the value)
     * @param operator    the operator name, for the failure message
     * @return a negative/zero/positive integer as {@code compareTo} answers
     * @throws XdStorageRuntimeException when the bound is {@code null}, the value is not
     *         {@code Comparable}, or the two types are not comparable with each other
     */
    static int compareForRange(final Object objectValue, final Object bound, final String operator) {
        if (bound == null) {
            throw new XdStorageRuntimeException("range operator " + operator
                    + " requires a non-null criterion bound — IS_NULL / IS_NOT_NULL are the null tests");
        }
        if (!(objectValue instanceof Comparable)) {
            throw new XdStorageRuntimeException("range operator " + operator + ": indexed value of type "
                    + objectValue.getClass().getName() + " is not Comparable — mixed-type comparison fails loudly");
        }
        try {
            return ((Comparable) objectValue).compareTo(bound);
        } catch (final ClassCastException e) {
            throw new XdStorageRuntimeException("range operator " + operator + ": indexed value of type "
                    + objectValue.getClass().getName() + " is not comparable with the criterion bound of type "
                    + bound.getClass().getName() + " — mixed-type comparison fails loudly", e);
        }
    }

    /**
     * Normalizes the {@code IN} criterion value to an iterable of candidates.
     *
     * @param criterionValue a {@link java.util.Collection} or an array
     * @return the candidates to test membership against
     * @throws XdStorageRuntimeException for any other type (and for {@code null})
     */
    static Iterable<?> valuesOf(final Object criterionValue) {
        if (criterionValue instanceof Collection) {
            return (Collection<?>) criterionValue;
        }
        if (criterionValue instanceof Object[]) {
            return java.util.Arrays.asList((Object[]) criterionValue);
        }
        throw new XdStorageRuntimeException("IN requires a Collection or an array criterion value, got "
                + (criterionValue == null ? "null" : criterionValue.getClass().getName()));
    }

    /**
     * Evaluates the operator against a record's indexed field.
     *
     * @param record    the index record being filtered
     * @param fieldName the indexed field to read
     * @param value     the criterion value (semantics per operator; ignored by the null tests)
     * @return {@code true} when the record passes
     */
    boolean passed(XdStorageSearchIndexRecord record, String fieldName, Object value);

    /**
     * Evaluates the operator against a raw value pair (the in-memory comparison form,
     * used for secondary criteria and for checks outside the index tree).
     *
     * @param objectValue    the indexed value (a {@code null} field value)
     * @param criterionValue the criterion value
     * @return {@code true} when the pair satisfies the operator
     */
    boolean passed(Object objectValue, Object criterionValue);
}
