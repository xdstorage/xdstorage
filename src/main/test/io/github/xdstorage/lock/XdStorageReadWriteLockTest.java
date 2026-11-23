package io.github.xdstorage.lock;

import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Комплексное и многопоточное тестирование локера XdStorageReadWriteLock")
public class XdStorageReadWriteLockTest {

    private XdStorageReadWriteLock rwLock;

    @BeforeEach
    public void setUp() {
        rwLock = new XdStorageReadWriteLock();
    }

    // =========================================================================
    // 1. БАЗОВЫЕ ОДНОПОТОЧНЫЕ ТЕСТЫ И РЕЕНТЕРАБЕЛЬНОСТЬ (HAPPY PATH)
    // =========================================================================

    @Test
    @DisplayName("Проверка исходного чистого состояния локера")
    public void testInitialState_ShouldNotBeLocked() {
        assertFalse(rwLock.isReadLocked());
        assertFalse(rwLock.isWriteLocked());
    }

    @Test
    @DisplayName("Захват Read-лока: проверка реентерабельности и каскадного освобождения")
    public void testTryLockRead_HappyPath_AndReentrancy() {
        // Первый захват на чтение
        assertTrue(rwLock.tryLockRead());
        assertTrue(rwLock.isReadLocked());
        assertFalse(rwLock.isWriteLocked());

        // Проверяем реентерабельность: повторный захват тем же потоком разрешен
        assertTrue(rwLock.tryLockRead());

        // Каскадное пошаговое освобождение
        assertDoesNotThrow(() -> rwLock.unlockRead());
        assertTrue(rwLock.isReadLocked(), "Read-лок еще должен удерживаться, так как счетчик равен 1!");

        assertDoesNotThrow(() -> rwLock.unlockRead());
        assertFalse(rwLock.isReadLocked(), "Read-лок должен полностью освободиться!");
    }

    @Test
    @DisplayName("Захват Write-лока: проверка эксклюзивной реентерабельности")
    public void testTryLockWrite_HappyPath_AndReentrancy() {
        // Первый эксклюзивный захват на запись
        assertTrue(rwLock.tryLockWrite());
        assertTrue(rwLock.isWriteLocked());
        assertFalse(rwLock.isReadLocked());

        // Проверяем реентерабельность записи для того же потока
        assertTrue(rwLock.tryLockWrite());

        assertDoesNotThrow(() -> rwLock.unlockWrite());
        assertTrue(rwLock.isWriteLocked());

        assertDoesNotThrow(() -> rwLock.unlockWrite());
        assertFalse(rwLock.isWriteLocked());
    }

    @Test
    @DisplayName("Lock Upgrade: поток с Read-локом может атомарно взять Write-лок (если он единственный читатель)")
    public void testLockUpgrade_ReadThenWrite_ShouldBeAllowedForSameThread() {
        assertTrue(rwLock.tryLockRead());
        assertTrue(rwLock.tryLockWrite(), "Upgrade обязан разрешаться, если поток — единственный читатель!");

        assertTrue(rwLock.isReadLocked());
        assertTrue(rwLock.isWriteLocked());

        assertDoesNotThrow(() -> rwLock.unlockWrite());
        assertDoesNotThrow(() -> rwLock.unlockRead());
    }

    @Test
    @DisplayName("Lock Downgrade: поток с Write-локом обязан мгновенно получать Read-лок")
    public void testLockDowngrade_WriteThenRead_ShouldBeAllowedForSameThread() {
        assertTrue(rwLock.tryLockWrite());

        // Верификация критического исправления ядра СУБД:
        assertTrue(rwLock.tryLockRead(), "Downgrade обязан мгновенно разрешаться для удерживающего писателя!");
        assertTrue(rwLock.isReadLocked());
        assertTrue(rwLock.isWriteLocked());

        assertDoesNotThrow(() -> rwLock.unlockRead());
        assertDoesNotThrow(() -> rwLock.unlockWrite());
    }

    // =========================================================================
    // 2. ГРАНИЧНЫЕ ТЕСТЫ И БАРЬЕРЫ ОШИБОК (EDGE CASES)
    // =========================================================================

    @Test
    @DisplayName("Попытка снять Write-лок без захвата должна выбрасывать IllegalMonitorStateException")
    public void testUnlockWrite_WhenNotLocked_ShouldThrowIllegalMonitorStateException() {
        assertThrows(IllegalMonitorStateException.class, () -> rwLock.unlockWrite());
    }

