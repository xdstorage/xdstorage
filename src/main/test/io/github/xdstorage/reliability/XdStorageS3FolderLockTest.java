package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-3: каталог хранилища открывается эксклюзивно — второй процесс или второй
 * инстанс JVM на тот же каталог получают громкий отказ, а не тихую порчу данных
 * (последняя запись побеждает). После shutdown() каталог освобождается.
 */
@DisplayName("S-3: эксклюзивная блокировка каталога хранилища")
public class XdStorageS3FolderLockTest {

    @Test
    @DisplayName("В одной JVM два инстанса с разными именами на один каталог несовместимы")
    public void testSameFolderSecondInstanceFailsInThisJvm() throws Exception {
        final Path dir = Files.createTempDirectory("s3_same_jvm_db");
        try {
            final IXdFileStorage first = XdStorageProvider.newOrGetFileStorage("s3-lock-a", dir.toString(), 128);
            try {
                final RuntimeException second = assertThrows(RuntimeException.class,
                        () -> XdStorageProvider.newOrGetFileStorage("s3-lock-b", dir.toString(), 128),
                        "second instance on the same folder must fail loudly, not corrupt data");
                assertTrue(second.getMessage().contains("already"), "message must explain the conflict: "
                        + second.getMessage());
            } finally {
                first.shutdown();
            }

            // после shutdown каталог свободен
            final IXdFileStorage secondAfterRelease = XdStorageProvider.newOrGetFileStorage("s3-lock-b", dir.toString(),
                    128);
            secondAfterRelease.shutdown();
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("Чужой процесс держит lock-файл — открытие отклоняется; после выхода процесса открывается")
    public void testCrossProcessLockIsRespected() throws Exception {
        final Path dir = Files.createTempDirectory("s3_cross_proc_db");
        try {
            final Path lockFile = dir.resolve(".xdstorage.lock");
            final Path readyMarker = dir.resolve("child-ready.marker");

            final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            final String classpath = new File(FolderLockHolderMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getPath();

            final Process child = new ProcessBuilder(javaBin, "-cp", classpath,
                    FolderLockHolderMain.class.getName(),
                    lockFile.toAbsolutePath().toString(), "8000", readyMarker.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            // ждем, пока дочерний процесс фактически захватит OS-блокировку
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(readyMarker)) {
                if (System.nanoTime() > deadline)
                    fail("child lock-holder process never became ready");
                Thread.sleep(50);
            }

            try {
                final RuntimeException rejected = assertThrows(RuntimeException.class,
                        () -> XdStorageProvider.newOrGetFileStorage("s3-lock-proc", dir.toString(), 128),
                        "open must fail while another process holds the folder");
                assertTrue(rejected.getMessage().contains("another process"),
                        "message must name the external holder: " + rejected.getMessage());
            } finally {
                child.waitFor(20, TimeUnit.SECONDS);
                child.destroyForcibly();
            }

            // дочерний процесс завершился — каталог свободен, открытие и shutdown работают
            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("s3-lock-proc", dir.toString(), 128);
            storage.shutdown();
        } finally {
            deleteQuietly(dir);
        }
    }

    private void deleteQuietly(final Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Throwable ignored) {
        }
    }
}
