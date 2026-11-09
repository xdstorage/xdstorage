package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;

public interface IXdStorageForeignKeyRegistry {

    XdStorageClassInfo getParentClassInfo();

    XdStorageClassInfo getChildClassInfo();

    XdStorageObjectField getParentField();

    void registerForeignKey(Comparable<?> parentObjectId, Comparable<?> childObjectId, IXdStorageTransaction transaction) throws XdStorageException;
}
