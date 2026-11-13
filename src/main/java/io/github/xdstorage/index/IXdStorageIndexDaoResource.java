package io.github.xdstorage.index;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.Collection;

/**
 * The id-index DAO surface shared by the hash and B+tree id-index resources: it routes an
 * object id to the data-fragment resource holding the object's record (see {@code
 * #getObjectResourceId}) and passes the record-level read/write operations through to that
 * fragment. Every operation takes the transaction explicitly — visibility and staging
 * follow the given transaction's snapshot and isolation level.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public interface IXdStorageIndexDaoResource {

    /**
     * Resolves the id of the data-fragment resource that holds the record of the given
     * object id.
     *
     * @param objectId    the object id to look up
     * @param transaction the transaction whose snapshot defines visibility
     * @return the data-fragment resource id, or {@code null} when the index does not know
     *         the id
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    Object getObjectResourceId(Object objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Whether the index knows the given object id (a record would be reachable through it).
     *
     * @param id          the object id to test
     * @param transaction the transaction whose snapshot defines visibility
     * @return {@code true} when the id maps to a data fragment
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    boolean has(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Reads every object of the indexed class by walking the index and loading each id's
     * record from its data fragment.
     *
     * <p><b>Semantics:</b> the id-map walk uses committed (globally visible) state —
     * objects removed by a concurrent commit during the walk ARE returned (they survive
     * in the fragment until their own transaction commits deletion). This is by design
     * for the hash id-index: the id-map is not versioned, so a concurrent delete that
     * removed an id from the map is not visible here.
     *
     * @param <T>         the element type the result collection is viewed as
     * @param transaction the transaction whose snapshot defines visibility
     * @return the objects visible to this transaction (never {@code null})
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    <T> Collection<T> read(final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Reads the objects of the indexed class that pass the predicate, by the same index
     * walk as {@code #read(XdStorageTransaction)}. The predicate sees each object wrapped
     * as an unmodifiable view.
     *
     * @param <T>         the element type the result collection is viewed as
     * @param transaction the transaction whose snapshot defines visibility
     * @param predicate   the filter applied to each candidate object
     * @return the matching objects visible to this transaction (never {@code null})
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    <T> Collection<T> read(final XdStorageTransaction transaction, final IXdStoragePredicate<T> predicate) throws XdStorageException, XdStorageConnectionException;

    /**
     * Delivers every object of the indexed class (wrapped as an unmodifiable view) to the
     * watcher, by the same index walk as {@code #read(XdStorageTransaction)}.
     *
     * @param <T>         the element type the watcher receives
     * @param transaction the transaction whose snapshot defines visibility
     * @param watcher     the callback invoked once per visible object
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    <T> void watch(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException;

    /**
     * Populates the given reference object in place from its stored record: the reference's
     * id is resolved through the index, then the data fragment fills the reference.
     *
     * @param reference   the reference object to populate (its id field must be set)
     * @param transaction the transaction whose snapshot defines visibility
     * @throws XdStorageException           when the index does not know the reference's id,
     *         or on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void readByReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads one object by id: resolves the id to its data fragment through the index and
     * reads the record from it.
     *
     * @param id          the object id to load
     * @param transaction the transaction whose snapshot defines visibility
     * @return the stored object, or {@code null} when the index does not know the id (or
     *         the record is invisible to this transaction's snapshot)
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    Object read(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Indexes a new object: stores its record into a data fragment and registers the
     * id {@code ->} fragment mapping in the index.
     *
     * @param object      the object to insert (its id field must be set, or resolvable by
     *                    the implementation's id generator)
     * @param transaction the transaction the insert is staged into
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void insert(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Updates the stored record of an existing indexed object.
     *
     * @param object      the object to update (its id must already be indexed)
     * @param transaction the transaction the update is staged into
     * @throws XdStorageException           when the object's id is not indexed, or on a
     *         storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void update(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Removes the record of an indexed object and its id {@code ->} fragment mapping.
     *
     * @param object      the object to delete (its id must already be indexed)
     * @param transaction the transaction the delete is staged into
     * @throws XdStorageException           when the object's id is not indexed, or on a
     *         storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void delete(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;
}
