package io.github.xdstorage.search;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStorageComplexCriterion;
import io.github.xdstorage.search.query.XdStorageCriterion;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageRange;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FP-1b (docs/tasks/2.1.0/10): composition suite of the result shaping — ORDER BY (primary,
 * ASC/DESC), LIMIT/OFFSET pagination and COUNT, composed with the FP-1a range operators on
 * both id-index types (Hash = RangeOpsEntity, B+Tree = RangeOpsBTreeIdEntity). Pins: the
 * ordered walk's order survives into the returned list; LIMIT stops the walk; DESC paginates
 * from the tail; scan-path ORDER BY is an explicit in-snapshot fallback sort; nulls are the
 * greatest value; negative pagination arguments fail loudly at query construction; a page read
 * inside a transaction is invariant under concurrent commits (snapshot semantics).
 */
@DisplayName("FP-1b: ORDER BY, LIMIT/OFFSET, COUNT on the search query")
public class XdStorageQueryShapingTest {

    @TempDir
    Path tempDir;

    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        final String unique = "query-shaping-" + System.nanoTime();
        storage = XdStorageProvider.newOrGetFileStorage(unique, tempDir.resolve("db").toString(), 512);
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            for (long i = 0; i < 50; i++) {
                final String label = i % 2 == 0 ? "even" : "odd";
                storage.save(new RangeOpsEntity("id-" + i, i, label), tx);
                storage.save(new RangeOpsBTreeIdEntity("id-" + i, i, label), tx);
            }
            // null-score entities: outside every range operator, visible to the scan path
            for (int i = 0; i < 3; i++) {
                storage.save(new RangeOpsEntity("idn-" + i, null, "nullish"), tx);
                storage.save(new RangeOpsBTreeIdEntity("idn-" + i, null, "nullish"), tx);
            }
            storage.commitTransaction(tx);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    private static List<String> ascendingIds(final long from, final long to) {
        final List<String> result = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            result.add("id-" + i);
        }
        return result;
    }

    private <T> List<String> orderedIds(final Class<T> cl, final String indexName, final XdStorageSearchQuery query)
            throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            final List<String> ids = storage.load(cl, indexName, query, tx)
                    .stream().map(e -> entity_id(e)).collect(Collectors.toList());
            storage.commitTransaction(tx);
            return ids;
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private String entity_id(final Object e) {
        if (e instanceof RangeOpsEntity) {
            return ((RangeOpsEntity) e).getId();
        }
        return ((RangeOpsBTreeIdEntity) e).getId();
    }

    private long countOf(final Class<?> cl, final String indexName, final XdStorageSearchQuery query) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            final long count = storage.count(cl, indexName, query, tx);
            storage.commitTransaction(tx);
            return count;
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private static XdStorageSearchQuery between(final long from, final long to) {
        return new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(from, to)), null);
    }

    @Test
    @DisplayName("ORDER BY ASC: range + ordering keeps the exact key order on both id-index types")
    public void orderByAscendingOnBothIdIndexTypes() throws Exception {
        final List<String> expected = ascendingIds(10, 20);
        assertEquals(expected, orderedIds(RangeOpsEntity.class, "range_idx", between(10, 20).orderByPrimary(false)),
                "Hash id index: the ordered walk order survives into the result list");
        assertEquals(expected, orderedIds(RangeOpsBTreeIdEntity.class, "range_bt_idx", between(10, 20)
                .orderByPrimary(false)),
                "B+Tree id index: the ordered walk order survives into the result list");
    }

    @Test
    @DisplayName("ORDER BY DESC reverses the key order on both id-index types")
    public void orderByDescendingOnBothIdIndexTypes() throws Exception {
        final List<String> expected = ascendingIds(10, 20);
        final List<String> reversed = new ArrayList<>(expected);
        java.util.Collections.reverse(reversed);
        assertEquals(reversed, orderedIds(RangeOpsEntity.class, "range_idx", between(10, 20).orderByPrimary(true)),
                "Hash id index: DESC is the exact mirror of ASC");
        assertEquals(reversed, orderedIds(RangeOpsBTreeIdEntity.class, "range_bt_idx", between(10, 20)
                .orderByPrimary(true)),
                "B+Tree id index: DESC is the exact mirror of ASC");
    }

    @Test
    @DisplayName("LIMIT stops the ordered walk: bounded and lower-bound-only ranges")
    public void limitTerminatesTheOrderedWalk() throws Exception {
        assertEquals(ascendingIds(0, 4), orderedIds(RangeOpsEntity.class, "range_idx",
                        between(0, 49).orderByPrimary(false).limit(5)),
                "LIMIT truncates a bounded range to the first page");
        assertEquals(ascendingIds(41, 43), orderedIds(RangeOpsBTreeIdEntity.class, "range_bt_idx",
                        new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GT, 40L),
                                null)
                                .orderByPrimary(false).limit(3)),
                "LIMIT pushes down to a lower-bound-only walk — early termination without an upper bound");
        assertEquals(ascendingIds(7, 7), orderedIds(RangeOpsEntity.class, "range_idx",
                        new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN, Arrays
                                .asList(7L, 15L, 42L)), null)
                                .orderByPrimary(false).limit(1)),
                "IN walk + LIMIT returns the first member in key order");
    }

    @Test
    @DisplayName("OFFSET/LIMIT pagination: page windows, tail pages, offset past the end, DESC pagination")
    public void offsetAndLimitPages() throws Exception {
        final String hashIdx = "range_idx";
        assertEquals(ascendingIds(10, 14), orderedIds(RangeOpsEntity.class, hashIdx, between(0, 49)
                .orderByPrimary(false).offset(10).limit(5)),
                "page window after the offset");
        assertEquals(ascendingIds(45, 49), orderedIds(RangeOpsEntity.class, hashIdx, between(0, 49)
                .orderByPrimary(false).offset(45)),
                "the tail page without a limit");
        assertTrue(orderedIds(RangeOpsEntity.class, hashIdx, between(0, 49).orderByPrimary(false).offset(50)).isEmpty(),
                "offset at the end yields an empty page");
        assertTrue(orderedIds(RangeOpsBTreeIdEntity.class, "range_bt_idx", between(0, 49).orderByPrimary(false)
                .offset(500)).isEmpty(),
                "offset far past the end yields an empty page");
        assertEquals(Arrays.asList("id-7", "id-6", "id-5"), orderedIds(RangeOpsBTreeIdEntity.class, "range_bt_idx",
                        between(0, 9).orderByPrimary(true).offset(2).limit(3)),
                "DESC pagination starts at the tail of the key order");
    }

    @Test
    @DisplayName("LIMIT 0 is a legal empty page; empty results stay empty under shaping")
    public void limitZeroAndEmptyResults() throws Exception {
        assertTrue(orderedIds(RangeOpsEntity.class, "range_idx", between(0, 49).orderByPrimary(false).limit(0))
                .isEmpty(),
                "LIMIT 0 is an empty page, not an error");
        assertTrue(orderedIds(RangeOpsBTreeIdEntity.class, "range_bt_idx", between(100, 200).orderByPrimary(false)
                .limit(5)).isEmpty(),
                "a range matching nothing stays empty under shaping");
        assertTrue(orderedIds(RangeOpsEntity.class, "range_idx", between(100, 200).orderByPrimary(true).offset(3)
                .limit(3)).isEmpty(),
                "empty result with full shaping");
    }

    @Test
    @DisplayName("negative LIMIT/OFFSET fail loudly at query construction")
    public void negativeArgumentsFailLoudly() {
        assertThrows(XdStorageRuntimeException.class, () -> between(0, 10).limit(-1),
                "negative LIMIT fails at construction, not at evaluation");
        assertThrows(XdStorageRuntimeException.class, () -> between(0, 10).offset(-5),
                "negative OFFSET fails at construction, not at evaluation");
    }

    @Test
    @DisplayName("composition: child criterion filters first, shaping shapes the survivors")
    public void childCriterionFilterThenShape() throws Exception {
        final XdStorageSearchQuery evens = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 40L),
                new XdStorageCriterion("label", IXdStorageCriterionOperator.EQUALS, "even"))
                .orderByPrimary(false).limit(2);
        assertEquals(Arrays.asList("id-40", "id-42"), orderedIds(RangeOpsEntity.class, "range_idx", evens),
                "Hash id index: filter-then-shape");
        assertEquals(Arrays.asList("id-40", "id-42"), orderedIds(RangeOpsBTreeIdEntity.class, "range_bt_idx",
                        new XdStorageSearchQuery(
                                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 40L),
                                new XdStorageCriterion("label", IXdStorageCriterionOperator.EQUALS, "even"))
                                .orderByPrimary(false).limit(2)),
                "B+Tree id index: filter-then-shape");
    }

    @Test
    @DisplayName("union of two ranges: ORDER BY sorts the union, LIMIT pages the sorted order")
    public void unionOfTwoRangesOrderedAndPaged() throws Exception {
        final XdStorageSearchQuery union = between(0, 9).orderByPrimary(false);
        union.or(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(40L, 49L)),
                null);

        final List<String> expected = ascendingIds(0, 9);
        expected.addAll(ascendingIds(40, 49));
        assertEquals(expected, orderedIds(RangeOpsEntity.class, "range_idx", union),
                "the union of two walks comes out in key order");

        final XdStorageSearchQuery paged = between(0, 9).orderByPrimary(false).limit(3);
        paged.or(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(40L, 49L)),
                null);
        assertEquals(Arrays.asList("id-0", "id-1", "id-2"), orderedIds(RangeOpsEntity.class, "range_idx", paged),
                "LIMIT pages the sorted union");

        final XdStorageSearchQuery descTail = between(0, 9).orderByPrimary(true).limit(2);
        descTail.or(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(40L, 49L)),
                null);
        assertEquals(Arrays.asList("id-49", "id-48"), orderedIds(RangeOpsEntity.class, "range_idx", descTail),
                "DESC pages the sorted union from the tail");
    }

    @Test
    @DisplayName("scan path: ORDER BY is an explicit in-snapshot fallback sort; nulls are the greatest value")
    public void scanPathFallbackOrdering() throws Exception {
        final XdStorageSearchQuery scanAsc = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "__never_matches__"), null)
                .orderByPrimary(false);
        final List<String> all = orderedIds(RangeOpsEntity.class, "range_idx", scanAsc);
        assertEquals(53, all.size(), "the scan matches every record including the null-score ones");
        assertEquals(ascendingIds(0, 49), all.subList(0, 50),
                "fallback sort orders the non-null prefix by key");
        assertEquals(Arrays.asList("idn-0", "idn-1", "idn-2"), all.subList(50, 53),
                "nulls are the greatest value: last in ASC");

        final List<String> allDesc = orderedIds(RangeOpsEntity.class, "range_idx", scanAsc.orderByPrimary(true));
        assertEquals(Arrays.asList("idn-0", "idn-1", "idn-2"), allDesc.subList(0, 3),
                "nulls are the greatest value: first in DESC");

        final XdStorageSearchQuery scanPaged = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "__never_matches__"),
                new XdStorageComplexCriterion(
                        new XdStorageCriterion("score", IXdStorageCriterionOperator.GTE, 10L))
                        .and(new XdStorageCriterion("score", IXdStorageCriterionOperator.LTE, 20L)))
                .orderByPrimary(false).offset(5).limit(3);
        assertEquals(ascendingIds(15, 17), orderedIds(RangeOpsEntity.class, "range_idx", scanPaged),
                "the scan fallback paginates the sorted survivors");
    }

    @Test
    @DisplayName("snapshot semantics: concurrent inserts stay invisible; a live-index deletion is reflected")
    public void orderedPageIsSnapshotStable() throws Exception {
        final List<String> expected = ascendingIds(10, 20);
        final IXdStorageTransaction readTx = storage.beginTransaction(60000L);
        try {
            assertEquals(expected, storage.load(RangeOpsEntity.class, "range_idx", between(10, 20)
                    .orderByPrimary(false), readTx)
                    .stream().map(RangeOpsEntity::getId).collect(Collectors.toList()));

            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final Future<?> writer = executor.submit(() -> {
                final IXdStorageTransaction wtx = storage.beginTransaction(60000L);
                try {
                    // an insert inside the page window: stays invisible in the open tx (the
                    // data read is snapshot-filtered — the FP-1a semantic, pinned here for pages)
                    storage.save(new RangeOpsEntity("id-late", 16L, "late"), wtx);
                    storage.commitTransaction(wtx);

                    final IXdStorageTransaction dtw = storage.beginTransaction(60000L);
                    try {
                        // a deletion inside the window: the index tree is live, so the record
                        // leaves the walk of the open tx too (the documented live-index exposure)
                        storage.delete(new RangeOpsEntity("id-15", 15L, "odd"), dtw);
                        storage.commitTransaction(dtw);
                    } catch (final Exception e) {
                        storage.rollbackTransaction(dtw);
                        throw e;
                    }
                } catch (final Exception e) {
                    storage.rollbackTransaction(wtx);
                    throw new RuntimeException(e);
                }
                return null;
            });
            writer.get(30, TimeUnit.SECONDS);

            final List<String> withDeletion = new ArrayList<>(expected);
            withDeletion.remove("id-15");
            assertEquals(withDeletion, storage.load(RangeOpsEntity.class, "range_idx", between(10, 20)
                    .orderByPrimary(false), readTx)
                            .stream().map(RangeOpsEntity::getId).collect(Collectors.toList()),
                    "inserts stay invisible in the open tx; the deletion is reflected through the live index — " +
                            "deterministically");
            storage.commitTransaction(readTx);

            final List<String> after = orderedIds(RangeOpsEntity.class, "range_idx", between(10, 20)
                    .orderByPrimary(false));
            final Set<String> afterSet = new HashSet<>(after);
            final Set<String> expectedAfter = new HashSet<>(ascendingIds(10, 20));
            expectedAfter.remove("id-15");
            expectedAfter.add("id-late");
            assertEquals(expectedAfter, afterSet,
                    "after the read tx ends, the deletion and the insert are both visible");
            assertEquals(expectedAfter.size(), after.size(), "no duplicates in the page");
            assertEquals(11L, countOf(RangeOpsEntity.class, "range_idx", between(10, 20)),
                    "the count reflects the new snapshot: id-15 gone, id-late (score 16) in");
            executor.shutdownNow();
        } catch (final Exception e) {
            storage.rollbackTransaction(readTx);
            throw e;
        }
    }

    @Test
    @DisplayName("COUNT: record-level, snapshot-visible matches, ignores LIMIT/OFFSET/ORDER BY")
    public void countSemantics() throws Exception {
        assertEquals(1L, countOf(RangeOpsEntity.class, "range_idx",
                new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, 7L), null)),
                "EQUALS count on the primary score key (the index primary is the first indexed field)");
        assertEquals(11L, countOf(RangeOpsEntity.class, "range_idx", between(10, 20)), "BETWEEN count");
        assertEquals(50L, countOf(RangeOpsEntity.class, "range_idx",
                new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GT, -1L), null)),
                "a range operator never counts null values");
        assertEquals(3L, countOf(RangeOpsEntity.class, "range_idx",
                new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IS_NULL, null),
                        null)),
                "IS_NULL counts the null-score records");
        assertTrue(countOf(RangeOpsBTreeIdEntity.class, "range_bt_idx", between(500, 1000)) == 0L,
                "an empty match set counts to zero");
        assertEquals(11L, countOf(RangeOpsEntity.class, "range_idx", between(10, 20).orderByPrimary(false).limit(3)
                .offset(2)),
                "COUNT ignores the shaping: the page would show 3, the count is 11");
        assertEquals(11L, countOf(RangeOpsBTreeIdEntity.class, "range_bt_idx", between(10, 20)),
                "same count on the B+Tree id index");
    }

    @Test
    @DisplayName("COUNT equivalence: count(q) equals the size of the equivalent load(q) result")
    public void countMatchesLoadSize() throws Exception {
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 25L),
                new XdStorageCriterion("label", IXdStorageCriterionOperator.EQUALS, "odd"));
        assertEquals(orderedIds(RangeOpsEntity.class, "range_idx", query).size(),
                countOf(RangeOpsEntity.class, "range_idx", query),
                "count and load agree on the snapshot-visible match set");
    }
}
