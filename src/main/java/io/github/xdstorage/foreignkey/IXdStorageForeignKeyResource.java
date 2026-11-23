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

/**
 * The per-relation foreign-key resource: serves one parent→child relation and owns the
 * FK pair trees (front by parent id, reverse by child id) plus the transaction's staged
 * write-set overlay. Provides the registration/dissolution operations for the FK engine,
 * the read APIs with read-your-own-read overlay, and the pending/orphan bookkeeping the
 * commit protocol validates.
 */
public interface IXdStorageForeignKeyResource extends IXdStorageResourceObject {

    /**
     * Reads all child ids registered under the parent — the committed state plus this
     * transaction's own staged pairs (the read-through overlay, read-your-own-read).
     *
     * @param objectId    the parent id to read the front tree by
     * @param transaction the owning transaction (the overlay source)
     * @return the child ids; empty when the parent has no registered children
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    Collection<Object> readByParentObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException;

    /**
     * Raw FK tree read WITHOUT overlay (MVCC snapshot only). Used for orphan detection
     * to avoid false positives from children committed by other transactions.
     *
     * @param objectId    the parent id to read the front tree by
     * @param transaction the owning transaction
     * @return the committed child ids only; this transaction's staged pairs are invisible
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    Collection<Object> readByParentObjectIdRaw(Comparable<?> objectId, XdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException;

    /**
     * Reads all parent ids registered for the child — the reverse-tree mirror of
     * {@link #readByParentObjectId} (the committed state plus this transaction's staged pairs).
     *
     * @param objectId    the child id to read the reverse tree by
     * @param transaction the owning transaction (the overlay source)
     * @return the parent ids; empty when the child has no registered parents
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    Collection<Object> readByChildObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException;

    /**
     * Registers the FK pair (parent→child) in both trees with both ids resolved.
     * A duplicate pair in the same relation is rejected (the idempotency contract
     * lives in the write-set: an equal later-wins intent replaces the earlier one).
     *
     * @param parentObjectId the resolved parent id
     * @param childObjectId  the resolved child id
     * @param transaction    the owning transaction
     * @throws XdStorageException          on a storage-level failure (including the duplicate-pair rejection)
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void insert(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException;

    /**
     * Registers the FK pair with the PARENT unresolved (an auto-id parent not yet saved):
     * the parent id lands via the observable wrapper; until then the pair is pending
     * and validated at phase-1 (the manual-id/auto-id parent contract).
     *
     * @param parentObject the parent's observable wrapper (its id backfills into the same instance)
     * @param childId      the resolved child id
     * @param transaction  the owning transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void insert(IXdStorageIdObservableWrapper parentObject, Comparable<?> childId, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException;

    /**
     * Registers the FK pair with the CHILD unresolved (an auto-id child not yet saved):
     * a deferred-id waiter resolves the pair when the child's generated id lands.
     *
     * @param parentObjectId the resolved parent id
     * @param childObject    the child's observable wrapper (its generated id backfills into the same instance)
     * @param transaction    the owning transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void insert(Comparable<?> parentObjectId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction
            transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Registers the FK pair with BOTH sides unresolved (both auto-id objects not yet
     * saved) — both deferred-id waiters must resolve before the pair lands.
     *
     * @param parentObject the parent's observable wrapper
     * @param childObject  the child's observable wrapper
     * @param transaction  the owning transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void insert(IXdStorageIdObservableWrapper parentObject, IXdStorageIdObservableWrapper childObject,
            XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Parent-update bookkeeping: re-arms the pair's waiter/provenance pre-checks for the
     * engine's outcome-UPDATE diff (the pair survives, dissolves, or vetoes per the diff).
     *
     * @param parentObject   the parent POJO (the provenance checks read its state)
     * @param parentObjectId the parent id
     * @param childObject    the child POJO
     * @param childObjectId  the child id
     * @param transaction    the owning transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void update(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId,
            XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Pair dissolution for the engine's delete paths: arms the delete waiters (the veto
     * expectations) and dissolves the pair when the referencing side is genuinely gone.
     * A {@code null} parent object serves the child-side (income) delete.
     *
     * @param parentObject   the parent POJO ({@code null} for the income-delete path)
     * @param parentObjectId the parent id
     * @param childObject    the child POJO ({@code null} for the outcome-delete path)
     * @param childObjectId  the child id
     * @param transaction    the owning transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void delete(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId,
            XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Idempotent: unregisters the FK pair (parentId→childId) from both trees.
     * Does NOT throw if the pair is already absent (e.g. already dissolved by
     * the FK engine's update-diff). This is safe to call from the FK engine's
     * DELETE path where concurrent-fill residue may have phantom pairs.
     * @param parentId the parent id
     * @param childId the child id
     * @param transaction the owning transaction
     * @throws XdStorageException on a storage-level failure
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
     *
     * @param transaction     the owning transaction
     * @param parentClassInfo the class info of the referenced parent class
     * @param parentObject    the not-yet-persisted parent object reference
     * @param childId         the resolved child id
     */
    void registerPendingFkByObject(XdStorageTransaction transaction, XdStorageClassInfo parentClassInfo,
                                   Object parentObject, Comparable<?> childId);

