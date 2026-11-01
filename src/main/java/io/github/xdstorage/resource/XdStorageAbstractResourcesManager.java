package io.github.xdstorage.resource;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.index.IXdStorageIndexResourceObject;
import io.github.xdstorage.search.IXdStorageSearchIndexDaoResource;
import io.github.xdstorage.search.IXdStorageSearchIndexResourceObject;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The registry and lock point of all open resources of a storage. Hands out DAO views of
 * resources locked (and created on first use) for a transaction, registering each resource
 * into the transaction with its commit-order group (see {@link XdStorageResourceCommitOrder})
 * so the transaction can commit resources in a fixed order; keeps per-resource usage counters
 * and per-class object counters (the latter drive fragment selection for
 * {@code StoreAsClassObjects} resources and are checkpointed by
 * {@link #checkpointAllJournals()}).
 *
 * <p>Thread-safety: instances are shared by all threads of a storage. All state lives in
 * concurrent maps; resource creation, lock counting and fragment-slot reservation are atomic
 * CAS/{@code computeIfAbsent} operations — no coarse monitor is held across resource
 * preparation or transaction critical sections.</p>
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public abstract class XdStorageAbstractResourcesManager {

    /** Engine services shared with collaborators (storage, config, naming, transactions manager). */
    protected final XdStorageServicesLocator services;

    /** Open resources by resource id; entries live until storage shutdown. */
    protected final Map<Object, IXdStorageResourceObject<?>> resources;

    /**
     * [resource_id, count_objects]
     *
     * <p>Per-resource object counters, grouped by entity class: the number of records the
     * resource holds, used to pick a fragment with a free slot and kept coherent by
     * {@link #updateCounter}, {@link #updateCounterByObjectAdded} and
     * {@link #updateCounterByObjectRemoved}.</p>
     */
    protected final Map<Class<?>, Map<Object, AtomicLong>> countObjects;

    private final Map<Object, AtomicLong> locks;

    /**
     * Builds a manager bound to the given engine services.
     *
     * @param services engine services shared with collaborators
     */
    public XdStorageAbstractResourcesManager(final XdStorageServicesLocator services) {
        this.services = services;
        this.resources = new ConcurrentHashMap<>();
        this.countObjects = new ConcurrentHashMap<>();
        this.locks = new ConcurrentHashMap<>();

    }

    /**
     * The naming service resolving entity classes and indexes to resource ids.
     *
     * @return the naming service
     */
    public IXdStorageResourceNamingService getNamingService() {
        return services.getNamingService();
    }

    /**
     * Locks (creating on first use) the entity class's id-index resource and registers it
     * into the transaction. Meaningful only for policies with a class-level index
     * ({@code StoreAsClassObjects}, {@code StoreWithParentObject}) — for others no resource
     * is created and the call fails loudly.
     *
     * @param clInfo      metadata of the entity class
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked index resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageIndexDaoResource lockIndexResource(final XdStorageClassInfo clInfo,
                                                        final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorageIndexResourceObject resource = null;
        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == XdStoragePolicy.StoreAsClassObjects || policy == XdStoragePolicy.StoreWithParentObject) {
            final Object resourceId = getNamingService().getIndexResourceId("index", policy, clInfo.getClazz());
            resource = internalLockResource(new IResourceFactory() {
                @Override
                public Object getResourceId() {
                    return resourceId;
                }

                @Override
                public IXdStorageResourceObject create() {
                    return createIndexResource(resourceId, "index", clInfo);
                }

                @Override
                public int getCommitOrder() {
                    return XdStorageResourceCommitOrder.INDEX_RESOURCE_ORDER;
                }
            }, transaction);
        }
        return resource.getDao();
    }

    /**
     * Creates the entity class's id-index resource — the subclass-supplied factory behind
     * {@link #lockIndexResource}.
     *
     * @param resourceId the storage-wide unique resource id
     * @param indexName  the index name
     * @param clInfo     metadata of the entity class
     * @return a new index resource object (not yet prepared)
     */
    protected abstract IXdStorageIndexResourceObject createIndexResource(final Object resourceId, final String indexName,
                                                                         final XdStorageClassInfo clInfo);

    /**
     * Locks (creating on first use) the data resource with the given resource id — typically
     * the structure-adjacent or explicitly addressed resource — and registers it into the
     * transaction.
     *
     * @param resourceId  the storage-wide unique resource id
     * @param clInfo      metadata of the entity class the resource stores
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageDaoResource lockResource(final Object resourceId, final XdStorageClassInfo clInfo,
                                              final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final IXdStorageResourceObject<IXdStorageDaoResource> resource = internalLockResource(new IResourceFactory() {
            @Override
            public Object getResourceId() {
                return resourceId;
            }

            @Override
            public IXdStorageResourceObject create() {
                return createResource(resourceId, clInfo);
            }

            @Override
            public int getCommitOrder() {
                return XdStorageResourceCommitOrder.DATA_RESOURCE_ORDER;
            }
        }, transaction);
        return resource.getDao();
    }

    /**
     * Locks (creating on first use) the data resource holding the object with the given id —
     * for a single-object policy the object's individual file, otherwise the class-shared
     * resource resolved by the naming service — and registers it into the transaction.
     *
     * @param clInfo      metadata of the entity class
     * @param id          the object id naming the resource
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageDaoResource lockResource(final XdStorageClassInfo clInfo, final Object id,
                                              final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final XdStoragePolicy policy = clInfo.getPolicy();
        final Object resourceId = getNamingService().getResourceId(policy, clInfo.getClazz(), null, id);
        final IXdStorageResourceObject<IXdStorageDaoResource> resource = internalLockResource(new IResourceFactory() {
            @Override
            public Object getResourceId() {
                return resourceId;
            }

            @Override
            public IXdStorageResourceObject create() {
                return createResource(resourceId, clInfo);
            }

            @Override
            public int getCommitOrder() {
                return XdStorageResourceCommitOrder.DATA_RESOURCE_ORDER;
            }
        }, transaction);
        return resource.getDao();
    }

    /**
     * Creates the data resource for the given resource id — the subclass-supplied factory
     * behind the {@code lockResource} family.
     *
     * @param resourceId the storage-wide unique resource id
     * @param clInfo     metadata of the entity class the resource stores
     * @return a new data resource object (not yet prepared)
     */
    protected abstract IXdStorageResourceObject<IXdStorageDaoResource> createResource(final Object resourceId,
                                                                                      final XdStorageClassInfo clInfo);

    /**
     * Locks (creating on first use) the entity class's references resource — the
     * parent/child bookkeeping file of a single-object policy ({@code StoreAsSingleObject})
     * — and registers it into the transaction. For other policies no resource is created
     * and the call fails loudly.
     *
     * @param clInfo      metadata of the entity class
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked references resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageDaoResource lockReferencesResource(final XdStorageClassInfo clInfo,
                                                        final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorageResourceObject<IXdStorageDaoResource> resource = null;
        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == XdStoragePolicy.StoreAsSingleObject) {
            final Object resourceId = getNamingService().getIndexResourceId("references", policy, clInfo.getClazz());
            resource = internalLockResource(new IResourceFactory() {
                @Override
                public Object getResourceId() {
                    return resourceId;
                }

                @Override
                public IXdStorageResourceObject create() {
                    return createReferencesResource(resourceId, clInfo);
                }

                @Override
                public int getCommitOrder() {
                    return XdStorageResourceCommitOrder.INDEX_RESOURCE_ORDER;
                }
            }, transaction);
        }
        return resource.getDao();
    }

    /**
     * Creates the references resource for the given resource id — the subclass-supplied
     * factory behind {@link #lockReferencesResource}.
     *
     * @param resourceId the storage-wide unique resource id
     * @param clInfo     metadata of the entity class
     * @return a new references resource object (not yet prepared)
     */
    protected abstract IXdStorageResourceObject<IXdStorageDaoResource> createReferencesResource(final Object resourceId,
                                                                                                final XdStorageClassInfo clInfo);

    /**
     * Locks (creating on first use) the class-shared data resource of a
     * {@code StoreAsClassObjects} entity and registers it into the transaction. For other
     * policies no resource is created and the call fails loudly.
     *
     * @param object      the entity instance the operation is performed for
     * @param clInfo      metadata of the entity class
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked class-shared resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageDaoResource lockClassResource(final Object object, final XdStorageClassInfo clInfo,
                                                   final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorageResourceObject<IXdStorageDaoResource> resource = null;
        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            final Object resourceId = getNamingService().getResourceId(policy, clInfo.getClazz(), null, null);
            resource = internalLockResource(new IResourceFactory() {
                @Override
                public Object getResourceId() {
                    return resourceId;
                }

                @Override
                public IXdStorageResourceObject create() {
                    return createResource(resourceId, clInfo);
                }

                @Override
                public int getCommitOrder() {
                    return XdStorageResourceCommitOrder.DATA_RESOURCE_ORDER;
                }
            }, transaction);
        }
        return resource.getDao();
    }

    /**
     * Locks (creating on first use) the data resource owning the given entity object — for
     * a {@code StoreAsSingleObject} policy the object's individual file — and registers it
     * into the transaction. For other policies no resource is created and the call fails
     * loudly.
     *
     * @param <T>         the entity class
     * @param object      the entity instance whose resource is locked
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public <T> IXdStorageDaoResource lockObjectResource(final T object,
                                                        final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorageResourceObject<IXdStorageDaoResource> resource = null;
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == XdStoragePolicy.StoreAsSingleObject) {
            final Object resourceId = getNamingService().getResourceId(policy, cl, object, null);
            resource = internalLockResource(new IResourceFactory() {
                @Override
                public Object getResourceId() {
                    return resourceId;
                }

                @Override
                public IXdStorageResourceObject create() {
                    return createResource(resourceId, clInfo);
                }

                @Override
                public int getCommitOrder() {
                    return XdStorageResourceCommitOrder.DATA_RESOURCE_ORDER;
                }
            }, transaction);
        }
        return resource.getDao();
    }

    /**
     * Locks (creating on first use) the data resource owning the object with the given id —
     * for a {@code StoreAsSingleObject} policy the object's individual file — and registers
     * it into the transaction. For other policies no resource is created and the call fails
     * loudly.
     *
     * @param clInfo      metadata of the entity class
     * @param id          the object id naming the resource
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageDaoResource lockObjectResource(final XdStorageClassInfo clInfo, final Object id,
                                                    final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        IXdStorageResourceObject<IXdStorageDaoResource> resource = null;
        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == XdStoragePolicy.StoreAsSingleObject) {
            final Object resourceId = getNamingService().getResourceId(policy, clInfo.getClazz(), null, id);
            resource = internalLockResource(new IResourceFactory() {
                @Override
                public Object getResourceId() {
                    return resourceId;
                }

                @Override
                public IXdStorageResourceObject create() {
                    return createResource(resourceId, clInfo);
                }

                @Override
                public int getCommitOrder() {
                    return XdStorageResourceCommitOrder.DATA_RESOURCE_ORDER;
                }
            }, transaction);
        }
        return resource.getDao();
    }

    /**
     * Locks (creating on first use) the named search index resource of the entity class and
     * registers it into the transaction.
     *
     * @param clInfo      metadata of the entity class
     * @param indexName   the search index name
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked search index resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageSearchIndexDaoResource lockSearchIndexResource(final XdStorageClassInfo clInfo, final String indexName,
                                                                    final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final XdStoragePolicy policy = clInfo.getPolicy();
        final Object resourceId = getNamingService().getIndexResourceId(indexName, policy, clInfo.getClazz());
        final IXdStorageSearchIndexResourceObject resource = internalLockResource(new IResourceFactory() {
            @Override
            public Object getResourceId() {
                return resourceId;
            }

            @Override
            public IXdStorageResourceObject create() {
                return createSearchIndexResource(resourceId, indexName, clInfo);
            }

            @Override
            public int getCommitOrder() {
                return XdStorageResourceCommitOrder.SEARCH_INDEX_RESOURCE_ORDER;
            }
        }, transaction);
        return resource.getDao();
    }

    /**
     * Creates the named search index resource — the subclass-supplied factory behind
     * {@link #lockSearchIndexResource}.
     *
     * @param resourceId the storage-wide unique resource id
     * @param indexName  the search index name
     * @param clInfo     metadata of the entity class
     * @return a new search index resource object (not yet prepared)
     */
    protected abstract IXdStorageSearchIndexResourceObject createSearchIndexResource(final Object resourceId, String indexName, XdStorageClassInfo clInfo);

    /**
     * Locks (creating on first use) the entity class's structure resource — the class
     * metadata record — and registers it into the transaction.
     *
     * @param clInfo      metadata of the entity class
     * @param transaction the transaction to lock and register the resource for
     * @return the DAO view of the locked structure resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public IXdStorageDaoResource lockStructureResource(final XdStorageClassInfo clInfo,
                                                       final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object resourceId = getNamingService().getStructureResourceId(clInfo.getClazz());
        final IXdStorageResourceObject<IXdStorageDaoResource> resource = internalLockResource(new IResourceFactory() {
            @Override
            public Object getResourceId() {
                return resourceId;
            }

            @Override
            public IXdStorageResourceObject create() {
                return createResource(resourceId, clInfo);
            }

            @Override
            public int getCommitOrder() {
                return XdStorageResourceCommitOrder.DATA_RESOURCE_ORDER;
            }
        }, transaction);
        return resource.getDao();
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
        IXdStorageResourceObject create();

        /**
         * The commit-order group the resource is registered into the transaction with
         * (a constant from {@link XdStorageResourceCommitOrder}).
         *
         * @return the commit order of the resource kind
         */
        int getCommitOrder();
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
    protected final <T extends IXdStorageResourceObject> T internalLockResource(final IResourceFactory factory,
                                                                                final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object resourceId = factory.getResourceId();

        // Гарантируем атомарное создание счетчика локов без synchronized барьеров
        final AtomicLong counter = locks.computeIfAbsent(resourceId, k -> new AtomicLong(0));

        // ИСПРАВЛЕНИЕ ДЕДЛОКА СУБД: Полностью ликвидируем опасный synchronized(counter)!
        // Используем атомарный метод computeIfAbsent на ConcurrentHashMap для создания ресурса.
        // Это полностью устраняет перекрестное заклинивание потоков на мониторах счетчиков ресурсов
        // и критических секциях транзакций во время параллельного выполнения стресс-теста!
        IXdStorageResourceObject returnResource = resources.computeIfAbsent(resourceId, k -> {
            IXdStorageResourceObject res = factory.create();
            final Class<?> cl = res.getObjectsClass();
            countObjects.computeIfAbsent(cl, c -> new ConcurrentHashMap<>()).put(resourceId, new AtomicLong(0));
            return res;
        });

        // Безопасно регистрируем ресурс в транзакции за пределами системных блокировок менеджера
        if (!transaction.isResourceRegistered(returnResource)) {
            transaction.registerResource(returnResource, factory.getCommitOrder());
            counter.incrementAndGet();
        }

        returnResource.prepare(transaction);
        return (T) returnResource;
    }

    /**
     * Decrements the resource's usage counter when the resource is known to this manager.
     * The resource object itself is kept in the registry (re-creating it per release would
     * re-parse the file and fork the cache state); resources are finally discarded at
     * storage shutdown.
     *
     * @param resource the resource being released by a transaction
     */
    public void releaseResource(final IXdStorageResourceObject resource) {
        final Object resourceId = resource.getResourceId();
        final AtomicLong counter = locks.get(resourceId);

        if (counter != null) {
            // R-55: РЕСУРС БОЛЬШЕ НЕ УДАЛЯЕТСЯ ПРИ ОБНУЛЕНИИ СЧЕТЧИКА. Раньше последний
            // release выкидывал resource-объект из карты, и следующий захват создавал НОВЫЙ
            // инстанс с пустым кэшем записей (кэш у каждого ресурса СВОЙ) и prepared=false:
            // (1) файл ресурса заново парсился с диска — горячие ноды Б+Дерева перемалывали
            //     десятки тысяч parse-циклов под штормом (дисковый IO под деревными локами —
            //     топливо find retry-budget exhaustion);
            // (2) пока «осиротевший» ресурс, зарегистрированный в живой транзакции, работал
            //     параллельно с новосозданным, существовали ДВЕ реальности одного файла:
            //     записи писались в кэш сироты, а новый ресурс читал УСТАРЕВШИЙ диск — канал
            //     рассинхрона структуры валидатора и потерянных записей;
            // (3) recreate сбрасывал слотовый счетчик фрагмента в 0 (гонка выбора фрагмента).
            // Удержание ресурса делает карту ресурсов ограниченной числом когда-либо открытых
            // файлов (объекты и так живут в record-кэшах — потолок heap≈DB задокументирован
            // как предстоящий W-4). Освобождение ресурсов происходит в storage.shutdown().

            counter.decrementAndGet();
        }
    }

    /**
     * Resets the class's counter for the resource to the resource cache's current size —
     * called after a bulk state load (preparation), not per object.
     *
     * @param cl       the entity class the counter belongs to
     * @param resource the resource whose cache size becomes the counter value
     */
    public void updateCounter(final Class<?> cl, final IXdStorageResourceObject<?> resource) {
        final Map<Object, AtomicLong> objectsCounters = countObjects.get(cl);
        final AtomicLong counter = objectsCounters.get(resource.getResourceId());
        counter.set(resource.getObjectsCount());
    }

    /**
     * Increments the class's object counter for the resource — called when an object is
     * staged as inserted.
     *
     * @param cl       the entity class the counter belongs to
     * @param resource the resource the object was added to
     */
    public void updateCounterByObjectAdded(final Class<?> cl, final IXdStorageResourceObject<?> resource) {
        final Map<Object, AtomicLong> objectsCounters = countObjects.get(cl);
        final AtomicLong counter = objectsCounters.get(resource.getResourceId());
        counter.incrementAndGet();
    }

    /**
     * Decrements the class's object counter for the resource — called when an object is
     * staged as deleted.
     *
     * @param cl       the entity class the counter belongs to
     * @param resource the resource the object was removed from
     */
    public void updateCounterByObjectRemoved(final Class<?> cl, final IXdStorageResourceObject<?> resource) {
        final Map<Object, AtomicLong> objectsCounters = countObjects.get(cl);
        final AtomicLong counter = objectsCounters.get(resource.getResourceId());
        counter.decrementAndGet();
    }

    /**
     * Picks a data resource (fragment) of the class that still has a free slot and atomically
     * reserves that slot by advancing its object counter (CAS loop) — the side effect is the
     * reservation, so the caller must actually insert into the returned resource. Scans
     * fragments in counter-map order, skipping full ones.
     *
     * @param cl            the entity class to allocate in
     * @param fragmentSize  the maximum number of objects per fragment
     * @return the resource id of the fragment with the reserved slot, or {@code null} when
     *         the class has no counters yet or every fragment is full
     */
    public Object reserveFreeResourceId(final Class<?> cl, final long fragmentSize) {
        final Map<Object, AtomicLong> objectsCounters = countObjects.get(cl);
        if (objectsCounters != null) {
            for (final Map.Entry<Object, AtomicLong> entry : objectsCounters.entrySet()) {
                final AtomicLong counter = entry.getValue();

                // НЕБЛОКИРУЮЩИЙ CAS-БАРЬЕР: Атомарно резервируем слот под будущий объект
                // прямо на этапе сканирования фрагментов. Это полностью исключает ситуацию,
                // когда 50 параллельных потоков выбирают один и тот же файл для записи,
                // предотвращая переполнение страниц и порчу дисковой базы данных!
                while (true) {
                    long currentVal = counter.get();
                    if (currentVal >= fragmentSize) {
                        break; // Текущий фрагмент заполнен полностью, идем к следующему
                    }

                    // Пытаемся занять слот через Compare-And-Swap операцию процессора
                    if (counter.compareAndSet(currentVal, currentVal + 1)) {
                        return entry.getKey(); // Успешное бронирование, отдаем ID файла!
                    }
                    // Если другой поток успел занять слот раньше - заходим на повторную CAS-проверку
                }
            }
        }
        return null;
    }
    /**
     * W-4c (docs/tasks/2.1.0/32, W4-DESIGN.md): best-effort shutdown checkpoint of every
     * journal-bearing resource — full committed-state rewrite + journal reset. After a
     * clean close every journal is empty, so a later power loss loses nothing even with
     * {@code durableWrites=false}. Never throws; returns the number of journals
     * checkpointed (busy or unprepared resources are skipped — their journals replay at
     * the next open).
     *
     * @return the number of journals actually checkpointed
     */
    public int checkpointAllJournals() {
        int checkpointed = 0;
        for (final IXdStorageResourceObject<?> resource : resources.values()) {
            if (resource instanceof IXdStorageJournalCheckpoint) {
                try {
                    if (((IXdStorageJournalCheckpoint) resource).checkpointJournalOnShutdown()) {
                        checkpointed++;
                    }
                } catch (final Throwable t) {
                    // безвредно: журнал остается — реплей при следующем открытии
                }
            }
        }
        return checkpointed;
    }
}
