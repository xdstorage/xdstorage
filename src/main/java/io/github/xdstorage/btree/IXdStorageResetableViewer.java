package io.github.xdstorage.btree;

/**
 * R56SCAN (docs/tasks/2.2.0/23): the resettable viewer contract for the epoch-validated
 * chain walk. A viewer implementing this interface consents to REPLAYS: when the walk's
 * post-walk structure validation detects a concurrent structural change (a split/merge
 * during the traversal), the tree DISCARDS the walked output and calls
 * {@code resetForReplay()} — the implementation must return its state to the fresh
 * start (clear the collected entries, reset the limit counters) — and the tree re-walks
 * the fresh chain. The viewers that do NOT implement this interface receive the legacy
 * behavior: the first walk's output stands (the mixed-structure window is disclosed).
 *
 * <p>The replay is bounded (3 attempts); the final attempt runs under the tree read
 * lock — the structure cannot change under it, so the last walk is always valid and
 * needs no reset.
 */
public interface IXdStorageResetableViewer {

    /**
     * Resets the viewer's state for a fresh replay of the walk (the previous walk's
     * output is discarded by the tree — the implementation clears its collected state).
     */
    void resetForReplay();
}
