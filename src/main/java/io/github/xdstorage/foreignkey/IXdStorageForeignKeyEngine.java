package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;

public interface IXdStorageForeignKeyEngine {

    void process(Object object, XdStorageForeignKeyObjectChangeType type, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /** Commit all cached FK pairs to the B+tree. Called from the 2PC commit protocol. */
    void commitAllFxPairs(XdStorageTransaction transaction) throws XdStorageException;

    /** FNKEY POINT 7: discards the transaction's staged FK pair write-set (rollback/abort). */
    void releaseFxPairs(XdStorageTransaction transaction);

    /** Access to the engine's resources manager — needed by shutdown cleanup. */
    XdStorageForeignKeyResourcesManager getForeignKeyResourcesManager();
}
