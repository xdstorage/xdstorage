package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.performance.entities.*;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FKBENCH Шаг 0 (task 50, ось контеншна): A/B-замер конкурентной записи блока —
 * FK включён/выключен × число потоков. Изолирует цену FK-штопора (пара пишется в
 * FK-дерево в контуре TX, change-слот записи дерева держится до коммита → отношение
 * сериализуется на длительность TX) от базового пути записи (домен WRTPATH).
 *
 * <p>Методология: каждому воркеру — СВОЯ вселенная (нулевая конкуренция на корневой
 * записи), единственный общий штопор — FK-деревья отношений. Сериальный прогрев
 * убирает одноразовую цену инфраструктуры (wrapper-кодогенерация, создание деревьев,
 * первичное заполнение индексов). Через окно замера: только коммиты, приземлившиеся
 * внутри окна. Конфигурация честная: дефолтный tx-таймаут (3s) и дефолтный sweeper —
 * штопор-столлы и их цена входят в замер как есть.
 *
 * <p>Запуск (surefire-invisible по имени):
 * {@code mvn test -Dtest=XdStorageFkContentionBenchmark -Dfkbench=true}
 */
@DisplayName("FKBENCH Шаг 0: конкурентная запись блока — FK on/off × потоки")
public class XdStorageFkContentionBenchmark {

    private static final int WINDOW_MS = 5000;
    private static final int UNIVERSE_COUNT = 12;
    private static final long WIND_DOWN_MS = 30_000L;

    @Test
    @EnabledIfSystemProperty(named = "fkbench", matches = "true")
    @DisplayName("FKBENCH Шаг 0: матрица FK on/off × 1/5/10 потоков")
    public void runMatrixTest() throws Exception {
        runMatrix();
    }

    public static void main(String[] args) throws Exception {
        new XdStorageFkContentionBenchmark().runMatrix();
    }

    public void runMatrix() throws Exception {
        System.out.println("=== FKBENCH Шаг 0: конкурентная запись блока, окно " + WINDOW_MS +
                "ms, дефолтный tx-таймаут 3s ===");
        System.out.println("config          | tx/s    | p50     | p95     | p99     | max      | stalls>1s | sweeper " +
                "| gate | other");
        System.out.println("----------------|---------|---------|---------|---------|----------|-----------|---------" +
                "|------|------");

        for (final boolean fk : new boolean[]{false, true}) {
            for (final int threads : new int[]{1, 5, 10}) {
                runConfig(fk, threads);
            }
        }
        System.out.println("=== конец матрицы. Чтение: FK-off t=10 ≈ базовый путь (домен WRTPATH); FK-on t=10 vs " +
                "FK-on t=1 = цена штопора. ===");
    }

    private void runConfig(final boolean fkEnabled, final int threads) throws Exception {
        final String tag = "fk=" + (fkEnabled ? "ON " : "OFF") + " t=" + threads;
        final String dir = "./target/fkbench-" + (fkEnabled ? "on" : "off") + "-" + threads + "-" + System.nanoTime();

        final XdStorageConfig config = new XdStorageConfig.Builder()
                .journalWrites(true)
                .setEnableForeignKeys(fkEnabled)
                .build();
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "fkbench_" + System.nanoTime(), dir, 200, FilesFormat.YAML, config);

        final AtomicInteger committedInWindow = new AtomicInteger();
        final AtomicInteger stalls = new AtomicInteger();
        final AtomicInteger sweeperKills = new AtomicInteger();
        final AtomicInteger gateRefusals = new AtomicInteger();
        final AtomicInteger otherFailures = new AtomicInteger();
        final List<Long> durations = Collections.synchronizedList(new ArrayList<Long>());
        final AtomicLong windowEndNano = new AtomicLong(Long.MAX_VALUE);

