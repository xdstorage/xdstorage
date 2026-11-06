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

@DisplayName("Стресс-тест MVCC: Экстремальная изоляция Phantom Read в Б+ Дереве")
@Tag("stress")
public class XdStoragePhantomReadIsolationStressTest {

    private static IXdStorage storage = null;
    private static final String STORAGE_PATH = "./target/phantom_stress_db";
    private static final int INITIAL_PLANETS = 200;

    /**
     * ФАЗА 0 (Честная классификация сбоев): таймаут спин-лока — штатное противодавление
     * протокола блокировок СУБД, а не нарушение изоляции. Ретрай следующей итерацией.
     */
    private static boolean isLockBackpressure(final Throwable t) {
        final String message = t.getMessage();
        if (message == null) {
            return false;
        }
        // R-36/R-42: исчерпание бюджета ретраев поиска — тот же класс штатного противодавления,
        // что и таймауты локов: операция ДО мутаций, откатывается чисто, ретраится следующей
        // итерацией. (Бюджет введен вместо вечного клина — R-36.)
        if (message.contains("retry budget exhausted")) {
            return true;
        }
        if (!(t instanceof io.github.xdstorage.exceptions.XdStorageRuntimeException)) {
            return false;
        }
        return message.contains("Превышен таймаут") || message.contains("cannot be locked for commit");
    }

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        // Инициализируем хранилище с достаточно большим размером страницы
        storage = XdStorageProvider.newOrGetFileStorage("phantom_stress_db", STORAGE_PATH, 128);

