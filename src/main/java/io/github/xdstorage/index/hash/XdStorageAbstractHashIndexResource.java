package io.github.xdstorage.index.hash;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.index.IXdStorageChangeIndexRollback;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.index.IXdStorageIndexResourceObject;
import io.github.xdstorage.index.XdStorageIndexResourceCache;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.resource.XdStorageResourceCache;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base of the hash id-index resource family: an in-memory id {@code ->}
 * data-fragment map ({@link XdStorageIndexResourceCache}) backed by a transactional
 * resource of {@link XdStorageHashIndexRecord} entries. Inserts route the object into a
 * not-yet-full fragment (or into a fresh fragment keyed by the id when none is free),
 * registering an {@link IXdStorageChangeIndexRollback} compensation per staged insert so a
 * rollback undoes exactly the id-map mutations; deletes drop the record; reads resolve the
 * id through the map and pass through to the owning data-fragment resource. Subclasses add
 * the persistence lifecycle (prepare, two-phase commit, journaling) and call {@code
 * #postPrepare}, {@code #postCommit} and {@code #postRollback} at the lifecycle points.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public abstract class XdStorageAbstractHashIndexResource implements IXdStorageIndexResourceObject, IXdStorageIndexDaoResource {

    private static final Logger log = LoggerFactory.getLogger(XdStorageAbstractHashIndexResource.class);

    /** Engine services locator (storage access, config, IO factory, id generator). */
    protected final XdStorageServicesLocator services;

    /** The resources manager used to lock and release the data-fragment resources. */
    protected final XdStorageAbstractResourcesManager manager;

    /** The storage-wide unique id of this index resource (its file path). */
    protected final Object resourceId;

    /** Optional id generator used by subclasses to mint ids on insert ({@code null} when absent). */
    protected final IXdStorageIdGenerator idGenerator;

    /** Class metadata of the index RECORD class ({@link XdStorageHashIndexRecord}). */
    protected final XdStorageClassInfo clInfo;

    /** Id-field accessor of the record class. */
    protected final XdStorageObjectIdField idField;

    /** Whether this resource stores reference entries rather than id-index records (always {@code false} here). */
    protected final boolean isReferences;

    /** Transaction resource cache holding the persisted {@link XdStorageHashIndexRecord} entries. */
    protected final XdStorageResourceCache cache;

    /** The logical name of the index this resource serves. */
    protected final String indexName;

    /** The lock-free id {@code ->} data-fragment map serving the point lookups. */
    protected final XdStorageIndexResourceCache index;

    /** Per-transaction stacks of compensations for staged id-map mutations (keyed by transaction id). */
    protected final Map<String, Stack<IXdStorageChangeIndexRollback>> rollbacks = new ConcurrentHashMap<String, Stack<IXdStorageChangeIndexRollback>>();

    /** Class metadata of the ENTITY class being indexed. */
    protected final XdStorageClassInfo objectClInfo;

    /** Id-field accessor of the ENTITY class being indexed. */
    protected final XdStorageObjectIdField objectIdField;

    /**
     * Creates the resource without an id generator (ids must be set by the caller).
     *
     * @param services     the engine services locator
     * @param manager      the resources manager owning this resource
     * @param resourceId   the storage-wide unique resource id (the backing file path)
     * @param indexName    the logical index name
     * @param clInfo       metadata of the ENTITY class being indexed
     * @param fragmentSize the record capacity of one data fragment
     */
    protected XdStorageAbstractHashIndexResource(final XdStorageServicesLocator services,
                                                 final XdStorageAbstractResourcesManager manager,
                                                 final Object resourceId, final String indexName,
                                                 final XdStorageClassInfo clInfo, final int fragmentSize) {
        this.services = services;
        this.manager = manager;
        this.resourceId = resourceId;
        this.clInfo = XdStorageObjectUtils.getClassInfo(XdStorageHashIndexRecord.class);
        this.idField = this.clInfo.getIdField();
        this.isReferences = false;
        this.cache = new XdStorageResourceCache(services, clInfo.getClazz(), idField);
        this.cache.setIsolationLevel(XdStorageIsolationLevel.READ_COMMITTED);
        this.idGenerator = null;

        this.indexName = indexName;
        this.index = new XdStorageIndexResourceCache(fragmentSize);
        this.objectClInfo = clInfo;
        this.objectIdField = clInfo.getIdField();
    }

    /**
     * Creates the resource with an id generator for subclass-driven id minting on insert.
     *
     * @param services     the engine services locator
     * @param manager      the resources manager owning this resource
     * @param resourceId   the storage-wide unique resource id (the backing file path)
     * @param indexName    the logical index name
     * @param clInfo       metadata of the ENTITY class being indexed
     * @param idGenerator  the id generator applied when an inserted object has no id yet
     * @param fragmentSize the record capacity of one data fragment
     */
    protected XdStorageAbstractHashIndexResource(final XdStorageServicesLocator services,
                                                 final XdStorageAbstractResourcesManager manager,
                                                 final Object resourceId, final String indexName,
                                                 final XdStorageClassInfo clInfo, final IXdStorageIdGenerator idGenerator,
                                                 final int fragmentSize) {
        this.services = services;
        this.manager = manager;
        this.resourceId = resourceId;
        this.clInfo = XdStorageObjectUtils.getClassInfo(XdStorageHashIndexRecord.class);
        this.idField = this.clInfo.getIdField();
        this.isReferences = false;
        this.cache = new XdStorageResourceCache(services, clInfo.getClazz(), idField);
        this.cache.setIsolationLevel(XdStorageIsolationLevel.READ_COMMITTED);
        this.idGenerator = idGenerator;

        this.indexName = indexName;
        this.index = new XdStorageIndexResourceCache(fragmentSize);
        this.objectClInfo = clInfo;
        this.objectIdField = clInfo.getIdField();
    }

    /** @return the storage-wide unique id of this index resource */
    @Override
    public Object getResourceId() {
        return resourceId;
    }

    /** @return the index record class this resource persists ({@code XdStorageHashIndexRecord}) */
    @Override
    public Class<?> getObjectsClass() {
        return clInfo.getClazz();
    }

    /** @return the number of index records currently held by the resource cache */
    @Override
    public long getObjectsCount() {
        return cache.getObjectsCount();
    }

    /**
     * @param transaction the transaction to inspect
     * @return {@code true} when the transaction has staged changes in this resource
     * @throws XdStorageException on a storage-level failure
     */
    @Override
    public boolean hasChanges(final XdStorageTransaction transaction) throws XdStorageException {
        return cache.hasChanges(transaction);
    }

    /**
     * Lifecycle hook called after preparation: fills the id {@code ->} fragment map from
     * the record cache (once, when the map is still empty), so point lookups do not touch
     * the record cache.
     *
     * @param transaction the transaction the resource was prepared for
     * @throws XdStorageException on a storage-level failure
     */
    protected void postPrepare(final XdStorageTransaction transaction) throws XdStorageException {
        if (index.isClear()) {
            final Collection<Object> objects = cache.read(transaction);
            for (final Object object : objects) {
                final XdStorageHashIndexRecord record = (XdStorageHashIndexRecord) object;
                index.insertRecord(record.getObjectId(), record.getResourceId());
            }
        }
    }

    /**
     * Lifecycle hook called after a successful commit: discards the transaction's rollback
     * stack — its compensations are never applied.
     *
     * @param transaction the committed transaction
     */
    protected void postCommit(final XdStorageTransaction transaction) {
        rollbacks.remove(transaction.getTransactionId());
    }

    /**
     * Lifecycle hook called after a rollback: pops and applies the transaction's registered
     * compensations (LIFO), undoing the staged id-map mutations.
     *
     * @param transaction the rolled-back transaction
     */
    protected void postRollback(final XdStorageTransaction transaction) {
        final Stack<IXdStorageChangeIndexRollback> stack = rollbacks.remove(transaction.getTransactionId());
        if (stack != null) {
            while (!stack.isEmpty()) {
                stack.pop().rollback();
            }
        }
    }

    /**
     * Registers one compensation on the transaction's rollback stack.
     *
     * @param transaction the transaction whose staged mutation is compensated
     * @param rb          the compensation to apply if the transaction rolls back
     */
    protected void registerRollback(final XdStorageTransaction transaction, final IXdStorageChangeIndexRollback rb) {
        Stack<IXdStorageChangeIndexRollback> stack = rollbacks.get(transaction.getTransactionId());
        if (stack == null) {
            rollbacks.put(transaction.getTransactionId(), stack = new Stack<IXdStorageChangeIndexRollback>());
        }
        stack.push(rb);
    }

    /**
     * No-op: the hash family does not serialize its commit phases — subclasses override
     * this when they need a commit lock.
     *
     * @param transaction the transaction acquiring the lock
     */
    @Override
    public void lockForCommit(final XdStorageTransaction transaction) {
        // do nothing
    }

    /**
     * No-op: nothing to release for the base implementation.
     *
     * @param transaction the transaction releasing the lock
     */
    @Override
    public void unlockAfterCommit(final XdStorageTransaction transaction) {
        // do nothing
    }

    /**
     * Releases the resource: clears the transaction's cache contours and returns the
     * resource to the manager.
     *
     * @param transaction the transaction releasing the resource
     */
    @Override
    public void release(final XdStorageTransaction transaction) {
        cache.clear(transaction);
        manager.releaseResource(this);
    }

    /**
     * Looks up the data-fragment resource id mapped to the given object id.
     *
     * @param id          the object id to look up
     * @param transaction the transaction whose snapshot defines visibility (the map is
     *                    shared, so the lookup is effectively committed-state)
     * @return the data-fragment resource id, or {@code null} when unknown
     */
    public Object getObjectResourceId(final Object id, final XdStorageTransaction transaction) {
        return index.getResourceId(id);
    }

    /**
     * Whether the index knows the given object id.
     *
     * @param id          the object id to test
     * @param transaction the transaction whose snapshot defines visibility
     * @return {@code true} when the id maps to a data fragment
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public boolean has(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return index.getResourceId(id) != null;
    }

    /**
     * Reads every indexed object by walking the id map fragment by fragment and loading
     * each fragment's records through its data resource.
     *
     * @param transaction the transaction whose snapshot defines visibility
     * @return the objects visible to this transaction (never {@code null})
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public Collection<Object> read(final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final List<Object> result = new ArrayList<>();
        final Collection<Object> ids = index.getResourcesIds();
        for (final Object resourceId : ids) {
            final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
            result.addAll(resource.read(transaction));
        }
        return result;
    }

    /**
     * Reads the indexed objects that pass the predicate, by the same fragment walk as
     * {@code #read(XdStorageTransaction)}; the predicate is applied per fragment.
     *
     * @param <T>         the element type the result collection is viewed as
     * @param transaction the transaction whose snapshot defines visibility
     * @param predicate   the filter applied to each candidate object
     * @return the matching objects visible to this transaction (never {@code null})
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public <T> Collection<T> read(final XdStorageTransaction transaction, final IXdStoragePredicate<T> predicate) throws XdStorageException, XdStorageConnectionException {
        final List<T> result = new ArrayList<>();
        final Collection<Object> ids = index.getResourcesIds();
        for (final Object resourceId : ids) {
            final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
            result.addAll(resource.read(transaction, predicate));
        }
        return result;
    }

    /**
     * Delivers every indexed object (wrapped as an unmodifiable view) to the watcher, by
     * the same fragment walk as {@code #read(XdStorageTransaction)}.
     *
     * @param <T>         the element type the watcher receives
     * @param transaction the transaction whose snapshot defines visibility
     * @param watcher     the callback invoked once per visible object
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public <T> void watch(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException {
        final Collection<Object> ids = index.getResourcesIds();
        for (final Object resourceId : ids) {
            final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
            resource.watch(transaction, watcher);
        }
    }

    /**
     * Populates the given reference object in place from its stored record: the reference's
     * id is resolved through the id map, then the data fragment fills the reference.
     *
     * @param reference   the reference object to populate (its id field must be set)
     * @param transaction the transaction whose snapshot defines visibility
     * @throws XdStorageException           when the index does not know the reference's id,
     *         or on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public void readByReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object objectId = objectIdField.get(reference);
        final Object resourceId = index.getResourceId(objectId);
        if (resourceId == null) {
            throw new XdStorageException("cannot load by reference object of class " + reference.getClass() + " with idgeneration " + objectId);
        }

        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        resource.readByReference(reference, transaction);
    }

    /**
     * Loads one object by id: resolves the id through the id map and reads the record from
     * its data fragment.
     *
     * @param id          the object id to load
     * @param transaction the transaction whose snapshot defines visibility
     * @return the stored object, or {@code null} when the id is unknown
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public Object read(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object resourceId = index.getResourceId(id);
        if (resourceId == null) {
            return null;
        }

        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        return resource.read(id, transaction);
    }

    /**
     * Indexes a new object: stores the record into a not-yet-full data fragment (or a fresh
     * fragment keyed by the object id when none is free), writes the {@link
     * XdStorageHashIndexRecord} into the record cache, registers the id {@code ->} fragment
     * mapping and a compensating removal for transaction rollback.
     *
     * @param object      the object to insert (its id field must be set)
     * @param transaction the transaction the insert is staged into
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public void insert(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object objectId = objectIdField.get(object);
        boolean isInserted = false;
        final Object freeResourceId = index.getFreeResourceId();
        if (freeResourceId != null) {
            final IXdStorageDaoResource resource = manager.lockResource(freeResourceId, objectClInfo, transaction);
            resource.insert(object, transaction);

            final XdStorageHashIndexRecord record = new XdStorageHashIndexRecord(objectId, resource.getResourceId());
            cache.insert(record, transaction);

            index.insertRecord(objectId, resource.getResourceId());
            registerRollback(transaction, new IXdStorageChangeIndexRollback() {

                @Override
                public void rollback() {
                    index.deleteRecord(objectId);
                }
            });
            isInserted = true;
        }
        if (!isInserted) {
            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(object.getClass());
            final IXdStorageDaoResource resource = manager.lockResource(clInfo, objectId, transaction);
            resource.insert(object, transaction);

            final XdStorageHashIndexRecord record = new XdStorageHashIndexRecord(objectId, resource.getResourceId());
            cache.insert(record, transaction);

            index.insertRecord(objectId, resource.getResourceId());
            registerRollback(transaction, new IXdStorageChangeIndexRollback() {

                @Override
                public void rollback() {
                    index.deleteRecord(objectId);
                }
            });
        }
    }

    /**
     * Updates the stored record of an existing indexed object: resolves the id through the
     * id map and delegates to the owning data fragment.
     *
     * @param object      the object to update (its id must already be indexed)
     * @param transaction the transaction the update is staged into
     * @throws XdStorageException           when the object's id is not indexed, or on a
     *         storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public void update(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object objectId = objectIdField.get(object);
        final Object resourceId = index.getResourceId(objectId);
        if (resourceId == null) {
            throw new XdStorageException("object of class " + object.getClass() + " with idgeneration " + objectId + " does not exists");
        }

        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        resource.update(object, transaction);
    }

    /**
     * Removes the stored record of an indexed object: deletes it through the owning data
     * fragment (transactionally rolled back on failure) and drops the id-index record.
     *
     * @param object      the object to delete (its id must already be indexed)
     * @param transaction the transaction the delete is staged into
     * @throws XdStorageException           when the object's id is not indexed, or on a
     *         storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public void delete(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object objectId = objectIdField.get(object);
        final Object resourceId = index.getResourceId(objectId);
        if (resourceId == null) {
            throw new XdStorageException("object of class " + object.getClass() + " with idgeneration " + objectId + " does not exists");
        }

        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        resource.delete(object, transaction); // will be rolled back

        cache.delete(objectId, transaction);
    }

    /** @return this resource itself — the hash id-index resource is its own DAO */
    @Override
    public IXdStorageIndexDaoResource getDao() {
        return this;
    }
}
