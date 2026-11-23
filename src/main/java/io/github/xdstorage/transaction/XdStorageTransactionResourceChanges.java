package io.github.xdstorage.transaction;

import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.trigger.XdStorageTriggerManager;

import java.util.ArrayList;
import java.util.Collection;

/**
 * The change set one transaction produced on one resource: an ordered list of
 * {@code XdStorageObjectChange} records (operation type, object id, old and new state), filled
 * by the first commit phase. After the commit finishes it replays the changes through the
 * trigger manager (firing insert/update/delete triggers); on a failed commit the records drive
 * the rollback of the phase-1 writes.
 *
 * <p>Engine-internal — not part of the public API.
 */
public class XdStorageTransactionResourceChanges {

    private final IXdStorageResourceObject resource;

    private final Collection<XdStorageObjectChange> changes;

    /**
     * Creates an empty change set for the resource.
     *
     * @param resource the resource whose changes are collected here
     */
    public XdStorageTransactionResourceChanges(final IXdStorageResourceObject resource) {
        this.resource = resource;
        this.changes = new ArrayList<>();
    }

    /**
     * The resource whose changes are collected.
     *
     * @return the resource
     */
    public IXdStorageResourceObject getResource() {
        return resource;
    }

    /**
     * Appends one change record to the set.
     *
     * @param type      the operation performed on the object
     * @param id        the object id the operation targeted
     * @param oldObject the previous state of the object ({@code null} for an insert)
     * @param newObject the new state of the object ({@code null} for a delete)
     */
    public void addChangeObject(final XdStorageObjectOperationType type, final Object id, final Object oldObject, final
            Object newObject) {
        changes.add(new XdStorageObjectChange(type, id, oldObject, newObject));
    }

    /**
     * Replays the collected changes through the trigger manager: fires the registered
     * insert/update/delete triggers of each affected entity class inside the given transaction.
     *
     * @param transaction    the committed transaction, handed to every trigger
     * @param triggerManager the storage's trigger manager
     */
    public void performTriggers(final XdStorageTransaction transaction, final XdStorageTriggerManager triggerManager) {
        triggerManager.performTriggers(transaction, changes);
    }

    /**
     * The collected change records.
     *
     * @return the live change list (records are appended as the commit captures them)
     */
    public Collection<XdStorageObjectChange> getChangesObjects() {
        return changes;
    }
}
