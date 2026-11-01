package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.index.btree.XdStorageBTreeIndexResource;
import io.github.xdstorage.index.hash.XdStorageAbstractHashIndexResource;
import io.github.xdstorage.index.hash.XdStorageHashIndexRecord;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.resource.*;
import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class XdStorageForeignKeyResource implements IXdStorageForeignKeyResource {

    private static final Logger log = LoggerFactory.getLogger(XdStorageForeignKeyResource.class);

    private final XdStorageServicesLocator services;

    private final Object resourceId;
    private final XdStorageClassInfo clParentInfo;
    private final XdStorageClassInfo clChildInfo;
    private final XdStorageObjectField clParentField;

    private final Lock createParentChildTreeLock = new ReentrantLock();
    private final Lock createChildParentTreeLock = new ReentrantLock();
    private final XdStorageBTreeId bTreeParentChildId;
    private final XdStorageBTreeId bTreeChildParentId;

    // map [transactionId, countObjectWithoutId]
    private final Map<String, AtomicLong> insertWaitingIdentifiers = new ConcurrentHashMap<>();

    private final Map<String, AtomicLong> deleteWaitingIdentifiers = new ConcurrentHashMap<>();

    public XdStorageForeignKeyResource(XdStorageServicesLocator services,
                                       Object resourceId,
                                       XdStorageClassInfo clParentInfo,
                                       XdStorageClassInfo clChildInfo,
                                       XdStorageObjectField clParentField) {
        this.services = services;
        this.resourceId = resourceId;
        this.clParentInfo = clParentInfo;
        this.clChildInfo = clChildInfo;
        this.clParentField = clParentField;

        bTreeParentChildId = new XdStorageBTreeId(clParentInfo.getClazz(),
                clChildInfo.getClazz().getSimpleName() + "_" + clParentField.getName() + "_foreign_key");
        bTreeChildParentId = new XdStorageBTreeId(clChildInfo.getClazz(),
                clParentInfo.getClazz().getSimpleName() + "_" + clParentField.getName() + "_foreign_key");
    }

    /** @return the storage-wide unique id of this resource */
    @Override
    public Object getResourceId() {
        return resourceId;
    }

    /**
     * @param transaction the transaction to inspect
     * @return {@code true} when the transaction has staged changes in this resource
     * @throws XdStorageException on a storage-level failure
     */
    @Override
    public boolean hasChanges(final XdStorageTransaction transaction) throws XdStorageException {
        return true;
    }

    @Override
    public Class<?> getObjectsClass() {
        return clParentInfo.getClass();
    }

    @Override
    public long getObjectsCount() {
        return 0;
    }

    /**
     * Acquires the tree commit lock, waiting up to the transaction's timeout. Fails loudly
     * with {@code XdStorageRuntimeException} when the lock cannot be acquired in time —
     * deliberately breaking any commit-cycle deadlock instead of waiting forever — and
     * re-asserts the thread's interrupt flag when interrupted while waiting.
     *
     * @param transaction the transaction acquiring the lock (its timeout bounds the wait;
     *                    {@code null} means the default 3-second bound)
     */
    @Override
    public void lockForCommit(final XdStorageTransaction transaction) {
        // do nothing
    }

    /**
     * Releases the tree commit lock acquired by {@code #lockForCommit} (no-op when the
     * current thread does not hold it).
     *
     * @param transaction the transaction releasing the lock
     */
    @Override
    public void unlockAfterCommit(final XdStorageTransaction transaction) {
        // do nothing
    }

    /**
     * Returns the path of this resource's backing file.
     *
     * @return the path of the backing file of this resource (the resource id as a string)
     */
    public String getFileName() {
        return resourceId.toString();
    }

    private Collection<Object> readAsObjects() {
        return Collections.emptyList();
    }

    private void writeObjects(final Collection<Object> objects) {
        // do nothing
    }

    /**
     * Prepares the resource for use: on the first call loads the backing file into the
     * tree-object cache (double-checked under the resource lock), later calls are no-ops.
     *
     * @param transaction the transaction the resource is prepared for
     * @throws XdStorageRuntimeException when the backing file exists but cannot be read —
     *         treated as an invalid database state
     */
    @Override
    public void prepare(final XdStorageTransaction transaction) {
        // do nothing
    }

    /**
     * Phase 1 of the two-phase commit: when the transaction has staged changes, durably
     * persists the staged tree objects via a crash-safe atomic file replace (without
     * making them visible to other transactions).
     *
     * @param transaction the committing transaction
     * @param record      the collector the phase fills with the staged changes for phase 2
     * @throws XdStorageException on a storage-level failure
     */
    @Override
    public void performFirstPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        // do nothing
    }

    /**
     * Phase 2 of the two-phase commit: publishes the phase-1 persisted changes into the
     * tree-object cache, making them visible to subsequent transactions.
     *
     * @param transaction the committing transaction
     * @param record      the collector filled by phase 1
     * @throws XdStorageException on a storage-level failure
     */
    @Override
    public void performSecondPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
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
     * Compensates an already-persisted phase 1 after the overall commit failed: rolls back
     * the staged changes in the cache, rewrites the backing file from the restored
     * (pre-transaction) state and drops the transaction's seen-keys snapshot.
     *
     * @param transaction the failed transaction
     * @param changes     the staged changes the first phase persisted
     * @throws XdStorageException on a storage-level failure
     */
    @Override
    public void rollbackPerformingFirstPhaseCommit(XdStorageTransaction transaction, Collection changes) throws XdStorageException {
        // do nothing
    }

    /**
     * Discards the transaction's staged changes in this resource (pre-commit rollback);
     * the persistent state is untouched, and the transaction's seen-keys snapshot is
     * dropped.
     *
     * @param transaction the transaction being rolled back
     * @throws XdStorageException on a storage-level failure
     */
    @Override
    public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
        // do nothing
    }

    /**
     * Releases the resource: clears the transaction's cache contours, drops the
     * transaction's seen-keys snapshot and returns the resource to the manager.
     *
     * @param transaction the transaction releasing the resource
     */
    @Override
    public void release(final XdStorageTransaction transaction) {
        insertWaitingIdentifiers.remove(transaction.getTransactionId());
        deleteWaitingIdentifiers.remove(transaction.getTransactionId());
    }

    /**
     * @param objectId
     * @param transaction
     * @return Returns collection of child identifiers.
     * @throws XdStorageException
     * @throws XdStorageConnectionException
     */
    @Override
    public Collection<Object> readByParentObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorage storage = services.getStorage();
        XdStorageBTree treeParentChild = storage.load(XdStorageBTree.class, bTreeParentChildId, transaction);
        return treeParentChild != null ? treeParentChild.find(objectId, storage, transaction) : new ArrayList<>();
    }

    /**
     *
     * @param objectId
     * @param transaction
     * @return Returns collection of parent identifiers.
     * @throws XdStorageException
     * @throws XdStorageConnectionException
     */
    @Override
    public Collection<Object> readByChildObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorage storage = services.getStorage();
        XdStorageBTree treeChildParent = storage.load(XdStorageBTree.class, bTreeChildParentId, transaction);
        return treeChildParent != null ? treeChildParent.find(objectId, storage, transaction) : new ArrayList<>();
    }

    @Override
    public void insert(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        registerForeignKey(parentObjectId, childObjectId, transaction);
    }

    @Override
    public void insert(IXdStorageIdObservableWrapper parentObject, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();

        Object parentObjectId = parentIdField.get(parentObject);
        if (parentObjectId != null) {
            registerForeignKey(parentObjectId, childObjectId, transaction);
        } else {
            registerParentIdWaiter(childObjectId, parentObject, transaction);
        }
    }

    @Override
    public void insert(Comparable<?> parentObjectId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        XdStorageObjectIdField childIdField = clChildInfo.getIdField();

        Object childObjectId = childIdField.get(childObject);
        if (childObjectId != null) {
            registerForeignKey(parentObjectId, childObjectId, transaction);
        } else {
            registerChildIdWaiter(parentObjectId, childObject, transaction);
        }
    }

    @Override
    public void insert(IXdStorageIdObservableWrapper parentObject, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();
        XdStorageObjectIdField childIdField = clChildInfo.getIdField();

        Object parentObjectId = parentIdField.get(parentObject);
        Object childObjectId = childIdField.get(childObject);

        if (parentObjectId != null) {
            if (childObjectId != null) {
                registerForeignKey(parentObjectId, childObjectId, transaction);
            } else {
                registerChildIdWaiter(parentObjectId, childObject, transaction);
            }
        } else {
            if (childObjectId != null) {
                registerParentIdWaiter(childObjectId, parentObject, transaction);
            } else {
                registerRelationIdsWaiters(parentObject, childObject, transaction);
            }
        }
    }

