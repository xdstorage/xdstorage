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

    // FNKEY (docs/tasks/2.1.0/42): delete-invariant expectations, per transaction.
    // deletedChildren — child ids deleted in the tx (identity of the delete flow);
    // danglingChildren — children alive whose referencing parent was deleted in the tx
    // (the child MUST be deleted by commit or the veto fires);
    // orphanedEntries — "parentId#childId" pairs whose child was deleted while the entry
    // still existed (the entry MUST be dissolved by commit or the veto fires).
    private final Map<String, java.util.Set<Comparable<?>>> deletedChildren = new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<Comparable<?>>> danglingChildren = new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<String>> orphanedEntries = new ConcurrentHashMap<>();
    // FNKEY: child ids whose INSERT declared a parent relation that the parent's save
    // must register within the same transaction (the child-alone-save invariant; also
    // armed by the deferred-id child waiter when the parent id never lands).
    private final Map<String, java.util.Set<Comparable<?>>> expectedChildInserts = new ConcurrentHashMap<>();

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

        // FNKEY (docs/tasks/2.1.0/42): the delete-invariant veto — every expectation must
        // be resolved by the commit. The violation is a DESIGNED refusal: it marks the
        // transaction rollback-only and propagates out of the commit call (the manager
        // rethrows XdStorageForeignKeyViolationException after compensation).
        final java.util.Set<Comparable<?>> dangling = danglingChildren.get(transaction.getTransactionId());
        if (dangling != null && !dangling.isEmpty()) {
            final XdStorageForeignKeyViolationException violation = new XdStorageForeignKeyViolationException(
                    transaction.getTransactionId(), relationName(),
                    "Foreign key violated for relation " + relationName()
                            + ": the parent was deleted but its children were not (tx "
                            + transaction.getTransactionId() + ", not deleted children: " + dangling + ")");
            transaction.markRollbackOnly("foreign key violation: " + relationName(), violation);
            throw violation;
        }
        final java.util.Set<String> orphaned = orphanedEntries.get(transaction.getTransactionId());
        if (orphaned != null && !orphaned.isEmpty()) {
            final XdStorageForeignKeyViolationException violation = new XdStorageForeignKeyViolationException(
                    transaction.getTransactionId(), relationName(),
                    "Foreign key violated for relation " + relationName()
                            + ": a child was deleted while its parent still references it (tx "
                            + transaction.getTransactionId() + ", unresolved relations: " + orphaned + ")");
            transaction.markRollbackOnly("foreign key violation: " + relationName(), violation);
            throw violation;
        }
        final java.util.Set<Comparable<?>> expectedChildren = expectedChildInserts.get(transaction.getTransactionId());
        if (expectedChildren != null && !expectedChildren.isEmpty()) {
            final XdStorageForeignKeyViolationException violation = new XdStorageForeignKeyViolationException(
                    transaction.getTransactionId(), relationName(),
                    "Foreign key violated for relation " + relationName()
                            + ": a child was saved but its parent never registered the relation in this transaction (tx "
                            + transaction.getTransactionId() + ", unresolved children: " + expectedChildren + ")");
            transaction.markRollbackOnly("foreign key violation: " + relationName(), violation);
            throw violation;
        }
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
        deletedChildren.remove(transaction.getTransactionId());
        danglingChildren.remove(transaction.getTransactionId());
        orphanedEntries.remove(transaction.getTransactionId());
        expectedChildInserts.remove(transaction.getTransactionId());
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
            if (parentObjectId == null) {
                // FNKEY: the child was saved while its declared parent had no identifier
                // and nobody registered the pair — arm the child-insert expectation (the
                // parent's save in this transaction must register the pair, or the veto).
                expectedChildInserts.computeIfAbsent(transaction.getTransactionId(), tId -> ConcurrentHashMap.newKeySet())
                        .add((Comparable<?>) childObjectId);
                return;
            }
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

    /**
     * FNKEY (docs/tasks/2.1.0/42): removes the (parent, child) relation pair from BOTH
     * persistent trees — the immediate, transactional primitive (the trees' mutations are
     * staged changes of the active transaction; the physical flush happens at the commit
     * phases). On top of the blind removal the method maintains the delete-invariant
     * expectations:
     * <ul>
     *   <li>a pair removal resolves a pending orphaned-entry expectation for that pair
     *       (the child was deleted first; the parent side followed);</li>
     *   <li>when the PARENT side dissolves the relation while the child is NOT deleted in
     *       this transaction (parentObject != null on a parent-anchored relation), the
     *       child becomes dangling — it must be deleted within the same transaction or
     *       the commit veto fires.</li>
     * </ul>
     *
     * @param parentObject   the parent-side object when the PARENT side drives the
     *                       dissolution (deleted/updated), {@code null} for the blind
     *                       pair removal
     * @param parentObjectId the parent's identifier
     * @param childObject    the child-side object when known, {@code null} otherwise
     * @param childObjectId  the child's identifier
     * @param transaction    the owning transaction
     * @throws XdStorageException when either foreign-key tree does not exist
     */
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

        treeParentChild.delete(parentObjectId, childObjectId, storage, transaction);
        treeChildParent.delete(childObjectId, parentObjectId, storage, transaction);

        resolveOrphanedEntry(parentObjectId, childObjectId, transaction);

        if (parentObject != null && !isChildAnchoredRelation() && !isChildDeleted(childObjectId, transaction)) {
            // FNKEY: the PARENT side dissolves the relation while the child survives —
            // the child is now dangling; it must be deleted within this transaction.
            danglingChildren.computeIfAbsent(transaction.getTransactionId(), tId -> ConcurrentHashMap.newKeySet())
                    .add(childObjectId);
        }
    }

    /** FNKEY: {@inheritDoc} — marks the child deleted and resolves its dangling expectation. */
    @Override
    public void markChildDeleted(Comparable<?> childObjectId, XdStorageTransaction transaction) {
        deletedChildren.computeIfAbsent(transaction.getTransactionId(), tId -> ConcurrentHashMap.newKeySet())
                .add(childObjectId);
        danglingChildren.computeIfAbsent(transaction.getTransactionId(), tId -> ConcurrentHashMap.newKeySet())
                .remove(childObjectId);
    }

    /** FNKEY: whether the child was already deleted in this transaction (per the engine's flow). */
    private boolean isChildDeleted(Comparable<?> childObjectId, XdStorageTransaction transaction) {
        final java.util.Set<Comparable<?>> deleted = deletedChildren.get(transaction.getTransactionId());
        return deleted != null && deleted.contains(childObjectId);
    }

    /** FNKEY: {@inheritDoc} */
    @Override
    public void registerOrphanedEntry(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction) {
        orphanedEntries.computeIfAbsent(transaction.getTransactionId(), tId -> ConcurrentHashMap.newKeySet())
                .add(parentObjectId + "#" + childObjectId);
    }

    /** FNKEY: resolves the orphaned-entry expectation dissolved by a pair removal. */
    private void resolveOrphanedEntry(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction) {
        final java.util.Set<String> entries = orphanedEntries.get(transaction.getTransactionId());
        if (entries != null) {
            entries.remove(parentObjectId + "#" + childObjectId);
        }
    }

    /** FNKEY: the relation-name fragment used by the invariant messages. */
    private String relationName() {
        return clParentInfo.getClazz().getSimpleName() + "#" + clParentField.getName() + " -> " + clChildInfo.getClazz().getSimpleName();
    }

    /**
     * FNKEY: whether this relation was declared through the CHILD's {@code @XdStorageParentObject}
     * field WITHOUT a counter-{@code @XdStorageAutoSave} on the parent — its deletes dissolve
     * blindly (the child-side declarer owns the reference; no dangling-child invariants).
     */
    private boolean isChildAnchoredRelation() {
        if (clParentInfo.getAutoSaveFields().stream().anyMatch(autosaveField -> {
            final Class<?> autosaveChildClass = autosaveField.getAccessor().getFieldInfo().getValueClass();
            return autosaveChildClass != null && autosaveChildClass == clChildInfo.getClazz();
        })) {
            return false;
        }
        return clParentInfo.getFields().values().stream().anyMatch(field -> field.isParent()
                && field.getFieldInfo().getValueClass() == clChildInfo.getClazz());
    }

    private void registerForeignKey(Object parentId, Object childId, XdStorageTransaction transaction) throws XdStorageConnectionException, XdStorageException {
        XdStorageBTree treeParentChild = getOrCreateForeignKeyBTree(bTreeParentChildId, clParentInfo, createParentChildTreeLock, transaction);
        XdStorageBTree treeChildParent = getOrCreateForeignKeyBTree(bTreeChildParentId, clChildInfo, createChildParentTreeLock, transaction);

        treeParentChild.insert((Comparable<?>) parentId, childId, services.getStorage(), transaction);
        treeChildParent.insert((Comparable<?>) childId, parentId, services.getStorage(), transaction);

        // FNKEY: the parent's save registered the declared pair — the child-insert
        // expectation is resolved.
        final java.util.Set<Comparable<?>> expected = expectedChildInserts.get(transaction.getTransactionId());
        if (expected != null) {
            expected.remove(childId);
        }
    }

    /** FNKEY: {@inheritDoc} */
    @Override
    public void registerChildInsert(Comparable<?> childObjectId, Comparable<?> parentObjectId, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        if (parentObjectId != null && readByChildObjectId(childObjectId, transaction).contains(parentObjectId)) {
            final XdStorageForeignKeyViolationException violation = new XdStorageForeignKeyViolationException(
                    transaction.getTransactionId(), relationName(),
                    "Foreign key violated for relation " + relationName()
                            + ": the pair is already registered (tx " + transaction.getTransactionId()
                            + ", parent: " + parentObjectId + ", child: " + childObjectId + ")");
            transaction.markRollbackOnly("foreign key violation: " + relationName(), violation);
            throw violation;
        }
        // The parent's save in THIS transaction must register the (parent, child) pair —
        // for a null/id-less parent reference there is nobody to register it unless the
        // parent object's id lands and its save follows; unresolved at commit = veto.
        expectedChildInserts.computeIfAbsent(transaction.getTransactionId(), tId -> ConcurrentHashMap.newKeySet())
                .add(childObjectId);
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
                    if (parentId == null) {
                        // FNKEY: the child's id landed but its declared parent has none and
                        // nobody registered the pair — the child-insert expectation holds
                        // the commit veto unless the parent's save registers the pair.
                        expectedChildInserts.computeIfAbsent(transaction.getTransactionId(), tId -> ConcurrentHashMap.newKeySet())
                                .add((Comparable<?>) childId);
                    } else {
                        registerForeignKey(parentId, childId, transaction);
                    }
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

    @Override
    public Object getDao() {
        throw new XdStorageRuntimeException("unsupported");
    }
}
