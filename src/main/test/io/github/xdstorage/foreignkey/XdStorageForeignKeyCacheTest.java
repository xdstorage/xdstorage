package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FNKEY (docs/analysis/FOREIGN_KEYS.md §4/§6):
 * the pinned unit suite for the FK pair write-set. The lifecycle tests that need real
 * storage (insert-then-commit reaches the trees; rollback leaves them clean; the
 * deferred-ID waiter fires on id landing) live in the real-storage estate
 * (ForeignKeySave/Delete/Update/Reference scenario suites + XdStorageForeignKeyAutoIdInsertionTest),
 * which exercises exactly these paths through the facade.
 */
@DisplayName("FK write-set: intents, later-wins, phases, isolation, validation, release")
class XdStorageForeignKeyCacheTest {

    private XdStorageForeignKeyCache cache;
    private XdStorageTransaction tx;
    private XdStorageTransaction otherTx;
    private XdStorageForeignKeyPair pair;

    @BeforeEach
    void setUp() {
        cache = new XdStorageForeignKeyCache();
        tx = mock(XdStorageTransaction.class);
        otherTx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn("tx-1");
        when(otherTx.getTransactionId()).thenReturn("tx-2");

        final XdStorageClassInfo parentInfo = mock(XdStorageClassInfo.class);
        final XdStorageClassInfo childInfo = mock(XdStorageClassInfo.class);
        when(parentInfo.getClazz()).thenReturn((Class) ParentProbe.class);
        when(childInfo.getClazz()).thenReturn((Class) ChildProbe.class);
        final XdStorageObjectField field = mock(XdStorageObjectField.class);
        when(field.getName()).thenReturn("children");

        pair = new XdStorageForeignKeyPair(10L, 20L, parentInfo, childInfo, field,
                XdStorageForeignKeyObjectChangeType.INSERT);
    }

    /** Probe entity family (the pair derives its tree ids from the class names). */
    private static class ParentProbe { }
    private static class ChildProbe { }

    private XdStorageForeignKeyPair deleteIntent() {
        return new XdStorageForeignKeyPair(10L, 20L, pair.parentClassInfo, pair.childClassInfo,
                pair.parentField, XdStorageForeignKeyObjectChangeType.DELETE);
    }

    @Test
    @DisplayName("POINT 1: a duplicate insert intent is idempotent (one staged record)")
    void testCacheInsertDuplicate() {
        cache.cacheInsert(pair, tx);
        cache.cacheInsert(pair, tx);
        assertEquals(1, cache.pairsForTransaction(tx).size());
    }

    @Test
    @DisplayName("POINT 5: an insert then a delete of the same pair — the pair never lands")
    void testCacheInsertThenDeleteNeverLands() {
        cache.cacheInsert(pair, tx);
        cache.cacheDelete(deleteIntent(), tx);
        assertTrue(cache.pairsForTransaction(tx).isEmpty(), "the insert+delete pair collapses to nothing");
    }

    @Test
    @DisplayName("later-wins: a delete then an insert of the same pair restores the pair")
    void testCacheDeleteThenInsertRestores() {
        cache.cacheDelete(deleteIntent(), tx);
        cache.cacheInsert(pair, tx);
        assertEquals(1, cache.pairsForTransaction(tx).size());
        assertEquals(XdStorageForeignKeyObjectChangeType.INSERT, cache.pairsForTransaction(tx).get(0).type());
    }

    @Test
    @DisplayName("POINT 2: validateAllForTransaction throws on a deferred (null-id) pair at commit")
    void testCacheValidateThrows() {
        final XdStorageForeignKeyPair deferred = new XdStorageForeignKeyPair(null, 20L,
                pair.parentClassInfo, pair.childClassInfo, pair.parentField,
                XdStorageForeignKeyObjectChangeType.INSERT);
        cache.cacheInsert(deferred, tx);
        assertThrows(XdStorageRuntimeException.class,
                () -> cache.validateAllForTransaction(tx),
                "a pair with unresolved ids must not reach commit silently");
    }

    @Test
    @DisplayName("POINT 2: validateAllForTransaction passes for resolved pairs")
    void testCacheValidatePassesResolved() {
        cache.cacheInsert(pair, tx);
        cache.cacheDelete(deleteIntent(), tx);
        cache.cacheInsert(pair, tx);
        cache.validateAllForTransaction(tx);
    }

    @Test
    @DisplayName("POINT 4: promoteAllForTransaction promotes every staged pair to PERFORM")
    void testCachePromoteAll() {
        cache.cacheInsert(pair, tx);
        cache.cacheDelete(deleteIntent(), tx);
        cache.promoteAllForTransaction(tx);
        for (final XdStorageForeignKeyPair staged : cache.pairsForTransaction(tx)) {
            assertEquals(XdStorageCacheCommitPhase.PERFORM_COMMIT, cache.phaseFor(tx, staged),
                    "every staged intent reaches PERFORM at phase 2");
        }
    }

    @Test
    @DisplayName("POINT 6: commitAllFxPairs promotes then clears the write-set (idempotent)")
    void testCommitAllFxPairsInOrderAndIdempotent() {
        cache.cacheInsert(pair, tx);
        cache.commitAllFxPairs(tx);
        assertTrue(cache.pairsForTransaction(tx).isEmpty(), "the write-set is closed at commit");
        cache.commitAllFxPairs(tx); // the second call is a no-op
        assertTrue(cache.pairsForTransaction(tx).isEmpty());
    }

    @Test
    @DisplayName("POINT 8: release clears only the releasing transaction's records")
    void testCacheReleaseIsPerTransaction() {
        cache.cacheInsert(pair, tx);
        final XdStorageForeignKeyPair otherPair = new XdStorageForeignKeyPair(30L, 40L,
                pair.parentClassInfo, pair.childClassInfo, pair.parentField,
                XdStorageForeignKeyObjectChangeType.INSERT);
        cache.cacheInsert(otherPair, otherTx);

        cache.release(tx);
        assertTrue(cache.pairsForTransaction(tx).isEmpty(), "the releasing tx's pairs are gone");
        assertFalse(cache.pairsForTransaction(otherTx).isEmpty(), "a foreign tx's pairs survive");
        final List<XdStorageForeignKeyPair> remaining = cache.pairsForTransaction(otherTx);
        assertEquals(30L, remaining.get(0).frontId());
    }

    @Test
    @DisplayName("isolation: concurrent transactions staging the same pair never collide")
    void testConcurrentTransactionsSamePairIsolated() {
        cache.cacheInsert(pair, tx);
        cache.cacheInsert(pair, otherTx);
        cache.cacheDelete(deleteIntent(), otherTx); // tx-2 dissolves its own intent

        assertEquals(1, cache.pairsForTransaction(tx).size(), "tx-1's insert survives");
        assertTrue(cache.pairsForTransaction(otherTx).isEmpty(), "tx-2's delete affects only tx-2");
    }

    @Test
    @DisplayName("the staged pair carries the relation scope (belongsTo) for the overlay reads")
    void testStagedPairBelongsToRelationScope() {
        cache.cacheInsert(pair, tx);
        final XdStorageForeignKeyPair staged = cache.pairsForTransaction(tx).get(0);
        assertNotNull(staged.frontTreeId());
        assertTrue(staged.belongsTo(staged.frontTreeId(), staged.reverseTreeId()));
        assertFalse(staged.belongsTo(staged.reverseTreeId(), staged.frontTreeId()));
    }
}
