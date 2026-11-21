package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.index.btree.XdStorageBTreeIndexResource;
import io.github.xdstorage.index.hash.XdStorageHashIndexResource;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.resource.*;
import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectFieldInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-transaction registry of pending foreign-key work used as a commit gate.
 *
 * <p>While a transaction waits for deferred child/parent identifiers or for parent-side
 * deletions to dissolve foreign-key pairs, per-transaction {@link AtomicLong} counters
 * (insert-waiting / delete-waiting) stay positive; {@link #checkAllNotificationsReceived}
 * vetoes the commit ("Not all foreign keys are registered/deleted") while a counter is
 * nonzero. Delete-waiter listeners also arm per-transaction delete pairs that phase-1
 * can sweep by state.</p>
 */
public class XdStorageForeignKeyChangeRegistry {

    private static final Logger log = LoggerFactory.getLogger(XdStorageForeignKeyChangeRegistry.class);

    private final IXdStorageForeignKeyRegistry registry;

    // map [transactionId, countObjectWithoutId]
    private final Map<String, AtomicLong> insertWaitingIdentifiers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> deleteWaitingIdentifiers = new ConcurrentHashMap<>();

    // MASSFILL: the per-transaction ARMED delete-waiter pairs (parentId -> childId), keyed
    // by pair identity. The PREPARE-event delivery to these listeners is fragile (the
    // purge's mixed-chunk deletes + torn-fill churn leave armed waiters whose child
    // records are already gone and whose events never dispatch), so the FK resource's
    // phase-1 sweeps them BY STATE before the counter veto: a waiter whose child record
    // is gone resolves by state (see XdStorageForeignKeyResource). Value = {parentId,
    // childId, sweepable}: sweepable=true for the parent-deletion/update families
    // (waitForChildDelete — the pair dissolves when the child turns out gone);
    // sweepable=false for the CHILD-deletion family (waitForParentDelete — the child is
    // deleted BY DESIGN and the pinned DeleteChildAlone veto rides the counter).
    private final Map<String, Map<String, Object[]>> armedDeletePairs = new ConcurrentHashMap<>();

    /**
     * Tracks an armed delete-waiter pair for the phase-1 state sweep.
     * @param transaction the owning transaction
     * @param parentObjectId the parent id
     * @param childObjectId the child id
     * @param sweepable true for the parent-deletion/update families (the waiter may be
     *        resolved by state when the child record is already gone); false for the
     *        child-deletion family, whose DeleteChildAlone veto must ride the counter
     */
    public void trackArmedDeletePair(final IXdStorageTransaction transaction,
                                     final Comparable<?> parentObjectId, final Comparable<?> childObjectId,
                                     final boolean sweepable) {
        if (parentObjectId == null || childObjectId == null) return;
        armedDeletePairs.computeIfAbsent(transaction.getTransactionId(), k -> new ConcurrentHashMap<>())
                .put(parentObjectId + "\u001F" + childObjectId, new Object[]{parentObjectId, childObjectId, sweepable});
    }

    /**
     * Untracks a resolved waiter pair and balances the delete counter. Removes the armed
     * pair keyed by the parent/child ids from the transaction's sweep set and decrements
     * the per-transaction delete-waiting counter.
     *
     * @param transaction the owning transaction
     * @param parentObjectId the parent id of the resolved pair
     * @param childObjectId the child id of the resolved pair
     */
    public void resolveArmedDeletePair(final IXdStorageTransaction transaction,
                                       final Comparable<?> parentObjectId, final Comparable<?> childObjectId) {
        final Map<String, Object[]> pairs = armedDeletePairs.get(transaction.getTransactionId());
        if (pairs != null) {
            pairs.remove(parentObjectId + "\u001F" + childObjectId);
        }
        decrementDeleteWaitingIdentifier(transaction);
    }

    /**
     * The armed delete-waiter pairs of the transaction (the phase-1 sweep input).
     *
     * @param transaction the transaction whose armed pairs to list
     * @return the armed pairs as {@code Object[]{parentId, childId, sweepable}} entries,
     *         empty when the transaction has none
     */
    public java.util.Collection<Object[]> armedDeletePairs(final IXdStorageTransaction transaction) {
        final Map<String, Object[]> pairs = armedDeletePairs.get(transaction.getTransactionId());
        return pairs != null ? pairs.values() : java.util.Collections.emptyList();
    }

    /**
     * Creates a change registry bound to the relation served by the given registry.
     *
     * @param registry the foreign-key registry providing the parent/child class infos and
     *        the parent field, and performing the actual foreign-key registration changes
     */
    public XdStorageForeignKeyChangeRegistry(IXdStorageForeignKeyRegistry registry) {
        this.registry = registry;
    }

    /**
     * Phase-1 commit gate: vetoes the commit while deferred foreign-key work is pending.
     * Logs (and then throws) when the transaction's insert-waiting counter is positive —
     * not all foreign keys are registered — or when its delete-waiting counter is
     * positive — not all foreign keys are deleted.
     *
     * @param transaction the transaction being validated
     * @throws XdStorageRuntimeException when either counter is still positive at phase-1
     */
    public void checkAllNotificationsReceived(IXdStorageTransaction transaction) {
        if (insertWaitingIdentifiers.getOrDefault(transaction.getTransactionId(), new java.util.concurrent.atomic.AtomicLong()).get() != 0) {
            log.error("Not all foreign keys are registered for relation "
                    + registry.getParentClassInfo().getClazz() + "#" + registry.getParentField().getName() + " -> " + registry.getChildClassInfo().getClazz());
        }
        if (deleteWaitingIdentifiers.getOrDefault(transaction.getTransactionId(), new java.util.concurrent.atomic.AtomicLong()).get() != 0) {
            log.error("Not all foreign keys are deleted for relation "
                    + registry.getParentClassInfo().getClazz() + "#" + registry.getParentField().getName() + " -> " + registry.getChildClassInfo().getClazz());
        }
        XdStorageClassInfo clParentInfo = registry.getParentClassInfo();
        XdStorageClassInfo clChildInfo = registry.getChildClassInfo();
        XdStorageObjectField clParentField = registry.getParentField();

        AtomicLong countObjectsWaitingInsertIdentifiers = insertWaitingIdentifiers
                .computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong());
        if (countObjectsWaitingInsertIdentifiers.get() > 0)
            throw new XdStorageRuntimeException("Not all foreign keys are registered for relation "
                    + clParentInfo.getClazz() + "#" + clParentField.getName() + " -> " + clChildInfo.getClazz());

        AtomicLong countObjectsWaitingDeleteIdentifiers = deleteWaitingIdentifiers
                .computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong());
        if (countObjectsWaitingDeleteIdentifiers.get() > 0)
            throw new XdStorageRuntimeException("Not all foreign keys are deleted for relation "
                    + clParentInfo.getClazz() + "#" + clParentField.getName() + " -> " + clChildInfo.getClazz());
    }

    /**
     * Discards all per-transaction state: the insert-waiting and delete-waiting counters
     * and the armed delete-waiter pairs. Called when the transaction ends, so that
     * never-resolved remainders do not leak into later transactions.
     *
     * @param transaction the transaction whose state to drop
     */
    public void release(IXdStorageTransaction transaction) {
        final String txId = transaction.getTransactionId();
        final AtomicLong remainingIns = insertWaitingIdentifiers.get(txId);
        final AtomicLong remainingDel = deleteWaitingIdentifiers.get(txId);
        insertWaitingIdentifiers.remove(txId);
        deleteWaitingIdentifiers.remove(txId);
        armedDeletePairs.remove(txId);
    }

    /**
     * Arms a delete-waiter on the given resource for an Update change: at
     * PREPARE_COMMIT of the owning resource, when the parent's new state no longer
     * references the child, the foreign-key pair is unregistered, the armed waiter
     * resolves and the transaction's delete-waiting counter decrements. Increments the
     * delete-waiting counter and tracks a sweepable armed pair immediately.
     *
     * @param resource the owning resource whose commit notifications drive the waiter
     * @param parentObjectId the id of the parent object currently referencing the child
     * @param childObjectId the id of the referenced child object
     * @param parentIdField the parent's id field, used to match change events to the parent
     * @param transaction the transaction the waiter is armed for
     */
    public void registerUpdateListener(XdStorageResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                       XdStorageObjectIdField parentIdField, IXdStorageTransaction transaction) {
        XdStorageObjectField clParentField = registry.getParentField();
        XdStorageClassInfo clChildInfo = registry.getChildClassInfo();

        incrementDeleteWaitingIdentifier(transaction);
        trackArmedDeletePair(transaction, parentObjectId, childObjectId, true);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Update) {
                    // oldObject may be the parent object or its String ID — handle both
                    Object pId = oldObject == null ? parentObjectId : parentIdField.get(oldObject);
                    if (pId.equals(parentObjectId)
                            && isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                        try {
                            registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                            resolveArmedDeletePair(transaction, parentObjectId, childObjectId);
                        } catch (XdStorageException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }, transaction);
    }

    /**
     * Arms a delete-waiter on the given B+tree index resource for an Update change: at
     * PREPARE_COMMIT of the owning resource, when the parent's new state no longer
     * references the child, the foreign-key pair is unregistered, the armed waiter
     * resolves and the transaction's delete-waiting counter decrements. Increments the
     * delete-waiting counter and tracks a sweepable armed pair immediately.
     *
     * @param resource the owning B+tree index resource whose commit notifications drive the waiter
     * @param parentObjectId the id of the parent object currently referencing the child
     * @param childObjectId the id of the referenced child object
     * @param parentIdField the parent's id field, used to match change events to the parent
     * @param transaction the transaction the waiter is armed for
     */
    public void registerUpdateListener(XdStorageBTreeIndexResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                       XdStorageObjectIdField parentIdField, IXdStorageTransaction transaction) {
        XdStorageObjectField clParentField = registry.getParentField();
        XdStorageClassInfo clChildInfo = registry.getChildClassInfo();

        incrementDeleteWaitingIdentifier(transaction);
        trackArmedDeletePair(transaction, parentObjectId, childObjectId, true);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Update) {
                    Object pId = oldObject == null ? parentObjectId : parentIdField.get(oldObject);
                    if (pId.equals(parentObjectId)
                            && isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                        try {
                            registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                            resolveArmedDeletePair(transaction, parentObjectId, childObjectId);
                        } catch (XdStorageException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }, transaction);
    }

    /**
     * Arms a delete-waiter on the given hash index resource for an Update change: at
     * PREPARE_COMMIT of the owning resource, when the parent's new state no longer
     * references the child, the foreign-key pair is unregistered, the armed waiter
     * resolves and the transaction's delete-waiting counter decrements. Increments the
     * delete-waiting counter and tracks a sweepable armed pair immediately.
     *
     * @param resource the owning hash index resource whose commit notifications drive the waiter
     * @param parentObjectId the id of the parent object currently referencing the child
     * @param childObjectId the id of the referenced child object
     * @param parentIdField the parent's id field, used to match change events to the parent
     * @param transaction the transaction the waiter is armed for
     */
    public void registerUpdateListener(XdStorageHashIndexResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                       XdStorageObjectIdField parentIdField, IXdStorageTransaction transaction) {
        XdStorageObjectField clParentField = registry.getParentField();
        XdStorageClassInfo clChildInfo = registry.getChildClassInfo();

        incrementDeleteWaitingIdentifier(transaction);
        trackArmedDeletePair(transaction, parentObjectId, childObjectId, true);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Update) {
                    Object pId = oldObject == null ? parentObjectId : parentIdField.get(oldObject);
                    if (pId.equals(parentObjectId)
                            && isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                        try {
                            registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                            resolveArmedDeletePair(transaction, parentObjectId, childObjectId);
                        } catch (XdStorageException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }, transaction);
    }

    private boolean isReferenceToChildObjectDeleted(Object newObject, XdStorageObjectField clParentField, XdStorageClassInfo clChildInfo, Comparable<?> childObjectId) {
        Object fieldObject = clParentField.get(newObject);
        XdStorageObjectFieldInfo clParentFieldInfo = clParentField.getFieldInfo();

        XdStorageObjectIdField clChildIdField = clChildInfo.getIdField();;

        if (clParentFieldInfo.isArray()) {
            Object[] arrObjects = (Object[]) fieldObject;
            if (arrObjects == null) return true;
            for (Object childObject : arrObjects) {
                if (childObject == null) continue;
                final Object memberId = clChildIdField.get(childObject);
                if (childObjectId.equals(memberId)) return false;
                // MASSFILL: a member whose id reads null (a deleted child's wrapper can
                // lose its readable id after its record is staged-deleted) is conservatively
                // treated as STILL REFERENCING — resolving the waiter here would kill the
                // DeleteChildAlone veto.
                if (memberId == null) return false;
            }
            return true;
        } else if (clParentFieldInfo.isCollection()) {
            Collection<?> collectionObjects = (Collection<?>) fieldObject;
            if (collectionObjects == null) return true;
            for (Object childObject : collectionObjects) {
                if (childObject == null) continue;
                final Object memberId = clChildIdField.get(childObject);
                if (childObjectId.equals(memberId)) return false;
                if (memberId == null) return false;
            }
            return true;
        } else if (clParentFieldInfo.isMap()) {
            Map<?, Object> mapObjects = (Map<?, Object>) fieldObject;
            if (mapObjects == null) return true;
            for (Object childObject : mapObjects.values()) {
                if (childObjectId.equals(clChildIdField.get(childObject))) return false;
            }
            return true;
        }
        return fieldObject == null;
    }

    /**
     * Arms a delete-waiter on the given resource for the Delete-OR-Update family: at
     * PREPARE_COMMIT of the owning resource, when the change is a Delete or an Update
     * of the watched child, the foreign-key pair is unregistered, the armed waiter
     * resolves and the transaction's delete-waiting counter decrements. Increments the
     * delete-waiting counter and tracks a sweepable armed pair immediately.
     *
     * @param resource the owning resource whose commit notifications drive the waiter
     * @param parentObjectId the id of the parent object holding the foreign key
     * @param childObjectId the id of the child object whose deletion/update dissolves the pair
     * @param childIdField the child's id field, used to match change events to the child
     * @param transaction the transaction the waiter is armed for
     */
    public void registerDeleteUpdateListener(XdStorageResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                             XdStorageObjectIdField childIdField, IXdStorageTransaction transaction) {
        incrementDeleteWaitingIdentifier(transaction);
        trackArmedDeletePair(transaction, parentObjectId, childObjectId, true);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    // oldObject may be the child object or its String ID
                    Comparable<?> cId = oldObject == null ? childObjectId : (Comparable<?>) childIdField.get(oldObject);
                    if (cId != null && cId.equals(childObjectId)) {
                        try {
                            registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                            resolveArmedDeletePair(transaction, parentObjectId, childObjectId);
                        } catch (XdStorageException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }, transaction);
    }

    /**
     * Arms a delete-waiter on the given B+tree index resource for the Delete-OR-Update
     * family: at PREPARE_COMMIT of the owning resource, when the change is a Delete or
     * an Update of the watched child, the foreign-key pair is unregistered, the armed
     * waiter resolves and the transaction's delete-waiting counter decrements.
     * Increments the delete-waiting counter and tracks a sweepable armed pair immediately.
     *
     * @param resource the owning B+tree index resource whose commit notifications drive the waiter
     * @param parentObjectId the id of the parent object holding the foreign key
     * @param childObjectId the id of the child object whose deletion/update dissolves the pair
     * @param childIdField the child's id field, used to match change events to the child
     * @param transaction the transaction the waiter is armed for
     */
    public void registerDeleteUpdateListener(XdStorageBTreeIndexResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                             XdStorageObjectIdField childIdField, IXdStorageTransaction transaction) {
        incrementDeleteWaitingIdentifier(transaction);
        trackArmedDeletePair(transaction, parentObjectId, childObjectId, true);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    Comparable<?> cId = oldObject == null ? childObjectId : (Comparable<?>) childIdField.get(oldObject);
                    if (cId != null && cId.equals(childObjectId)) {
                        try {
                            registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                            resolveArmedDeletePair(transaction, parentObjectId, childObjectId);
                        } catch (XdStorageException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }, transaction);
    }

    /**
     * Arms a delete-waiter on the given hash index resource for the Delete-OR-Update
     * family: at PREPARE_COMMIT of the owning resource, when the change is a Delete or
     * an Update of the watched child, the foreign-key pair is unregistered, the armed
     * waiter resolves and the transaction's delete-waiting counter decrements.
     * Increments the delete-waiting counter and tracks a sweepable armed pair immediately.
     *
     * @param resource the owning hash index resource whose commit notifications drive the waiter
     * @param parentObjectId the id of the parent object holding the foreign key
     * @param childObjectId the id of the child object whose deletion/update dissolves the pair
     * @param childIdField the child's id field, used to match change events to the child
     * @param transaction the transaction the waiter is armed for
     */
    public void registerDeleteUpdateListener(XdStorageHashIndexResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                             XdStorageObjectIdField childIdField, IXdStorageTransaction transaction) {
        incrementDeleteWaitingIdentifier(transaction);
        trackArmedDeletePair(transaction, parentObjectId, childObjectId, true);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    // oldObject may be the child object or its String ID — handle both
                    Comparable<?> cId = oldObject == null ? childObjectId : (Comparable<?>) childIdField.get(oldObject);
                    if (childObjectId.equals(cId)) {
                        try {
                            registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                            resolveArmedDeletePair(transaction, parentObjectId, childObjectId);
                        } catch (XdStorageException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }, transaction);
    }

    /**
     * Arms a delete-waiter for the parent-removal family: at PREPARE_COMMIT of the
     * owning resource, a Delete of the parent dissolves the pair unconditionally, while
     * an Update dissolves it only when the parent's new state actually dropped the child
     * — deleting a child alone while the parent still references it keeps the waiter
     * armed so the phase-1 counter veto fires. Increments the delete-waiting counter and
     * tracks a non-sweepable armed pair immediately.
     *
     * @param resource the owning resource whose commit notifications drive the waiter
     * @param parentObjectId the id of the parent object whose removal is awaited
     * @param childObjectId the id of the referenced child object
     * @param parentIdField the parent's id field, used to match change events to the parent
     * @param transaction the transaction the waiter is armed for
     */
    public void registerParentDeleteUpdateListener(XdStorageResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                                   XdStorageObjectIdField parentIdField, IXdStorageTransaction transaction) {
        incrementDeleteWaitingIdentifier(transaction);
        trackArmedDeletePair(transaction, parentObjectId, childObjectId, false);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    Object pId = oldObject == null ? parentObjectId : parentIdField.get(oldObject);
                    if (!pId.equals(parentObjectId)) {
                        return;
                    }
                    // CANON (the architect's arbitration 2026-11-20, DeleteScenarios.testDeleteOnlyOneChildThrows):
                    // delete-child-alone while the parent still references it → VETO. The UPDATE branch
                    // dissolves ONLY when the parent's new state actually dropped the child — the same
                    // guard the sibling registerUpdateListener family applies. A parent updated while its
                    // collection STILL references the just-deleted child keeps the armed waiter: the
                    // counter survives to phase-1 and the DeleteChildAlone veto fires. (A member whose id
                    // reads null is conservatively treated as still referencing — see
                    // isReferenceToChildObjectDeleted.) The DELETE branch stays unconditional: a deleted
                    // parent can never hold a dangling reference to the child.
                    if (change == XdStorageObjectOperationType.Update
                            && !isReferenceToChildObjectDeleted(newObject, registry.getParentField(), registry.getChildClassInfo(), childObjectId)) {
                        return;
                    }
                    try {
                        registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                        resolveArmedDeletePair(transaction, parentObjectId, childObjectId);
                    } catch (XdStorageException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }, transaction);
    }

    /**
     * Arms the deferred-id waiter on the child side: increments the insert-waiting
     * counter and attaches an id observer to the child wrapper, so that when the
     * child's generated id lands the foreign-key pair (parentId, childId) is registered
     * and the counter decrements.
     *
     * @param parentId the id of the parent object referencing the not-yet-identified child
     * @param childObject the child wrapper whose generated id is awaited
     * @param transaction the transaction the waiter is armed for
     */
    public void registerChildIdWaiter(Comparable<?> parentId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) {
        incrementInsertWaitingIdentifier(transaction);
        childObject.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
                try {
                    registry.registerForeignKey(parentId, (Comparable<?>) childId, transaction);
                    decrementInsertWaitingIdentifier(transaction);
                } catch (XdStorageException e) {
                    throw new XdStorageRuntimeException(e);
                }
            }
        });
    }

    /**
     * Arms the deferred-id waiter on the parent side: increments the insert-waiting
     * counter and attaches an id observer to the parent wrapper, so that when the
     * parent's generated id lands the foreign-key pair (parentId, childId) is registered
     * and the counter decrements.
     *
     * @param childId the id of the child object referenced by the not-yet-identified parent
     * @param parentObject the parent wrapper whose generated id is awaited
     * @param transaction the transaction the waiter is armed for
     */
    public void registerParentIdWaiter(Comparable<?> childId, IXdStorageIdObservableWrapper parentObject, XdStorageTransaction transaction) {
        incrementInsertWaitingIdentifier(transaction);
        parentObject.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object parentId) {
                try {
                    registry.registerForeignKey((Comparable<?>) parentId, childId, transaction);
                    decrementInsertWaitingIdentifier(transaction);
                } catch (XdStorageException e) {
                    throw new XdStorageRuntimeException(e);
                }
            }
        });
    }

    /**
     * Arms deferred-id waiters on both sides of a relation whose parent and child
     * identifiers are both still unset: increments the insert-waiting counter twice and
     * attaches id observers to both wrappers; the foreign-key pair is registered once
     * both generated ids have landed, and the counter decrements accordingly.
     *
     * @param parentObject the parent wrapper whose generated id is awaited
     * @param childObject the child wrapper whose generated id is awaited
     * @param transaction the transaction the waiters are armed for
     */
    public void registerRelationIdsWaiters(IXdStorageIdObservableWrapper parentObject, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) {
        Object[] relationIds = new Object[2];
        incrementInsertWaitingIdentifier(transaction);
        parentObject.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object parentId) {
                relationIds[0] = parentId;
                if (relationIds[1] != null) {
                    try {
                        registry.registerForeignKey((Comparable<?>) relationIds[0], (Comparable<?>) relationIds[1], transaction);
                        decrementInsertWaitingIdentifier(transaction);
                    } catch (XdStorageException e) {
                        throw new XdStorageRuntimeException(e);
                    }
                }
            }
        });
        incrementInsertWaitingIdentifier(transaction);
        childObject.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
                relationIds[1] = childId;
                if (relationIds[0] != null) {
                    try {
                        registry.registerForeignKey((Comparable<?>) relationIds[0], (Comparable<?>) relationIds[1], transaction);
                        decrementInsertWaitingIdentifier(transaction);
                    } catch (XdStorageException e) {
                        throw new XdStorageRuntimeException(e);
                    }
                }
            }
        });
    }

    /**
     * Increments the transaction's insert-waiting counter: one more foreign key is
     * waiting for a deferred identifier to be registered. A positive counter at phase-1
     * vetoes the commit.
     *
     * @param transaction the transaction whose counter to increment
     */
    public void incrementInsertWaitingIdentifier(IXdStorageTransaction transaction) {
        insertWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .incrementAndGet();
    }

    /**
     * Decrements the transaction's insert-waiting counter: one deferred foreign key has
     * been registered. Must mirror a prior {@link #incrementInsertWaitingIdentifier} call.
     *
     * @param transaction the transaction whose counter to decrement
     */
    public void decrementInsertWaitingIdentifier(IXdStorageTransaction transaction) {
        insertWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .decrementAndGet();
    }

    /**
     * Increments the transaction's delete-waiting counter: one more armed delete-waiter
     * is waiting for a foreign-key pair to be dissolved. A positive counter at phase-1
     * vetoes the commit.
     *
     * @param transaction the transaction whose counter to increment
     */
    public void incrementDeleteWaitingIdentifier(IXdStorageTransaction transaction) {
        deleteWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .incrementAndGet();
    }

    /**
     * Decrements the transaction's delete-waiting counter: one armed delete-waiter has
     * resolved and its foreign-key pair has been dissolved. Must mirror a prior
     * {@link #incrementDeleteWaitingIdentifier} call.
     *
     * @param transaction the transaction whose counter to decrement
     */
    public void decrementDeleteWaitingIdentifier(IXdStorageTransaction transaction) {
        deleteWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .decrementAndGet();
    }
}
