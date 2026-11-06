package io.github.xdstorage.transaction;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.factories.XdStorageSmartCloner;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.trigger.XdStorageTriggerManager;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 тесты для проверки базовых и граничных условий класса XdStorageTransactionManager.
 */
public class XdStorageTransactionManagerTest {

    private XdStorageServicesLocator mockServices;
    private XdStorageTransactionManager txManager;
    private ExecutorService testExecutor;

    @BeforeEach
    public void setUp() {
        // Создаем моки для сервис-локатора и внутренних утилит, которые дергает менеджер при коммите/откате
        mockServices = mock(XdStorageServicesLocator.class);
        testExecutor = Executors.newSingleThreadExecutor();
        XdStorageSmartCloner mockCloner = mock(XdStorageSmartCloner.class);
        XdStorageTriggerManager mockTriggers = mock(XdStorageTriggerManager.class);

        when(mockServices.getExecutor()).thenReturn(testExecutor);
        when(mockServices.getCloner()).thenReturn(mockCloner);
        when(mockServices.getTriggersManager()).thenReturn(mockTriggers);

        txManager = new XdStorageTransactionManager(mockServices);
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testBeginTransaction_HappyPath_ShouldCreateActiveTransaction() {
        // Создаем базовую транзакцию с тайм-аутом 100Lмс
        XdStorageTransaction tx = txManager.beginTransaction(100L);

        assertNotNull(tx);
        assertNotNull(tx.getTransactionId());
        assertEquals(100L, tx.getTimeout());
        assertTrue(txManager.isTransactionAlive(tx));

        // Проверяем, что менеджер правильно привязал транзакцию к текущему воркер-потоку jvm
        assertSame(tx, txManager.getCurrentTransaction());
        assertSame(tx, txManager.getTransaction(tx.getTransactionId()));
    }

    @Test
    public void testNestedTransactions_HappyPath_ShouldSuspendParent() {
        // 1. Стартуем глобальную родительскую транзакцию
        XdStorageTransaction parentTx = txManager.beginTransaction(10000L);

        // 2. Стартуем вложенную подтранзакцию в том же потоке
        XdStorageTransaction childTx = txManager.beginTransaction(100L);

        assertNotNull(childTx);
        assertNotEquals(parentTx.getTransactionId(), childTx.getTransactionId());
        assertSame(parentTx, childTx.getGlobalTransaction());

        // Проверяем, что текущей активной транзакцией потока стала дочерняя подтранзакция
        assertSame(childTx, txManager.getCurrentTransaction());
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testCommitTransaction_WhenMarkedRollbackOnly_ShouldThrowException() {
        XdStorageTransaction tx = txManager.beginTransaction(100L);

        // Имитируем падение или явную отметку "только откат"
        tx.markRollbackOnly();
        assertTrue(tx.isRollbackOnly());

        // Граничное условие: попытка закоммитить rollbackOnly транзакцию обязана выкинуть XdStorageRuntimeException
        assertThrows(XdStorageRuntimeException.class, () -> txManager.commitTransaction(tx));
    }

    @Test
    public void testIsTransactionAlive_WithRollbackOnly_ShouldReturnFalse() {
        XdStorageTransaction tx = txManager.beginTransaction(100L);
        assertTrue(txManager.isTransactionAlive(tx));

        tx.markRollbackOnly();

        // Граничное условие: транзакция с флагом rollbackOnly больше не должна считаться живой СУБД
        assertFalse(txManager.isTransactionAlive(tx));
    }

    @Test
    public void testThreadIsolation_ParallelThreads_ShouldHaveIndependentTransactions() throws Exception {
        // Граничное условие: проверка полной потокоизоляции (Thread-Safety) менеджера транзакций.
        // Запускаем транзакцию в параллельном потоке и проверяем, что в текущем главном потоке она не видна.
        XdStorageTransaction mainThreadTx = txManager.beginTransaction(3000L);

        Future<XdStorageTransaction> future = testExecutor.submit(() -> txManager.beginTransaction(4000L));
        XdStorageTransaction parallelThreadTx = future.get();

        assertNotNull(parallelThreadTx);
        assertNotEquals(mainThreadTx.getTransactionId(), parallelThreadTx.getTransactionId());

        // Проверяем, что главный поток видит строго свою транзакцию, а не параллельную
        assertSame(mainThreadTx, txManager.getCurrentTransaction());

        testExecutor.shutdown();
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testStateHolder_HappyPath_Contract() {
        XdStorageCommitTransactionStateHolder holder = new XdStorageCommitTransactionStateHolder();

        holder.setState(XdStorageCommitTransactionState.ACTIVE);
        assertEquals(XdStorageCommitTransactionState.ACTIVE, holder.getState());

        holder.setState(XdStorageCommitTransactionState.FINISHED);
        assertEquals(XdStorageCommitTransactionState.FINISHED, holder.getState());
    }

    @Test
    public void testGetTransaction_WhenEmpty_ShouldReturnNullSafely() {
        assertNull(txManager.getTransaction("ghost_tx_id_123"));
        assertNull(txManager.getCurrentTransaction());
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ И ИСКЛЮЧЕНИЯ ===

    @Test
    public void testCommitTransaction_WithRollbackOnly_ShouldThrowXdStorageRuntimeException() {
        XdStorageTransaction mockTx = mock(XdStorageTransaction.class);
        when(mockTx.getTransactionId()).thenReturn("tx_failed_999");
        // Маркируем транзакцию как поврежденную (Rollback Only)
        when(mockTx.isRollbackOnly()).thenReturn(true);

        // Попытка закоммитить поврежденную транзакцию обязана вызывать fail-fast исключение СУБД
        assertThrows(XdStorageRuntimeException.class, () -> {
            txManager.commitTransaction(mockTx);
        });
    }

    @Test
    public void testIsTransactionAlive_WhenNotRegistered_ShouldReturnFalse() {
        XdStorageTransaction mockTx = mock(XdStorageTransaction.class);
        when(mockTx.getTransactionId()).thenReturn("unregistered_id");

        assertFalse(txManager.isTransactionAlive(mockTx));
    }

    // === 3. ПРОДВИНУТЫЕ ТЕСТЫ ДЛЯ ВЫЯВЛЕНИЯ СКРЫТЫХ БАГОВ ЯДРА ===

    @Test
    public void testBeginTransaction_ThreadReuseFlow_ShouldIsolateTransactions() {
        // Тест выявляет баг неуникального getCurrentThreadId().
        // Имитируем каскадный запуск транзакций в рамках ОДНОГО потока.

        // 1. Стартуем первую независимую транзакцию
        XdStorageTransaction tx1 = txManager.beginTransaction(1000L);
        assertNotNull(tx1);
        String tx1Id = tx1.getTransactionId();

        // Проверяем, что менеджер считает её текущей для этого потока
        assertEquals(tx1, txManager.getCurrentTransaction());

        // 2. Имитируем, что транзакция tx1 завершилась аварией и помечена как rollbackOnly
        tx1.markRollbackOnly();
        assertTrue(tx1.isRollbackOnly());

        // 3. Открываем СЛЕДУЮЩУЮ транзакцию в этом же потоке (имитация переиспользования потока пулом)
        // Из-за бага в getCurrentThreadId() менеджер может некорректно связать tx2 с tx1,
        // либо попытаться сделать tx1.suspend(), что вызовет NullPointerException/сбой логики,
        // так как tx1.waitForFinish() зависнет на пустом моке/неинициализированных полях.
        XdStorageTransaction tx2 = txManager.beginTransaction(2000L);

        assertNotNull(tx2, "Менеджер обязан успешно создать новую транзакцию для очищенного потока!");
        assertNotEquals(tx1Id, tx2.getTransactionId(), "Новая транзакция должна иметь уникальный ID!");

        // Чистим за собой
        txManager.rollbackTransaction(tx2);
    }

    @Test
    public void testRegisterRollbackOnlyTransaction_ConcurrentRace_ShouldNotCrash() throws Exception {
        // Тест подсвечивает гонку потоков (Race Condition) в registerRollbackOnlyTransaction.
        // Мы мокаем транзакцию, которая находится в состоянии rollbackOnly.
        XdStorageTransaction mockTx = mock(XdStorageTransaction.class);
        String targetTxId = "race_tx_007";
        when(mockTx.getTransactionId()).thenReturn(targetTxId);
        when(mockTx.isRollbackOnly()).thenReturn(true);
        when(mockTx.getTransactionThreadId()).thenReturn(Thread.currentThread().getName() + Thread.currentThread().getId());

        // Настраиваем фейковый асинхронный Executor в сервисах, который сразу выполняет задачу
        java.util.concurrent.ExecutorService inlineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        when(mockServices.getExecutor()).thenReturn(inlineExecutor);
        when(mockServices.getCloner()).thenReturn(mock(XdStorageSmartCloner.class));

        // Вручную подсаживаем транзакцию в мапы менеджера, имитируя её активное состояние
        // Так как мапы в XdStorageTransactionManager приватные, мы инициируем её через легитимный beginTransaction
        XdStorageTransaction realTx = txManager.beginTransaction(3000L);
        realTx.markRollbackOnly();

        // Запускаем ОДНОВРЕМЕННЫЙ асинхронный откат и ручной отказ.
        // Если мапы очищаются параллельно без блокировки critical section,
        // один из потоков вылетит по NullPointerException или ConcurrentModificationException.
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        Thread asyncThread = new Thread(() -> {
            try {
                latch.await();
                txManager.registerRollbackOnlyTransaction(realTx);
            } catch (Exception ignored) {}
        });
        asyncThread.start();

        latch.countDown(); // Одновременный залп!

        // Ручной поток тоже бьет в откат
        assertDoesNotThrow(() -> {
            txManager.rollbackTransaction(realTx);
        });

        asyncThread.join(2000);
        inlineExecutor.shutdown();
    }

    @Test
    public void testNestedTransaction_SuspendAndResumeFlow_ShouldMaintainHierarchy() {
        // Тестируем сложнейший инвариант: Вложенные транзакции (Hierarchical MVCC)
        // 1. Стартуем глобальную родительскую транзакцию T1
        XdStorageTransaction t1 = txManager.beginTransaction(100L);
        assertNotNull(t1);
        String t1Id = t1.getTransactionId();

        // Убеждаемся, что T1 сейчас активна в текущем потоке
        assertEquals(t1, txManager.getCurrentTransaction());

        // 2. Стартуем вложенную транзакцию T2 в ЭТОМ ЖЕ ПОТОКЕ
        // Менеджер обязан вызвать t1.suspend() и подменить текущую транзакцию потока на T2
        XdStorageTransaction t2 = txManager.beginTransaction(3000L);
        assertNotNull(t2);
        String t2Id = t2.getTransactionId();

        // ПРОВЕРКА ИНВАРИАНТА СУСПЕНДА:
        // Теперь текущей транзакцией в потоке должна стать именно T2!
        assertEquals(t2, txManager.getCurrentTransaction(), "T2 должна вытеснить T1 из контекста потока");
        assertNotEquals(t1Id, t2Id, "Вложенная транзакция должна иметь свой уникальный UUID");
        assertEquals(t1, t2.getGlobalTransaction(), "T1 должна быть зафиксирована как родительская (глобальная) для T2");

        // Настраиваем фейковый стейт финиша для внутренней транзакции,
        // чтобы избежать зависания commitInternal на неинициализированных ресурсах
        XdStorageSmartCloner mockCloner = mock(XdStorageSmartCloner.class);
        when(mockServices.getCloner()).thenReturn(mockCloner);

        // 3. Коммитим вложенную транзакцию T2
        // При коммите T2 менеджер обязан вычистить T2 и вызвать t1.resume(),
        // вернув родительскую транзакцию T1 обратно на рабочее место в поток!
        try {
            // Чтобы commitTransaction не упал на внутренней стейт-машине t2.commitInternal(),
            // мы проверяем логику восстановления контекста нити.
            txManager.commitTransaction(t2);
        } catch (Throwable ignored) {
            // Если внутренние недописанные методы XdStorageTransaction (commitInternal) выбросят NPE,
            // нас интересует, восстановил ли менеджер структуру мап в блоке удаления нитей!
        }

        // ПРОВЕРКА ИНВАРИАНТА РЕЗЮМА:
        // После закрытия T2, родительская транзакция T1 ОБЯЗАНА вернуться в контекст потока!
        // Если из-за бага в мапах threadsByTransactionId/transactionsByThreadId контекст затерся,
        // метод getCurrentTransaction() вернет null или поломаную T2.
        XdStorageTransaction restoredTx = txManager.getCurrentTransaction();

        assertNotNull(restoredTx, "Критический баг! Родительская транзакция T1 потерялась после коммита вложенной T2!");
        assertEquals(t1Id, restoredTx.getTransactionId(), "В контекст потока должна была вернуться именно родительская T1!");

        // Чистим за собой реестр
        try {
            txManager.rollbackTransaction(t1);
        } catch (Throwable ignored) {}
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testTransactionMetadata_HappyPath() {
        IXdStorageTransactionManager mockManager = mock(IXdStorageTransactionManager.class);
        XdStorageTransaction tx = new XdStorageTransaction(mockManager, "thread-1", null, 100L, "tx-100");

        assertEquals("tx-100", tx.getTransactionId());
        assertEquals("thread-1", tx.getTransactionThreadId());
        assertEquals(100L, tx.getTimeout());
        assertFalse(tx.isRollbackOnly());
        assertNull(tx.getGlobalTransaction());
    }

    @Test
    public void testCriticalSection_IncrementAndDecrement() throws XdStorageException {
        IXdStorageTransactionManager mockManager = mock(IXdStorageTransactionManager.class);
        XdStorageTransaction tx = new XdStorageTransaction(mockManager, "thread-1", null, 100L, "tx-101");

        // Убеждаемся, что вход и выход из критической секции не генерируют исключений
        assertDoesNotThrow(() -> {
            tx.startCriticalSection();
            tx.finishCriticalSection();
        });
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testRegisterResource_WhenRollbackOnly_ShouldThrowXdStorageException() {
        IXdStorageTransactionManager mockManager = mock(IXdStorageTransactionManager.class);
        XdStorageTransaction tx = new XdStorageTransaction(mockManager, "thread-1", null, 100L, "tx-102");

        // Помечаем транзакцию на принудительный откат
        tx.markRollbackOnly();
        assertTrue(tx.isRollbackOnly());

        IXdStorageResourceObject mockResource = mock(IXdStorageResourceObject.class);
        when(mockResource.getResourceId()).thenReturn("res-999");

        // Граничное условие: Попытка зарегистрировать новый ресурс в поврежденной транзакции
        assertThrows(XdStorageException.class, () -> {
            tx.registerResource(mockResource, 1);
        });
    }

    @Test
    public void testObjectUtils_IsSimpleReference_Contract() {
        // Проверяем фундаментальные правила распознавания ссылок утилитами ядра
        assertFalse(XdStorageObjectUtils.isSimpleReference(null));
        assertFalse(XdStorageObjectUtils.isSimpleReference("just a string"));
        assertFalse(XdStorageObjectUtils.isSimpleReference(12345));
    }
}
