package io.github.xdstorage.services;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.foreignkey.IXdStorageForeignKeyEngine;
import io.github.xdstorage.foreignkey.XdStorageForeignKeyEngine;
import io.github.xdstorage.foreignkey.XdStorageForeignKeyRegistry;
import io.github.xdstorage.foreignkey.XdStorageForeignKeyResourcesManager;
import io.github.xdstorage.metrics.XdStorageMetrics;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.factories.IXdStorageReferenceProvider;
import io.github.xdstorage.factories.XdStorageDefaultReferenceProvider;
import io.github.xdstorage.factories.XdStorageSmartCloner;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageDefaultIdGenerator;
import io.github.xdstorage.resource.IXdStorageResourceNamingService;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.XdStorageResourceNamingService;
import io.github.xdstorage.resource.XdStorageResourcesManager;
import io.github.xdstorage.search.IXdStorageSearchManager;
import io.github.xdstorage.search.XdStorageSearchManager;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.serialization.IXdStorageIOFactory;
import io.github.xdstorage.serialization.XdStorageDefaultIOFactory;
import io.github.xdstorage.serialization.XdStorageIOFactory;
import io.github.xdstorage.structure.XdStorageStructureManager;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransactionManager;
import io.github.xdstorage.trigger.XdStorageTriggerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-storage service wiring hub: constructs and holds the collaborator instances of
 * one open storage — worker executor, configuration, metrics, transaction/trigger/
 * resource/search/structure managers, id generator, naming service, IO factory,
 * reference provider, cloner and the canonical B+tree instance registry — and hands
 * them to the engine through plain getters.
 *
 * <p>The locator is a mutable holder, not thread-safe by itself: collaborators are
 * wired once by {@code initFileConfiguration} before the storage starts serving, after
 * which the getters are read-only. Until initialization the accessors return
 * {@code null} fields (metrics yields a throwaway instance).
 * Engine-internal — not part of the public API.</p>
 */
public class XdStorageServicesLocator {

    /** Wiring happens through {@code initFileConfiguration}; an explicit constructor adds nothing. */
    public XdStorageServicesLocator() {
    }

    private IXdStorage storage;

    private ExecutorService executor;

    private XdStorageConfig storageConfig = XdStorageConfig.defaults();

    private XdStorageMetrics metrics;

    private IXdStorageTransactionManager transactionsManager;

    private XdStorageTriggerManager triggersManager;

    private XdStorageAbstractResourcesManager resourcesManager;

    private IXdStorageSearchManager searchManager;

    private XdStorageStructureManager structureManager;

    private IXdStorageIdGenerator idGenerator;

    private IXdStorageResourceNamingService namingService;

    private IXdStorageIOFactory ioFactory;

    private IXdStorageReferenceProvider referencesProvider;

    private IXdStorageCloner cloner;

    private IXdStorageForeignKeyEngine foreignKeyEngine;

    /**
     * Wires a file-backed storage with the default engine configuration.
     *
     * @param storage       the owning storage facade
     * @param folder        filesystem folder holding the storage resources
     * @param fragmentSize  fragment size for the file-backed resources
     * @param format        on-disk serialization format
     */
    public void initFileConfiguration(final IXdStorage storage, final String folder, final int fragmentSize, FilesFormat format) {
        initFileConfiguration(storage, folder, fragmentSize, format, XdStorageConfig.defaults());
    }

    /**
     * Wires all collaborators of a file-backed storage: naming service, id generator,
     * IO factory, trigger/transaction/resource/structure/search managers, reference
     * provider and cloner, plus the worker executor and metrics. The configuration is
     * stored first because collaborators read it at construction time.
     *
     * @param storage       the owning storage facade
     * @param folder        filesystem folder holding the storage resources
     * @param fragmentSize  fragment size for the file-backed resources
     * @param format        on-disk serialization format
     * @param config        engine configuration; {@code null} falls back to the defaults
     */
    public void initFileConfiguration(final IXdStorage storage, final String folder, final int fragmentSize,
                                      final FilesFormat format, final XdStorageConfig config) {
        // конфиг обязателен ДО создания коллабораторов: ioFactory/кэши читают его при конструировании
        this.storageConfig = config == null ? XdStorageConfig.defaults() : config;
        this.metrics = new XdStorageMetrics();
        this.storage = storage;
        this.executor = Executors.newFixedThreadPool(storageConfig.getWorkerPoolSize(), workerThreadFactory(storageConfig));
        this.namingService = new XdStorageResourceNamingService(folder, format.name().toLowerCase());
        this.idGenerator = new XdStorageDefaultIdGenerator(this);
//        this.ioFactory = new XdStorageDefaultIOFactory(this, idGenerator);
        this.ioFactory = XdStorageIOFactory.instanceIOFactory(this, idGenerator, format);
        this.triggersManager = new XdStorageTriggerManager();
        this.transactionsManager = new XdStorageTransactionManager(this);
        this.resourcesManager = new XdStorageResourcesManager(this, fragmentSize);
        this.structureManager = new XdStorageStructureManager(this);
        this.searchManager = new XdStorageSearchManager(this);
        this.referencesProvider = new XdStorageDefaultReferenceProvider(this.storage);
        this.cloner = new XdStorageSmartCloner(this.referencesProvider);
        XdStorageForeignKeyResourcesManager foreignKeyResourcesManager = new XdStorageForeignKeyResourcesManager(this, resourcesManager);
        this.foreignKeyEngine = new XdStorageForeignKeyEngine(foreignKeyResourcesManager, new XdStorageForeignKeyRegistry(), storageConfig.isEnableForeignKeys());
    }

