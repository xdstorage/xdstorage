package io.github.xdstorage.resource.cache;

import io.github.xdstorage.btree.XdStorageBTreeNode;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.BeforeEach;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractCacheMutatorIntegrationTest {

    protected TestCacheContext cacheContext;
    protected XdStorageTransactionWorkspace workspace;
    protected TestListener listener;
    protected XdStorageTransaction transaction;
    protected XdStorageCacheMutator mutator;

    @BeforeEach
    void setUp() {
        workspace = new XdStorageTransactionWorkspace();
        cacheContext = new TestCacheContext(workspace);
        listener = new TestListener();

        mutator = new XdStorageCacheMutator(cacheContext);
        mutator.setInternalListener(listener);

        // Настоящий инстанс транзакции через наследника (чтобы не мокать конструктор)
        transaction = new XdStorageTransaction(
                null, // IXdStorageTransactionManager нам не нужен для тестов мутатора
                String.valueOf(Thread.currentThread().getId()),
                null,
                5000L,
                "TX_INTEGRATION_TEST"
        ) {
            // Переопределяем методы критических секций, чтобы они не падали без менеджера
            @Override public void startCriticalSection(boolean r) {}
            @Override public void finishCriticalSection() {}
        };
    }

    // --- Доменный объект для тестов ---
    protected static class TestDomainEntity {
        Long id;
        String data;
        TestDomainEntity(Long id, String data) { this.id = id; this.data = data; }
    }

    // --- Реальный Listener для сбора вызовов ---
    protected static class TestListener implements IXdStorageCacheInternalListener {
        List<Object> insertCalls = new ArrayList<>();
        List<Object[]> updateCalls = new ArrayList<>();
        List<Object> deleteCalls = new ArrayList<>();

        @Override public void insertRegistered(Object obj, IXdStorageTransaction tx) { insertCalls.add(obj); }
        @Override public void updateRegistered(Object oldObj, Object newObj, IXdStorageTransaction tx) { updateCalls.add(new Object[]{oldObj, newObj}); }
        @Override public void deleteRegistered(Object obj, IXdStorageTransaction tx) { deleteCalls.add(obj); }
    }

    // --- Реальный इन-memory Контекст ---
    protected static class TestCacheContext implements XdStorageCacheContext {
        private final Map<Object, XdStorageCacheRecord> internalRecords = new ConcurrentHashMap<>();
        private final XdStorageTransactionWorkspace workspace;
        private boolean isInfra = false;
        private Class<?> entityClass = TestDomainEntity.class;

        public TestCacheContext(XdStorageTransactionWorkspace workspace) { this.workspace = workspace; }

        public void switchToDomainMode(Class<?> clazz) { this.isInfra = false; this.entityClass = clazz; }
        public void switchToInfrastructureMode(Class<?> clazz) { this.isInfra = true; this.entityClass = clazz; }

        @Override public Map<Object, XdStorageCacheRecord> records() { return internalRecords; }
        @Override public XdStorageTransactionWorkspace workspace() { return workspace; }
        @Override public boolean isInfrastructureCache() { return isInfra; }
        @Override public boolean isStructuralNodeCache() { return isInfra && entityClass == XdStorageBTreeNode.class; }
        @Override public Class<?> entityClass() { return entityClass; }
        @Override public XdStorageIsolationLevel getIsolationLevel() { return XdStorageIsolationLevel.SNAPSHOT; }
        @Override public boolean isTransactionAlive(XdStorageTransaction tx) { return true; }

        @Override
        public XdStorageObjectIdField idField() {
            return new XdStorageObjectIdField(null, null, null, null) {
                @Override
                public Object get(Object obj) {
                    if (obj instanceof TestDomainEntity) return ((TestDomainEntity) obj).id;
                    if (obj instanceof XdStorageBTreeNode) return ((XdStorageBTreeNode) obj).getId();
                    return null;
                }
            };
        }

        @Override
        public XdStorageCacheRecord createReadRecord(Object object) {
            return XdStorageCacheRecord.newReadRecord(this, idField(), object);
        }

        @Override
        public XdStorageCacheRecord createInsertRecord(Object newObject, XdStorageTransaction transaction) {
            return XdStorageCacheRecord.newInsertRecord(this, idField(), newObject, transaction);
        }

        @Override public Object materializeForCache(Object obj) { return obj; }

        @Override
        public void insert(Object obj, XdStorageTransaction tx) throws XdStorageException {
            Object id = idField().get(obj);
            XdStorageCacheRecord rec = createInsertRecord(obj, tx);
            internalRecords.put(id, rec);
            workspace.registerMutation(tx.getTransactionId(), id, rec);
            workspace.readObjectsOf(tx).put(id, new XdStorageCacheObjectChange(obj, XdStorageCacheChange.insert, tx.getTimestart()));
        }
    }
}
