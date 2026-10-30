package io.github.xdstorage.resource.cache;

import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Map;

/**
 * Контур мутаций кэша ресурсов: insert / update / delete (вынесен из
 * XdStorageResourceCache при разделении класса). Публичные методы фасада сохраняют
 * исходный порядок "лок записи ДО входа в критическую секцию транзакции" — здесь
 * живут тела операций, выполняемые внутри этих секций.
 */
public final class XdStorageCacheMutator {

    private static final Logger log = LoggerFactory.getLogger(XdStorageCacheMutator.class);
    private static final IXdStorageCacheInternalListener DO_NOTHING_LISTENER = new IXdStorageCacheInternalListener() {
        @Override
        public void insertRegistered(Object newObject, IXdStorageTransaction transaction) {
            // do nothing
        }

        @Override
        public void updateRegistered(Object oldObject, Object newObject, IXdStorageTransaction transaction) {
            // do nothing
        }

        @Override
        public void deleteRegistered(Object object, IXdStorageTransaction transaction) {
            // do nothing
        }
    };

    private final XdStorageCacheContext cache;

    private IXdStorageCacheInternalListener listener;

    /**
     * Binds the mutator to a cache context.
     *
     * @param cache the cache context (records registry, workspace, id field) this mutator operates on
     */
    public XdStorageCacheMutator(final XdStorageCacheContext cache) {
        this.cache = cache;
        this.listener = DO_NOTHING_LISTENER;
    }

    public void setInternalListener(IXdStorageCacheInternalListener listener) {
        this.listener = listener != null ? listener : DO_NOTHING_LISTENER;
    }

