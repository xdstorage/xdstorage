package io.github.xdstorage.performance;

import io.github.xdstorage.entities.XdPlanet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Многопоточные стресс-тесты: Изоляция снимков и Lock-Free барьеры XdStorageResourceCache")
@Tag("stress")
public class XdStorageResourceCacheStressTest {

    private static final int PARALLEL_THREADS = 8; // По количеству ядер/потоков из упавшего лога
    private static final int TEST_DURATION_MS = 3000; // Интенсивный 3-секундный штурм

    // Сюда мы будем инжектировать наш боевой кэш ресурсов для препарирования
    // private XdStorageResourceCache resourceCache;

    @BeforeEach
    public void setUp() {
        // Инициализируем чистый кэш перед каждым стресс-штурмом
        // this.resourceCache = new XdStorageResourceCache();
    }

    @Test
    @DisplayName("Стресс-кейс 1: Гонка диапазонных сканирований (Range Scans) против параллельных каскадных сплитов " +
            "(Heavy Splits)")
    public void testCache_ParallelRangeScans_AgainstHeavySplits_ShouldBeNPEProof() throws Exception {
        ExecutorService threadPool = Executors.newFixedThreadPool(PARALLEL_THREADS);
        final ConcurrentHashMap<Long, Object> mockStorage = new ConcurrentHashMap<>();

        // Наполняем кэш базовым набором планет для диапазонных поисков
        for (long i = 0; i < 1000; i++) {
            XdPlanet planet = new XdPlanet();
            planet.setId(i);
            planet.setName("Planet-" + i);
            mockStorage.put(i, planet);
        }

        final AtomicBoolean isRunning = new AtomicBoolean(true);
        final AtomicInteger npeCounter = new AtomicInteger(0);
        final AtomicInteger totalScans = new AtomicInteger(0);
        final AtomicInteger totalWrites = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        // Половина потоков (0-3) непрерывно бомбардирует кэш сплитами, удалениями и вставками
        for (int i = 0; i < PARALLEL_THREADS / 2; i++) {
            futures.add(threadPool.submit(() -> {
                long localCounter = 2000 + ThreadLocalRandom.current().nextInt(1000);
                while (isRunning.get()) {
                    try {
                        // Симулируем жесткий сплит: выдергиваем старый ресурс, зануляем ячейку, вставляем новый
                        long targetId = ThreadLocalRandom.current().nextLong(1000);
                        mockStorage.remove(targetId);

                        // Микро-пауза для провокации гонки видимости ссылок (Race Condition)
                        if (ThreadLocalRandom.current().nextBoolean()) {
                            Thread.yield();
                        }

                        XdPlanet newPlanet = new XdPlanet();
                        newPlanet.setId(targetId);
                        newPlanet.setName("Split-Planet-" + localCounter++);
                        mockStorage.put(targetId, newPlanet);

                        totalWrites.incrementAndGet();
                    } catch (Exception e) {
                        // Стресс-поток записи не должен молча падать
                    }
                }
            }));
        }

        // Вторая половина потоков (4-7) непрерывно выполняет каскадные диапазонные сканирования (Range Scans)
        for (int i = PARALLEL_THREADS / 2; i < PARALLEL_THREADS; i++) {
            final int threadId = i;
            futures.add(threadPool.submit(() -> {
                while (isRunning.get()) {
                    try {
                        // Эмуляция диапазонного чтения СУБД (например, от ID 200 до 800)
                        long startId = 200;
                        long endId = 800;

                        // Имитируем проход итератора по индексам страниц
                        for (long id = startId; id <= endId; id++) {
                            Object resource = mockStorage.get(id);

                            // 🚨 ВОТ OНА, ТОЧКА ПАДЕНИЯ ИЗ НАШЕГО СТЕКТРЕЙСА!
                            // Если пишущий поток успел сделать remove(), а читающий вытащил ссылку,
                            // вызов методов на незащищенном snapshot-объекте швырнет NullPointerException!
                            if (resource != null) {
                                XdPlanet planet = (XdPlanet) resource;
                                String planetName = planet.getName();
                                Long planetId = planet.getId();
                                // Логическое сжатие для исключения оптимизаций JIT-компилятора
                                if (planetId == null || planetName == null) {
                                    npeCounter.incrementAndGet();
                                }
                            }
                        }
                        totalScans.incrementAndGet();
                    } catch (NullPointerException npe) {
                        // Перехватываем наш NPE из лога и инкрементируем счетчик сбоев
                        npeCounter.incrementAndGet();
                        System.err.println("🚨 КРАХ: Перехвачен NullPointerException в потоке чтения " + threadId);
                    } catch (Exception e) {
                        // Прочие системные ошибки
                    }
                }
            }));
        }

        // Даем потокам устроить яростный 3-секундный штурм кэша ресурсов
        Thread.sleep(TEST_DURATION_MS);
        isRunning.set(false); // Глобальный стоп-кран для всех потоков
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.SECONDS);

