package io.github.xdstorage.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a named search index over the entity's fields — the surface queried by
 * {@code IXdFileStorage.load(cl, indexName, query)} and {@code count(...)}.
 *
 * <p>The FIRST declared index field is the index's PRIMARY value: it becomes the
 * B+tree key that {@code XdStoragePrimaryCriterion} compares against and the value
 * ORDER BY sorts on; the remaining fields are stored alongside each record and answer
 * the secondary criteria. {@code t()} tunes both per-index trees' fill factor;
 * {@code childrenIndexes()} joins child-class indexes into the query plan (see
 * {@link XdStorageObjectChildSearchIndex}).
 */
@Target(value = ElementType.TYPE)
@Retention(value = RetentionPolicy.RUNTIME)
public @interface XdStorageObjectSearchIndex {

/** Names the index.
      * @return the index's name — the {@code indexName} argument of {@code load(cl, indexName, query)}. Unique per
      * class. */
    String indexName();

/** Lists the indexed fields.
     * @return the indexed fields; the FIRST one is the PRIMARY value (the tree key ORDER BY sorts on) */
    String[] indexFieldNames();

    /**
     * B+Tree fill factor for the two per-index trees (main value tree + key tree).
     * Default 64 — the measured optimum of the config sweep (docs/PERFORMANCE-RECOMMENDATIONS.md §7):
     * best indexed-insert throughput and cold-search latency. The pre-study default 500 is ~5×
     * slower on indexed inserts and pays tens of ms on the first cold query.
     *
     * @return the B+tree minimum degree (fill factor) for both per-index trees
     */
    int t() default 64;

/** Lists the joined child indexes.
     * @return child-class indexes joined into this index's query plan (empty = no joins) */
    XdStorageObjectChildSearchIndex[] childrenIndexes() default {};
}
