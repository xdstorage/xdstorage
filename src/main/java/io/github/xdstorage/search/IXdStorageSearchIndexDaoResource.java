package io.github.xdstorage.search;

import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.List;

/** The DAO face of one persisted search-index resource: insert, update and delete of the
 * assembled {@code XdStorageSearchIndexRecord}s inside a caller's transaction, plus a
 * streaming scan of the whole index tree. Implemented by the resource that owns the index's
 * B+tree pair; the search manager reaches it through the resources manager
 * ({@code lockSearchIndexResource}).
 *
 * <p>Engine-internal — not part of the public API. Thread-safety is the implementation's
 * concern: calls arrive in the context of distinct transactions, coordinated by the
 * resources manager's locking. */
public interface IXdStorageSearchIndexDaoResource {

    /**
     * Indexes an object's assembled record: lazily creates the index's B+tree pair inside
     * the transaction when missing, then writes the search-tree and key-tree entries.
     * Repeated saves of an already-indexed object are a no-op (idempotency check in the
     * key tree).
     *
     * @param object      the assembled {@code XdStorageSearchIndexRecord} to index
     * @param transaction the caller's transaction
     * @throws XdStorageException         when the index structure cannot be initialized
     * @throws XdStorageConnectionException on a storage access failure
     */
    void insert(Object object, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Re-indexes an object's assembled record: rewrites the search-tree entry in place when
     * the PRIMARY value did not change, or moves the entry (delete old key, insert new) when
     * it did.
     *
     * @param object      the assembled {@code XdStorageSearchIndexRecord} to re-index
     * @param transaction the caller's transaction
     * @throws XdStorageException         when the object has no key-tree entry (never indexed)
     * @throws XdStorageConnectionException on a storage access failure
     */
    void update(Object object, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Removes an object's search-tree and key-tree entries. Absent entries (or a missing
     * tree) are silently tolerated — the index converges to the object-free state.
     *
     * @param object      the assembled {@code XdStorageSearchIndexRecord} of the object to de-index
     * @param transaction the caller's transaction
     * @throws XdStorageException         on an index access failure
     * @throws XdStorageConnectionException on a storage access failure
     */
    void delete(Object object, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Streams every record of the index tree to the watcher, in the tree's iteration order.
     *
     * <p><b>Semantics:</b> the B+tree iteration is snapshot-aware — records that became
     * invisible due to a concurrent commit during the walk are skipped (the tree's
     * versioned entries provide the visibility boundary). This contrasts with the hash
     * id-index's committed-state walk: the search B+tree IS versioned, while the hash
     * id-map is not.
     *
     * @param <T>         the record type the watcher observes
     * @param transaction the caller's transaction
     * @param watcher     the per-record callback
     * @throws XdStorageException         on an index access failure
     * @throws XdStorageConnectionException on a storage access failure
     */
    <T> void watch(XdStorageTransaction transaction, IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException;
}
