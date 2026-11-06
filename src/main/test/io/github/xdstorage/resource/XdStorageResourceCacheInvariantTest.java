package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Архитектурные тесты инвариантов: Верификация контрактов XdStorageResourceCache")
public class XdStorageResourceCacheInvariantTest {

    private XdStorageServicesLocator mockLocator;
    private IXdStorageCloner mockCloner;
    private IXdStorage mockStorage;
    private XdStorageObjectIdField mockField;
    private XdStorageResourceCache resourceCache;

    private XdStorageResource resource;

    // Простая сущность для изоляции тестов кэша ресурсов
    // Конструктор без аргументов обязателен по конвенции СУБД для генерации оберток;
    // @XdStorageObjectId + сеттер переносят ID в глубокие MVCC-копии
    public static class SampleNode {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private Long id;
        private String data;

        public SampleNode() {}
        public SampleNode(Long id, String data) {
            this.id = id;
            this.data = data;
        }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }

    @BeforeEach
    public void setUp() throws Exception {
        mockLocator = mock(XdStorageServicesLocator.class);
        mockCloner = mock(IXdStorageCloner.class);
        mockStorage = mock(IXdStorage.class);
        mockField = mock(XdStorageObjectIdField.class);
        resource = mock(XdStorageResource.class);

        // Стабильный, потокобезопасный менеджер транзакций (без ведения истории Mockito)
        IXdStorageTransactionManager mockTxManager = new IXdStorageTransactionManager() {
            @Override public IXdStorage getStorage() { return mockStorage; }
            @Override public XdStorageTransaction beginTransaction(long timeout) { return null; }

            @Override
            public IXdStorageTransaction beginTransaction(IXdStorageTransaction transaction, long timeout) {
                return null;
            }

            @Override public XdStorageTransaction getTransaction(String txId) { return null; }

            @Override
            public void commitTransaction(IXdStorageTransaction transaction) {

            }

            @Override
            public void rollbackTransaction(IXdStorageTransaction transaction) {

            }

            @Override public XdStorageTransaction getCurrentTransaction() { return null; }

            @Override
            public boolean isTransactionAlive(IXdStorageTransaction transaction) {
                return false;
            }

            @Override
            public void registerRollbackOnlyTransaction(IXdStorageTransaction transaction) {

            }
        };

        when(mockLocator.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockLocator.getCloner()).thenReturn(mockCloner);
        when(mockLocator.getStorage()).thenReturn(mockStorage);

        when(mockField.get(any())).thenAnswer(inv -> {
            Object obj = inv.getArgument(0);
            return obj instanceof SampleNode ? ((SampleNode) obj).getId() : null;
        });

        // Каноническое поведение клонера: честное создание изолированного Snapshot-объекта
        when(mockCloner.cloneAndWrap(any(), any(), any())).thenAnswer(inv -> {
            SampleNode orig = inv.getArgument(0);
            return new SampleNode(orig.getId(), orig.getData());
        });

        resourceCache = new XdStorageResourceCache(mockLocator, SampleNode.class, mockField);
    }

