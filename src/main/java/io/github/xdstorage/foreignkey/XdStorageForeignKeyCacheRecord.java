package io.github.xdstorage.foreignkey;

import io.github.xdstorage.resource.cache.XdStorageCacheCommitPhase;
import io.github.xdstorage.transaction.XdStorageTransaction;


/**
 * A single FK pair in the cache — mirrors XdStorageCacheRecord.
 * Each record has a phase state: PREPARE → PERFORM → COMMIT (or ROLLBACK).
 */
public final class XdStorageForeignKeyCacheRecord {
    private final XdStorageForeignKeyPair pair;
    private final String transactionId;
    private volatile XdStorageCacheCommitPhase phase;
    private volatile boolean validated;

    /**
     * Creates the record with the pair staged in the PREPARE_COMMIT phase.
     *
     * @param pair the staged FK pair intent
     * @param txId the owning transaction's id
     */
    public XdStorageForeignKeyCacheRecord(XdStorageForeignKeyPair pair, String txId) {
        this.pair = pair;
        this.transactionId = txId;
        this.phase = XdStorageCacheCommitPhase.PREPARE_COMMIT;
        this.validated = false;
    }

    /**
     * Marks the pair prepare-committed (phase 1 validated).
     *
     * @param tx the committing transaction
     */
    public void prepareCommit(XdStorageTransaction tx) {
        this.phase = XdStorageCacheCommitPhase.PREPARE_COMMIT;
    }

    /**
     * Marks the pair perform-committed (phase 2 confirmed).
     *
     * @param tx the committing transaction
     */
    public void performCommit(XdStorageTransaction tx) {
        this.phase = XdStorageCacheCommitPhase.PERFORM_COMMIT;
    }

    /**
     * Marks the pair committed and validated.
     *
     * @param tx the committing transaction
     */
    public void commit(XdStorageTransaction tx) {
        this.phase = XdStorageCacheCommitPhase.COMMIT;
        this.validated = true;
    }

    /**
     * Resets the pair's phase; the owning transaction's write-set entry is removed by
     * the cache, so a rolled-back pair simply stops existing in the write-set.
     *
     * @param tx the rolled-back transaction
     */
    public void rollback(XdStorageTransaction tx) {
        // No ROLLBACK phase in the enum - just reset to PREPARE_COMMIT
        this.phase = XdStorageCacheCommitPhase.PREPARE_COMMIT;
    }

    /**
     * @return the pair's relation-scoped identity (no intent type)
     */
    public String key() { return pair.baseKey(); }
    /**
     * @return the staged pair intent
     */
    public XdStorageForeignKeyPair pair() { return pair; }
    /**
     * @return the pair's current commit phase
     */
    public XdStorageCacheCommitPhase phase() { return phase; }
    /**
     * @return the owning transaction's id
     */
    public String transactionId() { return transactionId; }
    /**
     * @return whether the pair passed the commit validation
     */
    public boolean isValidated() { return validated; }

    /**
     * Two records are equal when their staged pairs are equal (the relation-scoped pair
     * identity, no intent type).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof XdStorageForeignKeyCacheRecord)) return false;
        XdStorageForeignKeyCacheRecord that = (XdStorageForeignKeyCacheRecord) o;
        return pair.equals(that.pair);
    }

    @Override
    public int hashCode() {
        return pair.hashCode();
    }

}
