package io.github.xdstorage.code;

/**
 * Contract of the generated simple wrapper — the lazy-loading wrapper subclass the
 * engine hands out for stored entities. Exposes the wrapped object's id, the reference
 * flag persisted alongside the object, and the per-wrapper lock that generated
 * load-by-get accessors hold during lazy loading.
 *
 * <p>Implementations are produced by {@code XdStorageSimpleWrapperClassCodeGenerator} — at
 * build time into the application's classes, or at runtime; {@code XdStorageDummySimpleWrapper}
 * is the stand-in used only where a wrapper's generation is in progress on this thread.
 * The reference flag is volatile in generated subclasses;
 * the lock methods delegate to a per-wrapper {@code ReentrantLock}.
 * Engine-internal — not part of the public API.</p>
 */
public interface IXdStorageSimpleWrapper {

    /**
     * Returns the id of the wrapped object, read through the entity's id getter.
     *
     * @return the id of the wrapped object (may be {@code null} until the id is set)
     */
    Object getObjectId__();

    /**
     * Внутренний (завернутый) объект обертки. Позволяет генерируемому equals()
     * сравнивать обертки без рефлекшна на каждый вызов: equals — горячий путь
     * HashSet/HashMap движка.
     *
     * @return the wrapped (inner) entity object, or {@code null} when the implementation
     *         does not track it
     */
    default Object getWrappedObject__() {
        return null;
    }

    /**
     * Sets the reference flag of this wrapper — whether the instance is an unloaded
     * reference rather than a fully materialized object. The flag is persisted with
     * the object.
     *
     * @param reference the new reference flag value
     */
    void setReference__(boolean reference);

    /**
     * Returns the current value of the reference flag.
     *
     * @return {@code true} while the instance is an unloaded reference
     */
    boolean isReference__();

    /**
     * Acquires the per-wrapper lock guarding lazy loads of strong fields.
     */
    void lock__();

    /**
     * Releases the per-wrapper lock acquired by {@link #lock__()}.
     */
    void unlock__();
}
