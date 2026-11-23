package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.serialization.*;
import io.github.xdstorage.services.XdStorageServicesLocator;

/**
 * The engine's YAML-format {@link IXdStorageIOFactory} (the storage default): builds
 * {@link XdStorageYamlObjectsReader}/{@link XdStorageYamlObjectsWriter} and exposes the
 * codec pieces the commit journal reuses. Uses the ThreadLocal pattern to fully isolate
 * the reader and writer used by each worker thread, so their reflection/metadata caches
 * are never shared and data races on those caches are eliminated by construction.
 *
 * <p>Thread-safety: the factory itself is immutable and safe to share across threads;
 * each thread gets its own lazily created reader/writer instances via thread-locals.
 * Engine-internal — not part of the public API.</p>
 */
public class XdStorageYamlIOFactory implements IXdStorageIOFactory {

    private final XdStorageServicesLocator services;
    private final IXdStorageSimpleTypeHelper simpleTypeHelper;
    private final IXdStorageIdGenerator idGenerator;

    // ПОТОКОВАЯ ИЗОЛЯЦИЯ: Каждый поток воркера СУБД получает свой собственный,
    // изолированный экземпляр ридера и райтера со своими локальными кэшами свойств!
    private final ThreadLocal<IXdStorageObjectsReader> threadLocalReader = new ThreadLocal<>();
    private final ThreadLocal<IXdStorageObjectsWriter> threadLocalWriter = new ThreadLocal<>();

    /**
     * Creates the YAML I/O factory with the engine's default simple-type helper.
     *
     * @param service     the storage services locator (configuration, storage root)
     * @param idGenerator the engine id generator applied when marshalling id fields
     */
    public XdStorageYamlIOFactory(final XdStorageServicesLocator service, final IXdStorageIdGenerator idGenerator) {
        this(service, new XdStorageDefaultSimpleTypeHelper(), idGenerator);
    }

    /**
     * Creates the YAML I/O factory with an explicit simple-type helper.
     *
     * @param services         the storage services locator (configuration, storage root)
     * @param simpleTypeHelper the simple-type SPI used by the produced readers/writers
     * @param idGenerator      the engine id generator applied when marshalling id fields
     */
    public XdStorageYamlIOFactory(final XdStorageServicesLocator services, final IXdStorageSimpleTypeHelper
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
            // S-1: движок по умолчанию строг (обрезанный файл = отказ загрузки);
            // XdStorageConfig.acceptMissingEofSentinel включает легаси-режим для миграции
            final boolean strict = services.getStorageConfig() == null
                    || !services.getStorageConfig().isAcceptMissingEofSentinel();
            reader = new XdStorageYamlObjectsReader(simpleTypeHelper, strict);
            threadLocalReader.set(reader);
        }
        return reader;
    }

    @Override
    public IXdStorageObjectsWriter newInstanceWriter() {
        // Ленивая инициализация райтера строго для текущего потока выполнения
        IXdStorageObjectsWriter writer = threadLocalWriter.get();
        if (writer == null) {
            writer = new XdStorageYamlObjectsWriter(services, simpleTypeHelper, idGenerator);
            threadLocalWriter.set(writer);
        }
        return writer;
    }

    /** W-1: доступ кодека журнала коммитов к простым типам (пакетный, внутренний). */
    IXdStorageSimpleTypeHelper simpleTypeHelper() {
        return simpleTypeHelper;
    }

    /** W-1: генератор id для writeObjectData при записи тела объекта в журнал. */
    IXdStorageIdGenerator idGenerator() {
        return idGenerator;
    }
}
