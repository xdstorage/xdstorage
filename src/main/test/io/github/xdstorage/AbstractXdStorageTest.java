package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;
import io.github.xdstorage.foreignkey.XdStorageForeignKeyEngine;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.search.IXdStorageSearchManager;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.structure.XdStorageStructureManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionManager;
import io.github.xdstorage.trigger.XdStorageTriggerManager;
import org.junit.jupiter.api.BeforeEach;

import static org.mockito.Mockito.*;

public abstract class AbstractXdStorageTest {

    protected XdStorage storage;
    protected XdStorageServicesLocator mockServices;
    protected XdStorageTransactionManager mockTxManager;
    protected XdStorageAbstractResourcesManager mockResourcesManager;
    protected IXdStorageSearchManager mockSearchManager;
    protected XdStorageTriggerManager mockTriggersManager;
    protected XdStorageStructureManager mockStructureManager;
    protected XdStorageTransaction mockTx;
    protected XdStorageForeignKeyEngine mockForeignKeyEngine;

    @BeforeEach
    public void baseSetUp() throws Exception {
        mockServices = mock(XdStorageServicesLocator.class);
        mockTxManager = mock(XdStorageTransactionManager.class);
        mockResourcesManager = mock(XdStorageAbstractResourcesManager.class);
        mockSearchManager = mock(IXdStorageSearchManager.class);
        mockTriggersManager = mock(XdStorageTriggerManager.class);
        mockStructureManager = mock(XdStorageStructureManager.class);
        mockForeignKeyEngine = mock(XdStorageForeignKeyEngine.class);
        mockTx = mock(XdStorageTransaction.class);

        // Связываем цепочку моков локатора сервисов
        when(mockServices.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockServices.getResourcesManager()).thenReturn(mockResourcesManager);
        when(mockServices.getSearchManager()).thenReturn(mockSearchManager);
        when(mockServices.getTriggersManager()).thenReturn(mockTriggersManager);
        when(mockServices.getStructureManager()).thenReturn(mockStructureManager);
        when(mockServices.getForeignKeyEngine()).thenReturn(mockForeignKeyEngine);

        // Дефолтное поведение транзакций
        when(mockTx.getTransactionId()).thenReturn("tx-facade-123");
        when(mockTxManager.getCurrentTransaction()).thenReturn(mockTx);
        when(mockTxManager.isTransactionAlive(any())).thenReturn(true);

        // Инстанцируем XdStorage и рефлексивно подменяем локатор сервисов на наш мок
        storage = new XdStorage("test-storage-facade", "./teststorage", 250);
        java.lang.reflect.Field servicesField = XdStorage.class.getDeclaredField("services");
        servicesField.setAccessible(true);
        servicesField.set(storage, mockServices);
    }

    // =========================================================================
    // ТЕСТОВЫЕ СУЩНОСТИ С ПОДДЕРЖКОЙ ИНДЕКСАЦИИ Б+ДЕРЕВА
    // =========================================================================

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    @XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
    // Объявляем вторичный Б+Дерево индекс по текстовому полю 'value'
    @XdStorageObjectSearchIndex(indexName = "idx_class_entity_value", indexFieldNames = {"value"})
    public static class ClassPolicyEntity {
        @XdStorageObjectId
        private Long id;
        private String value;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    @XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
    // Объявляем составной Б+Дерево индекс по полям 'name' и 'status'
    @XdStorageObjectSearchIndex(indexName = "idx_single_entity_composite", indexFieldNames = {"name", "status"})
    public static class SinglePolicyEntity {
        @XdStorageObjectId
        private Long id;
        private String name;
        private int status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
    }

    public static class InvalidEntity {
        private Long id; // Мина: отсутствует аннотация @XdStorageObjectId!
    }
}
