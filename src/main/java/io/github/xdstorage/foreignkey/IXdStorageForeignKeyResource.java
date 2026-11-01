package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.Collection;

public interface IXdStorageForeignKeyResource extends IXdStorageResourceObject {

    Collection<Object> readByParentObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    Collection<Object> readByChildObjectId(Comparable<?> objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(IXdStorageIdObservableWrapper parentObject, Comparable<?> childId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(Comparable<?> parentObjectId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void insert(IXdStorageIdObservableWrapper parentObject, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

//    void delete(Object parentObject, Object childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;
//
//    void delete(Object parentObject, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;
//
//    void delete(Comparable<?> parentObjectId, Object childObject, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void delete(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId,
                XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * FNKEY (docs/tasks/2.1.0/42): marks a CHILD object as deleted within the transaction —
     * resolves the pending "deleted parent expects this child's deletion" expectations for
     * the child and arms the child's pending-entry expectations to be checked at commit.
     * The engine calls it from the child-delete (income DELETE) flow of parent-anchored
     * relations.
     *
     * @param childObjectId the deleted child's identifier
     * @param transaction   the deleting transaction
     */
    void markChildDeleted(Comparable<?> childObjectId, XdStorageTransaction transaction);

    /**
     * FNKEY (docs/tasks/2.1.0/42): registers the (parent, child) entry expectation for a
     * child DELETED in this transaction whose entry still exists — the parent side must
     * dissolve the entry within the same transaction or the commit veto fires. The engine
     * calls it from the child-delete (income DELETE) flow of parent-anchored relations.
     *
     * @param parentObjectId the parent side of the still-present entry
     * @param childObjectId  the deleted child's identifier
     * @param transaction    the deleting transaction
     */
    void registerOrphanedEntry(Comparable<?> parentObjectId, Comparable<?> childObjectId, XdStorageTransaction transaction);

    /**
     * FNKEY (docs/tasks/2.1.0/42): registers a child INSERT under a parent-anchored
     * relation — the pair (parent, child) MUST be registered by the parent's save within
     * the same transaction or the commit veto fires. Immediate rejections:
     * a {@code null}/{@code id-less} parent reference throws on the spot (a child cannot
     * declare a parent that is not there), and a pair that is ALREADY registered throws
     * the duplicate-pair rejection.
     *
     * @param childObjectId  the saved child's identifier
     * @param parentObjectId the declared parent's identifier, {@code null} when unset
     * @param transaction    the saving transaction
     * @throws XdStorageForeignKeyViolationException when the parent reference is unset or
     *         the pair is already registered
     */
    void registerChildInsert(Comparable<?> childObjectId, Comparable<?> parentObjectId, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException;
}
