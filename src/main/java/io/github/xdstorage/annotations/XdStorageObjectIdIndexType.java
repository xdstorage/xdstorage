package io.github.xdstorage.annotations;

import io.github.xdstorage.index.XdStorageIndexType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Chooses the physical structure of the class's PRIMARY id index (the index backing
 * {@code has(cl, id)} and {@code load(cl, id)}).
 *
 * <p>{@link io.github.xdstorage.index.XdStorageIndexType#Hash} (default): O(1) point
 * lookups, best cold-read latency after restart, fewest files; equality-only.
 * {@link io.github.xdstorage.index.XdStorageIndexType#BTree}: ordered keys with the
 * FP-1a range operators on the id index, faster bulk inserts; every node is a file.
 * The measured trade-offs are in docs/GUIDE.md and docs/BENCHMARKS.md.
 */
@Target(value = ElementType.TYPE)
@Retention(value = RetentionPolicy.RUNTIME)
public @interface XdStorageObjectIdIndexType {

/** Selects the id-index structure.
      * @return {@code Hash} (default) for equality-only point lookups; {@code BTree} for ordered keys and range
      * queries on the id index */
    XdStorageIndexType indexType() default XdStorageIndexType.Hash;

    /**
     * B+Tree fill factor (node holds t-1 … 2t-1 keys; one node = one file on disk).
     * Default 64 — the measured optimum of the config sweep (docs/PERFORMANCE-RECOMMENDATIONS.md §4):
     * best bulk insert, commit latency, delete and cold-read costs at small-to-medium N.
     * The pre-study default 500 optimizes file count at the price of ~12× slower inserts;
     * values below 8 are pathological on every axis.
     *
     * @return the B+tree minimum degree (fill factor) of the id-index tree
     */
    int t() default 64;
}
