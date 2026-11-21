package io.github.xdstorage.performance;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.io.Reader;
import java.io.Writer;
import java.util.Collection;

/**
 * BINFEAS experiment (task 33 follow-up): the binary-format factory. Deliberately
 * EXTENDS {@link XdStorageYamlIOFactory}: the engine's journal-replay path type-checks
 * {@code factory instanceof XdStorageYamlIOFactory} — an interface-level decorator would
 * null the journal machinery (the NPE the probe hit on day one). The journals stay YAML
 * (they are written by the journal's own serializer anyway); only the BASE files
 * (fragments, the id-index) go through this factory's compact codec.
 */
public class BinFeasBinaryIOFactory extends XdStorageYamlIOFactory {

    public BinFeasBinaryIOFactory(final io.github.xdstorage.services.XdStorageServicesLocator services,
                                  final io.github.xdstorage.idgeneration.IXdStorageIdGenerator idGenerator) {
        super(services, idGenerator);
    }

    @Override
    public IXdStorageObjectsReader newInstanceReader() {
        return new BinaryReader();
    }

    @Override
    public IXdStorageObjectsWriter newInstanceWriter() {
        return new BinaryWriter();
    }

    private static final class BinaryReader implements IXdStorageObjectsReader {

        @Override
        public Collection<Object> readReferences(final Reader reader, final XdStorageObjectIdField field)
                throws XdStorageIOException, XdStorageException {
            try {
                return BinFeasBinaryCodec.readReferenceStubs(reader, field);
            } catch (final java.io.IOException e) {
                throw new XdStorageIOException("BINFEAS codec read failed", e);
            }
        }

        @Override
        public Collection<Object> read(final Reader reader)
                throws XdStorageIOException, XdStorageException {
            try {
                return BinFeasBinaryCodec.readInstances(reader, null);
            } catch (final java.io.IOException e) {
                throw new XdStorageIOException("BINFEAS codec read failed", e);
            }
        }

        @Override
        public Collection<Object> read(final Reader reader, final IXdStorageTransaction transaction)
                throws XdStorageIOException {
            try {
                return BinFeasBinaryCodec.readInstances(reader, transaction);
            } catch (final java.io.IOException e) {
                throw new XdStorageIOException("BINFEAS codec read failed", e);
            } catch (final XdStorageException e) {
                throw new XdStorageIOException(e.getMessage(), e);
            }
        }

        @Override
        public Collection<XdStorageIdentifiableObject> readData(final Reader xmlReader, final XdStorageObjectIdField field)
                throws XdStorageIOException, XdStorageException {
            try {
                return BinFeasBinaryCodec.readAsRecords(xmlReader, field);
            } catch (final java.io.IOException e) {
                throw new XdStorageIOException("BINFEAS codec read failed", e);
            }
        }
    }

    private static final class BinaryWriter implements IXdStorageObjectsWriter {

        @Override
        public void writeReferences(final Writer writer, final XdStorageObjectIdField field,
                                    final Collection<Object> references) throws XdStorageIOException {
            writeAll(writer, references);
        }

        @Override
        public void writeObjects(final Writer writer, final Collection<Object> objects)
                throws XdStorageIOException {
            writeAll(writer, objects);
        }

        private static void writeAll(final Writer writer, final Collection<Object> objects)
                throws XdStorageIOException {
            try {
                BinFeasBinaryCodec.writeBlock(writer, objects);
            } catch (final java.io.IOException e) {
                throw new XdStorageIOException("BINFEAS codec write failed", e);
            }
        }
    }
}
