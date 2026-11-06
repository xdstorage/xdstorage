package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractBTreeIntegrationTest {

    protected Map<Long, XdStorageBTreeNode> nodeStorage;
    protected IXdStorage storageFacade;
    protected XdStorageTransaction transaction;
    protected XdStorageBTree btree;

    @BeforeEach
    void setUp() {
        nodeStorage = new ConcurrentHashMap<>();

        // Реальный фасад СУБД для ленивой материализации стабов (materialize)
        storageFacade = new IXdStorage() {
            private long idSequence = 1000L;

            @SuppressWarnings("unchecked")
            @Override
            public <T> T load(Class<T> cl, Object id, IXdStorageTransaction tx) {
                if (cl == XdStorageBTreeNode.class) {
                    return (T) nodeStorage.get((Long) id);
                }
                return null;
            }

            @Override
            public void save(Object obj, IXdStorageTransaction tx) {
                if (obj instanceof XdStorageBTreeNode) {
                    XdStorageBTreeNode node = (XdStorageBTreeNode) obj;
                    if (node.getId() == null) {
                        node.setId(++idSequence);
                    }
                    nodeStorage.put(node.getId(), node);
                }
            }

            @Override
            public void update(Object obj, IXdStorageTransaction tx) {
                if (obj instanceof XdStorageBTreeNode) {
                    XdStorageBTreeNode node = (XdStorageBTreeNode) obj;
                    nodeStorage.put(node.getId(), node);
                }
            }

            @Override
            public void delete(Object obj, IXdStorageTransaction tx) {
                if (obj instanceof XdStorageBTreeNode) {
                    nodeStorage.remove(((XdStorageBTreeNode) obj).getId());
                }
            }

            @Override public String getName() { return "BTreeInMemoryStorage"; }
            @Override public io.github.xdstorage.metrics.XdStorageMetrics getMetrics() { return null; }
            @Override public <T> void registerTrigger(io.github.xdstorage.trigger.IXdStorageTrigger<T> t) {}
            @Override public IXdStorageTransaction beginTransaction() { return null; }
            @Override public IXdStorageTransaction beginTransaction(long timeout) { return null; }
            @Override public IXdStorageTransaction beginTransaction(IXdStorageTransaction tx) { return null; }
            @Override public IXdStorageTransaction beginTransaction(IXdStorageTransaction tx, long timeout) { return null; }
            @Override public void commitTransaction(IXdStorageTransaction tx) {}
            @Override public void rollbackTransaction(IXdStorageTransaction tx) {}
            @Override public void save(Object o) {}
            @Override public void save(Collection<?> o) {}
            @Override public void save(Collection<?> o, IXdStorageTransaction tx) {}
            @Override public boolean has(Class<?> c, Object id) { return false; }
            @Override public boolean has(Class<?> c, Object id, IXdStorageTransaction tx) { return false; }
            @Override public void load(Object r) {}
            @Override public void load(Object r, IXdStorageTransaction tx) {}
            @Override public <T> T load(Class<T> c, Object id) { return null; }
            @Override public void load(Collection<?> r) {}
            @Override public void load(Collection<?> r, IXdStorageTransaction tx) {}
            @Override public <T> Collection<T> load(Class<T> c) { return null; }
            @Override public <T> Collection<T> load(Class<T> c, IXdStorageTransaction tx) { return null; }

            @Override
            public void update(Object object) throws XdStorageException, XdStorageConnectionException {

            }

            @Override public void update(Collection<?> o) {}
            @Override public void update(Collection<?> o, IXdStorageTransaction tx) {}

            @Override
            public void delete(Object reference) throws XdStorageException, XdStorageConnectionException {

            }

            @Override public void delete(Collection<?> r) {}
            @Override public void delete(Collection<?> r, IXdStorageTransaction tx) {}
            @Override public io.github.xdstorage.transaction.IXdStorageTransactionManager getTransactionManager() { return null; }
            @Override public void shutdown() {}
        };

        // Живая транзакция для прохождения таймстамп-контроля и лимитов блокировок
        transaction = new XdStorageTransaction(null, "THREAD_1", null, 100L, "TX_1") {
            @Override public void startCriticalSection(boolean r) {}
            @Override public void finishCriticalSection() {}
        };

        // Инициализируем MULTI-дерево с t = 5 согласно предусловию
        XdStorageBTreeId treeId = new XdStorageBTreeId(AbstractBTreeIntegrationTest.class, "spec_multi_index");
        btree = new XdStorageBTree(treeId, true, 5);
    }
}
