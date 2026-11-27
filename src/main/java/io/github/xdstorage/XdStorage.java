package io.github.xdstorage;

import io.github.xdstorage.foreignkey.XdStorageForeignKeyObjectChangeType;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.utils.XdStorageFormatMeta;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.resource.*;
import io.github.xdstorage.search.IXdStorageSearchManager;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.services.XdStorageServicesLocator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import io.github.xdstorage.structure.update.IXdStorageStructureUpdater;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionManager;
import io.github.xdstorage.trigger.IXdStorageTrigger;
import io.github.xdstorage.utils.FilesCopy;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageFolderLock;
import io.github.xdstorage.utils.XdStorageAutoSaveCascadeEngine;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The file-storage implementation behind {@link XdStorageProvider}: the public contract is
 * {@link IXdStorage}/{@link IXdFileStorage}, whose javadoc this class inherits method by
 * method. Engine internals (services locator, resources, transactions) hang off
 * {@link XdStorageServicesLocator}; never instantiate directly — open storages through
 * the provider, which owns the folder lock and the name registry.
 */
class XdStorage implements IXdFileStorage {

    private static final Logger log = LoggerFactory.getLogger(XdStorage.class);

    private final String name;

    private final XdStorageServicesLocator services;

    private final XdStorageConfig config;

    /** S-4: после shutdown() любой публичный вызов падает громко, а не с RejectedExecutionException где-то в недрах. */
    private volatile boolean closed = false;

    private final Object shutdownLock = new Object();

    /** The on-disk folder — used by FP-5 backupTo(). */
    private final String folder;

    private XdStorageFolderLock folderLock;

    private Thread shutdownHook;

    /**
     * Builds the storage over the given folder: wires the services locator (which
     * prepares the folder layout), and registers the JVM-exit shutdown hook when the
     * configuration asks for one. Not public — {@link XdStorageProvider} is the factory,
     * it also owns the folder lock and the name registry.
     *
     * @param name          the storage name (registry key)
     * @param folder        the storage folder; all engine files live under it
     * @param fragmentSize  objects per class file ({@code StoreAsClassObjects} policy)
     */
    XdStorage(final String name, final String folder, final int fragmentSize) {
        this(name, folder, fragmentSize, FilesFormat.YAML, XdStorageConfig.defaults());
    }

    XdStorage(final String name, final String folder, final int fragmentSize, FilesFormat format) {
        this(name, folder, fragmentSize, format, XdStorageConfig.defaults());
    }

    XdStorage(final String name, final String folder, final int fragmentSize, final FilesFormat format, final
            XdStorageConfig config) {
        this.name = name;
        this.folder = folder;
        this.config = config == null ? XdStorageConfig.defaults() : config;
        if (this.config.isJournalWrites() && format != FilesFormat.YAML) {
            // W-1: журнал коммитов реализован для YAML-формата; остальное — громкий отказ при открытии
            throw new XdStorageRuntimeException("journalWrites is supported for the YAML format only (got " + format +
                    ")");
        }
        services = new XdStorageServicesLocator();
        services.initFileConfiguration(this, folder, fragmentSize, format, this.config);
        migrateLegacyLayout(folder, name, format.name());
        if (this.config.isRegisterShutdownHook()) {
            // S-4: best-effort закрытие при выходе JVM. НЕ заменяет daemonWorkerThreads:
            // хуки запускаются только после завершения последнего не-daemon потока, поэтому
            // не-daemon пул блокирует выход JVM еще до того, как хуки успеют сработать.
            final Thread hook = new Thread(this::shutdownFromHook, "xdstorage-shutdown-hook-" + name);
            hook.setDaemon(true);
            try {
                Runtime.getRuntime().addShutdownHook(hook);
                this.shutdownHook = hook;
            } catch (final IllegalStateException e) {
                log.warn("shutdown hook could not be registered for storage '{}' (JVM already shutting down)", name);
            }
        }
    }

    /** S-3: блокировку каталога выдает провайдер при создании; освобождается в shutdown(). */
    void attachFolderLock(final XdStorageFolderLock lock) {
        this.folderLock = lock;
    }

    /** S-4: громкий отказ после shutdown() вместо сырых RejectedExecutionException в недрах. */
    private void ensureOpen() {
        if (closed) {
            throw new XdStorageRuntimeException("storage '" + name +
                    "' is shut down — open a new instance via XdStorageProvider");
        }
    }

