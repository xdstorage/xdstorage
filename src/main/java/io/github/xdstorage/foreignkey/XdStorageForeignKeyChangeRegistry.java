package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.index.btree.XdStorageBTreeIndexResource;
import io.github.xdstorage.index.hash.XdStorageHashIndexResource;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.resource.IXdStorageInternalListener;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.resource.XdStorageResource;
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

public class XdStorageForeignKeyChangeRegistry {

    private final IXdStorageForeignKeyRegistry registry;

    // map [transactionId, countObjectWithoutId]
    private final Map<String, AtomicLong> insertWaitingIdentifiers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> deleteWaitingIdentifiers = new ConcurrentHashMap<>();

    public XdStorageForeignKeyChangeRegistry(IXdStorageForeignKeyRegistry registry) {
        this.registry = registry;
    }

    public void checkAllNotificationsReceived(IXdStorageTransaction transaction) {
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
        insertWaitingIdentifiers.remove(transaction.getTransactionId());
        deleteWaitingIdentifiers.remove(transaction.getTransactionId());
    }

    public void registerUpdateListener(XdStorageResource resource, Comparable<?> parentObjectId, Comparable<?> childObjectId,
                                       XdStorageObjectIdField parentIdField, IXdStorageTransaction transaction) {
        XdStorageObjectField clParentField = registry.getParentField();
        XdStorageClassInfo clChildInfo = registry.getChildClassInfo();

        incrementDeleteWaitingIdentifier(transaction);
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Update) {
                    Object pId = parentIdField.get(oldObject);
                    if (pId.equals(parentObjectId)
                            && isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                        try {
                            registry.registerForeignKey(parentObjectId, childObjectId, transaction);
                            decrementDeleteWaitingIdentifier(transaction);
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
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Update) {
                    Object pId = parentIdField.get(oldObject);
                    if (pId.equals(parentObjectId)
                            && isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                        try {
                            registry.registerForeignKey(parentObjectId, childObjectId, transaction);
                            decrementDeleteWaitingIdentifier(transaction);
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
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Update) {
                    Object pId = parentIdField.get(oldObject);
                    if (pId.equals(parentObjectId)
                            && isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                        try {
                            registry.registerForeignKey(parentObjectId, childObjectId, transaction);
                            decrementDeleteWaitingIdentifier(transaction);
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
                if (childObjectId.equals(clChildIdField.get(childObject))) return false;
            }
            return true;
        } else if (clParentFieldInfo.isCollection()) {
            Collection<?> collectionObjects = (Collection<?>) fieldObject;
            if (collectionObjects == null) return true;
            for (Object childObject : collectionObjects) {
                if (childObjectId.equals(clChildIdField.get(childObject))) return false;
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
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    Object cId = childIdField.get(oldObject);
                    if (cId.equals(childObjectId)) {
                        try {
                            registry.registerForeignKey(parentObjectId, childObjectId, transaction);
                            decrementDeleteWaitingIdentifier(transaction);
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
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    Object cId = oldObject == null ? null : childIdField.get(oldObject);
                    if (childObjectId.equals(cId)) {
                        try {
                            registry.registerForeignKey(parentObjectId, childObjectId, transaction);
                            decrementDeleteWaitingIdentifier(transaction);
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
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    Object cId = oldObject == null ? null : childIdField.get(oldObject);
                    if (childObjectId.equals(cId)) {
                        try {
                            registry.registerForeignKey(parentObjectId, childObjectId, transaction);
                            decrementDeleteWaitingIdentifier(transaction);
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
        resource.registerListener(new IXdStorageInternalListener() {
            @Override
            public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                        && (change == XdStorageObjectOperationType.Delete || change == XdStorageObjectOperationType.Update)) {
                    Object pId = parentIdField.get(oldObject);
                    if (pId.equals(parentObjectId)) {
                        try {
                            registry.registerForeignKey(parentObjectId, childObjectId, transaction);
                            decrementDeleteWaitingIdentifier(transaction);
                        } catch (XdStorageException e) {
                            throw new RuntimeException(e);
                        }
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
