package io.github.xdstorage.transaction;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;

/** One storage transaction: the unit of atomicity for every CRUD operation. Obtained
 * from {@code IXdStorage.beginTransaction(...)}; transactions are bound to the creating
 * thread (the CRUD overloads without an explicit transaction argument use that
 * binding) but MAY be used from many threads — the engine defers a rollback until
 * every active thread has left.
 *
 * <p>Commit semantics: a transaction marked rollback-only (wrapper-generated accessors
 * and search errors mark it) fails its commit; a CRUD failure does NOT mark it — the
 * caller decides to roll back or retry. Nested transactions
 * ({@code beginTransaction(parent, ...)}) roll back downward-only — a nested rollback
 * never poisons the parent. Timeouts are enforced lazily (at lock waits/latches/sweeps),
 * not by an expiry timer. */
public interface IXdStorageTransaction {

    /**
     * The manager this transaction belongs to.
     *
     * @return the owning storage's transaction manager
     */
    IXdStorageTransactionManager<?> getTransactionManager();

    /**
     * When the transaction was started.
     *
     * @return the start timestamp (millis)
     */
    long getTimestart();

    /**
     * The storage this transaction works on.
     *
     * @return the owning storage
     */
    IXdStorage getStorage();

    /**
     * The creating thread's identity (transactions are bound to their creating thread).
     *
     * @return the thread id string recorded at begin
     */
    String getTransactionThreadId();

    /**
     * The transaction's unique id (log/metrics correlation).
     *
     * @return the transaction id
     */
    String getTransactionId();

    /**
     * The configured timeout.
     *
     * @return the timeout in milliseconds
     */
    long getTimeout();

    /**
     * Commits the transaction (two-phase over every touched resource). Fails for a
     * rollback-only transaction; a timeout may also abort the commit.
     */
    void commit();

    /**
     * Rolls the transaction back — every change is discarded. Safe on an
     * already-finished transaction (no-op).
     */
    void rollback();

    /**
     * Marks the transaction rollback-only: its eventual commit will fail. Used by
     * wrapper-generated accessors and search errors; application code may call it to
     * veto a commit without discarding in-flight work yet.
     */
    void markRollbackOnly();

    /**
     * Whether the transaction is marked rollback-only.
     *
     * @return {@code true} when the commit would fail
     */
    boolean isRollbackOnly();

    /**
     * R-57: registers a successfully inserted index-tree key for COMPENSATING deletion
     * when the transaction rolls back. Tree mutations happen in-place in the live
     * canonical graph and are NOT undone by record-state rollback: without the
     * compensation an index key would outlive its transaction's rollback, forever
     * pointing snapshot readers at a record that no longer exists ("cannot load by
     * reference").
     *
     * @param tree the index tree that received the inserted key
     * @param key  the key to delete from that tree when the transaction rolls back
     */
    void registerIndexKeyForRollback(Object tree, Comparable key);

    /**
     * R-57: compensating deletion of every registered index key (in reverse
     * registration order). Deletion failures for absent keys are swallowed with a
     * WARN — the key may have been removed for the same reason the transaction rolled
     * back.
     */
    void rollbackIndexKeyInserts();

    /**
     * The mirror of {@link #registerIndexKeyForRollback} for DELETED keys: the tree
     * key removal is in-place and immediate, so a transaction that deletes an indexed
     * object and then rolls back would otherwise leave the key consumed forever — the
     * record and every reference to it come back with the record-state rollback, but
     * the index can no longer resolve the id ("cannot load by reference" at the next
     * indexed read). The (key, value) pair is re-inserted on rollback, which is what
     * keeps the B+tree READ COMMITTED always: after the rollback the index reflects
     * exactly the committed state.
     *
     * @param tree  the index tree the key was deleted from
     * @param key   the deleted key
     * @param value the index value to restore the key with (for the id-index tree: the
     *              data-fragment resource id the record lived in)
     */
    void registerIndexKeyDeleteForRollback(Object tree, Comparable key, Object value);

    /**
     * Compensating re-insert of every registered deleted index key (in reverse
     * registration order). A failing re-insert is logged with a WARN and never blocks
     * the rollback.
     */
    void rollbackIndexKeyDeletes();
}
