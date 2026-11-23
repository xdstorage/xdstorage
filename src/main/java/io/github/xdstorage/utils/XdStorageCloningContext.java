package io.github.xdstorage.utils;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The per-thread "already cloned" map shared by the MVCC cloning machinery
 * ({@code XdStorageMvccGraphCloner} / {@code XdStoragePojoCloner}): an identity-keyed map that
 * terminates reference cycles, deduplicates repeated references and lets nested clone calls
 * recognize their root. Thread-confined by construction — each cloning thread walks its own
 * graph. Engine-internal — not part of the public API.
 */
public final class XdStorageCloningContext {
    private static final ThreadLocal<Map<Object, Object>> cloningContext = ThreadLocal
            .withInitial(IdentityHashMap::new);

    private XdStorageCloningContext() {}

    /**
     * Returns the calling thread's identity-keyed visited map (created on first access, possibly empty).
     *
     * @return the calling thread's identity-keyed visited map (created on first access, possibly empty)
     */
    public static Map<Object, Object> get() {
        return cloningContext.get();
    }

    /**
     * Returns {@code true} when the calling thread's map is empty, i.e. no clone walk is in progress.
     *
     * @return {@code true} when the calling thread's map is empty, i.e. no clone walk is in progress
     */
    public static boolean isRootCall() {
        return cloningContext.get().isEmpty();
    }

    /** Empties the calling thread's map, releasing the whole visited-graph bookkeeping. */
    public static void clear() {
        cloningContext.get().clear();
    }
}
