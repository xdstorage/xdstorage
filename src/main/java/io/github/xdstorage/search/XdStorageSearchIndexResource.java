package io.github.xdstorage.search;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.btree.IXdStorageBTreeViewer;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.code.IXdStorageSimpleWrapper;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.IXdStorageChangeIndexRollback;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.search.data.XdStorageSearchKeyIndexRecord;
import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;
import io.github.xdstorage.search.key.XdStorageSearchIndexKey;
import io.github.xdstorage.search.key.XdStorageSearchIndexOperationType;
import io.github.xdstorage.serialization.IXdStorageIOFactory;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One persisted search-index resource: the transactional owner of an index's B+tree pair —
 * the search tree keyed by the composite {@code XdStorageSearchIndexKey} (PRIMARY value +
 * object id) and the address key tree keyed by object id. Lazily creates both trees inside
 * the caller's transaction on first insert, keeps them in step with entity changes
 * (in-place entry rewrite when the PRIMARY value is unchanged, delete+insert when it
 * moves), tolerates absent entries on delete, and streams the whole index for scans.
 * Search-index resources carry no data of their own — the persisted payload lives in the
 * trees, so every phase-2/rollback hook is a no-op.
 *
 * <p>Engine-internal — not part of the public API. One instance serves many concurrent
 * transactions: per-record work runs in the caller's transaction context, tree creation
 * is guarded by an internal lock, and the commit lifecycle is inherited from the
 * resource-object contract.</p>
 */
public class XdStorageSearchIndexResource implements IXdStorageSearchIndexResourceObject, IXdStorageSearchIndexDaoResource {

    private static final Logger log = LoggerFactory.getLogger(XdStorageSearchIndexResource.class);

    /** The engine's service locator (storage, canonical instances registry). */
    protected final XdStorageServicesLocator services;

    /** The resources manager that issued this resource and owns its lifecycle. */
    protected final XdStorageAbstractResourcesManager manager;

    /** The declared index name this resource persists (the B+tree id's index part). */
    protected final String indexName;

    /** The storage-wide unique resource id of this index resource. */
    protected final Object resourceId;

    /** Class metadata of the indexed entity class (its declared indexes and id field). */
    protected final XdStorageClassInfo objectClInfo;

    /** Class metadata of the key-tree record type the resource persists. */
    protected final XdStorageClassInfo clInfo;

    /** The id field of the key-tree record type. */
    protected final XdStorageObjectIdField idField;

    private Map<String, Stack<IXdStorageChangeIndexRollback>> rollbacks = new ConcurrentHashMap<String, Stack<IXdStorageChangeIndexRollback>>();

    private final IXdStorageObjectsReader reader;

    private final IXdStorageObjectsWriter writer;

    /**
     * Creates the resource for one declared index of one entity class.
     *
     * @param services     the engine's service locator
     * @param manager      the resources manager issuing this resource
     * @param indexName    the declared index name this resource persists
     * @param resourceId   the storage-wide unique resource id
     * @param objectClInfo the metadata of the indexed entity class
     * @param factory      the serialization factory for the record reader/writer
     * @param fragmentSize the engine's fragmentation setting (objects per file); accepted
     *                     per the resource contract, unused here — the payload lives in the B+trees
     */
    public XdStorageSearchIndexResource(final XdStorageServicesLocator services,
                                        final XdStorageAbstractResourcesManager manager,
                                        final String indexName, final Object resourceId,
                                        final XdStorageClassInfo objectClInfo, final IXdStorageIOFactory factory, final int fragmentSize) {
        this.services = services;
        this.manager = manager;
        this.indexName = indexName;
        this.resourceId = resourceId;
        this.objectClInfo = objectClInfo;
        this.clInfo = XdStorageObjectUtils.getClassInfo(XdStorageSearchKeyIndexRecord.class);
        this.idField = clInfo.getIdField();
        this.reader = factory.newInstanceReader();
        this.writer = factory.newInstanceWriter();
    }

    @Override
    public Object getResourceId() {
        return resourceId;
    }

    @Override
    public Class<?> getObjectsClass() {
        return clInfo.getClazz();
    }

    @Override
    public long getObjectsCount() {
        return 0;
    }

    @Override
    public boolean hasChanges(final XdStorageTransaction transaction) throws XdStorageException {
        return false;
    }

    /**
     * The backing file name of this index resource (the resource id as a string).
     *
     * @return the resource's file name
     */
    public String getFileName() {
        return resourceId.toString();
    }

