package io.github.xdstorage.resource;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;

import java.util.Collection;

/**
 * The transactional façade over one DAO resource — the unit the resources manager locks,
 * prepares, registers into transactions and drives through the two-phase commit. One instance
 * serves many concurrent transactions: staging goes through per-transaction cache contours,
 * while the commit phases are serialized per resource by {@link #lockForCommit}. Instances
 * are long-lived: they stay in the manager's registry until storage shutdown. The lifecycle
 * of one transaction on this resource is {@code lockForCommit -> performFirstPhaseCommit ->
 * performSecondPhaseCommit -> unlockAfterCommit} (or {@code rollback}/{@code release}).
 *
 * <p>Engine-internal — not part of the public API.</p>
 *
 * @param <DaoInterface> the DAO view type exposed by {@link #getDao()}
 */
public interface IXdStorageResourceObject<DaoInterface> {

    /**
     * The storage-wide unique id of this resource (its relative file path).
     *
     * @return the resource id
     */
    Object getResourceId();

    /**
     * The entity class whose objects this resource stores.
     *
     * @return the entity class
     */
    Class<?> getObjectsClass();

    /**
     * The number of records currently held by the resource cache (defined once prepared).
     *
     * @return the cached object count
     */
    long getObjectsCount();

    /**
     * Whether the transaction has staged changes (insert/update/delete) in this resource.
     *
     * @param transaction the transaction to inspect
     * @return {@code true} when the transaction's write set for this resource is non-empty
     * @throws XdStorageException on a storage-level failure
     */
    boolean hasChanges(XdStorageTransaction transaction) throws XdStorageException;

    /**
     * Prepares the resource for use by the transaction: on the first call the backing file is
     * loaded into the resource cache (and a commit journal replayed), later calls are no-ops;
     * idempotent and safe under concurrency.
     *
     * @param transaction the transaction the resource is prepared for
     * @throws XdStorageException          when the resource state cannot be loaded
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void prepare(XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Compensates an already-persisted phase-1 record after the overall commit failed: rolls
     * back the effects the first phase wrote (undoing the prepared changes) so no trace of the
     * failed transaction survives.
     *
     * @param transaction the failed transaction
     * @param changes     the staged changes the first phase persisted
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void rollbackPerformingFirstPhaseCommit(XdStorageTransaction transaction, Collection<XdStorageObjectChange> changes) throws XdStorageException, XdStorageConnectionException;

    /**
     * Phase 1 of the two-phase commit: durably persists the transaction's changes for this
     * resource (full-file write-out, or an append to the commit journal) WITHOUT making them
     * visible to other transactions. Once this succeeds the changes survive a crash and are
     * either published (phase 2) or compensated.
     *
     * @param transaction the committing transaction
     * @param record      the collector the phase fills with the staged changes (type, id,
     *                    old and new object) for phase 2 and compensation
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     * @throws XdStorageException           on a storage-level failure
     */
    void performFirstPhaseCommit(XdStorageTransaction transaction, XdStorageTransactionResourceChanges record) throws XdStorageConnectionException, XdStorageException;

    /**
     * Phase 2 of the two-phase commit: publishes the phase-1 persisted changes into the
     * resource cache, making them visible to subsequent transactions. Idempotent w.r.t. an
     * already-committed record.
     *
     * @param transaction the committing transaction
     * @param record      the collector filled by phase 1
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     * @throws XdStorageException           on a storage-level failure
     */
    void performSecondPhaseCommit(XdStorageTransaction transaction, XdStorageTransactionResourceChanges record) throws XdStorageConnectionException, XdStorageException;

    /**
     * Discards the transaction's staged changes in this resource (pre-commit rollback);
     * the persistent state is untouched.
     *
     * @param transaction the transaction being rolled back
     * @throws XdStorageException on a storage-level failure
     */
    void rollback(XdStorageTransaction transaction) throws XdStorageException;

    /**
     * Acquires this resource's commit lock — serializes the two-phase commit against other
     * committing transactions. Called before phase 1; must be paired with
     * {@link #unlockAfterCommit}. Blocks up to the transaction's timeout, then fails loudly.
     *
     * @param transaction the transaction acquiring the lock
     */
    void lockForCommit(XdStorageTransaction transaction);

    /**
     * Releases the commit lock acquired by {@link #lockForCommit}.
     *
     * @param transaction the transaction releasing the lock
     */
    void unlockAfterCommit(XdStorageTransaction transaction);

    /**
     * Releases the resource from the transaction: clears the transaction's contours in the
     * resource cache and decrements the manager's usage counter. Does not destroy the
     * resource object — it stays cached for the next user.
     *
     * @param transaction the transaction releasing the resource
     */
    void release(XdStorageTransaction transaction);

    /**
     * The DAO view of this resource — the record-level read/write surface (often the
     * resource itself).
     *
     * @return the DAO view
     */
    DaoInterface getDao();

    default void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction) {
        // do nothing
    }
}
