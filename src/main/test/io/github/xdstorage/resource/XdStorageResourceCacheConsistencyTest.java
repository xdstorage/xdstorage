package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Консистентностные тесты полного контура XdStorageResourceCache.
 * Закрывают методы и ветки, не покрытые остальными юнит-группами:
 * findUnidentified, rollbackFailedCommit, readUnsafe (полная семантика),
 * watch (изоляция и трансляция неидентифицированных объектов),
 * ошибочные ветки insert/update/delete и содержимое 2PC-коллектора.
 */
@DisplayName("Unit-Группа 5: Консистентность всех публичных контрактов XdStorageResourceCache")
public class XdStorageResourceCacheConsistencyTest extends AbstractXdStorageResourceCacheTest {

    // Честная живая обертка без ID для проверки контура неидентифицированных объектов
    public static class StubIdObservableWrapper implements IXdStorageIdObservableWrapper {
        public TestEntity object;
        public final List<XdStorageAbstractIdObserver> observers = new ArrayList<>();

        public StubIdObservableWrapper(TestEntity object) { this.object = object; }

        @Override
        public void addObserver(XdStorageAbstractIdObserver observer) { observers.add(observer); }
    }

    @BeforeEach
    public void fillBaseData() {
        resourceCache.fillCache(java.util.Arrays.asList(
                new TestEntity(1L, "Stable_1"),
                new TestEntity(2L, "Stable_2"),
                new TestEntity(3L, "Stable_3")
        ));
    }

    // =========================================================================
    // findUnidentified(): контур отложенных вставок без ID
    // =========================================================================

    @Test
    @DisplayName("findUnidentified() должен возвращать ту же инстанцию зарегистрированной обертки")
    public void testFindUnidentified_RegisteredWrapper_ShouldReturnSameInstance() throws Exception {
        XdStorageTransaction tx = createTx("TX-FIND-1", 1000L);
        StubIdObservableWrapper stub = new StubIdObservableWrapper(new TestEntity(null, "Lazy"));
        resourceCache.insert(stub, tx);

        Object found = resourceCache.findUnidentified(stub, tx);

        assertSame(stub, found, "findUnidentified() обязан вернуть идентичную инстанцию обертки той же транзакции!");
        // insert() + findUnidentified() — оба обязаны проходить через симметричный барьер критической секции
        verify(tx, times(2)).startCriticalSection();
        verify(tx, times(2)).finishCriticalSection();
    }

    @Test
    @DisplayName("findUnidentified() для чужой транзакции или POJO обязан вернуть null")
    public void testFindUnidentified_WrongTxOrPlainObject_ShouldReturnNull() throws Exception {
        XdStorageTransaction txOwner = createTx("TX-FIND-OWNER", 1000L);
        XdStorageTransaction txAlien = createTx("TX-FIND-ALIEN", 1001L);
        StubIdObservableWrapper stub = new StubIdObservableWrapper(new TestEntity(null, "Lazy"));
        resourceCache.insert(stub, txOwner);

        assertNull(resourceCache.findUnidentified(stub, txAlien),
                "Чужая транзакция не должна видеть чужие неидентифицированные объекты!");
        assertNull(resourceCache.findUnidentified(new TestEntity(1L, "Plain"), txOwner),
                "findUnidentified() для сырого POJO обязан возвращать null!");
    }

    @Test
    @DisplayName("hasChanges()/clear() должны корректно обслуживать контекст из одних неидентифицированных объектов")
    public void testHasChanges_UnidentifiedOnlyInsert_ShouldBeReportedAndPurgedByClear() throws Exception {
        XdStorageTransaction tx = createTx("TX-UNIDENT", 1000L);
        StubIdObservableWrapper stub = new StubIdObservableWrapper(new TestEntity(null, "Lazy"));
        resourceCache.insert(stub, tx);

        assertTrue(resourceCache.hasChanges(tx), "Отложенная вставка без ID обязана считаться изменением транзакции!");
        assertSame(stub, resourceCache.findUnidentified(stub, tx));

        resourceCache.clear(tx);

        assertFalse(resourceCache.hasChanges(tx), "clear() обязан вычищать контекст неидентифицированных объектов!");
        assertNull(resourceCache.findUnidentified(stub, tx),
                "После clear() обертка обязана исчезнуть из транзакционного контекста!");
    }