    /**
     * FNKEY (FOREIGN_KEY_LISTENERS_WAITERS.md §4.3): the FK-cache-backed pending-by-object
     * registration — the pending lands in phase-1's {@code resolvePendingByObjectByState}
     * (the id is read BY STATE: a manual setId on the same POJO, or the DATABASE generator
     * backfilling the same instance) and stages the deferred pair in the FK write-set.
     * {@code parentClassInfo} must be the class of the ACTUAL parent object (the engine's
     * outcome/declaration path passes the referenced class, not the resource's front class,
     * so the phase-1 getter applies to the matching POJO).
     *
     * @param transaction     the owning transaction
     * @param parentClassInfo the class of the ACTUAL parent object (not the resource's front class)
     * @param parentObject    the not-yet-identified parent object whose id resolves by state
     * @param childId         the resolved child id
     */
    void registerPendingFkByObjectToCache(XdStorageTransaction transaction, XdStorageClassInfo parentClassInfo,
                                          Object parentObject, Comparable<?> childId);

    /**
     * FNKEY: Registers orphaned children for commit-time validation.
     * Called by the FK engine after update-diff detects orphaned children.
     *
     * @param transaction the owning transaction
     * @param parentIds   the ids of the parents whose children turned orphaned by the update
     */
    void registerOrphanedChildren(XdStorageTransaction transaction, Set<Comparable<?>> parentIds);

    /**
     * Checks whether a child FK entry still exists under the parent.
     * Used by the FK engine's cascade-DELETE path to skip children that
     * were already deleted (avoiding stale FK-tree entries from the
     * seenKeys cache that would cause false waiter-arming).
     */
    /**
     * Checks whether a child FK entry still exists under the parent.
     * Used by the FK engine's cascade-DELETE path to skip children that
     * were already deleted (avoiding stale FK-tree entries from the
     * seenKeys cache that would cause false waiter-arming).
     *
     * @param parentObjectId the parent id (the front-tree key)
     * @param childObjectId  the child id (the front-tree value)
     * @param transaction    the owning transaction
     * @return {@code true} when the pair entry exists
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    boolean childExists(Comparable<?> parentObjectId, Comparable<?> childObjectId,
                       XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * MASSFILL: the parent-REMOVAL flow's waiter arming (the engine's outcome-DELETE
     * diff). Unlike {@code delete(parent, parentId, null, childId, tx)} (which serves the
     * parent-UPDATE diff with the pair-provenance pre-checks), the parent-removal flow
     * arms UNCONDITIONALLY for a live referenced child (the pinned DeleteParentAlone
     * veto — the pair's provenance is irrelevant when the parent itself goes away) and
     * dissolves for a gone child (the purge's deleted/phantom children).
     */
    /**
     * MASSFILL: the parent-REMOVAL flow's waiter arming (the engine's outcome-DELETE
     * diff). Unlike {@code delete(parent, parentId, null, childId, tx)} (which serves the
     * parent-UPDATE diff with the pair-provenance pre-checks), the parent-removal flow
     * arms UNCONDITIONALLY for a live referenced child (the pinned DeleteParentAlone
     * veto — the pair's provenance is irrelevant when the parent itself goes away) and
     * dissolves for a gone child (the purge's deleted/phantom children).
     *
     * @param parentObject   the parent POJO being removed
     * @param parentObjectId the parent id
     * @param childObjectId  the referenced child id
     * @param transaction    the owning transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure
     */
    void armChildDeleteWaiterForParentRemoval(Object parentObject, Comparable<?> parentObjectId,
                                              Comparable<?> childObjectId, IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException;
}
