package io.github.xdstorage.code;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageReferenceInvisibleException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.utils.XdStorageProxyCompilationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * WRAPGEN (docs/tasks/2.2.0/35) — the lazy load-by-get getters choose their branch by the
 * field's DECLARED type. The generator used to ask {@code returnType.isAssignableFrom(Collection.class)},
 * which is true only for {@code Collection}, {@code Iterable} and {@code Object} themselves: a
 * {@code Set}-, {@code ArrayList}- or {@code HashMap}-declared lazy field fell into the single-object
 * branch, the elements stayed unfilled reference stubs and the loaded flag was set anyway.
 */
@DisplayName("WRAPGEN (2.2.0/35): ленивые поля-коллекции — выбор ветки по объявленному типу поля")
public class XdStorageLazyGetterTypeDispatchTest {

    private IXdStorage storage;
    private IXdStorageTransactionManager transactionManager;
    private IXdStorageTransaction transaction;

    @BeforeEach
    public void setUp() {
        storage = mock(IXdStorage.class);
        //noinspection unchecked,rawtypes
        transactionManager = mock(IXdStorageTransactionManager.class);
        transaction = mock(IXdStorageTransaction.class);
        when(storage.getTransactionManager()).thenReturn(transactionManager);
        //noinspection unchecked,rawtypes
        when(transactionManager.getCurrentTransaction()).thenReturn(transaction);
    }

    // === fixtures ===

    private static ValueChild child(Long id, String label) {
        final ValueChild c = new ValueChild();
        c.setId(id);
        c.setLabel(label);
        return c;
    }

    /** A reference stub of the same shape the engine hands out on a read: the simple wrapper over the child. */
    private ValueChild stub(Long id) throws Exception {
        return (ValueChild) XdStorageObjectUtils.wrapAsSimpleObject(child(id, "stub-" + id), storage, transaction);
    }

    private LazyTypedCollectionsBean newSimpleWrapper(final LazyTypedCollectionsBean inner) throws Exception {
        final Class<?> w = XdStorageProxyCompilationEngine.getClassSimpleWrapper(LazyTypedCollectionsBean.class);
        return (LazyTypedCollectionsBean) w.getConstructor(Object.class, LazyTypedCollectionsBean.class,
                IXdStorage.class).newInstance(null, inner, storage);
    }

    private static final Function<Object, Collection<?>> AS_COLLECTION = value -> (Collection<?>) value;
    private static final Function<Object, Collection<?>> MAP_VALUES = value -> ((Map<?, ?>) value).values();

    /**
     * One row per lazy member of the fixture: the declared type, its getter, how the elements are
     * viewed, and how test elements are put into the field.
     *
     * @return the lazy members under test (the last row is the control declaration)
     */
    static Stream<Arguments> lazyMembers() {
        return Stream.of(
                Arguments.of("Set", (Function<LazyTypedCollectionsBean, Object>) LazyTypedCollectionsBean::getLazySet,
                        AS_COLLECTION,
                        (BiConsumer<LazyTypedCollectionsBean, List<ValueChild>>) (b, c) ->
                                b.setLazySet(new LinkedHashSet<>(c))),
                Arguments.of("ArrayList", (Function<LazyTypedCollectionsBean, Object>)
                                LazyTypedCollectionsBean::getLazyList, AS_COLLECTION,
                        (BiConsumer<LazyTypedCollectionsBean, List<ValueChild>>) (b, c) ->
                                b.setLazyList(new ArrayList<>(c))),
                Arguments.of("HashMap", (Function<LazyTypedCollectionsBean, Object>)
                                LazyTypedCollectionsBean::getLazyMap, MAP_VALUES,
                        (BiConsumer<LazyTypedCollectionsBean, List<ValueChild>>) (b, c) -> {
                            final HashMap<String, ValueChild> map = new HashMap<>();
                            for (final ValueChild value : c) {
                                map.put("key-" + value.getId(), value);
                            }
                            b.setLazyMap(map);
                        }),
                Arguments.of("Collection (control)", (Function<LazyTypedCollectionsBean, Object>)
                                LazyTypedCollectionsBean::getLazyCollection, AS_COLLECTION,
                        (BiConsumer<LazyTypedCollectionsBean, List<ValueChild>>) (b, c) ->
                                b.setLazyCollection(new ArrayList<>(c))));
    }

