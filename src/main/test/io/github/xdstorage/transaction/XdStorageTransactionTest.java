package io.github.xdstorage.transaction;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.IXdStorageInternalListener;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collection;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Перегенерированный JUnit 5 тест, полностью адаптированный под Generic-интерфейс IXdStorageTransactionManager.
 */
public class XdStorageTransactionTest {

    // Правильная реализация Generic-интерфейса менеджера строго по твоему контракту
    private static class DummyTransactionManager implements IXdStorageTransactionManager<XdStorageTransaction> {
        private boolean commitCalled = false;
        private boolean rollbackCalled = false;
        private boolean registerRollbackOnlyCalled = false;

        @Override
        public IXdStorage getStorage() {
            return null;
        }
        @Override public XdStorageTransaction beginTransaction(long timeout) { return null; }
        @Override public XdStorageTransaction beginTransaction(XdStorageTransaction tx, long timeout) { return null; }
        @Override public XdStorageTransaction getTransaction(String transactionId) { return null; }
        @Override public XdStorageTransaction getCurrentTransaction() { return null; }
        @Override public boolean isTransactionAlive(XdStorageTransaction transaction) { return false; }

        @Override
        public void commitTransaction(XdStorageTransaction transaction) {
            this.commitCalled = true;
        }

        @Override
        public void rollbackTransaction(XdStorageTransaction transaction) {
            this.rollbackCalled = true;
        }

        @Override
        public void registerRollbackOnlyTransaction(XdStorageTransaction transaction) {
            this.registerRollbackOnlyCalled = true;
        }
    }

    // Дочерний тестовый класс для доступа к protected-конструктору ядра
    private static class TestableTransaction extends XdStorageTransaction {
        public TestableTransaction(IXdStorageTransactionManager<XdStorageTransaction> manager, String threadId,
                                   XdStorageTransaction global, long timeout, String transactionId) {
            super(manager, threadId, global, timeout, transactionId);
        }
    }

    // Mock-ресурс СУБД для проверки приоритетов и очередей
    private static class DummyResource implements IXdStorageResourceObject {
        private final Object id;
        public DummyResource(Object id) { this.id = id; }
        @Override public Object getResourceId() { return id; }
        @Override public Class<?> getObjectsClass() { return Object.class; }
        @Override public long getObjectsCount() { return 0L; }
        @Override public boolean hasChanges(XdStorageTransaction tx) { return false; }
        @Override public Object getDao() { return null; }
        @Override public void prepare(XdStorageTransaction tx) {}
        @Override public void performFirstPhaseCommit(XdStorageTransaction tx, io.github.xdstorage.transaction.XdStorageTransactionResourceChanges r) {}
        @Override public void performSecondPhaseCommit(XdStorageTransaction tx, io.github.xdstorage.transaction.XdStorageTransactionResourceChanges r) {}
        @Override public void rollbackPerformingFirstPhaseCommit(XdStorageTransaction tx, java.util.Collection cs) {}
        @Override public void rollback(XdStorageTransaction tx) {}
        @Override public void release(XdStorageTransaction tx) {}
        @Override public void lockForCommit(XdStorageTransaction tx) {}
        @Override public void unlockAfterCommit(XdStorageTransaction tx) {}

        @Override
        public void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction) {

        }
    }

    private DummyTransactionManager mockManager;
    private TestableTransaction transaction;

    @BeforeEach
    public void setUp() {
        mockManager = new DummyTransactionManager();
        transaction = new TestableTransaction(mockManager, "thread-10", null, 8000L, "tx-abc-123");
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testInitialization_ShouldStoreCorrectFields() {
        assertEquals("tx-abc-123", transaction.getTransactionId());
        assertEquals("thread-10", transaction.getTransactionThreadId());
        assertEquals(8000L, transaction.getTimeout());
        assertFalse(transaction.isRollbackOnly());
        assertTrue(transaction.getTimestart() > 0);
        assertNull(transaction.getGlobalTransactionId());
    }

    @Test
    public void testRegisterResource_WithValidInputs_ShouldRegisterAndSetPriority() throws XdStorageException {
        DummyResource resourceA = new DummyResource("resA");
        DummyResource resourceB = new DummyResource("resB");

        // Проверяем реальный метод с commitOrder
        transaction.registerResource(resourceA, 10);
        transaction.registerResource(resourceB, 5);

        assertTrue(transaction.isResourceRegistered(resourceA));
        assertTrue(transaction.isResourceRegistered(resourceB));

        Collection<IXdStorageResourceObject> registered = transaction.getResources();
        assertEquals(2, registered.size());
    }

    @Test
    public void testCommitAndRollback_ShouldDelegateToManager() {
        transaction.commit();
        assertTrue(mockManager.commitCalled);

        transaction.rollback();
        assertTrue(mockManager.rollbackCalled);
    }

    @Test
    public void testSuspendAndResumeFlow() {
        assertDoesNotThrow(() -> {
            transaction.suspend("sub-tx-1");
            transaction.resume("sub-tx-1");
        });
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testRegisterResource_WhenRollbackOnly_ShouldThrowException() {
        transaction.markRollbackOnly();
        assertTrue(transaction.isRollbackOnly());

        DummyResource resource = new DummyResource("failRes");

        // Регистрация в транзакции rollbackOnly обязана выкидывать XdStorageException
        assertThrows(XdStorageException.class, () -> transaction.registerResource(resource, 1));
    }

    @Test
    public void testStartCriticalSection_WhenRollbackOnlyWithoutFlag_ShouldThrowRuntimeException() {
        transaction.markRollbackOnly();

        // Вход в критическую секцию без флага принудительного отката кидает XdStorageRuntimeException
        assertThrows(XdStorageRuntimeException.class, () -> transaction.startCriticalSection());

        // Вход с флагом rollback=true разрешен
        assertDoesNotThrow(() -> transaction.startCriticalSection(true));
    }

    @Test
    public void testRegisterResource_WithNullResource_ShouldThrowNullPointerException() {
        // Передача null-ресурса падает на получении ID
        assertThrows(NullPointerException.class, () -> transaction.registerResource(null, 1));
    }
}
