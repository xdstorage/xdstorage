package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Инварианты MVCC XdStorageResourceCache: фантомные чтения, изоляция снимков,
 * собственные записи транзакции и консистентность ссылок (readByReference).
 */
@DisplayName("Инварианты ResourceCache: фантомы, снимки, transit, ссылки")
public class XdStorageResourceCachePhantomReadTest extends AbstractXdStorageResourceCacheTest {

    @BeforeEach
    public void fillBaseData() {
        resourceCache.fillCache(java.util.Arrays.asList(
                new TestEntity(1L, "Stable_1"),
                new TestEntity(2L, "Stable_2"),
                new TestEntity(3L, "Stable_3")
        ));
    }

    // =========================================================================
    // ФАНТОМЫ: предикатный скан не видит чужих незакоммиченных мутаций
    // =========================================================================

    @Test
    @DisplayName("Фантом вставки: незакоммиченная вставка невидима в предикатном скане другой TX")
    public void phantomInsertNotVisible() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        final XdStorageTransaction txB = createTx("B", 1001);
        resourceCache.insert(new TestEntity(9L, "Phantom"), txA);

        final Collection<TestEntity> scan = resourceCache.read(txB,
                (IXdStoragePredicate<TestEntity>) e -> true);
        final List<Long> ids = ids(scan);
        assertFalse(ids.contains(9L), "Фантом вставки просочился в чужой скан!");
        assertEquals(3, ids.size());
    }

    @Test
    @DisplayName("Фантом удаления: незакоммиченное удаление не исчезает из чужого скана")
    public void phantomDeleteStaysVisible() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        final XdStorageTransaction txB = createTx("B", 1001);
        resourceCache.delete(2L, txA);

        final Collection<TestEntity> scan = resourceCache.read(txB,
                (IXdStoragePredicate<TestEntity>) e -> true);
        assertEquals(3, ids(scan).size(), "Незакоммиченное удаление видно чужому скану!");
    }

    @Test
    @DisplayName("Фантом обновления: чужой незакоммиченный апдейт не меняет значения в скане")
    public void phantomUpdateOldValue() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        final XdStorageTransaction txB = createTx("B", 1001);
        resourceCache.update(new TestEntity(1L, "Dirty_1"), txA);

        final Collection<TestEntity> scan = resourceCache.read(txB,
                (IXdStoragePredicate<TestEntity>) e -> "Dirty_1".equals(e.getValue()));
        assertTrue(scan.isEmpty(), "Грязное значение просочилось в чужой скан!");
        assertTrue(resourceCache.read(txB,
                (IXdStoragePredicate<TestEntity>) e -> "Stable_1".equals(e.getValue())).size() == 1);
    }

    // =========================================================================
    // СНИМКИ: читатель, начавший до коммита, видит старый мир; начавший после — новый
    // =========================================================================

    @Test
    @DisplayName("Снимок: читатель до коммита видит старый мир, читатель после — новый")
    public void snapshotBeforeAfterCommit() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        resourceCache.insert(new TestEntity(9L, "Committed_9"), txA);
        resourceCache.update(new TestEntity(1L, "Committed_1"), txA);
        resourceCache.delete(3L, txA);

        final XdStorageTransactionResourceChanges collector = mockResource();
        resourceCache.prepareCommit(txA, collector);
        resourceCache.performCommit(txA, collector);
        resourceCache.commit(txA);
        final XdStorageTransaction before = createTx("BEFORE", 999); // начал ДО коммиттера

        // Читатель, начавший до коммита: старый мир
        final Collection<TestEntity> old = resourceCache.read(before,
                (IXdStoragePredicate<TestEntity>) e -> true);
        final List<Long> oldIds = ids(old);
        assertTrue(oldIds.contains(3L), "Снимок потерял удаленную запись");
        assertFalse(oldIds.contains(9L), "Снимок увидел чужую вставку");
        assertTrue(old.contains(new TestEntity(1L, "Stable_1")) || values(old).contains("Stable_1"),
                "Снимок увидел чужой апдейт");

        // Читатель после коммита: новый мир
        final XdStorageTransaction after = createTx("AFTER", 2000);
        final Collection<TestEntity> fresh = resourceCache.read(after,
                (IXdStoragePredicate<TestEntity>) e -> true);
        assertTrue(ids(fresh).contains(9L), "Новая TX не видит закоммиченную вставку");
        assertFalse(ids(fresh).contains(3L), "Новая TX видит закоммиченное удаление");
        assertTrue(values(fresh).contains("Committed_1"), "Новая TX не видит закоммиченный апдейт");
    }

    // =========================================================================
    // СОБСТВЕННЫЕ ЗАПИСИ В ОДНОЙ ТРАНЗАКЦИИ (между модификациями)
    // =========================================================================

    @Test
    @DisplayName("Свои записи: insert/update/delete видны самой транзакции между модификациями")
    public void ownWritesVisibleBetweenModifications() throws Exception {
        final XdStorageTransaction tx = createTx("OWN", 1000);
        resourceCache.insert(new TestEntity(9L, "Mine_9"), tx);

        // Чтение после вставки
        TestEntity e = (TestEntity) resourceCache.read(9L, tx);
        assertNotNull(e, "Собственная вставка невидима для себя!");
        assertEquals("Mine_9", e.getValue());

        // Обновление и чтение
        resourceCache.update(new TestEntity(9L, "Mine_9_v2"), tx);
        e = (TestEntity) resourceCache.read(9L, tx);
        assertEquals("Mine_9_v2", e.getValue(), "Собственный апдейт невидим для себя!");

        // Предикатный скан видит свои записи
        assertEquals(1, resourceCache.read(tx,
                (IXdStoragePredicate<TestEntity>) x -> "Mine_9_v2".equals(x.getValue())).size());

        // Удаление и чтение
        resourceCache.delete(9L, tx);
        assertNull(resourceCache.read(9L, tx), "Собственное удаление не скрыло запись от себя!");
    }

    @Test
    @DisplayName("Identity Map: повторное чтение по id в одной TX возвращает ту же инстанцию")
    public void identityMapSameInstance() throws Exception {
        final XdStorageTransaction tx = createTx("ID", 1000);
        final Object first = resourceCache.read(1L, tx);
        final Object second = resourceCache.read(1L, tx);
        assertSame(first, second, "Identity Map нарушена: два чтения дали разные инстанции");
    }

    @Test
    @DisplayName("readUnsafe: транзитные мутации видны владельцу до коммита")
    public void readUnsafeShowsOwnTransit() throws Exception {
        final XdStorageTransaction tx = createTx("UNSAFE", 1000);
        resourceCache.insert(new TestEntity(9L, "Transit_9"), tx);
        resourceCache.update(new TestEntity(1L, "Transit_1"), tx);
        resourceCache.delete(3L, tx);

        final Collection<TestEntity> unsafe = resourceCache.readUnsafe(tx);
        assertTrue(values(unsafe).contains("Transit_9"), "readUnsafe не видит собственную вставку");
        assertTrue(values(unsafe).contains("Transit_1"), "readUnsafe не видит собственный апдейт");
        assertFalse(values(unsafe).contains("Stable_1"), "readUnsafe показывает старую версию вместо апдейта");
    }

    // =========================================================================
    // КОНСИСТЕНТНОСТЬ ССЫЛОК (readByReference)
    // =========================================================================

    @Test
    @DisplayName("Ссылка: readByReference наполняет прокси данными записи")
    public void referenceFillFromRecord() throws Exception {
        final XdStorageTransaction tx = createTx("REF", 1000);
        final TestEntity proxy = new TestEntity(2L, null);
        resourceCache.readByReference(proxy, tx);
        assertEquals("Stable_2", proxy.getValue(), "Ссылка не наполнилась данными записи");
    }

    @Test
    @DisplayName("Ссылка: повторное readByReference консистентно")
    public void referenceConsistentAcrossReads() throws Exception {
        final XdStorageTransaction tx = createTx("REF2", 1000);
        final TestEntity p1 = new TestEntity(3L, null);
        final TestEntity p2 = new TestEntity(3L, null);
        resourceCache.readByReference(p1, tx);
        resourceCache.readByReference(p2, tx);
        assertEquals(p1.getValue(), p2.getValue(), "Два чтения по ссылке разошлись");
    }

    @Test
    @DisplayName("Ссылка: чужой незакоммиченный апдейт не виден через readByReference")
    public void referenceIsolationFromForeignUpdate() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        resourceCache.update(new TestEntity(2L, "Dirty_2"), txA);

        final XdStorageTransaction txB = createTx("B", 1001);
        final TestEntity proxy = new TestEntity(2L, null);
        resourceCache.readByReference(proxy, txB);
        assertEquals("Stable_2", proxy.getValue(), "Грязный апдейт просочился через ссылку!");
    }

    @Test
    @DisplayName("Ссылка: отсутствующий объект обязан бросить исключение")
    public void referenceMissingThrows() {
        final XdStorageTransaction tx = createTx("REF-MISS", 1000);
        assertThrows(Exception.class, () -> resourceCache.readByReference(new TestEntity(999L, null), tx));
    }

    // =========================================================================
    // Изоляция отката: rollback снимает все мутации транзакции
    // =========================================================================

    @Test
    @DisplayName("Rollback: после отката чужие сканы и ссылки видят только стабильный мир")
    public void rollbackRestoresStableWorld() throws Exception {
        final XdStorageTransaction tx = createTx("RB", 1000);
        resourceCache.insert(new TestEntity(9L, "RB_9"), tx);
        resourceCache.update(new TestEntity(1L, "RB_1"), tx);
        resourceCache.delete(2L, tx);
        resourceCache.rollback(tx);

        final XdStorageTransaction reader = createTx("R", 1001);
        final Collection<TestEntity> scan = resourceCache.read(reader,
                (IXdStoragePredicate<TestEntity>) e -> true);
        assertEquals(3, ids(scan).size(), "Rollback не восстановил состав");
        assertTrue(values(scan).contains("Stable_1"), "Rollback не восстановил значение");
        assertFalse(values(scan).contains("RB_9"), "Rollback не убрал вставку");

        final TestEntity proxy = new TestEntity(2L, null);
        resourceCache.readByReference(proxy, reader);
        assertEquals("Stable_2", proxy.getValue());
    }

    // =========================================================================
    // Хелперы
    // =========================================================================

    private XdStorageTransactionResourceChanges mockResource() {
        return new XdStorageTransactionResourceChanges(mock(io.github.xdstorage.resource.IXdStorageResourceObject
                .class));
    }

    private List<Long> ids(final Collection<TestEntity> entities) {
        final List<Long> ids = new ArrayList<>();
        for (final TestEntity e : entities) ids.add(e.getId());
        return ids;
    }

    private List<String> values(final Collection<TestEntity> entities) {
        final List<String> values = new ArrayList<>();
        for (final TestEntity e : entities) values.add(e.getValue());
        return values;
    }
}
