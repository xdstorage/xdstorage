package io.github.xdstorage.index.btree;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.btree.IXdStorageBTreeViewer;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeNode;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.index.IXdStorageIndexResourceObject;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageResourceCache;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageAtomicFile;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The B+tree-backed id-index resource. It doubles as the transactional persistence resource
 * for the engine's own {@code XdStorageBTree} objects (its object class is {@code
 * XdStorageBTree}) and as the id-index DAO for one entity class: object ids are routed
 * through the entity's per-class tree (id {@code "id_index"}) to the data fragment holding
 * the record, so lookups are tree searches inside the caller's transaction snapshot with a
 * pass-through to the data-fragment resource. Because structural key removals by a
 * concurrent commit are not versioned, every key a transaction's snapshot has seen is
 * remembered and served on repeat by-id reads (fallback after a tree miss). The commit
 * phases persist the cached tree objects via a crash-safe atomic file replace; commits are
 * serialized by a {@code ReentrantLock} bounded by the transaction timeout, and mutations
 * are followed by (sampled) leaf-chain invariant validation.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public class XdStorageBTreeIndexResource implements IXdStorageIndexResourceObject, IXdStorageIndexDaoResource {

    private static final Logger log = LoggerFactory.getLogger(XdStorageBTreeIndexResource.class);

    /** Engine services locator (storage access, config, IO factory, id generator). */
    protected final XdStorageServicesLocator services;

    /** The resources manager used to lock and release the data-fragment resources. */
    protected final XdStorageAbstractResourcesManager manager;

    /** Transaction resource cache holding the persisted tree objects ({@code XdStorageBTree}). */
    protected final XdStorageResourceCache cache;

    /** The storage-wide unique id of this resource (its file path). */
    protected final Object resourceId;

    /** The logical name of the index this resource serves. */
    protected final String indexName;

    private final long fragmentSize;

    /** Class metadata of the tree object class this resource persists ({@code XdStorageBTree}). */
    protected final XdStorageClassInfo clInfo;

    /** Id-field accessor of the tree object class. */
    protected final XdStorageObjectIdField idField;

    /** Class metadata of the ENTITY class being indexed. */
    protected final XdStorageClassInfo objectClInfo;

    /** Id-field accessor of the ENTITY class being indexed. */
    protected final XdStorageObjectIdField objectIdField;

    private final IXdStorageObjectsReader reader;

    private final IXdStorageObjectsWriter writer;

    private final AtomicBoolean prepared = new AtomicBoolean(false);

    private final Lock lock = new ReentrantLock();

    /**
     * Creates the resource.
     *
     * @param services        the engine services locator
     * @param manager         the resources manager owning this resource
     * @param resourceId      the storage-wide unique resource id (the backing file path)
     * @param indexName       the logical index name
     * @param objectClassInfo metadata of the ENTITY class being indexed
     * @param fragmentSize    the record capacity of one data fragment
     */
    public XdStorageBTreeIndexResource(final XdStorageServicesLocator services,
                                       final XdStorageAbstractResourcesManager manager,
                                       final Object resourceId, final String indexName,
                                       final XdStorageClassInfo objectClassInfo, final int fragmentSize) {
        this.services = services;
        this.manager = manager;
        this.resourceId = resourceId;
        this.indexName = indexName;
        this.fragmentSize = fragmentSize;
        this.objectClInfo = objectClassInfo;
        this.objectIdField = objectClassInfo.getIdField();

        this.clInfo = XdStorageObjectUtils.getClassInfo(XdStorageBTree.class);
        this.idField = this.clInfo.getIdField();

        this.cache = new XdStorageResourceCache(services, this.clInfo.getClazz(), this.idField);
        this.cache.setIsolationLevel(XdStorageIsolationLevel.SNAPSHOT);

        this.reader = services.getIoFactory().newInstanceReader();
        this.writer = services.getIoFactory().newInstanceWriter();
    }

    /** @return the storage-wide unique id of this resource */
    @Override
    public Object getResourceId() {
        return resourceId;
    }

    /**
     * @return the object class this resource persists — always {@code XdStorageBTree}
     */
    @Override
    public Class<?> getObjectsClass() {
        return XdStorageBTree.class;
    }

    /** @return the number of tree objects currently held by the resource cache */
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

    private Map<String, Long> blockingTime = new HashMap<>();

    private AtomicBoolean locked = new AtomicBoolean(false);

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
        // ИСПРАВЛЕНИЕ ДЕДЛОКА СУБД: Тотально вырезаем опасный synchronized(locked) и locked.wait()!
        // Переводим блокировку B+ Дерева индексов на честный ReentrantLock 'lock' с поддержкой tryLock()
        // по таймауту транзакции. Если индекс заблокирован параллельным коммитом, поток чисто
        // дождется его или выйдет по таймауту, категорически пресекая циклические Deadlock блокировки!
        final long timeout = transaction != null ? transaction.getTimeout() : 3000L;
        try {
            boolean acquired = lock.tryLock(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new XdStorageRuntimeException("B+Tree Index Resource " + getFileName() + " cannot be locked for commit by transaction "
                        + transaction.getTransactionId() + " (Timeout " + timeout + " ms expired)");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XdStorageRuntimeException("waiting for lock index resource " + getFileName() + " has been interrupted", e);
        }
    }

    /**
     * Releases the tree commit lock acquired by {@code #lockForCommit} (no-op when the
     * current thread does not hold it).
     *
     * @param transaction the transaction releasing the lock
     */
    @Override
    public void unlockAfterCommit(final XdStorageTransaction transaction) {
        // ИСПРАВЛЕНИЕ ДЕДЛОКА СУБД: Освобождаем ReentrantLock вместо synchronized-уведомлений
        if (((ReentrantLock) lock).isHeldByCurrentThread()) {
            lock.unlock();
        }
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
        Collection<Object> objects = null;
        try {
            final File file = new File(getFileName());
            if (file.exists()) {
                Reader xmlReader = new java.io.BufferedReader(new FileReader(file), 1 << 16);
                try {
                    objects = reader.read(xmlReader);
                } finally {
                    xmlReader.close();
                }
            }
        } catch (final Throwable e) {
            throw new XdStorageRuntimeException("invalid state of database: error by reading resource " + resourceId, e);
        }
        return objects;
    }

    private void writeObjects(final Collection<Object> objects) {
        if (objects.size() == 0) {
            // пустой ресурс = файла нет (прежняя семантика)
            final File file = new File(getFileName());
            if (file.exists())
                file.delete();
            return;
        }

        try {
            // crash-safe: пишем во временный файл-соседа и атомарно подменяем ресурс,
            // чтобы сбой посреди коммита не мог обрезать существующий файл ресурса
            XdStorageAtomicFile.writeReplacing(getFileName(), services.getStorageConfig().isDurableWrites(), out -> {
                writer.writeObjects(out, objects);
            });
        } catch (final Throwable e) {
            throw new XdStorageRuntimeException("invalid state of database: error by writing resource " + resourceId, e);
        }
    }

    /**
     * Reads the persisted tree objects of this resource as raw identifiable data (id plus
     * serialized payload), without materializing them through the entity machinery.
     * Absent file yields {@code null}.
     *
     * @param transaction the transaction context of the read (unused by the file read itself)
     * @return the persisted identifiable objects, or {@code null} when the backing file
     *         does not exist
     * @throws XdStorageRuntimeException when the file exists but cannot be parsed — treated
     *         as an invalid database state
     */
    public Collection<XdStorageIdentifiableObject> readAsData(final XdStorageTransaction transaction) {
        Collection<XdStorageIdentifiableObject> objects = null;
        try {
            final File file = new File(getFileName());
            if (file.exists()) {
                Reader xmlReader = new java.io.BufferedReader(new FileReader(file), 1 << 16);
                try {
                    objects = reader.readData(xmlReader, idField);
                } finally {
                    xmlReader.close();
                }
            }
        } catch (final Throwable e) {
            throw new XdStorageRuntimeException("invalid state of database: error by reading resource " + resourceId, e);
        }
        return objects;
    }

    /**
     * Prepares the resource for use: on the first call loads the backing file into the
     * tree-object cache (double-checked under the resource lock), later calls are no-ops.
     *
     * @param transaction the transaction the resource is prepared for
     * @throws XdStorageRuntimeException when the backing file exists but cannot be read —
     *         treated as an invalid database state
     */
    public void prepare(final XdStorageTransaction transaction) {
        if (prepared.get()) {
            return;
        }

        lock.lock();
        try {
            if (prepared.get()) {
                return;
            }

            Collection<Object> objects = readAsObjects();

            if (objects != null)
                cache.fillCache(objects);

            prepared.set(true);
        } finally {
            lock.unlock();
        }
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
    public void performFirstPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        if (cache.hasChanges(transaction)) {
            final Collection<Object> objects = cache.readUnsafe(transaction);
            cache.prepareCommit(transaction, record);
            writeObjects(objects);
        }
    }

    /**
     * Phase 2 of the two-phase commit: publishes the phase-1 persisted changes into the
     * tree-object cache, making them visible to subsequent transactions.
     *
     * @param transaction the committing transaction
     * @param record      the collector filled by phase 1
     * @throws XdStorageException on a storage-level failure
     */
    public void performSecondPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        if (cache.hasChanges(transaction)) {
            cache.performCommit(transaction, record);
            cache.commit(transaction);
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
    public void rollbackPerformingFirstPhaseCommit(final XdStorageTransaction transaction, final Collection<XdStorageObjectChange> changes) throws XdStorageException {
        cache.rollbackFailedCommit(transaction, changes);
        writeObjects(cache.readUnsafe(transaction));
        seenKeysByTransaction.remove(transaction.getTransactionId());
    }

    /**
     * Discards the transaction's staged changes in this resource (pre-commit rollback);
     * the persistent state is untouched, and the transaction's seen-keys snapshot is
     * dropped.
     *
     * @param transaction the transaction being rolled back
     * @throws XdStorageException on a storage-level failure
     */
    public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
        cache.rollback(transaction);
        seenKeysByTransaction.remove(transaction.getTransactionId());
    }

    /**
     * Releases the resource: clears the transaction's cache contours, drops the
     * transaction's seen-keys snapshot and returns the resource to the manager.
     *
     * @param transaction the transaction releasing the resource
     */
    @Override
    public void release(final XdStorageTransaction transaction) {
        cache.clear(transaction);
        seenKeysByTransaction.remove(transaction.getTransactionId());
        manager.releaseResource(this);
    }

    /**
     * Resolves the data-fragment resource id holding the record of the given object id by
     * searching the entity's tree inside the transaction snapshot; on a tree miss after a
     * concurrent structural delete, falls back to the transaction's seen-keys snapshot.
     *
     * @param id          the object id to look up
     * @param transaction the transaction whose snapshot defines visibility
     * @return the data-fragment resource id, or {@code null} when the id is unknown to the
     *         index and the snapshot
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    public Object getObjectResourceId(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final IXdStorage storage = services.getStorage();
        final XdStorageBTreeId bTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "id_index");
        final XdStorageBTree tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
        if (XdStorageBTreeNode.CHAIN_DEBUG && tree != null) {
            System.out.println("[CHAIN] find id=" + id + " tree@" + System.identityHashCode(tree)
                    + " root=" + (tree.getRoot() != null ? tree.getRoot().getId() : null)
                    + " rootKeys=" + (tree.getRoot() != null ? tree.getRoot().getKeys() : null)
                    + " firstLeaf=" + (tree.getFirstLeaf() != null ? tree.getFirstLeaf().getId() : null)
                    + " firstLeafKeys=" + (tree.getFirstLeaf() != null ? tree.getFirstLeaf().getKeys().size() : null));
        }
        if (tree != null) {
            final List<Object> result = tree.find((Comparable) id, storage, transaction);
            if (!result.isEmpty()) {
                return result.get(0);
            }
            if (XdStorageBTreeNode.CHAIN_DEBUG) {
                System.out.println("[CHAIN] MISS id=" + id + " tree@" + System.identityHashCode(tree));
            }
            // SNAPSHOT-фолбэк: ключ уже удален из дерева чужим коммитом, но снапшот этой
            // транзакции его видел (скан/чтение до коммита) — дороже промаха берем
            // зафиксированный data-фрагмент; кэш данных отдаст историческую версию.
            final Map<Object, Object> seenKeys = seenKeysByTransaction.get(transaction.getTransactionId());
            if (seenKeys != null) {
                return seenKeys.get(id);
            }
        }
        return null;
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
    @Override
    public boolean has(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return getObjectResourceId(id, transaction) != null;
    }

    /**
     * Reads every indexed object by walking the entity's tree in leaf order and loading
     * each id's record from its data fragment. Snapshot-invisible objects (removed by a
     * concurrent commit during the walk) are skipped, and every served id is remembered
     * in the transaction's seen-keys snapshot.
     *
     * @param <T>         the element type the result collection is viewed as
     * @param transaction the transaction whose snapshot defines visibility
     * @return the objects visible to this transaction (never {@code null})
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public <T> Collection<T> read(final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final List<T> result = new ArrayList<>();

        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId bTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "id_index");
        final XdStorageBTree tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
        if (tree != null) {
            tree.read(storage, transaction, new IXdStorageBTreeViewer() {
                @Override
                public void look(final Comparable objectId, final Object resourceId) throws XdStorageException, XdStorageConnectionException {
                    final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
                    final T object = (T) resource.read(objectId, transaction);
                    // ФАЗА 2: индекс без версионирования может сослаться на объект, невидимый
                    // данному snapshot (закоммиченное удаление при обходе гонки структуры).
                    // Null в результирующей коллекции роняет NPE-ем циклы потребителей — пропуск.
                    if (object != null) {
                        rememberSeenKey(transaction, objectId, resourceId);
                        result.add(object);
                    }
                }
            });
        }

        return result;
    }

    /**
     * Reads the indexed objects that pass the predicate, by the same tree walk as {@code
     * #read(XdStorageTransaction)}; the predicate sees each candidate wrapped as an
     * unmodifiable view. A predicate failure aborts the walk with the collected error.
     *
     * @param <T>         the element type the result collection is viewed as
     * @param transaction the transaction whose snapshot defines visibility
     * @param predicate   the filter applied to each candidate object
     * @return the matching objects visible to this transaction (never {@code null})
     * @throws XdStorageException           on a storage-level failure, including a predicate
     *         failure collected during the walk
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public <T> Collection<T> read(final XdStorageTransaction transaction, final IXdStoragePredicate<T> predicate) throws XdStorageException, XdStorageConnectionException {
        final List<T> result = new ArrayList<>();

        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId bTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "id_index");
        final XdStorageBTree tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
        if (tree != null) {
            final XdStorageException exceptions[] = new XdStorageException[]{null};
            final AtomicBoolean hasError = new AtomicBoolean(false);
            tree.read(storage, transaction, new IXdStorageBTreeViewer() {
                @Override
                public void look(final Comparable objectId, final Object resourceId) throws XdStorageException, XdStorageConnectionException {
                    if (hasError.get()) {
                        return;
                    }

                    try {
                        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
                        final T object = (T) resource.read(objectId, transaction);
                        // ФАЗА 2: пропуск snapshot-невидимых объектов (см. полный read)
                        if (object == null) {
                            return;
                        }
                        rememberSeenKey(transaction, objectId, resourceId);
                        if (predicate.passed(XdStorageObjectUtils.wrapAsUnmodifiableObject(object, storage, transaction))) {
                            result.add(object);
                        }
                    } catch (final Throwable e) {
                        if (hasError.get()) {
                            return;
                        }
                        hasError.set(true);
                        exceptions[0] = new XdStorageException(e);
                    }
                }
            });
            if (exceptions[0] != null)
                throw exceptions[0];
        }

        return result;
    }

    /**
     * Delivers every indexed object (wrapped as an unmodifiable view) to the watcher, by
     * the same tree walk as {@code #read(XdStorageTransaction)}.
     *
     * @param <T>         the element type the watcher receives
     * @param transaction the transaction whose snapshot defines visibility
     * @param watcher     the callback invoked once per visible object
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public <T> void watch(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException {
        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId bTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "id_index");
        final XdStorageBTree tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
        if (tree != null) {
            tree.read(storage, transaction, new IXdStorageBTreeViewer() {
                @Override
                public void look(final Comparable objectId, final Object resourceId) throws XdStorageException, XdStorageConnectionException {
                    final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
                    final T object = (T) resource.read(objectId, transaction);
                    // ФАЗА 2: пропуск snapshot-невидимых объектов (см. полный read)
                    if (object == null) {
                        return;
                    }
                    watcher.watch(XdStorageObjectUtils.wrapAsUnmodifiableObject(object, storage, transaction));
                }
            });
        }
    }

    /**
     * Populates the given reference object in place from its stored record: the reference's
     * id is resolved through the tree, then the data fragment fills the reference.
     *
     * @param reference   the reference object to populate (its id field must be set)
     * @param transaction the transaction whose snapshot defines visibility
     * @throws XdStorageException           when the index does not know the reference's id,
     *         or on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public void readByReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object objectId = objectIdField.get(reference);
        final Object resourceId = getObjectResourceId(objectId, transaction);
        if (resourceId == null) {
            throw new XdStorageException("cannot load by reference object of class " + reference.getClass() + " with idgeneration " + objectId);
        }
        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        resource.readByReference(reference, transaction);
    }

    /**
     * Loads one object by id: resolves the id through the tree and reads the record from
     * its data fragment, remembering a served id in the transaction's seen-keys snapshot.
     *
     * @param id          the object id to load
     * @param transaction the transaction whose snapshot defines visibility
     * @return the stored object, or {@code null} when the id is unknown (or the record is
     *         invisible to this transaction's snapshot)
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public Object read(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object resourceId = getObjectResourceId(id, transaction);
        if (resourceId == null) {
            return null;
        }
        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        final Object object = resource.read(id, transaction);
        if (object != null) {
            // фиксируем снапшот-видимость ключа для повторных чтений по id этой транзакции
            rememberSeenKey(transaction, id, resourceId);
        }
        return object;
    }

    private final Lock createTreeLock = new ReentrantLock();

    /**
     * Ключи id_index, видимые транзакции на момент ее скана/чтения (id → data-фрагмент).
     * Структурные удаления ключа из дерева не версионированы: чужой коммит удаляет ключ
     * из дерева НАВСЕГДА, и скан, увидевший ключ до коммита, при повторном чтении по id
     * получит промах дерева вместо исторической версии. Карта позволяет by-id дороже
     * промаха дерева взять resourceId, зафиксированный снапшотом транзакции.
     */
    private final Map<String, Map<Object, Object>> seenKeysByTransaction = new ConcurrentHashMap<>();

    private void rememberSeenKey(final XdStorageTransaction transaction, final Object objectId, final Object resourceId) {
        seenKeysByTransaction.computeIfAbsent(transaction.getTransactionId(),
                k -> new ConcurrentHashMap<>()).put(objectId, resourceId);
    }

    /**
     * Indexes a new object: generates the id when the entity's id generator requires it and
     * the id is still unset, creates (or adopts from the canonical-instance registry) the
     * entity's tree, stores the record into a data fragment, inserts the id {@code ->}
     * fragment key into the tree (registering a compensating key removal for transaction
     * rollback) and runs the sampled leaf-chain invariant validation.
     *
     * @param object      the object to insert
     * @param transaction the transaction the insert is staged into
     * @throws XdStorageException           on a storage-level failure (including a failed
     *         structural-integrity barrier, which rolls the transaction back loudly)
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public void insert(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final IXdStorageIdGenerator idGenerator = services.getIdGenerator();

        Object objectId = objectIdField.get(object);
        // BUG-D (docs/tasks/2.1.0/14): UUID_GENERATOR generates here too — the mux
        // dispatches by id type and validates UUID_GENERATOR to String id fields.
        if (objectId == null &&
                (objectIdField.getIdGeneretorType() == XdStorageIdGeneratorType.DATABASE_GENERATOR
                        || objectIdField.getIdGeneretorType() == XdStorageIdGeneratorType.UUID_GENERATOR
                        || objectIdField.getIdGeneretorType() == XdStorageIdGeneratorType.CUSTOM_GENERATOR)
                && idGenerator != null) {
            objectIdField.set(XdStorageObserverService.getObservableWrapper(object), objectId = idGenerator.generate(object.getClass(), services.getStorage(), transaction));
        }

        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId bTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "id_index");
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
                    tree = new XdStorageBTree(bTreeId, false, objectClInfo.getIndexFillingValue());
                    storage.save(tree, transaction);
                }
            } finally {
                createTreeLock.unlock();
            }
        }

        final Object resourceId = manager.getFreeResourceId(objectClInfo.getClazz(), fragmentSize);
        final IXdStorageDaoResource resource;
        if (resourceId == null) {
            resource = manager.lockResource(objectClInfo, objectId, transaction);
        } else {
            resource = manager.lockResource(resourceId, objectClInfo, transaction);
        }

        resource.insert(object, transaction);

        tree.insert((Comparable) objectId, resource.getResourceId(), storage, transaction);
        // R-57: ключ успешно в живом дереве — при откате транзакции будет удален компенсирующе
        transaction.registerIndexKeyForRollback(tree, (Comparable) objectId);

        // =========================================================================
        // БАРЬЕР СТРУКТУРНОЙ ЦЕЛОСТНОСТИ ИНДEКСА: мутации id_index обязаны завершаться
        // валидацией цепочки листьев. При нарушении транзакция откатывается громко,
        // а не фиксирует битую структуру на диск молча (урок цепочки 268/1250).
        // =========================================================================
        // R-32: полная валидация сэмплируется (каждая 25-я операция); между сэмплами
        // структурную целостность держат tripwire-ы checkStructuralAlignment в узлах
        if (io.github.xdstorage.btree.XdStorageBTreeInvariants.shouldRunFullValidation()) {
            io.github.xdstorage.btree.XdStorageBTreeInvariants.validateLeafChain(tree, storage, transaction,
                    "id_index insert of " + objectClInfo.getClazz().getSimpleName() + "." + objectId);
        }
    }

    /**
     * Updates the stored record of an existing indexed object: resolves the id through the
     * tree and delegates to the owning data fragment.
     *
     * @param object      the object to update (its id must already be indexed)
     * @param transaction the transaction the update is staged into
     * @throws XdStorageException           when the object's id is not indexed, or on a
     *         storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public void update(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object objectId = objectIdField.get(object);

        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId bTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "id_index");
        final XdStorageBTree tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
        if (tree == null) {
            throw new XdStorageException("object of class " + object.getClass() + " with idgeneration " + objectId + " does not exists");
        }

        final List<Object> result = tree.find((Comparable) objectId, storage, transaction);
        final Object resourceId = result.isEmpty() ? null : result.get(0);
        if (resourceId == null) {
            throw new XdStorageException("object of class " + object.getClass() + " with idgeneration " + objectId + " does not exists");
        }

        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        resource.update(object, transaction);
    }

    /**
     * Removes the stored record of an indexed object and its tree key (delegating the
     * record delete to the owning data fragment), followed by the sampled leaf-chain
     * invariant validation.
     *
     * @param object      the object to delete (its id must already be indexed)
     * @param transaction the transaction the delete is staged into
     * @throws XdStorageException           when the object's id is not indexed, or on a
     *         storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public void delete(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Object objectId = objectIdField.get(object);

        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId bTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "id_index");
        final XdStorageBTree tree = storage.load(XdStorageBTree.class, bTreeId, transaction);
        if (tree == null) {
            throw new XdStorageException("object of class " + object.getClass() + " with idgeneration " + objectId + " does not exists");
        }

        final List<Object> result = tree.find((Comparable) objectId, storage, transaction);
        final Object resourceId = result.isEmpty() ? null : result.get(0);
        if (resourceId == null) {
            throw new XdStorageException("object of class " + object.getClass() + " with idgeneration " + objectId + " does not exists");
        }

        final IXdStorageDaoResource resource = manager.lockResource(resourceId, objectClInfo, transaction);
        resource.delete(object, transaction);

        tree.delete((Comparable) objectId, storage, transaction);
        // R-57 (зеркало): удаление ключа — ин-плейс мутация живого дерева. При откате
        // транзакции record-состояния вернут запись и ссылки, но ключ остался бы
        // поглощенным навсегда ("cannot load by reference" на следующем индексном
        // чтении). Регистрируем пару для компенсирующей вставки при откате — дерево
        // обязано оставаться read-committed всегда.
        transaction.registerIndexKeyDeleteForRollback(tree, (Comparable) objectId, resourceId);

        // БАРЬЕР СТРУКТУРНОЙ ЦЕЛОСТНОСТИ: слияния листьев (join/borrow) — самый опасный
        // контур ребалансировки, валидируем цепочку сразу после удаления
        if (io.github.xdstorage.btree.XdStorageBTreeInvariants.shouldRunFullValidation()) {
            io.github.xdstorage.btree.XdStorageBTreeInvariants.validateLeafChain(tree, storage, transaction,
                    "id_index delete of " + objectClInfo.getClazz().getSimpleName() + "." + objectId);
        }
    }

    /** @return this resource itself — the tree-object resource is its own id-index DAO */
    @Override
    public IXdStorageIndexDaoResource getDao() {
        return this;
    }
}