    // =========================================================================
    // readUnsafe(): грязное чтение своего транзакционного контура
    // =========================================================================

    @Test
    @DisplayName("readUnsafe() должен вычитывать собственный insert и скрывать собственный delete")
    public void testReadUnsafe_ShouldExposeOwnInsertAndHideOwnDelete() throws Exception {
        XdStorageTransaction tx = createTx("TX-UNSAFE-OWN", 1000L);
        resourceCache.insert(new TestEntity(4L, "Ins_4"), tx);
        resourceCache.delete(2L, tx);

        Collection<TestEntity> unsafe = resourceCache.readUnsafe(tx);

        assertEquals(3, unsafe.size(),
                "readUnsafe() должен видеть базу + собственный insert, но не собственный delete!");
        assertTrue(unsafe.stream().noneMatch(n -> n != null && Long.valueOf(2L).equals(n.getId())),
                "Собственное удаление обязано исчезнуть из грязного чтения владельца!");
        assertTrue(unsafe.stream().anyMatch(n -> n != null && "Ins_4".equals(n.getValue())),
                "Собственная незакоммиченная вставка обязана быть видна владельцу через readUnsafe()!");
    }

    @Test
    @DisplayName("readUnsafe() параллельной сессии должен видеть только закоммиченное состояние")
    public void testReadUnsafe_ShouldIsolateOtherSessionDirtyChanges() throws Exception {
        XdStorageTransaction txWriter = createTx("TX-UNSAFE-WRITER", 1000L);
        XdStorageTransaction txReader = createTx("TX-UNSAFE-READER", 1001L);
        resourceCache.update(new TestEntity(1L, "Dirty_1"), txWriter);

        Collection<TestEntity> unsafe = resourceCache.readUnsafe(txReader);

        assertTrue(unsafe.stream().anyMatch(n -> n != null && "Stable_1".equals(n.getValue())),
                "readUnsafe() чужой сессии не имеет права видеть грязный апдейт писателя!");
        assertTrue(unsafe.stream().noneMatch(n -> n != null && "Dirty_1".equals(n.getValue())),
                "Грязное значение обязано отсутствовать в unsafe-вычитке параллельной сессии!");
    }

    // =========================================================================
    // read(): изоляция незакоммиченных удалений и read-your-own-writes
    // =========================================================================

    @Test
    @DisplayName("read(id) и полный read(tx) должны переживать незакоммиченный delete параллельной сессии")
    public void testRead_UncommittedDeleteByOtherTx_ShouldStayVisibleForReader() throws Exception {
        XdStorageTransaction txWriter = createTx("TX-DEL-WRITER", 1000L);
        XdStorageTransaction txReader = createTx("TX-DEL-READER", 1001L);
        resourceCache.delete(2L, txWriter);

        TestEntity byId = (TestEntity) resourceCache.read(2L, txReader);
        Collection<TestEntity> fullScan = resourceCache.read(txReader);
        Collection<TestEntity> writerScan = resourceCache.read(txWriter);

        assertNotNull(byId, "Читатель обязан продолжать видеть объект, удаленный незакоммиченной транзакцией!");
        assertEquals("Stable_2", byId.getValue());
        assertEquals(3, fullScan.size(), "Полный снимок читателя обязан содержать все 3 записи!");
        assertEquals(2, writerScan.size(), "Снимок самого писателя обязан исключать его незакоммиченный delete!");
    }

    @Test
    @DisplayName("read() должен вычитывать собственные грязные апдейты (read-your-own-writes)")
    public void testRead_OwnDirtyUpdate_ShouldBeVisibleForOwner() throws Exception {
        XdStorageTransaction tx = createTx("TX-OWN-DIRTY", 1000L);
        resourceCache.update(new TestEntity(1L, "Dirty_1"), tx);

        TestEntity byId = (TestEntity) resourceCache.read(1L, tx);
        Collection<TestEntity> byPredicate = resourceCache.read(tx,
                (IXdStoragePredicate<TestEntity>) node -> "Dirty_1".equals(node.getValue()));

        assertEquals("Dirty_1", byId.getValue(), "Плоское чтение обязано видеть собственный незакоммиченный апдейт!");
        assertEquals(1, byPredicate.size(), "Предикатное чтение обязано видеть собственный незакоммиченный апдейт!");
    }

    // =========================================================================
    // watch(): трансляция событий и snapshot-изоляция
    // =========================================================================

