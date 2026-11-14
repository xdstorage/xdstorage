package io.github.xdstorage;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.search.query.IXdStorageCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.trigger.IXdStorageTrigger;

import java.util.Collection;

/**
 * The transactional object store contract: CRUD, transactions and triggers for entity
 * classes annotated with {@link io.github.xdstorage.annotations.XdStorageObjectId}.
 *
 * <p>Threading and transaction model (the full operational contract lives in
 * {@code docs/EMBEDDING.md}):
 * <ul>
 *   <li><b>Transactions are bound to the owning thread.</b> Every CRUD overload without an
 *       explicit transaction argument uses the current thread's binding and throws
 *       {@link XdStorageException} ("method must be executed in transaction") when none is
 *       open. A thread that abandons its transaction leaves it bound — always commit or
 *       roll back in a {@code finally}, or pass transactions explicitly.</li>
 *   <li><b>One transaction may be used from many threads</b> (e.g. parallel streams); the
 *       engine defers its rollback until every active thread has left.</li>
 *   <li><b>A failed CRUD operation throws but does NOT roll the transaction back</b> — the
 *       failure is yours to translate into {@link #rollbackTransaction}; commit of a
 *       rollback-only transaction (marked by wrapper accessors and search errors) throws.</li>
 *   <li>Reads answer from the transaction's MVCC snapshot ({@code SNAPSHOT} isolation by
 *       default, {@link io.github.xdstorage.resource.XdStorageIsolationLevel#READ_COMMITTED}
 *       configurable at open).</li>
 * </ul>
 *
 * <p>Open instances through {@link XdStorageProvider}; after {@link #shutdown()} every
 * method of the instance throws {@link io.github.xdstorage.exceptions.XdStorageRuntimeException}.
 *
 * @see IXdFileStorage — the extended contract with search queries, structure updates and watchers
 * @see XdStorageProvider#newOrGetFileStorage(String, String)
 */
public interface IXdStorage {

    /**
     * The storage's open-time name — the key {@link XdStorageProvider} registered it under.
     *
     * @return the storage name (unique per JVM while the instance is open)
     */
    String getName();

    /** O-1: operational counters for this storage (see XdStorageMetrics).
     *
     * @return the per-storage metrics instance (live counters, thread-safe)
     */
    io.github.xdstorage.metrics.XdStorageMetrics getMetrics();

    /**
     * Registers a trigger fired by the engine inside the changing transaction whenever an
     * object of the trigger's class is inserted, updated or deleted. A trigger must work
     * only with the object handed to it; using the storage inside a trigger requires
     * opening (and finishing) its own nested transaction.
     *
     * @param <T> the entity class the trigger observes
     * @param trigger the trigger to register
     */
    <T> void registerTrigger(IXdStorageTrigger<T> trigger);

    /**
     * Begins a transaction with an explicit timeout and binds it to the current thread.
     *
     * @param timeout the transaction timeout in milliseconds (enforced lazily; see
     *                {@link #beginTransaction()})
     * @return the new transaction (also bound to the calling thread)
     */
    IXdStorageTransaction beginTransaction(long timeout);

    /**
     * Begins a transaction with the storage's configured default timeout
     * ({@code defaultTransactionTimeoutMillis}, 3000&nbsp;ms by default) and binds it to the
     * current thread.
     *
     * <p>Timeouts are enforced lazily: a timed-out transaction is rolled back when a lock
     * wait, record latch or the rollback-only sweeper trips, not by an expiry timer.
     *
     * @return the new transaction (also bound to the calling thread)
     */
    IXdStorageTransaction beginTransaction();

    /**
     * Begins a <b>nested</b> transaction inside the given parent with an explicit timeout.
     *
     * @param transaction the parent transaction
     * @param timeout     the nested transaction's timeout in milliseconds
     * @return the new nested transaction (also bound to the calling thread)
     */
    IXdStorageTransaction beginTransaction(IXdStorageTransaction transaction, long timeout);

    /**
     * Begins a <b>nested</b> transaction inside the given parent and binds it to the
     * current thread. The nested transaction uses the default timeout (3000&nbsp;ms).
     * A nested rollback does not poison the parent (the rollback-only cascade is
     * downward-only); the parent's commit waits for open nested transactions.
     *
     * @param transaction the parent transaction
     * @return the new nested transaction (also bound to the calling thread)
     */
    IXdStorageTransaction beginTransaction(IXdStorageTransaction transaction);