    private XdStorageTransaction createTx(String txId, long timestart) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(txId);
        when(tx.getTimestart()).thenReturn(timestart);
        when(tx.getTimeout()).thenReturn(100L);
        return tx;
    }

    // =========================================================================
    // ИНВАРИАНТ 1: ДВУХФАЗНЫЙ КОММИТ (2PC CONTRACT VALIDATION)
    // =========================================================================
    @Test
    @DisplayName("Инвариант 1: Прямой коммит в обход фаз prepare/perform обязан взрываться исключением")
    public void testInvariant1_DirectCommitWithout2PC_ShouldThrowException() throws Exception {
        XdStorageTransaction tx = createTx("TX-1", System.nanoTime());
        SampleNode node = new SampleNode(100L, "Data_100");

        resourceCache.insert(node, tx);

        // Пытаемся закоммитить "в лоб" без вызова prepareCommit и performCommit
        assertThrows(RuntimeException.class, () -> {
            resourceCache.commit(tx);
        }, "Кэш обязан заблокировать коммит, если нарушена двухфазная цепочка флагов!");
    }

    // =========================================================================
    // ИНВАРИАНТ 2: ИЗОЛЯЦИЯ ИДЕНТИЧНОСТИ КЛЮЧЕЙ (IDENTITY REGISTRATION)
    // =========================================================================
    @Test
    @DisplayName("Инвариант 2: readByReference обязан сохранять объект в readObjects под нормализованным ID, а не прокси")
    public void testInvariant2_ReadByReference_ShouldRegisterWithNormalizedKey() throws Exception {
        XdStorageTransaction tx = createTx("TX-2", System.nanoTime());
        SampleNode node = new SampleNode(200L, "Data_200");

        // Преднаполняем кэш стабильной записью
        List<Object> initial = new ArrayList<>();
        initial.add(node);
        resourceCache.fillCache(initial);

        // Имитируем прокси-ссылку, которая прилетает из обертки СУБД
        SampleNode proxyReference = new SampleNode(200L, null);

        // Выполняем подгрузку по референсу
        resourceCache.readByReference(proxyReference, tx);

        // ПРОВЕРКА ИНВАРИАНТА: Теперь плоский метод чтения readInternal(id) ОБЯЗАН мгновенно
        // найти эту запись в транзитной мапе readObjects по сырому Long ID, не промахиваясь мимо кэша!
        Object foundObject = resourceCache.read(200L, tx);
        assertNotNull(foundObject, "🚨 КРАХ ИНВАРИАНТА ИДЕНТИЧНОСТИ! Плоское чтение по Long ID вернуло null после readByReference!");
    }

    // =========================================================================
    // ИНВАРИАНТ 3: ИЗОЛЯЦИЯ МОДИФИКАЦИЙ В ПАРАЛЛЕЛЬНЫХ СEССИЯХ (MVCC READ VIEW)
    // =========================================================================
    @Test
    @DisplayName("Инвариант 3: Незакоммиченные изменения транзакции А не должны быть видны транзакции Б")
    public void testInvariant3_MvccReadView_UncommittedChangesShouldBeIsolated() throws Exception {
        XdStorageTransaction txWriter = createTx("TX-WRITER", System.nanoTime());
        XdStorageTransaction txReader = createTx("TX-READER", System.nanoTime() + 100);

        SampleNode originalNode = new SampleNode(300L, "Original_Stable_Data");
        List<Object> initial = new ArrayList<>();
        initial.add(originalNode);
        resourceCache.fillCache(initial);

        // Транзакция писателя изменяет объект в оперативной памяти кэша
        SampleNode updatedNode = new SampleNode(300L, "Dirty_Uncommitted_Data");
        resourceCache.update(updatedNode, txWriter);

        // ПРОВЕРКА ИНВАРИАНТА: Параллельный читатель обязан увидеть оригинальное закоммиченное состояние!
        SampleNode readByTargetTx = (SampleNode) resourceCache.read(300L, txReader);
        assertNotNull(readByTargetTx);
        assertEquals("Original_Stable_Data", readByTargetTx.getData(),
                "🚨 КРАХ MVCC-ИНВАРИАНТА! Читатель увидел грязные, еще незакоммиченные данные параллельной сессии!");
    }

    // =========================================================================
    // ИНВАРИАНТ 4: СТАБИЛЬНОСТЬ SNAPSHOT-ЧТЕНИЯ ПРИ УДАЛЕНИИ (SOFT DELETE PURGE)
    // =========================================================================
    @Test
    @DisplayName("Инвариант 4: Метод диапазонного чтения read() обязан возвращать стабильный снимок, даже если объект удален параллельно")
    public void testInvariant4_PredicateRead_ShouldBeResilientToParallelDeletes() throws Exception {
        XdStorageTransaction txReader = createTx("TX-DIAPASONE-READER", System.nanoTime());
        XdStorageTransaction txDeleter = createTx("TX-DELETER", System.nanoTime() + 500);

        SampleNode node = new SampleNode(400L, "Planet_400");
        List<Object> initial = new ArrayList<>();
        initial.add(node);
        resourceCache.fillCache(initial);

        // Транзакция удаления помечает ноду стейтом Change.delete и фиксирует 2PC-коммит
        resourceCache.delete(400L, txDeleter);
        XdStorageTransactionResourceChanges collector = new XdStorageTransactionResourceChanges(resource);
        resourceCache.prepareCommit(txDeleter, collector);
        resourceCache.performCommit(txDeleter, collector);
        resourceCache.commit(txDeleter); // Физически cache.remove(400L) сработал!

        // ПРОВЕРКА ИНВАРИАНТА: Наша транзакция чтения стартовала ДО момента этого удаления.
        // Метод read(predicate) по контракту Snapshot Isolation ОБЯЗАН вернуть объект из истории!
        Collection<SampleNode> scannedCollection = resourceCache.read(txReader, obj -> true);

        assertFalse(scannedCollection.isEmpty(),
                "🚨 КРАХ SNAPSHOT-ИНВАРИАНТА! Диапазонный сканер потерял элемент, который был удален после старта транзакции чтения!");
        assertEquals(1, scannedCollection.size());
    }
}
