package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;

/**
 * The foreign-key engine: processes object changes through the declared relations
 * (registering/dissolving FK pairs, arming commit waiters) and drives the staged
 * write-set's commit/rollback contour.
 */
public interface IXdStorageForeignKeyEngine {

    /**
     * The trigger entry point: routes the object change through the income relations
     * (the child's {@code @XdStorageParentObject}) and the outcome relations (the
     * parent's {@code @XdStorageAutoSave}); unwraps the runtime proxy at entry.
     *
     * @param object      the changed POJO (possibly a generated wrapper — unwrapped here)
     * @param type        the change kind (INSERT / UPDATE / DELETE)
     * @param transaction the owning transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void process(Object object, XdStorageForeignKeyObjectChangeType type, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Commit all cached FK pairs to the B+tree. Called from the 2PC commit protocol.
     *
     * @param transaction the committing transaction
     * @throws XdStorageException on a storage-level failure
     */
    void commitAllFxPairs(XdStorageTransaction transaction) throws XdStorageException;

    /**
     * FNKEY POINT 7: discards the transaction's staged FK pair write-set (rollback/abort).
     *
     * @param transaction the transaction whose write-set is discarded
     */
    void releaseFxPairs(XdStorageTransaction transaction);

    /**
     * Access to the engine's resources manager — needed by shutdown cleanup.
     *
     * @return the FK resources manager owning the FK resources and the shared write-set
     */
    XdStorageForeignKeyResourcesManager getForeignKeyResourcesManager();
}
