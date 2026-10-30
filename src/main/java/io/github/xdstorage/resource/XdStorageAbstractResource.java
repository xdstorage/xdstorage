package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.util.Collection;

/**
 * Common base of the transactional resource objects: binds a resource id, entity class
 * metadata, the id field and the per-resource MVCC cache, and implements both sides of the
 * resource contract — the transactional lifecycle ({@code IXdStorageResourceObject}) and the
 * record-level DAO operations ({@code IXdStorageDaoResource}) — in front of the shared cache.
 * Instances are shared between threads: cache operations run inside the caller's transaction
 * critical section, while commit phases are serialized per resource by the manager's locking
 * protocol. Subclasses add the physical I/O and the real commit phases.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public abstract class XdStorageAbstractResource implements IXdStorageResourceObject<IXdStorageDaoResource>, IXdStorageDaoResource {

    /** Engine services shared with collaborators (storage, config, cloner, transactions manager). */
    protected final XdStorageServicesLocator services;

    /** The resources manager that owns this resource, hands it out and keeps its counters. */
    protected final XdStorageAbstractResourcesManager manager;

    /** The storage-wide unique id of this resource (its relative file path). */
    protected final Object resourceId;

    /** External id generator for database/custom id strategies; {@code null} otherwise. */
    protected final IXdStorageIdGenerator idGenerator;

    /** Metadata of the entity class this resource stores. */
    protected final XdStorageClassInfo clInfo;

    /** Metadata of the entity id field (accessors and id-generation strategy). */
    protected final XdStorageObjectIdField idField;

    /** Whether this is a references resource (parent/child bookkeeping of per-object files). */
    protected final boolean isReferences;

    /** The per-resource MVCC cache — every read and staged write resolves through it. */
    protected final XdStorageResourceCache cache;

    /**
     * Builds a non-references resource with in-process id generation.
     *
     * @param services   engine services shared with collaborators
     * @param manager    the owning resources manager
     * @param resourceId the storage-wide unique resource id
     * @param clInfo     metadata of the entity class
     */
    protected XdStorageAbstractResource(final XdStorageServicesLocator services,
                                        final XdStorageAbstractResourcesManager manager,
                                        final Object resourceId,
                                        final XdStorageClassInfo clInfo) {
        this(services, manager, resourceId, clInfo, null, false);
    }

    /**
     * Builds a resource with in-process id generation.
     *
     * @param services     engine services shared with collaborators
     * @param manager      the owning resources manager
     * @param resourceId   the storage-wide unique resource id
     * @param clInfo       metadata of the entity class
     * @param isReferences whether this is a references resource
     */
    protected XdStorageAbstractResource(final XdStorageServicesLocator services,
                                        final XdStorageAbstractResourcesManager manager,
                                        final Object resourceId,
                                        final XdStorageClassInfo clInfo,
                                        final boolean isReferences) {
        this(services, manager, resourceId, clInfo, null, isReferences);
    }

    /**
     * Builds a resource with full configuration.
     *
     * @param services     engine services shared with collaborators
     * @param manager      the owning resources manager
     * @param resourceId   the storage-wide unique resource id
     * @param clInfo       metadata of the entity class
     * @param idGenerator  external id generator, or {@code null} for in-process ids
     * @param isReferences whether this is a references resource
     */
    protected XdStorageAbstractResource(final XdStorageServicesLocator services,
                                        final XdStorageAbstractResourcesManager manager,
                                        final Object resourceId,
                                        final XdStorageClassInfo clInfo,
                                        final IXdStorageIdGenerator idGenerator,
                                        final boolean isReferences) {
        this.services = services;
        this.manager = manager;
        this.resourceId = resourceId;
        this.clInfo = clInfo;
        this.idField = clInfo.getIdField();
        this.isReferences = isReferences;
        this.cache = new XdStorageResourceCache(services, clInfo.getClazz(), idField);
        this.idGenerator = idGenerator;
    }

    /**
     * The resources manager that owns this resource.
     *
     * @return the owning manager
     */
    public XdStorageAbstractResourcesManager getManager() {
        return manager;
    }

    public Object getResourceId() {
        return resourceId;
    }

    @Override
    public Class<?> getObjectsClass() {
        return clInfo.getClazz();
    }

    @Override
    public long getObjectsCount() {
        return cache.getObjectsCount();
    }

    @Override
    public boolean hasChanges(final XdStorageTransaction transaction) throws XdStorageException {
        return cache.hasChanges(transaction);
    }

    /**
     * Subclass hook invoked at the end of {@code prepare} — e.g. to load derived state
     * after the base file is in the cache. The default does nothing.
     *
     * @param transaction the transaction the resource is prepared for
     */
    protected void postPrepare(final XdStorageTransaction transaction) {
        // do nothing
    }

    /**
     * Subclass hook invoked at the end of phase 2 (after the changes are published) — e.g.
     * to refresh derived state. The default does nothing.
     *
     * @param transaction the committed transaction
     */
    protected void postCommit(final XdStorageTransaction transaction) {
        // do nothing
    }

    /**
     * Subclass hook invoked at the end of a rollback — e.g. to restore derived state.
     * The default does nothing.
     *
     * @param transaction the rolled-back transaction
     */
    protected void postRollback(final XdStorageTransaction transaction) {
        // do nothing
    }

    @Override
    public void lockForCommit(final XdStorageTransaction transaction) {
        // do nothing
    }

    @Override
    public void unlockAfterCommit(final XdStorageTransaction transaction) {
        // do nothing
    }

    @Override
    public void release(final XdStorageTransaction transaction) {
        cache.clear(transaction);
        manager.releaseResource(this);
    }

    public Collection<XdStorageIdentifiableObject> readAsData(final XdStorageTransaction transaction) throws XdStorageException {
        throw new XdStorageRuntimeException("unsupported operation exception");
    }

    public boolean hasObject(final Object objectId, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return cache.hasObject(objectId);
    }

    public Collection<Object> readReferences(final XdStorageTransaction transaction) throws XdStorageException {
        return cache.read(transaction);
    }

    public void insertReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        Object objectId = idField.get(reference);
        if ((idField.getIdGeneretorType() == XdStorageIdGeneratorType.DATABASE_GENERATOR
                || idField.getIdGeneretorType() == XdStorageIdGeneratorType.CUSTOM_GENERATOR)
                && objectId == null && idGenerator != null) {
            idField.set(reference, idGenerator.generate(reference.getClass(), services.getStorage(), transaction));
        }
        cache.insert(reference, transaction);
        manager.updateCounterByObjectAdded(clInfo.getClazz(), this);
    }

    public void deleteReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException {
        cache.delete(idField.get(reference), transaction);
        manager.updateCounterByObjectRemoved(clInfo.getClazz(), this);
    }

    public <T> T find(final Object object, final XdStorageTransaction transaction) throws XdStorageException {
        return cache.findUnidentified(object, transaction);
    }

    public <T> Collection<T> read(final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return cache.read(transaction);
    }

    public <T> Collection<T> read(final XdStorageTransaction transaction, final IXdStoragePredicate<T> predicate) throws XdStorageException, XdStorageConnectionException {
        return cache.read(transaction, predicate);
    }

    public <T> void watch(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException {
        cache.watch(transaction, watcher);
    }

    public void readByReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        cache.readByReference(reference, transaction);
    }

    public Object read(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return cache.read(id, transaction);
    }

    public void insert(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        cache.insert(object, transaction);
        manager.updateCounterByObjectAdded(clInfo.getClazz(), this);
    }

    public void update(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        cache.update(object, transaction);
    }

    public void delete(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        cache.delete(idField.get(object), transaction);
        manager.updateCounterByObjectRemoved(clInfo.getClazz(), this);
    }

    @Override
    public IXdStorageDaoResource getDao() {
        return this;
    }
}
