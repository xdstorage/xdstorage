package io.github.xdstorage.resource;

import io.github.xdstorage.index.IXdStorageIndexResourceObject;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.index.btree.XdStorageBTreeIndexResource;
import io.github.xdstorage.index.hash.XdStorageHashIndexResource;
import io.github.xdstorage.search.IXdStorageSearchIndexResourceObject;
import io.github.xdstorage.search.XdStorageSearchIndexResource;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.utils.XdStorageClassInfo;

/**
 * The concrete resources manager of a file-backed storage: creates plain data and references
 * resources, and hash or B-tree id-index resources depending on the entity's declared index
 * type. New {@code StoreAsClassObjects} fragments are capped at the configured fragment size
 * (see {@link #reserveFreeResourceId}). Thread-safety as of the abstract base: all state is kept
 * in concurrent maps.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public class XdStorageResourcesManager
        extends XdStorageAbstractResourcesManager {

    /** Maximum number of objects per {@code StoreAsClassObjects} fragment file. */
    protected final int fragmentSize;

    /**
     * Builds the manager.
     *
     * @param services     engine services shared with collaborators
     * @param fragmentSize the maximum number of objects per class-shared fragment file
     */
    public XdStorageResourcesManager(final XdStorageServicesLocator services, final int fragmentSize) {
        super(services);

        this.fragmentSize = fragmentSize;
    }

    @Override
    protected IXdStorageIndexResourceObject createIndexResource(final Object resourceId, final String indexName,
                                                                final XdStorageClassInfo clInfo) {
        if (clInfo.getIndexType() == XdStorageIndexType.Hash) {
            return new XdStorageHashIndexResource(services, this, resourceId, indexName, clInfo, services.getIoFactory(), services.getIdGenerator(), fragmentSize);
        } else {
            return new XdStorageBTreeIndexResource(services, this, resourceId, indexName, clInfo, fragmentSize);
        }
    }

    @Override
    protected IXdStorageResourceObject<IXdStorageDaoResource> createResource(final Object resourceId,
                                                                             final XdStorageClassInfo clInfo) {
        return new XdStorageResource(services, this, resourceId, clInfo, services.getIoFactory());
    }

    @Override
    protected IXdStorageResourceObject<IXdStorageDaoResource> createReferencesResource(final Object resourceId,
                                                                                       final XdStorageClassInfo clInfo) {
        return new XdStorageResource(services, this, resourceId, clInfo, services.getIdGenerator(), true, services.getIoFactory());
    }

    @Override
    protected IXdStorageSearchIndexResourceObject createSearchIndexResource(final Object resourceId, final String indexName,
                                                                            final XdStorageClassInfo clInfo) {
        return new XdStorageSearchIndexResource(services, this, indexName, resourceId, clInfo, services.getIoFactory());
    }

}
