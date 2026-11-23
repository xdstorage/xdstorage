package io.github.xdstorage.structure.update;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.transaction.XdStorageTransaction;

/**
 * One structure-evolution step (a migration script) for a stored class: transforms the data
 * persisted under an older structure so it matches the class's current schema. Implementations
 * are registered with the structure manager before concurrent load and are executed by it
 * when the persisted structure of {@link #getDataClass()} is found changed; every execution
 * is journalled under {@link #getUpdateName()} so a script that already succeeded is skipped
 * on later structure checks.
 *
 * <p>Engine-internal — not part of the public API (applications plug their migrations in via
 * {@code XdStorage#registerStructureUpdater}); extend {@code XdStorageAbstractStructureUpdater}
 * instead of implementing this interface directly.</p>
 *
 * @param <T> the record type the updater handles (not used by this interface's own
 *            signatures; kept for the implementations built on the abstract base)
 */
public interface IXdStorageStructureUpdater<T> {

    /**
     * Returns the stored class this migration script targets.
     *
     * @return the stored class whose persisted data this updater migrates
     */
    Class<?> getDataClass();

    /**
     * Returns the journal name of this migration script.
     *
     * @return the unique name this update script is journalled under
     */
    String getUpdateName();

    /**
     * Executes the migration against the stored data of the target class.
     *
     * @param resourcesManager the engine resources manager to lock and read/write the
     *                         stored resources through
     * @param tx               the transaction the migration must run in
     * @return {@code SUCCESSFULLY} when the migration completed, {@code FAILURE} otherwise
     * @throws XdStorageException           when the migration fails with a storage error
     * @throws XdStorageConnectionException when the storage connection fails
     */
    XdStorageUpdateStructureResult execute(XdStorageAbstractResourcesManager resourcesManager, XdStorageTransaction tx)
            throws XdStorageException, XdStorageConnectionException;

    /**
     * Reports the execution outcome back to the caller (the structure manager).
     *
     * @param result  the execution result
     * @param message the human-readable result message
     * @param e       the failure cause, or {@code null} when the script did not fail
     */
    void setResult(XdStorageUpdateStructureResult result, String message, Throwable e);

    /**
     * Returns the reported outcome of the last execution.
     *
     * @return the result last passed to {@code setResult}, or {@code null} before the
     *         first execution
     */
    XdStorageUpdateStructureResult getResult();

    /**
     * Returns the reported message of the last execution.
     *
     * @return the message last passed to {@code setResult}, or {@code null} before the
     *         first execution
     */
    String getMessage();

    /**
     * Returns the reported error of the last failed execution.
     *
     * @return the error last passed to {@code setResult}, or {@code null} when the script
     *         has not failed
     */
    Throwable getError();
}
