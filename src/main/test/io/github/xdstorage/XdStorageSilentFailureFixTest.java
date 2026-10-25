package io.github.xdstorage;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.utils.XdStorageCollectionCloner;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SILENTFAIL (docs/tasks/2.1.0/35) — regression tests for the three swallow-and-continue
 * paths: the wrapper-generation result map keyed by the requesting class (multi-class
 * graphs lost all but the last loaded class), the observer wrapper get-or-create race with
 * its null-masquerading failure path, and the collection cloner swallowing clone failures
 * into {@code null} MVCC snapshots.
 */
public class XdStorageSilentFailureFixTest {

    // =====================================================================
    // FIX 1: the wrapper-generation result map keyed by the WRAPPED class
    // =====================================================================

    /**
     * Compiles TWO wrapper classes in one shot through the private
     * {@code compileGeneratedClassesCode(cl, generatedCode)}: the old code keyed both
     * loaded classes by the requesting entity class, silently keeping only the last.
     * The entity is TOP-LEVEL (XdStorageSilentFailMultiEntity) — the runtime
     * compiler cannot compile wrappers over nested classes.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void multiClassCompilationKeepsEveryGeneratedClass() throws Exception {
        final Class<?> cl = XdStorageSilentFailMultiEntity.class;
        final Class<?> child = XdStorageSilentFailChildEntity.class;
        final String pkg = "io.github.xdstorage.code";
        final Map<String, String> generatedCode = new java.util.HashMap<>();
        generatedCode.put(pkg + ".SilentFailMultiWrapperA",
                "package " + pkg + ";\n"
                + "public class SilentFailMultiWrapperA extends "
                + XdStorageSilentFailMultiEntity.class.getName() + " {\n"
                + "    public SilentFailMultiWrapperA(" + XdStorageSilentFailMultiEntity.class.getName() + " o) { super(o); }\n"
                + "}\n");
        generatedCode.put(pkg + ".SilentFailMultiWrapperB",
                "package " + pkg + ";\n"
                + "public class SilentFailMultiWrapperB extends "
                + child.getName() + " {\n"
                + "    public SilentFailMultiWrapperB(" + child.getName() + " o) { super(o); }\n"
                + "}\n");

        final Method compile = io.github.xdstorage.code.XdStorageClassGenerator.class
                .getDeclaredMethod("compileGeneratedClassesCode", Class.class, Map.class);
        compile.setAccessible(true);
        final Map<Class<?>, Class<?>> result = (Map<Class<?>, Class<?>>)
                compile.invoke(null, cl, generatedCode);

        assertEquals(2, result.size(),
                "a multi-class compilation graph must keep EVERY generated class, not only the last loaded one");
        assertTrue(result.containsKey(cl), "the entity's own wrapper must be in the result");
        assertTrue(result.containsKey(child), "the child class's wrapper must survive (was lost under the entity key)");
        assertNotSame(result.get(cl), result.get(child));
    }

    // =====================================================================
    // FIX 2: the observer wrapper — atomic get-or-create, no null masquerade
    // =====================================================================

    @Test
    public void concurrentObservableWrapperRequests_GetOneSharedInstance() throws Exception {
        final XdStorageSilentFailMultiEntity shared = new XdStorageSilentFailMultiEntity();
        shared.setId(1L);
        shared.setValue("shared");

        final int threads = 24;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < threads; ++i) {
                results.add(pool.submit((Callable<Object>) () ->
                        XdStorageObserverService.getObservableWrapper(shared)));
            }
            final Object first = results.get(0).get();
            assertNotNull(first);
            for (final Future<Object> future : results) {
                assertSame(first, future.get(),
                        "the get-or-create must be atomic: every racing thread observes the SAME wrapper instance");
            }
            // the cache settles on the single shared instance
            assertSame(first, XdStorageObserverService.getObservableWrapper(shared));
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================
    // FIX 3: the collection cloner — clone failures are loud, never a null snapshot
    // =====================================================================

    /** A list that cannot be re-instantiated reflectively (no accessible no-arg constructor). */
    public static final class NoCtorList extends ArrayList<String> {
        private final String poison;
        public NoCtorList(final String poison) { this.poison = poison; }
    }

    @Test
    public void cloneCollection_FailureIsLoud_NeverANullSnapshot() {
        final NoCtorList poisoned = new NoCtorList("poison");
        poisoned.add("element");

        final XdStorageRuntimeException x = assertThrows(XdStorageRuntimeException.class,
                () -> XdStorageCollectionCloner.cloneCollection(null, poisoned, new java.util.HashMap<>()),
                "a clone failure must fail the snapshot, never silently drop the collection");
        assertTrue(x.getMessage().contains("NoCtorList"), "the message names the offending type");
        assertNotNull(x.getCause());
    }

    @Test
    public void cloneMap_FailureIsLoud_NeverANullSnapshot() {
        final Map<String, String> source = new java.util.LinkedHashMap<>();
        source.put("k", "v");
        // cloneMap clones INTO a fresh instance of the SOURCE class: make the source class
        // itself the poison — a Map subtype that is not reflectively instantiable.
        final Map<String, String> uninjectable = java.util.Collections.unmodifiableMap(source);

        assertThrows(XdStorageRuntimeException.class,
                () -> XdStorageCollectionCloner.cloneMap(null, uninjectable, new java.util.HashMap<>()),
                "a map clone failure must fail the snapshot, never silently drop the map");
    }

    @Test
    public void nullSource_ClonesStayNull_HonestSemantics() {
        assertNull(XdStorageCollectionCloner.cloneCollection(null, null, new java.util.HashMap<>()));
        assertNull(XdStorageCollectionCloner.cloneMap(null, null, new java.util.HashMap<>()));
    }
}
