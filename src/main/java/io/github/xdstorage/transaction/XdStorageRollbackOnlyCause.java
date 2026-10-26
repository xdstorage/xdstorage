package io.github.xdstorage.transaction;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * RBCAUSE (docs/tasks/2.1.0/34): the FIRST-CAUSE record of a rollback-only mark — who
 * marked the transaction, when, and why. A rollback-only transaction historically carried
 * exactly one bit of state; this record keeps the diagnosability information that exists
 * at the mark site (the caught exception, the field being loaded, the sweep that fired)
 * instead of discarding it.
 *
 * <p>Immutable value object except the repeat-mark counter: the first mark wins (recorded
 * atomically by the owning transaction), later marks only increment
 * {@link #getRepeatMarks()} — never the log.</p>
 */
public final class XdStorageRollbackOnlyCause {

    private final String site;

    private final Throwable cause;

    private final long markedAtEpochMillis;

    private final long txAgeMillis;

    private final String markedByTransactionId;

    private final String cascadedFrom;

    private final AtomicInteger repeatMarks = new AtomicInteger(1);

    /**
     * Builds the cause record (called by the owning transaction at the first mark).
     *
     * @param site                  human-readable descriptor of the marking site (e.g.
     *                              {@code "lazy-load of field 'crew' on Ship"})
     * @param cause                 the root exception at the mark site, {@code null} for
     *                              cascade/delegate marks that carry no exception
     * @param cascadedFrom          id of the transaction whose failure cascaded into this
     *                              mark, {@code null} for direct marks
     * @param markedByTransactionId the id of the transaction the record belongs to
     * @param txAgeMillis           the transaction's age at the mark, milliseconds
     */
    XdStorageRollbackOnlyCause(final String site, final Throwable cause, final String cascadedFrom,
                               final String markedByTransactionId, final long txAgeMillis) {
        this.site = site;
        this.cause = cause;
        this.cascadedFrom = cascadedFrom;
        this.markedByTransactionId = markedByTransactionId;
        this.txAgeMillis = txAgeMillis;
        this.markedAtEpochMillis = System.currentTimeMillis();
    }

    /**
     * The marking-site descriptor.
     *
     * @return who/where marked the transaction (never {@code null}; the no-arg marks
     *         record {@code "unspecified"})
     */
    public String getSite() {
        return site;
    }

    /**
     * The root exception captured at the mark site.
     *
     * @return the original failure, or {@code null} for cascade marks without an exception
     */
    public Throwable getCause() {
        return cause;
    }

    /**
     * The wall-clock moment of the first mark.
     *
     * @return epoch milliseconds of the mark
     */
    public long getMarkedAtEpochMillis() {
        return markedAtEpochMillis;
    }

    /**
     * The transaction's age at the first mark.
     *
     * @return milliseconds between the transaction's start and the mark
     */
    public long getTxAgeMillis() {
        return txAgeMillis;
    }

    /**
     * The id of the transaction this record belongs to.
     *
     * @return the marked transaction's id
     */
    public String getMarkedByTransactionId() {
        return markedByTransactionId;
    }

    /**
     * The transaction whose failure cascaded into this mark.
     *
     * @return the delegating/cascading transaction's id, or {@code null} for direct marks
     */
    public String getCascadedFrom() {
        return cascadedFrom;
    }

    /**
     * How many marks landed on the transaction in total (the first one included).
     *
     * @return the mark count; {@code 1} when this was the only mark
     */
    public int getRepeatMarks() {
        return repeatMarks.get();
    }

    /** Counts a later mark (first cause wins — the record itself never changes). */
    void incrementRepeatMarks() {
        repeatMarks.incrementAndGet();
    }

    /**
     * The failure-message fragment used by the cause-chained commit failures:
     * {@code "by <site> at tx-age <n> ms[: <root message>]"}.
     *
     * @return the human-readable cause summary
     */
    public String describe() {
        final StringBuilder sb = new StringBuilder("by ").append(site)
                .append(" at tx-age ").append(txAgeMillis).append(" ms");
        if (cascadedFrom != null) {
            sb.append(" (cascaded from ").append(cascadedFrom).append(')');
        }
        if (cause != null) {
            sb.append(": ").append(cause.getMessage());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "RollbackOnlyCause{" + describe() + ", markedBy=" + markedByTransactionId
                + ", repeatMarks=" + repeatMarks.get() + "}";
    }

    /**
     * RBCAUSE: builds the cause-chained failure exception for a rollback-only refusal —
     * appends the first-cause summary to the historical message and chains the root
     * exception as the cause. Shared by the transaction and the manager failure sites.
     *
     * @param baseMessage the historical failure message
     * @param recorded    the transaction's first-cause record, may be {@code null}
     *                    (pre-capture marks) — the plain message is returned then
     * @return the exception to throw
     */
    static XdStorageRuntimeException buildFailure(final String baseMessage, final XdStorageRollbackOnlyCause recorded) {
        final String message = recorded != null
                ? baseMessage + " (" + recorded.describe() + ")"
                : baseMessage;
        final XdStorageRuntimeException failure = new XdStorageRuntimeException(message);
        if (recorded != null && recorded.getCause() != null) {
            failure.initCause(recorded.getCause());
        }
        return failure;
    }
}
