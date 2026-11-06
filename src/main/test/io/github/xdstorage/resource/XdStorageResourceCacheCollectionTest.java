package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.factories.IXdStorageReferenceProvider;
import io.github.xdstorage.factories.XdStorageDefaultReferenceProvider;
import io.github.xdstorage.factories.XdStorageSmartCloner;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Инварианты КОЛЛЕКЦИЙ объекта в XdStorageResourceCache:
 * вставка объекта с коллекцией, чтение обертки по id, добавление элементов
 * (через изначальный объект и через обертку), обновление (через изначальный
 * и через обернутый), загрузка через обернутый объект (readByReference).
 *
 * ГЛАВНЫЙ инвариант: элементы коллекции не теряются ни на одном шаге.
 */
@DisplayName("Коллекции в ResourceCache: элементы не теряются при вставке/чтении/мутациях")
public class XdStorageResourceCacheCollectionTest {

    /** Сущность с коллекцией строк. */
    public static class Doc {
        private Long id;
        private String title;
        private List<String> tags;

        public Doc() {}

        public Doc(final Long id, final String title, final List<String> tags) {
            this.id = id;
            this.title = title;
            this.tags = tags;
        }

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(final String title) { this.title = title; }
        public List<String> getTags() { return tags; }
        public void setTags(final List<String> tags) { this.tags = tags; }
    }

    private XdStorageResourceCache cache;
    private IXdStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        final io.github.xdstorage.services.XdStorageServicesLocator locator =
                mock(io.github.xdstorage.services.XdStorageServicesLocator.class);
        storage = mock(IXdStorage.class);
        when(locator.getStorage()).thenReturn(storage);