    @Test
    @DisplayName("watch() должен транслировать неидентифицированные обертки в коллбэк той же инстанцией")
    public void testWatch_ShouldForwardUnidentifiedWrappersAsIs() throws Exception {
        XdStorageTransaction tx = createTx("TX-WATCH-UNIDENT", 1000L);
        StubIdObservableWrapper stub = new StubIdObservableWrapper(new TestEntity(null, "Lazy"));
        resourceCache.insert(stub, tx);
        List<Object> watched = new ArrayList<>();

        resourceCache.watch(tx, new IXdStorageWatcher<Object>() {
            @Override
            public void watch(Object wrappedObject) { watched.add(wrappedObject); }
        });

        assertEquals(4, watched.size(), "watch() обязан транслировать 3 кэш-записи + 1 неидентифицированную обертку!");
        assertTrue(watched.contains(stub), "Неидентифицированная обертка обязана прийти в коллбэк без изменений!");
    }

    @Test
    @DisplayName("watch() должен снимать стабильный снимок, не видя грязных апдейтов писателя")
    public void testWatch_ShouldSeeCommittedSnapshotOnly() throws Exception {
        XdStorageTransaction txWriter = createTx("TX-WATCH-WRITER", 1000L);
        XdStorageTransaction txReader = createTx("TX-WATCH-READER", 1001L);
        resourceCache.update(new TestEntity(1L, "Dirty_1"), txWriter);
        List<String> watchedValues = new ArrayList<>();

        resourceCache.watch(txReader, new IXdStorageWatcher<TestEntity>() {
            @Override
            public void watch(TestEntity wrappedObject) { watchedValues.add(wrappedObject.getValue()); }
        });

        assertEquals(3, watchedValues.size());
        assertTrue(watchedValues.contains("Stable_1"), "watch() обязан видеть закоммиченное значение писателя!");
        assertFalse(watchedValues.contains("Dirty_1"), "watch() не имеет права транслировать грязные апдейты!");
    }

    // =========================================================================
    // readByReference(): ошибочная ветка отсутствующего объекта
    // =========================================================================

