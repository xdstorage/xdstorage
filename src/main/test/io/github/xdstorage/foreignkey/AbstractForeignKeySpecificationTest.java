package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
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

    /**
     * MASSFILL: seeds an entity into the in-memory storage so the harness's
     * {@code load(Class, id, tx)} (a plain map lookup) resolves it. The map's declared
     * key type is the tree id, but the load path looks entities up by their raw ids —
     * this helper encapsulates the unchecked cross-type put for the tests that need
     * their entities visible to the engine (e.g. the delete-veto scenarios).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void seedEntity(final Object id, final Object entity) {
        ((Map) databaseStorage).put(id, entity);
    }


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

        final io.github.xdstorage.serialization.IXdStorageObjectsReader mockReader = org.mockito.Mockito.mock(io.github
                .xdstorage.serialization.IXdStorageObjectsReader.class);
        final io.github.xdstorage.serialization.IXdStorageObjectsWriter mockWriter = org.mockito.Mockito.mock(io.github
                .xdstorage.serialization.IXdStorageObjectsWriter.class);
        // Сборка локатора сервисов на реальных in-memory структурах
        final XdStorageAbstractResourcesManager[] managerHolder = new XdStorageAbstractResourcesManager[1];
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
                    @Override public IXdStorageTransaction beginTransaction(IXdStorageTransaction tx, long timeout) {
                            return null; }
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
                    @Override public io.github.xdstorage.transaction.IXdStorageTransactionManager
                            getTransactionManager() { return null; }
                    @Override public void shutdown() {}
                    @Override public void checkpoint() {}
                };
            }

            @Override public io.github.xdstorage.resource.IXdStorageResourceNamingService getNamingService() { return
                    namingService; }
            @Override public XdStorageCanonicalInstanceRegistry getCanonicalInstances() { return canonicalRegistry; }
            @Override public io.github.xdstorage.serialization.IXdStorageIOFactory getIoFactory() {
                // A REAL YamlIOFactory (not a bare mock): the references resource's commit
                // journal replay calls XdStorageYamlIOFactory.simpleTypeHelper() — a bare
                // mock made that NPE ("commit journal cannot be replayed").
                return new io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory(
                        new io.github.xdstorage.services.XdStorageServicesLocator() {
                            @Override public io.github.xdstorage.resource.IXdStorageResourceNamingService
                                    getNamingService() { return namingService; }
                        }, null);
            }
            @Override public io.github.xdstorage.idgeneration.IXdStorageIdGenerator getIdGenerator() { return null; }
            @Override public io.github.xdstorage.resource.XdStorageAbstractResourcesManager getResourcesManager() {
                    return managerHolder[0]; }
        };

        // MASSFILL: a REAL resources manager (was: mockResourcesManager + a mockDao whose
        // read() returned non-null ALWAYS — that stub blinded every references-based FK
        // check in the harness: isParentPersistedInIndex/isChildInParentIndex saw every id
        // as "persisted", so the pending manual-ID registration and the orphan/delete
        // vetoes could never fire). The real manager creates real on-demand resources over
        // the in-memory storage above; unsaved ids honestly read as absent.
        final io.github.xdstorage.resource.XdStorageAbstractResourcesManager realResourcesManager =
                new io.github.xdstorage.resource.XdStorageResourcesManager(services, 64);
        managerHolder[0] = realResourcesManager;

        resourcesManager = new XdStorageForeignKeyResourcesManager(services, realResourcesManager);
        engine = new XdStorageForeignKeyEngine(resourcesManager, registry, true);

        // Инициализируем транзакцию с отслеживанием rollback-only статуса
        // MASSFILL/timeout-sweeper: a short timeout lets the real background sweeper mark
        // the harness transaction rollback-only mid-test — see the mirror fix in
        // AbstractForeignKeyEngineIntegrationTest.
        transaction = new XdStorageTransaction(null, "THREAD_1", null, 600000L, "TX_1") {
            private final Set<Object> registeredResources = new HashSet<>();
            @Override public void startCriticalSection(boolean r) {}
            @Override public void finishCriticalSection() {}
            @Override public boolean isResourceRegistered(io.github.xdstorage.resource.IXdStorageResourceObject res) {
                return registeredResources.contains(res.getResourceId());
            }
            @Override public void registerResource(io.github.xdstorage.resource.IXdStorageResourceObject res, int order)
                    {
                registeredResources.add(res.getResourceId());
            }
        };
    }
}
