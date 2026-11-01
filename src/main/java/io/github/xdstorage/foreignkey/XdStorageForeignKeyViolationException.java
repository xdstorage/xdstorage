package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;

/**
 * FNKEY (docs/tasks/2.1.0/42): a foreign-key invariant violation — the designed,
 * caller-visible commit refusal. Carries the transaction id, the violated relation and
 * the offending object identifiers. Throwing one marks the active transaction
 * rollback-only (the throwing site does that), and the transaction manager propagates
 * this type out of the commit call after compensation — unlike internal commit errors,
 * which are compensated silently.
 */
public class XdStorageForeignKeyViolationException extends XdStorageRuntimeException {

    private final String transactionId;
    private final String relation;

    /**
     * Builds the violation.
     *
     * @param transactionId the committing transaction whose invariants broke
     * @param relation      the violated relation name (parent class + field + child class)
     * @param message       the detailed violation message (offending identifiers included)
     */
    public XdStorageForeignKeyViolationException(final String transactionId, final String relation, final String message) {
        super(message);
        this.transactionId = transactionId;
        this.relation = relation;
    }

    /**
     * The committing transaction whose invariants broke.
     *
     * @return the transaction id
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * The violated relation name.
     *
     * @return the relation descriptor
     */
    public String getRelation() {
        return relation;
    }
}
