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
import java.util.concurrent.ConcurrentHashMap;

public class XdStorageForeignKeyResourcesManager {

    private final XdStorageServicesLocator services;
    private final XdStorageAbstractResourcesManager manager;

    private final XdStorageForeignKeyRegistry foreignKeyRegistry = new XdStorageForeignKeyRegistry();

    /** Open resources by resource id; entries live until storage shutdown. */
    private final Map<Object, IXdStorageForeignKeyResource> resources;

    public XdStorageForeignKeyResourcesManager(XdStorageServicesLocator services, XdStorageAbstractResourcesManager manager) {
        this.services = services;
        this.manager = manager;
        this.resources = new ConcurrentHashMap<>();
    }

    public XdStorageServicesLocator getServices() {
        return services;
    }

    public IXdStorageForeignKeyResource lockForeignKeyResource(XdStorageClassInfo clParentInfo, XdStorageClassInfo clChildInfo,
                                                               XdStorageObjectField clParentField, XdStorageTransaction transaction) throws XdStorageConnectionException, XdStorageException {
        final Object resourceId = services.getNamingService().getForeignKeyResourceId("fk", clParentInfo.getClazz(), clChildInfo.getClazz(), clParentField.getName());
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

    protected IXdStorageForeignKeyResource createFkResource(Object resourceId, XdStorageClassInfo clParentInfo, XdStorageClassInfo clChildInfo, XdStorageObjectField clParentField) {
        return new XdStorageForeignKeyResource(services, manager, resourceId, clParentInfo, clChildInfo, clParentField);
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
                                                                                    final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
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