    /**
     * Commits the transaction: two-phase-commit over every resource the transaction
     * touched. Committing a rollback-only transaction throws
     * {@link XdStorageException}; committing an already-finished transaction also fails.
     * On success the changes become visible to transactions that start afterwards.
     *
     * @param transaction the transaction to commit
     * @throws XdStorageException on any commit failure — the transaction's fate (rolled
     *                            back or left for an explicit rollback) is stated in the
     *                            exception message and the storage metrics
     */
    void commitTransaction(IXdStorageTransaction transaction) throws XdStorageException;

    /**
     * Rolls the transaction back: every change it made is discarded. Idempotent for an
     * already-finished transaction (no-op, not an error). A transaction that timed out is
     * rolled back by the engine at the next enforcement point.
     *
     * @param transaction the transaction to roll back
     */
    void rollbackTransaction(IXdStorageTransaction transaction);

    /**
     * Inserts the object as a new record. Requires an open transaction: with no explicit
     * {@code transaction} argument the engine uses the one bound to the calling thread and
     * throws {@link XdStorageException} when none is open.
     *
     * <p>The class must carry an id field ({@code @XdStorageObjectId}) and a storing policy
     * ({@code @XdStorageObjectPolicy}). When the id field is {@code null} and its generator
     * type is {@code DATABASE_GENERATOR}/{@code CUSTOM_GENERATOR}, the id is generated
     * <b>inside this call</b> and written back onto the same instance (visible to the
     * caller immediately, durable only after the commit). A failed save throws but does
     * <b>not</b> roll the transaction back.
     *
     * @param object the object to insert; must be a top-level class with an id field
     * @throws XdStorageException                when no transaction is bound to the thread,
     *                                           the transaction is not alive, the object is
     *                                           {@code null}, or the object's class has no
     *                                           id field or a broken policy
     * @throws XdStorageConnectionException      on a storage-level I/O failure
     */
    @SuppressWarnings("unchecked")
    void save(Object object) throws XdStorageException, XdStorageConnectionException;

