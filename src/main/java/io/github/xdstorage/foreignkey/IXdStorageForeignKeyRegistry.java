package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

/**
 * The per-relation foreign-key registry: declares the relation triple the FK resource
 * serves (parent class, child class, the parent's referencing field) and performs the
 * FK pair registration/unregistration in the dual B+trees.
 */
public interface IXdStorageForeignKeyRegistry {

    /**
     * The class info of the relation's parent (referencing) side.
     * @return the parent class info
     */
    XdStorageClassInfo getParentClassInfo();

    /**
     * The class info of the relation's child (referenced) side.
     * @return the child class info
     */
    XdStorageClassInfo getChildClassInfo();

    /**
     * The parent's field holding the reference (collection, array, map or single
     * reference) through which the relation is declared.
     * @return the parent's referencing field
     */
    XdStorageObjectField getParentField();

    /**
     * Registers the FK pair in both trees and registers pending-FK for manual-ID parents.
     * POSITIONAL note: on the outcome-relation path (the parent's children collection)
     * the arguments arrive swapped — the first argument carries the child id (§7 A2.1/A2.2
     * of docs/analysis/FOREIGN_KEYS.md); the {@code isChildInParentIndex} discriminator
     * resolves the real roles.
     *
     * @param parentObjectId the first positional id (the parent id on the income path; the child id on the outcome path)
     * @param childObjectId  the second positional id (the mirror of the above)
     * @param transaction    the owning transaction
     * @throws XdStorageException on a storage-level failure (including the duplicate-pair rejection)
     */
    void registerForeignKey(Comparable<?> parentObjectId, Comparable<?> childObjectId, IXdStorageTransaction transaction) throws XdStorageException;

    /**
     * Unregisters the FK pair (parent→child) from the dual B+trees.
     * Called by delete/update listeners when a child is actually deleted/updated,
     * to remove the stale FK-tree entry and decrement the deletion-wait counter.
     *
     * @param parentObjectId the parent id (the front-tree key)
     * @param childObjectId  the child id (the front-tree value)
     * @param transaction    the owning transaction
     * @throws XdStorageException on a storage-level failure
     */
    void unregisterForeignKey(Comparable<?> parentObjectId, Comparable<?> childObjectId,
                              IXdStorageTransaction transaction) throws XdStorageException;
}