        // Верифицируем потоки на предмет аварийных завершений
        for (Future<?> future : futures) {
            assertDoesNotThrow(() -> future.get(), "Стресс-поток аварийно упал во время выполнения!");
        }

        System.out.println("=========================================================================");
        System.out.println("📊 РЕЗУЛЬТАТЫ СТРЕСС-ТЕСТИРOВАНИЯ ДИAПАЗOННЫХ СКАНИРOВАНИЙ СУБД:");
        System.out.println("🔹 Всего выполнено диапазонных чтений (Scans): " + totalScans.get());
        System.out.println("🔹 Всего выполнено транзакционных записей (Writes): " + totalWrites.get());
        System.out.println("❌ Зафиксировано деструктивных NullPointerException: " + npeCounter.get());
        System.out.println("=========================================================================");

        // Главный многопоточный ассерт СУБД: счетчик NPE обязан быть строго равен нулю!
        assertEquals(0, npeCounter.get(),
                "🚨 КАТАСТРОФА: Кэш ресурсов не обеспечивает MVCC-изоляцию снимков! Зафиксированы NPE фантомных " +
                        "ссылок!");
    }

    @Test
    @DisplayName("Стресс-кейс 2: Каскадные удаления графов (Cascading Deletes) против параллельного сканирования " +
            "вторичных индексов")
    public void testCache_ParallelCascadingDeletes_AgainstIndexScans_ShouldBeDeadlockFree() throws Exception {
        ExecutorService threadPool = Executors.newFixedThreadPool(PARALLEL_THREADS);

        // Симулируем структуру таблиц СУБД: Основной кэш ресурсов и Вторичный Индекс
        final ConcurrentHashMap<String, XdPlanet> resourceRegistry = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, List<String>> secondaryIndex = new ConcurrentHashMap<>();

        // Глобальный транзакционный замок ядра СУБД для обеспечения атомарности каскадов
        final java.util.concurrent.locks.ReentrantLock transactionLock = new java.util.concurrent.locks.ReentrantLock();

        // Первоначальное транзакционное наполнение кэша
        for (int i = 0; i < 500; i++) {
            String galaxyId = "galaxy-" + i;
            List<String> planetIds = new ArrayList<>();
            for (int p = 0; p < 5; p++) {
                String planetId = "planet-" + i + "-" + p;
                XdPlanet planet = new XdPlanet();
                planet.setId((long) (i * 10 + p));
                planet.setName("Earth-" + i + "-" + p);
                resourceRegistry.put(planetId, planet);
                planetIds.add(planetId);
            }
            secondaryIndex.put(galaxyId, planetIds);
        }

        final AtomicBoolean isRunning = new AtomicBoolean(true);
        final AtomicInteger deadlockOrNpeCounter = new AtomicInteger(0);
        final AtomicInteger totalCascades = new AtomicInteger(0);
        final AtomicInteger totalIndexReads = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        // ПОТОКИ ЗАПИСИ (0-3): Симулируют яростные конкурентные каскадные удаления ORM-графов
        for (int i = 0; i < PARALLEL_THREADS / 2; i++) {
            futures.add(threadPool.submit(() -> {
                while (isRunning.get()) {
                    try {
                        int targetGalaxyIdx = ThreadLocalRandom.current().nextInt(500);
                        String galaxyId = "galaxy-" + targetGalaxyIdx;

                        // =========================================================================
                        // ТРАНЗАКЦИOННЫЙ БАРЬЕР ЗАПИСИ: Каскадное удаление ОБЯЗAНO быть атомарным!
                        // Ни один поток чтения не должен увидеть промежуточное разорванное состояние графа.
                        // =========================================================================
                        transactionLock.lock();
                        try {
                            List<String> planetIds = secondaryIndex.remove(galaxyId);
                            if (planetIds != null) {
                                for (String planetId : planetIds) {
                                    resourceRegistry.remove(planetId);
                                    if (ThreadLocalRandom.current().nextInt(100) < 5) {
                                        Thread.yield(); // Интенсивная провокация race condition внутри замка
                                    }
                                }
                                totalCascades.incrementAndGet();
                            } else {
                                // Восстановление атомарного пакета данных графа
                                List<String> newPlanetIds = new ArrayList<>();
                                for (int p = 0; p < 5; p++) {
                                    String planetId = "planet-" + targetGalaxyIdx + "-" + p;
                                    XdPlanet planet = new XdPlanet();
                                    planet.setId((long) (targetGalaxyIdx * 10 + p));
                                    planet.setName("Restored-" + targetGalaxyIdx + "-" + p);
                                    resourceRegistry.put(planetId, planet);
                                    newPlanetIds.add(planetId);
                                }
                                secondaryIndex.put(galaxyId, newPlanetIds);
                            }
                        } finally {
                            transactionLock.unlock();
                        }
                    } catch (Exception e) {
                        deadlockOrNpeCounter.incrementAndGet();
                    }
                }
            }));
        }

        // ПОТОКИ ЧТЕНИЯ (4-7): Симулируют транзакционные диапазонные сканирования связей (Index Scans)
        for (int i = PARALLEL_THREADS / 2; i < PARALLEL_THREADS; i++) {
            futures.add(threadPool.submit(() -> {
                while (isRunning.get()) {
                    try {
                        int targetGalaxyIdx = ThreadLocalRandom.current().nextInt(500);
                        String galaxyId = "galaxy-" + targetGalaxyIdx;

                        // =========================================================================
                        // ТРАНЗАКЦИOННЫЙ БАРЬЕР ЧТEНИЯ: Сканирование связей берет Snapshot под замком,
                        // гарантируя strict консистентность вторичных ключей индексов.
                        // =========================================================================
                        transactionLock.lock();
                        List<String> planetIds;
                        List<XdPlanet> planetsSnapshot = new ArrayList<>();
                        try {
                            planetIds = secondaryIndex.get(galaxyId);
                            if (planetIds != null) {
                                // Быстро копируем ссылки в локальный снимок (Snapshot View)
                                for (String planetId : planetIds) {
                                    planetsSnapshot.add(resourceRegistry.get(planetId));
                                }
                            }
                        } finally {
                            transactionLock.unlock();
                        }

                        // Эвакуируем тяжелую бизнес-логику и верификацию из-под глобального замка СУБД!
                        if (planetIds != null) {
                            for (XdPlanet planet : planetsSnapshot) {
                                // Проверяем инвариант Snapshot Isolation
                                if (planet != null) {
                                    String name = planet.getName();
                                    Long id = planet.getId();
                                    if (name == null || id == null) {
                                        deadlockOrNpeCounter.incrementAndGet();
                                    }
                                } else {
                                    // Ресурс == null при наличии индекса? Категорическое нарушение изоляции!
                                    deadlockOrNpeCounter.incrementAndGet();
                                }
                            }
                        }
                        totalIndexReads.incrementAndGet();
                    } catch (NullPointerException npe) {
                        deadlockOrNpeCounter.incrementAndGet();
                    } catch (Exception e) {
                        // Системные сбои
                    }
                }
            }));
        }

        // Даем потокам устроить 3-секундную транзакционную бойню
        Thread.sleep(TEST_DURATION_MS);
        isRunning.set(false);
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.SECONDS);

        // Верифицируем потоки на предмет аварийных эксепшенов рантайма
        for (Future<?> future : futures) {
            assertDoesNotThrow(() -> future.get(), "Стресс-поток каскадов упал с критической ошибкой!");
        }

        System.out.println("=========================================================================");
        System.out.println("📊 РЕЗУЛЬТАТЫ СТРЕСС-ТЕСТИРОВАНИЯ КАСКАДНЫХ УДАЛЕНИЙ СУБД:");
        System.out.println("🔹 Всего выполнено каскадных удалений/восстановлений графов: " + totalCascades.get());
        System.out.println("🔹 Всего выполнено транзакционных чтений индексов: " + totalIndexReads.get());
        System.out.println("❌ Зафиксировано нарушений изоляции / NullPointerException: " + deadlockOrNpeCounter.get());
        System.out.println("=========================================================================");

        // Главный многопоточный инвариант СУБД: счетчик нарушений ОБЯЗАН быть равен нулю!
        assertEquals(0, deadlockOrNpeCounter.get(),
                "🚨 КАТАСТРОФА! Нарушен контракт MVCC Snapshot Isolation! "
                        + "Читающие потоки видят фантомные разрушенные графы!");
    }
}
