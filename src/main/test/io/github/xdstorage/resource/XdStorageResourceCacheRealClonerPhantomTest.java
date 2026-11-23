package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.factories.IXdStorageReferenceProvider;
import io.github.xdstorage.factories.XdStorageDefaultReferenceProvider;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.factories.XdStorageSmartCloner;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ТЕ ЖЕ инварианты фантомов/снимков/ссылок, что и в XdStorageResourceCachePhantomReadTest,
 * но с НАСТОЯЩИМИ XdStorageSmartCloner + XdStorageDefaultReferenceProvider вместо
 * стаба клонера: пер-транзакционные SimpleWrapper-прокси, реальный pack/unpack,
 * реестр ссылок. Проверяет, что MVCC-инварианты держатся на боевом клонировании.
 */
@DisplayName("Инварианты ResourceCache на реальном SmartCloner + ReferenceProvider")
public class XdStorageResourceCacheRealClonerPhantomTest extends AbstractXdStorageResourceCacheTest {

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();
        // Подменяем стаб клонера на боевой: SmartCloner + DefaultReferenceProvider
        final IXdStorageReferenceProvider provider =
                new XdStorageDefaultReferenceProvider(mockStorage);
        final IXdStorageCloner realCloner = new XdStorageSmartCloner(provider);
        when(mockLocator.getCloner()).thenReturn(realCloner);

