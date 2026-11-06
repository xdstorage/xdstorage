package io.github.xdstorage.resource.cache;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Инварианты контура мутаций {@link XdStorageCacheMutator}: создание insert-записей,
 * быстрый/полный пути обновления, изоляция чужих мутаций, инфраструктурная релаксация
 * (R-42) и контракты фантомов дерева (contract A, BTreeEntityTest), самозарвка (R-40).
 */
class XdStorageCacheMutatorInvariantTest extends CacheInvariantTestSupport {

    private StubCacheContext context;
    private XdStorageCacheMutator mutator;

    @BeforeEach
    void setUp() {
        context = new StubCacheContext();
        mutator = new XdStorageCacheMutator(context);
    }

    // =========================================================================
    // INSERT
    // =========================================================================

    @Test
    void insertCreatesLockedInsertRecordAndJournalsMutation() throws Exception {
        TestEntity e1 = entity(1L, "a");
        XdStorageTransaction tx = mkTx("t1", 100L);

        mutator.insertInternal(e1, tx);

        XdStorageCacheRecord record = context.records.get(1L);
        assertNotNull(record);
        assertTrue(record.isInsertChange());
        assertEquals("locked", record.getState());
        assertSame(tx, record.getOwnerTransaction());
        assertNotSame(e1, record.getNewObject(), "доменные объекты материализуются ИЗОЛИРОВАННОЙ копией");
        assertEquals("a", ((TestEntity) record.getNewObject()).getValue());
        assertSame(e1, context.workspace.readObjectsOf(tx).get(1L).object,
                "транзитный контур хранит исходный инстанс (источник выписки)");
        assertEquals(XdStorageCacheChange.insert, context.workspace.readObjectsOf(tx).get(1L).change);
        assertEquals(1, context.workspace.peekUndoLog("t1").size());
        assertTrue(context.workspace.hasChanges("t1"));
    }

    @Test
    void infraInsertKeepsLiveInstanceIdentity() throws Exception {
        context.infrastructureCache = true;
        TestEntity live = entity(1L, "tree");
        XdStorageTransaction tx = mkTx("t1", 100L);

        mutator.insertInternal(live, tx);

        assertSame(live, context.records.get(1L).getNewObject(),
                "ИДЕНТИЧНОСТЬ B+ ДЕРЕВА: инфра-объекты кэшируются живой инстанцией");
    }

    @Test
    void insertWithNullIdIsRejectedBeforeCacheAccess() {
        TestEntity noId = entity(null, "orphan");

        XdStorageException e = assertThrows(XdStorageException.class,
                () -> mutator.insertInternal(noId, mkTx("t1", 100L)));
        assertTrue(e.getMessage().contains("null id"));
        assertTrue(context.records.isEmpty(), "барьер null-ID стоит ДО обращения к реестру записей");
    }

    @Test
    void doubleInsertBySameTxIsRejected() throws Exception {
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(entity(1L, "a"), tx);

        XdStorageException e = assertThrows(XdStorageException.class,
                () -> mutator.insertInternal(entity(1L, "b"), tx));
        assertTrue(e.getMessage().contains("trying to double insert"));
    }

