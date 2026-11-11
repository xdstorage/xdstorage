package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.Collection;

/**
 * The record-level DAO contract of one stored resource — the lowest storage layer that the
 * transactional resource objects ({@code IXdStorageResourceObject}) delegate to. Implementations
 * are shared between threads: reads resolve through the owning resource's MVCC cache inside the
 * caller's transaction critical section, and writes are only staged in the transaction's write
 * set — they reach the file exclusively through the two-phase commit protocol. The reference
 * operations ({@code readReferences}, {@code insertReference}, {@code deleteReference}) serve
 * the parent/child bookkeeping of one-file-per-object resources.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public interface IXdStorageDaoResource extends IXdStorageListenableResource {

    /**
     * The storage-wide unique id of this resource (its relative file path).
     *
     * @return the resource id, never {@code null} for a mounted resource
     */
    Object getResourceId();

    /**
     * Reads the resource file from disk as raw data records, bypassing the cache — used by the
     * structure/recovery passes, not by regular object reads.
     *
     * @param transaction the transaction context the read runs in
     * @return the data records of the resource, or {@code null} when the backing file does not exist
     * @throws XdStorageException when the file cannot be read or parsed
     */
    Collection<XdStorageIdentifiableObject> readAsData(final XdStorageTransaction transaction) throws XdStorageException;

    /**
     * Whether the resource holds an object with the given id in the transaction-visible state.
     *
     * @param objectId    the object id to look up
     * @param transaction the transaction whose snapshot the check runs in
     * @return {@code true} when the object is present and visible to the transaction
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    boolean hasObject(final Object objectId, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * All reference records held by this resource — for a references resource, the objects
     * pointing at their parent.
     *
     * @param transaction the transaction whose snapshot the read resolves in
     * @return the reference records (an empty collection when there are none)
     * @throws XdStorageException on a storage-level failure
     */
    Collection<Object> readReferences(final XdStorageTransaction transaction) throws XdStorageException;

    /**
     * Records a reference in this resource; when the reference's id strategy is external
     * generation (database or custom generator) and no id has been set yet, an id is assigned
     * to the reference object first. The change is staged in the transaction until commit.
     *
     * @param reference   the referencing object (must carry or receive an id)
     * @param transaction the transaction to stage the insert in
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void insertReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Removes the reference with the id carried by the passed object. The change is staged in
     * the transaction until commit.
     *
     * @param reference   the referencing object whose id identifies the record to remove
     * @param transaction the transaction to stage the delete in
     * @throws XdStorageException on a storage-level failure
     */
    void deleteReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException;

    /**
     * Looks up the transaction-local unidentified form of the given object — the observable
     * wrapper registered by an earlier read of the same not-yet-identified object.
     *
     * @param <T>         the expected type of the registered object
     * @param object      the object whose transaction-local twin is looked up
     * @param transaction the owning transaction
     * @return the registered unidentified object, or {@code null} when none was registered
     * @throws XdStorageException on a storage-level failure
     */
    <T> T find(final Object object, final XdStorageTransaction transaction) throws XdStorageException;

    /**
     * Reads every object of the resource visible in the transaction's snapshot, materializing
     * (cloning and wrapping) records not yet materialized by this transaction.
     *
     * @param <T>         the entity class of the stored objects
     * @param transaction the transaction whose snapshot is read
     * @return the snapshot-visible objects of the resource
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    <T> Collection<T> read(final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Reads the snapshot-visible objects that pass the predicate — a filtered in-snapshot scan;
     * no index is involved.
     *
     * @param <T>         the entity class of the stored objects
     * @param transaction the transaction whose snapshot is read
     * @param predicate   the filter applied per materialized record
     * @return the snapshot-visible objects passing the filter
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    <T> Collection<T> read(final XdStorageTransaction transaction, final IXdStoragePredicate<T> predicate) throws XdStorageException, XdStorageConnectionException;

    /**
     * Streams every object visible in the transaction's snapshot to the watcher without
     * materializing the whole result collection. Throwing from the callback fails the scan.
     *
     * @param <T>         the entity class the watcher observes
     * @param transaction the transaction whose snapshot is scanned
     * @param watcher     the per-object callback
     * @throws XdStorageException          on a storage-level failure or a callback failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    <T> void watch(final XdStorageTransaction transaction, final IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads the stored state of the object with the id carried by {@code reference} into
     * {@code reference} itself (hydrates the reference in place).
     *
     * @param reference   the reference object to hydrate (must carry an id)
     * @param transaction the transaction whose snapshot the load resolves in
     * @throws XdStorageException          when the referenced object cannot be loaded
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void readByReference(final Object reference, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * The object with the given id, as visible in the transaction's snapshot.
     *
     * @param id          the object id
     * @param transaction the transaction whose snapshot is read
     * @return the materialized object, or {@code null} when the id is absent or invisible
     *         in the snapshot
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    Object read(final Object id, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Stages the insert of a new object into the transaction's write set; the object becomes
     * visible to others and durable only at commit.
     *
     * @param object      the object to insert (must receive or carry an id)
     * @param transaction the transaction to stage the insert in
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void insert(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Stages the update of an existing object into the transaction's write set; the new state
     * becomes visible to others and durable only at commit.
     *
     * @param object      the object with its new state
     * @param transaction the transaction to stage the update in
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void update(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Stages the deletion of the object with the id carried by the passed object; the record
     * disappears for others only at commit.
     *
     * @param object      the object whose id identifies the record to delete
     * @param transaction the transaction to stage the delete in
     * @throws XdStorageException          on a storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    void delete(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

}
