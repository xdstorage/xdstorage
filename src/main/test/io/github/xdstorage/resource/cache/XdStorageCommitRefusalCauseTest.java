package io.github.xdstorage.resource.cache;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * RBCOMMIT (docs/tasks/2.1.0/45): инварианты ЗАВЕРШЕНИЯ КОММИТА на уровне записи и
 * протокола — отказ «Illegal state of record to commit» несет идентичность записи и
 * первую причину отравления транзакции, зомби-защита отказывается публиковать запись,
 * чей отложенный контент обнулен компенсирующим откатом (state=committed + null content —
 * документированный «cannot load by reference»-зомби), и двойной коммит цепочки
 * вложенности остается поглощенным R-58-контуром.
 */
class XdStorageCommitRefusalCauseTest extends CacheInvariantTestSupport {

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
    @DisplayName("Отказ коммита несет идентичность записи: id, tx, оба флага, состояние")
    void refusalMessageCarriesRecordIdentity() throws Exception {
        XdStorageTransaction tx = mkTx("tx-refusal-1", 100L);
        mutator.insertInternal(entity(7L, "a"), tx);

        XdStorageRuntimeException e = assertThrows(XdStorageRuntimeException.class, () -> protocol.commit(tx));
        assertTrue(e.getMessage().contains("Illegal state of record to commit"), "историческое ядро сообщения сохранено");
        assertTrue(e.getMessage().contains("record 7"), "идентификатор записи в сообщении");
        assertTrue(e.getMessage().contains("tx-refusal-1"), "идентификатор транзакции в сообщении");
        assertTrue(e.getMessage().contains("firstPhaseCommit=false"), "фактический флаг фазы 1 в сообщении");
        assertTrue(e.getMessage().contains("secondPhaseCommit=false"), "фактический флаг фазы 2 в сообщении");
    }

    @Test
    @DisplayName("Отказ коммита отравленной транзакции несет первую причину (RBCAUSE)")
    void refusalMessageCarriesRollbackOnlyCause() throws Exception {
        // Настоящая транзакция (не мок): причина отравления читается из записи RBCAUSE.
        // Настоящая транзакция (не мок): причина отравления читается из записи RBCAUSE.
        // Менеджер стабим: markRollbackOnly уходит в registration sweeper'а.
        IXdStorageTransactionManager manager = mock(io.github.xdstorage.transaction.IXdStorageTransactionManager.class);
        XdStorageTransaction tx = new XdStorageTransaction(
                manager, String.valueOf(Thread.currentThread().getId()), null, 1000L, "tx-refusal-2") { };
        // RBCAUSE: транзакция отравлена ДО коммита с записанным сайтом — отказ финализации
        // обязан процитировать причину в том же (by <site>) дисциплине, что и откаты.
        tx.markRollbackOnly("unit-test poisoning site", null);
        mutator.insertInternal(entity(8L, "b"), tx);

        XdStorageRuntimeException e = assertThrows(XdStorageRuntimeException.class, () -> protocol.commit(tx));
        assertTrue(e.getMessage().contains("Illegal state of record to commit"), "ядро сообщения сохранено");
        assertTrue(e.getMessage().contains("unit-test poisoning site"), "первая причина отравления процитирована в отказе");
    }

    @Test
    @DisplayName("Цепочно-разделяемая запись: null-контент не публикуется, финализация — no-op")
    void nullContentFinalizeSkipsPublishInsteadOfZombie() throws Exception {
        XdStorageTransaction tx = mkTx("tx-zombie-1", 100L);
        mutator.insertInternal(entity(9L, "c"), tx);
        XdStorageCacheRecord record = context.records.get(9L);
        assertNotNull(record);

        // Воспроизведение гонки разделяемой записи (task 47): обе фазы взведены штатно,
        // затем со-стейджер/член цепочки откатывает запись — отложенный контент обнулен.
        // Исторически финализация публиковала null-запись (зомби); теперь финализация —
        // no-op: первая финализация разделяемой записи выигрывает, null не публикуется.
        protocol.prepareCommit(tx, newCollector());
        record.rollback(tx);
        record.prepareCommit();
        record.performCommit();

        assertDoesNotThrow(() -> protocol.commit(tx));
        assertFalse(record.isCommittedState(), "null-контент НЕ публикуется как закоммиченный");
        assertNull(record.getNewObject(), "контент не resurrect-ится");
    }

    @Test
    @DisplayName("Двойная финализация цепочки (R-58) остается поглощенной — guard не срабатывает на committed")
    void doubleCommitOfCommittedRecordIsStillAbsorbedByR58() throws Exception {
        XdStorageTransaction tx = mkTx("tx-double-1", 100L);
        mutator.insertInternal(entity(11L, "d"), tx);
        XdStorageTransactionResourceChanges collector = newCollector();

        protocol.prepareCommit(tx, collector);
        protocol.performCommit(tx, collector);
        protocol.commit(tx); // первая финализация: запись committed, флаги потреблены

        // повторная финализация той же записи (второй контур коммита цепочки вложенности):
        // R-58 поглощает повторный commit по факту committed-состояния
        protocol.commit(tx);

        assertTrue(context.records.get(11L).isCommittedState());
        assertEquals("d", ((TestEntity) context.records.get(11L).getObject()).getValue());
        assertNull(record(11L).getNewObject(), "повторный коммит не resurrect-ит контент");
    }

    private XdStorageCacheRecord record(long id) {
        return context.records.get(id);
    }

    private XdStorageTransactionResourceChanges newCollector() {
        return new XdStorageTransactionResourceChanges(mock(IXdStorageResourceObject.class));
    }
}