    /**
     * Inserts the object as a new record inside the given transaction. Same contract as
     * {@link #save(Object)}, including the null-id backfill onto the same instance.
     *
     * @param object      the object to insert
     * @param transaction the transaction to save in (must be alive)
     * @throws XdStorageException           when the transaction is not alive, the object is
     *                                      {@code null}, or the object's class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void save(Object object, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Inserts every object of the collection. Stops at the first failure and rethrows it;
     * objects already inserted in this call stay inserted (inside the transaction).
     * Requires an open transaction — see {@link #save(Object)}.
     *
     * @param objects the objects to insert; an empty collection is a no-op
     * @throws XdStorageException           when no transaction is bound to the thread, or
     *                                      any object's class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void save(Collection<?> objects) throws XdStorageException, XdStorageConnectionException;

    /**
     * Inserts every object of the collection inside the given transaction.
     *
     * @param objects      the objects to insert; an empty collection is a no-op
     * @param transaction  the transaction to save in (must be alive)
     * @throws XdStorageException           when the transaction is not alive, or any
     *                                      object's class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void save(Collection<?> objects, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Answers whether an object with the given id exists. Requires an open transaction —
     * see {@link #save(Object)}.
     *
     * @param cl the entity class
     * @param id the id to look up
     * @return {@code true} if and only if the storage holds an object of {@code cl} with
     *         this id (visible in the current transaction's snapshot)
     * @throws XdStorageException           when no transaction is bound to the thread, the
     *                                      transaction is not alive, or {@code cl} has no
     *                                      id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    boolean has(Class<?> cl, Object id) throws XdStorageException, XdStorageConnectionException;

    /**
     * Answers whether an object with the given id exists in the given transaction's snapshot.
     *
     * @param cl          the entity class
     * @param id          the id to look up
     * @param transaction the transaction to read in (must be alive)
     * @return {@code true} if and only if the object exists in the snapshot
     * @throws XdStorageException           when the transaction is not alive or {@code cl}
     *                                      has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    boolean has(Class<?> cl, Object id, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Hydrates a reference in place: the passed object (typically an unfilled reference
     * obtained from another loaded object) has its fields overwritten from storage by its
     * id. Requires an open transaction — see {@link #save(Object)}. A dangling reference
     * (no record under its id) throws {@link XdStorageException}; a reference invisible in
     * the reader's MVCC snapshot is the softer
     * {@link io.github.xdstorage.exceptions.XdStorageReferenceInvisibleException}.
     *
     * @param reference the object to hydrate (its id field must be set)
     * @throws XdStorageException           when no transaction is bound to the thread, the
     *                                      reference is {@code null} or has no id, or no
     *                                      record exists for the reference's id
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void load(Object reference) throws XdStorageException, XdStorageConnectionException;

    /**
     * Hydrates a reference in place inside the given transaction — see
     * {@link #load(Object)}.
     *
     * @param reference   the object to hydrate (its id field must be set)
     * @param transaction the transaction to read in (must be alive)
     * @throws XdStorageException           when the transaction is not alive or no record
     *                                      exists for the reference's id
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void load(Object reference, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads an object by id. Requires an open transaction — see {@link #save(Object)}.
     * The returned instance is a snapshot materialization: later commits by other
     * transactions do not mutate it.
     *
     * @param <T> the entity class
     * @param cl the entity class
     * @param id the id to load by
     * @return the loaded object
     * @throws XdStorageException           when no transaction is bound to the thread, or
     *                                      no object with this id exists in the snapshot
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    <T> T load(Class<T> cl, Object id) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads an object by id inside the given transaction.
     *
     * @param <T>         the entity class
     * @param cl          the entity class
     * @param id          the id to load by
     * @param transaction the transaction to read in (must be alive)
     * @return the loaded object
     * @throws XdStorageException           when the transaction is not alive, or no object
     *                                      with this id exists in the snapshot
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    @SuppressWarnings("unchecked")
    <T> T load(Class<T> cl, Object id, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Hydrates every reference of the collection in place (per-element semantics of
     * {@link #load(Object)}). An element invisible in the reader's MVCC snapshot is
     * skipped (stays unfilled), not an error. Requires an open transaction.
     *
     * @param references the references to hydrate; an empty collection is a no-op
     * @throws XdStorageException           when no transaction is bound to the thread, or
     *                                      a reference dangles (no record under its id)
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void load(Collection<?> references) throws XdStorageException, XdStorageConnectionException;

    /**
     * Hydrates every reference of the collection inside the given transaction — see
     * {@link #load(Collection)}.
     *
     * @param references  the references to hydrate; an empty collection is a no-op
     * @param transaction the transaction to read in (must be alive)
     * @throws XdStorageException           when the transaction is not alive, or a
     *                                      reference dangles
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void load(Collection<?> references, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads every object of the class. Requires an open transaction — see
     * {@link #save(Object)}. For classes under the
     * {@link io.github.xdstorage.XdStoragePolicy#StoreAsSingleObject} policy this materializes
     * every stored object; under {@link io.github.xdstorage.XdStoragePolicy#StoreAsClassObjects}
     * it reads the class file's records.
     *
     * @param <T> the entity class
     * @param cl the entity class
     * @return the objects visible in the current transaction's snapshot
     * @throws XdStorageException           when no transaction is bound to the thread or
     *                                      the class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    <T> Collection<T> load(Class<T> cl) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads every object of the class inside the given transaction.
     *
     * @param <T>         the entity class
     * @param cl          the entity class
     * @param transaction the transaction to read in (must be alive)
     * @return the objects visible in the transaction's snapshot
     * @throws XdStorageException           when the transaction is not alive or the class
     *                                      has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    @SuppressWarnings("unchecked")
    <T> Collection<T> load(Class<T> cl, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Replaces the stored record of the object (matched by its id) with the object's
     * current state. Saving a new object with an already-stored id is an insert, not an
     * update — call update explicitly. Requires an open transaction — see
     * {@link #save(Object)}. A failed update throws but does <b>not</b> roll the
     * transaction back.
     *
     * @param object the object whose stored record to replace (its id field must be set)
     * @throws XdStorageException           when no transaction is bound to the thread, the
     *                                      transaction is not alive, or the object's class
     *                                      has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void update(Object object) throws XdStorageException, XdStorageConnectionException;

    /**
     * Replaces the stored record of the object inside the given transaction — see
     * {@link #update(Object)}.
     *
     * @param object      the object whose stored record to replace
     * @param transaction the transaction to update in (must be alive)
     * @throws XdStorageException           when the transaction is not alive or the
     *                                      object's class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void update(Object object, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Updates every object of the collection (per-element semantics of
     * {@link #update(Object)}). Stops at the first failure and rethrows it. Requires an
     * open transaction.
     *
     * @param objects the objects to update; an empty collection is a no-op
     * @throws XdStorageException           when no transaction is bound to the thread, or
     *                                      any object's class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void update(Collection<?> objects) throws XdStorageException, XdStorageConnectionException;

    /**
     * Updates every object of the collection inside the given transaction.
     *
     * @param objects     the objects to update; an empty collection is a no-op
     * @param transaction the transaction to update in (must be alive)
     * @throws XdStorageException           when the transaction is not alive, or any
     *                                      object's class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void update(Collection<?> objects, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Deletes the object's record (matched by its id). Requires an open transaction —
     * see {@link #save(Object)}. Deleting a record that does not exist fails loudly
     * inside the transaction.
     *
     * @param reference the object (or a reference carrying its id) to delete
     * @throws XdStorageException           when no transaction is bound to the thread, the
     *                                      reference is {@code null}, or its class has no
     *                                      id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void delete(Object reference) throws XdStorageException, XdStorageConnectionException;

    /**
     * Deletes the object's record inside the given transaction.
     *
     * @param reference   the object (or a reference carrying its id) to delete
     * @param transaction the transaction to delete in (must be alive)
     * @throws XdStorageException           when the transaction is not alive or the
     *                                      reference's class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void delete(Object reference, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Deletes the record of every object in the collection (per-element semantics of
     * {@link #delete(Object)}). Requires an open transaction.
     *
     * @param references the objects to delete; an empty collection is a no-op
     * @throws XdStorageException           when no transaction is bound to the thread
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void delete(Collection<?> references) throws XdStorageException, XdStorageConnectionException;

    /**
     * Deletes the record of every object in the collection inside the given transaction.
     *
     * @param references  the objects to delete; an empty collection is a no-op
     * @param transaction the transaction to delete in (must be alive)
     * @throws XdStorageException           when the transaction is not alive
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    void delete(Collection<?> references, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * The storage's transaction manager — the lower-level handle used by
     * {@code IXdStorageTransaction} ({@code commit()}/{@code rollback()}) and by code that
     * needs a transaction by id. Application code normally uses
     * {@link #beginTransaction()}, {@link #commitTransaction(IXdStorageTransaction)} and
     * {@link #rollbackTransaction(IXdStorageTransaction)} directly.
     *
     * @return the transaction manager of this storage
     */
    IXdStorageTransactionManager getTransactionManager();

