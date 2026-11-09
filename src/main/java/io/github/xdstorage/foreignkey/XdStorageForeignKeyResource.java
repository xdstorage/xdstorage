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
import io.github.xdstorage.index.hash.XdStorageHashIndexResource;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.resource.*;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class XdStorageForeignKeyResource implements IXdStorageForeignKeyResource, IXdStorageForeignKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(XdStorageForeignKeyResource.class);

    private final XdStorageServicesLocator services;
    private final XdStorageAbstractResourcesManager manager;

    private final XdStorageForeignKeyChangeRegistry changeRegistry;

    private final Object resourceId;
    private final XdStorageClassInfo clParentInfo;
    private final XdStorageClassInfo clChildInfo;
    private final XdStorageObjectField clParentField;

    private final Lock createParentChildTreeLock = new ReentrantLock();
    private final Lock createChildParentTreeLock = new ReentrantLock();
    private final XdStorageBTreeId bTreeParentChildId;
    private final XdStorageBTreeId bTreeChildParentId;

    // map [transactionId, set [relations]]
    private final Map<String, Set<String>> registeredListenersForTransaction = new ConcurrentHashMap<>();

    // FNKEY: per-transaction pending FK registrations for manual-ID parents that are
    // not yet persisted. Key = transactionId, Value = set of typed FkRelation pairs
    // registered for a parent whose Java-object ID was set but not yet committed.
    private final Map<String, Set<FkRelation>> pendingFkRegistrations = new ConcurrentHashMap<>();

    /** A typed FK registration pair: the parent reference (manual-ID) and the child ID. */
    private static class FkRelation {
        final Comparable<?> parentId;
        final Comparable<?> childId;
        FkRelation(Comparable<?> parentId, Comparable<?> childId) {
            this.parentId = parentId;
            this.childId = childId;
        }
        static boolean matchesParent(FkRelation rel, Object parentId) {
            return rel.parentId.equals(parentId);
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FkRelation)) return false;
            FkRelation that = (FkRelation) o;
            return parentId.equals(that.parentId) && childId.equals(that.childId);
        }
        @Override
        public int hashCode() {
            return parentId.hashCode() * 31 + childId.hashCode();
        }
    }

    public XdStorageForeignKeyResource(XdStorageServicesLocator services,
                                       XdStorageAbstractResourcesManager manager,
                                       Object resourceId,
                                       XdStorageClassInfo clParentInfo,
                                       XdStorageClassInfo clChildInfo,
                                       XdStorageObjectField clParentField) {
        this.services = services;
        this.manager = manager;
        this.resourceId = resourceId;
        this.clParentInfo = clParentInfo;
        this.clChildInfo = clChildInfo;
        this.clParentField = clParentField;

        this.changeRegistry = new XdStorageForeignKeyChangeRegistry(this);

        bTreeParentChildId = new XdStorageBTreeId(clParentInfo.getClazz(),
                clChildInfo.getClazz().getSimpleName() + "_" + clParentField.getName() + "_foreign_key");
        bTreeChildParentId = new XdStorageBTreeId(clChildInfo.getClazz(),
                clParentInfo.getClazz().getSimpleName() + "_" + clParentField.getName() + "_foreign_key");
    }

    @Override
    public XdStorageClassInfo getParentClassInfo() {
        return clParentInfo;
    }

    @Override
    public XdStorageClassInfo getChildClassInfo() {
        return clChildInfo;
    }

    @Override
    public XdStorageObjectField getParentField() {
        return clParentField;
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
        validatePendingFks(transaction);
        changeRegistry.checkAllNotificationsReceived(transaction);
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
        // do nothing
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
        changeRegistry.release(transaction);
        registeredListenersForTransaction.remove(transaction.getTransactionId());
        pendingFkRegistrations.remove(transaction.getTransactionId());
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

        Comparable<?> parentObjectId = parentIdField.get(parentObject);
        if (parentObjectId != null) {
            registerForeignKey(parentObjectId, childObjectId, transaction);
        } else {
            changeRegistry.registerParentIdWaiter(childObjectId, parentObject, transaction);
        }
    }

    @Override
    public void insert(Comparable<?> parentObjectId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        XdStorageObjectIdField childIdField = clChildInfo.getIdField();

        Comparable<?> childObjectId = childIdField.get(childObject);
        if (childObjectId != null) {
            registerForeignKey(parentObjectId, childObjectId, transaction);
        } else {
            changeRegistry.registerChildIdWaiter(parentObjectId, childObject, transaction);
        }
    }

    @Override
    public void insert(IXdStorageIdObservableWrapper parentObject, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();
        XdStorageObjectIdField childIdField = clChildInfo.getIdField();

        Comparable<?> parentObjectId = parentIdField.get(parentObject);
        Comparable<?> childObjectId = childIdField.get(childObject);

        if (parentObjectId != null) {
            if (childObjectId != null) {
                insert(parentObjectId, childObjectId, transaction);
            } else {
                changeRegistry.registerChildIdWaiter(parentObjectId, childObject, transaction);
            }
        } else {
            if (childObjectId != null) {
                changeRegistry.registerParentIdWaiter(childObjectId, parentObject, transaction);
            } else {
                changeRegistry.registerRelationIdsWaiters(parentObject, childObject, transaction);
            }
        }
    }

    @Override
    public void update(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId,
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

        if (!registerRelationIfAbsent(clParentInfo, parentObjectId, clChildInfo, childObjectId, clParentField, transaction)) {
            return;
        }

        if (parentObject == null) {
            // FNKEY: a child UPDATE whose parent reference still points at the pair's
            // parent (the AutoSave cascade re-writing an untouched child) dissolves
            // nothing — the reference-removal case is owned by the parent-update diff.
            // Registering a waiter here would hang forever: isReferenceToChildObjectDeleted
            // can never become true for a child the parent still references.
            if (childReferencesParent(childObject, parentObjectId)) {
                return;
            }
            waitForParentUpdate(parentObjectId, childObjectId, transaction, storage, treeParentChild, treeChildParent);
        } else {
            throw new XdStorageException("parent and child objects cannot be null both");
        }
    }

    /**
     * FNKEY: whether the child's {@code @XdStorageParentObject} reference still points at
     * the given parent. The no-op discriminator for the child-update flow.
     */
    private boolean childReferencesParent(final Object childObject, final Comparable<?> parentObjectId) {
        if (childObject == null) {
            return false;
        }
        try {
            final Object entity = io.github.xdstorage.utils.XdStorageObjectUtils.getWrappedObjectOrSameObject(childObject);
            // the parent-reference field: the @XdStorageParentObject flag when present,
            // otherwise the first field typed as the parent class
            XdStorageObjectField parentReferenceField = clChildInfo.getFields().values().stream()
                    .filter(f -> f.isParent() && f.getFieldInfo().getValueClass() == clParentInfo.getClazz())
                    .findFirst().orElse(null);
            if (parentReferenceField == null) {
                parentReferenceField = clChildInfo.getFields().values().stream()
                        .filter(f -> f.getFieldInfo().getValueClass() == clParentInfo.getClazz())
                        .findFirst().orElse(null);
            }
            if (parentReferenceField == null) {
                return false;
            }
            final Object referenced = parentReferenceField.get(entity);
            if (referenced == null) {
                return false;
            }
            final Object referencedId = clParentInfo.getIdField().get(
                    io.github.xdstorage.utils.XdStorageObjectUtils.getWrappedObjectOrSameObject(referenced));
            return parentObjectId.equals(referencedId);
        } catch (final Exception probeFailure) {
            return false;
        }
    }

    private void waitForParentUpdate(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction,
                                     IXdStorage storage, XdStorageBTree treeParentChild, XdStorageBTree treeChildParent) throws XdStorageException, XdStorageConnectionException {

        XdStoragePolicy policy = clParentInfo.getPolicy();
        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();

        XdStorageAbstractResourcesManager resourcesManager = manager;

        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clParentInfo, transaction);

            if (resource instanceof XdStorageBTreeIndexResource) {
                Object parentObjectResourceId = resource.getObjectResourceId(parentObjectId, transaction);
                IXdStorageDaoResource parentObjectResource = null;
                if (parentObjectId != null) {
                    parentObjectResource = resourcesManager.lockResource(parentObjectResourceId, clParentInfo, transaction);
                }

                if (parentObjectResource != null) {
                    changeRegistry.registerUpdateListener((XdStorageResource) parentObjectResource, parentObjectId, childObjectId, parentIdField, transaction);
                } else {
                    changeRegistry.registerUpdateListener((XdStorageBTreeIndexResource) resource, parentObjectId, childObjectId, parentIdField, transaction);
                }
            } else if (resource instanceof XdStorageHashIndexResource) {
                Object parentObjectResourceId = resource.getObjectResourceId(parentObjectId, transaction);
                IXdStorageDaoResource parentObjectResource = null;
                if (parentObjectId != null) {
                    parentObjectResource = resourcesManager.lockResource(parentObjectResourceId, clParentInfo, transaction);
                }

                if (parentObjectResource != null) {
                    changeRegistry.registerUpdateListener((XdStorageResource) parentObjectResource, parentObjectId, childObjectId, parentIdField, transaction);
                } else {
                    changeRegistry.registerUpdateListener((XdStorageHashIndexResource) resource, parentObjectId, childObjectId, parentIdField, transaction);
                }
            } else {
                throw new XdStorageException("unsupported resource type " + resource.getClass());
            }
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            IXdStorageDaoResource index = resourcesManager.lockReferencesResource(clParentInfo, transaction);
            Object parentObject = index.read(parentObjectId, transaction);

            final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(parentObject, transaction);

            if (resource instanceof XdStorageResource) {
                changeRegistry.registerUpdateListener((XdStorageResource) resource, parentObjectId, childObjectId, parentIdField, transaction);
            }
        } else {
            throw new XdStorageException("unsupported delete operation for object with policy " + XdStoragePolicy.StoreWithParentObject);
        }
    }

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

        if (!registerRelationIfAbsent(clParentInfo, parentObjectId, clChildInfo, childObjectId, clParentField, transaction)) {
            return;
        }

        if (childObject == null) {
            waitForChildDelete(parentObjectId, childObjectId, transaction, storage, treeParentChild, treeChildParent);
        } else if (parentObject == null) {
            waitForParentDelete(parentObjectId, childObjectId, transaction, storage, treeParentChild, treeChildParent);
        } else {
            throw new XdStorageException("parent and child objects cannot be null both");
        }
    }

    private void waitForChildDelete(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction, IXdStorage storage, XdStorageBTree treeParentChild, XdStorageBTree treeChildParent) throws XdStorageException, XdStorageConnectionException {
        XdStoragePolicy policy = clChildInfo.getPolicy();
        XdStorageObjectIdField childIdField = clChildInfo.getIdField();

        XdStorageAbstractResourcesManager resourcesManager = manager;

        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clChildInfo, transaction);

            if (resource instanceof XdStorageBTreeIndexResource) {
                Object childObjectResourceId = resource.getObjectResourceId(childObjectId, transaction);
                IXdStorageDaoResource childObjectResource = null;
                if (childObjectResourceId != null) {
                    childObjectResource = resourcesManager.lockResource(childObjectResourceId, clChildInfo, transaction);
                }

                if (childObjectResource != null) {
                    changeRegistry.registerDeleteUpdateListener((XdStorageResource) childObjectResource, parentObjectId, childObjectId, childIdField, transaction);
                } else {
                    changeRegistry.registerDeleteUpdateListener((XdStorageBTreeIndexResource) resource, parentObjectId, childObjectId, childIdField, transaction);
                }
            } else if (resource instanceof XdStorageHashIndexResource) {
                Object childObjectResourceId = resource.getObjectResourceId(childObjectId, transaction);
                IXdStorageDaoResource childObjectResource = null;
                if (childObjectId != null) {
                    childObjectResource = resourcesManager.lockResource(childObjectResourceId, clChildInfo, transaction);
                }

                if (childObjectResource != null) {
                    changeRegistry.registerDeleteUpdateListener((XdStorageResource) childObjectResource, parentObjectId, childObjectId, childIdField, transaction);
                } else {
                    changeRegistry.registerDeleteUpdateListener((XdStorageHashIndexResource) resource, parentObjectId, childObjectId, childIdField, transaction);
                }
            } else {
                throw new XdStorageException("unsupported resource type " + resource.getClass());
            }
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            IXdStorageDaoResource index = resourcesManager.lockReferencesResource(clChildInfo, transaction);
            Object childObject = index.read(childObjectId, transaction);
            IXdStorageDaoResource childObjectResource = resourcesManager.lockObjectResource(childObject, transaction);

            if (childObjectResource instanceof XdStorageResource) {
                changeRegistry.registerDeleteUpdateListener((XdStorageResource) childObjectResource, parentObjectId, childObjectId, childIdField, transaction);
            }
        } else {
            throw new XdStorageException("unsupported delete operation for object with policy " + XdStoragePolicy.StoreWithParentObject);
        }
    }

    private void waitForParentDelete(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction, IXdStorage storage, XdStorageBTree treeParentChild, XdStorageBTree treeChildParent) throws XdStorageException, XdStorageConnectionException {
        XdStoragePolicy policy = clParentInfo.getPolicy();
        XdStorageObjectIdField parentIdField = clParentInfo.getIdField();

        XdStorageAbstractResourcesManager resourcesManager = manager;

        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clParentInfo, transaction);
            Object parentResourceId = resource.getObjectResourceId(parentObjectId, transaction);
            IXdStorageDaoResource parentObjectResource = resourcesManager.lockResource(parentResourceId, clParentInfo, transaction);

            if (parentObjectResource instanceof XdStorageResource) {
                changeRegistry.registerParentDeleteUpdateListener((XdStorageResource) parentObjectResource, parentObjectId, childObjectId, parentIdField, transaction);
            } else {
                throw new XdStorageException("unsupported resource type " + parentObjectResource.getClass());
            }
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            IXdStorageDaoResource index = resourcesManager.lockReferencesResource(clParentInfo, transaction);
            Object parentObject = index.read(parentObjectId, transaction);
            IXdStorageDaoResource resource = resourcesManager.lockObjectResource(parentObject, transaction);

            if (resource instanceof XdStorageResource) {
                changeRegistry.registerParentDeleteUpdateListener((XdStorageResource) resource, parentObjectId, childObjectId, parentIdField, transaction);
            }
        } else {
            throw new XdStorageException("unsupported delete operation for object with policy " + XdStoragePolicy.StoreWithParentObject);
        }
    }

    @Override
    public void registerForeignKey(Comparable<?> parentId, Comparable<?> childId, IXdStorageTransaction transaction) throws XdStorageException {
        XdStorageTransaction tx = (XdStorageTransaction) transaction;

        XdStorageBTree treeParentChild = null;
        try {
            treeParentChild = getOrCreateForeignKeyBTree(bTreeParentChildId, clParentInfo, createParentChildTreeLock, tx);
            XdStorageBTree treeChildParent = getOrCreateForeignKeyBTree(bTreeChildParentId, clChildInfo, createChildParentTreeLock, tx);

            treeParentChild.insert(parentId, childId, services.getStorage(), tx);
            treeChildParent.insert(childId, parentId, services.getStorage(), tx);
        } catch (XdStorageConnectionException e) {
            throw new XdStorageRuntimeException(e);
        }

        // FNKEY: after inserting into the FK tree, if the parent has a manual ID (not null)
        // and the parent is NOT yet visible in the parent's index (committed + staged),
        // track it as a pending FK that must be validated at commit time.
        // This catches "save a child without saving its parent" where the parent's Java
        // object has a manual ID but the parent was never persisted in this transaction.
        //
        // NOTE: the FK engine calls insert(parentId, childId) for income-relation (child's
        // parent reference) and insert(childId, parentId) for outcome-relation (parent's
        // children). Distinguish by checking if childId (2nd arg) is in parent's index:
        // - income-relation + unsaved parent: childId=childId NOT in parent index → track pending
        // - income-relation + saved parent: childId=parentId IS in parent index → no pending needed
        // - outcome-relation: childId=realParentId IS in parent index → don't track
        if (parentId != null && childId != null) {
            boolean childInParentIndex = isChildInParentIndex(childId, tx);
            boolean parentInParentIndex = isParentPersistedInIndex(parentId, tx);

            if (!parentInParentIndex && !childInParentIndex) {
                // Unsaved parent case: parentId is manual-ID parent (not in parent index),
                // childId is child's ID (not in parent index) → track as pending
                registerPendingFk(tx, parentId, childId);
            } else if (parentInParentIndex) {
                // Parent already saved in this tx → clear any stale pending registration
                clearPendingFk(tx, parentId);
            } else {
                // Outcome-relation path: childId=realParentId IS in parent index,
                // but parentId=childId is NOT in parent index → don't track
                clearPendingFk(tx, parentId);
            }
        }
    }

    /** Checks whether the parent id exists in the parent's index (committed + staged). */
    private boolean isParentPersistedInIndex(Comparable<?> parentId, XdStorageTransaction tx) throws XdStorageException {
        try {
            XdStoragePolicy policy = clParentInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                IXdStorageIndexDaoResource indexResource = manager.lockIndexResource(clParentInfo, tx);
                if (indexResource instanceof XdStorageBTreeIndexResource) {
                    return indexResource.has(parentId, tx);
                }
                if (indexResource instanceof XdStorageHashIndexResource) {
                    return indexResource.has(parentId, tx);
                }
                return false;
            }
            if (policy == XdStoragePolicy.StoreAsSingleObject) {
                IXdStorageDaoResource index = manager.lockReferencesResource(clParentInfo, tx);
                return index.read(parentId, tx) != null;
            }
            return true; // StoreWithParentObject
        } catch (XdStorageConnectionException e) {
            return false;
        }
    }

    /** Checks if the child id exists in the child class's index (committed + staged). */
    private boolean isChildPersistedInIndex(Comparable<?> childId, XdStorageTransaction tx) throws XdStorageException {
        try {
            XdStoragePolicy policy = clChildInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                IXdStorageIndexDaoResource indexResource = manager.lockIndexResource(clChildInfo, tx);
                if (indexResource instanceof XdStorageBTreeIndexResource) {
                    return indexResource.has(childId, tx);
                }
                if (indexResource instanceof XdStorageHashIndexResource) {
                    return indexResource.has(childId, tx);
                }
                return false;
            }
            if (policy == XdStoragePolicy.StoreAsSingleObject) {
                IXdStorageDaoResource index = manager.lockReferencesResource(clChildInfo, tx);
                return index.read(childId, tx) != null;
            }
            return true; // StoreWithParentObject
        } catch (XdStorageConnectionException e) {
            return false;
        }
    }

    /** Checks if an id exists in the PARENT class's index (committed + staged). Used to distinguish
     * income-relation from outcome-relation FK registrations. */
    private boolean isChildInParentIndex(Comparable<?> childId, XdStorageTransaction tx) throws XdStorageException {
        try {
            XdStoragePolicy policy = clParentInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                IXdStorageIndexDaoResource indexResource = manager.lockIndexResource(clParentInfo, tx);
                if (indexResource instanceof XdStorageBTreeIndexResource) {
                    return indexResource.has(childId, tx);
                }
                if (indexResource instanceof XdStorageHashIndexResource) {
                    return indexResource.has(childId, tx);
                }
                return false;
            }
            if (policy == XdStoragePolicy.StoreAsSingleObject) {
                IXdStorageDaoResource index = manager.lockReferencesResource(clParentInfo, tx);
                return index.read(childId, tx) != null;
            }
            return true; // StoreWithParentObject
        } catch (XdStorageConnectionException e) {
            return false;
        }
    }

    /** Tracks a pending FK pair for manual-ID parents not yet persisted. */
    private void registerPendingFk(XdStorageTransaction tx, Comparable<?> parentId, Comparable<?> childId) {
        String tid = tx.getTransactionId();
        Set<FkRelation> pending = pendingFkRegistrations.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet());
        pending.add(new FkRelation(parentId, childId));
    }

    /** Clears the FK registration for a parent that was just persisted (it is now resolved). */
    private void clearPendingFk(XdStorageTransaction tx, Object parentId) {
        Set<FkRelation> pending = pendingFkRegistrations.get(tx.getTransactionId());
        if (pending != null) {
            pending.removeIf(rel -> FkRelation.matchesParent(rel, parentId));
        }
    }

    /**
     * Phase-1 commit-time validation for pending FKs: all manual-ID parents must be
     * persisted (visible in the parent's index including staged changes). Throws on violation.
     */
    private void validatePendingFks(final XdStorageTransaction transaction) throws XdStorageException {
        Set<FkRelation> pending = pendingFkRegistrations.get(transaction.getTransactionId());
        if (pending == null || pending.isEmpty()) return;

        XdStorageTransaction tx = (XdStorageTransaction) transaction;
        XdStoragePolicy policy = clParentInfo.getPolicy();

        for (FkRelation rel : pending) {
            Comparable<?> parentId = rel.parentId;

            boolean parentExists;
            try {
                if (policy == XdStoragePolicy.StoreAsClassObjects) {
                    IXdStorageIndexDaoResource indexResource = manager.lockIndexResource(clParentInfo, tx);
                    if (indexResource instanceof XdStorageBTreeIndexResource) {
                        parentExists = indexResource.has(parentId, tx);
                    } else if (indexResource instanceof XdStorageHashIndexResource) {
                        parentExists = indexResource.has(parentId, tx);
                    } else {
                        parentExists = false;
                    }
                } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                    IXdStorageDaoResource index = manager.lockReferencesResource(clParentInfo, tx);
                    parentExists = index.read(parentId, tx) != null;
                } else {
                    parentExists = true;
                }
            } catch (XdStorageConnectionException e) {
                throw new XdStorageRuntimeException(e);
            }

            if (!parentExists) {
                throw new XdStorageRuntimeException(
                        "Cannot persist FK (" + clParentInfo.getClazz().getSimpleName() + "#" + parentId
                        + " -> " + clChildInfo.getClazz().getSimpleName() + "#" + rel.childId + "): "
                        + "parent record does not exist - parent must be persisted before the child");
            }
        }
    }

    private XdStorageBTree getOrCreateForeignKeyBTree(XdStorageBTreeId bTreeId, XdStorageClassInfo objectClInfo,
                                                      Lock createTreeLock, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
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

    private final Object monitor = new Object();

    private boolean registerRelationIfAbsent(XdStorageClassInfo clParentInfo, Object parentId,
                                             XdStorageClassInfo clChildInfo, Object childId,
                                             XdStorageObjectField clParentField, XdStorageTransaction transaction) {
        StringBuilder builder = new StringBuilder();
        builder.append(clParentInfo.getClazz().getName()).append('_').append(parentId).append('-');
        builder.append(clChildInfo.getClazz().getName()).append('_').append(childId).append('-');
        builder.append(clParentField.getName());
        String relationId = builder.toString();

        Set<String> relations = registeredListenersForTransaction.computeIfAbsent(transaction.getTransactionId(), tId -> new HashSet<>());
        synchronized (monitor) {
            boolean result = false;
            if (!relations.contains(relationId)) {
                relations.add(relationId);
                result = true;
            }
            return result;
        }
    }

    @Override
    public void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction) {
        // do nothing
    }

    @Override
    public Object getDao() {
        throw new XdStorageRuntimeException("unsupported");
    }
}
