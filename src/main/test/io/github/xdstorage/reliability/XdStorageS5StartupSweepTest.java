package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-5 + S-2: зачистка .tmp-мусора прошлого сбоя при открытии; durable-режим
 * (fsync до переименования + fsync каталога) проходит полный round-trip.
 */
@DisplayName("S-5/S-2: стартовая зачистка .tmp и durable round-trip")
public class XdStorageS5StartupSweepTest {

    @Test
    @DisplayName("Забытые .tmp-файлы прошлого сбоя удаляются при открытии хранилища")
    public void testStaleTempFilesSweptAtOpen() throws Exception {
        final Path dir = Files.createTempDirectory("s5_sweep_db");
        try {
            // готовим «хранилище после сбоя»: данные + забытые .tmp в двух уровнях
            final Path dataDir = Files.createDirectories(dir.resolve("data/BenchmarkEntity"));
            final Path structureDir = Files.createDirectories(dir.resolve("structure"));
            final Path staleDataTmp = dataDir.resolve("BenchmarkEntity.yaml.tmp");
            final Path staleStructureTmp = structureDir.resolve("BenchmarkEntity.yaml.tmp");
            Files.write(staleDataTmp, "garbage".getBytes());
            Files.write(staleStructureTmp, "garbage".getBytes());

            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("s5-sweep", dir.toString(), 128);
            storage.shutdown();

            assertFalse(Files.exists(staleDataTmp), "stale .tmp in data dir must be swept at open");
            assertFalse(Files.exists(staleStructureTmp), "stale .tmp in structure dir must be swept at open");
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("durableWrites=true: данные проходят полный round-trip (fsync-путь записи)")
    public void testDurableWritesRoundTrip() throws Exception {
        final Path dir = Files.createTempDirectory("s5_durable_db");
        try {
            final XdStorageConfig durable = XdStorageConfig.builder().durableWrites(true).build();
            final IXdFileStorage writer = XdStorageProvider.newOrGetFileStorage("s5-durable", dir.toString(), 128,
                    durable);
            try {
                final IXdStorageTransaction tx = writer.beginTransaction();
                writer.save(new BenchmarkEntity("d-1", "alpha"), tx);
                writer.save(new BenchmarkEntity("d-2", "beta"), tx);
                tx.commit();
            } finally {
                writer.shutdown();
            }

            final IXdFileStorage reader = XdStorageProvider.newOrGetFileStorage("s5-durable", dir.toString(), 128,
                    durable);
            try {
                final IXdStorageTransaction tx = reader.beginTransaction();
                final java.util.Collection<BenchmarkEntity> loaded = reader.load(BenchmarkEntity.class, tx);
                assertEquals(2, loaded.size(), "durable write-out must round-trip");
                tx.commit();
            } finally {
                reader.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    private void deleteQuietly(final Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Throwable ignored) {
        }
    }
}
