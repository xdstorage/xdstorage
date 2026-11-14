package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.resource.XdStorageCanonicalInstanceRegistry;
import io.github.xdstorage.resource.XdStorageResourceNamingService;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractForeignKeySpecificationTest {

    protected XdStorageForeignKeyRegistry registry;
    protected XdStorageForeignKeyResourcesManager resourcesManager;
    protected XdStorageForeignKeyEngine engine;
    protected XdStorageTransaction transaction;

    protected Map<XdStorageBTreeId, XdStorageBTree> databaseStorage;
    protected XdStorageCanonicalInstanceRegistry canonicalRegistry;
    protected XdStorageResourceNamingService namingService;
    protected XdStorageConfig config;

    @BeforeEach
    void setUp() throws Exception {
        databaseStorage = new ConcurrentHashMap<>();
        canonicalRegistry = new XdStorageCanonicalInstanceRegistry();
        namingService = new XdStorageResourceNamingService("test_db_root", "yaml");
        registry = new XdStorageForeignKeyRegistry();

        // По умолчанию foreign keys включены согласно ТЗ (defaults to true)
        config = XdStorageConfig.defaults();

        // Полная очистка глобального JVM-wide реестра метаданных СУБД перед каждым тестом
        Field classesInfoField = XdStorageClassMetadataRegistry.class.getDeclaredField("classesInfo");
        classesInfoField.setAccessible(true);
        ((Map<?, ?>) classesInfoField.get(null)).clear();

        final io.github.xdstorage.serialization.IXdStorageObjectsReader mockReader = org.mockito.Mockito.mock(io.github.xdstorage.serialization.IXdStorageObjectsReader.class);
        final io.github.xdstorage.serialization.IXdStorageObjectsWriter mockWriter = org.mockito.Mockito.mock(io.github.xdstorage.serialization.IXdStorageObjectsWriter.class);
        final io.github.xdstorage.resource.XdStorageAbstractResourcesManager mockResourcesManager = org.mockito.Mockito.mock(io.github.xdstorage.resource.XdStorageAbstractResourcesManager.class);
        // stub lockReferencesResource → mock dao that returns non-null from read()
        final io.github.xdstorage.resource.IXdStorageDaoResource mockDao = org.mockito.Mockito.mock(io.github.xdstorage.resource.IXdStorageDaoResource.class);
        org.mockito.Mockito.when(mockDao.read(org.mockito.ArgumentMatchers.any(Object.class), org.mockito.ArgumentMatchers.any(io.github.xdstorage.transaction.XdStorageTransaction.class))).thenReturn(mockDao);

        // Сборка локатора сервисов на реальных in-memory структурах
        XdStorageServicesLocator services = new XdStorageServicesLocator() {
            @Override
            public IXdStorage getStorage() {
                return new IXdStorage() {
                    @Override public String getName() { return "InMemoryTestStorage"; }
                    @Override public io.github.xdstorage.metrics.XdStorageMetrics getMetrics() { return null; }
                    @Override public <T> void registerTrigger(io.github.xdstorage.trigger.IXdStorageTrigger<T> t) {}
                    @Override public IXdStorageTransaction beginTransaction() { return null; }
                    @Override public IXdStorageTransaction beginTransaction(long timeout) { return null; }
                    @Override public IXdStorageTransaction beginTransaction(IXdStorageTransaction tx) { return null; }
                    @Override public IXdStorageTransaction beginTransaction(IXdStorageTransaction tx, long timeout) { return null; }
                    @Override public void commitTransaction(IXdStorageTransaction tx) {}
                    @Override public void rollbackTransaction(IXdStorageTransaction tx) {}
                    @Override public void save(Object object) {}
                    @Override public void save(Object obj, IXdStorageTransaction tx) {
                        if (obj instanceof XdStorageBTree) {
                            databaseStorage.put(((XdStorageBTree) obj).getId(), (XdStorageBTree) obj);
                        }
                    }
                    @Override public void save(Collection<?> o) {}
                    @Override public void save(Collection<?> o, IXdStorageTransaction tx) {}
                    @Override public boolean has(Class<?> c, Object id) { return false; }
                    @Override public boolean has(Class<?> c, Object id, IXdStorageTransaction tx) { return false; }
                    @Override public void load(Object r) {}
                    @Override public void load(Object r, IXdStorageTransaction tx) {}
                    @Override public <T> T load(Class<T> c, Object id) { return null; }
                    @SuppressWarnings("unchecked")
                    @Override public <T> T load(Class<T> cl, Object id, IXdStorageTransaction transaction) {
                        return (T) databaseStorage.get(id);
                    }
                    @Override public void load(Collection<?> r) {}
                    @Override public void load(Collection<?> r, IXdStorageTransaction tx) {}
                    @Override public <T> Collection<T> load(Class<T> c) { return null; }
                    @Override public <T> Collection<T> load(Class<T> c, IXdStorageTransaction tx) { return null; }
                    @Override public void update(Object object) {}
                    @Override public void update(Object object, IXdStorageTransaction transaction) {}
                    @Override public void update(Collection<?> o) {}
                    @Override public void update(Collection<?> o, IXdStorageTransaction tx) {}
                    @Override public void delete(Object reference) {}
                    @Override public void delete(Object reference, IXdStorageTransaction transaction) {}
                    @Override public void delete(Collection<?> r) {}
                    @Override public void delete(Collection<?> r, IXdStorageTransaction tx) {}
                    @Override public io.github.xdstorage.transaction.IXdStorageTransactionManager getTransactionManager() { return null; }
                    @Override public void shutdown() {}
                    @Override public void checkpoint() {}
                };
            }

            @Override public io.github.xdstorage.resource.IXdStorageResourceNamingService getNamingService() { return namingService; }
            @Override public XdStorageCanonicalInstanceRegistry getCanonicalInstances() { return canonicalRegistry; }
            @Override public io.github.xdstorage.serialization.IXdStorageIOFactory getIoFactory() {
                // Mock factory — FK engine tests never serialize; provide a no-op reader/writer
                return new io.github.xdstorage.serialization.IXdStorageIOFactory() {
                    @Override public io.github.xdstorage.serialization.IXdStorageObjectsReader newInstanceReader() { return mockReader; }
                    @Override public io.github.xdstorage.serialization.IXdStorageObjectsWriter newInstanceWriter() { return mockWriter; }
                };
            }
            @Override public io.github.xdstorage.idgeneration.IXdStorageIdGenerator getIdGenerator() { return null; }
            @Override public io.github.xdstorage.resource.XdStorageAbstractResourcesManager getResourcesManager() { return mockResourcesManager; }
        };

        org.mockito.Mockito.when(mockResourcesManager.lockReferencesResource(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(mockDao);

        resourcesManager = new XdStorageForeignKeyResourcesManager(services, mockResourcesManager);
        engine = new XdStorageForeignKeyEngine(resourcesManager, registry, true);

        // Инициализируем транзакцию с отслеживанием rollback-only статуса
        transaction = new XdStorageTransaction(null, "THREAD_1", null, 100L, "TX_1") {
            private final Set<Object> registeredResources = new HashSet<>();
            @Override public void startCriticalSection(boolean r) {}
            @Override public void finishCriticalSection() {}
            @Override public boolean isResourceRegistered(io.github.xdstorage.resource.IXdStorageResourceObject res) {
                return registeredResources.contains(res.getResourceId());
            }
            @Override public void registerResource(io.github.xdstorage.resource.IXdStorageResourceObject res, int order) {
                registeredResources.add(res.getResourceId());
            }
        };
    }
}