    public void prepare(final XdStorageTransaction transaction) {
        // do nothing
    }

    public void performFirstPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) {
        // do nothing
    }

    public void performSecondPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        // do nothing
    }

    public void rollbackPerformingFirstPhaseCommit(final XdStorageTransaction transaction, final Collection<XdStorageObjectChange> changes) throws XdStorageException {
        // do nothing
    }

    public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
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
        manager.releaseResource(this);
    }

    private final Lock createTreeLock = new ReentrantLock();

    @Override
    public void insert(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final XdStorageSearchIndexRecord record = (XdStorageSearchIndexRecord) object;
        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId searchBTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), indexName);
        final XdStorageBTreeId keyBTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "key_" + indexName);

        // Локальное ленивое чтение дерева в рамках текущей транзакции пользователя
        XdStorageBTree tree = storage.load(XdStorageBTree.class, searchBTreeId, transaction);
        // ФАЗА 3B: реестр канонических инстансов — живое дерево могло потерять запись кэша
        // (откат создателя/eviction), но граф остается каноническим: восстанавливаем ссылку
        if (tree == null) {
            tree = (XdStorageBTree) services.getCanonicalInstances().get(XdStorageBTree.class, searchBTreeId);
            if (tree != null) {
                log.warn("search tree {} adopted from the canonical instance registry (cache record lost)", searchBTreeId);
            }
        }

        // =========================================================================
        // КАНОНИЧЕСКОЕ ИСПРАВЛЕНИЕ (Полная ликвидация сторонних потоков и транзакций):
        // Если дерева поиска на диске еще нет, мы инициализируем и сохраняем его
        // СТРОГО в рамках текущей пользовательской транзакции! Никаких Executor и autonomousTx.
        // Блокировка createTreeLock защищает структуру в куче Java от гонок инициализации.
        // =========================================================================
        if (tree == null) {
            createTreeLock.lock();
            try {
                // Повторно проверяем внутри критической секции в контексте текущей транзакции
                tree = storage.load(XdStorageBTree.class, searchBTreeId, transaction);
                if (tree == null) {
                    final XdStorageSearchIndex searchIndex = objectClInfo.getIndexes().get(indexName);
                    log.info("search trees {} and {} created for class {}", searchBTreeId, keyBTreeId, objectClInfo.getClazz().getName());

                    // Создаем и каскадно сохраняем дерево адресации ключей в ТЕКУЩУЮ транзакцию
                    XdStorageBTree subKeyTree = new XdStorageBTree(keyBTreeId, false, searchIndex.getIndexFillingValue());
                    storage.save(subKeyTree, transaction);

                    // Создаем и каскадно сохраняем основное дерево поиска в ТЕКУЩУЮ транзакцию
                    tree = new XdStorageBTree(searchBTreeId, false, searchIndex.getIndexFillingValue());
                    storage.save(tree, transaction);

                    // Обновляем метаданные самого индексного ресурса
                    storage.update(tree, transaction);
                    storage.update(subKeyTree, transaction);
                }
            } finally {
                createTreeLock.unlock();
            }
        }

        // Загружаем дерево ключей адресации в контексте текущей транзакции
        XdStorageBTree keyTree = storage.load(XdStorageBTree.class, keyBTreeId, transaction);
        if (tree == null || keyTree == null) {
            throw new XdStorageException("Сбой инициализации индексной структуры: деревья поиска не найдены");
        }
        restoreSearchKeyTypesIfNeeded(tree, storage, transaction);
        restoreSearchKeyTypesIfNeeded(keyTree, storage, transaction);

        // Идемпотентность: search-insert вызывается на КАЖДОМ save() объекта. Повторная
        // запись уже проиндексированного объекта — no-op (обратная запись в key-дереве есть).
        final List<Object> alreadyIndexed = keyTree.find((Comparable) record.getId(), storage, transaction);
        if (alreadyIndexed != null && !alreadyIndexed.isEmpty()) {
            return;
        }

        final XdStorageSearchKeyIndexRecord hashIndexRecord = new XdStorageSearchKeyIndexRecord(record.getId(), record.getPrimaryIndexValue());

        // Атомарно пишем данные в индексы в рамках ЕДИНОЙ транзакции.
        // RECIDENT/R-36 (docs/tasks/2.1.0/47): the entry-lock contention bursts are
        // absorbed INSIDE the btree (the retry wraps only the tree-level entry lock —
        // a clean boundary before any in-place mutation).
        keyTree.insert((Comparable) record.getId(), hashIndexRecord, storage, transaction);
        // R-57: компенсация при откате — keyTree по id записи
        transaction.registerIndexKeyForRollback(keyTree, (Comparable) record.getId());
        final XdStorageSearchIndexKey searchIndexKey = new XdStorageSearchIndexKey(record.getPrimaryIndexValue(), record.getId(), XdStorageSearchIndexOperationType.Insert);
        tree.insert(searchIndexKey, XdStorageObjectUtils.getWrappedObjectOrSameObject(record), storage, transaction);
        // R-57: и поисковое дерево по составному ключу
        transaction.registerIndexKeyForRollback(tree, searchIndexKey);
    }

    public void update(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final XdStorageSearchIndexRecord record = (XdStorageSearchIndexRecord) object;

        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId keyBTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "key_" + indexName);

        // Fail-safe загрузка дерева ключей
        XdStorageBTree keyTree = storage.load(XdStorageBTree.class, keyBTreeId, transaction);
        if (keyTree == null) {
            // =========================================================================
            // ИНФРАСТРУКТУРНЫЙ МОСТ: Если MVCC-барьер скрыл глобальное дерево индексов,
            // загружаем его без транзакционного паспорта напрямую с диска, чтобы спасти Писателя!
            // =========================================================================
            keyTree = storage.load(XdStorageBTree.class, keyBTreeId);
        }
        if (keyTree == null) {
            // ФАЗА 3B: реестр канонических инстансов — живое дерево могло потерять запись кэша
            // (откат создателя/eviction); граф остается каноническим — восстанавливаем ссылку.
            keyTree = (XdStorageBTree) services.getCanonicalInstances().get(XdStorageBTree.class, keyBTreeId);
            if (keyTree != null) {
                log.warn("search key tree {} adopted from the canonical instance registry (cache record lost)", keyBTreeId);
            }
        }
        if (keyTree == null) {
            throw new XdStorageException("object with id " + record.getId() + " does not exists");
        }

        final List<Object> findResult = keyTree.find((Comparable) record.getId(), storage, transaction);
        if (findResult == null || findResult.isEmpty()) {
            throw new XdStorageException("object with id " + record.getId() + " does not exists in index keys");
        }

        final XdStorageSearchKeyIndexRecord oldHashIndexRecord = (XdStorageSearchKeyIndexRecord) findResult.get(0);
        final Object oldPrimaryFieldValue = oldHashIndexRecord.getValue(), newPrimaryFieldValue = record.getPrimaryIndexValue();

        final XdStorageBTreeId searchBTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), indexName);

        // Fail-safe загрузка основного дерева поиска
        XdStorageBTree tree = storage.load(XdStorageBTree.class, searchBTreeId, transaction);
        if (tree == null) {
            tree = storage.load(XdStorageBTree.class, searchBTreeId); // Загружаем глобальный мета-объект напрямую
        }
        if (tree == null) {
            // ФАЗА 3B: реестр канонических инстансов — живое дерево могло потерять запись кэша
            // (откат создателя/eviction); граф остается каноническим — восстанавливаем ссылку.
            tree = (XdStorageBTree) services.getCanonicalInstances().get(XdStorageBTree.class, searchBTreeId);
            if (tree != null) {
                log.warn("search tree {} adopted from the canonical instance registry (cache record lost)", searchBTreeId);
            }
        }
        if (tree == null && Boolean.getBoolean("xdstorage.chain.debug")) {
            System.out.println("[CHAIN] SEARCH-TREE-MISS id=" + searchBTreeId + " recordId=" + record.getId());
        }
        if (tree == null) {
            throw new XdStorageException("object with id " + record.getId() + " does not exists");
        }
        restoreSearchKeyTypesIfNeeded(keyTree, storage, transaction);
        restoreSearchKeyTypesIfNeeded(tree, storage, transaction);

        if ((oldPrimaryFieldValue == null && newPrimaryFieldValue == null)
                || (oldPrimaryFieldValue != null && oldPrimaryFieldValue.equals(newPrimaryFieldValue))) {
            final XdStorageSearchIndexKey searchIndexKey = new XdStorageSearchIndexKey(oldHashIndexRecord.getValue(), oldHashIndexRecord.getObjectId(), XdStorageSearchIndexOperationType.Update);
            tree.update(searchIndexKey, record, storage, transaction);
        } else {
            final XdStorageSearchKeyIndexRecord newHashIndexRecord = new XdStorageSearchKeyIndexRecord(record.getId(), record.getPrimaryIndexValue());

            // КАНОНИЧЕСКОЕ ИСПРАВЛЕНИЕ (покрыто тестами переименования): ключ key-дерева - ид объекта
            // (Comparable), а НЕ XdStorageSearchIndexKey. Прежний каст бросал ClassCastException на
            // КАЖДОМ update со сменой первичного значения (переименование), ломая перенос ключа.
            keyTree.update((Comparable) record.getId(), newHashIndexRecord, storage, transaction);

            final XdStorageSearchIndexKey searchIndexKeyToDelete = new XdStorageSearchIndexKey(oldHashIndexRecord.getValue(), oldHashIndexRecord.getObjectId(), XdStorageSearchIndexOperationType.Delete);
            tree.delete(searchIndexKeyToDelete, storage, transaction);
            final XdStorageSearchIndexKey searchIndexKeyToInsert = new XdStorageSearchIndexKey(newHashIndexRecord.getValue(), newHashIndexRecord.getObjectId(), XdStorageSearchIndexOperationType.Insert);
            tree.insert(searchIndexKeyToInsert, record, storage, transaction);
        }
    }

    @Override
    public void delete(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final XdStorageSearchIndexRecord record = (XdStorageSearchIndexRecord) object;

        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId keyBTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), "key_" + indexName);

        XdStorageBTree keyTree = storage.load(XdStorageBTree.class, keyBTreeId, transaction);
        if (keyTree == null) {
            keyTree = storage.load(XdStorageBTree.class, keyBTreeId);
        }
        if (keyTree == null) {
            return;
        }

        restoreSearchKeyTypesIfNeeded(keyTree, storage, transaction);

        final List<Object> findResult = keyTree.find((Comparable) record.getId(), storage, transaction);
        if (findResult.isEmpty()) {
            return;
        }

        final XdStorageSearchKeyIndexRecord oldHashIndexRecord = (XdStorageSearchKeyIndexRecord) findResult.get(0);
        keyTree.delete((Comparable) record.getId(), storage, transaction);

        final XdStorageBTreeId searchBTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), indexName);

        XdStorageBTree tree = storage.load(XdStorageBTree.class, searchBTreeId, transaction);
        if (tree == null) {
            tree = storage.load(XdStorageBTree.class, searchBTreeId); // Инфраструктурный фолбэк-запрос
        }
        if (tree == null) {
            return;
        }
        restoreSearchKeyTypesIfNeeded(tree, storage, transaction);

        final XdStorageSearchIndexKey searchIndexKey = new XdStorageSearchIndexKey(oldHashIndexRecord.getValue(), oldHashIndexRecord.getObjectId(), XdStorageSearchIndexOperationType.Insert);
        tree.delete(searchIndexKey, storage, transaction);
    }

    public <T> void watch(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException {
        final IXdStorage storage = services.getStorage();

        final XdStorageBTreeId searchBTreeId = new XdStorageBTreeId(objectClInfo.getClazz(), indexName);

        final XdStorageBTree tree = storage.load(XdStorageBTree.class, searchBTreeId, transaction);
        if (tree != null) {
            tree.read(storage, transaction, new IXdStorageBTreeViewer() {
                @Override
                public void look(Comparable comparable, Object value) throws XdStorageException, XdStorageConnectionException {
                    watcher.watch((T) value);
                }
            });
        }
    }

    @Override
    public IXdStorageSearchIndexDaoResource getDao() {
        return this;
    }

    /**
     * BUG-E (docs/tasks/2.1.0/18): the resource's OWN tree instances (insert/update/delete/
     * watch load them independently of the search manager) need the same one-time key-type
     * restore after a reopen — otherwise the first DELETE/INSERT seek on a numeric search
     * index crashes on raw-String persisted keys. The CAS once-guard lives on the tree
     * instance, so duplicate invocations across instances converge on the canonical nodes.
     */
    private void restoreSearchKeyTypesIfNeeded(final XdStorageBTree tree, final IXdStorage storage,
                                               final XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        final XdStorageSearchIndex searchIndex = objectClInfo.getIndexes().get(indexName);
        if (searchIndex == null || searchIndex.getPrimaryField() == null) {
            return;
        }
        tree.restoreSearchKeyTypes(
                io.github.xdstorage.utils.XdStorageKeyTypeRestore.searchEntryRestorer(
                        searchIndex.getPrimaryField().field.getType(),
                        objectClInfo.getIdField().field.getType()),
                storage, transaction);
    }
}
