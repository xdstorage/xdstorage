package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.XdStorageCanonicalInstanceRegistry;
import io.github.xdstorage.resource.XdStorageResourceNamingService;
import io.github.xdstorage.resource.XdStorageResourcesManager;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractForeignKeyEngineIntegrationTest {

    protected XdStorageForeignKeyRegistry registry;
    protected XdStorageAbstractResourcesManager abstractResourcesManager;
    protected XdStorageForeignKeyResourcesManager resourcesManager;
    protected XdStorageForeignKeyEngine engine;
    protected XdStorageTransaction transaction;

    protected Map<XdStorageBTreeId, XdStorageBTree> databaseStorage;
    protected XdStorageCanonicalInstanceRegistry canonicalRegistry;
    protected XdStorageResourceNamingService namingService;

    @BeforeEach
    void setUp() throws Exception {
        databaseStorage = new ConcurrentHashMap<>();
        canonicalRegistry = new XdStorageCanonicalInstanceRegistry();
        namingService = new XdStorageResourceNamingService("test_db_root", "yaml");
        registry = new XdStorageForeignKeyRegistry();

        // Полная очистка глобального JVM-wide реестра метаданных СУБД перед каждым тестом
        Field classesInfoField = XdStorageClassMetadataRegistry.class.getDeclaredField("classesInfo");
        classesInfoField.setAccessible(true);
        ((Map<?, ?>) classesInfoField.get(null)).clear();

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
                };
            }

            @Override
            public XdStorageAbstractResourcesManager getResourcesManager() {
                return abstractResourcesManager;
            }

            @Override public io.github.xdstorage.resource.IXdStorageResourceNamingService getNamingService() { return namingService; }
            @Override public XdStorageCanonicalInstanceRegistry getCanonicalInstances() { return canonicalRegistry; }
        };

        abstractResourcesManager = new XdStorageResourcesManager(services, 64);
        resourcesManager = new XdStorageForeignKeyResourcesManager(services, null);
        engine = new XdStorageForeignKeyEngine(resourcesManager, registry, true);

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

    protected static class TestObservableWrapper implements IXdStorageIdObservableWrapper {
        private final Object wrapped;
        private final List<XdStorageAbstractIdObserver> observers = new ArrayList<>();

        public TestObservableWrapper(Object wrapped) { this.wrapped = wrapped; }
        @Override public void addObserver(XdStorageAbstractIdObserver obs) { observers.add(obs); }
        @Override public Object getWrappedObject__() { return wrapped; }

        public void triggerNewId(Object id) {
            for (XdStorageAbstractIdObserver obs : observers) {
                obs.onNewIdIsSet(this, id);
            }
        }
    }
}
