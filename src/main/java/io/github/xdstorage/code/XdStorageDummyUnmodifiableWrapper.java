package io.github.xdstorage.code;

/**
 * Stand-in for generated unmodifiable wrappers, used in ONE place only: where the wrapper
 * of the very class being generated on this thread is asked for again (a self-referencing
 * entity graph) — the recursion guard of {@code XdStorageProxyCompilationEngine} hands
 * this out so that the generation can finish. Its {@code equals}/{@code hashCode}
 * contracts are broken for engine purposes (it is a bare identity object); nothing that
 * stores or reads an entity may keep one.
 *
 * <p>Until 2.2.0 it was also the degradation the engine went on with when the runtime had
 * no Java compiler (a JRE). That use is gone: the engine refuses by name
 * ({@code XdStorageWrapperGenerationException}, docs/tasks/2.2.0/08).</p>
 *
 * <p>Stateless and thread-safe. Engine-internal — not part of the public API.</p>
 */
public class XdStorageDummyUnmodifiableWrapper implements IXdStorageUnmodifiableWrapper {

    /** Degrades to an inert stand-in: nothing to initialize. */
    public XdStorageDummyUnmodifiableWrapper() {
    }

    /**
     * Dummy-family marker mirroring the static marker generated into real unmodifiable wrappers.
     *
     * @return always {@code true} — this class is always the dummy variant
     */
    public static boolean isDummyUnmodifiableWrapper() {
        return true;
    }
}