    @Test
    @DisplayName("readByReference() для отсутствующего ID обязан бросить XdStorageException")
    public void testReadByReference_MissingObject_ShouldThrowException() {
        XdStorageTransaction tx = createTx("TX-REF-MISS", 1000L);

        XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> resourceCache.readByReference(new TestEntity(999L, null), tx));
        assertTrue(thrown.getMessage().contains("cannot load by reference"));
    }

    // =========================================================================
    // insert()/update()/delete(): ошибочные и конфликтные барьеры
    // =========================================================================

    @Test
    @DisplayName("insert() объекта с null ID обязан бросить исключение")
    public void testInsert_NullId_ShouldThrowException() {
        XdStorageTransaction tx = createTx("TX-INS-NULL", 1000L);

        XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> resourceCache.insert(new TestEntity(null, "NoId"), tx));
        assertTrue(thrown.getMessage().contains("null id"));
    }

    @Test
    @DisplayName("insert() в удаленную этой же TX запись обязан бросить исключение")
    public void testInsert_OnRecordDeletedBySameTx_ShouldThrowException() throws Exception {
        XdStorageTransaction tx = createTx("TX-INS-DELETED", 1000L);
        resourceCache.delete(2L, tx);

        XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> resourceCache.insert(new TestEntity(2L, "Zombie"), tx));
        assertTrue(thrown.getMessage().contains("trying to insert deleted object"));
    }

    @Test
    @DisplayName("insert() существующего закоммиченного ID из чужой TX обязан бросить concurrent modification")
    public void testInsert_ExistingCommittedId_FromOtherTx_ShouldThrowException() {
        XdStorageTransaction tx = createTx("TX-INS-EXIST", 1000L);

        XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> resourceCache.insert(new TestEntity(1L, "Duplicate"), tx));
        assertTrue(thrown.getMessage().contains("concurrent modification"));
    }

    @Test
    @DisplayName("update() записи, удаленной этой же TX, обязан бросить исключение")
    public void testUpdate_AfterOwnDelete_ShouldThrowException() throws Exception {
        XdStorageTransaction tx = createTx("TX-UPD-DELETED", 1000L);
        resourceCache.delete(2L, tx);

        XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> resourceCache.update(new TestEntity(2L, "UpdateDeleted"), tx));
        assertTrue(thrown.getMessage().contains("was deleted this transaction"));
    }

    @Test
    @DisplayName("delete() дважды по одной записи в рамках одной TX обязан бросить исключение")
    public void testDelete_DoubleDeleteInSameTx_ShouldThrowException() throws Exception {
        XdStorageTransaction tx = createTx("TX-DEL-DOUBLE", 1000L);
        resourceCache.delete(2L, tx);

        XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> resourceCache.delete(2L, tx));
        assertTrue(thrown.getMessage().contains("trying to double delete"));
    }

    @Test
    @DisplayName("delete() собственной вставки обязан физически изъять запись, в том числе после rollback")
    public void testDelete_OwnInsert_ShouldPhysicallyEvictRecord() throws Exception {
        XdStorageTransaction tx = createTx("TX-DEL-OWN-INS", 1000L);
        resourceCache.insert(new TestEntity(7L, "Ephemeral"), tx);
        assertTrue(resourceCache.hasObject(7L));

        resourceCache.delete(7L, tx);
        assertFalse(resourceCache.hasObject(7L), "delete() собственной вставки обязан изымать запись из кэша целиком!");

        resourceCache.rollback(tx);
        assertFalse(resourceCache.hasObject(7L), "Откат не должен воскрешать удаленную собственную вставку!");
    }

    // =========================================================================
    // fillAndCloneCacheOnlyIfNotExists(): контракт Identity Map
    // =========================================================================

    @Test
    @DisplayName("fillAndCloneCacheOnlyIfNotExists() при повторном вызове обязан переиспользовать клон (Identity)")
    public void testFillAndClone_SecondCall_ShouldReuseMaterializedClone() throws Exception {
        XdStorageTransaction tx = createTx("TX-FILL-IDENT", 1000L);
        TestEntity node = new TestEntity(10L, "Bulk");
        List<TestEntity> first = new ArrayList<>();
        List<TestEntity> second = new ArrayList<>();

        resourceCache.fillAndCloneCacheOnlyIfNotExists(Collections.singletonList(node), first, tx);
        resourceCache.fillAndCloneCacheOnlyIfNotExists(Collections.singletonList(node), second, tx);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertSame(first.get(0), second.get(0),
                "Повторное наполнение обязано переиспользовать уже материализованный клон!");
    }

    // =========================================================================
    // 2PC: содержимое коллектора prepareCommit/performCommit и финальная видимость
    // =========================================================================

    @Test
    @DisplayName("prepareCommit() обязан собрать в коллектор консистентные Insert/Update/Delete изменения")
    public void testPrepareCommit_CollectorContents_ShouldReflectAllOperationTypes() throws Exception {
        XdStorageTransaction tx = createTx("TX-2PC", 1000L);
        resourceCache.insert(new TestEntity(4L, "Ins_4"), tx);
        resourceCache.update(new TestEntity(1L, "Upd_1"), tx);
        resourceCache.delete(2L, tx);

        XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(XdStorageResource.class));
        resourceCache.prepareCommit(tx, collector);

        List<XdStorageObjectChange> prepared = new ArrayList<>(collector.getChangesObjects());
        assertEquals(3, prepared.size(), "Коллектор обязан получить ровно 3 изменения!");

        XdStorageObjectChange ins = findByType(prepared, XdStorageObjectOperationType.Insert);
        XdStorageObjectChange upd = findByType(prepared, XdStorageObjectOperationType.Update);
        XdStorageObjectChange del = findByType(prepared, XdStorageObjectOperationType.Delete);

        assertNotNull(ins); assertNotNull(upd); assertNotNull(del);

        assertEquals(4L, ins.id);
        assertNull(ins.oldObject, "Insert обязан иметь null oldObject!");
        assertEquals("Ins_4", ((TestEntity) ins.newObject).getValue());

        assertEquals(1L, upd.id);
        assertEquals("Stable_1", ((TestEntity) upd.oldObject).getValue(),
                "Update обязан нести оригинал как oldObject!");
        assertEquals("Upd_1", ((TestEntity) upd.newObject).getValue());

        assertEquals(2L, del.id);
        assertEquals("Stable_2", ((TestEntity) del.oldObject).getValue());
        assertNull(del.newObject, "Delete обязан иметь null newObject!");
    }

    @Test
    @DisplayName("Полный цикл 2PC обязан зафиксировать insert/update и скрыть delete для новых транзакций")
    public void testTwoPhaseCommit_FinalVisibleState_ShouldBeConsistent() throws Exception {
        XdStorageTransaction tx = createTx("TX-2PC-FINAL", 1000L);
        resourceCache.insert(new TestEntity(4L, "Ins_4"), tx);
        resourceCache.update(new TestEntity(1L, "Upd_1"), tx);
        resourceCache.delete(2L, tx);

        XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(XdStorageResource.class));
        resourceCache.prepareCommit(tx, collector);
        resourceCache.performCommit(tx, collector);
        resourceCache.commit(tx);

        XdStorageTransaction freshReader = createTx("TX-2PC-READER", 2000L);
        Collection<TestEntity> snapshot = resourceCache.read(freshReader);

        assertEquals(3, snapshot.size(), "После коммита читатель обязан видеть 3 записи: update, stable, insert!");
        assertNull(resourceCache.read(2L, freshReader),
                "Закоммиченный delete обязан скрыть запись для новых транзакций!");
        assertEquals("Upd_1", ((TestEntity) resourceCache.read(1L, freshReader)).getValue());
        assertEquals("Ins_4", ((TestEntity) resourceCache.read(4L, freshReader)).getValue());
        assertFalse(resourceCache.hasChanges(tx), "После коммита транзакционный контур обязан быть пуст!");
    }

    // =========================================================================
    // rollbackFailedCommit(): восстановление после провала второй фазы 2PC
    // =========================================================================

    @Test
    @DisplayName("rollbackFailedCommit() после провала коммита вставки обязан изъять запись из кэша")
    public void testRollbackFailedCommit_AfterInsert_ShouldEvictRecord() throws Exception {
        XdStorageTransaction tx = createTx("TX-RFC-INS", 1000L);
        resourceCache.insert(new TestEntity(4L, "Ins_4"), tx);

        XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(XdStorageResource.class));
        resourceCache.prepareCommit(tx, collector);

        resourceCache.rollbackFailedCommit(tx, collector.getChangesObjects());

        assertFalse(resourceCache.hasObject(4L), "Провал коммита вставки обязан полностью изъять запись!");
        assertNull(resourceCache.read(4L, createTx("TX-RFC-INS-R", 2000L)));
        assertFalse(resourceCache.hasChanges(tx));
    }

    @Test
    @DisplayName("rollbackFailedCommit() после провала коммита удаления обязан восстановить объект")
    public void testRollbackFailedCommit_AfterDelete_ShouldRestoreObject() throws Exception {
        XdStorageTransaction tx = createTx("TX-RFC-DEL", 1000L);
        resourceCache.delete(2L, tx);

        XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(XdStorageResource.class));
        resourceCache.prepareCommit(tx, collector);

        resourceCache.rollbackFailedCommit(tx, collector.getChangesObjects());

        TestEntity restored = (TestEntity) resourceCache.read(2L, createTx("TX-RFC-DEL-R", 2000L));
        assertNotNull(restored, "Провал коммита удаления обязан вернуть объект в кэш!");
        assertEquals("Stable_2", restored.getValue());
        assertFalse(resourceCache.hasChanges(tx));
    }

    @Test
    @DisplayName("rollbackFailedCommit() после провала коммита апдейта обязан откатить значение")
    public void testRollbackFailedCommit_AfterUpdate_ShouldRestorePreviousValue() throws Exception {
        XdStorageTransaction tx = createTx("TX-RFC-UPD", 1000L);
        resourceCache.update(new TestEntity(1L, "Upd_1"), tx);

        XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(XdStorageResource.class));
        resourceCache.prepareCommit(tx, collector);

        resourceCache.rollbackFailedCommit(tx, collector.getChangesObjects());

        TestEntity restored = (TestEntity) resourceCache.read(1L, createTx("TX-RFC-UPD-R", 2000L));
        assertNotNull(restored);
        assertEquals("Stable_1", restored.getValue(), "Провал коммита апдейта обязан вернуть оригинальное значение!");
        assertFalse(resourceCache.hasChanges(tx));
    }

    private static XdStorageObjectChange findByType(final Collection<XdStorageObjectChange> changes,
                                                    final XdStorageObjectOperationType type) {
        for (final XdStorageObjectChange change : changes) {
            if (change.type == type) return change;
        }
        return null;
    }
}
