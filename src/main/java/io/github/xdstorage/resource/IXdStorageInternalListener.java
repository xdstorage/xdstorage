package io.github.xdstorage.resource;

import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.transaction.IXdStorageTransaction;

/**
 * The internal change listener of a listenable resource: receives the cache-phase
 * notifications fired for each changed record as the owning transaction moves through
 * its two-phase commit (PREPARE_COMMIT and PERFORM_COMMIT per change).
 */
public interface IXdStorageInternalListener {

    /**
     * Delivers one commit-phase notification for a changed record.
     *
     * @param phase       the commit phase being applied (PREPARE_COMMIT or PERFORM_COMMIT)
     * @param change      the operation kind of the staged change (Insert/Update/Delete)
     * @param oldObject   the pre-change state (the previous object or its id, per the resource)
     * @param newObject   the post-change state (the pending new object)
     * @param transaction the transaction whose commit is moving through the phase
     */
    void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change,
                Object oldObject, Object newObject, IXdStorageTransaction transaction);
}
