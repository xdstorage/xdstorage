package io.github.xdstorage.code;

/**
 * Contract of the generated unmodifiable wrapper — the read-only wrapper subclass the
 * engine hands out for snapshot/MVCC views of stored entities (and for the
 * infrastructure base class via {@code XdStorageUnmodifiableXdStorageObjectWrapperClassCodeGenerator}).
 * Generated mutators reject or roll back any modification attempt.
 *
 * <p>Implementations are produced by the unmodifiable code generators — at build time into
 * the application's classes, or at runtime; {@code XdStorageDummyUnmodifiableWrapper} is
 * the stand-in used only where a wrapper's generation is in progress on this thread.
 * Stateless per instance.
 * Engine-internal — not part of the public API.</p>
 */
public interface IXdStorageUnmodifiableWrapper {

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

}
