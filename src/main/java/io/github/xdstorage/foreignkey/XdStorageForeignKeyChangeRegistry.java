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

    /** Tracks an armed delete-waiter pair for the phase-1 state sweep. */
    public void trackArmedDeletePair(final IXdStorageTransaction transaction,
                                     final Comparable<?> parentObjectId, final Comparable<?> childObjectId,
                                     final boolean sweepable) {
        if (parentObjectId == null || childObjectId == null) return;
        armedDeletePairs.computeIfAbsent(transaction.getTransactionId(), k -> new ConcurrentHashMap<>())
                .put(parentObjectId + "\u001F" + childObjectId, new Object[]{parentObjectId, childObjectId, sweepable});
    }

    /** Untracks a resolved waiter pair and balances the delete counter. */
    public void resolveArmedDeletePair(final IXdStorageTransaction transaction,
                                       final Comparable<?> parentObjectId, final Comparable<?> childObjectId) {
        final Map<String, Object[]> pairs = armedDeletePairs.get(transaction.getTransactionId());
        if (pairs != null) {
            pairs.remove(parentObjectId + "\u001F" + childObjectId);
        }
        decrementDeleteWaitingIdentifier(transaction);
    }

    /** The armed delete-waiter pairs of the transaction (the phase-1 sweep input). */
    public java.util.Collection<Object[]> armedDeletePairs(final IXdStorageTransaction transaction) {
        final Map<String, Object[]> pairs = armedDeletePairs.get(transaction.getTransactionId());
        return pairs != null ? pairs.values() : java.util.Collections.emptyList();
    }

    public XdStorageForeignKeyChangeRegistry(IXdStorageForeignKeyRegistry registry) {
        this.registry = registry;
    }

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

    public void release(IXdStorageTransaction transaction) {
        final String txId = transaction.getTransactionId();
        final AtomicLong remainingIns = insertWaitingIdentifiers.get(txId);
        final AtomicLong remainingDel = deleteWaitingIdentifiers.get(txId);
        insertWaitingIdentifiers.remove(txId);
        deleteWaitingIdentifiers.remove(txId);
        armedDeletePairs.remove(txId);
    }

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

    public void incrementInsertWaitingIdentifier(IXdStorageTransaction transaction) {
        insertWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .incrementAndGet();
    }

    public void decrementInsertWaitingIdentifier(IXdStorageTransaction transaction) {
        insertWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .decrementAndGet();
    }

    public void incrementDeleteWaitingIdentifier(IXdStorageTransaction transaction) {
        deleteWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .incrementAndGet();
    }

    public void decrementDeleteWaitingIdentifier(IXdStorageTransaction transaction) {
        deleteWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .decrementAndGet();
    }
}
