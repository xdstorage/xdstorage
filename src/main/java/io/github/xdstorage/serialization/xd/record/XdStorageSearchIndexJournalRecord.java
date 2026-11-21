package io.github.xdstorage.serialization;

/**
 * W-4b (docs/tasks/2.1.0/49): a single search-index logical-op journal record.
 * The search-index journal contains append-only ADDKEY/DELKEY operations
 * (O(delta) instead of full-base-file rewrite). Replay is done at open
 * against the live canonical B+Tree.
 *
 * <p>YAML layout (same as {@code XdStorageYamlCommitJournal} record-block format):
 * <pre>
 * - record:
 *     serial: 17
 *     treeId: 'search-tree-Order'
 *     op: ADDKEY
 *     key: 'Ship-5'
 *     objectId: '5'
 * </pre>
 *
 * <p>Crash semantics: same as W-1 — an interrupted append is discarded after
 * the last EOF sentinel. Replay is idempotent: ADDKEY for an existing key is
 * a no-op (the tree's own insert handles it), DELKEY for a non-existing key
 * is also a no-op (the tree's own delete handles it silently).
 *
 * @see io.github.xdstorage.search.XdStorageSearchIndexResource
 * @see io.github.xdstorage.serialization.search.XdStorageSearchIndexJournal
 */
public final class XdStorageSearchIndexJournalRecord {

    private static final String OP_ADDKEY = "ADDKEY";
    private static final String OP_DELKEY = "DELKEY";

    /** The commit serial (0 for the open-time guard auto-checkpoint). */
    public final long serial;

    /** The B+Tree identity (e.g. {@code "search-tree-Order"}).
     * One resource may hold multiple search trees — the treeId disambiguates. */
    public final String treeId;

    /** {@code "ADDKEY"} or {@code "DELKEY"}. */
    public final String op;

    /** The search-key primary value (the value being indexed). */
    public final String key;

    /** The object id being keyed. */
    public final String objectId;

    /**
     * Builds the journal record.
     *
     * @param serial   the commit serial (0 for the open-time guard auto-checkpoint)
     * @param treeId   the B+Tree identity (e.g. {@code "search-tree-Order"})
     * @param op       {@code "ADDKEY"} or {@code "DELKEY"}
     * @param key      the search-key primary value (the value being indexed)
     * @param objectId the object id being keyed
     */
    public XdStorageSearchIndexJournalRecord(final long serial, final String treeId,
                                              final String op, final String key,
                                              final String objectId) {
        this.serial = serial;
        this.treeId = treeId;
        this.op = op;
        this.key = key;
        this.objectId = objectId;
    }

    /**
     * Whether this is an ADDKEY operation.
     * @return {@code true} when {@code op} is {@code "ADDKEY"}
     */
    public boolean isAddKey() {
        return OP_ADDKEY.equals(op);
    }

    /**
     * Whether this is a DELKEY operation.
     * @return {@code true} when {@code op} is {@code "DELKEY"}
     */
    public boolean isDelKey() {
        return OP_DELKEY.equals(op);
    }

    @Override
    public String toString() {
        return "Record[serial=" + serial + " treeId=" + treeId + " " + op
                + "(" + key + ", " + objectId + ")]";
    }
}
