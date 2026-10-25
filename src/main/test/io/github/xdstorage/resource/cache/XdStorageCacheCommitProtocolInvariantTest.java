package io.github.xdstorage.resource.cache;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Инварианты протокола завершения транзакции {@link XdStorageCacheCommitProtocol}:
 * двухфазный коммит (перманентность + очистка контуров), откат в ОБРАТНОМ порядке
 * журнала мутаций, инфраструктурный откат вставки (R-40) и восстановление после
 * упавшего коммита по коллекции собранных изменений.
 */
class XdStorageCacheCommitProtocolInvariantTest extends CacheInvariantTestSupport {

    private StubCacheContext context;
    private XdStorageCacheMutator mutator;
    private XdStorageCacheCommitProtocol protocol;

    @BeforeEach
    void setUp() {
        context = new StubCacheContext();
        mutator = new XdStorageCacheMutator(context);
        protocol = new XdStorageCacheCommitProtocol(context);
    }

    // =========================================================================
    // ДВУХФАЗНЫЙ КОММИТ
    // =========================================================================

    @Test
    void twoPhaseCommitMakesInsertPermanentAndClearsContours() throws Exception {
        TestEntity e1 = entity(1L, "a");
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(e1, tx);
        XdStorageTransactionResourceChanges collector = newCollector();

        protocol.prepareCommit(tx, collector);
        protocol.performCommit(tx, collector);
        protocol.commit(tx);

        XdStorageCacheRecord record = context.records.get(1L);
        assertTrue(record.isCommitedState());
        assertFalse(record.isInsertChange());
        assertEquals("a", ((TestEntity) record.getObject()).getValue());
        assertNull(record.getNewObject());
        assertNull(record.getOwnerTransaction(), "commit сопровождается unlock записи");

        assertFalse(context.workspace.hasChanges("t1"));
        assertNull(context.workspace.readObjects("t1"), "транзитный контур чистится при коммите");
        assertNull(context.workspace.peekUndoLog("t1"), "журнал отката не нужен — изменения стали постоянными");

        assertEquals(2, collector.getChangesObjects().size(), "каждая фаза кладет изменения в коллектор выписки");
    }

    @Test
    void commitWithoutBothPhasesThrowsAndConsumesWriteSet() throws Exception {
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(entity(1L, "a"), tx);

        XdStorageRuntimeException e = assertThrows(XdStorageRuntimeException.class, () -> protocol.commit(tx));
        assertTrue(e.getMessage().contains("Illegal state of record to commit"),
                "протокол не позволяет финализировать неподготовленную запись");

        assertNull(context.workspace.changes("t1"),
                "write-set изымается ДО итерации — частично незакрытая мутация не коммитится повторно");
        assertTrue(context.records.get(1L).isInsertChange(), "запись остается в незакоммиченном состоянии");
    }

    // =========================================================================
    // ОТКАТ ПО ЖУРНАЛУ
    // =========================================================================

    @Test
    void rollbackRestoresEveryMutatedRecordToReadState() throws Exception {
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(2L, "v2");
        XdStorageCacheRecord r1 = context.createReadRecord(v1);
        XdStorageCacheRecord r2 = context.createReadRecord(v2);
        context.records.put(1L, r1);
        context.records.put(2L, r2);

        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.update(entity(1L, "v1-upd"), tx);
        mutator.update(entity(2L, "v2-upd"), tx);

        Deque<XdStorageCacheRecord> journal = context.workspace.peekUndoLog("t1");
        Iterator<XdStorageCacheRecord> chronological = journal.iterator();
        assertSame(r1, chronological.next(), "журнал хранит порядок мутаций");
        assertSame(r2, chronological.next());

        protocol.rollback(tx);

        assertTrue(r1.isReadChange());
        assertSame(v1, r1.getObject());
        assertTrue(r2.isReadChange());
        assertSame(v2, r2.getObject());
        assertNull(r1.getOwnerTransaction());
        assertNull(r2.getOwnerTransaction());
        assertFalse(context.workspace.hasChanges("t1"));
        assertNull(context.workspace.peekUndoLog("t1"));
        assertNotNull(context.records.get(1L), "откат обновления не изымает запись из кэша");
    }

    @Test
    void bothPhasesSetFlagsOnEveryRecordInOrder() throws Exception {
        // CACHE2PC: обе фазы проходят по ВСЕМУ write-set — после prepare у каждой записи
        // firstPhase=true/secondPhase=false, после perform — оба флага; коллектор получает
        // по одной подаче на фазу на запись (двойная подача = механизм обновления).
        XdStorageCacheRecord r1 = context.createReadRecord(entity(1L, "a"));
        XdStorageCacheRecord r2 = context.createReadRecord(entity(2L, "b"));
        context.records.put(1L, r1);
        context.records.put(2L, r2);
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.update(entity(1L, "a-upd"), tx);
        mutator.update(entity(2L, "b-upd"), tx);
        XdStorageTransactionResourceChanges collector = newCollector();

        protocol.prepareCommit(tx, collector);

        assertFalse(r1.isCommitedState());
        assertFalse(r2.isCommitedState());
        assertEquals(2, collector.getChangesObjects().size(), "фаза 1: по подаче на запись");

        protocol.performCommit(tx, collector);

        assertEquals(4, collector.getChangesObjects().size(), "фаза 2: вторая подача на запись (обновление коллектора)");

        // обе фазы установлены — финализация делает записи перманентными
        protocol.commit(tx);
        assertTrue(r1.isCommitedState());
        assertTrue(r2.isCommitedState());
        assertTrue(context.records.get(1L).getObject() instanceof TestEntity);
    }