        final io.github.xdstorage.transaction.IXdStorageTransactionManager txManager =
                mock(io.github.xdstorage.transaction.IXdStorageTransactionManager.class);
        when(txManager.isTransactionAlive(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(locator.getTransactionsManager()).thenReturn(txManager);

        // Боевой клонер: коллекции копируются реальными путями pack/unpack
        final IXdStorageReferenceProvider provider =
                new XdStorageDefaultReferenceProvider(storage);
        final IXdStorageCloner realCloner = new XdStorageSmartCloner(provider);
        when(locator.getCloner()).thenReturn(realCloner);

        final io.github.xdstorage.utils.XdStorageObjectIdField idField =
                mock(io.github.xdstorage.utils.XdStorageObjectIdField.class);
        when(idField.get(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            final Object o = inv.getArgument(0);
            return o instanceof Doc ? ((Doc) o).getId() : null;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            final Object target = inv.getArgument(0);
            final Long id = inv.getArgument(1);
            if (target instanceof Doc) ((Doc) target).setId(id);
            return null;
        }).when(idField).set(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        cache = new XdStorageResourceCache(locator, Doc.class, idField);
    }

    private static Doc doc(final long id, final String title, final String... tags) {
        return new Doc(id, title, new ArrayList<>(Arrays.asList(tags)));
    }

    private static List<String> tagsOf(final Object o) {
        if (o == null) {
            return null;
        }
        final Object unwrapped = XdStorageObjectUtils.getWrappedObjectOrSameObject(o);
        if (!(unwrapped instanceof Doc)) {
            return null;
        }
        final Doc d = (Doc) unwrapped;
        return d.getTags() == null ? null : new ArrayList<>(d.getTags());
    }

    private XdStorageTransaction tx(final String id) {
        final XdStorageTransaction tx = org.mockito.Mockito.mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(id);
        when(tx.getTimestart()).thenReturn(System.nanoTime());
        when(tx.getTimeout()).thenReturn(100L);
        return tx;
    }

    // =========================================================================
    // 1. Вставка объекта с коллекцией
    // =========================================================================

    @Test
    @DisplayName("Вставка: объект с коллекцией [a,b] читается по id с теми же элементами")
    public void insertWithCollection() throws Exception {
        final XdStorageTransaction txn = tx("t1");
        cache.insert(doc(1L, "Doc_1", "a", "b"), txn);

        assertTrue(cache.hasObject(1L), "Объект не вставлен");
        final Object readBack = cache.read(1L, txn);
        assertNotNull(readBack, "Объект не читается по id");
        assertEquals(Arrays.asList("a", "b"), tagsOf(readBack), "Элементы коллекции потеряны при вставке");
    }

    // =========================================================================
    // 2. Чтение по id — получение обернутого элемента
    // =========================================================================

    @Test
    @DisplayName("Чтение по id: возвращает обертку, делегирующую в живой объект")
    public void readByIdReturnsWrappedElement() throws Exception {
        final XdStorageTransaction txn = tx("t2");
        final Doc original = doc(2L, "Doc_2", "x");
        cache.insert(original, txn);

        final Object wrapped = cache.read(2L, txn);
        assertNotNull(wrapped, "Обертка не получена");
        // Канонические инстансы (Phase C): инфраструктурные read-пути возвращают живой объект
        assertSame(original, wrapped, "Канонический инстанс должен совпадать с живым");
    }

    // =========================================================================
    // 3. Добавление элемента в коллекцию
    // =========================================================================

    @Test
    @DisplayName("Добавление через ИСХОДНЫЙ объект: элемент не теряется после update+read")
    public void addViaOriginalObject() throws Exception {
        final XdStorageTransaction txn = tx("t3");
        final Doc original = doc(3L, "Doc_3", "a");
        cache.insert(original, txn);

        original.getTags().add("b");
        cache.update(original, txn);

        assertEquals(Arrays.asList("a", "b"), tagsOf(cache.read(3L, txn)),
                "Элемент 'b', добавленный через исходный объект, потерян");
    }

    @Test
    @DisplayName("Добавление через ОБЕРНУТЫЙ объект: элемент не теряется после update+read")
    public void addViaWrappedObject() throws Exception {
        final XdStorageTransaction txn = tx("t4");
        cache.insert(doc(4L, "Doc_4", "a"), txn);

        final Object wrapped = cache.read(4L, txn);
        final Doc wrappedDoc = (Doc) XdStorageObjectUtils.getWrappedObjectOrSameObject(wrapped);
        wrappedDoc.getTags().add("b");
        cache.update(wrapped, txn);

        final List<String> tags = tagsOf(cache.read(4L, txn));
        assertTrue(tags.contains("a"), "Базовый элемент потерян");
        assertTrue(tags.contains("b"), "Элемент 'b', добавленный через обертку, потерян");
    }

    // =========================================================================
    // 4. Обновление объекта
    // =========================================================================

    @Test
    @DisplayName("Обновление через ИСХОДНЫЙ объект: новая коллекция целиком")
    public void updateViaOriginalObject() throws Exception {
        final XdStorageTransaction txn = tx("t5");
        final Doc original = doc(5L, "Doc_5", "a", "b");
        cache.insert(original, txn);

        original.setTitle("Doc_5_v2");
        original.setTags(new ArrayList<>(Arrays.asList("x", "y", "z")));
        cache.update(original, txn);

        final Doc readBack = (Doc) cache.read(5L, txn);
        assertEquals("Doc_5_v2", readBack.getTitle());
        assertEquals(Arrays.asList("x", "y", "z"), readBack.getTags(), "Новая коллекция потеряна");
    }

    @Test
    @DisplayName("Обновление через ОБЕРНУТЫЙ объект: коллекция модифицируется без потерь")
    public void updateViaWrappedObject() throws Exception {
        final XdStorageTransaction txn = tx("t6");
        cache.insert(doc(6L, "Doc_6", "keep-1"), txn);

        final Object wrapped = cache.read(6L, txn);
        final Doc wrappedDoc = (Doc) XdStorageObjectUtils.getWrappedObjectOrSameObject(wrapped);
        wrappedDoc.setTitle("Doc_6_v2");
        wrappedDoc.setTags(new ArrayList<>(Arrays.asList("n1", "n2")));
        cache.update(wrapped, txn);

        final Doc readBack = (Doc) cache.read(6L, txn);
        assertEquals("Doc_6_v2", readBack.getTitle(), "Заголовок не обновился");
        assertEquals(Arrays.asList("n1", "n2"), readBack.getTags(), "Коллекция потеряна при обновлении через обертку");
    }

    // =========================================================================
    // 5. Загрузка через обернутый объект (readByReference)
    // =========================================================================

    @Test
    @DisplayName("Загрузка по ссылке: коллекция наполняется из записи без потерь")
    public void loadThroughWrappedReference() throws Exception {
        final XdStorageTransaction txn = tx("t7");
        cache.insert(doc(7L, "Doc_7", "t1", "t2"), txn);

        final Doc proxy = new Doc(7L, null, null);
        cache.readByReference(proxy, txn);

        assertEquals("Doc_7", proxy.getTitle());
        assertEquals(Arrays.asList("t1", "t2"), proxy.getTags(), "Коллекция потеряна при загрузке по ссылке");
    }

    // =========================================================================
    // 6. Циклы мутаций: элементы не теряются на серии шагов
    // =========================================================================

    @Test
    @DisplayName("Циклы: 20 раундов добавление+обновление+чтение — ни один элемент не потерян")
    public void churnCyclesElementsNeverLost() throws Exception {
        final XdStorageTransaction txn = tx("t8");
        final Doc original = doc(8L, "Doc_8");
        cache.insert(original, txn);

        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final String tag = "tag-" + i;
            // (а) через исходный объект
            original.getTags().add(tag);
            expected.add(tag);
            cache.update(original, txn);
            assertTrue(tagsOf(cache.read(8L, txn)).containsAll(expected),
                    "Цикл " + i + " (через исходный): элементы потеряны");

            // (б) через обернутый объект
            final Object wrapped = cache.read(8L, txn);
            ((Doc) XdStorageObjectUtils.getWrappedObjectOrSameObject(wrapped)).getTags().add("w-" + i);
            cache.update(wrapped, txn);
            expected.add("w-" + i);
            final List<String> after = tagsOf(cache.read(8L, txn));
            assertTrue(after.containsAll(expected),
                    "Цикл " + i + " (через обертку): потеряны " + expected + " vs " + after);
        }
        assertEquals(expected.size(), tagsOf(cache.read(8L, txn)).size(), "Финальный размер коллекции");
    }

    // =========================================================================
    // 7. Изоляция: незакоммиченное добавление не видно другой TX
    // =========================================================================

    @Test
    @DisplayName("Изоляция: чужое незакоммиченное добавление невидимо; свое — видимо")
    public void isolationOfAddedElement() throws Exception {
        final XdStorageTransaction txA = tx("tx-A");
        final XdStorageTransaction txB = tx("tx-B");
        cache.insert(doc(9L, "Doc_9", "base"), txA);

        final Object wrappedA = cache.read(9L, txA);
        ((Doc) XdStorageObjectUtils.getWrappedObjectOrSameObject(wrappedA)).getTags().add("added");
        cache.update(wrappedA, txA);

        // КЛАССИЧЕСКАЯ ИЗОЛЯЦИЯ: незакоммиченная вставка TX A невидима TX B
        assertNull(cache.read(9L, txB), "Незакоммиченная запись видна чужой TX!");

        // После коммита TX B видит закоммиченное состояние
        cache.prepareCommit(txA, new XdStorageTransactionResourceChanges(
                mock(io.github.xdstorage.resource.IXdStorageResourceObject.class)));
        cache.performCommit(txA, new XdStorageTransactionResourceChanges(
                mock(io.github.xdstorage.resource.IXdStorageResourceObject.class)));
        cache.commit(txA);

        final List<String> tagsCommitted = tagsOf(cache.read(9L, txB));
        assertTrue(tagsCommitted.contains("base"), "Закоммиченная база потеряна для TX B");
        assertTrue(tagsCommitted.contains("added"), "Закоммиченное добавление не видно TX B");
    }
}
