package io.github.xdstorage.resource.cache;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R-58: стражи отката кэша записей.
 * 1. ЗОМБИ: поздний/гонный откат догонял уже идущий или завершенный коммит и
 *    инвертировал закоммиченную вставку (object = prevObject = null при
 *    state=committed) — читатели by-reference получали «видимую пустышку».
 *    rollback() обязан отказывать для записей в фазе коммита / закоммиченных.
 * 2. ДВОЙНОЙ КОММИТ: вложенный/повторный контур коммита потребляет флаги фаз
 *    первым проходом — повторный commit той же записи обязан пропускаться
 *    с WARN (данные уже постоянны), а не ронять весь коммит.
 */
class XdStorageCacheRollbackGuardTest extends CacheInvariantTestSupport {

    private StubCacheContext context;
    private XdStorageCacheMutator mutator;
    private XdStorageCacheCommitProtocol protocol;

    @BeforeEach
    void setUp() {
        context = new StubCacheContext();
        mutator = new XdStorageCacheMutator(context);
        protocol = new XdStorageCacheCommitProtocol(context);
    }

    @Test
    @DisplayName("rollback() отказывает для закоммиченной записи — зомби object=null невозможен")
    void rollbackRefusesCommittedRecord() {
        TestEntity e = entity(7L, "committed");
        XdStorageTransaction tx = mkTx("t-commit", 100L);
        XdStorageCacheRecord record = XdStorageCacheRecord.newInsertRecord(context, context.idField(), e, tx);

        record.prepareCommit();
        record.performCommit();
        record.commit(tx);

        assertTrue(record.isCommittedState());
        assertEquals("committed", ((TestEntity) record.getObject()).getValue());

        // Поздний/гонный откат (свидетель-транзакция не важна — это тот же контракт)
        record.rollback(tx);

        assertTrue(record.isCommittedState(), "Состояние закоммиченной записи обязано остаться committed");
        assertEquals("committed", ((TestEntity) record.getObject()).getValue(),
                "Откат НЕ имеет права инвертировать закоммиченную вставку — контент обязан сохраниться");
    }

    @Test
    @DisplayName("Откат НЕ закоммиченной вставки работает как прежде (очищает запись)")
    void rollbackStillCleansUncommittedInsert() {
        TestEntity e = entity(8L, "uncommitted");
        XdStorageTransaction tx = mkTx("t-plain", 200L);
        XdStorageCacheRecord record = XdStorageCacheRecord.newInsertRecord(context, context.idField(), e, tx);

        record.rollback(tx);

        assertTrue(!record.isCommittedState(), "Незакоммиченная вставка откатывается штатно");
        assertTrue(!record.isInsertChange(), "Признак вставки снят откатом");
    }

    @Test
    @DisplayName("Протокол коммита пропускает уже закоммиченную запись вместо падения всего коммита")
    void commitProtocolSkipsAlreadyCommittedRecord() throws Exception {
        TestEntity e = entity(9L, "double");
        XdStorageTransaction tx = mkTx("t-double", 300L);
        mutator.insertInternal(e, tx);

        final Object recordId = 9L;
        XdStorageCacheRecord record = context.records.get(recordId);
        assertEquals("double", ((TestEntity) record.getNewObject()).getValue());

        XdStorageTransactionResourceChanges collector =
                new XdStorageTransactionResourceChanges(mock(IXdStorageResourceObject.class));
        protocol.prepareCommit(tx, collector);
        protocol.performCommit(tx, collector);
        protocol.commit(tx);

        assertTrue(record.isCommittedState());

        // Двойной контур: запись снова в карте изменений (вложенный коммит), флаги фаз
        // уже потреблены первым проходом. Старый код ронял ВЕСЬ коммит "Illegal state".
        context.workspace().registerMutation(tx.getTransactionId(), recordId, record);

        assertDoesNotThrow(() -> protocol.commit(tx),
                "Повторная фиксация уже закоммиченной записи обязана пропускаться");
        assertTrue(record.isCommittedState(), "Запись обязана остаться закоммиченной");
        assertEquals("double", ((TestEntity) record.getObject()).getValue(),
                "Контент обязан пережить повторный коммит");
    }

    @Test
    @DisplayName("Коммит записи в НЕподготовленном состоянии по-прежнему громкий")
    void commitWithoutPhaseFlagsRemainsLoud() {
        TestEntity e = entity(10L, "broken");
        XdStorageTransaction tx = mkTx("t-broken", 400L);
        XdStorageCacheRecord record = XdStorageCacheRecord.newInsertRecord(context, context.idField(), e, tx);

        // Флаги фаз не выставлены — это реальная ошибка протокола, а не двойной коммит
        assertThrows(XdStorageRuntimeException.class, () -> record.commit(tx));
    }
}
