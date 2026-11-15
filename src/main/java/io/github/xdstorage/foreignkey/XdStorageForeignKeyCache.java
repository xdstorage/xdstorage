package io.github.xdstorage.foreignkey;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The FK pair write-set (docs/analysis/FOREIGN_KEY_REDESIGN.md): every FK pair a
 * transaction stages goes through THIS cache through the phase pipeline
 * PREPARE_COMMIT → PERFORM_COMMIT → COMMIT (or disappears on ROLLBACK) — the pairs are
 * NEVER written into the FK B+trees at registration time. The B+tree write happens ONLY
 * at COMMIT ({@link #persistAllForTransaction}), after every entity record is committed.
 *
 * <p><b>Ownership:</b> ONE instance per storage, owned by
 * {@link XdStorageForeignKeyResourcesManager} and shared by the FK engine and every FK
 * resource — the engine's {@code commitAllFxPairs} and the resources' phase methods must
 * observe the same write-set.</p>
 *
 * <p><b>Per-transaction isolation:</b> records are keyed by
 * {@code transactionId + baseKey}, so concurrent transactions staging the same pair never
 * collide and a foreign transaction's rollback can never drop another transaction's pair.</p>
 *
 * <p><b>Intent semantics (later-wins):</b> an INSERT intent after a DELETE intent of the
 * same pair restores the pair; a DELETE intent after an INSERT intent makes the pair
 * never-landed. Duplicate intents of the same type are idempotent.</p>
 *
 * <p><b>Thread safety:</b> ConcurrentHashMap everywhere; no external synchronization.</p>
 */
public final class XdStorageForeignKeyCache {

    private final Map<String, XdStorageForeignKeyCacheRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> transactionRecords = new ConcurrentHashMap<>();

    /** Stages an INSERT intent (called by the FK resource at registration time). */
    public void cacheInsert(final XdStorageForeignKeyPair pair, final XdStorageTransaction tx) {
        final String fullKey = fullKey(tx, pair);
        final XdStorageForeignKeyCacheRecord existing = records.get(fullKey);
        if (existing != null) {
            if (existing.pair().type() == XdStorageForeignKeyObjectChangeType.INSERT) {
                return; // idempotent: the same insert intent already staged
            }
            // later-wins: a DELETE then an INSERT of the same pair restores the pair
            records.put(fullKey, newRecord(pair, tx));
            return;
        }
        records.put(fullKey, newRecord(pair, tx));
        transactionRecords.computeIfAbsent(tx.getTransactionId(), k -> ConcurrentHashMap.newKeySet()).add(fullKey);
    }

    /** Stages a DELETE intent (called by the FK resource at dissolution time). */
    public void cacheDelete(final XdStorageForeignKeyPair pair, final XdStorageTransaction tx) {
        final String fullKey = fullKey(tx, pair);
        final XdStorageForeignKeyCacheRecord existing = records.get(fullKey);
        if (existing != null) {
            if (existing.pair().type() == XdStorageForeignKeyObjectChangeType.DELETE) {
                return; // idempotent: the same delete intent already staged
            }
            // later-wins: an INSERT then a DELETE of the same pair — the pair never lands
            records.remove(fullKey);
            final Set<String> keys = transactionRecords.get(tx.getTransactionId());
            if (keys != null) {
                keys.remove(fullKey);
            }
            return;
        }
        records.put(fullKey, newRecord(pair, tx));
        transactionRecords.computeIfAbsent(tx.getTransactionId(), k -> ConcurrentHashMap.newKeySet()).add(fullKey);
    }

    private XdStorageForeignKeyCacheRecord newRecord(final XdStorageForeignKeyPair pair, final XdStorageTransaction tx) {
        final XdStorageForeignKeyCacheRecord record = new XdStorageForeignKeyCacheRecord(pair, tx.getTransactionId());
        record.prepareCommit(tx); // phase = PREPARE_COMMIT
        return record;
    }

    private String fullKey(final XdStorageTransaction tx, final XdStorageForeignKeyPair pair) {
        return tx.getTransactionId() + "\u001F" + pair.baseKey();
    }

    /** All pairs staged by the transaction (any intent) — the resource's overlay source. */
    public List<XdStorageForeignKeyPair> pairsForTransaction(final XdStorageTransaction tx) {
        final Set<String> keys = transactionRecords.get(tx.getTransactionId());
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        final List<XdStorageForeignKeyPair> result = new ArrayList<>(keys.size());
        for (final String key : keys) {
            final XdStorageForeignKeyCacheRecord record = records.get(key);
            if (record != null) {
                result.add(record.pair());
            }
        }
        return result;
    }

    /** Whether the transaction has any staged pair for this relation scope. */
    public boolean hasPairsForTransaction(final XdStorageTransaction tx) {
        final Set<String> keys = transactionRecords.get(tx.getTransactionId());
        return keys != null && !keys.isEmpty();
    }

    /**
     * POINT 2 (phase 1): structural validation of every staged pair. A pair that reached
     * phase 1 with unresolved (null) positional ids means its deferred-ID waiter never
     * fired — a loud integration failure, thrown here (the commit must not persist a
     * half-known pair). The SEMANTIC validation (pending manual-ID parents, waiter
     * counters) stays in the FK resource's phase-1, which owns those contracts.
     */
    public void validateAllForTransaction(final XdStorageTransaction tx) {
        for (final XdStorageForeignKeyPair pair : pairsForTransaction(tx)) {
            if (!pair.isResolved()) {
                throw new XdStorageRuntimeException("Not all foreign keys are registered for relation "
                        + pair.parentClassInfo.getClazz() + "#" + pair.parentField.getName() + " -> "
                        + pair.childClassInfo.getClazz() + ": the deferred pair " + pair.baseKey()
                        + " has unresolved ids at commit");
            }
        }
    }

    /** POINT 4 (phase 2): promote every staged pair to PERFORM_COMMIT. */
    public void promoteAllForTransaction(final XdStorageTransaction tx) {
        for (final XdStorageForeignKeyCacheRecord record : recordsFor(tx)) {
            record.performCommit(tx);
        }
    }

    /**
     * POINT 6 (commit): promotes every staged pair to PERFORM_COMMIT and closes the
     * transaction's write-set. The TREE PERSISTENCE itself is delivered by the FK trees'
     * own infrastructure 2PC contour: the pairs were applied to the staged node state at
     * registration/dissolution time (operation time — the only contour-correct window,
     * see XdStorageForeignKeyResource.registerForeignKey), became invisible-until-commit
     * through the node cache's MVCC, and are made permanent by the trees' own commit —
     * which runs as part of THIS transaction's commit. A rolled-back transaction's pairs
     * roll back with the node contour AND its write-set is released (rollback/release).
     */
    public void commitAllFxPairs(final XdStorageTransaction tx) {
        promoteAllForTransaction(tx);
        release(tx);
    }

    /** The phase of the staged pair (test/introspection surface). */
    public XdStorageCacheCommitPhase phaseFor(final XdStorageTransaction tx, final XdStorageForeignKeyPair pair) {
        final XdStorageForeignKeyCacheRecord record = records.get(fullKey(tx, pair));
        return record != null ? record.phase() : null;
    }

    /** POINT 7/8 (rollback and release): discards the transaction's write-set. */
    public void release(final XdStorageTransaction tx) {
        final String txId = tx.getTransactionId();
        final Set<String> keys = transactionRecords.remove(txId);
        if (keys != null) {
            for (final String key : keys) {
                records.remove(key);
            }
        }
    }

    private List<XdStorageForeignKeyCacheRecord> recordsFor(final XdStorageTransaction tx) {
        final Set<String> keys = transactionRecords.get(tx.getTransactionId());
        final List<XdStorageForeignKeyCacheRecord> result = new ArrayList<>();
        if (keys == null) {
            return result;
        }
        for (final String key : keys) {
            final XdStorageForeignKeyCacheRecord record = records.get(key);
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }
}