        // Базовые записи заполняются ПОСЛЕ установки боевого клонера и создания кэша —
        // порядок @BeforeEach между методами одного класса не гарантирован JUnit5
        fillBaseData();
    }

    public void fillBaseData() {
        resourceCache.fillCache(java.util.Arrays.asList(
                new TestEntity(1L, "Stable_1"),
                new TestEntity(2L, "Stable_2"),
                new TestEntity(3L, "Stable_3")
        ));
    }

    private Collection<Object> roundTripScan(final XdStorageTransaction tx) throws Exception {
        return resourceCache.read(tx, (IXdStoragePredicate<Object>) e -> true);
    }

    // =========================================================================
    // ФАНТОМЫ
    // =========================================================================

    @Test
    @DisplayName("Фантом вставки: невидима в предикатном скане чужой TX (реальный клонер)")
    public void phantomInsertNotVisible() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        final XdStorageTransaction txB = createTx("B", 1001);
        resourceCache.insert(new TestEntity(9L, "Phantom"), txA);

        final Collection<Object> scan = roundTripScan(txB);
        assertEquals(3, scan.size(), "Фантом вставки просочился в чужой скан (реальный клонер)");
    }

    @Test
    @DisplayName("Фантом удаления: запись остается видимой в чужом скане")
    public void phantomDeleteStaysVisible() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        final XdStorageTransaction txB = createTx("B", 1001);
        resourceCache.delete(2L, txA);

        final Collection<Object> scan = roundTripScan(txB);
        assertEquals(3, scan.size(), "Незакоммиченное удаление видно чужому скану");
    }

    @Test
    @DisplayName("Фантом обновления: чужой грязный апдейт невидим, старое значение на месте")
    public void phantomUpdateOldValue() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        final XdStorageTransaction txB = createTx("B", 1001);
        resourceCache.update(new TestEntity(1L, "Dirty_1"), txA);

        int dirty = 0, stable = 0;
        for (final Object o : roundTripScan(txB)) {
            final TestEntity e = (TestEntity) io.github.xdstorage.utils.XdStorageObjectUtils
                    .getWrappedObjectOrSameObject(o);
            if ("Dirty_1".equals(e.getValue())) dirty++;
            if ("Stable_1".equals(e.getValue())) stable++;
        }
        assertEquals(0, dirty, "Грязное значение просочилось");
        assertEquals(1, stable, "Стабильное значение потеряно");
    }

    private XdStorageTransactionResourceChanges mockResource() {
        return new XdStorageTransactionResourceChanges(
                mock(io.github.xdstorage.resource.IXdStorageResourceObject.class));
    }

    // =========================================================================
    // СНИМКИ
    // =========================================================================

    @Test
    @DisplayName("Снимок: читатель до коммита видит старый мир, после — новый (реальный клонер)")
    public void snapshotBeforeAfterCommit() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        resourceCache.insert(new TestEntity(9L, "Committed_9"), txA);
        resourceCache.update(new TestEntity(1L, "Committed_1"), txA);
        resourceCache.delete(3L, txA);

        final XdStorageTransactionResourceChanges collector = mockResource();
        resourceCache.prepareCommit(txA, collector);
        resourceCache.performCommit(txA, collector);
        resourceCache.commit(txA);

        final XdStorageTransaction before = createTx("BEFORE", 999);
        int c9 = 0, c3 = 0, stable1 = 0;
        for (final Object o : roundTripScan(before)) {
            final TestEntity e = (TestEntity) io.github.xdstorage.utils.XdStorageObjectUtils
                    .getWrappedObjectOrSameObject(o);
            if (Long.valueOf(9L).equals(e.getId())) c9++;
            if (Long.valueOf(3L).equals(e.getId())) c3++;
            if ("Stable_1".equals(e.getValue())) stable1++;
        }
        assertEquals(0, c9, "Снимок увидел чужую вставку");
        assertEquals(1, c3, "Снимок потерял удаленную запись");
        assertEquals(1, stable1, "Снимок увидел чужой апдейт");

        final XdStorageTransaction after = createTx("AFTER", 2000);
        int n9 = 0, n3 = 0, committed1 = 0;
        for (final Object o : roundTripScan(after)) {
            final TestEntity e = (TestEntity) io.github.xdstorage.utils.XdStorageObjectUtils
                    .getWrappedObjectOrSameObject(o);
            if (Long.valueOf(9L).equals(e.getId())) n9++;
            if (Long.valueOf(3L).equals(e.getId())) n3++;
            if ("Committed_1".equals(e.getValue())) committed1++;
        }
        assertEquals(1, n9, "Новая TX не видит закоммиченную вставку");
        assertEquals(0, n3, "Новая TX видит закоммиченное удаление");
        assertEquals(1, committed1, "Новая TX не видит закоммиченный апдейт");
    }

    // =========================================================================
    // СВОИ ЗАПИСИ + IDENTITY
    // =========================================================================

    @Test
    @DisplayName("Свои записи: insert/update/delete видны самой TX между модификациями")
    public void ownWritesVisibleBetweenModifications() throws Exception {
        final XdStorageTransaction tx = createTx("OWN", 1000);
        resourceCache.insert(new TestEntity(9L, "Mine_9"), tx);

        TestEntity e = (TestEntity) resourceCache.read(9L, tx);
        assertNotNull(e, "Собственная вставка невидима для себя");
        assertEquals("Mine_9", e.getValue());

        resourceCache.update(new TestEntity(9L, "Mine_9_v2"), tx);
        e = (TestEntity) resourceCache.read(9L, tx);
        assertEquals("Mine_9_v2", e.getValue(), "Собственный апдейт невидим для себя");

        resourceCache.delete(9L, tx);
        assertNull(resourceCache.read(9L, tx), "Собственное удаление не скрыло запись");
    }

    @Test
    @DisplayName("Identity Map: повторное чтение по id — та же инстанция (реальный клонер)")
    public void identityMapSameInstance() throws Exception {
        final XdStorageTransaction tx = createTx("ID", 1000);
        final Object first = resourceCache.read(1L, tx);
        final Object second = resourceCache.read(1L, tx);
        assertNotNull(first);
        assertSame(first, second, "Identity Map нарушена на реальном клонере");
    }

    @Test
    @DisplayName("readUnsafe: собственные транзитные мутации видны до коммита")
    public void readUnsafeShowsOwnTransit() throws Exception {
        final XdStorageTransaction tx = createTx("UNSAFE", 1000);
        resourceCache.insert(new TestEntity(9L, "Transit_9"), tx);
        resourceCache.update(new TestEntity(1L, "Transit_1"), tx);
        resourceCache.delete(3L, tx);

        int t9 = 0, t1 = 0, stale1 = 0;
        for (final Object o : resourceCache.readUnsafe(tx)) {
            final TestEntity e = (TestEntity) io.github.xdstorage.utils.XdStorageObjectUtils
                    .getWrappedObjectOrSameObject(o);
            if ("Transit_9".equals(e.getValue())) t9++;
            if ("Transit_1".equals(e.getValue())) t1++;
            if ("Stable_1".equals(e.getValue())) stale1++;
        }
        assertEquals(1, t9, "readUnsafe не видит собственную вставку");
        assertEquals(1, t1, "readUnsafe не видит собственный апдейт");
        assertEquals(0, stale1, "readUnsafe показывает устаревшую версию вместо апдейта");
    }

    // =========================================================================
    // ССЫЛКИ (readByReference через реальный fillAndWrap)
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
    @DisplayName("Ссылка: повторное чтение консистентно")
    public void referenceConsistentAcrossReads() throws Exception {
        final XdStorageTransaction tx = createTx("REF2", 1000);
        final TestEntity p1 = new TestEntity(3L, null);
        final TestEntity p2 = new TestEntity(3L, null);
        resourceCache.readByReference(p1, tx);
        resourceCache.readByReference(p2, tx);
        assertEquals(p1.getValue(), p2.getValue(), "Два чтения по ссылке разошлись");
    }

    @Test
    @DisplayName("Ссылка: чужой незакоммиченный апдейт невидим через readByReference")
    public void referenceIsolationFromForeignUpdate() throws Exception {
        final XdStorageTransaction txA = createTx("A", 1000);
        resourceCache.update(new TestEntity(2L, "Dirty_2"), txA);

        final XdStorageTransaction txB = createTx("B", 1001);
        final TestEntity proxy = new TestEntity(2L, null);
        resourceCache.readByReference(proxy, txB);
        assertEquals("Stable_2", proxy.getValue(), "Грязный апдейт просочился через ссылку");
    }

    // =========================================================================
    // ROLLBACK
    // =========================================================================

    @Test
    @DisplayName("Rollback: восстанавливает стабильный мир для сканов и ссылок")
    public void rollbackRestoresStableWorld() throws Exception {
        final XdStorageTransaction tx = createTx("RB", 1000);
        resourceCache.insert(new TestEntity(9L, "RB_9"), tx);
        resourceCache.update(new TestEntity(1L, "RB_1"), tx);
        resourceCache.delete(2L, tx);
        resourceCache.rollback(tx);

        final XdStorageTransaction reader = createTx("R", 1001);
        int c9 = 0, stable1 = 0, c2 = 0;
        for (final Object o : roundTripScan(reader)) {
            final TestEntity e = (TestEntity) io.github.xdstorage.utils.XdStorageObjectUtils
                    .getWrappedObjectOrSameObject(o);
            if (Long.valueOf(9L).equals(e.getId())) c9++;
            if ("Stable_1".equals(e.getValue())) stable1++;
            if (Long.valueOf(2L).equals(e.getId())) c2++;
        }
        assertEquals(0, c9, "Rollback не убрал вставку");
        assertEquals(1, stable1, "Rollback не восстановил значение");
        assertEquals(1, c2, "Rollback не восстановил удаленную запись");

        final TestEntity proxy = new TestEntity(2L, null);
        resourceCache.readByReference(proxy, reader);
        assertEquals("Stable_2", proxy.getValue());
    }
}
