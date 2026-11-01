package io.github.xdstorage.resource.cache;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.util.Map;

/**
 * Внутренний SPI движка кэша: то, что коллабораторам (запись, мутатор, протокол
 * коммита) нужно от фасада {@link io.github.xdstorage.resource.XdStorageResourceCache}.
 * Выделен интерфейсом при переносе коллабораторов в подпакет cache — это делает
 * зависимость явной, а инварианты классов проверяемыми юнит-тестами без фасада.
 */
public interface XdStorageCacheContext {

    /**
     * W-4/FK (docs/tasks/2.1.0/32, task 42): the cache-level internal listener notified at
     * MUTATION-REGISTRATION time by the cache mutator (phase
     * {@link XdStorageCacheCommitPhase#PREPARE_COMMIT}, one notification per successful
     * insert/update/delete). The production context delegates to the resource's
     * per-transaction listener chain (the foreign-key engine registers there); tests
     * override it. Default: no listener.
     */
    default IXdStorageCacheInternalListener internalListener() {
        return null;
    }

    /**
     * Поле идентификатора сущности данного ресурса.
     *
     * @return the id field accessor of this resource's entity class
     */
    XdStorageObjectIdField idField();

    /**
     * Реестр записей кэша: id объекта -> MVCC-запись.
     *
     * @return the live records registry of this cache, keyed by object id
     */
    Map<Object, XdStorageCacheRecord> records();

    /**
     * Транзакционные контуры (write-set / журнал отката / неидентифицированные / прочитанное).
     *
     * @return the transaction workspace holding this cache's per-transaction state
     */
    XdStorageTransactionWorkspace workspace();

    /**
     * Кэш обслуживает инфраструктуру B+Дерева (деревья/узлы) — живые канонические инстансы.
     *
     * @return {@code true} if this cache serves B+Tree infrastructure (trees/nodes) whose
     *         objects are kept as live canonical instances
     */
    boolean isInfrastructureCache();

    /**
     * Кэш обслуживает именно НОДЫ дерева — полная структурная релаксация (R-42).
     *
     * @return {@code true} if this cache serves tree nodes specifically (full structural
     *         conflict-check relaxation)
     */
    boolean isStructuralNodeCache();

    /**
     * Класс сущности данного ресурса (для сообщений об отказах).
     *
     * @return the entity class of this resource, used in failure messages
     */
    Class<?> entityClass();

    /**
     * Жива ли транзакция (контур мертвых владельцев записей).
     *
     * @param transaction the transaction to test
     * @return {@code true} if the transaction is still alive (records owned by a dead
     *         transaction become adoptable)
     */
    boolean isTransactionAlive(XdStorageTransaction transaction);

    /**
     * Уровень изоляции ресурса (SNAPSHOT | READ_COMMITTED).
     *
     * @return the isolation level configured for this resource
     */
    XdStorageIsolationLevel getIsolationLevel();

    /**
     * Запись чистого чтения (объект пришел с диска / из живой структуры).
     *
     * @param object the materialized committed object the record will hold
     * @return a new cache record in the clean-read state
     */
    XdStorageCacheRecord createReadRecord(Object object);

    /**
     * Запись незакоммиченной вставки (контент в newObject).
     *
     * @param newObject   the object being inserted (owned by the transaction until commit)
     * @param transaction the inserting transaction, which becomes the record's owner
     * @return a new cache record in the uncommitted-insert state
     */
    XdStorageCacheRecord createInsertRecord(Object newObject, XdStorageTransaction transaction);

    /**
     * Материализация объекта для записи в кэш: живая инстанция для B+ Дерева,
     * изолированный клон для доменных объектов (+ канонизация в реестре).
     *
     * @param newObject the object about to be written into the cache
     * @return the instance to store: the live instance for infrastructure caches,
     *         an isolated (canonized) clone for domain objects
     * @throws XdStorageException if the object cannot be cloned or canonized
     */
    Object materializeForCache(Object newObject) throws XdStorageException;

    /**
     * Полная публичная вставка фасада (защита от дубликатов + критическая секция):
     * нужна мутатору для самозарвки инфраструктурных объектов (R-40).
     *
     * @param newObject   the object to insert into this resource
     * @param transaction the transaction performing the insertion
     * @throws XdStorageException if the object has no id, already exists, or is locked
     *                            by another transaction
     */
    void insert(Object newObject, XdStorageTransaction transaction) throws XdStorageException;
}