//    @Override
//    public void delete(Object parentObject, Object childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
//        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();
//        XdStorageObjectIdField childIdField = clChildInfo.getIdField();
//
//        Object parentObjectId = parentIdField.get(parentObject);
//        Object childObjectId = childIdField.get(childObject);
//        if (parentObjectId != null) {
//            if (childObjectId != null) {
//                unregisterForeignKey(parentObjectId, childObjectId, transaction);
//            } else {
//                throw new XdStorageException("implement me");
//            }
//        } else {
//            throw new XdStorageException("implement me");
//        }
//    }
//
//    @Override
//    public void delete(Object parentObject, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
//        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();
//
//        Object parentObjectId = parentIdField.get(parentObject);
//        if (parentObjectId != null) {
//            unregisterForeignKey(parentObjectId, childObjectId, transaction);
//        } else {
//            throw new XdStorageException("implement me");
//        }
//    }
//
//    @Override
//    public void delete(Comparable<?> parentObjectId, Object childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
//        XdStorageObjectIdField childIdField = clChildInfo.getIdField();
//
//        Object childObjectId = childIdField.get(childObject);
//        if (childObjectId != null) {
//            unregisterForeignKey(parentObjectId, childObjectId, transaction);
//        } else {
//            throw new XdStorageException("implement me");
//        }
//    }

    @Override
    public void delete(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId,
                       XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

        IXdStorage storage = services.getStorage();
        XdStorageBTree treeParentChild = storage.load(XdStorageBTree.class, bTreeParentChildId, transaction);
        if (treeParentChild == null) {
            throw new XdStorageRuntimeException("foreign key for relation parentClass " +
                    clParentInfo.getClazz().getSimpleName() + " and child class " +
                    clChildInfo.getClazz().getSimpleName() + " does not exist");
        }
        XdStorageBTree treeChildParent = storage.load(XdStorageBTree.class, bTreeChildParentId, transaction);
        if (treeChildParent == null) {
            throw new XdStorageRuntimeException("foreign key for relation parentClass " +
                    clParentInfo.getClazz().getSimpleName() + " and child class " +
                    clChildInfo.getClazz().getSimpleName() + " does not exist");
        }

        // registering listeners
        XdStoragePolicy policy = clParentInfo.getPolicy();
        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();

        XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clParentInfo, transaction);

            if (resource instanceof XdStorageAbstractHashIndexResource) {
                incrementDeleteWaitingIdentifier(transaction);
                ((XdStorageAbstractHashIndexResource) resource).registerListener(new IXdStorageInternalListener() {
                    @Override
                    public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                        if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Delete) {
                            if (oldObject instanceof XdStorageHashIndexRecord
                                    && ((XdStorageHashIndexRecord) oldObject).getObjectId().equals(parentObjectId)) {
                                try {
                                    treeParentChild.delete(parentObjectId, childObjectId, storage, transaction);
                                    treeChildParent.delete(childObjectId, parentObjectId, storage, transaction);
                                    decrementDeleteWaitingIdentifier(transaction);
                                } catch (XdStorageException | XdStorageConnectionException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }
                }, transaction);
            } else if (resource instanceof XdStorageBTreeIndexResource) {
                incrementDeleteWaitingIdentifier(transaction);
                ((XdStorageBTreeIndexResource) resource).registerListener(new IXdStorageInternalListener() {
                    @Override
                    public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                        if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Delete) {
                            Object pId = parentIdField.get(oldObject);
                            if (pId.equals(parentObjectId)) {
                                try {
                                    treeParentChild.delete(parentObjectId, childObjectId, storage, transaction);
                                    treeChildParent.delete(childObjectId, parentObjectId, storage, transaction);
                                    decrementDeleteWaitingIdentifier(transaction);
                                } catch (XdStorageException | XdStorageConnectionException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }
                }, transaction);
            }
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            if (parentObject == null) {
                IXdStorageDaoResource index = resourcesManager.lockReferencesResource(clParentInfo, transaction);
                parentObject = index.read(parentObjectId, transaction);
            }

            final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(parentObject, transaction);

            if (resource instanceof XdStorageResource) {
                incrementDeleteWaitingIdentifier(transaction);
                ((XdStorageResource) resource).registerListener(new IXdStorageInternalListener() {
                    @Override
                    public void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change, Object oldObject, Object newObject, IXdStorageTransaction transaction) {
                        if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT && change == XdStorageObjectOperationType.Delete) {
                            Object pId = parentIdField.get(oldObject);
                            if (pId.equals(parentObjectId)) {
                                try {
                                    treeParentChild.delete(parentObjectId, childObjectId, storage, transaction);
                                    treeChildParent.delete(childObjectId, parentObjectId, storage, transaction);
                                    decrementDeleteWaitingIdentifier(transaction);
                                } catch (XdStorageException | XdStorageConnectionException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }
                }, transaction);
            }
        } else {
            throw new XdStorageException("unsupported delete operation for object with policy " + XdStoragePolicy.StoreWithParentObject);
        }
    }

    private void registerForeignKey(Object parentId, Object childId, XdStorageTransaction transaction) throws XdStorageConnectionException, XdStorageException {
        XdStorageBTree treeParentChild = getOrCreateForeignKeyBTree(bTreeParentChildId, clParentInfo, createParentChildTreeLock, transaction);
        XdStorageBTree treeChildParent = getOrCreateForeignKeyBTree(bTreeChildParentId, clChildInfo, createChildParentTreeLock, transaction);

        treeParentChild.insert((Comparable<?>) parentId, childId, services.getStorage(), transaction);
        treeChildParent.insert((Comparable<?>) childId, parentId, services.getStorage(), transaction);
    }

//    private void unregisterForeignKey(Object parentId, Object childId, XdStorageTransaction transaction) throws XdStorageConnectionException, XdStorageException {
//        XdStorageBTree treeParentChild = getOrCreateForeignKeyBTree(bTreeParentChildId, clParentInfo, createParentChildTreeLock, transaction);
//        XdStorageBTree treeChildParent = getOrCreateForeignKeyBTree(bTreeChildParentId, clChildInfo, createChildParentTreeLock, transaction);
//
//        treeParentChild.delete((Comparable<?>) parentId, (Comparable<?>) childId, services.getStorage(), transaction);
//        treeChildParent.delete((Comparable<?>) childId, (Comparable<?>) parentId, services.getStorage(), transaction);
//    }

    private void registerChildIdWaiter(Object parentId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) {
        incrementInsertWaitingIdentifier(transaction);
        childObject.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
                try {
                    registerForeignKey(parentId, childId, transaction);
                    decrementInsertWaitingIdentifier(transaction);
                } catch (XdStorageException | XdStorageConnectionException e) {
                    throw new XdStorageRuntimeException(e);
                }
            }
        });
    }

    private void registerParentIdWaiter(Object childId, IXdStorageIdObservableWrapper parentObject, XdStorageTransaction transaction) {
        incrementInsertWaitingIdentifier(transaction);
        parentObject.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object parentId) {
                try {
                    registerForeignKey(parentId, childId, transaction);
                    decrementInsertWaitingIdentifier(transaction);
                } catch (XdStorageException | XdStorageConnectionException e) {
                    throw new XdStorageRuntimeException(e);
                }
            }
        });
    }

    private void registerRelationIdsWaiters(IXdStorageIdObservableWrapper parentObject, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) {
        Object[] relationIds = new Object[2];
        incrementInsertWaitingIdentifier(transaction);
        parentObject.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object parentId) {
                relationIds[0] = parentId;
                if (relationIds[1] != null) {
                    try {
                        registerForeignKey(relationIds[0], relationIds[1], transaction);
                        decrementInsertWaitingIdentifier(transaction);
                    } catch (XdStorageException | XdStorageConnectionException e) {
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
                        registerForeignKey(relationIds[0], relationIds[1], transaction);
                        decrementInsertWaitingIdentifier(transaction);
                    } catch (XdStorageException | XdStorageConnectionException e) {
                        throw new XdStorageRuntimeException(e);
                    }
                }
            }
        });
    }

    private XdStorageBTree getOrCreateForeignKeyBTree(XdStorageBTreeId bTreeId, XdStorageClassInfo objectClInfo,
                                                      Lock createTreeLock, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorage storage = services.getStorage();
        XdStorageBTree tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
        if (tree == null) {
            // ФАЗА 3B: реестр канонических инстансов — живое дерево могло потерять запись кэша
            // (откат создателя/eviction); граф остается каноническим — восстанавливаем ссылку
            // ВМЕСТО создания конкурентного второго дерева.
            tree = (XdStorageBTree) services.getCanonicalInstances().get(XdStorageBTree.class, bTreeId);
            if (tree != null) {
                log.warn("id_index tree {} adopted from the canonical instance registry (cache record lost)", bTreeId);
            }
        }
        if (tree == null) {
            createTreeLock.lock();
            try {
                tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
                if (tree == null) {
                    tree = (XdStorageBTree) services.getCanonicalInstances().get(XdStorageBTree.class, bTreeId);
                    if (tree != null) {
                        log.warn("id_index tree {} restored from the canonical instance registry (its cache record was lost"
                                + " to a creator rollback/eviction)", bTreeId);
                    }
                }
                if (tree == null) {
                    log.info("id_index tree {} created for class {}", bTreeId, objectClInfo.getClazz().getName());
                    tree = new XdStorageBTree(bTreeId, true, objectClInfo.getIndexFillingValue());
                    storage.save(tree, transaction);
                }
            } finally {
                createTreeLock.unlock();
            }
        }
        return tree;
    }

    private void incrementInsertWaitingIdentifier(IXdStorageTransaction transaction) {
        insertWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .incrementAndGet();
    }

    private void decrementInsertWaitingIdentifier(IXdStorageTransaction transaction) {
        insertWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .decrementAndGet();
    }

    private void incrementDeleteWaitingIdentifier(IXdStorageTransaction transaction) {
        deleteWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .incrementAndGet();
    }

    private void decrementDeleteWaitingIdentifier(IXdStorageTransaction transaction) {
        deleteWaitingIdentifiers.computeIfAbsent(transaction.getTransactionId(), tId -> new AtomicLong())
                .decrementAndGet();
    }

    @Override
    public Object getDao() {
        throw new XdStorageRuntimeException("unsupported");
    }
}
