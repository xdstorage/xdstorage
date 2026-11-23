package io.github.xdstorage.transaction;

import io.github.xdstorage.IXdStorage;

/** The transaction factory and registry of one storage: begins transactions (also the
 * thread-binding authority for {@code getCurrentTransaction()}), commits/rolls back,
 * and answers liveness. Application code reaches it via
 * {@code IXdStorage.getTransactionManager()}; the facade methods are usually enough.
 *
 * @param <T> the concrete transaction type of the storage */
public interface IXdStorageTransactionManager<T extends IXdStorageTransaction> {

    /**
     * The storage this manager serves.
     *
     * @return the owning storage
     */
    IXdStorage getStorage();

    /**
     * Begins a new transaction with an explicit timeout and binds it to the calling thread.
     *
     * @param timeout the timeout in milliseconds
     * @return the new transaction
     */
    T beginTransaction(long timeout);

    /**
     * Begins a NESTED transaction inside the given parent (downward-only rollback-only
     * cascade; the parent waits for open nested transactions at commit).
     *
     * @param transaction the parent transaction
     * @param timeout     the nested timeout in milliseconds
     * @return the new nested transaction
     */
    T beginTransaction(T transaction, long timeout);

    /**
     * Looks a transaction up by id.
     *
     * @param transactionId the id recorded at begin
     * @return the transaction, or {@code null} when unknown/finished
     */
    T getTransaction(String transactionId);

    /**
     * Commits the transaction.
     *
     * @param transaction the transaction to commit
     */
    void commitTransaction(T transaction);

    /**
     * Rolls the transaction back (idempotent for finished transactions).
     *
     * @param transaction the transaction to roll back
     */
    void rollbackTransaction(T transaction);

    /**
     * The transaction bound to the CURRENT thread — the one every CRUD overload without
     * an explicit argument uses. A thread that abandons its transaction leaves it bound.
     *
     * @return the thread's current transaction, or {@code null} when none is open
     */
    T getCurrentTransaction();

    /**
     * Whether the transaction is still alive (begun, not yet committed/rolled back and
     * not swept by timeout).
     *
     * @param transaction the transaction to test
     * @return {@code true} while the transaction can still do work
     */
    boolean isTransactionAlive(T transaction);

    /**
     * Registers the transaction as rollback-only (its commit will fail).
     *
     * @param transaction the transaction to mark
     */
    void registerRollbackOnlyTransaction(T transaction);
}
