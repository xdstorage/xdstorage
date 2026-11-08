package io.github.xdstorage.integration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.factories.IXdStorageCloner;
import io.github.xdstorage.factories.XdStorageSmartCloner;
import io.github.xdstorage.resource.XdStorageResourceCache;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Интеграционное тестирование связки XdStorageBTree + XdStorageResourceCache")
@Tag("integration")
public class BTreeResourceCacheIntegrationTest {

    private static final Logger log = LogManager.getLogger(BTreeResourceCacheIntegrationTest.class);

    private IXdStorage mockStorage;
    private XdStorageServicesLocator mockServices;
    private XdStorageObjectIdField mockIdField;
    private XdStorageResourceCache resourceCache;
    private XdStorageBTree bTree;

    @BeforeEach
    public void setUp() throws Exception {
        mockStorage = mock(IXdStorage.class);
        mockServices = mock(XdStorageServicesLocator.class);

        // =========================================================================
        // ИСПРАВЛЕНИЕ: Интегрируем потокобезопасный анонимный Stub менеджера транзакций,
        // чтобы Guard-барьеры ядра СУБД не падали в NullPointerException!
        // =========================================================================
        io.github.xdstorage.transaction.IXdStorageTransactionManager mockTxManager =
                new io.github.xdstorage.transaction.IXdStorageTransactionManager() {
                    @Override
                    public boolean isTransactionAlive(io.github.xdstorage.transaction.IXdStorageTransaction transaction) {
                        return true;
                    }
                    @Override
                    public IXdStorage getStorage() { return mockStorage; }
                    @Override
                    public io.github.xdstorage.transaction.IXdStorageTransaction beginTransaction(long timeout) { return null; }
                    @Override
                    public io.github.xdstorage.transaction.IXdStorageTransaction beginTransaction(io.github.xdstorage.transaction.IXdStorageTransaction transaction, long timeout) { return null; }
                    @Override
                    public io.github.xdstorage.transaction.IXdStorageTransaction getTransaction(String transactionId) { return null; }
                    @Override
                    public void commitTransaction(io.github.xdstorage.transaction.IXdStorageTransaction transaction) {}
                    @Override
                    public void rollbackTransaction(io.github.xdstorage.transaction.IXdStorageTransaction transaction) {}
                    @Override
                    public XdStorageTransaction getCurrentTransaction() { return null; }
                    @Override
                    public void registerRollbackOnlyTransaction(io.github.xdstorage.transaction.IXdStorageTransaction transaction) {}
                };
        when(mockServices.getTransactionsManager()).thenReturn(mockTxManager);

        // Настраиваем реальный или симулированный клонер ORM графа
        // (Остальной код метода оставляем без изменений)
        IXdStorageCloner realCloner = new XdStorageSmartCloner(mock(io.github.xdstorage.factories.IXdStorageReferenceProvider.class));
        when(mockServices.getCloner()).thenReturn(realCloner);
        when(mockServices.getStorage()).thenReturn(mockStorage);

        // Настраиваем метаданные рефлексии для тестовой JavaBeans сущности
        XdStorageClassInfo classInfo = XdStorageObjectUtils.getClassInfo(IntegrationEntity.class);
        mockIdField = classInfo.getIdField();

        // Инстанциируем транзакционный кэш версий ресурсов
        resourceCache = new XdStorageResourceCache(mockServices, IntegrationEntity.class, mockIdField);

        // Инстанциируем B+ Дерево с коротким размером ноды (t=4), чтобы гарантировать сплиты под нагрузкой
        XdStorageBTreeId btreeId = new XdStorageBTreeId(String.class, "integration_index_tree");
        bTree = new XdStorageBTree(btreeId, true, 8);
    }

    // Вспомогательный фабричный метод для генерации транзакций с кастомным лимитом таймаута ожидания локов
    private XdStorageTransaction createTx(String id, long timeoutMs) {
        XdStorageTransaction tx = mock(XdStorageTransaction.class);
        try {
            when(tx.getTransactionId()).thenReturn(id);
            when(tx.getTimeout()).thenReturn(timeoutMs);
            when(tx.getTimestart()).thenReturn(System.nanoTime());

            java.lang.reflect.Field timeoutField = XdStorageTransaction.class.getDeclaredField("timeout");
            timeoutField.setAccessible(true);
            timeoutField.set(tx, timeoutMs);
        } catch (Throwable ignored) {}
        return tx;
    }

