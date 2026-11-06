package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public abstract class AbstractXdStorageResourceCacheTest {

    protected XdStorageServicesLocator mockLocator;
    protected IXdStorageCloner mockCloner;
    protected IXdStorage mockStorage;
    protected XdStorageObjectIdField mockField;
    protected IXdStorageTransactionManager mockTxManager;
    protected XdStorageResourceCache resourceCache;

    // Изолированная сущность для Unit-тестов
    // Конструктор без аргументов обязателен по конвенции СУБД: генератор оберток
    // наследует сущность, а скомпилированный прокси требует no-arg super().
    // @XdStorageObjectId + сеттер обязательны, чтобы MVCC-клонер переносил ID в глубокие копии
    public static class TestEntity {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private Long id;
        private String value;

        public TestEntity() {}
        public TestEntity(Long id, String value) {
            this.id = id;
            this.value = value;
        }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public interface MockObservableWrapper extends io.github.xdstorage.observing.IXdStorageIdObservableWrapper {
        void triggerIdSet(Object id);
    }

    @BeforeEach
    public void setUp() throws Exception {
        mockLocator = mock(XdStorageServicesLocator.class);
        mockCloner = mock(IXdStorageCloner.class);
        mockStorage = mock(IXdStorage.class);
        mockField = mock(XdStorageObjectIdField.class);
        mockTxManager = mock(IXdStorageTransactionManager.class);

        when(mockLocator.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockLocator.getCloner()).thenReturn(mockCloner);
        when(mockLocator.getStorage()).thenReturn(mockStorage);
        when(mockTxManager.isTransactionAlive(any())).thenReturn(true);

        when(mockField.get(any())).thenAnswer(inv -> {
            Object obj = inv.getArgument(0);
            return obj instanceof TestEntity ? ((TestEntity) obj).getId() : null;
        });

        // =========================================================================
        // СУБД TDD-ФИКС СТEНДA: Исправляем SampleNode на TestEntity!
        // Прежний скрытый ClassCastException внутри cloneAndWrap гасил предикат,
        // из-за чего readInternal возвращал пустую коллекцию (Actual: 0).
        // =========================================================================
        when(mockCloner.cloneAndWrap(any(), any(), any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof TestEntity) {
                TestEntity orig = (TestEntity) arg;
                return new TestEntity(orig.getId(), orig.getValue());
            }
            return arg;
        });

        when(mockCloner.unwrapAndClone(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof TestEntity) {
                TestEntity orig = (TestEntity) arg;
                return new TestEntity(orig.getId(), orig.getValue());
            }
            return arg;
        });

        doAnswer(inv -> {
            // Типобезопасно: стаб обслуживает только пары TestEntity, прочие классы
            // (например XdStorageBTree) пропускает без каста — CCE недопустим в стабе
            if (inv.getArgument(0) instanceof TestEntity && inv.getArgument(1) instanceof TestEntity) {
                ((TestEntity) inv.getArgument(0)).setValue(((TestEntity) inv.getArgument(1)).getValue());
            }
            return null;
        }).when(mockCloner).fillAndWrap(any(), any(), any(), any());

        resourceCache = new XdStorageResourceCache(mockLocator, TestEntity.class, mockField);
    }

    protected XdStorageTransaction createTx(String txId, long timestart) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(txId);
        when(tx.getTimestart()).thenReturn(timestart);
        when(tx.getTimeout()).thenReturn(100L);
        return tx;
    }
}