    private void shutdownFromHook() {
        log.info("JVM shutdown hook closing storage '{}'", name);
        shutdown();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public io.github.xdstorage.metrics.XdStorageMetrics getMetrics() {
        ensureOpen();
        return services.getMetrics();
    }

    /**
     * This method registers trigger. Every trigger will be executed in change
     * transaction and must work only with parameter's object.
     *
     * @param trigger This trigger will be registered.
     */
    @Override
    public <T> void registerTrigger(final IXdStorageTrigger<T> trigger) {
        ensureOpen();
        services.getTriggersManager().registerTrigger(trigger);
    }

    /**
     * This method starts new transaction.
     *
     * @param timeout Transaction timeout in milliseconds
     * @return Return started transaction.
     */
    @Override
    public IXdStorageTransaction beginTransaction(final long timeout) {
        ensureOpen();
        return services.getTransactionsManager().beginTransaction(timeout);
    }

    @Override
    public IXdStorageTransaction beginTransaction() {
        ensureOpen();
        return beginTransaction(config.getDefaultTransactionTimeoutMillis());
    }

    @Override
    public IXdStorageTransaction beginTransaction(final IXdStorageTransaction transaction, final long timeout) {
        ensureOpen();
        return services.getTransactionsManager().beginTransaction(transaction, timeout);
    }

    @Override
    public IXdStorageTransaction beginTransaction(final IXdStorageTransaction transaction) {
        ensureOpen();
        return beginTransaction(transaction, 3 * 1000);
    }

    /**
     * This method commit specified transaction.
     *
     * @param transaction Specified transaction
     */
    @Override
    public void commitTransaction(final IXdStorageTransaction transaction) throws XdStorageException {
        ensureOpen();
        if (transaction == null)
            throw new IllegalArgumentException("transaction cannot be null");

        services.getTransactionsManager().commitTransaction(transaction);
    }

    /**
     * This method roll back specified transaction.
     *
     * @param transaction Specified transaction
     */
    @Override
    public void rollbackTransaction(final IXdStorageTransaction transaction) {
        ensureOpen();
        if (transaction == null)
            throw new IllegalArgumentException("transaction cannot be null");

        services.getTransactionsManager().rollbackTransaction(transaction);
    }

    /**
     * This method checks transaction.
     *
     * @return Return true if transaction has been started.
     */
    private boolean checkIsInTransaction() {
        return services.getTransactionsManager().getCurrentTransaction() != null;
    }

    /**
     * This method checks transaction is not finished.
     *
     * @param transaction
     * @return Returns true if transaction was not committed or rolled back.
     */
    private boolean isTransactionAlive(final IXdStorageTransaction transaction) {
        return services.getTransactionsManager().isTransactionAlive(transaction);
    }

    /**
     * @param updater
     */
    @Override
    public void registerStructureUpdater(final IXdStorageStructureUpdater updater) {
        ensureOpen();
        services.getStructureManager().registerUpdater(updater);
    }

    /**
     * @param cl
     * @throws XdStorageException
     */
    @Override
    public List<IXdStorageStructureUpdater> executeStructureUpdate(final Class<?> cl) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        if (!checkHasObjectIdField(cl))
            throw new XdStorageException("the class " + cl + " must have identifier field @XdStorageObjectId");

        return services.getStructureManager().checkAndUpdateStructureIfNeed(cl, (XdStorageTransaction) services
                .getTransactionsManager().getCurrentTransaction());
    }

