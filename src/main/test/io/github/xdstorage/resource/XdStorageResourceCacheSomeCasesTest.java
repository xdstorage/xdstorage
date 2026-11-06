package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class XdStorageResourceCacheSomeCasesTest {

    private XdStorageServicesLocator mockLocator;
    private IXdStorageTransactionManager mockTxManager;
    private IXdStorageCloner mockCloner;
    private IXdStorage mockStorage;
    private XdStorageObjectIdField mockField;
    private XdStorageResourceCache resourceCache;

    // Простой доменный класс для тестирования кэширования
    private static class TestEntity {
        private Long id;
        private String value;

        public TestEntity(Long id, String value) {
            this.id = id;
            this.value = value;
        }
        public Long getId() { return id; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    @BeforeEach
    public void setUp() throws Exception {
        mockLocator = mock(XdStorageServicesLocator.class);
        mockTxManager = mock(IXdStorageTransactionManager.class);
        mockCloner = mock(IXdStorageCloner.class);
        mockStorage = mock(IXdStorage.class);
        mockField = mock(XdStorageObjectIdField.class);

        when(mockLocator.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockLocator.getCloner()).thenReturn(mockCloner);
        when(mockLocator.getStorage()).thenReturn(mockStorage);

        // Настройка извлечения ID сущности
        when(mockField.get(any())).thenAnswer(invocation -> {
            Object obj = invocation.getArgument(0);
            if (obj instanceof TestEntity) return ((TestEntity) obj).getId();
            return null;
        });

        // Базовая настройка клонера (возвращает копию)
        when(mockCloner.cloneAndWrap(any(), any(), any())).thenAnswer(invocation -> {
            TestEntity original = invocation.getArgument(0);
            return new TestEntity(original.getId(), original.getValue());
        });

        resourceCache = new XdStorageResourceCache(mockLocator, TestEntity.class, mockField);
    }

    private XdStorageTransaction createMockTransaction(String txId, long timestart) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(txId);
        when(tx.getTimestart()).thenReturn(timestart);
        when(tx.getTimeout()).thenReturn(100L);
        return tx;
    }

    // =========================================================================
    // БЛОК 1: БАЗОВЫЕ ТЕСТЫ ФУНКЦИОНАЛЬНОСТИ И АТОМАРНОСТИ (ACID)
    // =========================================================================

    @Test
    @DisplayName("Базовый тест: Заполнение прогревочного кэша и проверка присутствия")
    public void testCache_FillCacheAndHasObject() {
        TestEntity entity = new TestEntity(1L, "InitialData");
        resourceCache.fillCache(Collections.singletonList(entity));

        assertTrue(resourceCache.hasObject(1L));
        assertFalse(resourceCache.hasObject(999L));
        assertEquals(1, resourceCache.getObjectsCount());
    }

    @Test
    @DisplayName("Транзакционный тест: Изменения незакоммиченной транзакции не должны быть видны параллельной сессии")
    public void testCache_Isolation_UncommittedChangesShouldBeIsolated() throws Exception {
        XdStorageTransaction tx1 = createMockTransaction("tx-writer-1", 1000L);
        XdStorageTransaction tx2 = createMockTransaction("tx-reader-2", 1005L);

        when(mockTxManager.isTransactionAlive(tx1)).thenReturn(true);
        when(mockTxManager.isTransactionAlive(tx2)).thenReturn(true);

        // Тх1 добавляет объект
        TestEntity entity = new TestEntity(10L, "Tx1-Data");
        resourceCache.insert(entity, tx1);

        // Тх2 делает вычитку кэша
        Collection<Object> tx2Read = resourceCache.read(tx2);
        assertTrue(tx2Read.isEmpty(), "Параллельная транзакция не имеет права видеть незакоммиченный грязный инвертарь!");
    }

    @Test
    @DisplayName("Транзакционный тест: После успешного коммита данные обязаны стать видимыми для новых транзакций")
    public void testCache_CommitVisibility_ShouldBecomeVisibleAfterCommit() throws Exception {
        XdStorageTransaction tx1 = createMockTransaction("tx-writer-1", 1000L);
        when(mockTxManager.isTransactionAlive(tx1)).thenReturn(true);

        TestEntity entity = new TestEntity(20L, "SecureState");
        when(mockCloner.unwrapAndClone(entity)).thenReturn(entity);
        resourceCache.insert(entity, tx1);

        // Имитируем двухфазный коммит менеджера ресурсов СУБД
        io.github.xdstorage.transaction.XdStorageTransactionResourceChanges mockCollector =
                mock(io.github.xdstorage.transaction.XdStorageTransactionResourceChanges.class);

        resourceCache.prepareCommit(tx1, mockCollector);
        resourceCache.performCommit(tx1, mockCollector);
        resourceCache.commit(tx1);

        // Новая транзакция чтения с актуальным timestart
        XdStorageTransaction tx2 = createMockTransaction("tx-reader-2", System.nanoTime());
        when(mockTxManager.isTransactionAlive(tx2)).thenReturn(true);

        Collection<TestEntity> tx2Read = resourceCache.read(tx2);
        assertEquals(1, tx2Read.size());
        assertEquals("SecureState", tx2Read.iterator().next().getValue());
    }

    @Test
    @DisplayName("Транзакционный тест: Откат транзакции (Rollback) обязан полностью вырезать грязные записи")
    public void testCache_Rollback_ShouldWipeDirtyInserts() throws Exception {
        XdStorageTransaction tx1 = createMockTransaction("tx-writer-1", 1000L);
        when(mockTxManager.isTransactionAlive(tx1)).thenReturn(true);

        TestEntity entity = new TestEntity(30L, "DirtyInsert");
        when(mockCloner.unwrapAndClone(entity)).thenReturn(entity);
        resourceCache.insert(entity, tx1);

        // Выполняем откат
        resourceCache.rollback(tx1);

        assertFalse(resourceCache.hasObject(30L), "После роллбэка запись обязана полностью аннигилироваться из ConcurrentHashMap!");
    }

    // =========================================================================
    // БЛОК 2: ТЕСТИРОВАНИЕ АЛГОРИТМИЧЕСКОГО БАРЬЕРА (МЕРТВЫЕ ТРАНЗАКЦИИ-СИРОТЫ)
    // =========================================================================

    @Test
    @Disabled
    @DisplayName("Архитектурный тест: Если владелец блокировки мертв (isTransactionAlive == false), замок аннулируется")
    public void testCache_DeadTransactionOrphan_ShouldBeOverriddenByNextTransaction() throws Exception {
        XdStorageTransaction txDead = createMockTransaction("tx-dead-worker", 1000L);
        XdStorageTransaction txClean = createMockTransaction("tx-clean-after-each", System.nanoTime());

        // Менеджер транзакций сообщает: старый параллельный воркер МЕРТВ, а очистка — ЖИВА
        when(mockTxManager.isTransactionAlive(txDead)).thenReturn(false);
        when(mockTxManager.isTransactionAlive(txClean)).thenReturn(true);

        // Создаем заблокированную запись в кэше от мертвой транзакции
        TestEntity entity = new TestEntity(40L, "InitialData");
        resourceCache.fillCache(Collections.singletonList(entity));

        // Имитируем, что мертвый воркер успел зайти в update и бросить замок
        resourceCache.update(entity, txDead);

        // Наша новая транзакция очистки localTx пытается обновить/удалить этот же объект
        assertDoesNotThrow(() -> {
            TestEntity updatedEntity = new TestEntity(40L, "PurgedData");
            resourceCache.update(updatedEntity, txClean);
        }, "Критический баг! Кэш выдал ложный 'marked as rollback only' на брошенном замке мертвой транзакции!");
    }

    // =========================================================================
    // БЛОК 3: ВЫСОКОНАГРУЖЕННЫЕ И МНОГОПОТОЧНЫЕ СТРЕСС-ТЕСТЫ (STRESS TESTING)
    // =========================================================================

    @Test
    @DisplayName("Нагрузочный стресс-тест: Параллельные транзакции уходят в жесткий Lock Contention за один ресурс")
    public void testCache_LockContentionContest_ShouldEnforceTimeout() throws Exception {
        XdStorageTransaction txMonopolist = createMockTransaction("tx-monopolist", 1000L);
        XdStorageTransaction txVictim = createMockTransaction("tx-victim", 1005L);

        when(mockTxManager.isTransactionAlive(txMonopolist)).thenReturn(true);
        when(mockTxManager.isTransactionAlive(txVictim)).thenReturn(true);
// Задаем короткий таймаут для жертвы
        when(txVictim.getTimeout()).thenReturn(150L);

        TestEntity entity = new TestEntity(500L, "MonopolyObject");
        resourceCache.fillCache(Collections.singletonList(entity));

        // Монополист успешно захватывает и блокирует запись
        resourceCache.update(entity, txMonopolist);

        // Жертва в параллельном потоке пытается модифицировать этот же заблокированный объект
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> {
            assertThrows(XdStorageRuntimeException.class, () -> {
                resourceCache.update(entity, txVictim);
            }, "Поток-жертва обязан был вылететь по таймауту ожидания блокировки lock.wait()!");
        });

        future.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();
    }
}