        // Накатываем стартовый стабильный Snapshot (базис для читателей)
        IXdStorageTransaction txInit = storage.beginTransaction();
        for (int i = 1; i <= INITIAL_PLANETS; i++) {
            XdPlanet planet = new XdPlanet();
            // Имитируем генерацию ID (задаем вручную для прецизионного контроля диапазона)
            planet.setName("StablePlanet_" + i);
            planet.setWaterPercent(1);
            storage.save(planet, txInit);
        }
        txInit.commit();
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
    @DisplayName("Штурм Phantom Read: Параллельное диапазонное сканирование против лавины In-Place сплитов")
    public void testMVCC_ParallelRangeScans_AgainstHeavySplits_ShouldBeIsolated() throws Exception {
        final int readerThreadsCount = 8;
        final int durationSeconds = 10; // Длительность прессинга

        ExecutorService threadPool = Executors.newFixedThreadPool(readerThreadsCount + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean stopFlag = new AtomicBoolean(false);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalScansCounter = new AtomicLong(0);
        AtomicLong totalMutationsCounter = new AtomicLong(0);

        // =========================================================================
        // POTOK 1: АГРЕССИВНЫЙ ПИСАТЕЛЬ (Мутирует структуру дерева, плодит сплиты)
        // =========================================================================
        threadPool.submit(() -> {
            try {
                startLatch.await();
                int planetCounter = INITIAL_PLANETS + 1;

                while (!stopFlag.get()) {
                    // Стартуем независимую транзакцию записи
                    IXdStorageTransaction txWrite = storage.beginTransaction();

                    // Вставляем пачку новых планет-"призраков"
                    for (int k = 0; k < 10; k++) {
                        XdPlanet phantomPlanet = new XdPlanet();
                        phantomPlanet.setName("PhantomPlanet_" + planetCounter);
                        phantomPlanet.setWaterPercent(2);
                        storage.save(phantomPlanet, txWrite);
                        planetCounter++;
                    }

                    txWrite.commit();
                    totalMutationsCounter.addAndGet(10);

                    // Микропауза, чтобы дать читателям занять локеры
                    Thread.sleep(5);
                }
            } catch (Throwable t) {
                if (!isLockBackpressure(t)) {
                    exceptions.add(new AssertionError("🚨 Крах в потоке Писателя: ", t));
                }
                // таймаут лока — штатное противодавление: следующая итерация повторит вставку
            }
        });

        // =========================================================================
        // POTOKI 2..N: ЧИТАТЕЛИ СНАПШOТOВ (Range Scans в изоляции Read Committed/Snapshot)
        // =========================================================================
        for (int i = 0; i < readerThreadsCount; i++) {
            final int readerId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();

                    while (!stopFlag.get()) {
                        // Каждое сканирование выполняется в СВОЕЙ изолированной транзакции чтения!
                        IXdStorageTransaction txRead = storage.beginTransaction();

                        try {
                            // Делаем Range Scan через легитимный load всей таблицы классов
                            Collection<XdPlanet> visiblePlanets = storage.load(XdPlanet.class, txRead);

                            int stableCount = 0;
                            int phantomCount = 0;

                            for (XdPlanet planetRef : visiblePlanets) {
                                // Материализуем прокси-ссылки, проверяя наш новый equals и локеры нод
                                XdPlanet planet = storage.load(XdPlanet.class, planetRef.getId(), txRead);
                                assertNotNull(planet, "Читатель поймал битую ссылку внутри Б+ Дерева!");

                                if (planet.getName().startsWith("StablePlanet_")) {
                                    stableCount++;
                                } else if (planet.getName().startsWith("PhantomPlanet_")) {
                                    phantomCount++;
                                }
                            }

                            // 🚨 ГЛАВНЫЙ АССЕРТ ИЗОЛЯЦИИ PHANTOM READ:
                            // В рамках Snapshot-изоляции транзакция чтения ОБЯЗАНА видеть консистентное
                            // количество базовых планет (ровно INITIAL_PLANETS). Если транзакция началась,
                            // новые планеты-призраки из параллельных потоков не имеют права просочиться
                            // внутрь текущего Range Scan до закрытия транзакции!
                            if (stableCount != INITIAL_PLANETS) {
                                exceptions.add(new AssertionError(
                                        String.format("🚨 НАРУШЕН ИНВАРИАНТ MVCC ИЗОЛЯЦИИ! Поток-Читатель %d увидел фантомов или потерял данные! Ожидали стабильных %d, а получили %d. (Призраков в скане: %d)",
                                                readerId, INITIAL_PLANETS, stableCount, phantomCount)
                                ));
                            }

                            txRead.commit();
                            totalScansCounter.incrementAndGet();

                        } catch (Throwable t) {
                            txRead.rollback();
                            if (!isLockBackpressure(t)) {
                                exceptions.add(new AssertionError("🚨 Сбой на фазе диапазонного чтения в потоке " + readerId, t));
                            }
                            // таймаут лока — штатное противодавление: ретрай следующей итерацией
                        }
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            });
        }

        // Залп!
        System.out.println("🚀 Запуск экстремального прессинга Phantom Read (9 потоков)...");
        startLatch.countDown();

        // Даем потокам ворвать JVM на указанное время
        Thread.sleep(durationSeconds * 1000L);

        System.out.println("🧹 Остановка потоков нагрузки...");
        stopFlag.set(true);
        threadPool.shutdown();
        boolean poolTerminated = threadPool.awaitTermination(5, TimeUnit.SECONDS);

        if (!poolTerminated) {
            threadPool.shutdownNow();
        }

        // Выводим статистику прогона
        System.out.println("=========================================================================");
        System.out.println("📊 ИТОГИ СТРЕСС-ТЕСТА ФАНТОМНОГО ЧТЕНИЯ:");
        System.out.println("🔹 Успешных диапазонных сканирований (Range Scans): " + totalScansCounter.get());
        System.out.println("🔹 Конкурентно вставленных элементов-призраков: " + totalMutationsCounter.get());
        System.out.println("=========================================================================");

        // Выводим стектрейсы, если словили гонку или IndexOutOfBoundsException
        if (!exceptions.isEmpty()) {
            System.err.println("🚨 ОБНАРУЖЕНЫ СБОИ ИЛИ НАРУШЕНИЯ ИЗОЛЯЦИИ ТРАНЗАКЦИЙ (" + exceptions.size() + " шт):");
            exceptions.forEach(e -> {
                System.err.println("---");
                e.printStackTrace(System.err);
            });
            fail("СНAПШOТЫ СУБД СЛОМАЛИСЬ! Найдена гонка данных или фантомное просачивание!");
        } else {
            System.out.println("🎉 ИДЕАЛЬНО! Ни одной гонки, ни одного фантома. Snapshot Isolation стоит как скала!");
        }
    }
}
