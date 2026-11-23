package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-4: жизненный цикл — после shutdown() публичные вызовы падают громко;
 * shutdown идемпотентен; опционально — daemon-воркеры (забытый shutdown()
 * не вешает выход JVM) и shutdown-хук.
 */
@DisplayName("S-4: жизненный цикл хранилища (closed-guard, daemon, хук)")
public class XdStorageS4LifecycleTest {

    @Test
    @DisplayName("После shutdown() операции падают с понятным исключением; повторный shutdown безопасен")
    public void testUseAfterShutdownFailsLoudly() throws Exception {
        final Path dir = Files.createTempDirectory("s4_closed_guard_db");
        try {
            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("s4-lifecycle", dir.toString(), 128);
            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(new BenchmarkEntity("s4-1", "payload"), tx);
            tx.commit();

            storage.shutdown();
            storage.shutdown(); // идемпотентность

            assertThrows(RuntimeException.class, () -> storage.beginTransaction(),
                    "begin after shutdown must fail loudly");
            assertThrows(RuntimeException.class, () -> storage.save(new BenchmarkEntity("s4-2", "x")),
                    "save after shutdown must fail loudly");
            assertThrows(RuntimeException.class, () -> storage.getTransactionManager(),
                    "transaction manager access after shutdown must fail loudly");
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("daemonWorkerThreads: воркеры пула — daemon (забытый shutdown() не вешает JVM)")
    public void testDaemonWorkerThreadsOption() throws Exception {
        final Path dir = Files.createTempDirectory("s4_daemon_db");
        try {
            final XdStorageConfig config = XdStorageConfig.builder().daemonWorkerThreads(true).build();
            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("s4-daemon", dir.toString(), 128, config);
            try {
                // форсируем создание воркера через executor сервис-локатора (рефлексия: класс пакето-приватный)
                final Object services = readField(storage, "services");
                final java.util.concurrent.ExecutorService executor =
                        (java.util.concurrent.ExecutorService) readField(services, "executor");
                executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);

                boolean workerSeen = false;
                for (final Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    if (e.getKey().getName().startsWith("xdstorage-worker-")) {
                        workerSeen = true;
                        assertTrue(e.getKey().isDaemon(), "worker threads must be daemon in daemon mode");
                    }
                }
                assertTrue(workerSeen, "at least one named worker thread must exist after submitting a task");
            } finally {
                storage.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("registerShutdownHook: хук регистрируется при открытии и снимается в shutdown()")
    public void testShutdownHookRegisteredAndRemoved() throws Exception {
        final Path dir = Files.createTempDirectory("s4_hook_db");
        try {
            final XdStorageConfig config = XdStorageConfig.builder().registerShutdownHook(true).build();
            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("s4-hook", dir.toString(), 128, config);
            final Object hook = readField(storage, "shutdownHook");
            assertNotNull(hook, "shutdown hook must be registered while the storage is open");

            storage.shutdown();
            assertNull(readField(storage, "shutdownHook"), "hook must be removed on explicit shutdown");
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("Конфиг по умолчанию воспроизводит прежние константы (таймаут 15000 — raised 2026-11-22, the cold-first-tx codegen; пул 15, SNAPSHOT)")
    public void testDefaultsMatchPreConfigBehavior() {
        final XdStorageConfig defaults = XdStorageConfig.defaults();
        assertEquals(15000L, defaults.getDefaultTransactionTimeoutMillis());
        assertEquals(15, defaults.getWorkerPoolSize());
        assertFalse(defaults.isDurableWrites());
        assertFalse(defaults.isAcceptMissingEofSentinel());
        assertFalse(defaults.isDaemonWorkerThreads());
        assertFalse(defaults.isRegisterShutdownHook());
    }

    private static Object readField(final Object target, final String name) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                final Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (final NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void deleteQuietly(final Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Throwable ignored) {
        }
    }
}
