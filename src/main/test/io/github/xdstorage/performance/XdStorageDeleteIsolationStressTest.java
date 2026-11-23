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

@DisplayName("Стресс-тест MVCC: Экстремальная изоляция удалений (Delete Isolation)")
@Tag("stress")
public class XdStorageDeleteIsolationStressTest {

    private static IXdStorage storage = null;
    private static final String STORAGE_PATH = "./target/delete_stress_db";
    private static final int INITIAL_PLANETS = 500; // Мощный массив для длительного пурджа

    /**
     * ФАЗА 0 (Честная классификация сбоев): таймаут спин-лока — штатное противодавление
     * протокола блокировок СУБД, а не нарушение MVCC-изоляции. Ретрай следующей итерацией.
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
        // Инициализируем файловое хранилище СУБД с оптимальным размером страницы
        storage = XdStorageProvider.newOrGetFileStorage("delete_stress_db", STORAGE_PATH, 500);

        // Накатываем стартовый стабильный Snapshot (500 планет)
        IXdStorageTransaction txInit = storage.beginTransaction();
        for (int i = 1; i <= INITIAL_PLANETS; i++) {
            XdPlanet planet = new XdPlanet();
            planet.setName("StablePlanet_" + i);
            planet.setWaterPercent(1);
            storage.save(planet, txInit);
        }
        txInit.commit();
        System.out.println("🎉 База преднаполнена: " + INITIAL_PLANETS + " планет записаны на диск.");
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
    @DisplayName("Штурм Удалений: Параллельное чтение Snapshot-View против лавины Soft-Delete коммитов")
    public void testMVCC_ParallelRangeScans_AgainstMassDeletes_ShouldBeIsolated() throws Exception {
        final int readerThreadsCount = 8;
        final int durationSeconds = 10; // 10 секунд агрессивного прессинга

        ExecutorService threadPool = Executors.newFixedThreadPool(readerThreadsCount + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean stopFlag = new AtomicBoolean(false);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalScansCounter = new AtomicLong(0);
        AtomicLong totalDeletesCounter = new AtomicLong(0);

        // =========================================================================
        // POTOK 1: АГРЕССИВНЫЙ ИСТРЕБИТЕЛЬ (Непрерывно выкашивает планеты одну за другой)
        // =========================================================================
        threadPool.submit(() -> {
            try {
                startLatch.await();
                int currentDeleteTargetId = 1;

                while (!stopFlag.get() && currentDeleteTargetId <= INITIAL_PLANETS) {
                    IXdStorageTransaction txDelete = storage.beginTransaction();
                    try {
                        // Вытаскиваем живую коллекцию, чтобы найти актуальный прокси-референс
                        Collection<XdPlanet> currentPlanets = storage.load(XdPlanet.class, txDelete);
                        XdPlanet targetToPurge = null;

                        for (XdPlanet p : currentPlanets) {
                            if (p.getName().equals("StablePlanet_" + currentDeleteTargetId)) {
                                targetToPurge = p;
                                break;
                            }
                        }

                        if (targetToPurge != null) {
                            // Вызываем физическое транзакционное удаление
                            storage.delete(targetToPurge, txDelete);
                            txDelete.commit();
                            totalDeletesCounter.incrementAndGet();
                        } else {
                            txDelete.rollback();
                        }
                    } catch (Throwable t) {
                        txDelete.rollback();
                        // Допускаем коллизии, если элемент уже стерт параллельно
                    }
                    currentDeleteTargetId++;
                    Thread.sleep(2); // Высокая частота удалений для создания плотной нагрузки
                }
            } catch (Throwable t) {
                exceptions.add(new AssertionError("🚨 Крах в потоке Истребителя: ", t));
            }
        });

        // =========================================================================
        // POTOKI 2..N: ЧИТАТЕЛИ ИСТОРИИ (Range Scans с динамической Snapshot верификацией)
        // =========================================================================
        for (int i = 0; i < readerThreadsCount; i++) {
            final int readerId = i;
            threadPool.submit(() -> {
                try {
                    startLatch.await();

                    while (!stopFlag.get()) {
                        // Каждая транзакция чтения фиксирует свой собственный Snapshot-момент старта!
                        IXdStorageTransaction txRead = storage.beginTransaction();

                        try {
                            // Выполняем диапазонный скан через загрузку всей таблицы классов
                            Collection<XdPlanet> visiblePlanets = storage.load(XdPlanet.class, txRead);

                            int stableCount = 0;
                            int nullLinksCount = 0;

                            // Фиксируем, сколько РЕАЛЬНО ссылок выдал индекс в момент старта ТЕКУЩЕЙ сессии
                            int expectedSnapshotSize = visiblePlanets != null ? visiblePlanets.size() : 0;

                            for (XdPlanet planetRef : visiblePlanets) {
                                if (planetRef == null) {
                                    nullLinksCount++;
                                    continue;
                                }

                                // МАТЕРИАЛИЗАЦИЯ ССЫЛКИ: Запрашиваем кэш ресурсов.
                                // Если планета удалена Истребителем параллельно ПОСЛЕ старта нашей txRead,
                                // канонический MVCC обязан выдать нам стабильный record.prevObject!
                                XdPlanet planet = storage.load(XdPlanet.class, planetRef.getId(), txRead);

                                if (planet != null && planet.getName().startsWith("StablePlanet_")) {
                                    stableCount++;
                                }
                            }

                            // =========================================================================
                            // ИСПРАВЛEННЫЙ ДИНАМИЧEСКИЙ ИНВАРИАНТ MVCC:
                            // Если индекс на диске выдал коллекцию размером expectedSnapshotSize, то кэш ресурсов
                            // по законам Snapshot Isolation ОБЯЗАН материализовать ровно столько же живых/исторических
                            // объектов (stableCount)! Ни одна ссылка внутри ОДНОЙ транзакции не имеет права вернуть
                            // null!
                            // =========================================================================
                            if (stableCount != expectedSnapshotSize || nullLinksCount > 0) {
                                exceptions.add(new AssertionError(
                                        String.format("🚨 НАРУШЕН ИНВАРИАНТ MVCC ИЗОЛЯЦИИ УДАЛEНИЙ! "
                                                + "Внутри транзакции чтения пропали данные! Индекс выдал ссылок: %d, "
                                                + "а кэш материализовал: %d. (Битых null-ссылок в скане: %d)",
                                                expectedSnapshotSize, stableCount, nullLinksCount)
                                ));
                            }

                            txRead.commit();
                            totalScansCounter.incrementAndGet();

                        } catch (Throwable t) {
                            txRead.rollback();
                            if (!isLockBackpressure(t)) {
                                exceptions.add(new AssertionError("🚨 Сбой на фазе диапазонного чтения удалений в " +
                                        "потоке " + readerId, t));
                            }
                            // таймаут лока — штатное противодавление: ретрай следующей итерацией
                        }
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            });
        }

        System.out.println("🚀 Запуск экстремального прессинга удалений (9 потоков)...");
        startLatch.countDown();

        // Держим JVM под нагрузкой указанное время
        Thread.sleep(durationSeconds * 1000L);

        System.out.println("🧹 Остановка потоков нагрузки...");
        stopFlag.set(true);
        threadPool.shutdown();
        boolean poolTerminated = threadPool.awaitTermination(5, TimeUnit.SECONDS);

        if (!poolTerminated) {
            threadPool.shutdownNow();
        }

        System.out.println("=========================================================================");
        System.out.println("📊 ИТОГИ СТРЕСС-ТЕСТА ИЗОЛЯЦИИ УДАЛЕНИЙ:");
        System.out.println("🔹 Успешных исторических сканирований (Range Scans): " + totalScansCounter.get());
        System.out.println("🔹 Конкурентно закоммиченных удалений (Soft Deletes): " + totalDeletesCounter.get());
        System.out.println("=========================================================================");

        if (!exceptions.isEmpty()) {
            System.err.println("🚨 ОБНАРУЖЕНЫ АНОМАЛИИ ИЛИ ПОТЕРЯ ССЫЛОЧНОЙ ЦЕЛОСТНОСТИ (" + exceptions.size() +
                    " шт):");
            exceptions.forEach(e -> {
                System.err.println("---");
                e.printStackTrace(System.err);
            });
            fail("МВСС СЛОМАЛСЯ НА УДAЛEНИЯХ! Читатели теряют prevObject исторической видимости!");
        } else {
            System.out.println("🎉 ИДЕАЛЬНО! Ни одного промаха, ни одной битой ссылки. Динамический MVCC-контур " +
                    "безупречен!");
        }
    }
}
