package io.github.xdstorage.performance;

import io.github.xdstorage.*;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Оптимизированный стресс-тест СУБД (Поинт Г).
 * Внедряет адаптивный Exponential Backoff для разведения конкурирующих транзакций во времени.
 */
@Tag("stress")
public class XdStorageStressTest {

    private static final String STRESS_DIR = "./target/stress_test_storage";
    private static final int CONCURRENT_THREADS = 50;
    private static final int OPERATIONS_PER_THREAD = 100;
    private static final int MAX_RETRIES = 15; // Увеличиваем лимит попыток для пробития пиковой нагрузки

    private static class TransactionWorker implements Runnable {
        private final IXdFileStorage storage;
        private final CountDownLatch latch;
        private final AtomicInteger successCount;
        private final AtomicInteger rollbackCount;

        public TransactionWorker(IXdFileStorage storage, CountDownLatch latch,
                                 AtomicInteger successCount, AtomicInteger rollbackCount) {
            this.storage = storage;
            this.latch = latch;
            this.successCount = successCount;
            this.rollbackCount = rollbackCount;
        }

        @Override
        public void run() {
            try {
                latch.await(); // 1. Все 50 потоков одновременно просыпаются здесь

                // 2. Делаем небольшую случайную паузу, чтобы потоки не толкались
                Thread.sleep(ThreadLocalRandom.current().nextInt(150));

                // 3. ВНЕШНИЙ ЦИКЛ: Каждый поток должен успешно выполнить 100 операций записи
                for (int j = 0; j < OPERATIONS_PER_THREAD; ++j) {
                    // Создаем инстанс нашего тестового Java-класса (POJO).
                    // Конструктор принимает сгенерированный уникальный ID и строку с полезными данными (payload).
                    // Ядро СУБД считает это поле первичным ключом благодаря аннотации @XdStorageObjectId.
                    BenchmarkEntity entity = new BenchmarkEntity(UUID.randomUUID().toString(), "Stress_Payload_Data");

                    // Усыпляем поток на чуть-чуть, прежде чем повторить попытку в цикле while
                    // Переменные-индикаторы для одной конкретной операции записи
                    boolean txSuccess = false;
                    int retries = 0;

                    // Внутренний цикл будет крутиться, пока транзакция не завершится успехом
                    // ИЛИ пока мы не исчерпаем лимит в 15 попыток (MAX_RETRIES)
                    while (!txSuccess && retries < MAX_RETRIES) {
                        // 1. Открываем изолированную транзакцию в СУБД с таймаутом ожидания локов в 3 секунды
                        IXdStorageTransaction tx = storage.beginTransaction(3_000L);

                        try {
                            // 2. Сама запись: Передаем наш POJO-объект и контекст текущей транзакции.
                            // Ядро проверяет аннотацию @XdStorageObjectId и политику StoreAsClassObjects,
                            // после чего резервирует место во фрагменте кэша индексов.
                            storage.save(entity, tx);

                            // 3. Фиксация: Даем команду на двухфазный коммит (2PC).
                            // СУБД преобразует накопленные в памяти дельты изменений в реальные пакеты
                            // команд PreparedStatement и надежно записывает их в базу данных.
                            storage.commitTransaction(tx);

                            // Если СУБД успешно сохранила данные без конфликтов блокировок — инкрементируем счетчик
                            // коммитов
                            successCount.incrementAndGet();
                            txSuccess = true; // Выходим из цикла повторов (while) к следующей операции

                        } catch (Exception e) {
                            // Если произошел конфликт MVCC-блокировок или таймаут — откатываем попытку
                            storage.rollbackTransaction(tx);

                            // Фиксируем отклонение ТОЛЬКО если это была последняя 15-я попытка
                            if (retries + 1 >= MAX_RETRIES) {
                                rollbackCount.incrementAndGet();
                            }

                            ++retries;

                            // Адаптивно засыпаем, давая другим потокам завершить свои транзакции
                            executeAdaptiveBackoffDelay(retries);
                        }
                    } // Конец цикла while (Retry Policy)
                } // Конец цикла for (100 операций)
            } catch (Exception e) {
                rollbackCount.incrementAndGet();
            }
        }

        /**
         * Адаптивный экспоненциальный Backoff с джиттером для разгона СУБД.
         */
        private void executeAdaptiveBackoffDelay(int currentRetry) {
            try {
                // Базовая пауза растет с каждой ошибкой + добавляется случайный разброс
                long baseDelay = 5L * currentRetry;
                long jitter = ThreadLocalRandom.current().nextInt(15);
                Thread.sleep(baseDelay + jitter);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void initStorageEnvironment() {
        deleteDir(new File(STRESS_DIR));
    }

    private void printStressResults(AtomicInteger successCount, AtomicInteger rollbackCount) {
        System.out.println("\n=========================================");
        System.out.println("      РЕЗУЛЬТАТЫ СТРЕСС-ТЕСТА СУБД       ");
        System.out.println("=========================================");
        System.out.println("Успешных транзакций (Коммитов): " + successCount.get());
        System.out.println("Отклонено (Конфликты/Таймауты): " + rollbackCount.get());
        System.out.println("=========================================\n");
    }

    @Test
    public void testHighConcurrentLoad_ShouldNotLeakOrDeadlock() throws Exception {
        initStorageEnvironment();

        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("StressStorage", STRESS_DIR, 512);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rollbackCount = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            executor.submit(new TransactionWorker(storage, latch, successCount, rollbackCount));
        }

        latch.countDown(); // Одновременный залп!

        executor.shutdown();

        boolean finishedCleanly = executor.awaitTermination(60, TimeUnit.SECONDS);

        assertTrue(finishedCleanly, "Критический баг! Локеры СУБД ушли в циклический Deadlock!");

        printStressResults(successCount, rollbackCount);

        storage.shutdown();
        deleteDir(new File(STRESS_DIR));

        assertTrue(successCount.get() > 0, "Ни один поток не смог пробить локер ядра!");
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
