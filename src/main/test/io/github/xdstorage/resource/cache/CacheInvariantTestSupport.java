package io.github.xdstorage.resource.cache;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общий стенд инвариантных тестов подпакета cache: легковесная реализация
 * {@link XdStorageCacheContext} (без фасада, без реестра канонических инстансов,
 * материализация — поверхностная копия) + фабрика мок-транзакций.
 */
class CacheInvariantTestSupport {

    /** Минимальная доменная сущность стенда (конвенции СУБД: no-arg ctor + getter/setter id). */
    public static class TestEntity {
        @XdStorageObjectId
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

    /**
     * Стаб-контекст: карты в памяти, живость транзакций управляемая, материализация
     * делает поверхностную копию доменных объектов (инфраструктура — живой инстанс).
     */
    static class StubCacheContext implements XdStorageCacheContext {

        final Map<Object, XdStorageCacheRecord> records = new ConcurrentHashMap<>();
        final XdStorageTransactionWorkspace workspace = new XdStorageTransactionWorkspace();
        final XdStorageObjectIdField idField;
        final Set<XdStorageTransaction> deadTransactions = ConcurrentHashMap.newKeySet();
        final List<Object> insertDelegations = new ArrayList<>();

        volatile boolean infrastructureCache;
        volatile boolean structuralNodeCache;
        volatile XdStorageIsolationLevel isolationLevel = XdStorageIsolationLevel.SNAPSHOT;

        StubCacheContext() {
            idField = mock(XdStorageObjectIdField.class);
            when(idField.get(any())).thenAnswer(inv -> {
                Object obj = inv.getArgument(0);
                return obj instanceof TestEntity ? ((TestEntity) obj).getId() : null;
            });
        }

        @Override
        public XdStorageObjectIdField idField() {
            return idField;
        }

        @Override
        public Map<Object, XdStorageCacheRecord> records() {
            return records;
        }

        @Override
        public XdStorageTransactionWorkspace workspace() {
            return workspace;
        }

        @Override
        public boolean isInfrastructureCache() {
            return infrastructureCache;
        }

        @Override
        public boolean isStructuralNodeCache() {
            return structuralNodeCache;
        }

        @Override
        public Class<?> entityClass() {
            return TestEntity.class;
        }

        @Override
        public boolean isTransactionAlive(final XdStorageTransaction transaction) {
            return !deadTransactions.contains(transaction);
        }

        @Override
        public XdStorageIsolationLevel getIsolationLevel() {
            return isolationLevel;
        }

        @Override
        public XdStorageCacheRecord createReadRecord(final Object object) {
            return XdStorageCacheRecord.newReadRecord(this, idField, object);
        }

        @Override
        public XdStorageCacheRecord createInsertRecord(final Object newObject, final XdStorageTransaction transaction) {
            return XdStorageCacheRecord.newInsertRecord(this, idField, newObject, transaction);
        }

        @Override
        public Object materializeForCache(final Object newObject) {
            if (infrastructureCache) {
                return newObject; // живая инстанция
            }
            if (newObject instanceof TestEntity) {
                TestEntity origin = (TestEntity) newObject;
                return new TestEntity(origin.getId(), origin.getValue());
            }
            return newObject;
        }

        @Override
        public void insert(final Object newObject, final XdStorageTransaction transaction) throws io.github.xdstorage.exceptions.XdStorageException {
            // Минимальная модель публичной вставки фасада: без guard'а дубликатов и
            // критических секций (моки транзакций делают их no-op), но с ПОЛНЫМ
            // контуром вставки мутатора — самозарвка (R-40) обязана создавать запись.
            insertDelegations.add(newObject);
            new XdStorageCacheMutator(this).insertInternal(newObject, transaction);
        }
    }

    static TestEntity entity(final Long id, final String value) {
        return new TestEntity(id, value);
    }

    static XdStorageTransaction mkTx(final String txId, final long timestart) {
        return mkTx(txId, timestart, 100L);
    }

    static XdStorageTransaction mkTx(final String txId, final long timestart, final long timeout) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(txId);
        when(tx.getTimestart()).thenReturn(timestart);
        when(tx.getTimeout()).thenReturn(timeout);
        // getSnapshotSerial/getCommitSerial не стабим: Mockito дает 0L — зарезервированный
        // sentinel "нет номера" (тот же контракт, что в мок-сьютах фасада).
        return tx;
    }

    static XdStorageTransaction withCommitSerial(final XdStorageTransaction tx, final long serial) {
        when(tx.getCommitSerial()).thenReturn(serial);
        return tx;
    }

    static XdStorageTransaction withSnapshotSerial(final XdStorageTransaction tx, final long serial) {
        when(tx.getSnapshotSerial()).thenReturn(serial);
        return tx;
    }

    static XdStorageTransaction withParent(final XdStorageTransaction tx, final XdStorageTransaction parent) {
        when(tx.getGlobalTransaction()).thenReturn(parent);
        return tx;
    }
}
