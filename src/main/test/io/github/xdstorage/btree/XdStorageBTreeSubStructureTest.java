package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Однопоточные тесты: Валидация чтения подчиненных структур B+ Дерева")
public class XdStorageBTreeSubStructureTest {

    private IXdStorage mockStorage;
    private IXdStorageTransactionManager mockTxManager;

    @BeforeEach
    public void setUp() throws Exception {
        mockStorage = mock(IXdStorage.class);

        // Потокобезопасный анонимный стаб менеджера транзакций (канонический JVM-скоростной контракт)
        mockTxManager = new IXdStorageTransactionManager() {
            @Override public IXdStorage getStorage() { return mockStorage; }
            @Override public IXdStorageTransaction beginTransaction(long timeout) { return null; }
            @Override public IXdStorageTransaction beginTransaction(IXdStorageTransaction tx, long timeout) { return null; }
            @Override public IXdStorageTransaction getTransaction(String txId) { return null; }
            @Override public void commitTransaction(IXdStorageTransaction tx) {}
            @Override public void rollbackTransaction(IXdStorageTransaction tx) {}
            @Override public XdStorageTransaction getCurrentTransaction() { return null; }
            @Override public boolean isTransactionAlive(IXdStorageTransaction tx) { return true; }
            @Override public void registerRollbackOnlyTransaction(IXdStorageTransaction tx) {}
        };

        // Перехватываем обновления страниц на диске, эмулируя моментальное сохранение в память сессии
        Mockito.doAnswer(inv -> null).when(mockStorage).save(Mockito.any(), Mockito.any());
        Mockito.doAnswer(inv -> null).when(mockStorage).update(Mockito.any(), Mockito.any());
    }

    private XdStorageTransaction createMockTransaction(String txId) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(txId);
        when(tx.getTimestart()).thenReturn(System.currentTimeMillis());
        when(tx.getTimeout()).thenReturn(100L);
        return tx;
    }

    @Test
    @DisplayName("Проверка: Запись и последующее чтение подчиненных дубликатов ключей в B+ Дереве")
    public void testBTree_InsertAndThenLoadSubStructures_ShouldBeConsistent() throws Exception {
        // Создаем множественное дерево индексов (multiple = true), как для планет СУБД
        final XdStorageBTreeId treeId = new XdStorageBTreeId(String.class, "sub_structure_index");
        final XdStorageBTree bTree = new XdStorageBTree(treeId, true, 2);

        final String targetKey = "star-system-1";
        final int recordsCount = 10; // Имитируем 10 подчиненных планет в одной системе

        // =========================================================================
        // ФАЗА 1: ЗАПИСЬ (Выполняется в рамках транзакции вставки TX-WRITE)
        // =========================================================================
        XdStorageTransaction writeTx = createMockTransaction("TX-WRITE-100");

        System.out.println("=== ФАЗА ЗАПИСИ ПОДЧИНЕННЫХ СТРУКТУР В ДЕРЕВО ===");
        for (int i = 0; i < recordsCount; i++) {
            String planetResourceId = "planet-resource-id-" + i;
            bTree.insert(targetKey, planetResourceId, mockStorage, writeTx);
            System.out.println("INSERTED TO INDEX: Key=" + targetKey + " | ValueRef=" + planetResourceId);
        }

        // Мягкая пауза, чтобы развести Snapshot-время
        Thread.sleep(5);

        // =========================================================================
        // ФАЗА 2: ЧТЕНИЕ (Выполняется в рамках СОВЕРШЕННО НОВОЙ транзакции TX-READ)
        // =========================================================================
        XdStorageTransaction readTx = createMockTransaction("TX-READ-200");

        System.out.println("=== ФАЗА ЧТЕНИЯ ПОДЧИНЕННЫХ СТРУКТУР ИЗ ДЕРЕВА ===");

        // Пытаемся найти подчиненные структуры по нашему ключу
        List<Object> searchResult = bTree.find(targetKey, mockStorage, readTx);

        // Верифицируем Snapshot-инвариант
        assertNotNull(searchResult, "🚨 Дерево вернуло null вместо списка подчиненных нод!");

        System.out.println("📊 РЕЗУЛЬТАТЫ ПРОВЕРКИ СТРУКТУР ДЕРЕВА:");
        System.out.println("🔹 Ожидали подчиненных элементов: " + recordsCount);
        System.out.println("🔹 Фактически извлечено из дерева: " + searchResult.size());

        for (int i = 0; i < searchResult.size(); i++) {
            System.out.println("  -> Ссылка на ресурс [" + i + "]: " + searchResult.get(i));
        }

        assertEquals(recordsCount, searchResult.size(),
                "🚨 КАТАСТРОФА! Рассинхронизация дерева: после коммита вставки новая транзакция чтения потеряла подчиненные структуры!");
        System.out.println("🎉 УСПЕХ! Однопоточный контракт чтения подчиненных структур дерева работает безупречно!");
    }
}