    /**
     * Body of the facade's insert, executed inside the transaction's critical section.
     * Observable wrappers without an id yet are parked as unidentified objects and get a
     * record created later via an id observer; objects with an id create their cache record
     * immediately (refusing null ids, double inserts and records held by other transactions)
     * and register the mutation in the transaction's write-set.
     *
     * @param newObject   the object to insert into the resource
     * @param transaction the inserting transaction
     * @throws XdStorageException if the object has a null id, an object with the same id
     *                            already exists, was deleted earlier in this transaction,
     *                            or its record cannot be locked by this transaction
     */
    public void insertInternal(final Object newObject, final XdStorageTransaction transaction) throws XdStorageException {
        log.debug("INSERT by transaction {} of {}", transaction.getTransactionId(), newObject.getClass().getSimpleName());
        if (IXdStorageIdObservableWrapper.class.isAssignableFrom(newObject.getClass())) {
            final String transactionId = transaction.getTransactionId();

            final Map<Object, IXdStorageIdObservableWrapper> records = cache.workspace().unidentifiedOf(transaction.getTransactionId());
            records.put(newObject, (IXdStorageIdObservableWrapper) newObject);

            ((IXdStorageIdObservableWrapper) newObject).addObserver(new XdStorageAbstractIdObserver() {
                @Override
                public void onNewIdIsSet(final IXdStorageIdObservableWrapper wrapper, final Object id) {
                    XdStorageCacheRecord record;

                    final Map<Object, IXdStorageIdObservableWrapper> wrappers = cache.workspace().unidentified(transactionId);
                    if (wrappers != null) {
                        wrappers.remove(newObject);
                    }

                    final Object object = XdStorageObjectUtils.getWrappedObjectOrSameObject(newObject);

                    final XdStorageCacheRecord tmpRecord;
                    try {
                        // ИДЕНТИЧНОСТЬ B+ ДЕРЕВА: ноды индекса сохраняются живой инстанцией
                        cache.records().putIfAbsent(id, tmpRecord = cache.createInsertRecord(cache.materializeForCache(object), transaction));
                        record = cache.records().get(id);
                        if (record != tmpRecord) {
                            throw new XdStorageRuntimeException("trying to double insert one object of " + newObject.getClass() + " with idgeneration " + id);
                        }

                        record.lock(transaction);
                    } catch (final XdStorageException e) {
                        throw new XdStorageRuntimeException("trying to lock object", e);
                    }

                    if (!record.canBeChangedByTransaction(transaction)) {
                        log.error("INSERT (deferred id) refused: object {} id {} record[state={}, ownerTx={}, recordTs={}]",
                                newObject.getClass().getSimpleName(), id, record.getState(),
                                record.getOwnerTransactionId(), record.getRecordTimestamp());
                        throw new XdStorageRuntimeException("object of " + newObject.getClass() + " with idgeneration " + id + " can not be changed by transaction " + transactionId);
                    }

                    final Map<Object, XdStorageCacheObjectChange> readObjects = cache.workspace().readObjectsOf(transaction);
                    readObjects.put(id, new XdStorageCacheObjectChange(object, XdStorageCacheChange.insert, transaction.getTimestart()));

                    synchronized (record) {
                        record.prepareCommit();
                    }

                    cache.workspace().registerMutation(transactionId, id, record);
                }
            });
        } else {
            final String transactionId = transaction.getTransactionId();
            final Map<Object, XdStorageCacheObjectChange> readObjects = cache.workspace().readObjectsOf(transaction);

            final Object objectId = cache.idField().get(newObject);
            if (objectId == null) {
                // Барьер null-ID обязан стоять ДО обращения к CHM: cache.get(null)
                // падает с голым NullPointerException, съедая осмысленную диагностику
                throw new XdStorageException("cannot store object of " + newObject.getClass() + " with null id");
            }
            XdStorageCacheRecord record = cache.records().get(objectId);
            if (record == null) {
                // ИДЕНТИЧНОСТЬ B+ ДЕРЕВА: ноды индекса сохраняются живой инстанцией,
                // чтобы граф children/next не расходился между кэшем и памятью после коммита
                Object newMaterializedObject = cache.materializeForCache(newObject);
                XdStorageCacheRecord tmpRecord = cache.createInsertRecord(newMaterializedObject, transaction);
                cache.records().putIfAbsent(objectId, tmpRecord);
                record = cache.records().get(objectId);
                if (record != tmpRecord) {
                    throw new XdStorageException("trying to double insert one object of " + newObject.getClass() + " with idgeneration " + objectId);
                }
                record.lock(transaction);
                if (!record.canBeChangedByTransaction(transaction)) {
                    record.unlock(transaction);
                    log.error("INSERT refused (record check): object {} id {} record[state={}, ownerTx={}, recordTs={}]",
                            newObject.getClass().getSimpleName(), objectId, record.getState(),
                            record.getOwnerTransactionId(), record.getRecordTimestamp());
                    throw new XdStorageException("object of " + newObject.getClass() + " with idgeneration " + objectId + " can not be inserted by transaction " + transactionId);
                }
                readObjects.put(objectId, new XdStorageCacheObjectChange(newObject, XdStorageCacheChange.insert, transaction.getTimestart()));
                // Notify listeners about insertion to cache
                listener.insertRegistered(newMaterializedObject, transaction);
            } else {
                synchronized (record) {
                    if (record.isChangedByTransaction(transaction)) {
                        if (record.isDeleteChange()) {
                            log.error("INSERT refused: object {} id {} was deleted in this transaction", newObject.getClass().getSimpleName(), objectId);
                            throw new XdStorageException("trying to insert deleted object of " + newObject.getClass() + " with idgeneration " + objectId);
                        } else if (record.isInsertChange()) {
                            log.error("INSERT refused: double insert of object {} id {} in transaction {}",
                                    newObject.getClass().getSimpleName(), objectId, transactionId);
                            throw new XdStorageException("trying to double insert one object of " + newObject.getClass() + " with idgeneration " + objectId);
                        } else {
                            log.error("INSERT refused: object {} id {} already exists (state={}, ownerTx={})",
                                    newObject.getClass().getSimpleName(), objectId, record.getState(), record.getOwnerTransactionId());
                            throw new XdStorageException("object of " + newObject.getClass() + " with idgeneration " + objectId + " is exists");
                        }
                    } else {
                        log.error("INSERT refused: concurrent modification of object {} id {} — record owner is {}",
                                newObject.getClass().getSimpleName(), objectId, record.getOwnerTransactionId());
                        throw new XdStorageException("concurrent modification one object of " + newObject.getClass() + " with idgeneration " + objectId);
                    }
                }
            }
            cache.workspace().registerMutation(transactionId, objectId, record);
        }
    }

