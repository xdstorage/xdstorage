package io.github.xdstorage.helpers;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.XdStorageIndexedDurationEntity;
import io.github.xdstorage.entities.XdStorageIndexedInstantEntity;
import io.github.xdstorage.entities.XdStorageIndexedUuidEntity;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SIMPLETYPES (docs/tasks/2.2.0/41): a `UUID` or `java.time` field behind a search index
 * is queried by VALUE — the index key keeps the field's type — not by the field's text
 * form. The two orders part company exactly where a text comparison would give a wrong
 * answer: an `Instant` with a fraction of a second sorts before the same second without
 * one (`…01.250Z` &lt; `…01Z` as text), and a `Duration`'s ISO-8601 form does not sort by
 * length at all (`PT10M` &lt; `PT15M` &lt; `PT1M30S` as text). Every query is asked twice:
 * in the session that wrote the records, and after a reopen — the keys travel through
 * their text form on disk and are restored to the field's type at reopen (BUG-E).
 */
@DisplayName("SIMPLETYPES (2.2.0/41): индексированные UUID и java.time-поля ищутся по значению")
class XdStorageSimpleTypesIndexedQueryTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration QUARTER = Duration.ofMinutes(15L);

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("1. Диапазон по Instant идёт по времени, а не по тексту (доли секунды)")
    void instantRangeFollowsTimeNotText() throws Exception {
        final Path folder = tempDir.resolve("instant");
        IXdFileStorage storage = open(folder);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            storage.save(new XdStorageIndexedInstantEntity("i-0", T), tx);
            storage.save(new XdStorageIndexedInstantEntity("i-1", T.plusSeconds(1L)), tx);
            storage.save(new XdStorageIndexedInstantEntity("i-2", T.plusMillis(1250L)), tx);
            storage.save(new XdStorageIndexedInstantEntity("i-3", T.plusSeconds(2L)), tx);
            storage.commitTransaction(tx);
            assertInstantQueries(storage, "in the session that wrote");
        } finally {
            storage.shutdown();
        }
        storage = open(folder);
        try {
            assertInstantQueries(storage, "after the reopen");
        } finally {
            storage.shutdown();
        }
    }

    @Test
    @DisplayName("2. Диапазон по Duration идёт по длительности, а не по тексту")
    void durationRangeFollowsLengthNotText() throws Exception {
        final Path folder = tempDir.resolve("duration");
        IXdFileStorage storage = open(folder);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            storage.save(new XdStorageIndexedDurationEntity("d-0", Duration.ofHours(1L)), tx);
            storage.save(new XdStorageIndexedDurationEntity("d-1", Duration.ofMinutes(10L)), tx);
            storage.save(new XdStorageIndexedDurationEntity("d-2", Duration.ofSeconds(90L)), tx);
            storage.save(new XdStorageIndexedDurationEntity("d-3", Duration.ofHours(2L)), tx);
            storage.commitTransaction(tx);
            assertDurationQueries(storage, "in the session that wrote");
        } finally {
            storage.shutdown();
        }
        storage = open(folder);
        try {
            assertDurationQueries(storage, "after the reopen");
        } finally {
            storage.shutdown();
        }
    }

    @Test
    @DisplayName("3. EQUALS по индексированному UUID находит запись и после переоткрытия")
    void uuidEqualityAfterReopen() throws Exception {
        final Path folder = tempDir.resolve("uuid");
        final UUID[] codes = new UUID[5];
        IXdFileStorage storage = open(folder);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            for (int i = 0; i < codes.length; i++) {
                codes[i] = UUID.nameUUIDFromBytes(("code-" + i).getBytes("UTF-8"));
                storage.save(new XdStorageIndexedUuidEntity("u-" + i, codes[i]), tx);
            }
            storage.commitTransaction(tx);
        } finally {
            storage.shutdown();
        }
        storage = open(folder);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            assertEquals(Arrays.asList("u-4"), ids(storage, XdStorageIndexedUuidEntity.class, "by_code",
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, codes[4]), tx),
                    "EQUALS on the UUID index answers after the reopen");
            assertEquals(new ArrayList<String>(), ids(storage, XdStorageIndexedUuidEntity.class, "by_code",
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, UUID.randomUUID()), tx),
                    "an unknown UUID matches nothing");
            assertEquals(codes[3], storage.load(XdStorageIndexedUuidEntity.class, "u-3", tx).getCode(),
                    "the field itself survives");
            storage.rollbackTransaction(tx);
        } finally {
            storage.shutdown();
        }
    }

    private static void assertInstantQueries(final IXdFileStorage storage, final String when) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        // as text "…:01Z" sorts AFTER "…:01.250Z" and "…:01.100Z": the text order would answer [i-1, i-3]
        assertEquals(Arrays.asList("i-2", "i-3"), ids(storage, XdStorageIndexedInstantEntity.class, "by_at",
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, T.plusMillis(1100L)), tx),
                when + ": GTE T+1.1 s");
        assertEquals(Arrays.asList("i-0", "i-1"), ids(storage, XdStorageIndexedInstantEntity.class, "by_at",
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.LT, T.plusMillis(1100L)), tx),
                when + ": LT T+1.1 s");
        assertEquals(Arrays.asList("i-2"), ids(storage, XdStorageIndexedInstantEntity.class, "by_at",
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, T.plusMillis(1250L)), tx),
                when + ": EQUALS the fraction");
        assertEquals(Arrays.asList("i-1", "i-2", "i-3"), ids(storage, XdStorageIndexedInstantEntity.class, "by_at",
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new XdStorageRange(T.plusSeconds(1L), T.plusSeconds(2L))), tx), when + ": BETWEEN");
        storage.rollbackTransaction(tx);
    }

    private static void assertDurationQueries(final IXdFileStorage storage, final String when) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        // as text "PT1M30S" sorts AFTER "PT15M": the text order would answer [d-0, d-2, d-3]
        assertEquals(Arrays.asList("d-0", "d-3"), ids(storage, XdStorageIndexedDurationEntity.class,
                "by_duration", new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, QUARTER), tx),
                when + ": GTE 15 minutes");
        assertEquals(Arrays.asList("d-1", "d-2"), ids(storage, XdStorageIndexedDurationEntity.class,
                "by_duration", new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.LT, QUARTER), tx),
                when + ": LT 15 minutes");
        assertEquals(Arrays.asList("d-1"), ids(storage, XdStorageIndexedDurationEntity.class, "by_duration",
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, Duration.ofMinutes(10L)), tx),
                when + ": EQUALS 10 minutes");
        storage.rollbackTransaction(tx);
    }

    /** The sorted ids the index answers for the criterion — the walk order is not part of the contract. */
    private static List<String> ids(final IXdFileStorage storage, final Class<?> type, final String index,
                                    final XdStoragePrimaryCriterion criterion, final IXdStorageTransaction tx)
            throws Exception {
        final List<String> found = new ArrayList<>();
        for (final Object record : storage.load(type, index, new XdStorageSearchQuery(criterion, null), tx)) {
            found.add(String.valueOf(type.getMethod("getId").invoke(record)));
        }
        java.util.Collections.sort(found);
        return found;
    }

    private static IXdFileStorage open(final Path folder) throws Exception {
        java.nio.file.Files.createDirectories(folder);
        return XdStorageProvider.newOrGetFileStorage("simple-index-" + System.nanoTime(),
                folder.toAbsolutePath().toString(), 64);
    }
}
