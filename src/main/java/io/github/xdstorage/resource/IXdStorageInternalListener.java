package io.github.xdstorage.resource;

import io.github.xdstorage.transaction.IXdStorageTransaction;

public interface IXdStorageInternalListener {

    void insertRegistered(Object newObject, IXdStorageTransaction transaction);

    void updateRegistered(Object oldObject, Object newObject, IXdStorageTransaction transaction);

    void deleteRegistered(Object object, IXdStorageTransaction transaction);
}
