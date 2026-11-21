package io.github.xdstorage.resource.cache;

/**
 * The phases of the record-level two-phase commit pipeline. Phase-gated components
 * (the FK pair write-set, the internal listeners) observe the phase transitions of a
 * changed record between staging and the actual commit.
 */
public enum XdStorageCacheCommitPhase {

    /** Phase 1: the change is staged and validated; the record's prepare flag is armed. */
    PREPARE_COMMIT,
    /** Phase 2: the change is confirmed for application; the record's perform flag is armed. */
    PERFORM_COMMIT,
    /** The final phase: the change is made permanent and visible. */
    COMMIT
}
