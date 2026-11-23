package io.github.xdstorage.search;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStorageComplexCriterion;
import io.github.xdstorage.search.query.XdStorageCriterion;
import io.github.xdstorage.search.query.XdStorageCriterionNot;
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
import java.util.Arrays;
import java.util.Collections;
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
 * FP-1a (docs/tasks/2.1.0/09): boundary-semantics suite of the range operators
 * GT/GTE/LT/LTE/BETWEEN/IN — empty and degenerate ranges, open/closed bound combinations,
 * IN with duplicates/empty list, null handling for every operator, mixed-type loud failure,
 * composition with AND/OR/NOT and child criteria, snapshot-isolation interplay, and
 * scan-path equivalence (a range result is indistinguishable from the same filter evaluated
 * on the scan path).
 */
@DisplayName("FP-1a: range operators on the ordered search index")
public class XdStorageRangeSearchQueryTest {

    @TempDir
    Path tempDir;

    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        final String unique = "range-ops-" + System.nanoTime();
        storage = XdStorageProvider.newOrGetFileStorage(unique, tempDir.resolve("db").toString(), 512);
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            for (long i = 0; i < 50; i++) {
                storage.save(new RangeOpsEntity("id-" + i, i, i % 2 == 0 ? "even" : "odd"), tx);
            }
            // null-score entities: never satisfy a range operator, IS_NULL finds them
            for (int i = 0; i < 3; i++) {
                storage.save(new RangeOpsEntity("idn-" + i, null, "nullish"), tx);
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

    private Set<String> ids(final long scoreFromInclusive, final long scoreToInclusive) {
        final Set<String> result = new HashSet<>();
        for (long i = scoreFromInclusive; i <= scoreToInclusive; i++) {
            result.add("id-" + i);
        }
        return result;
    }

    private Set<String> searchIds(final XdStorageSearchQuery query) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            final java.util.Collection<RangeOpsEntity> found = storage.load(RangeOpsEntity.class, "range_idx", query,
                    tx);
            storage.commitTransaction(tx);
            return found.stream().map(RangeOpsEntity::getId).collect(Collectors.toSet());
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Test
    @DisplayName("GT/GTE/LT/LTE: open and closed bound combinations")
    public void openAndClosedBoundCombinations() throws Exception {
        assertEquals(ids(46, 49), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GT, 45L), null)), "GT excludes the bound");
        assertEquals(ids(45, 49), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 45L), null)), "GTE includes the bound");
        assertEquals(ids(0, 4), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.LT, 5L), null)), "LT excludes the bound");
        assertEquals(ids(0, 5), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.LTE, 5L), null)), "LTE includes the bound");
    }

    @Test
    @DisplayName("BETWEEN: inclusive bounds, single point, degenerate lo>hi is empty, empty results are legal")
    public void betweenSemantics() throws Exception {
        assertEquals(ids(10, 20), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(10L, 20L)),
                        null)),
                "both bounds inclusive (pinned contract)");
        assertEquals(ids(25, 25), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(25L, 25L)),
                        null)),
                "degenerate equal bounds match exactly one value");
        assertTrue(searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(40L, 10L)), null))
                        .isEmpty(),
                "lo > hi matches nothing");
        assertEquals(ids(0, 49), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(-5L, 100L)),
                        null)),
                "a range wider than the dataset returns everything with a non-null value");
    }

    @Test
    @DisplayName("IN: duplicates deduplicate, empty list matches nothing, array form works")
    public void inSemantics() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("id-10", "id-20")), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN, Arrays.asList(10L, 20L, 20L, 10L)),
                        null)),
                "duplicated IN values yield one copy per object");
        assertTrue(searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN, Collections.emptyList()), null))
                        .isEmpty(),
                "empty IN matches nothing");
        assertEquals(ids(7, 7), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN, new Long[]{7L}), null)),
                "array form of IN");
    }

    @Test
    @DisplayName("nulls never satisfy any range operator; IS_NULL remains the null test")
    public void nullsNeverSatisfyRangeOperators() throws Exception {
        final Set<String> nullIds = new HashSet<>(Arrays.asList("idn-0", "idn-1", "idn-2"));
        final XdStoragePrimaryCriterion[] rangePrimaries = {
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GT, -1L),
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, -1L),
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.LT, 1000L),
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.LTE, 1000L),
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(-1L, 1000L)),
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN, Arrays.asList(1L, 2L, 3L)),
        };
        for (final XdStoragePrimaryCriterion primary : rangePrimaries) {
            final Set<String> result = searchIds(new XdStorageSearchQuery(primary, null));
            assertTrue(Collections.disjoint(result, nullIds),
                    primary.getOperator() + " must never match null values, got " + result);
        }
        assertEquals(nullIds, searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IS_NULL, null), null)),
                "IS_NULL is the null test");
    }

    @Test
    @DisplayName("mixed-type order comparison fails loudly; IN membership simply never matches")
    public void mixedTypeFailsLoudly() {
        assertThrows(XdStorageRuntimeException.class, () -> searchIdsUnchecked(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange("10", "20")),
                        null)),
                "String bounds against a Long field must fail loudly, not silently-empty");
        assertThrows(XdStorageRuntimeException.class, () -> searchIdsUnchecked(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GT, "abc"), null)),
                "a String GT bound against a Long field must fail loudly");
        assertTrue(searchIdsUnchecked(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN, Arrays.asList("10", "20")), null))
                        .isEmpty(),
                "IN is equals-membership: a foreign type simply never matches");
    }

    @Test
    @DisplayName("composition: child criteria, NOT, AND/OR complex, or() of two ranges")
    public void compositionWithChildAndCompositeCriteria() throws Exception {
        // range + child criterion on another field
        assertEquals(new HashSet<>(Arrays.asList("id-40", "id-42", "id-44", "id-46", "id-48")), searchIds(
                new XdStorageSearchQuery(
                        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 40L),
                        new XdStorageCriterion("label", IXdStorageCriterionOperator.EQUALS, "even"))));
        // range + NOT child (odd scores >= 40)
        assertEquals(new HashSet<>(Arrays.asList("id-41", "id-43", "id-45", "id-47", "id-49")), searchIds(new
                XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 40L),
                new XdStorageCriterionNot(new XdStorageCriterion("label", IXdStorageCriterionOperator.EQUALS,
                        "even")))));
        // range + complex AND/OR child on the same field
        assertEquals(ids(42, 46), searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 40L),
                new XdStorageComplexCriterion(
                        new XdStorageCriterion("score", IXdStorageCriterionOperator.GTE, 42L))
                        .and(new XdStorageCriterion("score", IXdStorageCriterionOperator.LTE, 46L))
                        .or(new XdStorageCriterion("score", IXdStorageCriterionOperator.EQUALS, 43L)))));
        // union of two ranges via or()
        final XdStorageSearchQuery union = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(0L, 9L)), null);
        union.or(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(40L, 49L)),
                null);
        Set<String> expected = ids(0, 9);
        expected.addAll(ids(40, 49));
        assertEquals(expected, searchIds(union));
    }

    @Test
    @DisplayName("snapshot isolation: a range query inside a tx does not see concurrent commits")
    public void rangeQueryInsideTxSeesSnapshot() throws Exception {
        final IXdStorageTransaction readTx = storage.beginTransaction(60000L);
        try {
            assertEquals(ids(45, 49), searchIdsInTx(readTx, new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 45L), null)));

            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final Future<?> writer = executor.submit(() -> {
                final IXdStorageTransaction wtx = storage.beginTransaction(60000L);
                try {
                    storage.save(new RangeOpsEntity("id-late", 500L, "late"), wtx);
                    storage.commitTransaction(wtx);
                } catch (final Exception e) {
                    storage.rollbackTransaction(wtx);
                    throw new RuntimeException(e);
                }
                return null;
            });
            writer.get(30, TimeUnit.SECONDS);

            assertEquals(ids(45, 49), searchIdsInTx(readTx, new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 45L), null)),
                    "the open tx keeps its snapshot: the concurrently committed object is invisible");
            storage.commitTransaction(readTx);

            final Set<String> after = searchIds(new XdStorageSearchQuery(
                    new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.GTE, 45L), null));
            final Set<String> expectedAfter = ids(45, 49);
            expectedAfter.add("id-late");
            assertEquals(expectedAfter, after, "after the read tx ends, the new object is visible");
            executor.shutdownNow();
        } catch (final Exception e) {
            storage.rollbackTransaction(readTx);
            throw e;
        }
    }

    @Test
    @DisplayName("scan-path equivalence: the same filter via walk path and via the scan path agree")
    public void scanPathEquivalence() throws Exception {
        // fast path: BETWEEN primary
        final Set<String> viaWalk = searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN, new XdStorageRange(10L, 30L)),
                        null));
        // scan path: NOT_EQUALS primary forces the full scan; the SAME filter is expressed
        // as child criteria GTE/LTE evaluated per record — also exercises the operators
        // as child criteria on the scan path
        final Set<String> viaScan = searchIds(new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "__never_matches__"),
                new XdStorageComplexCriterion(
                        new XdStorageCriterion("score", IXdStorageCriterionOperator.GTE, 10L))
                        .and(new XdStorageCriterion("score", IXdStorageCriterionOperator.LTE, 30L))));
        assertEquals(viaScan, viaWalk,
                "a range result must be indistinguishable from the same filter on the scan path");
        assertEquals(ids(10, 30), viaWalk);
    }

    /** For assertThrows lambdas (Executable forbids checked exceptions); the runtime type
     *  of a loud range failure must survive. */
    private Set<String> searchIdsUnchecked(final XdStorageSearchQuery query) {
        try {
            return searchIds(query);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> searchIdsInTx(final IXdStorageTransaction tx, final XdStorageSearchQuery query) throws Exception
            {
        return storage.load(RangeOpsEntity.class, "range_idx", query, tx)
                .stream().map(RangeOpsEntity::getId).collect(Collectors.toSet());
    }
}
