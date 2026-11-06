package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("4. Конкурентный стресс-тест блокировок, потоков и таймаутов")
public class XdStorageCacheConcurrencyTest extends AbstractCacheResourceTest {

    @Test
    @DisplayName("Параллельное изменение одного объекта должно вызывать таймаут блокировки записи")
    public void testLock_ContentionTimeout_ShouldThrowXdStorageException() throws Exception {
        TestEntity sharedEntity = new TestEntity("Shared");
        long id = 555L;
        when(mockIdField.get(sharedEntity)).thenReturn(id);
        resourceCache.fillCache(Collections.singletonList(sharedEntity));

        XdStorageTransaction txMonopolist = mockTransaction("tx-monopolist");
        XdStorageTransaction txVictim = mockTransaction("tx-victim");
        when(txVictim.getTimeout()).thenReturn(100L); // Жесткий короткий таймаут

        TestEntity update1 = new TestEntity("Upd1");
        when(mockIdField.get(update1)).thenReturn(id);
        when(mockCloner.unwrapAndClone(update1)).thenReturn(update1);

        resourceCache.update(update1, txMonopolist);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> {
            XdStorageRuntimeException thrown = assertThrows(XdStorageRuntimeException.class, () -> {
                resourceCache.update(update1, txVictim);
            });
            assertTrue(thrown.getMessage().contains("rolled back by timeout"));
        });

        future.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();
    }

    @Test
    @DisplayName("Многопоточный обход стримов кэша при параллельном предикатном чтении")
    public void testConcurrent_ReadInternal_ShouldBeThreadSafe() throws Exception {
        int initialCount = 100;
        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < initialCount; i++) {
            TestEntity e = new TestEntity("Batch_" + i);
            when(mockIdField.get(e)).thenReturn((long) i);
            batch.add(e);
        }
        resourceCache.fillCache(batch);

        int readersCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(readersCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(readersCount);
        AtomicInteger totalReadCount = new AtomicInteger(0);

        IXdStoragePredicate mockPredicate = mock(IXdStoragePredicate.class);
        when(mockPredicate.passed(any())).thenReturn(true);
        when(mockCloner.cloneAndWrap(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < readersCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    XdStorageTransaction tx = mockTransaction("tx-reader-" + idx);
                    startLatch.await();
                    Collection<?> res = resourceCache.read(tx, mockPredicate);
                    totalReadCount.addAndGet(res.size());
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean cleanlyFinished = finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(cleanlyFinished, "Стрим-обход заклинило гонкой данных!");
        assertEquals(initialCount * readersCount, totalReadCount.get());
    }

    /**
     * ТЕСТ: Верификация принудительного отвала блокировок СУБД по таймауту транзакции (Lock Contention Timeout Barrier).
     * Тест проверяет критически важный fail-safe барьер ядра: если параллельная транзакция монопольно
     * удерживает блокировку над записью CacheRecord (State.locked), то прилетающий конкурирующий поток
     * с жестким коротким таймаутом (timeout = 100ms) обязан штатно выбросить XdStorageException с требованием
     * отката сессии, категорически предотвращая бесконечное заклинивание и дедлоки в JVM!
     */
    @Test
    @DisplayName("Проверка, что конкурирующие транзакции гарантированно падают по таймауту при заклинивании")
    public void testConcurrent_TransactionLockContention_ShouldEnforceTimeoutAndThrow() throws Exception {
        TestEntity sharedEntity = new TestEntity("Shared_Resource");
        long id = 777111L;

        // Настраиваем мок-окружение рефлексии
        when(mockIdField.get(sharedEntity)).thenReturn(id);

        // Накатываем объект в чистый базовый кэш СУБД
        resourceCache.fillCache(Collections.singletonList(sharedEntity));

        // Открываем транзакцию-монополиста (которая захватит лок на долгое время)
        XdStorageTransaction txMonopolist = mockTransaction("tx-monopolist-holder");
        when(txMonopolist.getTimeout()).thenReturn(100L); // 5 секунд

        // Открываем транзакцию-жертву (у которой выставим очень короткий лимит ожидания)
        XdStorageTransaction txVictim = mockTransaction("tx-victim-timeout-target");
        when(txVictim.getTimeout()).thenReturn(150L); // Жесткий лимит 150 миллисекунд!

        TestEntity mutationData = new TestEntity("Mutated_State");
        when(mockIdField.get(mutationData)).thenReturn(id);
        when(mockCloner.unwrapAndClone(mutationData)).thenReturn(mutationData);

        // 1. Поток-монополист успешно захватывает Write-блокировку над CacheRecord (locked.set(true))
        resourceCache.update(mutationData, txMonopolist);

        // 2. Запускаем параллельный поток-воркер жертвы, который попытается обновить этот же заблокированный объект
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<Throwable> victimExecutionFuture = executor.submit(() -> {
            try {
                // Поток жертвы обязан врезаться в locked.wait() и ровно через ~150ms
                // вылететь по контролируемому таймауту ядра СУБД!
                resourceCache.update(mutationData, txVictim);
                return null; // Если не упал — значит барьер таймаутов СУБД сломан!
            } catch (Throwable t) {
                return t; // Передаем пойманное исключение для JUnit верификации
            }
        });

        // Дожидаемся завершения асинхронной операции с запасом времени
        Throwable caughtException = victimExecutionFuture.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();

        // === ВЕРИФИКАЦИЯ ФАТАЛЬНОГО ТРАНЗАКЦИОННОГО ИНВАРИАНТА ===
        assertNotNull(caughtException, "🚨 СУБД проглотила таймаут! Конкурирующий поток ушел в вечное заклинивание вместо fail-fast падения!");

        // Проверяем тип исключения и каноничный текст контракта xdstorage
        assertTrue(caughtException instanceof XdStorageRuntimeException, "Исключение таймаута обязано быть экземпляром XdStorageException!");

        String exceptionMessage = caughtException.getMessage();
        caughtException.printStackTrace(System.err);

        assertTrue(exceptionMessage.contains("should be rolled back by timeout"),
                "Текст ошибки СУБД не соответствует каноничному контракту ядра! Найдено: " + exceptionMessage);
        assertTrue(exceptionMessage.contains("is locked for"),
                "Ошибка обязана указывать тип блокирующей операции! Найдено: " + exceptionMessage);
    }
}
