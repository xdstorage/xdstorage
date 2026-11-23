package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.XdStorageResourceCommitOrder;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FK resources manager: owns the per-relation foreign-key resources (created and
 * cached by resource id), the relation registry and the storage-wide FK pair write-set
 * shared by the engine and every FK resource.
 */
public class XdStorageForeignKeyResourcesManager {

    private static final Logger log = LoggerFactory.getLogger(XdStorageForeignKeyResourcesManager.class);

    private final XdStorageServicesLocator services;
    private final XdStorageAbstractResourcesManager manager;

    private final XdStorageForeignKeyRegistry foreignKeyRegistry = new XdStorageForeignKeyRegistry();

    /**
     * FNKEY (docs/analysis/FOREIGN_KEYS.md §4/§5): the storage-wide FK pair write-set.
     * ONE instance shared by the engine (commitAllFxPairs) and every FK resource
     * (cacheInsert/cacheDelete at registration/dissolution, the phase methods) — a second
     * instance would split the write-set and silently lose pairs at commit.
     */
    private final XdStorageForeignKeyCache foreignKeyCache = new XdStorageForeignKeyCache();

    /**
     * The relation registry backing every FK resource of this storage.
     * @return the shared {@link XdStorageForeignKeyRegistry}
     */
    public XdStorageForeignKeyRegistry getForeignKeyRegistry() {
        return foreignKeyRegistry;
    }

    /**
     * The shared FK pair write-set (one per storage).
     * @return the shared {@link XdStorageForeignKeyCache}
     */
    public XdStorageForeignKeyCache getForeignKeyCache() {
        return foreignKeyCache;
    }

    /**
     * The storage the FK pairs persist into (the commit-phase tree writes).
     * @return the storage services locator
     */
    public XdStorageServicesLocator getServices() {
        return services;
    }

    /** Open resources by resource id; entries live until storage shutdown. */
    private final Map<Object, IXdStorageForeignKeyResource> resources;

    /**
     * Access to the resources map for shutdown cleanup (FK resources are class-keyed and reused across storages).
     * @return the open FK resources by resource id
     */
    public Map<Object, IXdStorageForeignKeyResource> getResources() {
        return resources;
    }

    /**
     * Creates the manager.
     *
     * @param services the storage services locator (naming service, storage access)
     * @param manager  the core storage resources manager the FK resources delegate to
     */
    public XdStorageForeignKeyResourcesManager(XdStorageServicesLocator services, XdStorageAbstractResourcesManager
            manager) {
        this.services = services;
        this.manager = manager;
        this.resources = new ConcurrentHashMap<>();
    }

    /**
     * Access to the core storage resources manager (needed by FK resource).
     * @return the core storage resources manager
     */
    public XdStorageAbstractResourcesManager getManager() {
        return manager;
    }

    /**
     * FNKEY: Notifies all FK resources of orphaned children for commit-time validation.
     * Called by the FK engine at commit time (before first-phase commit of each FK resource).
     *
     * @param transaction     the committing transaction
     * @param orphanedParents the ids of the parents whose children turned orphaned
     */
    public void notifyOrphanedChildren(XdStorageTransaction transaction, Set<Comparable<?>> orphanedParents) {
        for (IXdStorageForeignKeyResource resource : resources.values()) {
            if (resource instanceof XdStorageForeignKeyResource) {
                ((XdStorageForeignKeyResource) resource).registerOrphanedChildren(transaction, orphanedParents);
            }
        }
    }

    /**
     * FNKEY: Registers orphaned children via the FK registry (for FK resources to read at commit).
     *
     * @param tx        the owning transaction
     * @param parentIds the ids of the parents whose children turned orphaned
     */
    public void registerOrphanedChildren(XdStorageTransaction tx, Set<Comparable<?>> parentIds) {
        foreignKeyRegistry.registerOrphanedChildren(tx, parentIds);
    }

