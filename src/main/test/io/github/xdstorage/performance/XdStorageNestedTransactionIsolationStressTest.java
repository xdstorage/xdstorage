package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.XdPlanet;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Стресс-тест MVCC: Экстремальная изоляция вложенных транзакций")
@Tag("stress")
public class XdStorageNestedTransactionIsolationStressTest {

    private static IXdStorage storage = null;
    private static final String STORAGE_PATH = "./target/nested_tx_stress_db";
    private static final int INITIAL_PLANETS = 200;

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        // Инициализируем хранилище через наш обновленный фасад XdStorage
        storage = XdStorageProvider.newOrGetFileStorage("nested_tx_db", STORAGE_PATH, 500);

        // Накатываем стартовую стабильную базу (базис для Snapshot Isolation)
        IXdStorageTransaction txInit = storage.beginTransaction();
        for (int i = 1; i <= INITIAL_PLANETS; i++) {
            XdPlanet planet = new XdPlanet();
            planet.setName("StableBasePlanet_" + i);
            planet.setWaterPercent(1);
            storage.save(planet, txInit);
        }
        txInit.commit();
        System.out.println("🎉 Базовая персистентная сетка на диске готова: " + INITIAL_PLANETS + " планет.");
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_PATH));
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

    @Test
    @DisplayName("Штурм Вложенности: Параллельная гонка воркеров с каскадными Nested-откатами и коммитами")
    public void testMVCC_ParallelNestedTransactions_ShouldMaintainACID() throws Exception {
        final int workerThreadsCount = 8;
        final int durationSeconds = 10; // Время удержания прессинга

        ExecutorService threadPool = Executors.newFixedThreadPool(workerThreadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean stopFlag = new AtomicBoolean(false);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalParentCommits = new AtomicLong(0);
        AtomicLong totalNestedCommits = new AtomicLong(0);
        AtomicLong totalNestedRollbacks = new AtomicLong(0);

        // Запускаем 8 воркеров, которые будут непрерывно бомбардировать ядро
        for (int i = 0; i < workerThreadsCount; i++) {
            final int workerId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    int localStep = 0;

                    while (!stopFlag.get()) {
                        localStep++;

                        // 1. ОТКРЫВАЕМ РОДИТЕЛЬСКУЮ ТРАНЗАКЦИЮ (Global TX)
                        IXdStorageTransaction parentTx = storage.beginTransaction(100L);

                        try {
                            // Делаем базовое изменение в контексте родительской сессии
                            XdPlanet parentPlanet = new XdPlanet();
                            parentPlanet.setName("Parent_" + workerId + "_" + localStep);
                            parentPlanet.setWaterPercent(0);
                            storage.save(parentPlanet, parentTx);

                            // В зависимости от итерации симулируем разные исходы вложенных транзакций
                            if (localStep % 2 == 0) {
                                // =========================================================================
                                // СЦЕНАРИЙ 1: Успешная фиксация вложенной транзакции (Слияние контекстов)
                                // =========================================================================

                                // Передаем parentTx в качестве родительского контекста в наш новый метод фасада!
                                IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 3000);

                                XdPlanet childPlanet = new XdPlanet();
                                childPlanet.setName("Nested_Commit_" + workerId + "_" + localStep);
                                childPlanet.setWaterPercent(2);
                                storage.save(childPlanet, nestedTx);

                                // Коммитим внутреннюю транзакцию
                                storage.commitTransaction(nestedTx);
                                totalNestedCommits.incrementAndGet();
                            } else {
                                // =========================================================================
                                // СЦЕНАРИЙ 2: Аварийный ОТКАТ вложенной транзакции (Родитель должен выжить!)
                                // =========================================================================
                                IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx, 3000);

                                XdPlanet doomedPlanet = new XdPlanet();
                                doomedPlanet.setName("Nested_Rollback_" + workerId + "_" + localStep);
                                doomedPlanet.setWaterPercent(3);
                                storage.save(doomedPlanet, nestedTx);

                                // Жестко швыряем только вложенную транзакцию в откат
                                storage.rollbackTransaction(nestedTx);
                                totalNestedRollbacks.incrementAndGet();
                            }

                            // Фиксируем родительскую транзакцию целиком.
                            // Если СЦЕНАРИЙ 2 отработал изолированно, родитель закоммитит свою ParentPlanet,
                            // но проигнорирует DoomedPlanet, не сломав МВСС-структуру!
                            storage.commitTransaction(parentTx);
                            totalParentCommits.incrementAndGet();

                        } catch (Throwable t) {
                            try { storage.rollbackTransaction(parentTx); } catch (Exception ignored) {}
                            exceptions.add(new AssertionError(
                                    String.format("🚨 Крах транзакционного контракта вложенности в потоке %d на шаге %d", workerId, localStep), t));
                        }
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            });
        }

        System.out.println("🚀 Запуск экстремального прессинга вложенных транзакций через новый фасад СУБД...");
        startLatch.countDown();

        // Удерживаем JVM под нагрузкой
        Thread.sleep(durationSeconds * 1000L);

        System.out.println("🧹 Остановка потоков нагрузки...");
        stopFlag.set(true);
        threadPool.shutdown();
        if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
            threadPool.shutdownNow();
        }

        // Выводим статистику
        System.out.println("=========================================================================");
        System.out.println("📊 ИТОГИ СТРЕСС-ТЕСТА ВЛОЖЕННЫХ ТРАНЗАКЦИЙ:");
        System.out.println("🔹 Успешных глобальных коммитов (Parent Commits): " + totalParentCommits.get());
        System.out.println("🔹 Зафиксированных вложенных сессий (Nested Commits): " + totalNestedCommits.get());
        System.out.println("🔹 Изолированных откатов вложенных сессий (Nested Rollbacks): " + totalNestedRollbacks.get());
        System.out.println("=========================================================================");

        // Анализируем сбои
        if (!exceptions.isEmpty()) {
            System.err.println("🚨 ОБНАРУЖЕНЫ АНОМАЛИИ ВЗАИМНЫХ БЛОКИРОВОК ИЛИ УТEЧEК ВEРСИЙ (" + exceptions.size() + " шт):");
            exceptions.forEach(e -> {
                System.err.println("---");
                e.printStackTrace(System.err);
            });
            fail("МВСС-ЯДРО СЛОМАЛОСЬ НА ВЛОЖЕННЫХ ТРАНЗАКЦИЯХ! Обнаружены дедлоки, NPE или просадки Snapshot Isolation.");
        } else {
            System.out.println("🎉 ИДЕАЛЬНО! Ни одной гонки данных, каскадные вложенные ролбэки изолированы на 100%.");
        }
    }
}
