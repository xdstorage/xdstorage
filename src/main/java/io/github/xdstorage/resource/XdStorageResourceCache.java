package io.github.xdstorage.resource;

import io.github.xdstorage.resource.cache.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Кэш ресурсов (Identity Map + MVCC-контуры транзакций) — ФАСАД читающих операций
 * и поддержки инфраструктуры B+Дерева. Разделение обязанностей (см. OPTIMIZATION-PLAN,
 * R-46): детали engine переехали в подпакет io.github.xdstorage.resource.cache —
 * <ul>
 *   <li>{@link XdStorageCacheRecord} — MVCC-состояние записи, latch, видимость версий;</li>
 *   <li>{@link XdStorageTransactionWorkspace} — транзакционные контуры (write-set,
 *       журнал отката, неидентифицированные обертки, материализованное);</li>
 *   <li>{@link XdStorageCacheMutator} — контур мутаций insert/update/delete;</li>
 *   <li>{@link XdStorageCacheCommitProtocol} — 2PC-коммит, откат, откат упавшего коммита;</li>
 *   <li>{@link XdStorageCacheContext} — внутренний SPI фасада для коллабораторов.</li>
 * </ul>
 */
public class XdStorageResourceCache implements XdStorageCacheContext {

    private static final Logger log = LoggerFactory.getLogger(XdStorageResourceCache.class);

    private final XdStorageServicesLocator services;

    private final IXdStorageCloner cloner;

    private final XdStorageObjectIdField field;

    /** Реестр записей кэша: id объекта -> MVCC-запись. */
    private final Map<Object, XdStorageCacheRecord> cache = new ConcurrentHashMap<>();

    /** Транзакционные контуры (write-set / журнал отката / неидентифицированные / прочитанное). */
    private final XdStorageTransactionWorkspace workspace = new XdStorageTransactionWorkspace();

    /** Контур мутаций (insert/update/delete). */
    private final XdStorageCacheMutator mutator = new XdStorageCacheMutator(this);

    /** Протокол завершения транзакции (2PC / rollback). */
    private final XdStorageCacheCommitProtocol commitProtocol = new XdStorageCacheCommitProtocol(this);

    private final Lock lock = new ReentrantLock();

    private AtomicBoolean isWrappableClass;

    private Class<?> clazz;

    private volatile XdStorageIsolationLevel isolationLevel = XdStorageIsolationLevel.SNAPSHOT;

    /**
     * ФАЗА 3B: кэш инфраструктуры B+Дерева (деревья/узлы). Граф — общее мутируемое
     * состояние: читатели получают ЖИВОЙ канонический инстанс (без клонирования и без
     * пос-транзакционных копий), record-level MVCC к структуре не применяется.
     */
    private final boolean infrastructureCache;
    /** R-42: ноды дерева — внутренняя структура с живым инстансом-истиной (полная инфра-релаксация). */
    private final boolean structuralNodeCache;

    /**
     * Builds the cache facade for one resource: binds the entity class, its id field and the
     * engine services, picks the infrastructure/structural-node mode from the class name
     * (B+-tree internals are cached as live canonical graphs, not clones) and adopts the
     * storage's configured read isolation level.
     *
     * @param services engine services shared with collaborators
     * @param clazz    the entity class the cache stores, or {@code null} when unknown
     * @param field    metadata of the entity id field
     */
    public XdStorageResourceCache(final XdStorageServicesLocator services, final Class<?> clazz, final XdStorageObjectIdField field) {
        this.services = services;
        this.field = field;
        this.infrastructureCache = clazz != null && clazz.getName().contains("io.github.xdstorage.btree");
        // R-42: НОДЫ — внутренняя структура (истина = живой инстанс, сериализация нодовыми
        // write-локами) — полная инфра-релаксация. Само ДЕРЕВО — логическая сущность БД:
        // контракты фантомов (BTreeEntityTest) действуют на его удаление.
        this.structuralNodeCache = clazz != null && clazz.getName().equals("io.github.xdstorage.btree.XdStorageBTreeNode");
        this.clazz = clazz;
        this.cloner = this.services.getCloner();
        // O-3: уровень изоляции хранилища задается конфигом при открытии (раньше был
        // недостижим из публичного API); mock-локаторы в тестах остаются на SNAPSHOT
        final XdStorageConfig config = services.getStorageConfig();
        if (config != null && config.getIsolationLevel() != null) {
            this.isolationLevel = config.getIsolationLevel();
        }
    }

    void setInternalListener(IXdStorageCacheInternalListener listener) {
        this.mutator.setInternalListener(listener);
    }

    // =========================================================================
    // ВНУТРЕННИЙ SPI (XdStorageCacheContext) — то, что коллабораторам из
    // подпакета cache нужно от фасада; состояние кэша остается приватным
    // =========================================================================

    @Override
    public XdStorageObjectIdField idField() {
        return field;
    }

    @Override
    public Map<Object, XdStorageCacheRecord> records() {
        return cache;
    }

    @Override
    public XdStorageTransactionWorkspace workspace() {
        return workspace;
    }

    @Override
    public boolean isInfrastructureCache() {
        return infrastructureCache;
    }

    @Override
    public boolean isStructuralNodeCache() {
        return structuralNodeCache;
    }

    @Override
    public Class<?> entityClass() {
        return clazz;
    }

    @Override
    public boolean isTransactionAlive(final XdStorageTransaction transaction) {
        return services.getTransactionsManager().isTransactionAlive(transaction);
    }

    @Override
    public XdStorageCacheRecord createReadRecord(final Object object) {
        return XdStorageCacheRecord.newReadRecord(this, field, object);
    }

    @Override
    public XdStorageCacheRecord createInsertRecord(final Object newObject, final XdStorageTransaction transaction) {
        return XdStorageCacheRecord.newInsertRecord(this, field, newObject, transaction);
    }

    // =========================================================================
    // КОНФИГУРАЦИЯ / СЧЕТЧИКИ
    // =========================================================================

    @Override
    public XdStorageIsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    /**
     * Overrides the read isolation level of this cache ({@code null} falls back to
     * {@link XdStorageIsolationLevel#SNAPSHOT}). Meant for tests and explicit reconfiguration;
     * the open-time level comes from the storage config.
     *
     * @param isolationLevel the isolation level to apply, or {@code null} for the default
     */
    public void setIsolationLevel(final XdStorageIsolationLevel isolationLevel) {
        this.isolationLevel = isolationLevel == null ? XdStorageIsolationLevel.SNAPSHOT : isolationLevel;
    }

    /**
     * The number of records currently held by the cache (the prepared state of the resource).
     *
     * @return the cached record count
     */
    public long getObjectsCount() {
        return cache.size();
    }

