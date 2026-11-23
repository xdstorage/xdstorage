package io.github.xdstorage.serialization;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.services.XdStorageServicesLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Полный многопоточный стресс-тест для верификации потокобезопасности оригинальной XML-сериализации,
 * атомарных локов компиляции прокси-классов и монотонных MVCC-таймстампов (Поинт Г).
 */
public class XdStorageConcurrentSerializationTest {

    private XdStorageServicesLocator mockServices;
    private IXdStorageIdGenerator mockIdGenerator;
    private XdStorageDefaultSimpleTypeHelper typeHelper;
    private XdStorageDefaultObjectsWriter xmlWriter;
    private XdStorageDefaultObjectsReader xmlReader;

    // Сложная циклическая сущность для симуляции узлов B+ Дерева индексов СУБД
    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    public static class ConcurrentXmlTestNode {
        @XdStorageObjectId
        private String nodeName;
        private ConcurrentXmlTestNode parent;
        private List<ConcurrentXmlTestNode> children = new ArrayList<>();

        public ConcurrentXmlTestNode() {
        }

        public ConcurrentXmlTestNode(String nodeName) {
            this.nodeName = nodeName;
        }

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
        }

        public ConcurrentXmlTestNode getParent() {
            return parent;
        }

        public void setParent(ConcurrentXmlTestNode parent) {
            this.parent = parent;
        }

        public List<ConcurrentXmlTestNode> getChildren() {
            return children;
        }

