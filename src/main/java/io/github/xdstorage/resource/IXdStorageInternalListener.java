package io.github.xdstorage.resource;

import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.transaction.IXdStorageTransaction;

public interface IXdStorageInternalListener {

    void notify(XdStorageCacheCommitPhase phase, XdStorageObjectOperationType change,
                Object oldObject, Object newObject, IXdStorageTransaction transaction);
}
