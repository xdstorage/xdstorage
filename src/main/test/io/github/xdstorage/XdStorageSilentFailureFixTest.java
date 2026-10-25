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
import java.util.Deque;
import java.util.Queue;
import java.util.SortedMap;
import java.util.SortedSet;

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

    // a pojo the reflective cloner cannot instantiate (no no-arg constructor)
    public static final class NoNoArgPojo {
        private final String payload;
        public NoNoArgPojo(final String payload) { this.payload = payload; }
        public String getPayload() { return payload; }
    }

    @Test
    public void collection_WithUninstantiableElement_FailsLoud_NeverANullSnapshot() {
        // PojoCloner instantiates element clones via cl.newInstance(): a pojo without a
        // no-arg constructor throws, and the cloner must propagate it LOUDLY — the old
        // code swallowed it and returned a null snapshot (silent data loss, R-44 family).
        final List<NoNoArgPojo> poisoned = new ArrayList<>();
        poisoned.add(new NoNoArgPojo("boom"));

        final XdStorageRuntimeException x = assertThrows(XdStorageRuntimeException.class,
                () -> XdStorageCollectionCloner.cloneCollection(null, poisoned, new java.util.HashMap<>()),
                "an element clone failure must fail the snapshot, never silently drop the collection");
        assertTrue(x.getMessage().contains("ArrayList"), "the message names the offending collection type");
        assertNotNull(x.getCause());
    }

    @Test
    public void nonInstantiableCollection_FallsBackToSemanticEquivalent() {
        // the fallback ladder: NoCtorList cannot be re-instantiated — the clone becomes
        // the closest mutable semantic equivalent (an ArrayList) with contents preserved.
        final NoCtorList poisoned = new NoCtorList("poison");
        poisoned.add("element");

        final Object clone = XdStorageCollectionCloner.cloneCollection(null, poisoned, new java.util.HashMap<>());
        assertTrue(clone instanceof List, "the fallback keeps list semantics");
        assertEquals(poisoned, clone, "contents preserved across the class coarsening");
    }

    @Test
    public void nonInstantiableMap_FallsBackToSemanticEquivalent() {
        // the map mirror: an unmodifiable map cannot be re-instantiated — the clone
        // becomes a mutable semantic equivalent (a HashMap) with contents preserved.
        final Map<String, String> inner = new java.util.LinkedHashMap<>();
        inner.put("k1", "v1");
        final Map<String, String> source = java.util.Collections.unmodifiableMap(inner);

        final Object clone = XdStorageCollectionCloner.cloneMap(null, source, new java.util.HashMap<>());
        assertTrue(clone instanceof Map, "the fallback keeps map semantics");
        assertEquals(source, clone, "contents preserved across the class coarsening");
    }

    // =====================================================================
    // EXTENSION (the fallback ladder): non-instantiable containers clone into the
    // closest MUTABLE semantic equivalent — contents preserved, class coarsened.
    // =====================================================================

    @Test
    public void unmodifiableList_ClonesIntoRandomAccessList() {
        final List<String> source = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(java.util.Arrays.asList("a", "b")));
        final Object clone = XdStorageCollectionCloner.cloneCollection(null, source, new java.util.HashMap<>());
        assertTrue(clone instanceof List, "the clone stays a List");
        assertEquals(source, clone, "contents and order preserved");
        assertTrue(clone instanceof java.util.RandomAccess, "the RandomAccess fallback keeps list access traits");
    }

    @Test
    public void unmodifiableSet_ClonesIntoASet_NotAList() {
        final java.util.Set<String> source = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList("x", "y")));
        final Object clone = XdStorageCollectionCloner.cloneCollection(null, source, new java.util.HashMap<>());
        assertTrue(clone instanceof java.util.Set, "set semantics must survive (a List fallback would admit duplicates)");
        assertEquals(source, clone);
    }

    @Test
    public void unmodifiableSortedSet_ClonesWithOrderingPreserved() {
        final java.util.SortedSet<String> source = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(java.util.Arrays.asList("c", "a", "b")));
        final Object clone = XdStorageCollectionCloner.cloneCollection(null, source, new java.util.HashMap<>());
        assertTrue(clone instanceof java.util.SortedSet, "sorted-set ordering must survive the fallback");
        assertEquals(source.first(), ((java.util.SortedSet<?>) clone).first());
        assertEquals(source.last(), ((java.util.SortedSet<?>) clone).last());
        assertEquals(source, clone);
    }

    @Test
    public void synchronizedLinkedList_ClonesIntoALinkedList() {
        final java.util.LinkedList<String> inner = new java.util.LinkedList<>();
        inner.add("q1");
        final List<String> source = java.util.Collections.synchronizedList(inner);
        final Object clone = XdStorageCollectionCloner.cloneCollection(null, source, new java.util.HashMap<>());
        assertTrue(clone instanceof List);
        assertEquals(source, clone);
    }

    @Test
    public void unmodifiableMap_NonEmpty_ClonesIntoAMap_WithContentsPreserved() {
        final java.util.Map<String, String> inner = new java.util.LinkedHashMap<>();
        inner.put("k1", "v1");
        inner.put("k2", "v2");
        final java.util.Map<String, String> source = java.util.Collections.unmodifiableMap(inner);
        final Object clone = XdStorageCollectionCloner.cloneMap(null, source, new java.util.HashMap<>());
        assertTrue(clone instanceof java.util.Map);
        assertEquals(source, clone, "the non-empty unmodifiable map must clone with contents preserved");
    }

    @Test
    public void unmodifiableSortedMap_ClonesWithOrderingPreserved() {
        final java.util.SortedMap<String, String> innerSorted = new java.util.TreeMap<>();
        innerSorted.put("m3", "v3");
        innerSorted.put("m1", "v1");
        innerSorted.put("m2", "v2");
        final java.util.SortedMap<String, String> source = java.util.Collections.unmodifiableSortedMap(innerSorted);
        final Object clone = XdStorageCollectionCloner.cloneMap(null, source, new java.util.HashMap<>());
        assertTrue(clone instanceof java.util.SortedMap, "sorted-map ordering must survive the fallback");
        assertEquals(source.firstKey(), ((java.util.SortedMap<?, ?>) clone).firstKey());
        assertEquals(source.lastKey(), ((java.util.SortedMap<?, ?>) clone).lastKey());
        assertEquals(source, clone);
    }

    @Test
    public void nullSource_ClonesStayNull_HonestSemantics() {
        assertNull(XdStorageCollectionCloner.cloneCollection(null, null, new java.util.HashMap<>()));
        assertNull(XdStorageCollectionCloner.cloneMap(null, null, new java.util.HashMap<>()));
    }
}
