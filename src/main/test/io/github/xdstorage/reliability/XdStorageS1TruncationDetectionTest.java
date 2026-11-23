package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-1: обрезанный YAML-ресурс НЕ должен загружаться молча как частичная база.
 * Движок пишет сентинел конца файла; строгий ридер (умолчание) отказывает в открытии,
 * легаси-режим ({@code acceptMissingEofSentinel}) читает как раньше для миграции.
 */
@DisplayName("S-1: детекция обрезанного ресурса (sentinel конца файла)")
public class XdStorageS1TruncationDetectionTest {

    private static final String NAME = "s1-truncation-db";

    @Test
    @DisplayName("Файл ресурса заканчивается сентинелом; обрезанный файл строго отвергается при чтении")
    public void testTruncatedResourceFailsLoudlyInStrictMode() throws Exception {
        final Path dir = Files.createTempDirectory("s1_truncation_db");
        try {
            saveThreeEntities(dir.toString());

            final File dataFile = findClassObjectsFile(dir);
            assertNotNull(dataFile, "class-objects data file must exist after commit");
            final String content = new String(Files.readAllBytes(dataFile.toPath()), "UTF-8");
            assertTrue(content.contains("# xdstorage-eof"), "writer must append the EOF sentinel, got: " +
                    tail(content));

            // эмулируем обрезку (сбой/внешнее вмешательство): срезаем хвост ПО ГРАНИЦЕ ЗАПИСЕЙ —
            // остаются 2 целых записи валидного YAML, но сентинела нет
            final int thirdRecord = content.indexOf("- object", content.indexOf("- object", content.indexOf("- object")
                    + 1) + 1);
            assertTrue(thirdRecord > 0, "expected at least three object records in the data file");
            Files.write(dataFile.toPath(), content.substring(0, thirdRecord).getBytes("UTF-8"));

            // строгое умолчание: загрузка должна ОТКАЗАТЬ с диагностикой обрыва,
            // а не отдать частичную базу (журнал выключен — тест пинит семантику БАЗОВЫХ файлов)
            final XdStorageConfig baseOnly = XdStorageConfig.builder().journalWrites(false).build();
            IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, baseOnly);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                final Exception thrown = assertThrows(Exception.class, () -> storage.load(BenchmarkEntity.class, tx),
                        "truncated resource must not load silently as a partial database");
                assertTrue(toStringWithCauses(thrown).contains("TRUNCATED"),
                        "failure must name the truncation, got: " + toStringWithCauses(thrown));
            } finally {
                rollbackQuietly(storage);
                storage.shutdown();
            }

            // легаси-режим миграции: читает частичные данные (2 из 3 записей) без ошибки сентинела
            final XdStorageConfig legacy = XdStorageConfig.builder().journalWrites(false).acceptMissingEofSentinel(true)
                    .build();
            IXdFileStorage legacyStorage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128, legacy);
            try {
                final IXdStorageTransaction tx = legacyStorage.beginTransaction();
                final java.util.Collection<BenchmarkEntity> partial = legacyStorage.load(BenchmarkEntity.class, tx);
                assertEquals(2, partial.size(), "legacy mode must load the surviving whole records");
                tx.commit();
            } finally {
                rollbackQuietly(legacyStorage);
                legacyStorage.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("Целый файл со сентинелом читается полностью; повторный round-trip после strict-отказа работает")
    public void testCompleteFileRoundTrip() throws Exception {
        final Path dir = Files.createTempDirectory("s1_roundtrip_db");
        try {
            saveThreeEntities(dir.toString());

            IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, dir.toString(), 128,
                    XdStorageConfig.builder().journalWrites(false).build());
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                final java.util.List<BenchmarkEntity> loaded = new java.util.ArrayList<>(storage.load(BenchmarkEntity
                        .class, tx));
                assertEquals(3, loaded.size(), "complete file must load fully");
                tx.commit();
            } finally {
                storage.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    private void saveThreeEntities(final String folder) throws Exception {
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(NAME, folder, 128,
                XdStorageConfig.builder().journalWrites(false).build());
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(new BenchmarkEntity("id-1", "alpha"), tx);
            storage.save(new BenchmarkEntity("id-2", "beta"), tx);
            storage.save(new BenchmarkEntity("id-3", "gamma"), tx);
            tx.commit();
        } finally {
            storage.shutdown();
        }
    }

    /** data/BenchmarkEntity/BenchmarkEntity[-frag].yaml — классовые объекты, НЕ index.yaml. */
    private File findClassObjectsFile(final Path dir) {
        final File dataDir = new File(dir.toFile(), "data/BenchmarkEntity");
        final File[] files = dataDir.listFiles((d, n) -> n.startsWith("BenchmarkEntity") && n.endsWith(".yaml"));
        if (files == null || files.length == 0)
            return null;
        return java.util.Arrays.stream(files).max(Comparator.comparingLong(File::length)).orElse(null);
    }

    private String toStringWithCauses(final Throwable t) {
        final StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private String tail(final String s) {
        return s.length() <= 120 ? s : s.substring(s.length() - 120);
    }

    private void rollbackQuietly(final IXdFileStorage storage) {
        try {
            final IXdStorageTransaction current = storage.getTransactionManager().getCurrentTransaction();
            if (current != null)
                current.rollback();
        } catch (final Throwable ignored) {
        }
    }

    private void deleteQuietly(final Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Throwable ignored) {
        }
    }
}
