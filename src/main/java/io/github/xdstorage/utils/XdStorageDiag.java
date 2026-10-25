package io.github.xdstorage.utils;

/**
 * The engine's deep-diagnostics switch (TREEFILE/LOSTUPD, docs/tasks/2.1.0/40+41): enabled
 * with {@code -Dxdstorage.diag=true} it routes the per-decision trace of the cache-record
 * lifecycle (write-gate accepts/rejects, commit stamps) and the B+tree structural mutations
 * (root changes, join absorptions) to INFO — visible in the suite sink and any embedder log
 * for the duration of an investigation. Disabled (the default) the trace is compiled out by
 * the flag check and costs nothing; the L-2 discipline (INFO = rare lifecycle events only)
 * holds for normal runs.
 *
 * <p>Follows the {@code XdStorageBTreeNode.CHAIN_DEBUG} ({@code -Dxdstorage.chain.debug})
 * precedent. Engine-internal — not part of the public API.
 */
public final class XdStorageDiag {

    /** {@code true} when {@code -Dxdstorage.diag=true} was passed to the JVM. */
    public static final boolean ENABLED = Boolean.getBoolean("xdstorage.diag");

    private XdStorageDiag() {
    }
}