    /**
     * Whether the cache holds a record with the given id (irrespective of snapshot
     * visibility).
     *
     * @param id the object id
     * @return {@code true} when a record with the id is cached
     */
    public boolean hasObject(final Object id) {
        return cache.containsKey(id);
    }

    // =========================================================================
    // НАПОЛНЕНИЕ КЭША (загрузка ресурса с диска)
    // =========================================================================

    /**
     * Loads parsed records into the cache as committed read records — the prepare-from-disk
     * path. Records already present are kept (first writer wins); for B+-tree infrastructure
     * the parsed copy yields to a live canonical instance when one is registered.
     *
     * @param objects the parsed objects to cache
     */
    public void fillCache(final Collection<Object> objects) {
        for (final Object object : objects) {
            // ФАЗА 3B: parse-копия уступает место живому каноническому инстансу (если он есть)
            final Object objectCanonical = adoptCanonicalInstance(object);
            cache.putIfAbsent(field.get(objectCanonical), createReadRecord(objectCanonical));
        }
    }

    /** W-1: replay of a committed journal UPSERT — base-парсинг уступает состоянию журнала (last writer wins), поэтому put, не putIfAbsent.
     *
     * @param object the committed object state from the journal
     */
    public void applyCommittedJournalUpsert(final Object object) {
        final Object objectCanonical = adoptCanonicalInstance(object);
        cache.put(field.get(objectCanonical), createReadRecord(objectCanonical));
    }

    /** W-1: replay of a committed journal DELETE.
     *
     * @param id the id of the record the journal deletes
     */
    public void applyCommittedJournalDelete(final Object id) {
        cache.remove(id);
    }

    /**
     * W-4c (docs/tasks/2.1.0/32, W4-DESIGN.md): the COMMITTED view of every cached record
     * for a shutdown checkpoint — no transaction, no isolation. {@code record.getObject()}
     * is the last committed body; a record locked by a live transaction yields its
     * pre-transaction committed body, which is exactly the state the base+journal pair
     * already describes (an abandoned tx never reached phase-1, a committing tx's redo is
     * in the journal and replay is idempotent). Records without a committed body
     * (uncommitted inserts) are skipped — their journal redo re-creates them at replay.
     *
     * @return the committed object bodies, ready for a full-file write-out
     */
    public java.util.Collection<Object> readCommittedForCheckpoint() {
        final java.util.List<Object> result = new java.util.ArrayList<>();
        for (final Object node : cache.values().toArray()) {
            if (node == null) {
                continue;
            }
            final XdStorageCacheRecord record = (XdStorageCacheRecord) node;
            final Object committed = record.getObject();
            if (committed != null) {
                result.add(committed);
            }
        }
        return result;
    }

    /**
     * W-2: per-transaction materialization budget (XdStorageConfig.txMaterializedObjectsCap,
     * 0 = unlimited). Checked at the exit of every READ-path materialization; WRITE_OUT
     * (коммитная выписка) сознательно не бюджетируется. Превышение — громкий отказ с
     * подсказкой вместо молчаливого роста кучи до OOM.
     */
    /** W-2: бюджет включают ТОЛЬКО ресурсы пользовательских данных (id-индексы — служебные записи, не бюджетируются). */
    private volatile boolean enforceMaterializationBudget;

    /**
     * Turns the per-transaction materialization budget on or off for this cache — enabled
     * only for user-data resources (id-index caches hold service records that are not
     * budgeted).
     *
     * @param value {@code true} to enforce the budget on the read paths
     */
    public void setEnforceMaterializationBudget(final boolean value) {
        this.enforceMaterializationBudget = value;
    }

    private void checkMaterializationBudget(final XdStorageTransaction transaction,
                                            final Map<Object, XdStorageCacheObjectChange> readObjects)
            throws XdStorageException {
        if (!enforceMaterializationBudget || readObjects == null) {
            return;
        }
        final XdStorageConfig config = services.getStorageConfig();
        final long cap = config != null ? config.getTxMaterializedObjectsCap() : 0L;
        if (cap > 0 && readObjects.size() > cap) {
            throw new XdStorageException("transaction " + transaction.getTransactionId()
                    + " materialization budget exceeded: " + readObjects.size() + " materialized objects > cap " + cap
                    + " — narrow the query (predicate / index search / watch) or raise txMaterializedObjectsCap");
        }
    }

    /**
     * Loads parsed records into the cache as committed read records without ever replacing
     * an existing record (unlike {@link #fillCache} this is a strict no-overwrite fill).
     *
     * @param <T>      the entity class of the objects
     * @param objects  the parsed objects to cache
     */
    public <T> void fillCacheOnlyIfNotExists(final Collection<T> objects) {
        for (final Object object : objects) {
            final Object objectCanonical = adoptCanonicalInstance(object);
            cache.putIfAbsent(field.get(objectCanonical), createReadRecord(objectCanonical));
        }
    }

