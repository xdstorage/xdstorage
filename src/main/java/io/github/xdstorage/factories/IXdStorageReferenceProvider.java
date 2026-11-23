package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.code.IXdStorageSimpleWrapper;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

/**
 * The per-transaction reference registry SPI: hands out and memoizes the canonical
 * reference wrapper ({@code IXdStorageSimpleWrapper}) for one (class, id) pair within a
 * transaction, so every materialization of the same object inside the transaction shares
 * one wrapper instance instead of duplicating MVCC versions. Implementations must be safe
 * for concurrent use by parallel snapshot materializations.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public interface IXdStorageReferenceProvider {

    /**
     * Returns the reference wrapper memoized for the (class, id) pair in this transaction.
     *
     * @param cl          the entity class of the object
     * @param objectId    the object's id
     * @param transaction the transaction scoping the lookup
     * @return the memoized wrapper, or {@code null} when none was registered yet
     */
    IXdStorageSimpleWrapper getReference(Class<?> cl, Object objectId, IXdStorageTransaction transaction);

    /**
     * Creates a fresh reference wrapper for the (class, id) pair, sets its id field and
     * memoizes it in this transaction — or returns the already-registered wrapper when one
     * exists (creating at most one per pair, atomically under concurrency).
     *
     * @param cl          the entity class of the object
     * @param idField     the id-field accessor used to stamp the id into the new wrapper
     * @param objectId    the object's id
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction scoping the registration
     * @return the canonical wrapper for the pair (never {@code null})
     * @throws XdStorageException when the wrapper cannot be instantiated, its generation
     *         degrades to a dummy, or registration fails
     */
    IXdStorageSimpleWrapper createAndRegisterReference(Class<?> cl, XdStorageObjectIdField idField, Object objectId,
            IXdStorage storage, IXdStorageTransaction transaction) throws XdStorageException;

    /**
     * Discards the transaction's whole reference registry.
     *
     * @param transaction the transaction being released
     */
    void release(XdStorageTransaction transaction);
}