    /**
     * Body of the facade's update: locks the object's record before entering the transaction's
     * critical section, then applies the change. A repeated update of an object this transaction
     * already inserted or updated takes the fast path (replace the pending content); otherwise
     * the record is re-read from the registry after the lock wait and the update is applied
     * under the isolation/conflict checks, with infrastructure records relaxed per R-42.
     *
     * @param newObject   the object carrying the new state (its id selects the record)
     * @param transaction the updating transaction
     * @throws XdStorageException if no record exists for the object's id (and the cache is not
     *                            infrastructure), the object was deleted in this transaction,
     *                            or the conflict checks refuse the update
     */
    public void update(final Object newObject, final XdStorageTransaction transaction) throws XdStorageException {
        final Object objectId = cache.idField().get(newObject);
        XdStorageCacheRecord record = cache.records().get(objectId);

        if (record == null) {
            // R-40: САМОЗАРАВКА ИНФРАСТРУКТУРНЫХ ОБЪЕКТОВ. Для B+Tree (деревья/узлы)
            // истина — живой инстанс в памяти дерева: вызывающий держит канонический узел
            // из структуры. Если его record потеряна (откат сплита + выселение cold-ресурса,
            // который ни разу не коммитился на диск), отказ "does not exists" ронял КАЖДЫЙ
            // спуск через такой узел навсегда. Материализуем запись заново (как insert):
            // коммит узла запишет его на диск в согласии со структурой дерева.
            if (cache.isInfrastructureCache() && objectId != null) {
                log.warn("UPDATE of infrastructure object {} id {} without cache record — re-materializing (structural ghost self-heal)",
                        newObject.getClass().getSimpleName(), objectId);
                cache.insert(newObject, transaction);

                listener.insertRegistered(newObject, transaction);
                return;
            }
            throw new XdStorageException("object of " + newObject.getClass() + " with idgeneration " + objectId + " does not exists");
        }

        // Запрашиваем лок ДО входа в глобальную критическую секцию транзакции!
        record.lock(transaction);

        transaction.startCriticalSection();
        try {
            final String transactionId = transaction.getTransactionId();

            final Map<Object, XdStorageCacheRecord> map = cache.workspace().changes(transactionId);
            if (objectId != null && map != null && map.containsKey(objectId)) {
                final XdStorageCacheRecord existingRecord = map.get(objectId);
                if (existingRecord.isInsertChange() || existingRecord.isUpdateChange()) {
                    // If update -> store oldObject for listener
                    Object oldObject = null;
                    if (existingRecord.isUpdateChange()) {
                        oldObject = existingRecord.getObject();
                    }

                    // ИДЕНТИЧНОСТЬ B+ ДЕРЕВА: быстрый путь повторного апдейта обязан подчиняться
                    // тому же правилу живой инстанции, что и полный путь — иначе коммит пишет
                    // устаревший клон ноды на диск и рвет цепочку листьев индекса.
                    Object materializedUpdatedObject = cache.materializeForCache(newObject);
                    existingRecord.setNewObject(materializedUpdatedObject);

                    if (existingRecord.isUpdateChange()) {
                        listener.updateRegistered(oldObject, materializedUpdatedObject, transaction);
                    }

                    // =========================================================================
                    // СИНХРОНИЗАЦИЯ ТРАНЗИТНОГО ОБЪЕКТА С БАРЬЕРОМ ВЫГРУЗКИ: readUnsafe() при
                    // выписке на диск предпочитает readObjects[id].object. Без обновления этой
                    // карты быстрый путь оставлял там ИСХОДНУЮ инстанцию с момента insert(),
                    // и все мутации, выполненные через другую инстанцию (например, подгруженный
                    // стаб ссылки B+ Дерева), молча терялись при коммите!
                    // =========================================================================
                    final Map<Object, XdStorageCacheObjectChange> transitObjects = cache.workspace().readObjects(transactionId);
                    if (transitObjects != null) {
                        final XdStorageCacheObjectChange previous = transitObjects.get(objectId);
                        if (previous != null) {
                            transitObjects.put(objectId, new XdStorageCacheObjectChange(newObject, XdStorageCacheChange.update, previous.readTimestamp));
                        }
                    }
                    return;
                }
            }

            updateInternalAfterLock(newObject, transaction, record);
        } finally {
            transaction.finishCriticalSection();
        }
    }

