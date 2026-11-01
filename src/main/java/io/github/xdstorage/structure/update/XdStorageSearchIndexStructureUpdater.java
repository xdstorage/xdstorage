package io.github.xdstorage.structure.update;

import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.search.IXdStorageSearchManager;
import io.github.xdstorage.search.XdStorageSearchIndex;
import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Date;

/**
 * The structure updater the structure manager schedules when a search index definition of a
 * class changed: {@code execute} attaches a watcher to the class's stored resources (the id
 * hash index resource for {@code StoreAsClassObjects}, the references resource and the
 * per-object resources for {@code StoreAsSingleObject}) so every stored record is re-indexed
 * through the search manager inside the migration transaction. The update name embeds a
 * creation timestamp, so each run is journalled as its own script.
 *
 * <p>Created and driven solely by the structure manager. Engine-internal — not part of the
 * public API.</p>
 */
public class XdStorageSearchIndexStructureUpdater extends XdStorageAbstractStructureUpdater<XdStorageSearchIndexRecord> {

    private final IXdStorageSearchManager searchManager;

    private final Class<?> cl;

    private final XdStorageSearchIndex index;

    /**
     * Creates the re-indexing updater for one changed search index.
     *
     * @param searchManager the search manager the stored records are re-indexed through
     * @param cl            the stored class whose index definition changed
     * @param index         the changed search index definition
     */
    public XdStorageSearchIndexStructureUpdater(final IXdStorageSearchManager searchManager, final Class<?> cl, final XdStorageSearchIndex index) {
        super(cl.getSimpleName() + "-" + index.getName() + "-" + new Date().getTime());

        this.searchManager = searchManager;

        this.cl = cl;
        this.index = index;
    }

    /**
     * {@inheritDoc}
     *
     * @return the class whose search index is refreshed by this updater
     */
    @Override
    public Class<?> getDataClass() {
        return cl;
    }

    /**
     * Refreshes the search index of the target class: registers a watcher on the class's
     * stored resources so each stored record is re-indexed via the search manager within
     * the given transaction. Never reports {@code FAILURE} — it either succeeds or throws.
     *
     * @param resourcesManager the engine resources manager to lock the stored resources of
     *                         the class through
     * @param transaction      the transaction the re-indexing runs in
     * @return {@code SUCCESSFULLY} when the re-indexing watchers were registered
     * @throws XdStorageConnectionException when the storage connection fails
     * @throws XdStorageRuntimeException    (unchecked) when the index cannot be refreshed,
     *                                      including any storage error wrapped from below
     */
    @Override
    public XdStorageUpdateStructureResult execute(final XdStorageAbstractResourcesManager resourcesManager, final XdStorageTransaction transaction) throws XdStorageConnectionException {
        try {
            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStoragePolicy policy = clInfo.getPolicy();
            final XdStorageObjectIdField idField = clInfo.getIdField();

            final IXdStorageWatcher watcher = new IXdStorageWatcher() {
                @Override
                public void watch(final Object object) {
                    try {
                        searchManager.update(object, transaction);
                    } catch (final XdStorageException | XdStorageConnectionException e) {
                        throw new XdStorageRuntimeException("cannot update search index record for object " + idField.get(object) + " of " + cl, e);
                    }
                }
            };

            if (policy == XdStoragePolicy.StoreAsClassObjects) {
                final IXdStorageIndexDaoResource indexResource = resourcesManager.lockIndexResource(clInfo, transaction);
                indexResource.watch(transaction, watcher);
            } else if (policy == XdStoragePolicy.StoreAsSingleObject) {

                final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo, transaction);
                referencesResource.watch(transaction, new IXdStorageWatcher<Object>() {
                    @Override
                    public void watch(final Object object) {
                        try {
                            final IXdStorageDaoResource objectResource = resourcesManager.lockObjectResource(object, transaction);
                            objectResource.watch(transaction, watcher);
                        } catch (final XdStorageException | XdStorageConnectionException e) {
                            throw new XdStorageRuntimeException(e);
                        }
                    }
                });
            } else {
                throw new XdStorageException("cannot update index for " + cl);
            }
        } catch (final XdStorageException e) {
            throw new XdStorageRuntimeException("execute update exception", e);
        }
        return XdStorageUpdateStructureResult.SUCCESSFULLY;
    }
}