    /**
     * Loads parsed records into the cache AND registers an isolated clone of every object in
     * the transaction's read contour (materialization), appending the instance the
     * transaction will actually see to {@code result}. Runs inside the transaction's
     * critical section.
     *
     * @param <T>         the entity class of the objects
     * @param objects     the parsed objects to load and materialize
     * @param result      the collection the materialized (clone/wrapped) objects are added to
     * @param transaction the transaction to materialize for
     * @throws XdStorageException when the critical section cannot be entered
     */
    public <T> void fillAndCloneCacheOnlyIfNotExists(final List<T> objects, final Collection<T> result, final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            fillAndCloneCacheOnlyIfNotExistsInternal(objects, result, transaction);
        } finally {
            transaction.finishCriticalSection();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void fillAndCloneCacheOnlyIfNotExistsInternal(final List<T> objects, final Collection<T> result, final XdStorageTransaction transaction) throws XdStorageException {
        log.debug("fillAndClone: {} object(s) into the read contour of tx {}", objects.size(), transaction.getTransactionId());
        final Map<Object, XdStorageCacheObjectChange> tmpReadObjects = workspace.readObjectsOf(transaction);

        for (final T object : objects) {
            final Object objectCanonical = adoptCanonicalInstance(object);
            final Object objectId = field.get(objectCanonical);
            final Object objectToCache = objectCanonical;
            final Object clonedObject = infrastructureCache
                    ? objectToCache
                    : cloner.cloneAndWrap(objectToCache, services.getStorage(), transaction);
            tmpReadObjects.putIfAbsent(objectId, new XdStorageCacheObjectChange(clonedObject, XdStorageCacheChange.read, System.nanoTime()));
            result.add((T) tmpReadObjects.get(objectId).object);
            cache.putIfAbsent(objectId, createReadRecord(objectToCache));
        }
    }

    /**
     * ФАЗА 3B: для инфраструктуры B+Дерева — канонизация parse-копии при наполнении кэша.
     * Если живой инстанс уже зарегистрирован (писатель мутирует его), parse-копия отбрасывается
     * и записи кэша получают канонический инстанс; иначе parse-копия сама регистрируется.
     */
    private Object adoptCanonicalInstance(final Object parsed) {
        if (!infrastructureCache || parsed == null) {
            return parsed;
        }
        // R-53: парсерные стабы (references.yaml/readReference -> newInstance с одним id)
        // НЕ канонизируются: references-ресурс инфраструктурного класса готовится раньше
        // по-id объектных файлов, и зарегистрированный стаб навсегда подменял собой
        // канонический инстанс (реальный парс затем отбрасывался как LOST-TO-EXISTING) —
        // materialize() получал пустой узел. Реестр держит только живые/полные инстансы.
        if (parsed instanceof io.github.xdstorage.code.IXdStorageReferenceMarked
                && ((io.github.xdstorage.code.IXdStorageReferenceMarked) parsed).isReference()) {
            return parsed;
        }
        final XdStorageCanonicalInstanceRegistry registry = services.getCanonicalInstances();
        if (registry == null) {
            return parsed; // мок-locator без реестра (юнит-сьюты)
        }
        final Object parsedId = field.get(parsed);
        if (parsedId == null) {
            return parsed;
        }
        final Object canonical = registry.adoptOrRegister(clazz, parsedId, parsed);
        if (log.isDebugEnabled() && canonical != parsed) {
            log.debug("infra instance {}#{} adopted from the canonical registry; parsed copy discarded", clazz.getSimpleName(), parsedId);
        }
        return canonical;
    }

    private boolean isWrappableObject(final Object object) {
        if (isWrappableClass == null) {
            lock.lock();
            try {
                if (isWrappableClass == null) {
                    isWrappableClass = new AtomicBoolean(XdStorageObjectUtils.isWrappableObject(object));
                }
            } finally {
                lock.unlock();
            }
        }
        return isWrappableClass.get();
    }

    // =========================================================================
    // НЕИДЕНТИФИЦИРОВАННЫЕ ОБЪЕКТЫ (observable-обертки без DB-id)
    // =========================================================================

    /**
     * Looks up the transaction-local unidentified (observable wrapper) form of the given
     * object, registered by an earlier read of the same not-yet-identified object. Runs
     * inside the transaction's critical section.
     *
     * @param <T>         the expected type of the registered object
     * @param object      the object whose transaction-local twin is looked up
     * @param transaction the owning transaction
     * @return the registered unidentified object, or {@code null} when none was registered
     * @throws XdStorageException when the critical section cannot be entered
     */
    public <T> T findUnidentified(final Object object, final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            return findUnidentifiedInternal(object, transaction);
        } finally {
            transaction.finishCriticalSection();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T findUnidentifiedInternal(final Object object, final XdStorageTransaction transaction) {
        if (IXdStorageIdObservableWrapper.class.isAssignableFrom(object.getClass())) {
            final Map<Object, IXdStorageIdObservableWrapper> unidentifiedObjects =
                    workspace.unidentified(transaction.getTransactionId());
            if (unidentifiedObjects != null) {

                return (T) unidentifiedObjects.get(object);
            }
        }
        return null;
    }

    // =========================================================================
    // СКАНИРУЮЩИЕ ЧТЕНИЯ (предикат / полный / unsafe)
    // =========================================================================

    /**
     * Snapshot scan filtered by the predicate: resolves every snapshot-visible record,
     * materializes it (per the cache's isolation level) and keeps those passing the filter.
     * Fails loudly on the first record whose materialization or filtering throws. Runs
     * inside the transaction's critical section and enforces the materialization budget.
     *
     * @param <T>         the entity class of the stored objects
     * @param transaction the transaction whose snapshot is scanned
     * @param predicate   the filter applied per materialized record
     * @return the snapshot-visible objects passing the filter
     * @throws XdStorageException on a storage-level failure, a record-level failure, or a
     *                            materialization budget violation
     */
    @SuppressWarnings("unchecked")
    public <T> Collection<T> read(final XdStorageTransaction transaction, final IXdStoragePredicate<T> predicate) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            final Collection<T> result = readInternal(transaction, predicate);
            checkMaterializationBudget(transaction, workspace.readObjectsOf(transaction));
            return result;
        } finally {
            transaction.finishCriticalSection();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Collection<T> readInternal(final XdStorageTransaction transaction, final IXdStoragePredicate<T> predicate) throws XdStorageException {
        final XdStorageException[] exceptions = new XdStorageException[]{null};
        final java.util.concurrent.atomic.AtomicBoolean hasError = new java.util.concurrent.atomic.AtomicBoolean(false);

        Map<Object, XdStorageCacheObjectChange> tmpReadObjects = workspace.readObjectsOf(transaction);
        final Map<Object, XdStorageCacheObjectChange> readObjects = tmpReadObjects;

        final List<T> result = Collections.synchronizedList(new java.util.LinkedList<>());

        // БЛOК 1: СУБД SNAPSHOT VIEW БАРЬЕР (Чтение по предикату)
        Object[] recordsSnapshot = cache.values().toArray();

        for (final Object node : recordsSnapshot) {
            if (hasError.get()) {
                break;
            }

            if (node == null) {
                continue;
            }
            XdStorageCacheRecord record = (XdStorageCacheRecord) node;

            Object object = null;
            synchronized (record) {
                object = record.resolveVisible(transaction, XdStorageCacheRecord.View.SCAN, 0L);
            }

            if (object != null) {
                try {
                    final Object objectId = field.get(object);
                    // Phase D-1: в READ COMMITTED перечитываем свежую копию ТОЛЬКО когда версия
                    // записи изменилась с момента материализации (чужой mark/commit) или это своя
                    // мутация. Раньше каждый скан переклонировал ВСЕ строки — O(N) клонов на скан.
                    final boolean rcRefresh = isolationLevel == XdStorageIsolationLevel.READ_COMMITTED;
                    final XdStorageCacheObjectChange materialized = readObjects.get(objectId);
                    final boolean needsRematerialize = materialized == null
                            || record.isChangedByTransaction(transaction)
                            || (rcRefresh && materialized.recordVersion != record.getVersion());
                    if (needsRematerialize) {
                        Object cloned = cloner.cloneAndWrap(object, services.getStorage(), transaction);
                        final XdStorageCacheObjectChange oc = new XdStorageCacheObjectChange(cloned, XdStorageCacheChange.read, System.nanoTime());
                        oc.recordVersion = record.getVersion();
                        if (rcRefresh) {
                            readObjects.put(objectId, oc);
                        } else {
                            readObjects.putIfAbsent(objectId, oc);
                        }
                    }
                    Object objectToReturn = readObjects.get(objectId).object;

                    // =========================================================================
                    // СУБД-БАРЬEР БEЗOПAСНOСТИ ПРEДИКАТА: Если объект не удалось обернуть в
                    // unmodifiable-прокси (wrapAsUnmodifiableObject вернул null), мы ОБЯЗАНЫ
                    // проверить предикат по сырому материализованному объекту, а не выбрасывать
                    // объект из выборки. Иначе диапазонное чтение молча теряет записи, которые
                    // видит полный скан read(transaction) — нарушение контракта Snapshot Isolation!
                    // =========================================================================
                    if (objectToReturn != null) {
                        final Object wrappedObject = XdStorageObjectUtils.wrapAsUnmodifiableObject(objectToReturn, services.getStorage(), transaction);
                        final Object objectForPredicate = wrappedObject != null ? wrappedObject : objectToReturn;
                        if (predicate.passed((T) objectForPredicate)) {
                            result.add((T) objectToReturn);
                        }
                    }
                } catch (final Throwable e) {
                    if (!hasError.get()) {
                        hasError.set(true);
                        exceptions[0] = new XdStorageException(e);
                    }
                }
            }
        }

        if (exceptions[0] != null)
            throw new XdStorageRuntimeException(exceptions[0]);

        final Map<Object, IXdStorageIdObservableWrapper> unidentifiedObjects =
                workspace.unidentified(transaction.getTransactionId());
        if (unidentifiedObjects != null) {
            Object[] unidentifiedSnapshot = unidentifiedObjects.values().toArray();
            for (Object nodeWrapper : unidentifiedSnapshot) {
                if (hasError.get()) break;
                if (nodeWrapper == null) continue;
                IXdStorageIdObservableWrapper wrapper = (IXdStorageIdObservableWrapper) nodeWrapper;
                try {
                    if (wrapper != null && predicate.passed((T) wrapper)) {
                        result.add((T) wrapper);
                    }
                } catch (final Throwable e) {
                    if (!hasError.get()) {
                        hasError.set(true);
                        exceptions[0] = new XdStorageException(e);
                    }
                }
            }

            if (exceptions[0] != null)
                throw new XdStorageRuntimeException(exceptions[0]);
        }

        return result;
    }

    /**
     * Streaming scan: feeds every object visible in the transaction's snapshot to the
     * watcher without materializing the whole result collection. A callback that throws
     * fails the scan. Runs inside the transaction's critical section and enforces the
     * materialization budget.
     *
     * @param <T>         the entity class the watcher observes
     * @param transaction the transaction whose snapshot is scanned
     * @param watcher     the per-object callback
     * @throws XdStorageException on a storage-level failure, a callback failure, or a
     *                            materialization budget violation
     */
    @SuppressWarnings("unchecked")
    public <T> void watch(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            watchInternal(transaction, watcher);
            checkMaterializationBudget(transaction, workspace.readObjectsOf(transaction));
        } finally {
            transaction.finishCriticalSection();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void watchInternal(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException {
        final XdStorageException[] exceptions = new XdStorageException[]{null};
        final AtomicBoolean hasError = new AtomicBoolean(false);

        Map<Object, XdStorageCacheObjectChange> tmpReadObjects = workspace.readObjectsOf(transaction);
        final Map<Object, XdStorageCacheObjectChange> readObjects = tmpReadObjects;

        // =========================================================================
        // СНАЙПEРСКИЙ ФИКС CLASSCASTEXCEPTION: Снимаем чистый Snapshot-массив Object[]!
        // Избавляемся от хрупкого принудительного каста (CacheRecord[]) всего массива.
        // =========================================================================
        Object[] recordsSnapshot = cache.values().toArray();

        Arrays.stream(recordsSnapshot).forEach(node -> {
            if (hasError.get() || node == null) {
                return;
            }
            XdStorageCacheRecord record = (XdStorageCacheRecord) node;

            Object object = null;
            synchronized (record) {
                object = record.resolveVisible(transaction, XdStorageCacheRecord.View.SCAN, 0L);
            }

            try {
                if (object != null) {
                    final Object objectId = field.get(object);
                    // READ COMMITTED: если чужой коммит изменил запись после материализации —
                    // сбрасываем устаревшую копию, ниже она пересоздастся (как в readInternal(id))
                    final XdStorageCacheObjectChange materialized = readObjects.get(objectId);
                    if (materialized != null && isolationLevel == XdStorageIsolationLevel.READ_COMMITTED
                            && materialized.recordVersion != record.getVersion()) {
                        readObjects.remove(objectId);
                    }
                    final Object objectToWrap;
                    if (readObjects.containsKey(objectId)) {
                        objectToWrap = readObjects.get(objectId).object;
                    } else if (isWrappableObject(object)) {
                        objectToWrap = object;
                    } else {
                        objectToWrap = XdStorageObjectUtils.cloneObject(object);
                        if (isolationLevel == XdStorageIsolationLevel.READ_COMMITTED) {
                            // запоминаем версию записи, чтобы следующее чтение увидело устаревание копии
                            final XdStorageCacheObjectChange refreshed = new XdStorageCacheObjectChange(objectToWrap, XdStorageCacheChange.read, System.nanoTime());
                            refreshed.recordVersion = record.getVersion();
                            readObjects.put(objectId, refreshed);
                        }
                    }
                    watcher.watch((T) XdStorageObjectUtils.wrapAsUnmodifiableObject(objectToWrap, services.getStorage(), transaction));
                }
            } catch (final Throwable e) {
                if (hasError.get()) {
                    return;
                }
                hasError.set(true);
                exceptions[0] = new XdStorageException(e);
            }
        });

        if (exceptions[0] != null)
            throw new XdStorageRuntimeException(exceptions[0]);

        final Map<Object, IXdStorageIdObservableWrapper> unidentifiedObjects =
                workspace.unidentified(transaction.getTransactionId());
        if (unidentifiedObjects != null) {
            unidentifiedObjects.values().stream().forEach(wrapper -> {
                if (hasError.get()) {
                    return;
                }

                try {
                    if (wrapper != null) {
                        watcher.watch((T) wrapper);
                    }
                } catch (final Throwable e) {
                    if (hasError.get()) {
                        return;
                    }
                    hasError.set(true);
                    exceptions[0] = new XdStorageException(e);
                }
            });
            if (exceptions[0] != null)
                throw new XdStorageRuntimeException(exceptions[0]);
        }
    }

    /**
     * Full snapshot scan: returns every object of the resource visible in the transaction's
     * snapshot, materializing (cloning and wrapping) records not yet materialized by this
     * transaction. Runs inside the transaction's critical section and enforces the
     * materialization budget.
     *
     * @param <T>         the entity class of the stored objects
     * @param transaction the transaction whose snapshot is scanned
     * @return the snapshot-visible objects of the resource
     * @throws XdStorageException on a storage-level failure or a materialization budget
     *                            violation
     */
    @SuppressWarnings("unchecked")
    public <T> Collection<T> read(final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            final Collection<T> result = readInternal(transaction);
            checkMaterializationBudget(transaction, workspace.readObjectsOf(transaction));
            return result;
        } finally {
            transaction.finishCriticalSection();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Collection<T> readInternal(final XdStorageTransaction transaction) throws XdStorageException {
        Map<Object, XdStorageCacheObjectChange> readObjects = workspace.readObjectsOf(transaction);

        final List<T> result = Collections.synchronizedList(new ArrayList<>());

        // БЛOК 1: СУБД SNAPSHOT VIEW БАРЬЕР (Полный скан кэша)
        Object[] recordsSnapshot = cache.values().toArray();

        for (final Object node : recordsSnapshot) {
            if (node == null) continue;
            XdStorageCacheRecord record = (XdStorageCacheRecord) node;

            Object object = null;
            synchronized (record) {
                object = record.resolveVisible(transaction, XdStorageCacheRecord.View.SCAN, 0L);
            }

            if (object != null) {
                final Object objectId = field.get(object);
                synchronized (result) {
                    synchronized (record) {
                        // Phase D-1: в RC перечитываем ТОЛЬКО при изменившейся версии записи
                        // (или своя мутация); SNAPSHOT — материализация при отсутствии, как раньше
                        final XdStorageCacheObjectChange materialized = readObjects.get(objectId);
                        final boolean needsRematerialize = materialized == null
                                || record.isChangedByTransaction(transaction)
                                || (isolationLevel == XdStorageIsolationLevel.READ_COMMITTED
                                    && materialized.recordVersion != record.getVersion());
                        if (needsRematerialize) {
                            Object cloned = cloner.cloneAndWrap(object, services.getStorage(), transaction);
                            final XdStorageCacheObjectChange oc = new XdStorageCacheObjectChange(cloned, XdStorageCacheChange.read, System.nanoTime());
                            oc.recordVersion = record.getVersion();
                            readObjects.put(objectId, oc);
                        }
                    }
                    result.add((T) readObjects.get(objectId).object);
                }
            }
        }

        final Map<Object, IXdStorageIdObservableWrapper> unidentifiedObjects =
                workspace.unidentified(transaction.getTransactionId());
        if (unidentifiedObjects != null) {
            Object[] unidentifiedSnapshot = unidentifiedObjects.values().toArray();
            for (final Object wrapper : unidentifiedSnapshot) {
                if (wrapper != null) {
                    result.add((T) wrapper);
                }
            }
        }
        return result;
    }

    /**
     * The write-out view: resolves records for the commit path ({@code View.WRITE_OUT}) and
     * prefers the transaction's own staged versions over the resolved state — no wrapping,
     * no budget. Callers persist this collection in commit phase 1.
     *
     * @param <T>         the entity class of the stored objects
     * @param transaction the committing transaction
     * @return the objects to persist for the transaction
     * @throws XdStorageException when the critical section cannot be entered
     */
    public <T> Collection<T> readUnsafe(final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            return readUnsafeInternal(transaction);
        } finally {
            transaction.finishCriticalSection();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Collection<T> readUnsafeInternal(final XdStorageTransaction transaction) {
        Map<Object, XdStorageCacheObjectChange> readObjects = workspace.readObjectsOf(transaction);

        final List<T> result = Collections.synchronizedList(new ArrayList<>());

        // БЛOК 1: СУБД SNAPSHOT VIEW БАРЬЕР (Unsafe скан)
        Object[] recordsSnapshot = cache.values().toArray();

        for (final Object node : recordsSnapshot) {
            if (node == null) continue;
            XdStorageCacheRecord record = (XdStorageCacheRecord) node;

            Object object = null;
            synchronized (record) {
                object = record.resolveVisible(transaction, XdStorageCacheRecord.View.WRITE_OUT, 0L);
            }
            if (object != null) {
                final Object objectId = field.get(object);
                final XdStorageCacheObjectChange readObject = readObjects.get(objectId);
                if (readObject != null && readObject.change != XdStorageCacheChange.read) {
                    result.add((T) readObject.object);
                } else {
                    result.add((T) object);
                }
            }
        }

        final Map<Object, IXdStorageIdObservableWrapper> unidentifiedObjects =
                workspace.unidentified(transaction.getTransactionId());
        if (unidentifiedObjects != null) {
            Object[] unidentifiedSnapshot = unidentifiedObjects.values().toArray();
            for (final Object wrapper : unidentifiedSnapshot) {
                if (wrapper != null) {
                    result.add((T) wrapper);
                }
            }
        }
        return result;
    }

    // =========================================================================
    // ЧТЕНИЕ ПО ID И ПО ССЫЛКЕ
    // =========================================================================

    /**
     * Reads the record with the given id as visible in the transaction's snapshot,
     * materializing it on first access (per the cache's isolation level). Runs inside the
     * transaction's critical section and enforces the materialization budget.
     *
     * @param id          the object id
     * @param transaction the transaction whose snapshot is read
     * @return the materialized object, or {@code null} when the id is absent or invisible
     *         in the snapshot
     * @throws XdStorageException on a storage-level failure or a materialization budget
     *                            violation
     */
    public Object read(final Object id, final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            final Object result = readInternal(id, transaction);
            checkMaterializationBudget(transaction, workspace.readObjectsOf(transaction));
            return result;
        } finally {
            transaction.finishCriticalSection();
        }
    }

    private Object readInternal(final Object id, final XdStorageTransaction transaction) throws XdStorageException {
        Map<Object, XdStorageCacheObjectChange> tmpReadObjects = workspace.readObjectsOf(transaction);
        final Map<Object, XdStorageCacheObjectChange> readObjects = tmpReadObjects;

        XdStorageCacheRecord record = cache.get(id);

        // ФАЗА 3B: барьер невидимой незакоммиченной вставки/апдейта для инфраструктуры дерева
        // (реестр канонических инстансов теперь канонизирует parse-копии при fillCache — R-19/R-20)
        if (infrastructureCache && record != null && waitForRecordResolution(record, id, transaction)) {
            record = cache.get(id);
        }
        // ФАЗА 3B: записи нет (eviction / откат создателя удалил незакоммиченную запись) —
        // канонический ЖИВОЙ инстанс остается в реестре; восстанавливаем запись кэша из него.
        // Без этого читатель/писатель дерева получал null ("does not exists") при живом графе.
        if (infrastructureCache && record == null) {
            final XdStorageCanonicalInstanceRegistry registry = services.getCanonicalInstances();
            final Object canonical = registry != null ? registry.get(clazz, id) : null;
            if (canonical != null) {
                log.warn("cache record for infra object {}#{} was lost — restored from the canonical registry",
                        clazz.getSimpleName(), id);
                cache.putIfAbsent(id, createReadRecord(canonical));
                record = cache.get(id);
            }
        }
        XdStorageCacheObjectChange materialized = readObjects.get(id);

        // READ COMMITTED: если версия записи изменилась с момента материализации
        // (чужой коммит) — сбрасываем устаревшую материализацию, ниже она пересоздастся.
        // SNAPSHOT: отдаем материализованную копию как есть (повторяемое чтение).
        if (materialized != null && isolationLevel == XdStorageIsolationLevel.READ_COMMITTED
                && (record == null || materialized.recordVersion != record.getVersion())) {
            readObjects.remove(id);
            materialized = null;
        }

        // Если объект уже материализован текущей транзакцией — мгновенно возвращаем его (Identity)
        if (materialized != null) {
            return materialized.object;
        }

        if (record != null) {
            Object object = null;

            if (infrastructureCache) {
                // ФАЗА 3B: живой канонический граф — без клонирования, без пос-транзакционных копий
                synchronized (record) {
                    object = record.getObject() != null
                            ? record.getObject()
                            : (record.isChangedByTransaction(transaction) ? record.getNewObject() : null);
                }
                if (object != null) {
                    readObjects.putIfAbsent(id, new XdStorageCacheObjectChange(object, XdStorageCacheChange.read, System.nanoTime()));
                    return readObjects.get(id).object;
                }
                if (Boolean.getBoolean("xdstorage.chain.debug")) {
                    synchronized (record) {
                        System.out.println("[CHAIN] INFRA-NULL id=" + id + " tx=" + transaction.getTransactionId()
                                + " state=" + record.getState() + " own=" + record.isChangedByTransaction(transaction)
                                + " owner=" + record.getOwnerTransactionId());
                    }
                }
                return null;
            }

            // Канонический MVCC-барьер вычисления видимости в оперативной памяти
            synchronized (record) {
                // R-45: если запись удерживает ЧУЖАЯ транзакция, находящаяся В ФАЗЕ КОММИТА,
                // и снапшот читателя уже включает ее единый commit-serial — читатель обязан
                // видеть НОВОЕ состояние (все эффекты коммита атомарно), несмотря на то что
                // фаза-2 этой конкретной записи еще не дошла до нее. Без этого читатель между
                // фазами разных ресурсов видел новую систему со ссылками на еще "невидимые"
                // планеты ("cannot load by reference") — дыра атомарности коммита GraphPhantom.
                final long committingSerial = record.committingSerialVisibleTo(transaction);
                object = record.resolveVisible(transaction, XdStorageCacheRecord.View.BY_ID, committingSerial);

                if (object != null) {
                    if (!readObjects.containsKey(id) || record.isChangedByTransaction(transaction)) {
                        Object cloned = cloner.cloneAndWrap(object, services.getStorage(), transaction);
                        final XdStorageCacheObjectChange oc = new XdStorageCacheObjectChange(cloned, XdStorageCacheChange.read, System.nanoTime());
                        oc.recordVersion = record.getVersion();
                        readObjects.put(id, oc);
                    }
                    return readObjects.get(id).object;
                }
            }
        }
        return null;
    }

    /**
     * Hydrates the passed reference object in place from the snapshot-visible state of the
     * record with the same id; fails with a storage exception when the object cannot be
     * loaded, and with a reference-invisible exception when the record exists but is not
     * visible in the reader's snapshot (normal MVCC semantics, not corruption). Runs inside
     * the transaction's critical section and enforces the materialization budget.
     *
     * @param reference   the reference object to fill (must carry an id)
     * @param transaction the transaction whose snapshot the load resolves in
     * @throws XdStorageException when the referenced object cannot be loaded
     */
    public void readByReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            readByReferenceInternal(reference, transaction);
            checkMaterializationBudget(transaction, workspace.readObjectsOf(transaction));
        } finally {
            transaction.finishCriticalSection();
        }
    }

    private void readByReferenceInternal(final Object reference, final XdStorageTransaction transaction) throws XdStorageException {
        Map<Object, XdStorageCacheObjectChange> readObjects = workspace.readObjectsOf(transaction);

        final Object objectId = field.get(reference);
        if (objectId == null) {
            return;
        }

        XdStorageCacheRecord record = cache.get(objectId);

        // ФАЗА 3B: записи нет — восстанавливаем из реестра канонических инстансов (см. readInternal)
        if (infrastructureCache && record == null) {
            final XdStorageCanonicalInstanceRegistry registry = services.getCanonicalInstances();
            final Object canonical = registry != null ? registry.get(clazz, objectId) : null;
            if (canonical != null) {
                log.warn("cache record for infra object {}#{} was lost — restored from the canonical registry",
                        clazz.getSimpleName(), objectId);
                cache.putIfAbsent(objectId, createReadRecord(canonical));
                record = cache.get(objectId);
            }
        }

        // =========================================================================
        // ФАЗА 3B: БАРЬЕР НЕВИДИМОЙ НЕЗАКОММИЧЕННОЙ ВСТАВКИ (только инфраструктура дерева).
        // Параллельный писатель публикует указатели на новые узлы (сплиты) немедленно,
        // а сами узлы видимы после коммита. Читатель, зашедший по такому указателю,
        // ограниченно ждет развязки чужой транзакции (коммит -> узел виден; откат ->
        // запись изъята из кэша и честно считается отсутствующей).
        // =========================================================================
        if (infrastructureCache && record != null && waitForRecordResolution(record, objectId, transaction)) {
            record = cache.get(objectId);
        }

        Object object = null;

        if (record != null) {
            if (infrastructureCache) {
                // ФАЗА 3B: живой канонический инстанс — наполняем ссылку из канона
                synchronized (record) {
                    object = record.getObject() != null
                            ? record.getObject()
                            : (record.isChangedByTransaction(transaction) ? record.getNewObject() : null);
                }
                if (object != null) {
                    cloner.fillAndWrap(reference, object, services.getStorage(), transaction);
                    readObjects.putIfAbsent(objectId, new XdStorageCacheObjectChange(reference, XdStorageCacheChange.read, System.nanoTime()));
                    return;
                }
                throw new XdStorageException("cannot load by reference object of " + reference.getClass() + " with idgeneration " + field.get(reference));
            }
            synchronized (record) {
                // R-58: гидрация ссылки обязана быть СОГЛАСОВАННОЙ СО СНАПШОТОМ читателя.
                // View.SCAN отдавал ЖИВУЮ версию записи: читатель-снапшот получал коллекцию,
                // уже содержащую чужие незакоммиченные/более новые ссылки (S-с-фантомом),
                // а сами фантомы по своим записям оставались невидимыми — «висячие» ссылки
                // и крах "cannot load by reference". View.BY_ID несет снапшот-гейт
                // (prevObject/commitSerial, R-45): читатель видит ровно ту версию, что
                // и остальной свой снапшот.
                object = record.resolveVisible(transaction, XdStorageCacheRecord.View.BY_ID,
                        record.committingSerialVisibleTo(transaction));

                if (object != null) {
                    // READ COMMITTED: наполняем ссылку последним закоммиченным состоянием,
                    // а не устаревшей материализацией из транзитной карты
                    cloner.fillAndWrap(reference, object, services.getStorage(), transaction);
                    readObjects.putIfAbsent(objectId, new XdStorageCacheObjectChange(reference, XdStorageCacheChange.read, System.nanoTime()));
                    return;
                }
            }
        }

        // R-58: запись существует, но невидима в снапшоте читателя (закоммичена после
        // начала его транзакции / транзитно откатена) — штатный ответ MVCC
        // «в вашей версии графа этого элемента нет», а не повреждение структуры.
        if (record != null) {
            if (Boolean.getBoolean("xdstorage.refdiag")) {
                final StringBuilder diag = new StringBuilder("[REFDIAG] invisible by reference ")
                        .append(clazz.getSimpleName()).append('#').append(objectId)
                        .append(" update=").append(record.isUpdateChange())
                        .append(" insert=").append(record.isInsertChange())
                        .append(" delete=").append(record.isDeleteChange())
                        .append(" committed=").append(record.isCommitedState())
                        .append(" ownerAlive=").append(services.getTransactionsManager().isTransactionAlive(record.getOwnerTransaction()))
                        .append(" ownChange=").append(record.isChangedByTransaction(transaction))
                        .append(" readerSnap=").append(transaction instanceof XdStorageTransaction ? ((XdStorageTransaction) transaction).getSnapshotSerial() : null)
                        .append(" readerTimestart=").append(transaction.getTimestart())
                        .append(" recCommitSerial=").append(record.getCommitSerial())
                        .append(" recTimestamp=").append(record.getTimestamp());
                System.out.println(diag);
            }
            throw new io.github.xdstorage.exceptions.XdStorageReferenceInvisibleException(
                    "reference is not visible in the reader snapshot: object of " + reference.getClass()
                            + " with idgeneration " + field.get(reference));
        }
        throw new XdStorageException("cannot load by reference object of " + reference.getClass() + " with idgeneration " + field.get(reference));
    }

    /**
     * ФАЗА 3B: ограниченное ожидание развязки чужой незакоммиченной мутации записи
     * (ТОЛЬКО инфраструктурные кэши — вызовы скоупятся флагом infrastructureCache).
     * Ждем пока запись остается невидимой незакоммиченной вставкой/апдейтом ЖИВОЙ
     * чужой транзакции. Возвращает true, если ждали и запись могла смениться
     * (вызывающий обязан перечитать cache.get). Бюджет — таймаут транзакции (до 3 с):
     * создатель ленивого дерева держит его незакоммиченным весь СВОЙ tx — под
     * конкуренцией lockForCommit это секунды, и короткий бюджет давал конкурентное
     * ВТОРОЕ дерево (createTreeLock double-check) и "does not exists" у апдейтеров.
     */
    private boolean waitForRecordResolution(final XdStorageCacheRecord initialRecord, final Object objectId,
                                            final XdStorageTransaction transaction) throws XdStorageException {
        if (!isInvisibleUncommittedMutation(initialRecord, transaction)) {
            return false;
        }
        final long timeout = Math.min(transaction.getTimeout() > 0 ? transaction.getTimeout() : 5000L, 3000L);
        final long deadlineNano = System.nanoTime() + timeout * 1_000_000L;
        XdStorageCacheRecord record = initialRecord;

        while (isInvisibleUncommittedMutation(record, transaction)) {
            if (System.nanoTime() >= deadlineNano) {
                // Чужая транзакция держит инфраструктурную запись невидимой дольше бюджета:
                // читатель идет дальше с "честным отказом" — этот факт обязан быть в логе.
                log.warn("infra record {} stayed invisible past the wait budget ({} ms, owner tx: {}) — serving absent",
                        objectId, timeout, record.getOwnerTransactionId());
                return true; // честный отказ дальше по контракту "cannot load by reference"
            }
            try {
                Thread.sleep(5);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new XdStorageException("waiting for record resolution interrupted", e);
            }
            record = cache.get(objectId);
            if (record == null) {
                log.debug("infra record {} disappeared while waiting — owner rolled back the invisible insert", objectId);
                return false; // запись изъята (откат вставки) — записи больше нет
            }
        }
        return true;
    }

    private boolean isInvisibleUncommittedMutation(final XdStorageCacheRecord record, final XdStorageTransaction transaction) {
        if (record == null || record.isCommitedState()) {
            return false;
        }
        if (record.isChangedByTransaction(transaction)) {
            return false; // своя мутация — видим через newObject
        }
        if (!record.isInsertChange() && !record.isUpdateChange()) {
            return false; // delete/read-состояния обрабатываются обычным MVCC-переключателем
        }
        return services.getTransactionsManager().isTransactionAlive(record.getOwnerTransaction());
    }

    // =========================================================================
    // ПОДДЕРЖКА ИНФРАСТРУКТУРЫ B+ДЕРЕВА (живые канонические инстансы)
    // =========================================================================

    /**
     * Классификация инфраструктурных объектов B+ Дерева индексов.
     * Такие объекты обязаны храниться в кэше РЕФЕРЕНСОМ НА ЖИВУЮ ИНСТАНЦИЮ, а не клоном:
     * их поля (children/nextTreeNodeOnThisLevel/parent) образуют единый граф в памяти JVM,
     * и любая подмена инстанса клоном разрывает связность структуры между транзакциями.
     */
    private static boolean isBTreeInfrastructureObject(final Object object) {
        final Class<?> entityClass = XdStorageObjectUtils.getEntityClass(object.getClass());
        return entityClass == io.github.xdstorage.btree.XdStorageBTree.class
                || entityClass == io.github.xdstorage.btree.XdStorageBTreeNode.class
                || entityClass.getName().contains("io.github.xdstorage.btree");
    }

    /**
     * Материализация объекта для записи в кэш: живая инстанция для B+ Дерева, изолированный клон для доменных объектов.
     */
    @Override
    public Object materializeForCache(final Object newObject) throws XdStorageException {
        final Object materialized = isBTreeInfrastructureObject(newObject)
                ? XdStorageObjectUtils.getWrappedObjectOrSameObject(newObject)
                : cloner.unwrapAndClone(newObject);
        // ФАЗА 3B: живой инстанс инфраструктуры становится каноническим при первой записи
        final XdStorageCanonicalInstanceRegistry registry = services.getCanonicalInstances();
        if (materialized != null && isBTreeInfrastructureObject(materialized) && registry != null) {
            final Object materializedId = field.get(materialized);
            if (materializedId != null) {
                final Object canonical = registry.adoptOrRegister(clazz, materializedId, materialized);
                if (Boolean.getBoolean("xdstorage.chain.debug")) {
                    System.out.println("[CHAIN] REGISTER class=" + clazz.getName() + " id=" + materializedId
                            + " instance@" + System.identityHashCode(materialized)
                            + (canonical != materialized ? " ADOPTED-EXISTING@" + System.identityHashCode(canonical) : " NEW"));
                }
                return canonical != null ? canonical : materialized;
            } else if (Boolean.getBoolean("xdstorage.chain.debug")) {
                System.out.println("[CHAIN] REGISTER-SKIP-NULL-ID class=" + clazz.getName()
                        + " object@" + System.identityHashCode(materialized));
            }
        }
        return materialized;
    }

    // =========================================================================
    // МУТАЦИИ (делегация контуру XdStorageCacheMutator)
    // =========================================================================

    /**
     * Stages the insert of a new object into the transaction's write set (via the mutation
     * contour); a re-insert of an object already staged as inserted/updated by the same
     * transaction is silently ignored (duplicate-insert guard). Runs inside the
     * transaction's critical section.
     *
     * @param newObject   the object to insert
     * @param transaction the transaction to stage the insert in
     * @throws XdStorageException when the object cannot be materialized for the cache
     */
    public void insert(final Object newObject, final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            final String transactionId = transaction.getTransactionId();

            // ГЛОБАЛЬНОЕ ИСПРАВЛЕНИЕ СУБД: Защита от избыточных повторных вставок.
            // Если объект уже имеет ID и зарегистрирован в карте изменений текущей транзакции,
            // мы fail-safe завершаем метод, предотвращая коллизии MVCC-версий на индексах классов!
            if (!IXdStorageIdObservableWrapper.class.isAssignableFrom(newObject.getClass())) {
                final Object objectId = field.get(newObject);
                final Map<Object, XdStorageCacheRecord> map = workspace.changes(transactionId);
                if (objectId != null && map != null && map.containsKey(objectId)) {
                    final XdStorageCacheRecord existingRecord = map.get(objectId);
                    if (existingRecord.isInsertChange() || existingRecord.isUpdateChange()) {
                        return; // Объект уже находится в транзакционном контуре, игнорируем дубликат
                    }
                }
            }

            mutator.insertInternal(newObject, transaction);
        } finally {
            transaction.finishCriticalSection();
        }
    }

    /**
     * Stages the update of an object into the transaction's write set (delegated to the
     * mutation contour).
     *
     * @param newObject   the object with its new state
     * @param transaction the transaction to stage the update in
     * @throws XdStorageException when the object cannot be materialized for the cache
     */
    public void update(final Object newObject, final XdStorageTransaction transaction) throws XdStorageException {
        mutator.update(newObject, transaction);
    }

    /**
     * Stages the deletion of the record with the given id into the transaction's write set
     * (delegated to the mutation contour).
     *
     * @param objectId    the id of the record to delete
     * @param transaction the transaction to stage the delete in
     * @throws XdStorageException when the deletion cannot be staged
     */
    public void delete(final Object objectId, final XdStorageTransaction transaction) throws XdStorageException {
        mutator.delete(objectId, transaction);
    }

    // =========================================================================
    // ЗАВЕРШЕНИЕ ТРАНЗАКЦИИ (делегация XdStorageCacheCommitProtocol)
    // =========================================================================

    /**
     * Whether the transaction has staged changes (insert/update/delete) in this cache.
     *
     * @param transaction the transaction to inspect
     * @return {@code true} when the transaction's write set is non-empty
     * @throws XdStorageException when the critical section cannot be entered
     */
    public boolean hasChanges(final XdStorageTransaction transaction) throws XdStorageException {
        transaction.startCriticalSection();
        try {
            return workspace.hasChanges(transaction.getTransactionId());
        } finally {
            transaction.finishCriticalSection();
        }
    }

    /**
     * Discards the transaction's contours in this cache (read set, write set, rollback
     * journal) — the release step after the transaction has ended.
     *
     * @param transaction the transaction whose contours are cleared
     */
    public void clear(final XdStorageTransaction transaction) {
        workspace.clear(transaction.getTransactionId());
    }

    /**
     * Commit phase 0: fills the collector with the transaction's staged changes for this
     * cache (delegated to the commit protocol) — the input for the persisted phases.
     *
     * @param transaction the committing transaction
     * @param collector   the collector receiving the staged changes
     */
    public void prepareCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges collector) {
        commitProtocol.prepareCommit(transaction, collector);
    }

    /**
     * Second two-phase step at the cache level: contributes the staged changes to the
     * collector again and marks every changed record perform-committed — the second
     * two-phase flag that must be set before the final {@link #commit} (delegated to the
     * commit protocol).
     *
     * @param transaction the committing transaction
     * @param collector   the collector filled by {@link #prepareCommit}
     */
    public void performCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges collector) {
        commitProtocol.performCommit(transaction, collector);
    }

    /**
     * Commit phase 2 at the cache level: publishes the transaction's staged changes into the
     * visible committed state (delegated to the commit protocol).
     *
     * @param transaction the committing transaction
     * @throws XdStorageException when the changes cannot be published
     */
    public void commit(final XdStorageTransaction transaction) throws XdStorageException {
        commitProtocol.commit(transaction);
    }

    /**
     * Discards the transaction's staged changes and unregisters its contours (pre-commit
     * rollback, delegated to the commit protocol).
     *
     * @param transaction the transaction being rolled back
     * @throws XdStorageException when the rollback cannot be completed
     */
    public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
        commitProtocol.rollback(transaction);
    }

    /**
     * Rolls back an already-prepared (phase-1 persisted) commit from the cache state: undoes
     * the staged effects of the given committed changes so a failed commit leaves no trace
     * in the visible state (delegated to the commit protocol).
     *
     * @param transaction the failed transaction
     * @param committed   the changes the failed first phase persisted
     * @throws XdStorageException when the compensation cannot be completed
     */
    public void rollbackFailedCommit(final XdStorageTransaction transaction, final Collection<XdStorageObjectChange> committed) throws XdStorageException {
        commitProtocol.rollbackFailedCommit(transaction, committed);
    }
}