    /**
     * Resolves (or atomically creates) the FK resource serving the given relation and
     * registers/prepares it for the transaction.
     *
     * @param clParentInfo the relation's parent (referencing) class info
     * @param clChildInfo  the relation's child (referenced) class info
     * @param clParentField the parent's referencing field
     * @param transaction  the transaction to lock and prepare the resource for
     * @return the locked, registered and prepared FK resource
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     * @throws XdStorageException          on a storage-level failure
     */
    public IXdStorageForeignKeyResource lockForeignKeyResource(XdStorageClassInfo clParentInfo, XdStorageClassInfo
            clChildInfo,
                                                               XdStorageObjectField clParentField, XdStorageTransaction
                                                                transaction) throws XdStorageConnectionException,
                                                                XdStorageException {
        final Object resourceId = services.getNamingService().getForeignKeyResourceId("fk", clParentInfo.getClazz(),
                clChildInfo.getClazz(), clParentField.getName());
        return internalLockResource(new IResourceFactory() {
            @Override
            public Object getResourceId() {
                return resourceId;
            }

            @Override
            public IXdStorageForeignKeyResource create() {
                return createFkResource(resourceId, clParentInfo, clChildInfo, clParentField);
            }

            @Override
            public int getCommitOrder() {
                return XdStorageResourceCommitOrder.FOREIGN_KEY_RESOURCE_ORDER;
            }
        }, transaction);
    }

    /**
     * Creates the FK resource for the given relation (the {@code lockForeignKeyResource}
     * factory step; overridable in tests).
     *
     * @param resourceId    the FK resource id (from the naming service)
     * @param clParentInfo  the relation's parent (referencing) class info
     * @param clChildInfo   the relation's child (referenced) class info
     * @param clParentField the parent's referencing field
     * @return the created FK resource
     */
    protected IXdStorageForeignKeyResource createFkResource(Object resourceId, XdStorageClassInfo clParentInfo,
            XdStorageClassInfo clChildInfo, XdStorageObjectField clParentField) {
        return new XdStorageForeignKeyResource(services, manager, this, resourceId, clParentInfo, clChildInfo,
                clParentField);
    }

    /**
     * The shared core of the {@code lockXxxResource} family: resolves the resource by id,
     * creating it atomically on first use; registers it into the transaction with the
     * factory's commit order (first registration only — later transactions reuse the same
     * instance) and increments its usage counter; then prepares the resource so its state
     * is in the cache before the caller proceeds.
     *
     * @param <T>         the resource type the caller expects
     * @param factory     identifies, creates and orders the resource
     * @param transaction the transaction to register and prepare the resource for
     * @return the locked, registered and prepared resource
     * @throws XdStorageException          on a storage-level failure (including preparation)
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    protected final <T extends IXdStorageForeignKeyResource> T internalLockResource(final IResourceFactory factory,
                                                                                    final XdStorageTransaction
                                                                transaction) throws XdStorageException,
                                                                XdStorageConnectionException {
        final Object resourceId = factory.getResourceId();
        IXdStorageForeignKeyResource returnResource = resources.computeIfAbsent(resourceId, k -> factory.create());
        returnResource.prepare(transaction);

        // Безопасно регистрируем ресурс в транзакции за пределами системных блокировок менеджера
        if (!transaction.isResourceRegistered(returnResource)) {
            transaction.registerResource(returnResource, factory.getCommitOrder());
        }

        return (T) returnResource;
    }

    /**
     * Internal callback bundle handed to {@link #internalLockResource} by the
     * {@code lockXxxResource} family: identifies the resource, creates it on first use and
     * names the commit-order group it is registered into the transaction with.
     */
    protected interface IResourceFactory {

        /**
         * The storage-wide unique id of the resource to lock.
         *
         * @return the resource id
         */
        Object getResourceId();

        /**
         * Creates a new resource object (called only when no resource with the id exists).
         *
         * @return a new resource object (not yet prepared)
         */
        IXdStorageForeignKeyResource create();

        /**
         * The commit-order group the resource is registered into the transaction with
         * (a constant from {@link XdStorageResourceCommitOrder}).
         *
         * @return the commit order of the resource kind
         */
        int getCommitOrder();
    }
}
