package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.resource.XdStorageResourceCache;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Многопоточные стресс-тесты: Конкурентное удаление и сканирование XdStorageResourceCache")
@Tag("stress")
public class XdStorageResourceCacheDeleteReadStressTest {

    private XdStorageServicesLocator mockLocator;
    private IXdStorageTransactionManager mockTxManager;
    private IXdStorageCloner mockCloner;
    private IXdStorage mockStorage;
    private XdStorageObjectIdField mockField;
    private XdStorageResourceCache resourceCache;

    // Доменная сущность для стресс-тестирования
    public static class TestEntity {
        private Long id;
        private String value;

        public TestEntity(Long id, String value) {
            this.id = id;
            this.value = value;
        }

        public Long getId() {
            return id;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        mockLocator = mock(XdStorageServicesLocator.class);
        mockCloner = mock(IXdStorageCloner.class);
        mockStorage = mock(IXdStorage.class);
        mockField = mock(XdStorageObjectIdField.class);

        // Потокобезопасный анонимный стаб менеджера транзакций
        mockTxManager = new IXdStorageTransactionManager() {
            @Override
            public IXdStorage getStorage() {
                return mockStorage;
            }

            @Override
            public IXdStorageTransaction beginTransaction(long timeout) {
                return null;
            }

            @Override
            public IXdStorageTransaction beginTransaction(IXdStorageTransaction tx, long timeout) {
                return null;
            }

            @Override
            public IXdStorageTransaction getTransaction(String txId) {
                return null;
            }

            @Override
            public void commitTransaction(IXdStorageTransaction tx) {
            }

            @Override
            public void rollbackTransaction(IXdStorageTransaction tx) {
            }

            @Override
            public XdStorageTransaction getCurrentTransaction() {
                return null;
            }

            @Override
            public boolean isTransactionAlive(IXdStorageTransaction tx) {
                return true;
            }

            @Override
            public void registerRollbackOnlyTransaction(IXdStorageTransaction tx) {
            }
        };

        when(mockLocator.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockLocator.getCloner()).thenReturn(mockCloner);
        when(mockLocator.getStorage()).thenReturn(mockStorage);

        when(mockField.get(any())).thenAnswer(invocation -> {
            Object obj = invocation.getArgument(0);
            if (obj instanceof TestEntity) return ((TestEntity) obj).getId();
            return null;
        });

        when(mockCloner.cloneAndWrap(any(), any(), any())).thenAnswer(invocation -> {
            TestEntity original = invocation.getArgument(0);
            return new TestEntity(original.getId(), original.getValue());
        });

        resourceCache = new XdStorageResourceCache(mockLocator, TestEntity.class, mockField);

        // Преднаполняем кэш ресурсами (ID 1 - 500) для стабильной работы удаления
        List<Object> initialObjects = new ArrayList<>();
        for (long i = 1; i <= 500; i++) {
            initialObjects.add(new TestEntity(i, "Initial_Value_" + i));
        }
        resourceCache.fillCache(initialObjects);
    }

    private XdStorageTransaction createMockTransaction(String txId, long timestart) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(txId);
        when(tx.getTimestart()).thenReturn(timestart);
        when(tx.getTimeout()).thenReturn(100L);
        return tx;
    }

    @Test
    @DisplayName("Стресс-кейс: Каскадные удаления (Delete) против параллельного прецизионного сканирования (Read)")
    public void testCache_ParallelDeletes_AgainstPredicateScans_ShouldBeNPEProof() throws Exception {
        int threadsCount = 16;
        int operationsPerThread = 200;

        ExecutorService pool = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadsCount);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger activeReadsCount = new AtomicInteger(0);

        for (int i = 0; i < threadsCount; i++) {
            final int threadIdx = i;
            pool.submit(() -> {
                try {
                    startLatch.await(); // Одновременный залп!
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                for (int j = 0; j < operationsPerThread; j++) {
                    String txId = "tx-delete-stress-" + threadIdx + "-" + j;
                    XdStorageTransaction tx = createMockTransaction(txId, System.nanoTime());
                    try {
                        if (threadIdx % 2 == 0) {
                            // ПОТОКИ ЗАПИСИ/УДАЛЕНИЯ: Выбирают случайный существующий ID и пытаются его удалить
                            long targetId = ThreadLocalRandom.current().nextLong(1, 501);

                            try {
                                resourceCache.delete(targetId, tx);
                            } catch (Exception e) {
                                // Допускаем контролируемые эксепшены, если объект уже помечен на удаление
                            }

                            // Микро-пауза для провокации race condition видимости ссылок
                            if (ThreadLocalRandom.current().nextBoolean()) {
                                Thread.yield();
                            }

                            // Логика восстановления: если объект удален, мгновенно делаем insert,
                            // чтобы поддерживать высокую плотность данных в кэше
                            if (!resourceCache.hasObject(targetId)) {
                                TestEntity restored = new TestEntity(targetId, "Restored_Value_" + targetId);
                                resourceCache.insert(restored, tx);
                            }
                        } else {
                            // ПОТОКИ ЧТЕНИЯ: Непрерывно сканируют кэш по предикату
                            activeReadsCount.incrementAndGet();
                            resourceCache.read(tx, new IXdStoragePredicate<TestEntity>() {
                                @Override
                                public boolean passed(TestEntity wrappedObject) {
                                    // 🚨 КРИТИЧЕСКАЯ ТОЧКА: Если внутри read() итератор наткнется на
                                    // частично удаленную ноду или ConcurrentModificationException,
                                    // поток мгновенно выбросит ошибку!
                                    return wrappedObject != null && wrappedObject.getValue() != null;
                                }
                            });
                            // Снижаем транзакционный контур
//                            resourceCache.clear(tx);
                            XdStorageTransactionResourceChanges collector = new XdStorageTransactionResourceChanges(
                                    mock(IXdStorageResourceObject.class)
                            );
                            resourceCache.prepareCommit(tx, collector);
                            resourceCache.performCommit(tx, collector);
                            resourceCache.commit(tx);
                        }
                    } catch (Throwable e) {
                        exceptions.add(e);
                        try {
                            resourceCache.rollback(tx);
                        } catch (XdStorageException ex) {
                            throw new RuntimeException(ex);
                        }
                    } finally {
                        finishLatch.countDown();
                    }
                }
            });
        }

        startLatch.countDown(); // Погнали!
        boolean finishedCleanly = finishLatch.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        exceptions.forEach(e -> e.printStackTrace(System.err));

        assertTrue(finishedCleanly, "🚨 ОБНАРУЖЕН DEADLOCK КЭША РЕСУРСОВ ПРИ УДАЛЕНИИ/ЧТЕНИИ!");
        assertTrue(exceptions.isEmpty(), "🚨 Зафиксированы падения NullPointerException или грязные снимки: " + exceptions);
        System.out.println("🎉 УСПЕХ! Тест на удаление и чтение прошел чисто. Выполнено чтений по предикату: " + activeReadsCount.get());
    }
}