    private void updateInternalAfterLock(final Object newObject, final XdStorageTransaction transaction, XdStorageCacheRecord record) throws XdStorageException {
        log.debug("UPDATE by transaction {} of {}", transaction.getTransactionId(), newObject.getClass().getSimpleName());

        final String transactionId = transaction.getTransactionId();
        final Object objectId = cache.idField().get(newObject);

        // Перечитываем актуальное состояние ноды после прохождения барьера ожидания лока
        record = cache.records().get(objectId);
        if (record == null) {
            throw new XdStorageException("object of " + cache.entityClass() + " with idgeneration " + objectId + " was deleted by another transaction");
        }

        final Map<Object, XdStorageCacheObjectChange> readObjects = cache.workspace().readObjectsOf(transaction);

        final XdStorageCacheObjectChange change = readObjects.get(objectId);
        synchronized (record) {
            // R-42: ИНФРАСТРУКТУРНЫЕ ЗАПИСИ (B+Tree узлы/деревья) НЕ участвуют в политике
            // отказа-до-коммита. Корректность структуры уже обеспечивает write-лок самого узла
            // (hand-over-hand спуски, deep-локи ребалансировки — см. R-27 про latch): record
            // здесь — просто буфер записи на диск, а не объектно-версионная блокировка.
            // Отказы "can not be updated" на горячих внутренних узлах отказывали КАЖДОМУ
            // параллельному спуску до конца транзакции-держателя (лавина R-37) — для
            // пользовательских объектов семантика остается строгой.
            // R-42: ноды — полная релаксация (структура); у дерева релаксировано обновление,
            // но незакоммиченная ЧУЖАЯ вставка неприкосновенна (контракты фантомов
            // BTreeEntityTest: дерево, видимое не существующим в БД, чужая tx не трогает).
            final boolean infraRelax = cache.isStructuralNodeCache()
                    || (cache.isInfrastructureCache()
                        && !(record.isInsertChange() && !record.isCommitedState() && !record.isChangedByTransaction(transaction)));
            if (change != null) {
                // LOSTUPD diagnostics: the accept/reject decision with its exact inputs —
                // the read moment vs the record's version stamp.
                if (io.github.xdstorage.utils.XdStorageDiag.ENABLED) {
                    log.info("UPDATE gate id={} tx={} readTs={} record[state={}, ts={}] infraRelax={}",
                            objectId, transactionId, change.readTimestamp,
                            record.getState(), record.getRecordTimestamp(), infraRelax);
                }
                if (!infraRelax && !record.canBeChangedByTransaction(transaction, change.readTimestamp)) {
                    // R-40: отказ обновления обязан оставлять в логе СОСТОЯНИЕ записи — без него
                    // отказ выглядит необъяснимым (владелец == сам апдейтер, но отказ).
                    log.error("UPDATE refused (read-version check) object {} id {}: record[state={}, ownerTx={}, recordTs={}, txTs={}, readTs={}]",
                            newObject.getClass().getSimpleName(), objectId, record.getState(), record.getOwnerTransactionId(),
                            record.getRecordTimestamp(), transaction.getTimestart(), change.readTimestamp);
                    throw new XdStorageException("object of " + newObject.getClass() + " with idgeneration " + objectId + " can not be updated by transaction " + transactionId);
                }
            } else if (!infraRelax && !record.canBeChangedByTransaction(transaction)) {
                log.error("UPDATE refused (record check) object {} id {}: record[state={}, ownerTx={}, recordTs={}, txTs={}]",
                        newObject.getClass().getSimpleName(), objectId, record.getState(), record.getOwnerTransactionId(),
                        record.getRecordTimestamp(), transaction.getTimestart());
                throw new XdStorageException("object of " + newObject.getClass() + " with idgeneration " + objectId + " can not be updated by transaction " + transactionId);
            }

            if (record.isReadChange()) {
                record.setNewObject(cache.materializeForCache(newObject));
                record.markUpdate(transaction);
                readObjects.put(objectId, new XdStorageCacheObjectChange(newObject, XdStorageCacheChange.update, change != null ? change.readTimestamp : transaction.getTimestart()));

                listener.updateRegistered(record.getObject(), record.getNewObject(), transaction);
            } else if (!record.isCommitedState() && record.isChangedByTransaction(transaction)) {
                if (record.isUpdateChange() || record.isInsertChange()) {
                    record.setNewObject(cache.materializeForCache(newObject));
                    readObjects.put(objectId, new XdStorageCacheObjectChange(newObject, XdStorageCacheChange.update, change != null ? change.readTimestamp : transaction.getTimestart()));

                    listener.updateRegistered(record.getObject(), record.getNewObject(), transaction);
                } else {
                    throw new XdStorageException("object of " + newObject.getClass() + " with idgeneration " + objectId + " was deleted this transaction");
                }
            } else if (infraRelax || record.canBeChangedByTransaction(transaction)) {
                record.setNewObject(cache.materializeForCache(newObject));
                record.markUpdate(transaction);
                readObjects.put(objectId, new XdStorageCacheObjectChange(newObject, XdStorageCacheChange.update, change != null ? change.readTimestamp : transaction.getTimestart()));

                listener.updateRegistered(record.getObject(), record.getNewObject(), transaction);
            } else {
                throw new XdStorageException("concurrent modification one object of " + newObject.getClass() + " with idgeneration " + objectId);
            }
        }

        cache.workspace().registerMutation(transactionId, objectId, record);
    }

