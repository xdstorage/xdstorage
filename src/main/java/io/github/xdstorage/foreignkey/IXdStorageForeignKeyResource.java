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

    void update(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    void delete(Object parentObject, Comparable<?> parentObjectId, Object childObject, Comparable<?> childObjectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;
}
