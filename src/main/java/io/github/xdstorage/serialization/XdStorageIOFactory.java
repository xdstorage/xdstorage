package io.github.xdstorage.serialization;

import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory;
import io.github.xdstorage.services.XdStorageServicesLocator;

/**
 * Static entry point that resolves the {@link IXdStorageIOFactory} for a storage's
 * {@link FilesFormat}: {@code XML} selects the XML implementation, anything else
 * (including the default {@code YAML}) selects the YAML implementation.
 *
 * <p>Thread-safety: stateless — the returned factory is safe to share across threads.
 * Engine-internal — not part of the public API.</p>
 */
public class XdStorageIOFactory {
    private XdStorageIOFactory() {

    }

    /**
     * Builds the I/O factory for the requested on-disk format.
     *
     * @param serviceLocator the storage services locator passed through to the factory
     * @param idGenerator    the engine id generator passed through to the factory
     * @param format         the requested storage format (must not be {@code null});
     *                       any value other than {@code XML} resolves to YAML
     * @return a fresh {@link IXdStorageIOFactory} for {@code format}
     */
    public static IXdStorageIOFactory instanceIOFactory(XdStorageServicesLocator serviceLocator, IXdStorageIdGenerator idGenerator, FilesFormat format) {
        // YAMLREAD: a null format NPEd at the switch — refuse with an actionable message.
        if (format == null) {
            throw new io.github.xdstorage.exceptions.XdStorageRuntimeException(
                    "format must be a FilesFormat value (XML or YAML), got null");
        }
        IXdStorageIOFactory ioFactory;
        switch (format) {
            case XML:
                ioFactory = new XdStorageDefaultIOFactory(serviceLocator, idGenerator);
                break;
            case YAML:
            default:
                ioFactory = new XdStorageYamlIOFactory(serviceLocator, idGenerator);
        }
        return ioFactory;
    }
}