    @Test
    void legacyNoJournalContourStillRollsBackEveryRecord() throws Exception {
        // CACHE2PC: контур БЕЗ журнала отката достижим только из сторонних/моковых
        // транзакций (все транзакции движка пишут журнал при первой мутации). Симулируем
        // его: две мутации создают И карту, и журнал — журнал изымаем вручную, откат
        // обязан пройти по карте и вернуть обе записи в read-состояние.
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(2L, "v2");
        XdStorageCacheRecord r1 = context.createReadRecord(v1);
        XdStorageCacheRecord r2 = context.createReadRecord(v2);
        context.records.put(1L, r1);
        context.records.put(2L, r2);

        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.update(entity(1L, "v1-upd"), tx);
        mutator.update(entity(2L, "v2-upd"), tx);
        assertNotNull(context.workspace.peekUndoLog("t1"), "движковые мутации обязаны иметь журнал");

        context.workspace.removeUndoLog("t1"); // симуляция стороннего контура без журнала

        protocol.rollback(tx);

        assertTrue(r1.isReadChange());
        assertSame(v1, r1.getObject());
        assertTrue(r2.isReadChange());
        assertSame(v2, r2.getObject());
        assertFalse(context.workspace.hasChanges("t1"));
    }

    @Test
    void rollbackOfInsertRemovesDomainRecord() throws Exception {
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(entity(1L, "a"), tx);

        protocol.rollback(tx);

        assertNull(context.records.get(1L), "откат вставки доменного объекта изымает запись");
        assertFalse(context.workspace.hasChanges("t1"));
    }

    @Test
    void rollbackOfInfraInsertKeepsRecordServingLiveInstance() throws Exception {
        context.infrastructureCache = true;
        TestEntity live = entity(1L, "split-node");
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(live, tx);

        protocol.rollback(tx);

        XdStorageCacheRecord record = context.records.get(1L);
        assertNotNull(record, "R-40: откаченный сплит физически остается в структуре — запись продолжает обслуживание");
        assertTrue(record.isReadChange());
        assertEquals("undefined", record.getState());
        assertSame(live, record.getObject(), "инфра-материализация хранит живую инстанцию");
        assertNull(record.getOwnerTransaction());
    }

    // =========================================================================
    // ОТКАТ УПАВШЕГО КОММИТА
    // =========================================================================

    @Test
    void rollbackFailedCommitRestoresUpdatedRecord() throws Exception {
        TestEntity v1 = entity(1L, "v1");
        XdStorageCacheRecord record = context.createReadRecord(v1);
        context.records.put(1L, record);
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.update(entity(1L, "v2"), tx);

        List<XdStorageObjectChange> committed = new ArrayList<>();
        committed.add(new XdStorageObjectChange(XdStorageObjectOperationType.Update, 1L,
                entity(1L, "v1-clone"), entity(1L, "v2")));

        protocol.rollbackFailedCommit(tx, committed);

        assertTrue(record.isReadChange(), "восстановление возвращает запись в чистое чтение");
        assertSame(v1, record.getObject(), "объект restored из prevObject");
        assertNull(record.getOwnerTransaction());
        assertNotNull(context.records.get(1L));
    }

    @Test
    void rollbackFailedCommitRemovesInsertedRecord() throws Exception {
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(entity(1L, "a"), tx);

        List<XdStorageObjectChange> committed = new ArrayList<>();
        committed.add(new XdStorageObjectChange(XdStorageObjectOperationType.Insert, 1L, null, entity(1L, "a")));

        protocol.rollbackFailedCommit(tx, committed);

        assertNull(context.records.get(1L), "вставка, не доехавшая до диска, изымается из кэша");
    }

    @Test
    void rollbackFailedCommitRecreatesDeletedRecord() throws Exception {
        TestEntity v1 = entity(1L, "v1");
        XdStorageCacheRecord record = context.createReadRecord(v1);
        context.records.put(1L, record);
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.delete(1L, tx);

        List<XdStorageObjectChange> committed = new ArrayList<>();
        committed.add(new XdStorageObjectChange(XdStorageObjectOperationType.Delete, 1L, entity(1L, "v1-clone"), null));

        protocol.rollbackFailedCommit(tx, committed);

        XdStorageCacheRecord restored = context.records.get(1L);
        assertNotNull(restored, "удаление, не доехавшее до диска, разворачивается — запись воссоздается");
        assertTrue(restored.isReadChange());
        assertSame(v1, restored.getObject());
    }

    @Test
    void rollbackFailedCommitSkipsEmptyEntriesWithoutObjects() {
        XdStorageTransaction tx = mkTx("t1", 100L);
        List<XdStorageObjectChange> committed = new ArrayList<>();
        committed.add(new XdStorageObjectChange(XdStorageObjectOperationType.Insert, null, null, null));

        assertDoesNotThrow(() -> protocol.rollbackFailedCommit(tx, committed));
        assertTrue(context.records.isEmpty(), "элементы без old/new пропускаются защитным guard'ом");
    }

    // =========================================================================
    // ПОМОЩНИКИ
    // =========================================================================

    private XdStorageTransactionResourceChanges newCollector() {
        return new XdStorageTransactionResourceChanges(mock(IXdStorageResourceObject.class));
    }
}