    /** S-4: именованные воркеры (диагностика дампов) + опционально daemon (забытый shutdown() не вешает JVM). */
    private static ThreadFactory workerThreadFactory(final XdStorageConfig config) {
        final boolean daemon = config.isDaemonWorkerThreads();
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, "xdstorage-worker-" + counter.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
    }

    /**
     * Returns the active engine configuration.
     *
     * @return the configuration installed at init (defaults until then)
     */
    public XdStorageConfig getStorageConfig() {
        return storageConfig;
    }

    /**
     * Returns the metrics registry of this storage.
     *
     * @return the registry installed at init, or a fresh throwaway instance when the
     *         locator is not initialized
     */
    public XdStorageMetrics getMetrics() {
        return metrics != null ? metrics : new XdStorageMetrics();
    }

    /**
     * Returns the owning storage facade.
     *
     * @return the storage passed to {@code initFileConfiguration}, {@code null} before init
     */
    public IXdStorage getStorage() {
        return storage;
    }

    /**
     * Returns the fixed worker thread pool created at init.
     *
     * @return the worker executor, {@code null} before init
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Returns the transaction manager of this storage.
     *
     * @return the manager wired at init, {@code null} before init
     */
    public IXdStorageTransactionManager getTransactionsManager() {
        return transactionsManager;
    }

    /**
     * Returns the trigger manager of this storage.
     *
     * @return the manager wired at init, {@code null} before init
     */
    public XdStorageTriggerManager getTriggersManager() {
        return triggersManager;
    }

    /**
     * Returns the resources manager of this storage.
     *
     * @return the manager wired at init, {@code null} before init
     */
    public XdStorageAbstractResourcesManager getResourcesManager() {
        return resourcesManager;
    }

    /**
     * Returns the search manager of this storage.
     *
     * @return the manager wired at init, {@code null} before init
     */
    public IXdStorageSearchManager getSearchManager() {
        return searchManager;
    }

    /**
     * Returns the structure manager of this storage.
     *
     * @return the manager wired at init, {@code null} before init
     */
    public XdStorageStructureManager getStructureManager() {
        return structureManager;
    }

    /**
     * Returns the id generator of this storage.
     *
     * @return the generator wired at init, {@code null} before init
     */
    public IXdStorageIdGenerator getIdGenerator() {
        return idGenerator;
    }

    /**
     * Returns the resource naming service of this storage.
     *
     * @return the service wired at init, {@code null} before init
     */
    public IXdStorageResourceNamingService getNamingService() {
        return namingService;
    }

    /**
     * Returns the IO factory of this storage.
     *
     * @return the factory wired at init, {@code null} before init
     */
    public IXdStorageIOFactory getIoFactory() {
        return ioFactory;
    }

    /**
     * Returns the cloner of this storage.
     *
     * @return the cloner wired at init, {@code null} before init
     */
    public IXdStorageCloner getCloner() {
        return cloner;
    }

    /** ФАЗА 3B: реестр канонических инстансов B+Дерева (на один storage). */
    private final io.github.xdstorage.resource.XdStorageCanonicalInstanceRegistry canonicalInstances =
            new io.github.xdstorage.resource.XdStorageCanonicalInstanceRegistry();

    /**
     * Returns the per-storage registry of canonical B+tree instances.
     *
     * @return the registry (created with the locator, empty until the tree managers populate it)
     */
    public io.github.xdstorage.resource.XdStorageCanonicalInstanceRegistry getCanonicalInstances() {
        return canonicalInstances;
    }

    /**
     * Returns the reference provider of this storage.
     *
     * @return the provider wired at init, {@code null} before init
     */
    public IXdStorageReferenceProvider getReferencesProvider() {
        return referencesProvider;
    }

    public IXdStorageForeignKeyEngine getForeignKeyEngine() {
        return foreignKeyEngine;
    }

    /**
     * Shuts the worker thread pool down and clears the canonical B+tree instance
     * registry. Called once when the storage closes.
     */
    public void shutdown() {
        this.executor.shutdown();
        this.canonicalInstances.clear();
    }
}