    /**
     * Closes the storage: releases the exclusive folder lock, checkpoints every journal
     * (best-effort — a clean close means a later power loss loses nothing even with
     * {@code durableWrites=false}) and unregisters the instance from
     * {@link XdStorageProvider} (the name becomes openable again).
     *
     * <p>Idempotent. After it every method of this instance throws
     * {@link io.github.xdstorage.exceptions.XdStorageRuntimeException}. In-flight
     * transactions are NOT waited for — quiesce your workers before shutting down.
     * Worker threads are non-daemon by default, so a forgotten {@code shutdown()} blocks
     * JVM exit (see {@code XdStorageConfig.builder().daemonWorkerThreads(true)}).
     */
    void shutdown();

    /**
     * W-4c (docs/tasks/2.1.0/48): force-checkpoints every open resource with a non-empty
     * journal — full committed-state rewrite + journal reset. Idempotent: calling it
     * when no journals are non-empty is a no-op. Safe to call at any time (including
     * during an open transaction) — it operates on committed cache state and acquires
     * per-resource checkpoint locks. After this call the folder's base files are
     * authoritative and all journals are empty.
     *
     * <p><b>Contract:</b> after {@code checkpoint()} completes (no exception thrown),
     * every open resource's journal is empty and its base file reflects the committed
     * state. A power loss between this call and the next open loses nothing.
     *
     * @throws XdStorageConnectionException on a storage-level I/O failure during the rewrite
     */
    void checkpoint();
}