    /**
     * Body of the facade's delete: locks the object's record before entering the transaction's
     * critical section, then marks the record deleted (clearing the pending content), drops the
     * object from the transaction's materialized set and registers the mutation. Infrastructure
     * objects whose record has already disappeared are treated as already deleted (R-42);
     * for domain objects a missing record is a hard failure.
     *
     * @param objectId    the id of the object to delete
     * @param transaction the deleting transaction
     * @throws XdStorageException if no record exists for the id (and the cache is not
     *                            infrastructure), the object was already deleted in this
     *                            transaction, or the conflict checks refuse the delete
     */
    public void delete(final Object objectId, final XdStorageTransaction transaction) throws XdStorageException {
        XdStorageCacheRecord record = cache.records().get(objectId);
        if (record == null) {
            // R-42: инфраструктурный (B+Tree) объект, чья record потеряна, при удалении
            // считается УЖЕ ОТСУТСТВУЮЩИМ: цель удаления достигнута, отказ ронял монолитный
            // пурдж на узлах-сиротах откатанных сплитов. Пользовательские объекты по-прежнему
            // строгие — потеря их записи есть нарушение и требует исключения.
            if (cache.isInfrastructureCache()) {
                log.warn("DELETE of infrastructure object {} id {} without cache record — treating as already deleted",
                        cache.entityClass().getSimpleName(), objectId);
                return;
            }
            throw new XdStorageException("object of " + cache.entityClass() + " with idgeneration " + objectId + " does not exists");
        }

        // Запрашиваем лок ДО входа в глобальную критическую секцию транзакции!
        // Поток дождется освобождения записи, не подвешивая монитор сессии для коммитов.
        record.lock(transaction);

        transaction.startCriticalSection();
        try {
            deleteInternalAfterLock(objectId, transaction, record);
        } finally {
            transaction.finishCriticalSection();
        }
    }

