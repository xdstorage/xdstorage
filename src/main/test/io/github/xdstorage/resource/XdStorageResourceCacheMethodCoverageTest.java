package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Покрытие ВСЕХ публичных методов XdStorageResourceCache, не закрытых
 * фантомными/RC/инвариантными наборами: watch, findUnidentified,
 * rollbackFailedCommit, отложенная вставка с генерацией id (observable),
 * fillAndCloneCacheOnlyIfNotExists, счетчики и служебные методы.
 */
@DisplayName("Покрытие методов XdStorageResourceCache: watch, findUnidentified, rollbackFailedCommit")
public class XdStorageResourceCacheMethodCoverageTest extends AbstractXdStorageResourceCacheTest {

    /** Живая реализация observable-обертки с отложенной генерацией id. */
    public static class DeferredIdEntity implements io.github.xdstorage.observing.IXdStorageIdObservableWrapper {
        // Внутренний объект для getWrappedObjectOrSameObject: ищется поле 'object';
        // сам inner НЕ должен реализовывать IXdStorageIdObservableWrapper — иначе рекурсия
        public final Object object = new Object();
        private final List<XdStorageAbstractIdObserver> observers = new ArrayList<>();

        @Override
        public void addObserver(final XdStorageAbstractIdObserver observer) {
            observers.add(observer);
        }

        public void removeObserver(final XdStorageAbstractIdObserver observer) {
            observers.remove(observer);
        }

        public List<XdStorageAbstractIdObserver> getObservers() {
            return observers;
        }

        public void fireIdSet(final Object id) {
            for (final XdStorageAbstractIdObserver observer : new ArrayList<>(observers)) {
                observer.onNewIdIsSet(this, id);
            }
        }
    }

    @BeforeEach
    public void fillBase() {
        resourceCache.fillCache(Arrays.asList(
                new TestEntity(1L, "Stable_1"),
                new TestEntity(2L, "Stable_2")
        ));
    }

    // =========================================================================
    // watch(): трансляция снимка в коллбэк
    // =========================================================================

    @Test
    @DisplayName("watch(): коллбэк получает все закешированные записи")
    public void watchDeliversAllCachedEntries() throws Exception {
        final XdStorageTransaction tx = createTx("W", 1000);
        final List<Object> seen = new ArrayList<>();

        resourceCache.watch(tx, new IXdStorageWatcher<Object>() {
            @Override
            public void watch(final Object object) {
                seen.add(object);
            }
        });

        assertEquals(2, seen.size(), "watch обязан передать все записи кэша");
    }

    @Test
    @DisplayName("watch(): собственная незакоммиченная вставка видна своей TX")
    public void watchOwnInsertVisibleToOwnTx() throws Exception {
        final XdStorageTransaction tx = createTx("W-OWN", 1000);
        resourceCache.insert(new TestEntity(9L, "Mine_9"), tx);

        final List<String> values = new ArrayList<>();
        resourceCache.watch(tx, new IXdStorageWatcher<TestEntity>() {
            @Override
            public void watch(final TestEntity e) {
                values.add(e.getValue());
            }
        });

        assertTrue(values.contains("Mine_9"), "watch в своей TX не видит собственную вставку");
        assertTrue(values.contains("Stable_1"));
    }

    // =========================================================================
    // findUnidentified() + отложенная вставка с генерацией id
    // =========================================================================

    @Test
    @DisplayName("findUnidentified(): зарегистрированная обертка находится, чужие объекты — нет")
    public void findUnidentifiedFindsRegisteredWrapper() throws Exception {
        final XdStorageTransaction tx = createTx("DEF", 1000);
        final DeferredIdEntity deferred = new DeferredIdEntity();
        resourceCache.insert(deferred, tx);

        assertSame(deferred, resourceCache.findUnidentified(deferred, tx),
                "findUnidentified не нашел зарегистрированную обертку");
        assertNull(resourceCache.findUnidentified(new TestEntity(1L, "Stable_1"), tx),
                "findUnidentified вернул посторонний объект");
    }

    @Test
    @DisplayName("Отложенная вставка: onNewIdIsSet материализует запись с сгенерированным id")
    public void deferredInsertMaterializesOnIdSet() throws Exception {
        final XdStorageTransaction tx = createTx("DEF-ID", 1000);
        final DeferredIdEntity deferred = new DeferredIdEntity();
        resourceCache.insert(deferred, tx);

        assertFalse(resourceCache.hasObject(777L));
        deferred.fireIdSet(777L);
        assertTrue(resourceCache.hasObject(777L), "Отложенная вставка не материализовалась по id");

        // После материализации обертка перестает быть "неопознанной"
        assertNull(resourceCache.findUnidentified(deferred, tx),
                "Материализованная обертка осталась в неопознанных");
    }

