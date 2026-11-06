package io.github.xdstorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("4. Комплексный ультра-стресс тест фасада СУБД и Б+Дерево индексов")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("stress")
public class XdStorageFacadeStressTest extends AbstractXdStorageTest {

    private static final Logger log = LogManager.getLogger(XdStorageFacadeStressTest.class);
    private static IXdFileStorage realStorage;
    private static final String STORAGE_DIR = "./target/facade_stress_db";

    @BeforeAll
    public static void initRealStorage() {
        deleteDir(new File("./facade_stress_db"));
        realStorage = XdStorageProvider.newOrGetFileStorage("filetest", "./facade_stress_db", 250);
    }

    @AfterAll
    public static void destroyRealStorage() {
        if (realStorage != null) {
            realStorage.shutdown();
        }
//        deleteDir(new File(STORAGE_DIR));
    }

    @Test
    @Order(1)
    @DisplayName("Штурм СУБД: 20 параллельных потоков, лавина Б+Дерево сплитов и тотальный пурдж базы")
    public void testFacade_MassParallelMutationsAndTotalPurge() throws Exception {
        // R-41: штурм реально выполняется (раньше latch-баг оставлял воркеры спать вечно,
        // а пурдж гонялся по пустой базе 100 раз). Полный масштаб 100×2000 = 200k транзакций
        // не помещается в бюджет тест-сьюты — выбираем репрезентативный штурм ~10k транзакций.
        final int threadCount = 25;
        final int itemsPerThread = 400;
        final int totalItems = threadCount * itemsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successfulInserts = new AtomicInteger(0);

        log.info("=== ФАЗА 1: ЗАПУСК ПАРАЛЛЕЛЬНОГO ШТУРМА БАЗЫ ДАННЫХ (ЛАВИНА СПЛИТOВ) ===");

        // Шаг 1: Запускаем 20 транзакционных воркеров в параллельных потоках
        for (int i = 0; i < threadCount; i++) {
            final int workerId = i;
            executor.submit(() -> {
                try {
                    // 1. Барьер одновременного старта: все 20 потоков замирают здесь
                    // и одновременно стреляют в базу по сигналу startLatch.countDown()
                    startLatch.await();

                    for (int j = 0; j < itemsPerThread; j++) {
                        // Математически вычисляем уникальный ID для каждого объекта
                        long uniqueId = (long) workerId * itemsPerThread + j + 1;

                        // Открываем изолированную транзакцию для текущего потока с запасом времени
                        IXdStorageTransaction tx = realStorage.beginTransaction(5000L);
                        try {
                            ClassPolicyEntity entity = new ClassPolicyEntity();
                            entity.setId(uniqueId);
                            entity.setValue("Initial_Value_Worker_" + workerId + "_" + j);

                            // АКТИВАЦИЯ ТЕНЕВОГО БУФЕРА: Запись перехватывается индексным ресурсом
                            // и атомарно складывается в карту XdStorageBTreeBufferRegistry
                            realStorage.save(entity, tx);

                            // ФИКСАЦИЯ КOММИТА: Менеджер транзакций вызывает XdStorageBTreeFlushOrchestrator,
                            // дельты из буфера «смываются» в физические ноды Б+ Дерева, и транзакция закрывается!
                            tx.commit();
                            successfulInserts.incrementAndGet();
                        } catch (Throwable t) {
                            // Если локер дерева выкинул таймаут или дедлок — fail-safe откатываем сессию
                            tx.rollback();
                            throw t;
                        }
                    }
                } catch (Throwable t) {
                    // Потокобезопасно собираем любые ошибки для финального ассерта JUnit
                    exceptions.add(t);
                } finally {
                    // Отпускаем счетчик завершения потока
                    finishLatch.countDown();
                }
            });
        }

        // Шаг 2: снимаем барьер старта и ждем завершения ВСЕХ воркеров до пурджа
        startLatch.countDown();
        finishLatch.await();

        // Все ошибки воркеров (таймауты блокировок, дедлоки) валидны только здесь
        if (!exceptions.isEmpty()) {
            fail("🚨 Воркеры штурма упали с исключениями!", exceptions.get(0));
        }
        assertEquals(totalItems, successfulInserts.get(), "Не все вставки штурма закоммичены!");

        // Пул не должен держать JVM после теста
        executor.shutdownNow();

        // =========================================================================
        // ФАЗА 3: ТОТАЛЬНАЯ АТОМАРНАЯ АННИГИЛЯЦИЯ БАЗЫ ДАННЫХ (ПУРДЖ)
        // =========================================================================
        log.info("=== ФАЗА 3: ЗАПУСК МОНОЛИТНОЙ ОЧИСТКИ ВСЕЙ БАЗЫ ДАННЫХ (ЛАВИНА СЖАТИЙ) ===");
        IXdStorageTransaction txPurge = realStorage.beginTransaction(20000L);
        try {
            Collection<ClassPolicyEntity> entitiesToPurge = realStorage.load(ClassPolicyEntity.class, txPurge);
            log.info("Выкашиваем ярусы Б+Дерева до самого корня. Элементов к удалению: " + entitiesToPurge.size());

            for (ClassPolicyEntity entity : entitiesToPurge) {
                // Лавинообразно стираем ключи, заставляя ноды рекурсивно объединяться (join)
                realStorage.delete(entity, txPurge);
            }

            // Финальный монолитный коммит пурджа: вся база атомарно схлопывается в один пустой корень!
            txPurge.commit();
            log.info("=== МOНOЛИТНАЯ ОЧИСТКА БАЗЫ ЗАВЕРШЕНА УСПЕШНО ===");
        } catch (Throwable t) {
            txPurge.rollback();
            fail("🚨 Аварийный крах монолитного коммита очистки хранилища СУБД!", t);
        }

        // =========================================================================
        // ФАЗА 4: КОНТРОЛЬНЫЙ ВЫСТРЕЛ — ПРОВЕРКА ПОЛНОЙ ПУСТОТЫ
        // =========================================================================
        log.info("=== ФАЗА 4: КОНТРОЛЬНАЯ ПРОВЕРКА ПОЛНОЙ АННИГИЛЯЦИИ СТРУКТУРЫ ===");
        IXdStorageTransaction txCheckEmpty = realStorage.beginTransaction(100L);
        try {
            Collection<ClassPolicyEntity> emptyCheck = realStorage.load(ClassPolicyEntity.class, txCheckEmpty);

            // Жесткий ACID ассерт: в базе не должно остаться ни одной фантомной записи
            assertTrue(emptyCheck.isEmpty(), "Критический дефект ACID! База данных содержит фантомные индексы после пурджа!");

            txCheckEmpty.commit();
            log.info("СУБД абсолютно чиста. Инвариант Б+Дерева и теневых буферов подтвержден на 100%.");
        } catch (Throwable t) {
            txCheckEmpty.rollback();
            throw t;
        }
    }

    public static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
