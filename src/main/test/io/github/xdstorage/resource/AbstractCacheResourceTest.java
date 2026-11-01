package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.BeforeEach;

import static org.mockito.Mockito.*;

public abstract class AbstractCacheResourceTest {

    protected XdStorageServicesLocator mockServices;
    protected IXdStorageCloner mockCloner;
    protected XdStorageObjectIdField mockIdField;
    protected IXdStorage mockStorage;
    protected XdStorageResourceCache resourceCache;

    @BeforeEach
    public void setUp() {
        mockServices = mock(XdStorageServicesLocator.class);
        mockCloner = mock(IXdStorageCloner.class);
        mockIdField = mock(XdStorageObjectIdField.class);
        mockStorage = mock(IXdStorage.class);

        // =========================================================================
        // ИСПРАВЛЕНИЕ: Создаем и обучаем мок менеджера транзакций СУБД.
        // Для всех лабораторных юнит-тестов изоляции он будет возвращать true,
        // предотвращая NullPointerException при Guard-проверках активности!
        // =========================================================================
        io.github.xdstorage.transaction.IXdStorageTransactionManager mockTxManager =
                mock(io.github.xdstorage.transaction.IXdStorageTransactionManager.class);
        when(mockTxManager.isTransactionAlive(any())).thenReturn(true);
        when(mockServices.getTransactionsManager()).thenReturn(mockTxManager);

        when(mockServices.getCloner()).thenReturn(mockCloner);
        when(mockServices.getStorage()).thenReturn(mockStorage);

        // Стратегия генерации ID по умолчанию — хэш-код объекта
        when(mockIdField.get(any())).thenAnswer(invocation -> (long) invocation.getArgument(0).hashCode());

        resourceCache = new XdStorageResourceCache(mockServices, TestEntity.class, mockIdField);
    }

    protected XdStorageTransaction mockTransaction(String txId) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(txId);
        when(tx.getTimestart()).thenReturn(System.nanoTime());
        when(tx.getTimeout()).thenReturn(3000L);
        return tx;
    }

    public static class TestEntity {
        private final String data;
        public TestEntity() { this.data = "default"; }
        public TestEntity(String data) { this.data = data; }
        public String getData() { return data; }
        @Override
        public String toString() { return "TestEntity{" + data + "}"; }
    }
}
