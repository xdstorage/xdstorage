package io.github.xdstorage.idgeneration;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;

/** The SPI no-op generator: the default of
 * {@code @XdStorageObjectFieldProperties#idGeneratorClass()} and the marker for "no
 * custom generator configured" — it returns {@code null} and is never used as a real
 * generator. */
public class XdStorageDummyIdGenerator implements IXdStorageIdGenerator {

    /** Instantiation only through the reflective path that resolves the annotation default. */
    public XdStorageDummyIdGenerator() {
    }

    /** Always returns {@code null} — this generator never assigns ids. */
    @Override
    public Object generate(final Class<?> cl, final IXdStorage storage, final IXdStorageTransaction transaction) throws
            XdStorageException {
        return null;
    }
}
