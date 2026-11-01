package io.github.xdstorage.structure.update;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.index.hash.XdStorageHashIndexResource;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.XdStorageResource;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for structure updaters that rewrite the stored records of a class one by one.
 * {@code execute} walks the class's stored records according to its storage policy — through
 * the id hash index for {@code StoreAsClassObjects}, through the references resource and the
 * per-object resources for {@code StoreAsSingleObject} — applies {@code transform} to each
 * record's old state and writes the migrated state back inside the migration transaction.
 * The embedded policy ({@code StoreWithParentObject}) has no own records and fails the
 * migration. The outcome (result, message, error) is kept in fields readable through the
 * {@code IXdStorageStructureUpdater} getters.
 *
 * <p>Instances are executed under the structure manager's per-class lock and are not safe
 * for concurrent executions on their own. Engine-internal — not part of the public API.</p>
 *
 * @param <T> the migrated record type produced by {@code transform}
 */
public abstract class XdStorageAbstractStructureUpdater<T> implements IXdStorageStructureUpdater<T> {

    private final String updateName;

    private XdStorageUpdateStructureResult result;

    private String message;

    private Throwable e;

    /**
     * Creates an updater journalled under the given script name.
     *
     * @param updateName the unique name this update script is journalled under
     */
    protected XdStorageAbstractStructureUpdater(final String updateName) {
        this.updateName = updateName;
    }

    /**
     * {@inheritDoc}
     *
     * @return the unique name of this update script
     */
    @Override
    public String getUpdateName() {
        return updateName;
    }

    /**
     * {@inheritDoc}
     *
     * @param result  the execution result
     * @param message the human-readable result message
     * @param e       the failure cause, or {@code null} when the script did not fail
     */
    @Override
    public void setResult(final XdStorageUpdateStructureResult result, final String message, final Throwable e) {
        this.result = result;
        this.message = message;
        this.e = e;
    }

    /**
     * {@inheritDoc}
     *
     * @return the result of the last execution, or {@code null} before the first one
     */
    @Override
    public XdStorageUpdateStructureResult getResult() {
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * @return the message of the last execution, or {@code null} before the first one
     */
    @Override
    public String getMessage() {
        return message;
    }

    /**
     * {@inheritDoc}
     *
     * @return the error of the last failed execution, or {@code null} when it did not fail
     */
    @Override
    public Throwable getError() {
        return e;
    }

    /**
     * Migrates every stored record of the target class: reads the records per the class
     * storage policy, transforms each old state with {@code transform} and updates the
     * record in place, all within the given transaction. Stops at the first failing record
     * and reports the failure through {@code setResult}.
     *
     * @param resourcesManager the engine resources manager to lock and read/write the
     *                         stored resources through
     * @param transaction      the transaction the migration runs in
     * @return {@code SUCCESSFULLY} when every stored record was migrated, {@code FAILURE}
     *         when the storage policy is unsupported or a record migration failed
     * @throws XdStorageException           when the migration fails with a storage error
     * @throws XdStorageConnectionException when the storage connection fails
     */
    @Override
    public XdStorageUpdateStructureResult execute(final XdStorageAbstractResourcesManager resourcesManager,
                                                  final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        final Class<?> cl = getDataClass();
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStoragePolicy policy = clInfo.getPolicy();

        final AtomicBoolean success = new AtomicBoolean(true);
        if (policy == XdStoragePolicy.StoreAsClassObjects) {
            final Set<String> handledResources = new HashSet<>();

            final IXdStorageIndexDaoResource resource = resourcesManager.lockIndexResource(clInfo, transaction);
            if (resource instanceof XdStorageHashIndexResource) {
                ((XdStorageHashIndexResource) resource).readAsData(transaction).forEach(object -> {
                    final String resourceId = (String) object.getProperty("resourceId");

                    if (!success.get() || handledResources.contains(resourceId)) {
                        return;
                    }

                    handledResources.add(resourceId);

                    final IXdStorageDaoResource objectsResource;
                    try {
                        objectsResource = resourcesManager.lockResource(resourceId, clInfo, transaction);
                        if (objectsResource instanceof XdStorageResource) {
                            ((XdStorageResource) objectsResource).readAsData(transaction).stream().forEach(data -> {
                                if (!success.get()) {
                                    return;
                                }

                                final T convertedObject = transform(data);
                                try {
                                    objectsResource.update(convertedObject, transaction);
                                } catch (final XdStorageException | XdStorageConnectionException e1) {
                                    success.set(false);
                                    e = e1;
                                }
                            });
                        }
                    } catch (final XdStorageException | XdStorageConnectionException e1) {
                        e = e1;
                        success.set(false);
                    }
                });
            }
        } else if (policy == XdStoragePolicy.StoreAsSingleObject) {
            final IXdStorageDaoResource referencesResource = resourcesManager.lockReferencesResource(clInfo, transaction);
            referencesResource.readAsData(transaction).stream().forEach(referenceData -> {
                if (!success.get()) {
                    return;
                }

                final Object id = referenceData.getId();

                try {
                    final IXdStorageDaoResource objectResource = resourcesManager.lockObjectResource(clInfo, id, transaction);
                    objectResource.readAsData(transaction).stream().forEach(data -> {
                        final T convertedObject = transform(data);
                        try {
                            objectResource.update(convertedObject, transaction);
                        } catch (final XdStorageException | XdStorageConnectionException e1) {
                            success.set(false);
                            e = e1;
                        }
                    });
                } catch (final XdStorageException | XdStorageConnectionException e1) {
                    success.set(false);
                    e = e1;
                }
            });
        } else {
            success.set(false);
            message = "Cannot update objects of " + cl + ". Storage policy cannot be " + XdStoragePolicy.StoreWithParentObject + ".";
        }

        return success.get() ? XdStorageUpdateStructureResult.SUCCESSFULLY : XdStorageUpdateStructureResult.FAILURE;
    }

    /**
     * Transforms the old persisted state of one stored record into its migrated state.
     *
     * <p>STRUCTFIX (docs/tasks/2.1.0/36): deliberately NOT abstract. Updaters that do not
     * use the record-by-record rewrite machinery (their {@code execute} is overridden —
     * e.g. {@code XdStorageSearchIndexStructureUpdater} re-indexes through watchers) no
     * longer carry a dead {@code transform} override returning {@code null}. A
     * record-rewriting subclass that forgets to override this method fails LOUDLY at
     * migration time instead of silently writing {@code null} records.
     *
     * @param oldStateOfObject the record as currently stored
     * @return the migrated state to write back for the record
     */
    protected T transform(final XdStorageIdentifiableObject oldStateOfObject) {
        throw new UnsupportedOperationException(
                getClass().getName() + " uses the record-by-record migration machinery but does not override transform()");
    }
}
