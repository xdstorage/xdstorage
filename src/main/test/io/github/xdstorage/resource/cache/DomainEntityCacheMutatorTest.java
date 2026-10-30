package io.github.xdstorage.resource.cache;

import io.github.xdstorage.exceptions.XdStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционные тесты мутатора кэша: Доменная логика")
public class DomainEntityCacheMutatorTest extends AbstractCacheMutatorIntegrationTest {

    private TestDomainEntity domainEntity;
    private final Long entityId = 100L;

    @BeforeEach
    @Override
    void setUp() {
        super.setUp();
        cacheContext.switchToDomainMode(TestDomainEntity.class);
        domainEntity = new TestDomainEntity(entityId, "Domain Data Baseline");
    }

    @Test
    @DisplayName("1) Вставка обычной доменной сущности")
    void testDomainInsert() throws XdStorageException {
        mutator.insertInternal(domainEntity, transaction);

        // Проверяем, что запись физически создана в правильном MVCC-статусе (locked / insert)
        XdStorageCacheRecord record = cacheContext.records().get(entityId);
        assertNotNull(record);
        assertEquals(domainEntity, record.getNewObject());
        assertTrue(record.isInsertChange());
        assertFalse(record.isCommitedState());

        // Проверяем, что запись легла в транзакционный журнал отката (undo-log)
        assertNotNull(workspace.changes(transaction.getTransactionId()).get(entityId));

        // Листнер должен зафиксировать регистрацию вставки
        assertEquals(1, listener.insertCalls.size());
        assertEquals(domainEntity, listener.insertCalls.get(0));
    }

    @Test
    @DisplayName("2) Вставка, а затем обновление доменной сущности")
    void testDomainInsertThenUpdate() throws XdStorageException {
        mutator.insertInternal(domainEntity, transaction);

        TestDomainEntity updatedEntity = new TestDomainEntity(entityId, "Domain Data Updated");
        mutator.update(updatedEntity, transaction);

        XdStorageCacheRecord record = cacheContext.records().get(entityId);
        // Проверяем быстрый путь: новый объект заместил старый в слоте newObject
        assertEquals(updatedEntity, record.getNewObject());

        // Так как это незакоммиченная вставка, листенер апдейтов вызываться не должен
        assertEquals(0, listener.updateCalls.size());

        // Воркспейс должен зафиксировать обновление транзитного состояния
        XdStorageCacheObjectChange transitChange = workspace.readObjects(transaction.getTransactionId()).get(entityId);
        assertEquals(XdStorageCacheChange.update, transitChange.change);
    }

    @Test
    @DisplayName("3) Вставка, обновление, а затем удаление доменной сущности")
    void testDomainInsertUpdateThenDelete() throws XdStorageException {
        mutator.insertInternal(domainEntity, transaction);
        mutator.update(domainEntity, transaction);

        mutator.delete(entityId, transaction);

        // Обычные доменные сущности при удалении «свежей» вставки полностью удаляются из ConcurrentHashMap
        assertFalse(cacheContext.records().containsKey(entityId));
        assertEquals(1, listener.deleteCalls.size());
    }

    @Test
    @DisplayName("4) Обновление существующей (ранее прочитанной) сущности")
    void testDomainUpdateExisting() throws XdStorageException {
        // Создаем запись чистого чтения (будто объект пришел с диска)
        XdStorageCacheRecord existingRecord = cacheContext.createReadRecord(domainEntity);
        cacheContext.records().put(entityId, existingRecord);
        workspace.readObjectsOf(transaction).put(entityId, new XdStorageCacheObjectChange(domainEntity, XdStorageCacheChange.read, 1000L));

        TestDomainEntity freshUpdate = new TestDomainEntity(entityId, "Fresh Update");
        mutator.update(freshUpdate, transaction);

        // Проверяем переход состояний: запись перешла в статусlocked / update
        assertTrue(existingRecord.isUpdateChange());
        assertEquals(domainEntity, existingRecord.getObject());      // Старый слепок заархивирован
        assertEquals(freshUpdate, existingRecord.getNewObject());     // Новый ждет коммита

        // Листнер фиксирует модификацию
        assertEquals(1, listener.updateCalls.size());
        Object[] args = listener.updateCalls.get(0);
        assertEquals(domainEntity, args[0]);
        assertEquals(freshUpdate, args[1]);
    }

    @Test
    @DisplayName("5) Обновление и последующее удаление доменной сущности")
    void testDomainUpdateThenDelete() throws XdStorageException {
        XdStorageCacheRecord existingRecord = cacheContext.createReadRecord(domainEntity);
        cacheContext.records().put(entityId, existingRecord);

        mutator.update(domainEntity, transaction);
        mutator.delete(entityId, transaction);

        // Объект переходит в статус удаления
        assertTrue(existingRecord.isDeleteChange());
        assertNull(existingRecord.getNewObject());
        assertEquals(1, listener.deleteCalls.size());
    }

    @Test
    @DisplayName("6) Удаление существующей доменной сущности")
    void testDomainDeleteExisting() throws XdStorageException {
        XdStorageCacheRecord existingRecord = cacheContext.createReadRecord(domainEntity);
        cacheContext.records().put(entityId, existingRecord);

        mutator.delete(entityId, transaction);

        assertTrue(existingRecord.isDeleteChange());
        assertEquals(1, listener.deleteCalls.size());
    }
}
