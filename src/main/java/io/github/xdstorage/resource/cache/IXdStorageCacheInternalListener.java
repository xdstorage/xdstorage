package io.github.xdstorage.resource.cache;

import io.github.xdstorage.resource.IXdStorageInternalListener;

/**
 * The cache-scoped internal listener: receives the resource cache's commit-phase
 * notifications (PREPARE_COMMIT/PERFORM_COMMIT per changed record) inside the
 * resource's own commit pipeline.
 */
public interface IXdStorageCacheInternalListener extends IXdStorageInternalListener {

}