    // === the per-element load: the first getter access loads every wrapped element ===

    @ParameterizedTest(name = "{0}")
    @MethodSource("lazyMembers")
    public void firstGetterAccessLoadsEveryWrappedElement(final String declaredType,
            final Function<LazyTypedCollectionsBean, Object> getter,
            final Function<Object, Collection<?>> elementsOf,
            final BiConsumer<LazyTypedCollectionsBean, List<ValueChild>> filler) throws Exception {
        final LazyTypedCollectionsBean inner = new LazyTypedCollectionsBean();
        final ValueChild first = stub(11L);
        final ValueChild second = stub(12L);
        filler.accept(inner, Arrays.asList(first, second));
        final LazyTypedCollectionsBean wrapper = newSimpleWrapper(inner);

        final Collection<?> lazy = elementsOf.apply(getter.apply(wrapper));

        assertEquals(2, lazy.size(), declaredType + ": the getter must return the field as is");
        verify(storage, times(1)).load(same(first), same(transaction));
        verify(storage, times(1)).load(same(second), same(transaction));
        verify(storage, times(2)).load(any(Object.class), any(IXdStorageTransaction.class));

        // the second access is served from the loaded flag — no further storage round-trip
        getter.apply(wrapper);
        verify(storage, times(1)).load(same(first), same(transaction));
        verify(storage, times(1)).load(same(second), same(transaction));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lazyMembers")
    public void plainElementsAreNeverLoaded(final String declaredType,
            final Function<LazyTypedCollectionsBean, Object> getter,
            final Function<Object, Collection<?>> elementsOf,
            final BiConsumer<LazyTypedCollectionsBean, List<ValueChild>> filler) throws Exception {
        final LazyTypedCollectionsBean inner = new LazyTypedCollectionsBean();
        final ValueChild plain = child(13L, "plain");
        filler.accept(inner, Collections.singletonList(plain));
        final LazyTypedCollectionsBean wrapper = newSimpleWrapper(inner);

        final Collection<?> lazy = elementsOf.apply(getter.apply(wrapper));

        assertEquals(1, lazy.size());
        assertTrue(lazy.contains(plain), declaredType + ": a plain element must pass through untouched");
        assertEquals("plain", plain.getLabel());
        verify(storage, times(0)).load(any(Object.class), any(IXdStorageTransaction.class));
        verify(storage, times(0)).load(any(Collection.class), any(IXdStorageTransaction.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lazyMembers")
    public void invisibleElementIsDroppedFromTheLazyField(final String declaredType,
            final Function<LazyTypedCollectionsBean, Object> getter,
            final Function<Object, Collection<?>> elementsOf,
            final BiConsumer<LazyTypedCollectionsBean, List<ValueChild>> filler) throws Exception {
        // R-58: an element invisible in the reader snapshot is not part of the visible graph version
        final LazyTypedCollectionsBean inner = new LazyTypedCollectionsBean();
        final ValueChild visible = stub(11L);
        final ValueChild invisible = stub(12L);
        filler.accept(inner, Arrays.asList(visible, invisible));
        doThrow(new XdStorageReferenceInvisibleException("invisible in the reader snapshot"))
                .when(storage).load(same(invisible), same(transaction));
        final LazyTypedCollectionsBean wrapper = newSimpleWrapper(inner);

        final Collection<?> lazy = elementsOf.apply(getter.apply(wrapper));

        assertEquals(1, lazy.size(), declaredType + ": the invisible element must be removed");
        assertTrue(lazy.contains(visible));
        assertFalse(lazy.contains(invisible));
        verify(transaction, times(0)).markRollbackOnly(anyString(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lazyMembers")
    public void nullFieldIsTolerated(final String declaredType,
            final Function<LazyTypedCollectionsBean, Object> getter) throws Exception {
        final LazyTypedCollectionsBean wrapper = newSimpleWrapper(new LazyTypedCollectionsBean());

        assertNull(getter.apply(wrapper), declaredType + ": an empty lazy field must stay null");
        verify(storage, times(0)).load(any(Object.class), any(IXdStorageTransaction.class));
    }

    // === the generated source: the branch actually emitted per declared type ===

    @Test
    @DisplayName("Сгенерированный код: каждое ленивое поле-коллекция получает ветку поэлементной загрузки")
    public void generatedSourceTakesTheElementBranchForEveryLazyMember() {
        final Map<String, String> generated = new HashMap<>();
        new XdStorageSimpleWrapperClassCodeGenerator().generate("io.github.xdstorage.code.gentest",
                LazyTypedCollectionsBean.class, generated);
        final String source = String.join("\n", generated.values());

        // the element branches (the iterator loop / the values() view) ...
        assertTrue(source.contains("toLoadCollection = object.getLazySet();"),
                "a Set-declared lazy field must iterate its elements");
        assertTrue(source.contains("toLoadCollection = object.getLazyList();"),
                "an ArrayList-declared lazy field must iterate its elements");
        assertTrue(source.contains("toLoadMap = object.getLazyMap();")
                        && source.contains("toLoadCollection = toLoadMap == null ? null : toLoadMap.values();"),
                "a HashMap-declared lazy field must load its values behind a null guard");
        assertTrue(source.contains("toLoadCollection = object.getLazyCollection();"),
                "the control Collection declaration must keep its branch");
        // ... and never the single-object one for those getters
        assertFalse(source.contains("Object toLoadObject = object.getLazySet();"));
        assertFalse(source.contains("Object toLoadObject = object.getLazyList();"));
        assertFalse(source.contains("Object toLoadObject = object.getLazyMap();"));
        assertFalse(source.contains("Object toLoadObject = object.getLazyCollection();"));

        // the R-58 path and the rollback-only site descriptor ride along with every element branch
        assertEquals(4, countOf(source, "toLoadIterator.remove();"), "one invisible-element removal per member");
        assertEquals(4, countOf(source, "markRollbackOnly(\"lazy-load of field '"), "one site descriptor per member");
    }

    @Test
    @DisplayName("Выбор ветки: любой подтип Collection — коллекция, любой подтип Map — карта, остальное — один объект")
    public void typeDispatchFollowsTheDeclaredType() {
        for (final Class<?> type : new Class<?>[]{Collection.class, List.class, Set.class, Queue.class,
                Iterable.class, ArrayList.class, HashSet.class, LinkedHashSet.class, ArrayDeque.class}) {
            assertTrue(XdStorageWrapperBlockGettersGenerator.isCollectionType(type), type.getSimpleName());
            assertFalse(XdStorageWrapperBlockGettersGenerator.isMapType(type), type.getSimpleName());
        }
        for (final Class<?> type : new Class<?>[]{Map.class, HashMap.class, LinkedHashMap.class,
                SortedMap.class, NavigableMap.class, ConcurrentHashMap.class}) {
            assertTrue(XdStorageWrapperBlockGettersGenerator.isMapType(type), type.getSimpleName());
            assertFalse(XdStorageWrapperBlockGettersGenerator.isCollectionType(type), type.getSimpleName());
        }
        // neither: the single-object branch (an Object-typed lazy field used to be sent to the
        // collection branch, whose generated source did not compile)
        for (final Class<?> type : new Class<?>[]{Object.class, ValueChild.class, String.class, String[].class}) {
            assertFalse(XdStorageWrapperBlockGettersGenerator.isCollectionType(type), type.getSimpleName());
            assertFalse(XdStorageWrapperBlockGettersGenerator.isMapType(type), type.getSimpleName());
        }
    }

    // === real storage: the user-visible contract ===

    @Test
    @DisplayName("Реальное хранилище: после первого обращения к ленивому геттеру дети читаются полностью")
    public void realStorageFirstGetterAccessMakesEveryChildReadable() throws Exception {
        final Path folder = Files.createTempDirectory("xdstorage_wrapgen_");
        final String storageName = "wrapgen-lazy-" + UUID.randomUUID();
        IXdFileStorage realStorage = XdStorageProvider.newOrGetFileStorage(storageName, folder.toString(), 200);
        try {
            final ValueChild setA = child(11L, "set-a"), setB = child(12L, "set-b");
            final ValueChild listA = child(21L, "list-a"), listB = child(22L, "list-b");
            final ValueChild mapA = child(31L, "map-a"), mapB = child(32L, "map-b");
            final ValueChild colA = child(41L, "col-a"), colB = child(42L, "col-b");

            final LazyTypedCollectionsBean parent = new LazyTypedCollectionsBean();
            parent.setId(1L);
            parent.setName("parent");
            parent.setLazySet(new LinkedHashSet<>(Arrays.asList(setA, setB)));
            parent.setLazyList(new ArrayList<>(Arrays.asList(listA, listB)));
            final HashMap<String, ValueChild> map = new HashMap<>();
            map.put("a", mapA);
            map.put("b", mapB);
            parent.setLazyMap(map);
            parent.setLazyCollection(new ArrayList<>(Arrays.asList(colA, colB)));

            final IXdStorageTransaction writeTx = realStorage.beginTransaction(30_000L);
            for (final ValueChild value : new ValueChild[]{setA, setB, listA, listB, mapA, mapB, colA, colB}) {
                realStorage.save(value, writeTx);
            }
            realStorage.save(parent, writeTx);
            writeTx.commit();

            // the cold reopen: no in-memory record can have filled the children in advance
            realStorage.shutdown();
            realStorage = XdStorageProvider.newOrGetFileStorage(storageName, folder.toString(), 200);

            // distinct children per field: the engine memoizes one wrapper per (transaction, class, id),
            // so a shared child loaded by one getter would hide a missing load in another
            final IXdStorageTransaction readTx = realStorage.beginTransaction(30_000L);
            final LazyTypedCollectionsBean loaded = realStorage.load(LazyTypedCollectionsBean.class, 1L, readTx);
            final Collection<ValueChild> lazySet = loaded.getLazySet();
            final List<ValueChild> lazyList = loaded.getLazyList();
            final Collection<ValueChild> lazyMapValues = loaded.getLazyMap().values();
            final Collection<ValueChild> lazyCollection = loaded.getLazyCollection();

            assertAll(
                    () -> assertChildren("Set", lazySet, "set-a", "set-b"),
                    () -> assertChildren("ArrayList", lazyList, "list-a", "list-b"),
                    () -> assertChildren("HashMap", lazyMapValues, "map-a", "map-b"),
                    () -> assertChildren("Collection", lazyCollection, "col-a", "col-b"));
            readTx.commit();
        } finally {
            realStorage.shutdown();
            deleteRecursively(folder);
        }
    }

    private static void assertChildren(final String declaredType, final Collection<ValueChild> children,
            final String... expectedLabels) {
        assertEquals(expectedLabels.length, children.size(), declaredType + ": the element count must survive");
        for (final ValueChild child : children) {
            assertFalse(((IXdStorageSimpleWrapper) child).isReference__(),
                    declaredType + ": the getter must have loaded the child " + child.getId());
        }
        final List<String> labels = new ArrayList<>();
        for (final ValueChild child : children) {
            labels.add(child.getLabel());
        }
        for (final String expected : expectedLabels) {
            assertTrue(labels.contains(expected), declaredType + ": the child '" + expected + "' must be readable");
        }
    }

    private static int countOf(final String source, final String fragment) {
        int count = 0;
        for (int at = source.indexOf(fragment); at >= 0; at = source.indexOf(fragment, at + fragment.length())) {
            ++count;
        }
        return count;
    }

    private static void deleteRecursively(final Path path) {
        // best effort: a file held elsewhere may legally refuse deletion (it must not fail the test)
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // cleanup only
        }
    }
}