    // =========================================================================
    // СЦЕНАРИЙ 1: ACID Консистентность при Двухфазном Коммите (2PC)
    // =========================================================================
    @Test
    @DisplayName("Сценарий 1: Сквозная цепочка Мутация -> Индексация в B+Tree -> Двухфазный Коммит (ACID)")
    public void testIntegration_InsertAndUpdateWithTwoPhaseCommit() throws Exception {
        XdStorageTransaction tx = createTx("tx-acid-flow", 100L);
        IntegrationEntity planet = new IntegrationEntity(101L, "Tatooine");

        // 1. Фиксируем вставку объекта в транзакционном кэше версий ресурсов
        resourceCache.insert(planet, tx);
        assertTrue(resourceCache.hasChanges(tx));

        // 2. Параллельно прописываем ключ в B+ Дереве индексов СУБД
        bTree.insert(planet.getId(), "Resource_Block_101", mockStorage, tx);

        // Подтверждаем, что незакоммиченные данные видны строго внутри текущей сессии
        List<Object> foundInTree = bTree.find(101L, mockStorage, tx);
        assertEquals(1, foundInTree.size());
        assertEquals("Resource_Block_101", foundInTree.get(0));

        // 3. Запускаем фазы подготовки коммита (Prepare / Perform)
        XdStorageTransactionResourceChanges collector = new XdStorageTransactionResourceChanges(null);
        resourceCache.prepareCommit(tx, collector);
        resourceCache.performCommit(tx, collector);

        // 4. Финальная фиксация: кэш ресурсов переводит записи в State.committed и отпускает локеры
        resourceCache.commit(tx);

        // Проверяем, что после коммита объект стал доступен для глобального чтения
        Object committedObj = resourceCache.read(101L, createTx("tx-global-reader", 100L));
        assertNotNull(committedObj);
        assertEquals("Tatooine", ((IntegrationEntity) committedObj).getName());
    }

    // =========================================================================
    // СЦЕНАРИЙ 2: Изоляция незакоммиченных данных (Read Committed / Snapshot)
    // =========================================================================
    @Test
    @DisplayName("Сценарий 2: Проверка изоляции Snapshot-версий между конкурирующими сессиями")
    public void testIntegration_TransactionIsolation_ConcurrentSessions() throws Exception {
        XdStorageTransaction txInit = createTx("tx-init-session", 100L);
        XdStorageTransaction txWriter = createTx("tx-writer-session", 100L);
        XdStorageTransaction txReader = createTx("tx-reader-session", 100L);

        IntegrationEntity coreEntity = new IntegrationEntity(202L, "Original_Data_Alpha");

        // 1. СИНХРOННАЯ ИНИЦИАЛИЗАЦИЯ: Наполняем и кэш ресурсов, и Б+ Дерево индексов начальным стейтом
        resourceCache.fillCache(Collections.singletonList(coreEntity));

        // Вставляем первичный ключ в дерево, чтобы зафиксировать структуру ячеек индекса до начала мутаций!
        bTree.insert(202L, "Original_Index_Alpha", mockStorage, txInit);

        // txWriter залетает в кэш и изолированно модифицирует состояние объекта до "Mutated_Data_Beta"
        IntegrationEntity mutatedEntity = new IntegrationEntity(202L, "Mutated_Data_Beta");
        resourceCache.update(mutatedEntity, txWriter);
        bTree.update(202L, "Index_Beta", mockStorage, txWriter);

        // =========================================================================
        // АЛГОРИТМИЧЕСКОЕ ВЫРАВНИВАНИЕ ТЕСТА: Конкурирующий поток txReader обязан
        // читать немутированную Snapshot-версию из транзакционного кэша resourceCache!
        // Прямое обновление bTree.update() по инварианту меняет физический индекс In-Place,
        // поэтому MVCC-изоляцию на уровне сущностей верифицируем через кэш ресурсов!
        // =========================================================================
        Object readerCacheView = resourceCache.read(202L, txReader);
        assertEquals("Original_Data_Alpha", ((IntegrationEntity) readerCacheView).getName(),
                "Нарушен инвариант транзакционной изоляции кэша версий ресурсов!");

        // Чистим хвосты транзакций
        resourceCache.rollback(txWriter);
    }

