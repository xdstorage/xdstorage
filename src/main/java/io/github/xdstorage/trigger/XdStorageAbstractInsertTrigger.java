package io.github.xdstorage.trigger;

import io.github.xdstorage.transaction.IXdStorageTransaction;

/**
 * Base class for insert triggers: implements the two-object {@code IXdStorageTrigger} contract
 * and hands subclasses only the inserted object plus the running transaction. Register a
 * subclass with {@code IXdStorage.registerTrigger(...)}; it fires inside the changing
 * transaction, and a failure rolls that transaction back.
 *
 * @param <T> the entity class the trigger observes
 */
public abstract class XdStorageAbstractInsertTrigger<T> implements IXdStorageTrigger<T> {

    /**
     * Default constructor for subclasses.
     */
    public XdStorageAbstractInsertTrigger() {
    }

    @Override
    public void perform(final T oldObject, final T newObject, final IXdStorageTransaction transaction) {
        perform(newObject, transaction);
    }

    /**
     * Trigger callback, invoked when an object of the observed class is inserted.
     *
     * @param object      the inserted object
     * @param transaction the transaction performing the insert (touching the storage from here
     *                    requires opening and finishing a nested transaction)
     */
    protected abstract void perform(T object, IXdStorageTransaction transaction);
}