    private void deleteInternalAfterLock(final Object objectId, final XdStorageTransaction transaction, XdStorageCacheRecord record) throws XdStorageException {
        log.debug("DELETE by transaction {} of id {}", transaction.getTransactionId(), objectId);

        final String transactionId = transaction.getTransactionId();

        // Перечитываем ноду из CHM, так как за время ожидания лока её могли подменить
        record = cache.records().get(objectId);
        if (record == null) {
            throw new XdStorageException("object of " + cache.entityClass() + " with idgeneration " + objectId + " was deleted by another transaction");
        }

        final Map<Object, XdStorageCacheObjectChange> readObjects = cache.workspace().readObjectsOf(transaction);

        synchronized (record) {
            final XdStorageCacheObjectChange change = readObjects.get(objectId);
            // R-42: инфраструктурные записи не отказывают по версии (см. комментарий в update) —
            // структурная конкуренция сериализована нодовыми write-локами. Релаксация действует
            // КРОМЕ незакоммиченной ЧУЖОЙ вставки (контракт фантомного удаления дерева,
            // BTreeEntityTest). Ноды удалению не подлежат контрактам — полная релаксация.
            final boolean infraRelax = cache.isStructuralNodeCache()
                    || (cache.isInfrastructureCache()
                        && !(record.isInsertChange() && !record.isCommitedState() && !record.isChangedByTransaction(transaction)));
            if (change != null) {
                if (!infraRelax && !record.canBeChangedByTransaction(transaction, change.readTimestamp)) {
                    throw new XdStorageException("object of " + cache.entityClass() + " with idgeneration " + objectId + " can not be updated by transaction " + transactionId);
                }
            } else if (!infraRelax && !record.canBeChangedByTransaction(transaction)) {
                throw new XdStorageException("object of " + cache.entityClass() + " with idgeneration " + objectId + " can not be deleted by transaction " + transactionId);
            }

            if (record.isReadChange()) {
                record.setNewObject(null);
                record.markDelete(transaction);
                readObjects.remove(objectId);

                listener.deleteRegistered(record.getObject(), transaction);
            } else if (!record.isCommitedState() && record.isChangedByTransaction(transaction)) {
                if (record.isInsertChange()) {
                    if (!cache.isInfrastructureCache()) {
                        cache.records().remove(objectId);

                        listener.deleteRegistered(record.getNewObject(), transaction);
                    } else {
                        // R-40: инфра-вставка, удаленная в той же tx, остается обслуживаемой —
                        // живой инстанс может оставаться в структуре дерева.
                        Object object = record.getNewObject();

                        record.setNewObject(null);
                        record.markDelete(transaction);

                        listener.deleteRegistered(object, transaction);
                    }
                    readObjects.remove(objectId);
                } else if (record.isUpdateChange()) {
                    record.setNewObject(null);
                    record.markDelete(transaction);
                    readObjects.remove(objectId);

                    listener.deleteRegistered(record.getObject(), transaction);
                } else {
                    throw new XdStorageException("trying to double delete one object of " + cache.entityClass() + " with idgeneration " + objectId);
                }
            } else if (infraRelax || record.canBeChangedByTransaction(transaction)) {
                Object object = record.getObject() == null ? record.getNewObject() : record.getObject();

                record.setNewObject(null);
                record.markDelete(transaction);
                readObjects.remove(objectId);

                listener.deleteRegistered(object, transaction);
            } else {
                throw new XdStorageException("concurrent modification one object of " + cache.entityClass() + " with idgeneration " + objectId);
            }
        }

        cache.workspace().registerMutation(transactionId, objectId, record);
    }
}
