package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;

/**
 * The object-graph cloning SPI of the MVCC snapshot machinery: materializes
 * transaction-visible snapshots by cloning engine records into user-facing POJO graphs and,
 * in the other direction, wraps user graphs into canonical engine reference objects.
 * Implementations delegate the per-level field and collection/array copying to the {@code
 * XdStorageClonerFieldPacker}/{@code XdStorageClonerFieldUnpacker} and the collection/array
 * processors, and memoize per-object references through {@link IXdStorageReferenceProvider}.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public interface IXdStorageCloner {

    /**
     * Clones the given object for a transaction snapshot, replacing nested entity objects
     * with the transaction's registered reference wrappers; {@code null} passes through.
     *
     * @param toClone     the object to clone and wrap
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the cloned graph belongs to
     * @return the wrapped clone, or {@code null} when {@code toClone} is {@code null}
     * @throws XdStorageException when the object cannot be cloned or wrapped
     */
    Object cloneAndWrap(Object toClone, IXdStorage storage, IXdStorageTransaction transaction) throws
            XdStorageException;

    /**
     * Reverse direction: unwraps a (possibly wrapped) object and clones it into a plain
     * POJO graph for delivery to the application.
     *
     * @param toCloneWrapped the object to unwrap and clone
     * @return the plain clone, or {@code null} when the input is {@code null}
     * @throws XdStorageException when the object cannot be unwrapped or cloned
     */
    Object unwrapAndClone(Object toCloneWrapped) throws XdStorageException;

    /**
     * Populates an existing reference object in place with the fields of {@code object}
     * (packing one reference level, keeping nested entity values as references).
     *
     * @param reference   the reference object to populate (never {@code null})
     * @param object      the source object providing the field values (never {@code null})
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the reference belongs to
     * @throws XdStorageException when either argument is {@code null} or the packing fails
     */
    void fillAndWrap(Object reference, Object object, IXdStorage storage, XdStorageTransaction transaction) throws
            XdStorageException;

    /**
     * Releases the transaction's reference state (the memoized per-object wrappers).
     *
     * @param transaction the transaction being released
     */
    void release(XdStorageTransaction transaction);
}
