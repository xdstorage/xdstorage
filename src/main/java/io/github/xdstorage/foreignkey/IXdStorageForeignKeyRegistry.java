package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

public interface IXdStorageForeignKeyRegistry {

    XdStorageClassInfo getParentClassInfo();

    XdStorageClassInfo getChildClassInfo();

    XdStorageObjectField getParentField();

    /**
     * Registers the FK pair in both trees and registers pending-FK for manual-ID parents.
     */
    void registerForeignKey(Comparable<?> parentObjectId, Comparable<?> childObjectId, IXdStorageTransaction transaction) throws XdStorageException;

    /**
     * Unregisters the FK pair (parent→child) from the dual B+trees.
     * Called by delete/update listeners when a child is actually deleted/updated,
     * to remove the stale FK-tree entry and decrement the deletion-wait counter.
     */
    void unregisterForeignKey(Comparable<?> parentObjectId, Comparable<?> childObjectId,
                              IXdStorageTransaction transaction) throws XdStorageException;
}
