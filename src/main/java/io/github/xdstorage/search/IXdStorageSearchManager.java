package io.github.xdstorage.search;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.Collection;

/**
 * The query-executor contract of the secondary search indexes: index maintenance on
 * insert/update/delete of indexed entities, and query execution that selects the access
 * plan per criterion — an ordered B+tree walk for the range family (GT/GTE/LT/LTE/
 * BETWEEN/IN), an exact key lookup for EQUALS, a whole-index scan otherwise — with
 * FP-1b ORDER BY / LIMIT / OFFSET shaping pushed down where the plan allows it.
 * Results are snapshot-consistent: every returned object is re-read through the
 * caller's transaction.
 *
 * <p>Engine-internal — not part of the public API. Implementations are reached through
 * the engine's services locator inside a caller's transaction; calls are as thread-safe
 * as the underlying storage and resource locking.</p>
 */
public interface IXdStorageSearchManager {

    /**
     * Whether the object's class declares at least one search index.
     *
     * @param object the object to inspect (its class is the authority)
     * @return {@code true} when the class has at least one declared index
     */
    boolean hasIndex(Object object);

    /**
     * Indexes one object into every search index declared on its class, inside the
     * caller's transaction. A {@code null} object is ignored.
     *
     * @param object      the object to index
     * @param transaction the caller's transaction
     * @throws XdStorageException           on an indexing or storage failure
     * @throws XdStorageConnectionException on a storage I/O failure (a retry may help)
     */
    void insert(Object object, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Indexes a collection of objects of one entity class, skipping already-indexed
     * members (a non-null id marks them as indexed). Null members are ignored.
     *
     * @param objects     the objects to index (non-empty, one entity class)
     * @param transaction the caller's transaction
     * @throws XdStorageException           on an indexing or storage failure
     * @throws XdStorageConnectionException on a storage I/O failure (a retry may help)
     */
    void insert(Collection<?> objects, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Re-indexes one object after a change, inside the caller's transaction. The object
     * must already be indexed.
     *
     * @param object      the changed object to re-index
     * @param transaction the caller's transaction
     * @throws XdStorageException           when the object is not indexed, or on a storage failure
     * @throws XdStorageConnectionException on a storage I/O failure (a retry may help)
     */
    void update(Object object, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Removes one object from every search index declared on its class, inside the
     * caller's transaction.
     *
     * @param reference   the object to de-index
     * @param transaction the caller's transaction
     * @throws XdStorageException           on a de-indexing or storage failure
     * @throws XdStorageConnectionException on a storage I/O failure (a retry may help)
     */
    void delete(Object reference, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Removes a collection of objects of one entity class from every search index
     * declared on the class, inside the caller's transaction.
     *
     * @param references  the objects to de-index (non-empty, one entity class)
     * @param transaction the caller's transaction
     * @throws XdStorageException           on a de-indexing or storage failure
     * @throws XdStorageConnectionException on a storage I/O failure (a retry may help)
     */
    void delete(Collection<?> references, XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Executes the query against the named index of the entity class, inside the caller's
     * transaction: the criteria select the access plan (ordered tree walk / key lookup /
     * scan) and the objects behind the matched records are fetched snapshot-visible. An
     * unknown index name fails loudly.
     *
     * @param <T>         the entity type
     * @param cl          the indexed entity class
     * @param indexName   the declared index to query
     * @param query       the criteria plus optional ORDER BY / LIMIT / OFFSET shaping
     * @param transaction the caller's transaction (defines the snapshot)
     * @return the matching objects (empty when nothing matches or the index is empty);
     *         ordered only when the query requests ORDER BY
     * @throws XdStorageException           when the index does not exist for the class, or on a storage failure
     * @throws XdStorageConnectionException on a storage I/O failure (a retry may help)
     */
    <T> Collection<T> search(Class<T> cl, String indexName, XdStorageSearchQuery query,
                             XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * FP-1b (docs/tasks/2.1.0/10): record-level count of the query's snapshot-visible matches.
     * Ignores the query's ORDER BY / LIMIT / OFFSET shaping (SQL COUNT semantics). Every counted
     * record passes the same snapshot-visibility read as a load(), so the number is exactly the
     * size the equivalent search() would return; the objects are read but not retained.
     *
     * @param cl          the indexed entity class
     * @param indexName   the declared index to count against
     * @param query       the criteria (ORDER BY / LIMIT / OFFSET shaping is ignored)
     * @param transaction the caller's transaction (defines the snapshot)
     * @return the number of snapshot-visible records satisfying the query's criteria
     * @throws XdStorageException           when the index does not exist for the class, or on a storage failure
     * @throws XdStorageConnectionException on a storage I/O failure (a retry may help)
     */
    long count(Class<?> cl, String indexName, XdStorageSearchQuery query,
               XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * EXPLAIN (docs/tasks/2.1.0/25): the read-only query plan — the path the engine would
     * take (seek / ordered range walk / scan), the bound shape, the post-filters, the
     * degradation reason — WITHOUT executing the query. Built from the same classification
     * the execution lives by (handleQuery), so the plan cannot disagree with it. No records
     * are materialized; the plan is static (index metadata is class-level), the transaction
     * only guards the index-tree availability probe.
     *
     * @param cl          the indexed entity class
     * @param indexName   the declared index to explain against
     * @param query       the criteria plus optional shaping
     * @param transaction the caller's transaction ({@code null} — an internal probe)
     * @return the query plan value object
     * @throws XdStorageException when the index does not exist for the class
     */
    io.github.xdstorage.search.query.XdStorageQueryPlan explain(Class<?> cl, String indexName,
                                                                XdStorageSearchQuery query,
                                                                XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException;
}
