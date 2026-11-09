package io.github.xdstorage.idgeneration;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.IXdStorageInternalListener;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("stress")
public class XdStorageLongIdGeneratorStressTest {

    private XdStorageServicesLocator mockLocator;
    private IXdStorageTransactionManager mockTxManager;
    private XdStorageAbstractResourcesManager mockResManager;
    private IXdStorage mockStorage;
    private IXdStorageDaoResource stubDaoResource;
    private XdStorageLongIdGenerator idGenerator;

    // Класс-заглушка для доменной сущности
    private static class SampleEntity {}

    @BeforeEach
    public void setUp() throws Exception {
        mockLocator = mock(XdStorageServicesLocator.class);
        mockTxManager = mock(IXdStorageTransactionManager.class);
        mockResManager = mock(XdStorageAbstractResourcesManager.class);
        mockStorage = mock(IXdStorage.class);

        // Настраиваем локаторы сервисов
        when(mockLocator.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockLocator.getResourcesManager()).thenReturn(mockResManager);
        when(mockLocator.getExecutor()).thenReturn(null); // Заставляем выполнять Callable синхронно в текущем потоке

        // Имитируем создание транзакции внутренней подсистемой генератора
        when(mockTxManager.beginTransaction(anyLong())).thenAnswer(invocation -> mock(XdStorageTransaction.class));

        // Потокобезопасная лабораторная "база данных" для счетчиков ID СУБД
        stubDaoResource = new IXdStorageDaoResource() {
            private final ConcurrentHashMap<Object, XdStorageLongIdCounterRecord> dbTable = new ConcurrentHashMap<>();

            @Override
            public void insert(Object object, XdStorageTransaction tx) throws XdStorageException {
                XdStorageLongIdCounterRecord record = (XdStorageLongIdCounterRecord) object;
                dbTable.put(record.getCl(), record);
            }

            @Override
            public void update(Object object, XdStorageTransaction tx) throws XdStorageException {
                XdStorageLongIdCounterRecord record = (XdStorageLongIdCounterRecord) object;
                // Имитируем обновление: создаем копию состояния
                XdStorageLongIdCounterRecord newRecord = new XdStorageLongIdCounterRecord();
                newRecord.setCl(record.getCl());
                newRecord.setCounter(record.getCounter());
                dbTable.put(record.getCl(), newRecord);
            }

            @Override
            public void delete(Object object, XdStorageTransaction tx) throws XdStorageException {}

            @Override
            public Object getResourceId() {
                return null;
            }

            @Override
            public Collection<XdStorageIdentifiableObject> readAsData(XdStorageTransaction transaction) throws XdStorageException {
                return null;
            }

            @Override
            public boolean hasObject(Object objectId, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
                return false;
            }

            @Override
            public Collection<Object> readReferences(XdStorageTransaction transaction) throws XdStorageException {
                return null;
            }

            @Override
            public void insertReference(Object reference, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

            }

            @Override
            public void deleteReference(Object reference, XdStorageTransaction transaction) throws XdStorageException {

            }

            @Override
            public <T> T find(Object object, XdStorageTransaction transaction) throws XdStorageException {
                return null;
            }

            @Override
            public <T> Collection<T> read(XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
                return null;
            }

            @Override
            public <T> Collection<T> read(XdStorageTransaction transaction, IXdStoragePredicate<T> predicate) throws XdStorageException, XdStorageConnectionException {
                return null;
            }

            @Override
            public <T> void watch(XdStorageTransaction transaction, IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException {

            }

            @Override
            public void readByReference(Object reference, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

            }

            @Override
            public Object read(Object id, XdStorageTransaction tx) throws XdStorageException {
                Class<?> targetClass = (Class<?>) id;
                return dbTable.get(targetClass);
            }

            @Override
            public void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction) {

            }
        };

        // Настраиваем менеджер ресурсов возвращать наш потокобезопасный Stub DAO
        when(mockResManager.lockStructureResource(any(XdStorageClassInfo.class), any(XdStorageTransaction.class)))
                .thenReturn(stubDaoResource);

        idGenerator = new XdStorageLongIdGenerator(mockLocator);
    }

    @Test
    @DisplayName("СТРЕСС-ТЕСТ: Массовая параллельная генерация ID 32 потоками без дубликатов")
    public void testGenerate_HeavyParallelLoad_MustBeUniqueAndDense() throws Exception {
        final int threadCount = 32;
        final int idsPerThread = 500; // Каждый поток запросит 500 ID (всего 16 000 генераций)
        final int expectedTotalIds = threadCount * idsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        // Потокобезопасное хранилище для сбора всех сгенерированных идентификаторов
        Set<Long> generatedIds = ConcurrentHashMap.newKeySet();
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        IXdStorageTransaction txStub = mock(IXdStorageTransaction.class);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Синхронный залп всех потоков

                    for (int j = 0; j < idsPerThread; j++) {
                        Object idObj = idGenerator.generate(SampleEntity.class, mockStorage, txStub);
                        assertNotNull(idObj, "Генератор вернул пустой ID!");
                        assertTrue(idObj instanceof Long, "Тип сгенерированного ID должен быть Long!");

                        Long id = (Long) idObj;
                        boolean isUnique = generatedIds.add(id);

                        if (!isUnique) {
                            exceptions.add(new AssertionError("🚨 ОБНАРУЖЕН ДУБЛИКАТ ИДЕНТИФИКАТОРА: " + id));
                        }
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Огонь! Включаем нагрузку
        startLatch.countDown();
        boolean finishedCleanly = finishLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Проверяем, что тест завершился без дедлоков
        assertTrue(finishedCleanly, "🚨 СТРЕСС-ТЕСТ ЗАКЛИНИЛО! Потоки ушли в дедлок блокировок ReentrantLock!");

        // Выводим ошибки, если они есть
        if (!exceptions.isEmpty()) {
            exceptions.forEach(e -> e.printStackTrace(System.err));
        }
        assertTrue(exceptions.isEmpty(), "🚨 Зафиксированы коллизии генерации, дубликаты или гонки стейтов!");

        // Проверяем полноту и плотность диапазона (не потерялись ли идентификаторы при расширении пачек Hi-Lo)
        assertEquals(expectedTotalIds, generatedIds.size(), "Итоговое количество уникальных ID не совпадает с ожидаемым!");

        // Верифицируем, что диапазон непрерывен: от 1 до expectedTotalIds
        long maxId = generatedIds.stream().max(Long::compare).orElse(0L);
        long minId = generatedIds.stream().min(Long::compare).orElse(0L);

        assertEquals(1L, minId, "Начальный ID в Hi-Lo должен быть равен 1!");
        assertEquals((long) expectedTotalIds, maxId, "Конечный ID не совпадает с максимальным сгенерированным!");
    }
}
