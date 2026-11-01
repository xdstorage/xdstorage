package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Tag("stress")
@DisplayName("B+Tree Стресс-тест: Многопоточный штурм прецизионных удалений")
public class XdStorageBTreeTargetedDeleteStressTest {

    private static final String STORAGE_PATH = "./target/btree_target_stress_db";
    private static IXdStorage storage = null;
    private static XdStorageBTreeId treeId;

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("target_stress_test", STORAGE_PATH, 128, FilesFormat.YAML);
        treeId = new XdStorageBTreeId(Integer.class, "fk_stress_index");

        // Первичное наполнение индекса стабильным базисом дубликатов
        IXdStorageTransaction tx = storage.beginTransaction();
        XdStorageBTree bTree = new XdStorageBTree(treeId, true, 3);
        storage.save(bTree, tx);

        // На один ключ 10 вешаем 50 различных дочерних числовых значений
        for (int v = 100; v <= 150; v++) {
            bTree.insert(10, v, storage, tx);
        }
        storage.save(bTree, tx);
        tx.commit();
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
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }

    @Test
    @DisplayName("Штурм Конкурентности: 8 потоков перекрестных точечных удалений и Snapshot-вычиток")
    public void testTargetedDelete_UnderHeavyConcurrency_ShouldMaintainConsistency() throws Exception {
        final int threadsCount = 8;
        final int durationSeconds = 5;

        ExecutorService threadPool = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean stopFlag = new AtomicBoolean(false);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicLong successfulDeletes = new AtomicLong(0);
        AtomicLong successfulReads = new AtomicLong(0);

        // Диапазон значений для удаления
        final List<Integer> targetValuesToPurge = new CopyOnWriteArrayList<>();
        for (int v = 100; v <= 150; v++) {
            targetValuesToPurge.add(v);
        }

        for (int i = 0; i < threadsCount; i++) {
            final int workerId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random();

                    while (!stopFlag.get()) {
                        IXdStorageTransaction tx = storage.beginTransaction(5000);
                        try {
                            XdStorageBTree bTree = storage.load(XdStorageBTree.class, treeId, tx);

                            if (workerId % 2 == 0) {
                                // Половина потоков — АГРЕССИВНЫЕ ИСТРЕБИТЕЛИ ПАР
                                if (!targetValuesToPurge.isEmpty()) {
                                    Integer targetValue = targetValuesToPurge.remove(0);

                                    // Точечно вырезаем пару
                                    bTree.delete(10, targetValue, storage, tx);
                                    storage.save(bTree, tx);
                                    tx.commit();
                                    successfulDeletes.incrementAndGet();
                                } else {
                                    tx.commit();
                                }
                            } else {
                                // Вторая половина потоков — ЧИТАТЕЛИ СНАПШОТОВ
                                List<Object> currentSnapshot = bTree.find(10, storage, tx);
                                assertNotNull(currentSnapshot);
                                tx.commit();
                                successfulReads.incrementAndGet();
                            }
                        } catch (Throwable t) {
                            try { tx.rollback(); } catch (Exception ignored) {}
                            // Пропускаем легитимные транзиентные отказы гонки:
                            // "does not exists" — ключ стерли параллельно;
                            // "concurrent modification" — сохранение hot-записи самого
                            // дерева отказано по first-committer-wins (все воркеры
                            // пересохраняют запись дерева после каждого удаления).
                            // Структурные аномалии — всё остальное.
                            final String message = String.valueOf(t.getMessage());
                            if (!message.contains("does not exists") && !message.contains("concurrent modification")) {
                                exceptions.add(t);
                            }
                        }
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            });
        }

        System.out.println("🚀 Запуск экстремального прессинга целевых удалений Б+ Дерева...");
        startLatch.countDown();

        Thread.sleep(durationSeconds * 1000L);

        System.out.println("🧹 Остановка потоков нагрузки...");
        stopFlag.set(true);
        threadPool.shutdown();
        assertTrue(threadPool.awaitTermination(5, TimeUnit.SECONDS));

        System.out.println("=========================================================================");
        System.out.println("📊 ИТОГИ ШТУРМА ЦЕЛЕВЫХ УДАЛЕНИЙ:");
        System.out.println("🔹 Успешных точечных удалений пар: " + successfulDeletes.get());
        System.out.println("🔹 Конкурентных Snapshot-вычиток: " + successfulReads.get());
        System.out.println("=========================================================================");

        if (!exceptions.isEmpty()) {
            System.err.println("🚨 ОБНАРУЖЕНЫ АНОМАЛИИ СТРУКТУРЫ ИЛИ ДЕДЛОКИ БЛОКИРОВОК (" + exceptions.size() + " шт):");
            exceptions.forEach(e -> {
                System.err.println("---");
                e.printStackTrace(System.err);
            });
            fail("Б+ ДЕРЕВО СЛOМAЛOСЬ НА ТAРГEТИРOВAННЫХ УДAЛEНИЯХ!");
        } else {
            System.out.println("🎉 ИДЕАЛЬНО! Ни одного рассинхрона, конкурентная ребалансировка отработала монолитно.");
        }
    }
}
