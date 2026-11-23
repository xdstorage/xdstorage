package io.github.xdstorage.serialization;

import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.io.Writer;
import java.util.Collection;

/**
 * Marshalling half of the serialization SPI: writes objects and references into the
 * character stream of one resource file, in the format paired with this writer by its
 * {@link IXdStorageIOFactory}. Writers are consumed by the resource flush path and by
 * full-file write-outs; the reader of the same format must be able to restore exactly
 * what they produce.
 *
 * <p>Instances are expected to be confined to one worker thread (the factories keep
 * them in thread-locals) and carry per-instance reflection caches. Engine-internal —
 * not part of the public API.</p>
 */
public interface IXdStorageObjectsWriter {

    /**
     * Writes a references resource: one entry per given object carrying its class and id
     * (the referenced body lives in its own resource), wrapped in the resource root
     * element for this format.
     *
     * @param writer     the character stream of the references resource
     * @param field      the id field accessor used to read each object's id
     * @param references the referenced objects to persist as id-only entries
     * @throws XdStorageIOException when writing fails or an object cannot be marshalled
     */
    void writeReferences(Writer writer, XdStorageObjectIdField field, Collection<Object> references) throws
            XdStorageIOException;

    /**
     * Writes a full-body resource: each given object is marshalled with every persisted
     * field (nested objects, collections, maps, arrays and references included), wrapped
     * in the resource root element for this format.
     *
     * @param writer  the character stream of the resource
     * @param objects the objects to persist with full bodies
     * @throws XdStorageIOException when writing fails or an object cannot be marshalled
     */
    void writeObjects(Writer writer, Collection<Object> objects) throws XdStorageIOException;
}