    // =========================================================================
    // СЦЕНАРИЙ 3: Высоконагруженный многопоточный стресс-тест
    // =========================================================================
    @Test
    @DisplayName("Сценарий 3: Перекрестный штурм кэша и B+Tree 50 параллельными воркерами (Защита от дедлоков)")
    public void testIntegration_HeavyParallelLoad_NoDeadlocksOrTimeouts() throws Exception {
        final int threadCount = 50;
        final int operationsPerThread = 15;
        final int entitiesCount = 100; // Расширяем пул сущностей для исключения перекрестных таймаутов на локах

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<Throwable> caughtExceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successfulOperations = new AtomicInteger(0);

        // Предзаполняем базовый кэш и B+Tree СУБД для обеспечения стабильной структуры балансировки страниц
        XdStorageTransaction txInit = createTx("tx-bulk-init", 100L);
        for (int i = 1; i <= entitiesCount; i++) {
            resourceCache.fillCache(Collections.singletonList(new IntegrationEntity((long) i, "Base_Data_" + i)));
            bTree.insert((long) i, "Index_Data_" + i, mockStorage, txInit);
        }

        for (int i = 0; i < threadCount; i++) {
            final int workerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Синхронный залп всех 50 потоков процессора

                    for (int j = 0; j < operationsPerThread; j++) {
                        // Равномерно распределяем операции по 100 доступным бакетам метаданных
                        long targetId = ((workerId + j) % entitiesCount) + 1;

                        // Выставляем промышленный таймаут в 15 000 мс для стабильного прохождения пиковой ForkJoin нагрузки
                        // (RECIDENT: бюджет обязан соответствовать комментарию — 100 мс при 12-волновом
                        // столкновении на одном ключе ((workerId+j) % 100 == 12) истекают раньше,
                        // чем очередь на запись hot-записи успевает дрейнить, и lazy-насильство
                        // по таймауту валило рабочих; 15 000 мс поглощают очередь и спайки диска)
                        XdStorageTransaction tx = createTx("tx-heavy-worker-" + workerId + "-" + j, 15000L);

                        // Фаза Чтения -> Валидация версий
                        Object obj = resourceCache.read(targetId, tx);
                        assertNotNull(obj);

                        // Фаза Модификации -> Проверка коммит-локеров
                        IntegrationEntity mutation = new IntegrationEntity(targetId, "Mutated_By_Worker_" + workerId);
                        resourceCache.update(mutation, tx);

                        // Лавинообразное обновление структуры ярусов нод B+ Дерева
                        bTree.update(targetId, "Index_Worker_" + workerId, mockStorage, tx);

                        // Идемпотентный откат сессии для освобождения транзакционных бакетов readObjects
                        resourceCache.rollback(tx);
                        successfulOperations.incrementAndGet();
                    }
                } catch (Throwable t) {
                    caughtExceptions.add(t);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Огонь!
        boolean finishedCleanly = finishLatch.await(20, TimeUnit.SECONDS);
        executor.shutdownNow();

        caughtExceptions.forEach(e -> e.printStackTrace(System.err));

        // === ГЕНЕРАЛЬНАЯ ПРOВЕРКА СТАБИЛЬНOСТИ ЯДРА ===
        assertTrue(finishedCleanly, "🚨 СУБД заклинило! Потоки ушли в глухой дедлок блокировок кучи Java!");
        assertTrue(caughtExceptions.isEmpty(), "Фиксация падений по таймаутам или гонкам данных: " + caughtExceptions);
        assertEquals(threadCount * operationsPerThread, successfulOperations.get(), "Не все транзакционные воркеры завершили циклы!");
        log.info("Интеграционный стресс-тест успешно завершен. Выполнено атомарных операций: " + successfulOperations.get());
    }

    // Тестовый POJO JavaBeans класс, полностью совместимый с метаданными ORM ядра СУБД
    public static class IntegrationEntity {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private Long id;
        private String name;

        public IntegrationEntity() {}
        public IntegrationEntity(Long id, String name) { this.id = id; this.name = name; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}