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

    /**
     * MASSFILL: the in-memory ENTITY storage (per class+id). Was: save() dropped
     * non-btree objects and load() always returned null — every test flow that
     * round-tripped an entity got "object cannot be null" from the next update/delete.
     * The FK engine's flows (deferred-ID waiters, veto scenarios) need the entities
     * visible across process()/storage calls within a test.
     */
    protected final Map<String, Object> entityStorage = new ConcurrentHashMap<>();

    private String entityKey(final Class<?> cl, final Object id) {
        return cl.getName() + "\u001F" + id;
    }
    protected XdStorageCanonicalInstanceRegistry canonicalRegistry;
    protected XdStorageResourceNamingService namingService;
    protected io.github.xdstorage.serialization.IXdStorageObjectsReader mockReader;
    protected io.github.xdstorage.serialization.IXdStorageObjectsWriter mockWriter;

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

        final io.github.xdstorage.serialization.IXdStorageObjectsReader mockReader = org.mockito.Mockito.mock(io.github.xdstorage.serialization.IXdStorageObjectsReader.class);
        final io.github.xdstorage.serialization.IXdStorageObjectsWriter mockWriter = org.mockito.Mockito.mock(io.github.xdstorage.serialization.IXdStorageObjectsWriter.class);

        // XdStorageYamlIOFactory for commit-journal replay — must be created before the
        // services locator because getIoFactory() references it (see T-FK-006 fix).
        final io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory yamlFactory =
                new io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory(
                        new io.github.xdstorage.services.XdStorageServicesLocator() {
                            @Override public io.github.xdstorage.resource.IXdStorageResourceNamingService getNamingService() { return namingService; }
                        }, null);

        // Сборка локатора сервисов на реальных in-memory структурах
        final XdStorageServicesLocator services = new XdStorageServicesLocator() {
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
                        } else {
                            try {
                                final Class<?> entityClass = io.github.xdstorage.utils.XdStorageObjectUtils.getEntityClass(obj.getClass());
                                final Object objId = io.github.xdstorage.utils.XdStorageObjectUtils.getClassInfo(entityClass).getIdField().get(obj);
                                if (objId != null) {
                                    entityStorage.put(entityKey(entityClass, objId), obj);
                                }
                            } catch (final Exception noId) {
                                // not an id-bearing entity — nothing to store
                            }
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
                        if (cl == XdStorageBTree.class || cl == io.github.xdstorage.btree.XdStorageBTreeNode.class) {
                            return (T) databaseStorage.get(id);
                        }
                        return (T) entityStorage.get(entityKey(cl, id));
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

            @Override
            public XdStorageAbstractResourcesManager getResourcesManager() {
                return abstractResourcesManager;
            }

            @Override public io.github.xdstorage.resource.IXdStorageResourceNamingService getNamingService() { return namingService; }
            @Override public XdStorageCanonicalInstanceRegistry getCanonicalInstances() { return canonicalRegistry; }
            @Override public io.github.xdstorage.serialization.IXdStorageIOFactory getIoFactory() {
                // Return a real XdStorageYamlIOFactory (not a bare mock) so that
                // XdStorageResource.yamlJournalFactory != null and commit-journal replay
                // does not crash on NPE from a null factory.
                return yamlFactory;
            }
            @Override public io.github.xdstorage.idgeneration.IXdStorageIdGenerator getIdGenerator() { return null; }
        };

        abstractResourcesManager = new XdStorageResourcesManager(services, 64);
        resourcesManager = new XdStorageForeignKeyResourcesManager(services, abstractResourcesManager);
        engine = new XdStorageForeignKeyEngine(resourcesManager, registry, true);

        // MASSFILL/timeout-sweeper: a short timeout here lets the real background sweeper mark
        // the harness transaction rollback-only (and roll its staged pairs back) MID-TEST —
        // the mock spec tests then see "nothing was thrown" / isRollbackOnly=true at random.
        // The harness transaction lives for the whole test method: give it a test-scale
        // timeout (10 minutes), matching the real-storage suites' transaction budgets.
        transaction = new XdStorageTransaction(null, "THREAD_1", null, 600000L, "TX_1") {
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
