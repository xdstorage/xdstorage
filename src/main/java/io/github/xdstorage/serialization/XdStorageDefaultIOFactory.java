package io.github.xdstorage.serialization;

import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.services.XdStorageServicesLocator;

/**
 * The engine's XML-format {@link IXdStorageIOFactory} ("point G"): builds the XML
 * {@link XdStorageDefaultObjectsReader}/{@link XdStorageDefaultObjectsWriter} pair.
 * Uses the ThreadLocal pattern to fully isolate the reader and writer used by each
 * worker thread, so their reflection/metadata caches are never shared and data races
 * on those caches are eliminated by construction.
 *
 * <p>Thread-safety: the factory itself is immutable and safe to share across threads;
 * each thread gets its own lazily created reader/writer instances via thread-locals.
 * Engine-internal — not part of the public API.</p>
 */
public class XdStorageDefaultIOFactory implements IXdStorageIOFactory {

    private final XdStorageServicesLocator services;
    private final IXdStorageSimpleTypeHelper simpleTypeHelper;
    private final IXdStorageIdGenerator idGenerator;

    // ПОТОКОВАЯ ИЗОЛЯЦИЯ: Каждый поток воркера СУБД получает свой собственный,
    // изолированный экземпляр ридера и райтера со своими локальными кэшами свойств!
    private final ThreadLocal<IXdStorageObjectsReader> threadLocalReader = new ThreadLocal<>();
    private final ThreadLocal<IXdStorageObjectsWriter> threadLocalWriter = new ThreadLocal<>();

    /**
     * Creates the XML I/O factory with the engine's default simple-type helper.
     *
     * @param service     the storage services locator (storage root, configuration)
     * @param idGenerator the engine id generator applied when marshalling id fields
     */
    public XdStorageDefaultIOFactory(final XdStorageServicesLocator service, final IXdStorageIdGenerator idGenerator) {
        this(service, new XdStorageDefaultSimpleTypeHelper(), idGenerator);
    }

    /**
     * Creates the XML I/O factory with an explicit simple-type helper.
     *
     * @param services         the storage services locator (storage root, configuration)
     * @param simpleTypeHelper the simple-type SPI used by the produced readers/writers
     * @param idGenerator      the engine id generator applied when marshalling id fields
     */
    public XdStorageDefaultIOFactory(final XdStorageServicesLocator services, final IXdStorageSimpleTypeHelper
            simpleTypeHelper,
                                     final IXdStorageIdGenerator idGenerator) {
        this.services = services;
        this.simpleTypeHelper = simpleTypeHelper;
        this.idGenerator = idGenerator;
    }

    @Override
    public IXdStorageObjectsReader newInstanceReader() {
        // Ленивая инициализация ридера строго для текущего потока выполнения
        IXdStorageObjectsReader reader = threadLocalReader.get();
        if (reader == null) {
            reader = new XdStorageDefaultObjectsReader(simpleTypeHelper);
            threadLocalReader.set(reader);
        }
        return reader;
    }

    @Override
    public IXdStorageObjectsWriter newInstanceWriter() {
        // Ленивая инициализация райтера строго для текущего потока выполнения
        IXdStorageObjectsWriter writer = threadLocalWriter.get();
        if (writer == null) {
            writer = new XdStorageDefaultObjectsWriter(services, simpleTypeHelper, idGenerator);
            threadLocalWriter.set(writer);
        }
        return writer;
    }
}