    /**
     * This method saves specified object.
     *
     * @param object Specified object
     * @throws XdStorageException This exception will be throw if an error arises in save
     *                            operation.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void save(final Object object) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        save(object, transaction);
    }

    @Override
    public void save(final Object object, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (object == null)
            throw new XdStorageException("object cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            final Class<?> cl = object.getClass();
            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageSearchManager searchManager = services.getSearchManager();

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                resource.insert(object, tx);

                if (searchManager.hasIndex(object)) {
                    searchManager.insert(object, tx);
                }
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo, tx);
                referencesResource.insertReference(object, tx);

                final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(object, tx);
                resource.insert(object, tx);

                if (searchManager.hasIndex(object)) {
                    searchManager.insert(object, tx);
                }
            } else {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }

            log.debug("saved {} by tx {}", cl.getSimpleName(), transaction.getTransactionId());

            // AUTOSAVE (docs/tasks/2.1.0/22): cascade @XdStorageAutoSave children in the same tx
            XdStorageAutoSaveCascadeEngine.cascadeAfterPersist(object, tx, services, this);

            services.getForeignKeyEngine().process(object, XdStorageForeignKeyObjectChangeType.INSERT, tx);
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method saves collection of objects.
     *
     * @param objects Collection of objects.
     * @throws XdStorageException This exception will be throw if error arises in save
     *                            operation.
     */
    @Override
    public void save(final Collection<?> objects) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        save(objects, transaction);
    }

    @Override
    public void save(final Collection<?> objects, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (objects == null)
            throw new XdStorageException("objects collection cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (objects.isEmpty())
                return;

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageSearchManager searchManager = services.getSearchManager();

            final XdStorageException[] exceptions = new XdStorageException[]{null};
            final XdStorageConnectionException[] conExceptions = new XdStorageConnectionException[]{null};
            final AtomicBoolean hasError = new AtomicBoolean(false);

            objects.forEach(object -> {
                if (hasError.get()) {
                    return;
                }

                try {
                    final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());

                    if (!checkHasObjectIdField(cl)) {
                        throw new XdStorageException("the class " + cl +
                                " must have  identifier field @XdStorageObjectId");
                    }

                    final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

                    final XdStoragePolicy policy = clInfo.getPolicy();
                    if (policy == XdStoragePolicy.StoreAsClassObjects) {
                        final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                        resource.insert(object, tx);

                        if (searchManager.hasIndex(object)) {
                            searchManager.insert(object, tx);
                        }
                    } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                        final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo,
                                tx);
                        referencesResource.insertReference(object, tx);

                        final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(object, tx);
                        resource.insert(object, tx);

                        if (searchManager.hasIndex(object)) {
                            searchManager.insert(object, tx);
                        }
                    } else {
                        throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                                .StoreAsClassObjects + " or "
                                + XdStoragePolicy.StoreAsSingleObject);
                    }

                    log.debug("saved {} by tx {}", cl.getSimpleName(), transaction.getTransactionId());

                    // AUTOSAVE (docs/tasks/2.1.0/22): cascade @XdStorageAutoSave children in the same tx
                    XdStorageAutoSaveCascadeEngine.cascadeAfterPersist(object, tx, services, this);

                    services.getForeignKeyEngine().process(object, XdStorageForeignKeyObjectChangeType.INSERT, tx);
                } catch (final XdStorageConnectionException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    conExceptions[0] = e;
                } catch (final XdStorageException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    exceptions[0] = e;
                }
            });

            if (exceptions[0] != null)
                throw exceptions[0];
            if (conExceptions[0] != null)
                throw conExceptions[0];

            log.debug("saved collection of {} ({} objects) by tx {}", objects.iterator().hasNext() ? objects.iterator()
                    .next().getClass().getSimpleName() : "?", objects.size(), transaction.getTransactionId());
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This check existing object by specified identifier.
     *
     * @param cl Specified class
     * @param id Specified identifier
     * @return Return true if and only if object contains in the storage
     */
    @Override
    public boolean has(final Class<?> cl, final Object id) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        return has(cl, id, transaction);
    }

    @Override
    public boolean has(final Class<?> cl, final Object id, final IXdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (id == null)
            throw new XdStorageException("idgeneration cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        if (!isTransactionAlive(transaction))
            throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

        if (!checkHasObjectIdField(cl))
            throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

        final XdStorageTransaction tx = cast(transaction);

        final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
            return resource.read(id, tx) != null;
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(clInfo, id, tx);
            return resource.read(id, tx) != null;
        } else {
            throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                    .StoreAsClassObjects + " or "
                    + XdStoragePolicy.StoreAsSingleObject);
        }
    }

    /**
     * This method loads required object by object's reference.
     *
     * @param reference Specified object's reference.
     * @throws XdStorageException This exception will be throw if error arises in load
     *                            operation.
     */
    @Override
    public void load(final Object reference) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        load(reference, transaction);
    }

    @Override
    public void load(final Object reference, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (reference == null)
            throw new XdStorageException("reference cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            final Class<?> cl = XdStorageObjectUtils.getEntityClass(reference.getClass());
            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                resource.readByReference(reference, tx);
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(reference, tx);
                resource.readByReference(reference, tx);
            } else {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method loads object by specified identifier.
     *
     * @param cl Specified class
     * @param id Specified identifier
     * @return Return object if exist, else will be throw exception
     * @throws XdStorageException This exception will be throw if object isn't exist.
     */
    @Override
    public <T> T load(final Class<T> cl, final Object id) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        return load(cl, id, transaction);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T load(final Class<T> cl, final Object id, final IXdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (id == null)
            throw new XdStorageException("idgeneration cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            final T result;
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                result = (T) resource.read(id, tx);
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(clInfo, id, tx);
                result = (T) resource.read(id, tx);
            } else {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }
            return result;
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method loads some objects in specified references collection.
     *
     * @param references Collection of objects' references.
     * @return Return true if all objects from collection was been loaded
     * successful, else return false.
     * @throws XdStorageException This exception will throw if error arises in loading process.
     */
    @Override
    public void load(final Collection<?> references) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        load(references, transaction);
    }

    @Override
    public void load(final Collection<?> references, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (references == null)
            throw new XdStorageException("references collection cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (references.isEmpty())
                return;

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();

            final XdStorageException[] exceptions = new XdStorageException[]{null};
            final XdStorageConnectionException[] conExceptions = new XdStorageConnectionException[]{null};
            final AtomicBoolean hasError = new AtomicBoolean(false);

            references.forEach(reference -> {
                if (hasError.get()) {
                    return;
                }

                try {
                    final Class<?> cl = XdStorageObjectUtils.getEntityClass(reference.getClass());

                    if (!checkHasObjectIdField(cl)) {
                        throw new XdStorageException("the class " + cl +
                                " must have  identifier field @XdStorageObjectId");
                    }

                    final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

                    final XdStoragePolicy policy = clInfo.getPolicy();
                    if (policy == XdStoragePolicy.StoreAsClassObjects) {
                        final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                        resource.readByReference(reference, tx);
                    } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                        final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(reference, tx);
                        resource.readByReference(reference, tx);
                    } else {
                        throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                                .StoreAsClassObjects + " or "
                                + XdStoragePolicy.StoreAsSingleObject);
                    }
                } catch (final XdStorageConnectionException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    conExceptions[0] = e;
                } catch (final io.github.xdstorage.exceptions.XdStorageReferenceInvisibleException e) {
                    // R-58: the element is invisible in the reader snapshot (committed after its start /
                    // transiently rolled back) - normal MVCC answer, not an error: the element is not
                    // part of the visible graph version. The reference stays unfilled.
                } catch (final XdStorageException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    exceptions[0] = e;
                }
            });

            if (exceptions[0] != null)
                throw exceptions[0];

            if (conExceptions[0] != null)
                throw conExceptions[0];
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method load all objects of specified class.
     *
     * @param cl Class of objects.
     * @return Return map of objects.
     * @throws XdStorageException This exception will throw if error arises in loading process.
     */
    @Override
    public <T> Collection<T> load(final Class<T> cl) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        return load(cl, transaction);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Collection<T> load(final Class<T> cl, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                return (Collection<T>) resource.read(tx);
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo, tx);

                final XdStorageException[] exceptions = new XdStorageException[]{null};
                final XdStorageConnectionException[] conExceptions = new XdStorageConnectionException[]{null};
                final AtomicBoolean hasError = new AtomicBoolean(false);

                final Collection<Object> references = referencesResource.readReferences(tx);
                references.forEach(reference -> {
                    if (hasError.get()) {
                        return;
                    }

                    try {
                        final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(reference, tx);
                        resource.readByReference(reference, tx);
                    } catch (final XdStorageConnectionException e) {
                        if (hasError.get()) {
                            return;
                        }
                        hasError.set(true);
                        conExceptions[0] = e;
                    } catch (final XdStorageException e) {
                        if (hasError.get()) {
                            return;
                        }
                        hasError.set(true);
                        exceptions[0] = e;
                    }
                });

                if (exceptions[0] != null)
                    throw exceptions[0];

                if (conExceptions[0] != null)
                    throw conExceptions[0];

                return (Collection<T>) references;
            } else {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method load objects by specified predicate.
     *
     * @param cl        Class of objects.
     * @param predicate Predicate for filtering objects.
     * @return Return map of objects.
     * @throws XdStorageException This exception will throw if error arises in loading process.
     */
    @Override
    public <T> Collection<T> load(final Class<T> cl, final IXdStoragePredicate<T> predicate) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        return load(cl, predicate, transaction);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Collection<T> load(final Class<T> cl, final IXdStoragePredicate<T> predicate, final IXdStorageTransaction
            transaction) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (predicate == null)
            throw new XdStorageException("predicate cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");
        if (!isTransactionAlive(transaction))
            throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

        if (!checkHasObjectIdField(cl))
            throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

        final XdStorageTransaction tx = cast(transaction);

        final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
            return resource.read(tx, predicate);
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            final Collection<T> result = new ArrayList<>();
            final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo, tx);

            final XdStorageException[] exceptions = new XdStorageException[]{null};
            final XdStorageConnectionException[] conExceptions = new XdStorageConnectionException[]{null};
            final AtomicBoolean hasError = new AtomicBoolean(false);

            final Collection<Object> references = referencesResource.readReferences(tx);
            references.forEach(reference -> {
                if (hasError.get()) {
                    return;
                }

                try {
                    final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(reference, tx);
                    resource.readByReference(reference, tx);
                    if (predicate.passed((T) reference)) {
                        result.add((T) reference);
                    }
                } catch (final XdStorageConnectionException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    conExceptions[0] = e;
                } catch (final XdStorageException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    exceptions[0] = e;
                }
            });

            if (exceptions[0] != null)
                throw exceptions[0];

            if (conExceptions[0] != null)
                throw conExceptions[0];

            return result;
        } else {
            throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                    .StoreAsClassObjects + " or "
                    + XdStoragePolicy.StoreAsSingleObject);
        }
    }

    @Override
    public <T> Collection<T> load(final Class<T> cl, final String indexName, final XdStorageSearchQuery query) throws
            XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        return load(cl, indexName, query, transaction);
    }

    @Override
    public <T> Collection<T> load(final Class<T> cl, final String indexName, final XdStorageSearchQuery query,
                                  final IXdStorageTransaction transaction) throws XdStorageException,
                                          XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (query == null)
            throw new XdStorageException("query cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreWithParentObject) {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }

            return services.getSearchManager().search(cl, indexName, query, tx);
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    @Override
    public io.github.xdstorage.search.query.XdStorageQueryPlan explain(final Class<?> cl, final String indexName,
                                                                       final XdStorageSearchQuery query)
            throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (query == null)
            throw new XdStorageException("query cannot be null");

        // EXPLAIN (docs/tasks/2.1.0/25): the plan is static (the index metadata is
        // class-level) — no transaction is required; the internal probe tx only guards the
        // index-tree availability probe and is rolled back immediately (no side effects).
        final IXdStorageTransactionManager txManager = services.getTransactionsManager();
        final XdStorageTransaction probeTx = (XdStorageTransaction) txManager.beginTransaction(60_000L);
        try {
            return services.getSearchManager().explain(cl, indexName, query, probeTx);
        } finally {
            txManager.rollbackTransaction(probeTx);
        }
    }

    @Override
    public io.github.xdstorage.search.query.XdStorageQueryPlan explain(final Class<?> cl, final String indexName,
                                                                       final XdStorageSearchQuery query,
                                                                       final IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (query == null)
            throw new XdStorageException("query cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");
        if (!isTransactionAlive(transaction))
            throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

        return services.getSearchManager().explain(cl, indexName, query, cast(transaction));
    }

    @Override
    public long count(final Class<?> cl, final String indexName, final XdStorageSearchQuery query) throws
            XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        return count(cl, indexName, query, transaction);
    }

    @Override
    public long count(final Class<?> cl, final String indexName, final XdStorageSearchQuery query,
                      final IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (query == null)
            throw new XdStorageException("query cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreWithParentObject) {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }

            return services.getSearchManager().count(cl, indexName, query, tx);
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method load objects by specified predicate.
     *
     * @param cl      Class of objects.
     * @param watcher Watcher for viewable objects.
     * @return Return map of objects.
     * @throws XdStorageException This exception will throw if error arises in loading process.
     */
    @Override
    public <T> void watch(final Class<T> cl, final IXdStorageWatcher<T> watcher) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        watch(cl, watcher, transaction);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void watch(final Class<T> cl, final IXdStorageWatcher<T> watcher, final IXdStorageTransaction
            transaction) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (cl == null)
            throw new XdStorageException("class cannot be null");
        if (watcher == null)
            throw new XdStorageException("predicate cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                resource.watch(tx, watcher);
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo, tx);

                final XdStorageException[] exceptions = new XdStorageException[]{null};
                final XdStorageConnectionException[] conExceptions = new XdStorageConnectionException[]{null};
                final AtomicBoolean hasError = new AtomicBoolean(false);

                final Collection<Object> references = referencesResource.readReferences(tx);
                references.forEach(reference -> {
                    if (hasError.get()) {
                        return;
                    }

                    try {
                        final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(reference, tx);
                        resource.watch(tx, watcher);
                    } catch (final XdStorageConnectionException e) {
                        if (hasError.get()) {
                            return;
                        }
                        hasError.set(true);
                        conExceptions[0] = e;
                    } catch (final XdStorageException e) {
                        if (hasError.get()) {
                            return;
                        }
                        hasError.set(true);
                        exceptions[0] = e;
                    }
                });

                if (exceptions[0] != null)
                    throw exceptions[0];

                if (conExceptions[0] != null)
                    throw conExceptions[0];
            } else {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method updates object.
     *
     * @param object Specified object
     * @throws XdStorageException This exception will be throw if an error arises in update
     *                            process.
     */
    @Override
    public void update(final Object object) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        update(object, transaction);
    }

    @Override
    public void update(final Object object, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (object == null)
            throw new XdStorageException("object cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageSearchManager searchManager = services.getSearchManager();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                resource.update(object, tx);

                if (searchManager.hasIndex(object)) {
                    searchManager.update(object, tx);
                }
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(object, tx);
                resource.update(object, tx);

                if (searchManager.hasIndex(object)) {
                    searchManager.update(object, tx);
                }
            } else {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }

            log.debug("updated {} by tx {}", cl.getSimpleName(), transaction.getTransactionId());

            // AUTOSAVE (docs/tasks/2.1.0/22): cascade @XdStorageAutoSave children in the same tx
            XdStorageAutoSaveCascadeEngine.cascadeAfterPersist(object, tx, services, this);

            services.getForeignKeyEngine().process(object, XdStorageForeignKeyObjectChangeType.UPDATE, tx);
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method updates objects.
     *
     * @param objects Specified objects
     * @throws XdStorageException This exception will be throw if an error arises in update
     *                            process.
     */
    @Override
    public void update(final Collection<?> objects) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        update(objects, transaction);
    }

    @Override
    public void update(final Collection<?> objects, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (objects == null)
            throw new XdStorageException("objects cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (objects.isEmpty())
                return;

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageSearchManager searchManager = services.getSearchManager();

            final XdStorageException[] exceptions = new XdStorageException[]{null};
            final XdStorageConnectionException[] conExceptions = new XdStorageConnectionException[]{null};
            final AtomicBoolean hasError = new AtomicBoolean(false);

            objects.forEach(object -> {
                if (hasError.get()) {
                    return;
                }

                try {
                    final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
                    if (!checkHasObjectIdField(cl)) {
                        throw new XdStorageException("the class " + cl +
                                " must have  identifier field @XdStorageObjectId");
                    }

                    final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

                    final XdStoragePolicy policy = clInfo.getPolicy();
                    if (policy == XdStoragePolicy.StoreAsClassObjects) {
                        final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                        resource.update(object, tx);

                        if (searchManager.hasIndex(object)) {
                            searchManager.update(object, tx);
                        }
                    } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                        final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(object, tx);
                        resource.update(object, tx);

                        if (searchManager.hasIndex(object)) {
                            searchManager.update(object, tx);
                        }
                    } else {
                        throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                                .StoreAsClassObjects + " or "
                                + XdStoragePolicy.StoreAsSingleObject);
                    }

                    log.debug("updated {} by tx {}", cl.getSimpleName(), transaction.getTransactionId());

                    // AUTOSAVE (docs/tasks/2.1.0/22): cascade @XdStorageAutoSave children in the same tx
                    XdStorageAutoSaveCascadeEngine.cascadeAfterPersist(object, tx, services, this);

                    services.getForeignKeyEngine().process(object, XdStorageForeignKeyObjectChangeType.UPDATE, tx);
                } catch (final XdStorageConnectionException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    conExceptions[0] = e;
                } catch (final XdStorageException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    exceptions[0] = e;
                }
            });

            if (exceptions[0] != null)
                throw exceptions[0];

            if (conExceptions[0] != null)
                throw conExceptions[0];
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method deletes specified object by object's reference. This
     * reference can be full filled object.
     *
     * @param reference Specified reference.
     * @throws XdStorageException This exception will be throw if an error arises in delete
     *                            process.
     */
    @Override
    public void delete(final Object reference) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        delete(reference, transaction);
    }

    @Override
    public void delete(final Object reference, final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        ensureOpen();
        if (reference == null)
            throw new XdStorageException("reference cannot be null");
        log.debug("delete {} requested by tx {}", reference.getClass().getSimpleName(), transaction.getTransactionId());
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            final Class<?> cl = XdStorageObjectUtils.getEntityClass(reference.getClass());
            if (!checkHasObjectIdField(cl))
                throw new XdStorageException("the class " + cl + " must have  identifier field @XdStorageObjectId");

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageSearchManager searchManager = services.getSearchManager();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                if (searchManager.hasIndex(reference)) {
                    searchManager.delete(reference, tx);
                }

                final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                resource.delete(reference, tx);
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                if (searchManager.hasIndex(reference)) {
                    searchManager.delete(reference, tx);
                }

                final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo, tx);
                final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(reference, tx);
                referencesResource.deleteReference(reference, tx);
                resource.delete(reference, tx);
            } else {
                throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                        .StoreAsClassObjects + " or "
                        + XdStoragePolicy.StoreAsSingleObject);
            }

            log.debug("deleted {} by tx {}", cl.getSimpleName(), transaction.getTransactionId());

            services.getForeignKeyEngine().process(reference, XdStorageForeignKeyObjectChangeType.DELETE, tx);
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    /**
     * This method deletes some objects.
     *
     * @param references Collection objects to remove.
     * @throws XdStorageException This exception will throw if an error arises in delete
     *                            process.
     */
    @Override
    public void delete(final Collection<?> references) throws XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (!checkIsInTransaction())
            throw new XdStorageException("method must be executed in transaction");

        final IXdStorageTransaction transaction = services.getTransactionsManager().getCurrentTransaction();

        delete(references, transaction);
    }

    @Override
    public void delete(final Collection<?> references, final IXdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException {
        ensureOpen();
        if (references == null)
            throw new XdStorageException("references collection cannot be null");
        if (transaction == null)
            throw new XdStorageException("transaction cannot be null");

        final XdStorageTransactionManager txManager = (XdStorageTransactionManager) services.getTransactionsManager();
        txManager.incrementActiveThreads(transaction.getTransactionId());
        try {
            if (!isTransactionAlive(transaction))
                throw new XdStorageException("transaction " + transaction.getTransactionId() + " is not alive");

            if (references.isEmpty())
                return;

            final XdStorageTransaction tx = cast(transaction);

            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageSearchManager searchManager = services.getSearchManager();

            final XdStorageException[] exceptions = new XdStorageException[]{null};
            final XdStorageConnectionException[] conExceptions = new XdStorageConnectionException[]{null};
            final AtomicBoolean hasError = new AtomicBoolean(false);

            references.forEach(reference -> {
                if (hasError.get()) {
                    return;
                }

                try {
                    final Class<?> cl = XdStorageObjectUtils.getEntityClass(reference.getClass());
                    if (!checkHasObjectIdField(cl)) {
                        throw new XdStorageException("the class " + cl +
                                " must have  identifier field @XdStorageObjectId");
                    }

                    final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

                    final XdStoragePolicy policy = clInfo.getPolicy();
                    if (policy == XdStoragePolicy.StoreAsClassObjects) {
                        if (searchManager.hasIndex(reference)) {
                            searchManager.delete(reference, tx);
                        }

                        final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, tx);
                        resource.delete(reference, tx);
                    } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
                        if (searchManager.hasIndex(reference)) {
                            searchManager.delete(reference, tx);
                        }

                        final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo,
                                tx);
                        final IXdStorageDaoResource resource = resourcesManager.lockObjectResource(reference, tx);
                        referencesResource.deleteReference(reference, tx);
                        resource.delete(reference, tx);
                    } else {
                        throw new XdStorageException("class " + cl.getName() + " must have policy " + XdStoragePolicy
                                .StoreAsClassObjects + " or "
                                + XdStoragePolicy.StoreAsSingleObject);
                    }

                    log.debug("deleted {} by tx {}", cl.getSimpleName(), transaction.getTransactionId());

                    services.getForeignKeyEngine().process(reference, XdStorageForeignKeyObjectChangeType.DELETE, tx);
                } catch (final XdStorageConnectionException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    conExceptions[0] = e;
                } catch (final XdStorageException e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    exceptions[0] = e;
                }
            });

            if (exceptions[0] != null)
                throw exceptions[0];

            if (conExceptions[0] != null)
                throw conExceptions[0];
        } finally {
            txManager.decrementActiveThreads(transaction.getTransactionId());
        }
    }

    @Override
    public IXdStorageTransactionManager getTransactionManager() {
        ensureOpen();
        return services.getTransactionsManager();
    }

    /** W-4c (docs/tasks/2.1.0/48): the checkpoint API — force-checkpoint all journals. */
    @Override
    public void checkpoint() {
        ensureOpen();
        try {
            final int checkpointed = services.getResourcesManager().checkpointAllJournals();
            if (checkpointed > 0) {
                log.info("storage '{}' checkpoint: {} journal(s) checkpointed", name, checkpointed);
            }
        } catch (final Throwable t) {
            log.warn("checkpoint of storage '{}' failed (safe: journals replay at the next open): {}",
                    name, t.getMessage());
        }
    }

    @Override
    public void shutdown() {
        synchronized (shutdownLock) {
            // S-4: shutdown идемпотентен — повторный вызов (явный + хук) ничего не ломает
            if (closed)
                return;
            closed = true;
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (final IllegalStateException e) {
                // JVM уже в процессе завершения — хук сейчас сам и сработает
            } catch (final Throwable t) {
                log.warn("shutdown hook of storage '{}' could not be removed: {}", name, t.getMessage());
            }
            shutdownHook = null;
        }
        // W-4c: best-effort shutdown checkpoint — after a clean close every journal is
        // empty, so a later power loss loses nothing even with durableWrites=false
        // (W4-DESIGN.md §W-4c). Busy/unprepared resources are skipped (replay covers them).
        try {
            final int checkpointed = services.getResourcesManager().checkpointAllJournals();
            if (checkpointed > 0) {
                log.info("storage '{}' shutdown checkpoint: {} journal(s) checkpointed", name, checkpointed);
            }
        } catch (final Throwable t) {
            log.warn("shutdown checkpoint of storage '{}' failed (safe: journals replay at the next open): {}",
                    name, t.getMessage());
        }
        XdStorageProvider.unregisterStorage(this);
        services.shutdown();
        if (folderLock != null) {
            folderLock.close();
            folderLock = null;
        }
    }

    // ---- FP-5: backupTo (docs/tasks/2.2.0/15) ----

    @Override
    public void backupTo(final Path destination) throws XdStorageConnectionException, XdStorageException {
        ensureOpen();
        final File source = new File(folder).getAbsoluteFile();
        final Path target = destination.toAbsolutePath().normalize();
        if (target.startsWith(source.toPath().toAbsolutePath().normalize())) {
            throw new XdStorageRuntimeException("the backup destination " + destination + " is inside the storage"
                    + " folder " + folder + " — the copy would copy itself");
        }
        final boolean fresh = !Files.exists(target);
        if (!fresh) {
            if (!Files.isDirectory(target)) {
                throw new XdStorageRuntimeException("the backup destination " + destination + " exists and is not"
                        + " a directory");
            }
            try (Stream<Path> entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new XdStorageRuntimeException("the backup destination " + destination + " exists and is"
                            + " not empty — a backup goes into a new or an empty directory");
                }
            } catch (final IOException e) {
                throw new XdStorageConnectionException("the backup destination " + destination + " cannot be read",
                        e);
            }
        }
        // The quiesced copy — the consistency contract task 15 chose. Closing the storage is
        // what makes it a point in time: the shutdown checkpoint moves every committed change
        // into the base files, and nothing writes to the folder afterwards. A commit that was
        // in flight is cut the way a crash cuts it — its journal replays at the next open.
        shutdown();
        try {
            final int copied = FilesCopy.copyStorage(source.toPath(), target, folder, target.toString());
            log.info("storage '{}': backup into {} ({} file(s))", name, target, copied);
        } catch (final IOException | RuntimeException failure) {
            if (fresh) {
                FilesCopy.deleteRecursively(target);
            }
            throw new XdStorageConnectionException("backup of storage '" + name + "' into " + destination + " failed:"
                    + " " + failure.getMessage(), failure);
        }
    }

    private boolean checkHasObjectIdField(final Class<?> cl) {
        return XdStorageObjectUtils.getClassInfo(cl).getIdField() != null;
    }

    private static <T> T cast(final Object value) throws XdStorageException {
        try {
            return (T) value;
        } catch (final Throwable t) {
            throw new XdStorageException(t);
        }
    }


    /** Task 51 (LAYOUTMIG): the one-time legacy-layout migration at open — the released
     *  2.0.0 arrangement (and the unreleased intermediate spellings) into the 2.1.0
     *  {@code indexes/} layout. Idempotent and crash-tolerant: every step checks
     *  source-exists/dest-absent before moving, so a half-migrated folder (a crash
     *  mid-migration) is recognized by the same structural detection and completed by
     *  the next open. On full success the format marker is rewritten v2 → v3
     *  (the layout-generation stamp; a v3 folder opened by a 2.0 library refuses loudly
     *  through the pre-existing newer-version gate).
     *
     *  <p>What moves:
     *  <ul>
     *    <li>the interim top-level {@code index/} → {@code indexes/} (never written by the
     *        released 2.0.0 — the dev-train folders only);</li>
     *    <li>the legacy index bases inside {@code data/<Class>/}: the id-index
     *        {@code index.yaml}, the references base {@code references.yaml} (the
     *        StoreAsSingleObject classes) and the search-index bases
     *        {@code <indexName>.yaml} → {@code indexes/<Class>/}. The classification is
     *        stem-based: a file whose stem equals {@code <Class>} or starts with
     *        {@code <Class>-} is a DATA FRAGMENT and stays (the fragment paths inside the
     *        id-index payload keep resolving — the fragments do not move); every other
     *        base file moves. The pathological case (an index literally named like a
     *        fragment) stays behind and surfaces as an empty index — the post-open
     *        verification walk catches it;</li>
     *    <li>the B+Tree infra dirs {@code data/XdStorageBTree/} and
     *        {@code data/XdStorageBTreeNode/} → {@code indexes/}.</li>
     *  </ul>
     *
     *  <p>What does NOT move: the data fragments (their paths are persisted inside the
     *  id-index payload — task 24), the stacked 2.0.0 journals
     *  ({@code <base>.yaml.journal.yaml} — the journal naming chain replays and settles
     *  them lazily at each resource's first prepare), {@code structure/}, the lock/marker
     *  files. New storages create the layout directly and run no steps.
     *
     *  <p>Any move failure (a Windows file lock, permissions) = a LOUD open refusal:
     *  continuing with a half-moved index would surface as a silently empty id index and
     *  the first save would clobber fragment-0 (the counters rebuild from an empty index)
     *  — silent data loss is worse than a refused open. */
    private void migrateLegacyLayout(final String folderPath, final String storageName, final String format) {
        final File folder = new File(folderPath);
        final File indexDir = new File(folder, "index");
        final File indexesDir = new File(folder, "indexes");
        final File dataDir = new File(folder, "data");
        // 1. the interim index/ → indexes/ (dev-train folders)
        if (indexDir.isDirectory() && !indexesDir.exists()) {
            log.info("LAYOUTMIG: moving index/ -> indexes/ (interim id-index layout)");
            moveLoudly(indexDir.toPath(), indexesDir.toPath(), "index/ -> indexes/");
        }
        if (!indexesDir.exists() && !indexesDir.mkdirs()) {
            throw new XdStorageRuntimeException("LAYOUTMIG: cannot create the indexes/ directory in " + folderPath
                    + " — check the permissions/a file named 'indexes' blocking the path");
        }

        // 2. the legacy index bases inside data/<Class>/ → indexes/<Class>/
        migrateLegacyIndexBases(dataDir, indexesDir);

        // 3. the B+Tree infra dirs
        final File[] subDirs = dataDir.listFiles();
        if (subDirs != null) {
            for (final File sub : subDirs) {
                if (!sub.isDirectory()) {
                    continue;
                }
                final String infraName = sub.getName();
                if (!"XdStorageBTree".equals(infraName) && !"XdStorageBTreeNode".equals(infraName)) {
                    continue;
                }
                final File dest = new File(indexesDir, infraName);
                if (dest.exists()) {
                    continue; // already migrated (the crash-tolerance re-entry)
                }
                log.info("LAYOUTMIG: moving data/{}/ -> indexes/{}/ (indexes/ layout)", infraName, infraName);
                moveLoudly(sub.toPath(), dest.toPath(), "data/" + infraName + "/ -> indexes/" + infraName + "/");
            }
        }

        // 4. the format marker catch-up (v2 -> v3): also runs when nothing moved — the
        //    2.1.0-dev folders created before the stamp get their marker finished here
        XdStorageFormatMeta.upgradeFormatMarkerToCurrent(folder, storageName, format);
    }

    /** Task 51 step 2: the legacy index bases in {@code data/<Class>/} →
     *  {@code indexes/<Class>/}. See {@link #migrateLegacyLayout} for the classification. */
    private void migrateLegacyIndexBases(final File dataDir, final File indexesDir) {
        if (!dataDir.isDirectory()) {
            return;
        }
        final File[] classDirs = dataDir.listFiles();
        if (classDirs == null) {
            return;
        }
        for (final File classDir : classDirs) {
            if (!classDir.isDirectory()) {
                continue; // loose files in data/ are not a 2.0.0 shape — untouched
            }
            final String className = classDir.getName();
            if ("XdStorageBTree".equals(className) || "XdStorageBTreeNode".equals(className)) {
                continue; // the infra dirs are handled by their own step
            }
            final File[] entries = classDir.listFiles();
            if (entries == null) {
                continue;
            }
            for (final File entry : entries) {
                if (!entry.isFile()) {
                    continue; // journal/ subdirs and nested dirs stay
                }
                final String fileName = entry.getName();
                if (fileName.endsWith(".journal.yaml") || fileName.endsWith("-journal.yaml")) {
                    continue; // a STACKED/SIBLING commit journal — the task-24 chain replays and
                              // settles it lazily; the folder migration must not touch it
                }
                final String stem = stripBaseSuffix(fileName);
                if (stem == null) {
                    continue; // not a base file (a .tmp litter — the S-5 sweep handles it)
                }
                if (stem.equals(className) || stem.startsWith(className + "-")) {
                    continue; // a data fragment — stays (the id-index payload points here)
                }
                final File destDir = new File(indexesDir, className);
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw new XdStorageRuntimeException("LAYOUTMIG: cannot create " + destDir);
                }
                final File dest = new File(destDir, fileName);
                if (dest.exists()) {
                    continue; // already migrated (the crash-tolerance re-entry)
                }
                log.info("LAYOUTMIG: moving data/{}/{} -> indexes/{}/{} (the legacy index base)",
                        className, fileName, className, fileName);
                moveLoudly(entry.toPath(), dest.toPath(),
                        "data/" + className + "/" + fileName + " -> indexes/" + className + "/" + fileName);
            }
        }
    }

    /** Strips a base-file suffix; {@code null} when the name carries none (litter). */
    private static String stripBaseSuffix(final String fileName) {
        final String[] suffixes = {".yaml", ".yml", ".xml", ".json"};
        for (final String suffix : suffixes) {
            if (fileName.endsWith(suffix) && fileName.length() > suffix.length()) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return null;
    }

    /** NIO move with the ATOMIC_MOVE preference and the loud failure contract. */
    private static void moveLoudly(final java.nio.file.Path source, final java.nio.file.Path target, final String what)
            {
        try {
            try {
                java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
                // cross-device or a filesystem without atomic renames — the plain move keeps
                // the crash-tolerance contract (a partial move re-enters the migration)
                java.nio.file.Files.move(source, target);
            }
        } catch (final java.io.IOException moveFailure) {
            throw new XdStorageRuntimeException("LAYOUTMIG: the legacy-layout migration failed at '" + what
                    + "': " + moveFailure.getMessage() + " — the storage is NOT opened; resolve the file lock"
                    + " or the permissions problem and reopen", moveFailure);
        }
    }
}
