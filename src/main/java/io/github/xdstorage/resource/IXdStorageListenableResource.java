package io.github.xdstorage.resource;

import io.github.xdstorage.transaction.IXdStorageTransaction;

/**
 * A resource that accepts internal commit-phase listeners: registered listeners receive
 * the resource cache's PREPARE_COMMIT/PERFORM_COMMIT notifications for the changes of
 * the transaction they were registered for.
 */
public interface IXdStorageListenableResource {

    /**
     * Registers an internal listener scoped to the given transaction: it is notified at
     * the resource cache's commit phases for the records that transaction changes.
     *
     * @param listener    the internal listener to notify
     * @param transaction the transaction whose commit phases the listener observes
     */
    void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction);
}
