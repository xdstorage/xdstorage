package io.github.xdstorage.search.query;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;

/**
 * FP-1a (docs/tasks/2.1.0/09): criterion value of the {@code BETWEEN} operator —
 * the inclusive {@code [from, to]} interval. Both bounds INCLUSIVE (pinned contract);
 * a degenerate range with {@code from > to} matches nothing. Bounds are compared with
 * the indexed value per its field type (numbers numerically, strings lexicographically,
 * Comparable contract otherwise); a type mismatch fails loudly.
 */
public final class XdStorageRange {

    private final Object from;

    private final Object to;

    /**
     * Builds the inclusive interval.
     *
     * @param from the inclusive lower bound (never {@code null})
     * @param to   the inclusive upper bound (never {@code null})
     * @throws XdStorageRuntimeException when either bound is {@code null} — the
     *         IS_NULL/IS_NOT_NULL operators are the null tests
     */
    public XdStorageRange(final Object from, final Object to) {
        if (from == null || to == null) {
            throw new XdStorageRuntimeException("BETWEEN requires non-null bounds — IS_NULL / IS_NOT_NULL are the " +
                    "null tests");
        }
        this.from = from;
        this.to = to;
    }

    /** The interval's lower bound.
     *
     * @return the inclusive lower bound */
    public Object getFrom() {
        return from;
    }

    /** The interval's upper bound.
     *
     * @return the inclusive upper bound */
    public Object getTo() {
        return to;
    }

    /** {@code "[from, to]"} — the mathematical interval notation. */
    @Override
    public String toString() {
        return "[" + from + ", " + to + "]";
    }
}