        try {
            // --- сид: по вселенной на воркер + запас → нулевая конкуренция на корневой записи ---
            final List<String> universeIds = new ArrayList<>();
            final IXdStorageTransaction seedTx = storage.beginTransaction();
            for (int i = 0; i < UNIVERSE_COUNT; i++) {
                final XdUniverseAutoSave u = new XdUniverseAutoSave();
                u.setId(UUID.randomUUID().toString());
                storage.save(u, seedTx);
                universeIds.add(u.getId());
            }
            seedTx.commit();

            // --- сериальный прогрев: каждый воркер 1 блок ДО окна ---
            for (int i = 0; i < threads; i++) {
                insertBlock(storage, universeIds.get(i % universeIds.size()));
            }

            // --- гонка ---
            final CountDownLatch startLatch = new CountDownLatch(1);
            final AtomicBoolean stopFlag = new AtomicBoolean(false);
            final ExecutorService executor = Executors.newFixedThreadPool(threads);
            final long windowStart = System.nanoTime();
            windowEndNano.set(windowStart + WINDOW_MS * 1_000_000L);

            for (int w = 0; w < threads; w++) {
                final int workerIndex = w;
                final Random rand = new Random(42L + workerIndex);
                executor.submit(() -> {
                    final String targetUni = universeIds.get(workerIndex % universeIds.size());
                    startLatch.await();
                    while (!stopFlag.get()) {
                        final long txStart = System.nanoTime();
                        IXdStorageTransaction tx = null;
                        boolean committed = false;
                        String failureKind = null;
                        try {
                            tx = storage.beginTransaction();
                            final XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, targetUni, tx);
                            if (u != null) {
                                final XdGalaxyAutoSave g = new XdGalaxyAutoSave();
                                g.setId(UUID.randomUUID().toString());
                                u.addGalaxy(g);
                                final XdBlackHoleAutoSave hole = new XdBlackHoleAutoSave();
                                hole.setId(UUID.randomUUID().toString());
                                hole.setMass(rand.nextInt(1_000_000));
                                g.setHole(hole);
                                final XdStarSystemAutoSave sys = new XdStarSystemAutoSave();
                                sys.setId(UUID.randomUUID().toString());
                                sys.setNewName("System_" + rand.nextInt(1000));
                                g.addSystem(sys);
                                final XdPlanetAutoSave p = new XdPlanetAutoSave();
                                p.setName("Planet_" + rand.nextInt(1000));
                                final XdSatelliteAutoSave s = new XdSatelliteAutoSave();
                                s.setName("Sat_" + rand.nextInt(1000));
                                p.setSatellite(s);
                                sys.addPlanet(p);
                            }
                            storage.update(u, tx);
                            tx.commit();
                            committed = true;
                        } catch (final Throwable t) {
                            failureKind = classify(t);
                            if (otherFailures.get() < 3) {
                                System.out.println("[DIAG-F] " + failureKind + ": ");
                                t.printStackTrace(System.out);
                            }
                        } finally {
                            if (tx != null && !committed) {
                                try {
                                    tx.rollback();
                                } catch (final Throwable ignored) {
                                }
                            }
                        }
                        final long durMs = (System.nanoTime() - txStart) / 1_000_000L;
                        durations.add(durMs);
                        if (durMs > 1000) {
                            stalls.incrementAndGet();
                        }
                        if (committed) {
                            // в пропускную способность идут только коммиты внутри окна
                            if (System.nanoTime() <= windowEndNano.get()) {
                                committedInWindow.incrementAndGet();
                            }
                        } else if ("sweeper".equals(failureKind)) {
                            sweeperKills.incrementAndGet();
                        } else if ("gate".equals(failureKind)) {
                            gateRefusals.incrementAndGet();
                        } else if (failureKind != null) {
                            otherFailures.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            startLatch.countDown();
            Thread.sleep(WINDOW_MS);
            stopFlag.set(true);
            executor.shutdown();
            executor.awaitTermination(WIND_DOWN_MS, TimeUnit.MILLISECONDS);
            executor.shutdownNow();

            // --- агрегация ---
            final List<Long> sorted = new ArrayList<Long>(durations);
            Collections.sort(sorted);
            final double throughput = committedInWindow.get() / (WINDOW_MS / 1000.0);
            final long p50 = percentile(sorted, 0.50);
            final long p95 = percentile(sorted, 0.95);
            final long p99 = percentile(sorted, 0.99);
            final long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

            System.out.println(String.format(
                    "%-15s | %7.1f | %7s | %7s | %7s | %8s | %9d | %7d | %4d | %5d",
                    tag, throughput, fmtMs(p50), fmtMs(p95), fmtMs(p99), fmtMs(max),
                    stalls.get(), sweeperKills.get(), gateRefusals.get(), otherFailures.get()));
        } finally {
            try {
                storage.shutdown();
            } catch (final Exception ignored) {
            }
            deleteDir(new File(dir));
        }
    }

    private static long percentile(final List<Long> sorted, final double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * fraction)));
    }

    private static String fmtMs(final long ms) {
        return ms < 1000 ? ms + "ms" : (ms / 1000) + "s" + (ms % 1000) / 100;
    }

    private static String classify(final Throwable t) {
        final String m = String.valueOf(t.getMessage());
        if (m.contains("rolled back by timeout")) {
            return "sweeper";
        }
        if (m.contains("read-version check") || m.contains("can not be updated") || m.contains("can not be deleted")) {
            return "gate";
        }
        return "other";
    }

    private static void insertBlock(final IXdFileStorage storage, final String targetUni) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction();
        final XdUniverseAutoSave u = storage.load(XdUniverseAutoSave.class, targetUni, tx);
        if (u != null) {
            final XdGalaxyAutoSave g = new XdGalaxyAutoSave();
            g.setId(UUID.randomUUID().toString());
            u.addGalaxy(g);
            final XdBlackHoleAutoSave hole = new XdBlackHoleAutoSave();
            hole.setId(UUID.randomUUID().toString());
            g.setHole(hole);
            final XdStarSystemAutoSave sys = new XdStarSystemAutoSave();
            sys.setId(UUID.randomUUID().toString());
            g.addSystem(sys);
            final XdPlanetAutoSave p = new XdPlanetAutoSave();
            p.setName("WarmPlanet");
            sys.addPlanet(p);
        }
        storage.update(u, tx);
        tx.commit();
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
