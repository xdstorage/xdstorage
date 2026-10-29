package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.resource.XdStorageResourceCache;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Настройки замера: измеряем пропускную способность (Throughput - количество операций в секунду)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark) // Состояние живет на протяжении всего запуска бенчмарка
@Warmup(iterations = 4, time = 2) // 3 итерации прогрева JVM
@Measurement(iterations = 5, time = 2) // 3 итерации чистых замеров
@Fork(4) // Запуск в 1 отдельном JVM-процессе для чистоты JIT-компиляции
@Threads(1)
public class XdStorageResourceCacheBenchmark {

    private XdStorageResourceCache resourceCache;
    private XdStorageTransaction staticTx;

    // Сущность для замеров
    public static class BenchEntity {
        private Long id;
        private String value;

        public BenchEntity(Long id, String value) {
            this.id = id;
            this.value = value;
        }
        public Long getId() { return id; }
        public String getValue() { return value; }
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
        XdStorageServicesLocator mockLocator = mock(XdStorageServicesLocator.class);
        IXdStorageCloner mockCloner = mock(IXdStorageCloner.class);
        IXdStorage mockStorage = mock(IXdStorage.class);
        XdStorageObjectIdField mockField = mock(XdStorageObjectIdField.class);

        // Быстрый, потокобезопасный транзакционный анонимный стаб
        IXdStorageTransactionManager mockTxManager = new IXdStorageTransactionManager() {
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

        when(mockLocator.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockLocator.getCloner()).thenReturn(mockCloner);
        when(mockLocator.getStorage()).thenReturn(mockStorage);

        when(mockField.get(any())).thenAnswer(inv -> {
            Object obj = inv.getArgument(0);
            return obj instanceof BenchEntity ? ((BenchEntity) obj).getId() : null;
        });

        when(mockCloner.cloneAndWrap(any(), any(), any())).thenAnswer(inv -> {
            BenchEntity orig = inv.getArgument(0);
            return new BenchEntity(orig.getId(), orig.getValue());
        });

        resourceCache = new XdStorageResourceCache(mockLocator, BenchEntity.class, mockField);

        // Наполняем кэш 1000 базовых записей
        List<Object> initialData = new ArrayList<>();
        for (long i = 1; i <= 1000; i++) {
            initialData.add(new BenchEntity(i, "Bench_Data_" + i));
        }
        resourceCache.fillCache(initialData);

        // Статическая транзакция для легковесных Lock-Free операций
        staticTx = mock(XdStorageTransaction.class);
        when(staticTx.getTransactionId()).thenReturn("tx-bench-global");
        when(staticTx.getTimestart()).thenReturn(System.nanoTime());
        when(staticTx.getTimeout()).thenReturn(10000L);
    }

    // =========================================================================
    // БЕНЧМАРК 1: Чистое параллельное диапазонное чтение по предикату (100% Read)
    // =========================================================================
    @Benchmark
    @Threads(8) // Симулируем 8 параллельных потоков чтения
    public void runPurePredicateReads() throws Exception {
        resourceCache.read(staticTx, new IXdStoragePredicate<BenchEntity>() {
            @Override
            public boolean passed(BenchEntity obj) {
                return obj != null;
            }
        });
    }

    // =========================================================================
    // БЕНЧМАРК 2: Агрессивная гонка смешанной нагрузки (50% Read / 50% Write-Mutation)
    // =========================================================================
    @Benchmark
    @Threads(8) // 8 потоков будут непрерывно бороться за замки и мутировать стейт
    public void runMixedReadWriteLoad() throws Exception {
        long threadId = Thread.currentThread().getId();
        long randomId = ThreadLocalRandom.current().nextLong(1, 1001);

        // Генерируем уникальный ID транзакции для каждого потока/шага, как в твоих тестах
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn("tx-bench-" + threadId + "-" + randomId);
        when(tx.getTimestart()).thenReturn(System.nanoTime());
        when(tx.getTimeout()).thenReturn(5000L);

        if (randomId % 2 == 0) {
            // Чтение по предикату
            resourceCache.read(tx, obj -> obj != null);

            XdStorageTransactionResourceChanges collector = new XdStorageTransactionResourceChanges(
                    mock(IXdStorageResourceObject.class)
            );
            resourceCache.prepareCommit(tx, collector);
            resourceCache.performCommit(tx, collector);
            resourceCache.commit(tx);
        } else {
            // Безопасное удаление и мгновенный инсерт восстановленной ноды
            try {
                resourceCache.delete(randomId, tx);
            } catch (Exception e) {
                // Контролируемый пропуск коллизий
            }
            if (!resourceCache.hasObject(randomId)) {
                BenchEntity restored = new BenchEntity(randomId, "Restored_" + randomId);
                resourceCache.insert(restored, tx);
            }
            XdStorageTransactionResourceChanges collector = new XdStorageTransactionResourceChanges(
                    mock(IXdStorageResourceObject.class)
            );
            resourceCache.prepareCommit(tx, collector);
            resourceCache.performCommit(tx, collector);
            resourceCache.commit(tx);
        }
    }

    // Метод main для удобного запуска бенчмарка прямо из IDE или через JAR
    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(XdStorageResourceCacheBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