    @Test
    void insertOverForeignLiveRecordIsConcurrencyRejection() throws Exception {
        XdStorageTransaction owner = mkTx("owner", 100L);
        mutator.insertInternal(entity(1L, "a"), owner);

        XdStorageTransaction foreign = mkTx("foreign", 200L);
        XdStorageException e = assertThrows(XdStorageException.class,
                () -> mutator.insertInternal(entity(1L, "b"), foreign));
        assertTrue(e.getMessage().contains("concurrent modification"),
                "чужая незакоммиченная вставка не может быть перезаписана");
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    @Test
    void repeatedUpdateFastPathRefreshesRecordAndTransit() throws Exception {
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(entity(1L, "a"), tx);
        Object newObjectAfterInsert = context.records.get(1L).getNewObject();

        mutator.update(entity(1L, "b"), tx);

        XdStorageCacheRecord record = context.records.get(1L);
        assertNotSame(newObjectAfterInsert, record.getNewObject(), "быстрый путь обязан подменить контент новой материализацией");
        assertEquals("b", ((TestEntity) record.getNewObject()).getValue());
        assertTrue(record.isInsertChange(), "быстрый путь не двигает тип мутации (вставка остается вставкой)");

        XdStorageCacheObjectChange transit = context.workspace.readObjectsOf(tx).get(1L);
        assertEquals(XdStorageCacheChange.update, transit.change);
        assertEquals("b", ((TestEntity) transit.object).getValue());
        assertEquals(100L, transit.readTimestamp, "быстрый путь СОХРАНЯЕТ исходный readTimestamp (read-version check)");
    }

    @Test
    void updateOfReadRecordGoesThroughSlowPathPreservingReadTimestamp() throws Exception {
        TestEntity v1 = entity(1L, "v1");
        XdStorageTransaction tx = mkTx("t1", 200L);
        context.records.put(1L, context.createReadRecord(v1));
        context.workspace.readObjectsOf(tx).put(1L,
                new XdStorageCacheObjectChange(entity(1L, "stale-read"), XdStorageCacheChange.read, 555L));

        mutator.update(entity(1L, "v2"), tx);

        XdStorageCacheRecord record = context.records.get(1L);
        assertTrue(record.isUpdateChange());
        assertEquals("locked", record.getState());
        assertEquals("v2", ((TestEntity) record.getNewObject()).getValue());
        assertEquals(200L, record.getRecordTimestamp());
        XdStorageCacheObjectChange transit = context.workspace.readObjectsOf(tx).get(1L);
        assertEquals(555L, transit.readTimestamp, "readTimestamp от первого чтения — основа read-version проверки");
        assertSame(v1, record.getObject(), "пока мутация незакоммичена, object хранит прежнюю версию");
    }

    @Test
    void foreignUpdateOfUpdateLockedRecordTimesOut() throws Exception {
        // LATCH держится только update-записями: update() захватывает latch ДО markUpdate.
        // Insert-записи владение получают от фабрики (lock no-op), их защищает MVCC-арбитраж
        // (см. foreignUpdateOfUncommittedInfraInsertIsRefused_contractA).
        TestEntity v1 = entity(1L, "v1");
        context.records.put(1L, context.createReadRecord(v1));
        XdStorageTransaction owner = mkTx("owner", 100L);
        mutator.update(entity(1L, "v1-upd"), owner);

        XdStorageTransaction foreign = mkTx("foreign", 200L, 200L);
        XdStorageRuntimeException e = assertThrows(XdStorageRuntimeException.class,
                () -> mutator.update(entity(1L, "b"), foreign));
        assertTrue(e.getMessage().contains("locked for"), "живой владелец latch держит запись до коммита/отката");
    }

    @Test
    void insertLockedRecordDoesNotEngageTheLatch() throws Exception {
        // Инвариант latch: фабрика insert-записи пред-назначает владельца, поэтому lock
        // создателя — no-op и флаг latch НЕ ставится. Чужой lock() мгновенно перехватывает
        // latch И владение, после чего арбитраж видит foreign владельцем и разрешает
        // мутацию. Для инфраструктуры тот же сценарий отсекается контрактом фантомов
        // (см. foreignUpdateOfUncommittedInfraInsertIsRefused_contractA).
        XdStorageTransaction creator = mkTx("creator", 100L);
        mutator.insertInternal(entity(1L, "a"), creator);

        XdStorageTransaction foreign = mkTx("foreign", 200L);
        assertThrows(XdStorageRuntimeException.class, () -> mutator.update(entity(1L, "b"), foreign));

        XdStorageCacheRecord record = context.records.get(1L);
        assertFalse(record.isUpdateChange(), "перехват владельца открывает чужую мутацию незакоммиченной вставки");
        assertNotSame(foreign, record.getOwnerTransaction(), "владение не перешло к foreign вместе с latch");
        assertEquals("a", ((TestEntity) record.getNewObject()).getValue());
    }

    @Test
    void updateOfMissingDomainObjectThrows() {
        XdStorageTransaction tx = mkTx("t1", 100L);
        XdStorageException e = assertThrows(XdStorageException.class,
                () -> mutator.update(entity(42L, "ghost"), tx));
        assertTrue(e.getMessage().contains("does not exists"));
        assertTrue(context.insertDelegations.isEmpty());
    }

    @Test
    void updateOfMissingInfraObjectSelfHealsThroughPublicInsert() throws Exception {
        context.infrastructureCache = true;
        XdStorageTransaction tx = mkTx("t1", 100L);

        mutator.update(entity(42L, "structural-ghost"), tx);

        assertFalse(context.insertDelegations.isEmpty(), "R-40: инфраструктурная запись восстанавливается через публичную вставку");
        assertNotNull(context.records.get(42L), "после самозарвки запись обслуживается");
    }

    // =========================================================================
    // ИНФРАСТРУКТУРНАЯ РЕЛАКСАЦИЯ (R-42) / КОНТРАКТЫ ФАНТОМОВ
    // =========================================================================

    @Test
    void foreignUpdateOfUncommittedInfraInsertIsRefused_contractA() throws Exception {
        context.infrastructureCache = true;
        XdStorageTransaction creator = mkTx("creator", 100L);
        mutator.insertInternal(entity(1L, "lazy-tree"), creator);

        XdStorageTransaction foreign = mkTx("foreign", 200L);
        XdStorageException e = assertThrows(XdStorageException.class,
                () -> mutator.update(entity(1L, "stolen"), foreign));
        assertTrue(e.getMessage().contains("can not be updated"),
                "контракт фантомов: чужая tx не трогает дерево, еще не существующее в БД");
    }

    @Test
    void structuralNodesAreFullyRelaxedForForeignUpdates() throws Exception {
        context.infrastructureCache = true;
        context.structuralNodeCache = true;
        XdStorageTransaction creator = mkTx("creator", 100L);
        mutator.insertInternal(entity(1L, "node"), creator);

        mutator.update(entity(1L, "node-v2"), mkTx("descender", 200L));

        assertTrue(context.records.get(1L).isUpdateChange(),
                "R-42: ноды — внутренняя структура, структурная конкуренция сериализована write-локами дерева");
    }

    @Test
    void committedTreeAcceptsForeignUpdates() throws Exception {
        context.infrastructureCache = true;
        XdStorageTransaction creator = mkTx("creator", 100L);
        mutator.insertInternal(entity(1L, "tree"), creator);

        XdStorageCacheRecord record = context.records.get(1L);
        record.prepareCommit();
        record.performCommit();
        record.commit(creator);

        mutator.update(entity(1L, "tree-v2"), mkTx("foreign", 200L));
        assertTrue(record.isUpdateChange(), "R-42: у ЗАКОММИЧЕННОГО дерева релаксировано обновление");
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @Test
    void deleteOfOwnInsertRemovesDomainRecordButKeepsJournalPoint() throws Exception {
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(entity(1L, "a"), tx);

        mutator.delete(1L, tx);

        assertNull(context.records.get(1L), "вставка+удаление в одной tx: запись изымается из кэша");
        assertNull(context.workspace.readObjectsOf(tx).get(1L), "транзитная вставка вычищается");
        assertEquals(1, context.workspace.peekUndoLog("t1").size(),
                "точка отмены остается ОДНА (первая мутация) — обратный проигрыш журнала ее закроет");
    }

    @Test
    void deleteOfOwnInfraInsertKeepsRecordServing() throws Exception {
        context.infrastructureCache = true;
        XdStorageTransaction tx = mkTx("t1", 100L);
        mutator.insertInternal(entity(1L, "node"), tx);

        mutator.delete(1L, tx);

        XdStorageCacheRecord record = context.records.get(1L);
        assertNotNull(record, "R-40: живой инстанс может оставаться в структуре — запись продолжает обслуживание");
        assertTrue(record.isDeleteChange());
    }

    @Test
    void deleteOfReadRecordMarksDelete() throws Exception {
        TestEntity v1 = entity(1L, "v1");
        XdStorageTransaction tx = mkTx("t1", 100L);
        context.records.put(1L, context.createReadRecord(v1));

        mutator.delete(1L, tx);

        XdStorageCacheRecord record = context.records.get(1L);
        assertTrue(record.isDeleteChange());
        assertNull(record.getNewObject());
        assertSame(v1, record.getObject(), "пока удаление незакоммичено, объект хранится для чужих читателей");
        assertNull(context.workspace.readObjectsOf(tx).get(1L), "транзит чтения вычищается");
    }

    @Test
    void deleteOfMissingDomainObjectThrowsButInfraIsGhost() throws Exception {
        XdStorageTransaction tx = mkTx("t1", 100L);

        XdStorageException e = assertThrows(XdStorageException.class, () -> mutator.delete(42L, tx));
        assertTrue(e.getMessage().contains("does not exists"));

        context.infrastructureCache = true;
        mutator.delete(42L, tx); // R-42: сирота откатанного сплита = уже отсутствует
        assertTrue(context.records.isEmpty());
    }
}
