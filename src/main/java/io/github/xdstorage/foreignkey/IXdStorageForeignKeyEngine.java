package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;

public interface IXdStorageForeignKeyEngine {

    void process(Object object, XdStorageForeignKeyObjectChangeType type, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

}