    @Test
    @DisplayName("Попытка снять Read-лок без захвата должна выбрасывать IllegalMonitorStateException")
    public void testUnlockRead_WhenNotLocked_ShouldThrowIllegalMonitorStateException() {
        assertThrows(IllegalMonitorStateException.class, () -> rwLock.unlockRead());
    }

    @Test
    @DisplayName("Синхронный lockRead по таймауту транзакции (Happy Path)")
    public void testLockRead_HappyPath_WithTransactionTimeout() {
        IXdStorageTransaction mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(1000L);

        assertDoesNotThrow(() -> rwLock.lockRead(mockTx));
        assertTrue(rwLock.isReadLocked());
        assertDoesNotThrow(() -> rwLock.unlockRead());
    }

    @Test
    @DisplayName("Синхронный lockWrite по таймауту транзакции (Happy Path)")
    public void testLockWrite_HappyPath_WithTransactionTimeout() {
        IXdStorageTransaction mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(1000L);

        assertDoesNotThrow(() -> rwLock.lockWrite(mockTx));
        assertTrue(rwLock.isWriteLocked());
        assertDoesNotThrow(() -> rwLock.unlockWrite());
    }

    // =========================================================================
    // 3. МНОГОПОТОЧНЫЕ СТРЕСС-ТЕСТЫ КОНКУРЕНЦИИ (CONCURRENCY & INTERFERENCE)
    // =========================================================================

    @Test
    @DisplayName("Эксклюзивность писателей: параллельный поток не может взять Read или Write лок, если ресурс занят")
    public void testConcurrent_WriteLockExclusivity_ShouldBlockOtherThreads() throws Exception {
        assertTrue(rwLock.tryLockWrite()); // Главный поток монопольно захватил ресурс

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicBoolean secondThreadReadStatus = new AtomicBoolean(true);
        AtomicBoolean secondThreadWriteStatus = new AtomicBoolean(true);

        CountDownLatch latch = new CountDownLatch(2);

        // Поток А пытается зайти на чтение
        executor.submit(() -> {
            secondThreadReadStatus.set(rwLock.tryLockRead());
            latch.countDown();
        });

        // Поток Б пытается зайти на запись
        executor.submit(() -> {
            secondThreadWriteStatus.set(rwLock.tryLockWrite());
            latch.countDown();
        });

        latch.await(2, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertFalse(secondThreadReadStatus.get(),
                "Параллельный поток не имел права взять Read-лок при живом писателе!");
        assertFalse(secondThreadWriteStatus.get(),
                "Параллельный поток не имел права взять Write-лок при живом писателе!");

        assertDoesNotThrow(() -> rwLock.unlockWrite());
    }

    @Test
    @DisplayName("Совместимость читателей (Shared Read): 10 параллельных потоков должны беспрепятственно читать " +
            "одновременно")
    public void testConcurrent_SharedRead_ShouldAllowParallelReading() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successfulLocks = new AtomicInteger(0);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Одновременный залп
                    if (rwLock.tryLockRead()) {
                        successfulLocks.incrementAndGet();
                        rwLock.unlockRead();
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Огонь!
        boolean finishedCleanly = finishLatch.await(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(finishedCleanly, "Потоки заклинило при совместном чтении!");
        assertTrue(exceptions.isEmpty(), "Вылетели непредвиденные исключения: " + exceptions);
        assertEquals(threadCount, successfulLocks.get(),
                "Не все параллельные потоки смогли взять разделяемый Read-лок!");
    }

    @Test
    @DisplayName("Отвал по Таймауту: конкурирующий поток обязан выбросить XdStorageRuntimeException при превышении " +
            "лимита ожидания лока")
    public void testConcurrent_LockTimeoutEnforcement_ShouldThrowOnTimeout() throws Exception {
        assertTrue(rwLock.tryLockWrite()); // Главный поток занял запись

        IXdStorageTransaction mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(150L); // Выставляем короткий таймаут 150 мс для жертвы

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> victimFuture = executor.submit(() -> {
            try {
                // Поток должен упереться в замок и через 150мс вылететь по таймауту
                rwLock.lockRead(mockTx);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        Throwable caughtException = victimFuture.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertNotNull(caughtException, "Поток жертвы заснул навсегда вместо контролируемого отвала по таймауту!");
        assertTrue(caughtException instanceof XdStorageRuntimeException,
                "Тип исключения должен быть XdStorageRuntimeException!");
        assertTrue(caughtException.getMessage().contains("Превышен таймаут ожидания"),
                "Текст ошибки не соответствует контракту!");

        assertDoesNotThrow(() -> rwLock.unlockWrite());
    }
}
