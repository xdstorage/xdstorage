package io.github.xdstorage.serialization;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.io.Reader;
import java.util.Collection;

/**
 * Demarshalling half of the serialization SPI: restores objects, plain references and
 * typed data records from a character stream produced by the matching
 * {@link IXdStorageObjectsWriter}. Implementations exist per {@link FilesFormat} (XML
 * and YAML in this engine) and are handed out by an {@link IXdStorageIOFactory}.
 *
 * <p>Instances are expected to be confined to one worker thread (the factories keep
 * them in thread-locals) and carry per-instance reflection caches. Engine-internal —
 * not part of the public API.</p>
 */
public interface IXdStorageObjectsReader {

    /**
     * Reads a references resource: for each stored reference entry restores an instance
     * of the reference's class with only its id field populated (the referenced body
     * lives in its own resource).
     *
     * @param reader the character stream of the references resource
     * @param field  the id field accessor used to populate the restored instances
     * @return the restored id-carrying stub objects, in file order
     * @throws XdStorageIOException when the stream cannot be parsed or a value cannot be
     *         materialized
     * @throws XdStorageException when the resource violates engine-level contracts
     */
    Collection<Object> readReferences(Reader reader, XdStorageObjectIdField field) throws XdStorageIOException,
            XdStorageException;

    /**
     * Reads a full resource: restores each stored object as a live instance of its class,
     * with every persisted field populated (nested objects, collections, maps, arrays and
     * references included).
     *
     * @param reader the character stream of the resource
     * @return the restored objects, in file order
     * @throws XdStorageIOException when the stream cannot be parsed or a value cannot be
     *         materialized
     * @throws XdStorageException when the resource violates engine-level contracts
     */
    Collection<Object> read(Reader reader) throws XdStorageIOException, XdStorageException;

    // ДОБАВЛЯЕМ СИГНАТУРУ С ПОДДЕРЖКОЙ ТРАНЗАКЦИИ ДЛЯ ИДЕНТИЧНОСТИ КЭША
    /**
     * Transaction-carrying variant of {@link #read(Reader)}: the signature exists so
     * identity-sensitive caches can keep one instance per id within a transaction. The
     * parameter names the owning transaction; current implementations parse the stream
     * exactly like {@link #read(Reader)} and do not act on it.
     *
     * @param reader      the character stream of the resource
     * @param transaction the owning transaction, carried for cache identity
     * @return the restored objects, in file order
     * @throws XdStorageIOException when the stream cannot be parsed or a value cannot be
     *         materialized
     */
    Collection<Object> read(Reader reader, IXdStorageTransaction transaction) throws XdStorageIOException;

    /**
     * Reads a data resource into the engine's storage-neutral record form: each entry
     * becomes an {@link XdStorageIdentifiableObject} carrying the entity class, the id
     * and the raw field values, without instantiating the entity class itself.
     *
     * @param xmlReader the character stream of the data resource
     * @param field     the id field accessor of the entity class the resource belongs to
     * @return the restored typed records, in file order
     * @throws XdStorageIOException when the stream cannot be parsed or a value cannot be
     *         materialized
     * @throws XdStorageException when the resource violates engine-level contracts
     */
    Collection<XdStorageIdentifiableObject> readData(Reader xmlReader, XdStorageObjectIdField field) throws
            XdStorageIOException, XdStorageException;
}