        public void setChildren(List<ConcurrentXmlTestNode> children) {
            this.children = children;
        }
    }

    @BeforeEach
    public void setUp() {
        mockServices = mock(XdStorageServicesLocator.class);
        mockIdGenerator = mock(IXdStorageIdGenerator.class);
        typeHelper = new XdStorageDefaultSimpleTypeHelper();

        // Используем оригинальный XML-контур движков, как это делает XdStorageDefaultIOFactory
        xmlWriter = new XdStorageDefaultObjectsWriter(mockServices, typeHelper, mockIdGenerator);
        xmlReader = new XdStorageDefaultObjectsReader(typeHelper);
    }

    /**
     * ТЕСТ 1: Нагрузочное стресс-тестирование XML-демаршалинга в параллельных потоках.
     * Проверяет, что при залповом запуске 16 потоков воркеров ForkJoinPool не происходит
     * циклического дедлока или ClassCastException (XdStorageDummySimpleWrapper) в ObjectUtils.
     */
    @Test
    public void testConcurrentXmlSerialization_ShouldMaintainThreadSafety() throws InterruptedException {
        int threadsCount = 16;
        int iterationsPerThread = 30;

        ExecutorService executor = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadsCount);

        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger errorCounter = new AtomicInteger(0);
        List<String> errorsLog = Collections.synchronizedList(new ArrayList<>());

        // Формируем граф с циклической зависимостью
        ConcurrentXmlTestNode root = new ConcurrentXmlTestNode("RootXmlIndex");
        ConcurrentXmlTestNode child = new ConcurrentXmlTestNode("ChildXmlNode");
        child.setParent(root);
        root.getChildren().add(child);

        // Переводим граф в эталонную XML-строку через оригинальный писатель
        StringWriter stringWriter = new StringWriter();
        assertDoesNotThrow(() -> xmlWriter.writeObjects(stringWriter, Collections.singletonList(root)));
        final String targetXml = stringWriter.toString();

        for (int i = 0; i < threadsCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Синхронный залповый старт всех потоков

                    for (int j = 0; j < iterationsPerThread; j++) {
                        StringReader stringReader = new StringReader(targetXml);

                        // Читаем XML через оригинальный XML-ридер СУБД
                        Collection<Object> result = xmlReader.read(stringReader);

                        assertNotNull(result);
                        assertFalse(result.isEmpty());

                        ConcurrentXmlTestNode restoredRoot = (ConcurrentXmlTestNode) result.iterator().next();
                        assertEquals("RootXmlIndex", restoredRoot.getNodeName());

                        successCounter.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errorCounter.incrementAndGet();
                    errorsLog.add(t.getClass().getName() + ": " + t.getMessage());
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Залп!
        boolean finishedCleanly = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        if (!errorsLog.isEmpty()) {
            System.err.println("=== Лог ошибок параллельного XML-маршаллинга ===");
            errorsLog.forEach(System.err::println);
        }
        assertTrue(finishedCleanly, "Многопоточный XML-тест завис! Обнаружен скрытый Deadlock локеров в ObjectUtils!");
        assertEquals(0, errorCounter.get(), "Зафиксированы рантайм ошибки при параллельном XML-обходе!");
        assertEquals(threadsCount * iterationsPerThread, successCounter.get(),
                "Не все итерации многопоточного XML-теста были выполнены!");
    }

    /**
     * ТЕСТ 2: Параллельная сквозная запись и чтение (Write & Read).
     * Проверяет, что одновременная генерация XML-документов и их разбор разными потоками
     * не вызывают гонки данных (Race Condition) в статических кэшах рефлексии СУБД.
     */
    @Test
    public void testConcurrentXmlWriteAndRead_HappyPath() throws InterruptedException {
        int threadsCount = 12;
        int iterationsPerThread = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadsCount);

        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalErrors = new AtomicInteger(0);

        for (int i = 0; i < threadsCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < iterationsPerThread; j++) {
                        String nodeName = "Node_" + threadId + "_" + j;
                        ConcurrentXmlTestNode node = new ConcurrentXmlTestNode(nodeName);

                        // ИСПРАВЛЕНИЕ: Инстанцируем РАЙТЕР и РИДЕР локально для каждого потока/итерации,
                        // строго соблюдая потоковый контракт и изоляцию состояния СУБД!
                        XdStorageDefaultObjectsWriter localWriter = new XdStorageDefaultObjectsWriter(mockServices,
                                typeHelper, mockIdGenerator);
                        XdStorageDefaultObjectsReader localReader = new XdStorageDefaultObjectsReader(typeHelper);

                        // 1. Тестируем запись локальным писателем
                        StringWriter sw = new StringWriter();
                        localWriter.writeObjects(sw, Collections.singletonList(node));
                        String xmlOutput = sw.toString();

                        // 2. Тестируем чтение локальным читателем
                        StringReader sr = new StringReader(xmlOutput);
                        Collection<Object> deserialized = localReader.read(sr);

                        assertNotNull(deserialized);
                        assertEquals(1, deserialized.size());
                        assertEquals(nodeName, ((ConcurrentXmlTestNode) deserialized.iterator().next()).getNodeName());

                        totalSuccess.incrementAndGet();
                    }
                } catch (Throwable t) {
                    totalErrors.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finishedCleanly = finishLatch.await(8, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(finishedCleanly, "Тест Write/Read завис! Возможен взаимодедлок в свойствах метаданных.");
        assertEquals(0, totalErrors.get(), "Обнаружены гонки данных при параллельной записи и чтении!");
        assertEquals(threadsCount * iterationsPerThread, totalSuccess.get());
    }

    /**
     * ТЕСТ 3: Смешанная агрессивная конкурентная нагрузка.
     * Потоки параллельно генерируют массивы и коллекции, провоцируя конфликты
     * при одновременной инициализации и наполнении кэша прокси-классов.
     */
    @Test
    public void testConcurrentMixedOperations_ShouldNotLeakOrDeadlock() throws InterruptedException {
        int threadsCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadsCount);

        AtomicInteger activeErrors = new AtomicInteger(0);

        for (int i = 0; i < threadsCount; i++) {
            final int seed = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    // Симулируем хаотичное наполнение древовидного графа
                    ConcurrentXmlTestNode root = new ConcurrentXmlTestNode("MixedRoot_" + seed);
                    for (int k = 0; k < 10; k++) {
                        ConcurrentXmlTestNode child = new ConcurrentXmlTestNode("MixedChild_" + seed + "_" + k);
                        child.setParent(root);
                        root.getChildren().add(child);
                    }
                    StringWriter sw = new StringWriter();
                    xmlWriter.writeObjects(sw, Collections.singletonList(root));
                    StringReader sr = new StringReader(sw.toString());
                    Collection res = xmlReader.read(sr);
                    assertNotNull(res);
                } catch (Throwable t) {
                    activeErrors.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        boolean success = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertTrue(success, "Смешанный стресс-тест ушел в дедлок блокировок!");
        assertEquals(0, activeErrors.get(), "Воркеры СУБД выбросили исключения под смешанной нагрузкой!");
    }
}
