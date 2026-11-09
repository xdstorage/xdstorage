package io.github.xdstorage.resource;

import io.github.xdstorage.transaction.IXdStorageTransaction;

public interface IXdStorageListenableResource {

    void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction);
}
