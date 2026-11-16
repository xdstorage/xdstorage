package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;

import java.util.Set;

import java.util.Collection;

public interface IXdStorageForeignKeyResource extends IXdStorageResourceObject {

    Collection<Object> readByParentObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /** Raw FK tree read WITHOUT overlay (MVCC snapshot only). Used for orphan detection
     * to avoid false positives from children committed by other transactions. */
    Collection<Object> readByParentObjectIdRaw(Comparable<?> objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    Collection<Object> readByChildObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(IXdStorageIdObservableWrapper parentObject, Comparable<?> childId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(Comparable<?> parentObjectId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(IXdStorageIdObservableWrapper parentObject, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void update(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void delete(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Idempotent: unregisters the FK pair (parentId→childId) from both trees.
     * Does NOT throw if the pair is already absent (e.g. already dissolved by
     * the FK engine's update-diff). This is safe to call from the FK engine's
     * DELETE path where concurrent-fill residue may have phantom pairs.
     */
    void unregisterForeignKey(Comparable<?> parentId, Comparable<?> childId,
                              IXdStorageTransaction transaction) throws XdStorageException;

    /**
     * FNKEY: Registers a pending FK pair tracked by parent object reference
     * (not parent ID). Used when the parent has no ID yet (auto-id parent
     * not yet saved) and the FK engine detects the parent is not persisted.
     * At commit time, validatePendingFks checks if the parent is persisted;
     * if not, throws "parent does not exist". If the parent IS persisted,
     * the FK pair will have been registered by the parent's trigger and no
     * error is raised.
     */
    void registerPendingFkByObject(XdStorageTransaction transaction, XdStorageClassInfo parentClassInfo,
                                   Object parentObject, Comparable<?> childId);

    /**
     * FNKEY: Registers orphaned children for commit-time validation.
     * Called by the FK engine after update-diff detects orphaned children.
     */
    void registerOrphanedChildren(XdStorageTransaction transaction, Set<Comparable<?>> parentIds);

    /**
     * Checks whether a child FK entry still exists under the parent.
     * Used by the FK engine's cascade-DELETE path to skip children that
     * were already deleted (avoiding stale FK-tree entries from the
     * seenKeys cache that would cause false waiter-arming).
     */
    boolean childExists(Comparable<?> parentObjectId, Comparable<?> childObjectId,
                       XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;
}
