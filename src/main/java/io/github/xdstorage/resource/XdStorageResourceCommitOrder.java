package io.github.xdstorage.resource;

/**
 * The commit-order groups a resource is registered into its transaction with (see
 * {@code XdStorageTransaction.registerResource}). A committing transaction processes its
 * resources in ascending order of these constants, so base data is persisted before the
 * indexes and search indexes that point at it. Values are spaced to let subclasses slot
 * intermediate groups in.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public class XdStorageResourceCommitOrder {

    /** Container of commit-order constants only — not instantiable by callers. */
    public XdStorageResourceCommitOrder() {
    }

    /** Base data resources of entities (class-shared fragments and per-object files). */
    public static final int DATA_RESOURCE_ORDER = 1000;

    /** Data resources of child/embedded objects committed after their parents' base data. */
    public static final int CHILDREN_DATA_RESOURCE_ORDER = 2000;

    /** Data resources of foreign keys spanning datasources, after children. */
    public static final int CROSS_DATASOURCE_FK_RESOURCE_ORDER = 2500;


    /** Id-index resources, after all data they will point at. */
    public static final int INDEX_RESOURCE_ORDER = 3000;

    /** Search-index resources, last — after both data and id indexes. */
    public static final int SEARCH_INDEX_RESOURCE_ORDER = 5000;

    public static final int FOREIGN_KEY_RESOURCE_ORDER = 7500;
}
