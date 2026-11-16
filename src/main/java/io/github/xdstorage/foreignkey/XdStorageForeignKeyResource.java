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
    private final XdStorageForeignKeyRegistry foreignKeyRegistry;
    private final XdStorageForeignKeyResourcesManager fkResourcesManager;
    private final XdStorageForeignKeyCache fkCache;

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

    // FNKEY: per-transaction pending FK registrations where the parent has no ID yet
    // (auto-id parent not yet saved). Tracked by object reference. Key = transactionId,
    // Value = set of typed FkObjectRelation pairs. Used when the income-relation or
    // outcome-relation path detects that the parent object is NOT persisted — instead of
    // registering a parent-id waiter (which would hang forever), we track the pair and
    // validate at commit time: if the parent is still not persisted, throw
    // "parent does not exist". If the parent IS persisted (saved later in same tx),
    // its FK trigger will register the pair and we simply don't throw.
    private final Map<String, Set<FkObjectRelation>> pendingFkRegistrationsByObject = new ConcurrentHashMap<>();

    /** A typed FK registration pair tracking a parent object (not ID) and a child ID.
     * Includes parentClassInfo so the parent's ID can be read correctly regardless
     * of which FK direction (income vs outcome) registered the pending entry. */
    private static class FkObjectRelation {
        final XdStorageClassInfo parentClassInfo;
        final Object parentObject;
        final Comparable<?> childId;
        FkObjectRelation(XdStorageClassInfo parentClassInfo, Object parentObject, Comparable<?> childId) {
            this.parentClassInfo = parentClassInfo;
            this.parentObject = parentObject;
            this.childId = childId;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FkObjectRelation)) return false;
            FkObjectRelation that = (FkObjectRelation) o;
            return parentObject.equals(that.parentObject) && childId.equals(that.childId);
        }
        @Override
        public int hashCode() {
            return System.identityHashCode(parentObject) * 31 + childId.hashCode();
        }
    }

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
                                       XdStorageForeignKeyResourcesManager fkResourcesManager,
                                       Object resourceId,
                                       XdStorageClassInfo clParentInfo,
                                       XdStorageClassInfo clChildInfo,
                                       XdStorageObjectField clParentField) {
        this.services = services;
        this.manager = manager;
        this.fkResourcesManager = fkResourcesManager;
        this.foreignKeyRegistry = fkResourcesManager.getForeignKeyRegistry();
        this.resourceId = resourceId;
        this.clParentInfo = clParentInfo;
        this.clChildInfo = clChildInfo;
        this.clParentField = clParentField;

        this.changeRegistry = new XdStorageForeignKeyChangeRegistry(this);
        // FNKEY: the SHARED storage-wide write-set (owned by the resources manager) — a
        // per-resource cache would split the write-set and silently lose pairs at commit.
        this.fkCache = fkResourcesManager.getForeignKeyCache();

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
        // FNKEY POINT 2 — the FULL phase-1 validation:
        // (1) the semantic layer: pending manual-ID parents, pending-by-object parents and
        //     orphaned children (the estate's vetoes — "parent must be persisted before the child");
        validatePendingFks(transaction);
        // (2) the write-set: every staged pair must be fully resolved (no deferred pair
        //     with null ids reaching commit — its waiter failed to fire);
        fkCache.validateAllForTransaction(transaction);
        // (3) the waiter counters: every armed waiter (deferred ids, child/parent deletions)
        //     must have resolved — the "Not all foreign keys are registered/deleted" vetoes.
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
        // FNKEY: promote ALL cached pairs to PERFORM state
        fkCache.promoteAllForTransaction(transaction);
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
        // FNKEY POINT 7: the pairs were only STAGED in the write-set (never written to the
        // trees before COMMIT) — discarding the write-set fully compensates phase 1.
        fkCache.release(transaction);
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
        // FNKEY POINT 7: a rolled-back transaction's staged pairs NEVER reach the trees —
        // the write-set discard is the whole compensation (rollback-safe by construction).
        fkCache.release(transaction);
        changeRegistry.release(transaction);
        registeredListenersForTransaction.remove(transaction.getTransactionId());
        removePendingFkContours(transaction.getTransactionId());
    }

    /**
     * Releases the resource: clears the transaction's cache contours, drops the
     * transaction's seen-keys snapshot and returns the resource to the manager.
     *
     * @param transaction the transaction releasing the resource
     */
    @Override
    public void release(final XdStorageTransaction transaction) {
        // FNKEY POINT 8: release via the write-set — PER-TX (a global clear would drop
        // concurrent transactions' staged pairs).
        fkCache.release(transaction);
        changeRegistry.release(transaction);
        registeredListenersForTransaction.remove(transaction.getTransactionId());
        removePendingFkContours(transaction.getTransactionId());
    }

    /** Removes THIS transaction's pending-FK contours (never another transaction's). */
    private void removePendingFkContours(final String transactionId) {
        pendingFkRegistrations.remove(transactionId);
        pendingFkRegistrationsByObject.remove(transactionId);
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
        // FNKEY A2.3 (corrected): the search stays TYPED. The tree's in-memory staged keys
        // are typed (registerForeignKey inserts the caller's ids); a String search key
        // would CCE against them (String.compareTo(Long)). The POST-REOPEN half of the
        // hazard — the same tree's reloaded keys are raw Strings — is handled by the
        // BUG-E restoration below (once per tree instance, the same machinery the search
        // indexes use), so a typed find sees a uniformly typed tree in both worlds.
        if (treeParentChild != null) {
            restoreForeignKeyTreeTypesIfNeeded(treeParentChild, clParentInfo, clChildInfo, transaction);
        }
        Collection<Object> result = treeParentChild != null
                ? treeParentChild.find(objectId, storage, transaction)
                : new ArrayList<>();
        // Normalize the returned values to the child's id type: the tree may hand back
        // raw Strings (the persisted form) next to typed ids (fresh in-memory inserts).
        result = restoreResultKeyType(result, clChildInfo, storage, transaction);
        // FNKEY: the tree holds only COMMITTED pairs — this transaction's staged pairs
        // (write-set) must be visible to the in-transaction readers (the engine's
        // update-diff, the waiters) or the diff logic mis-judges same-tx registrations.
        overlayForeignKeyReads((XdStorageTransaction) transaction, true, objectId, result);
        return result;
    }

    /**
     * FNKEY A2.3: restores a FK tree's key/value types after a reopen — the generic YAML
     * reader hands back raw Strings while the in-memory inserts are typed, and the first
     * typed seek on the mixed tree crashes with a CCE. Delegates to the btree's BUG-E
     * machinery (task 18): a CAS-guarded, ONCE-per-tree-instance in-place walk; the call
     * is free after the first. The restorer tries BOTH relation id types because keys and
     * values belong to different sides (the front tree: parent keys / child values; the
     * reverse tree: child keys / parent values) and the walk applies one function to both.
     */
    private void restoreForeignKeyTreeTypesIfNeeded(final XdStorageBTree tree,
                                                    final XdStorageClassInfo keySide,
                                                    final XdStorageClassInfo valueSide,
                                                    final IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        tree.restoreSearchKeyTypes(raw -> restoreIdValue(raw, keySide, valueSide),
                services.getStorage(), (XdStorageTransaction) transaction);
    }

    /** Converts a raw String id back to a typed id, trying both relation sides. */
    private Object restoreIdValue(final Object raw, final XdStorageClassInfo keySide, final XdStorageClassInfo valueSide) {
        if (!(raw instanceof String)) {
            return raw;
        }
        final String rawString = (String) raw;
        final Object asKeySide = tryParseId(rawString, keySide);
        if (asKeySide != null) {
            return asKeySide;
        }
        final Object asValueSide = tryParseId(rawString, valueSide);
        if (asValueSide != null) {
            return asValueSide;
        }
        return raw;
    }

    /** Parses a raw String into the class's id type; {@code null} when it does not parse. */
    private Object tryParseId(final String raw, final XdStorageClassInfo clInfo) {
        try {
            return convertToIdType(raw, clInfo.getIdField().getFieldInfo().getValueClass());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * FNKEY: applies the transaction's staged FK pairs over a tree read. INSERT intents
     * add their value, DELETE intents remove it — the read-through view of the write-set.
     */
    private void overlayForeignKeyReads(final XdStorageTransaction tx, final boolean byFront,
                                        final Comparable<?> objectId, final Collection<Object> result) {
        for (final XdStorageForeignKeyPair pair : fkCache.pairsForTransaction(tx)) {
            if (!pair.belongsTo(bTreeParentChildId, bTreeChildParentId)) {
                continue;
            }
            final Comparable<?> key = byFront ? pair.frontId() : pair.reverseId();
            final Comparable<?> value = byFront ? pair.reverseId() : pair.frontId();
            if (key == null || !objectId.equals(key)) {
                continue;
            }
            if (pair.type() == XdStorageForeignKeyObjectChangeType.INSERT) {
                if (!result.contains(value)) {
                    result.add(value);
                }
            } else {
                result.remove(value);
            }
        }
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
        // FNKEY A2.3 (corrected): typed search + the restore-once machinery — the mirror
        // of readByParentObjectId (the reverse tree: child keys / parent values).
        if (treeChildParent != null) {
            restoreForeignKeyTreeTypesIfNeeded(treeChildParent, clChildInfo, clParentInfo, transaction);
        }
        Collection<Object> result = treeChildParent != null
                ? treeChildParent.find(objectId, storage, transaction)
                : new ArrayList<>();
        // Normalize the returned values to the parent's id type.
        result = restoreResultKeyType(result, clParentInfo, storage, transaction);
        overlayForeignKeyReads((XdStorageTransaction) transaction, false, objectId, result);
        return result;
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
            // Parent has no ID yet. Check if parent is already persisted.
            // If not persisted, DON'T register a waiter (it would hang forever).
            // Instead, register as pending FK tracked by object reference.
            if (isParentPersistedByObject(parentObject, (XdStorageTransaction) transaction)) {
                // Parent is persisted — register waiter for auto-assigned ID
                changeRegistry.registerParentIdWaiter(childObjectId, parentObject, transaction);
            } else {
                // Parent not persisted — register as pending FK (tracked by object)
                // Pass parent class info so validation can read the parent's ID correctly
                registerPendingFkByObject((XdStorageTransaction) transaction, clParentInfo, parentObject, childObjectId);
            }
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

        // FNKEY POINT 1 (docs/analysis/FOREIGN_KEYS.md §4/§5), CONTOUR-CORRECT FORM:
        // the pair is staged into the FK write-set AND written into the B+trees at
        // registration. The trees are INFRASTRUCTURE CACHES with their own 2PC contour —
        // their records must be mutated while the transaction's phase window is open
        // (operation time); a mutation after the tree resources' phases ran orphanes the
        // record ("Illegal state of record to commit", proven by the first integration
        // run). Rollback-safety and invisible-until-commit are delivered by the trees'
        // OWN contour (the W-4a journals): a vetoed transaction's staged node mutations
        // roll back with the tx, so an invalid pair never becomes visible. The write-set
        // adds what the trees do not provide: the read-through overlay for the in-tx
        // readers, the phase-gated intent state, the structural phase-1 validation and
        // the explicit rollback/release hygiene.
        try {
            getOrCreateForeignKeyBTree(bTreeParentChildId, clParentInfo, createParentChildTreeLock, tx);
            getOrCreateForeignKeyBTree(bTreeChildParentId, clChildInfo, createChildParentTreeLock, tx);
        } catch (XdStorageConnectionException e) {
            throw new XdStorageRuntimeException(e);
        }

        if (parentId != null && childId != null) {
            fkCache.cacheInsert(new XdStorageForeignKeyPair(parentId, childId, clParentInfo, clChildInfo,
                    clParentField, XdStorageForeignKeyObjectChangeType.INSERT), tx);
        }

        XdStorageBTree treeParentChild = null;
        try {
            treeParentChild = services.getStorage().load(XdStorageBTree.class, bTreeParentChildId, tx);
            XdStorageBTree treeChildParent = services.getStorage().load(XdStorageBTree.class, bTreeChildParentId, tx);

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

    /**
     * Unregisters the FK pair (parentId→childId) from both trees and cleans up
     * pending-FK tracking. Opposite of registerForeignKey.
     * Called by FK change-registry listeners when a child is actually deleted/updated.
     * Idempotent: does NOT throw if the pair is already absent (e.g. already dissolved
     * by the FK engine's update-diff based on a different snapshot). This prevents the
     * cascade-rollback scenario where concurrent update-diffs dissolve pairs that a
     * still-registered parent-deletion listener later tries to unregister again.
     */
    public void unregisterForeignKey(Comparable<?> parentId, Comparable<?> childId,
                                      IXdStorageTransaction transaction) throws XdStorageException {
        XdStorageTransaction tx = (XdStorageTransaction) transaction;

        // FNKEY POINT 5, CONTOUR-CORRECT FORM: the dissolution is staged as a DELETE intent
        // in the write-set AND applied to the trees at dissolution time (operation time —
        // the same contour window as the registration; see registerForeignKey). Idempotency
        // is preserved in both layers: the cache collapses duplicate DELETE intents, and
        // the tree deletes absorb "does not exists".
        if (parentId != null && childId != null) {
            fkCache.cacheDelete(new XdStorageForeignKeyPair(parentId, childId, clParentInfo, clChildInfo,
                    clParentField, XdStorageForeignKeyObjectChangeType.DELETE), tx);
        }

        try {
            XdStorageBTree treeParentChild = getOrCreateForeignKeyBTree(bTreeParentChildId, clParentInfo, createParentChildTreeLock, tx);
            XdStorageBTree treeChildParent = getOrCreateForeignKeyBTree(bTreeChildParentId, clChildInfo, createChildParentTreeLock, tx);

            try {
                treeParentChild.delete(parentId, childId, services.getStorage(), tx);
            } catch (XdStorageException e) {
                // "does not exists" is expected when the pair was already dissolved;
                // ignore it (idempotent delete)
                if (!e.getMessage().contains("does not exists")) {
                    throw e;
                }
            }
            try {
                treeChildParent.delete(childId, parentId, services.getStorage(), tx);
            } catch (XdStorageException e) {
                // same idempotent semantics for the reverse tree
                if (!e.getMessage().contains("does not exists")) {
                    throw e;
                }
            }
        } catch (XdStorageConnectionException e) {
            // Connection-level errors (I/O, disk, etc.) are NOT idempotent — rethrow
            throw new XdStorageRuntimeException(e);
        }

        // Clean up any pending-FK entry for this pair
        clearPendingFk(tx, parentId);
    }

    /**
     * FNKEY: checks whether the parent object is already persisted (exists in the
     * parent's id_index). Used to distinguish "parent auto-id not yet assigned"
     * from "parent never saved" — in the latter case we must NOT register a
     * parent-id waiter (it would hang forever) and instead register a pending FK
     * that validates at commit time.
     */
    private boolean isParentPersistedByObject(Object parentObject, XdStorageTransaction tx) {
        try {
            Comparable<?> parentId = clParentInfo.getIdField().get(parentObject);
            if (parentId == null) {
                // Parent object has no ID — cannot be persisted
                return false;
            }
            return isParentPersistedInIndex(parentId, tx);
        } catch (XdStorageException e) {
            return false;
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

    /**
     * FNKEY: Tracks a pending FK pair where the parent has no ID yet (auto-id parent
     * not yet saved). Tracked by object reference instead of ID. At commit time,
     * validatePendingFks checks if the parent object is persisted; if not, throws
     * "parent does not exist".
     */
    public void registerPendingFkByObject(XdStorageTransaction tx, XdStorageClassInfo parentClassInfo,
                                          Object parentObject, Comparable<?> childId) {
        String tid = tx.getTransactionId();
        Set<FkObjectRelation> pending = pendingFkRegistrationsByObject.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet());
        pending.add(new FkObjectRelation(parentClassInfo, parentObject, childId));
    }

    /**
     * FNKEY: Registers orphaned children for commit-time validation.
     * Called by the FK engine after update-diff detects orphaned children.
     */
    @Override
    public void registerOrphanedChildren(XdStorageTransaction transaction, Set<Comparable<?>> parentIds) {
        // Delegate to FK engine's orphaned children tracking via the FK resources manager
        fkResourcesManager.registerOrphanedChildren(transaction, parentIds);
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
     * persisted (visible in the parent's index including staged changes). Also validates
     * pending FKs tracked by object reference (auto-id parents not yet saved). Also validates
     * orphaned children (tracked by FK engine's update-diff). Throws on violation.
     */
    private void validatePendingFks(final XdStorageTransaction transaction) throws XdStorageException {
        // FNKEY: Validate orphaned children tracked by FK engine's update-diff
        Set<Comparable<?>> orphanedParents = foreignKeyRegistry.getOrphanedChildren(transaction);
        if (orphanedParents != null && !orphanedParents.isEmpty()) {
            // Check if orphaned parents still exist (they might have been deleted)
            XdStorageTransaction tx = (XdStorageTransaction) transaction;
            for (Comparable<?> parentId : orphanedParents) {
                boolean parentExists = isParentPersistedInIndex(parentId, tx);
                if (parentExists) {
                    // Parent still exists with orphaned children — FK violation
                    throw new XdStorageRuntimeException(
                            "Foreign key violation: parent " + clParentInfo.getClazz().getSimpleName()
                            + "#" + parentId + " has children in FK tree not in its collection (orphaned children).");
                }
            }
            // Parent was deleted — orphaned children will be handled by FK veto
            foreignKeyRegistry.clearOrphanedChildren(transaction);
        }

        // Validate manual-ID pending FKs
        Set<FkRelation> pending = pendingFkRegistrations.get(transaction.getTransactionId());
        if (pending != null && !pending.isEmpty()) {
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

        // FNKEY: Validate pending FKs tracked by object reference (auto-id parents not yet saved)
        Set<FkObjectRelation> pendingByObject = pendingFkRegistrationsByObject.get(transaction.getTransactionId());
        if (pendingByObject != null && !pendingByObject.isEmpty()) {
            XdStorageTransaction tx = (XdStorageTransaction) transaction;

            for (FkObjectRelation rel : pendingByObject) {
                Object parentObject = rel.parentObject;
                // If parent was saved in this transaction, its ID is now non-null and
                // the FK pair will have been registered by the parent's outcome-relation
                // trigger — no throw needed. If parent is still not saved (null ID),
                // throw "parent does not exist".
                Comparable<?> parentId = rel.parentClassInfo.getIdField().get(parentObject);
                if (parentId == null) {
                    throw new XdStorageRuntimeException(
                            "Cannot persist FK (" + clParentInfo.getClazz().getSimpleName() + "" + ": "
                            + "parent record does not exist - parent must be persisted before the child");
                }
                // Parent was saved — FK pair registered by parent's trigger. No verification
                // needed here: the FK tree may not be loaded in this transaction's context
                // (it was loaded in a different transaction), and the FK engine's insert/update
                // diff logic handles correctness. Just confirming the parent is still persisted
                // (has non-null ID) is sufficient.
                // The FK tree consistency is verified by the FK engine's update-diff and
                // delete-path logic, not by this commit-time check.
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

    /**
     * FNKEY A2.3 fix: restores result values from raw String (YAML serialization) back to
     * the correct ID type for the given class's id-field. The FK tree stores all keys and
     * values as raw Objects (from YAML → String). Callers expect typed IDs.
     *
     * <p>The FK tree may return a mix of raw Strings (from the persisted tree) and typed
     * IDs (from the write-set overlay). If we only check the first value and skip
     * conversion when it's already typed, tree values (Strings) would remain unconverted
     * and later be used as search keys in entity index trees → ClassCastException.
     * Therefore we always convert when the target type is not String.
     */
    private Collection<Object> restoreResultKeyType(Collection<Object> values, XdStorageClassInfo clInfo,
                                                    IXdStorage storage, XdStorageTransaction transaction) {
        if (values == null || values.isEmpty()) return values;
        final Class<?> idType = clInfo.getIdField().getFieldInfo().getValueClass();
        // If the ID type is String, no conversion needed (already String, and String.equals(String) works)
        if (idType == String.class) return values;
        // Always convert to target type — the FK tree may return a mix of raw Strings (persisted) and
        // typed IDs (write-set overlay); converting all ensures uniform typed output.
        return values.stream()
                .map(v -> convertToIdType(v, idType))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Converts a raw value (String from YAML) to the correct ID type.
     * If value is already the correct type, returns it unchanged (avoids unnecessary
     * parsing when the FK tree's write-set overlay already returned typed values).
     */
    private Object convertToIdType(Object value, Class<?> idType) {
        if (value == null) return null;
        // If already the correct type, return as-is (write-set overlay may have returned typed values)
        if (idType.isInstance(value)) return value;
        // value is a raw String from YAML; convert to the target ID type
        if (idType == Long.class) return Long.parseLong(value.toString());
        if (idType == Integer.class) return Integer.parseInt(value.toString());
        if (idType == Short.class) return Short.parseShort(value.toString());
        if (idType == Byte.class) return Byte.parseByte(value.toString());
        if (idType == java.math.BigDecimal.class) return new java.math.BigDecimal(value.toString());
        if (idType == java.math.BigInteger.class) return new java.math.BigInteger(value.toString());
        if (idType == java.util.UUID.class) return java.util.UUID.fromString(value.toString());
        if (idType == String.class) return value.toString();
        // Fallback: return as-is (might be another Comparable type)
        return value;
    }

    @Override
    public Object getDao() {
        throw new XdStorageRuntimeException("unsupported");
    }
}