    @Test
    @DisplayName("hasChanges/clear: контур отложенной вставки обслуживается")
    public void hasChangesAndClearForDeferredInsert() throws Exception {
        final XdStorageTransaction tx = createTx("DEF-CLR", 1000);
        resourceCache.insert(new DeferredIdEntity(), tx);
        assertTrue(resourceCache.hasChanges(tx), "Отложенная вставка не отразилась в hasChanges");

        resourceCache.clear(tx);
        assertFalse(resourceCache.hasChanges(tx), "clear не вычистил контур транзакции");
    }

    // =========================================================================
    // rollbackFailedCommit(): откат провалившегося двухфазного коммита
    // =========================================================================

    @Test
    @DisplayName("rollbackFailedCommit(): вставка откатывается удалением записи")
    public void rollbackFailedCommitInsert() throws Exception {
        final XdStorageTransaction tx = createTx("RFC-INS", 1000);
        resourceCache.insert(new TestEntity(9L, "Ins_9"), tx);

        final XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(io.github.xdstorage.resource.IXdStorageResourceObject
                        .class));
        resourceCache.prepareCommit(tx, collector);

        resourceCache.rollbackFailedCommit(tx, collector.getChangesObjects());

        assertFalse(resourceCache.hasObject(9L), "Провалившаяся вставка осталась в кэше");
        assertTrue(resourceCache.hasObject(1L), "Соседние записи не должны пострадать");
    }

    @Test
    @DisplayName("rollbackFailedCommit(): апдейт откатывается к прежнему значению")
    public void rollbackFailedCommitUpdate() throws Exception {
        final XdStorageTransaction tx = createTx("RFC-UPD", 1000);
        resourceCache.update(new TestEntity(1L, "Updated_1"), tx);

        final XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(io.github.xdstorage.resource.IXdStorageResourceObject
                        .class));
        resourceCache.prepareCommit(tx, collector);

        resourceCache.rollbackFailedCommit(tx, collector.getChangesObjects());

        final TestEntity restored = (TestEntity) resourceCache.read(1L, createTx("RFC-R", 2000));
        assertNotNull(restored, "Запись потеряна после отката коммита");
        assertEquals("Stable_1", restored.getValue(), "Апдейт не откатился к прежнему значению");
    }

    @Test
    @DisplayName("rollbackFailedCommit(): удаление откатывается восстановлением записи")
    public void rollbackFailedCommitDelete() throws Exception {
        final XdStorageTransaction tx = createTx("RFC-DEL", 1000);
        resourceCache.delete(2L, tx);

        final XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(io.github.xdstorage.resource.IXdStorageResourceObject
                        .class));
        resourceCache.prepareCommit(tx, collector);

        resourceCache.rollbackFailedCommit(tx, collector.getChangesObjects());

        final TestEntity restored = (TestEntity) resourceCache.read(2L, createTx("RFC-R2", 2000));
        assertNotNull(restored, "Удаленная запись не восстановлена откатом коммита");
        assertEquals("Stable_2", restored.getValue());
    }

    // =========================================================================
    // fillAndCloneCacheOnlyIfNotExists(): идемпотентность и изоляция клонов
    // =========================================================================

    @Test
    @DisplayName("fillAndCloneCacheOnlyIfNotExists(): наполняет, клоны изолированы и идемпотентны")
    public void fillAndCloneOnlyIfNotExists() throws Exception {
        final XdStorageTransaction tx = createTx("FAC", 1000);
        final List<TestEntity> source = Arrays.asList(
                new TestEntity(11L, "Clone_11"),
                new TestEntity(12L, "Clone_12")
        );
        final Collection<TestEntity> first = new ArrayList<>();
        resourceCache.fillAndCloneCacheOnlyIfNotExists(source, first, tx);
        assertEquals(2, first.size(), "Первое наполнение");

        // Повторный вызов наполняет аккумулятор вызывающего; записи кэша не дублируются
        final Collection<TestEntity> second = new ArrayList<>();
        resourceCache.fillAndCloneCacheOnlyIfNotExists(source, second, tx);
        assertEquals(2, second.size(), "Повторное наполнение вернуло не все записи");
        assertEquals(new ArrayList<>(first).get(0), new ArrayList<>(second).get(0),
                "Клон не переиспользован из транзитной карты");
        assertTrue(resourceCache.hasObject(11L) && resourceCache.hasObject(12L));
    }

    // =========================================================================
    // Счетчики и служебные методы
    // =========================================================================

    @Test
    @DisplayName("getObjectsCount/hasObject: счетчики отражают наполнение кэша")
    public void countersAndHasObject() throws Exception {
        assertEquals(2, resourceCache.getObjectsCount());
        assertTrue(resourceCache.hasObject(1L));
        assertFalse(resourceCache.hasObject(999L));
    }
}
