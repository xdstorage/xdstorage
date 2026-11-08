package io.github.xdstorage;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.structure.update.IXdStorageStructureUpdater;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageVersion;

import java.util.Collection;
import java.util.List;

/**
 * The file-storage contract: {@link IXdStorage} extended with the capabilities of the
 * on-disk engine — indexed search queries ({@link XdStorageSearchQuery} over
 * {@code @XdStorageObjectSearchIndex} indexes), record-level counts, schema (structure)
 * updates, predicate scans and change watchers.
 *
 * <p>All contract notes of {@link IXdStorage} apply unchanged: the thread-bound
 * transaction requirement (every overload without an explicit transaction argument uses
 * the current thread's binding), no auto-rollback on CRUD failures, MVCC snapshot reads.
 * Open instances through {@link XdStorageProvider}.
 */
public interface IXdFileStorage extends IXdStorage {

    /**
     * A2: the library version (semver, e.g. "2.0.0"), read from the Maven-filtered
     * {@code /xdstorage.properties} resource. "unknown" when the classpath was assembled
     * without Maven filtering.
     *
     * @return the library version string (e.g. "2.1.0"), never {@code null}
     */
    default String getVersion() {
        return XdStorageVersion.getVersion();
    }

    /**
     * Registers a structure updater — a schema-evolution step the engine runs for a class
     * when {@link #executeStructureUpdate(Class)} is invoked. Updaters registered here see
     * the class's stored structure and rewrite it transactionally.
     *
     * @param updater the updater to register
     */
    void registerStructureUpdater(IXdStorageStructureUpdater updater);

    /**
     * Runs the registered structure updaters for the class inside the <b>current
     * transaction</b> (a thread-bound transaction is required) and returns the updaters
     * that were applied. The class must carry an id field.
     *
     * @param cl the entity class whose stored structure to evolve
     * @return the updaters applied, in application order (empty when the structure is current)
     * @throws XdStorageException           when no transaction is bound to the thread, the
     *                                      class has no id field, or an updater fails
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    List<IXdStorageStructureUpdater> executeStructureUpdate(Class<?> cl) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads every object of the class that passes the predicate — an in-snapshot scan
     * (no index required; the predicate runs per materialized record). Requires an open
     * transaction — see {@link #save(Object)}.
     *
     * @param <T>       the entity class
     * @param cl        the entity class
     * @param predicate the filter; a {@code null} predicate is rejected
     * @return the objects visible in the current transaction's snapshot that pass
     * @throws XdStorageException           when no transaction is bound to the thread or
     *                                      the class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    <T> Collection<T> load(Class<T> cl, IXdStoragePredicate<T> predicate) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads every object of the class that passes the predicate, inside the given
     * transaction.
     *
     * @param <T>         the entity class
     * @param cl          the entity class
     * @param predicate   the filter; a {@code null} predicate is rejected
     * @param transaction the transaction to read in (must be alive)
     * @return the snapshot-visible objects that pass
     * @throws XdStorageException           when the transaction is not alive or the class
     *                                      has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    @SuppressWarnings("unchecked")
    <T> Collection<T> load(Class<T> cl, IXdStoragePredicate<T> predicate, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads objects through a declared search index
     * ({@code @XdStorageObjectSearchIndex}): the query's criteria evaluate against the
     * index records; equality and the FP-1a range operators use the index tree when one
     * applies and degrade to an indexed scan otherwise. The result honors the query's
     * ORDER BY / LIMIT / OFFSET shaping; {@code count(cl, indexName, query)} answers the
     * same matches without materializing them. Requires an open transaction — see
     * {@link #save(Object)}.
     *
     * @param <T>       the entity class
     * @param cl        the entity class
     * @param indexName the {@code indexName} of one of the class's declared search indexes
     * @param query     the query (criteria + shaping); a {@code null} query is rejected
     * @return the matching objects, shaped by the query
     * @throws XdStorageException           when no transaction is bound to the thread, the
     *                                      class/index pair is unknown, or a criterion is
     *                                      malformed (e.g. a non-{@code Comparable} range
     *                                      comparison — mixed types fail loudly)
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    <T> Collection<T> load(Class<T> cl, String indexName, XdStorageSearchQuery query) throws XdStorageException, XdStorageConnectionException;

    /**
     * Loads objects through a declared search index inside the given transaction — see
     * {@link #load(Class, String, XdStorageSearchQuery)}.
     *
     * @param <T>         the entity class
     * @param cl          the entity class
     * @param indexName   the {@code indexName} of one of the class's declared search indexes
     * @param query       the query (criteria + shaping)
     * @param transaction the transaction to read in (must be alive)
     * @return the matching objects, shaped by the query
     * @throws XdStorageException           when the transaction is not alive, the
     *                                      class/index pair is unknown, or a criterion is
     *                                      malformed
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    <T> Collection<T> load(Class<T> cl, String indexName, XdStorageSearchQuery query,
                           IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * FP-1b (docs/tasks/2.1.0/10): record-level count of the query's snapshot-visible matches,
     * without building the result collection. Ignores the query's ORDER BY / LIMIT / OFFSET
     * (SQL COUNT semantics); runs in the current transaction when one is open.
     *
     * @param cl        the entity class
     * @param indexName the {@code indexName} of one of the class's declared search indexes
     * @param query     the query whose criteria define the matches (shaping is ignored)
     * @return the number of snapshot-visible matches
     * @throws XdStorageException           when no transaction is bound to the thread or
     *                                      the class/index pair is unknown
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    long count(Class<?> cl, String indexName, XdStorageSearchQuery query) throws XdStorageException, XdStorageConnectionException;

    /**
     * EXPLAIN (docs/tasks/2.1.0/25): the read-only query plan — which path the engine would
     * take (INDEX_SEEK / INDEX_RANGE_WALK / SCAN), the index, the bound shape, the
     * post-filters, the degradation reason and the shaping summary — WITHOUT executing the
     * query. No records are materialized; no transaction is required (the plan is static:
     * the index metadata is class-level).
     *
     * @param cl        the indexed entity class
     * @param indexName the declared index to explain against
     * @param query     the criteria plus optional shaping
     * @return the query plan value object
     * @throws XdStorageException           when the index does not exist for the class
     * @throws XdStorageConnectionException on a storage I/O failure
     */
    io.github.xdstorage.search.query.XdStorageQueryPlan explain(Class<?> cl, String indexName,
                                                                XdStorageSearchQuery query)
            throws XdStorageException, XdStorageConnectionException;

    /**
     * EXPLAIN: transactional form — the plan is still static, the transaction only guards
     * the index-tree availability probe (an index tree not yet built plans as SCAN).
     *
     * @param cl          the indexed entity class
     * @param indexName   the declared index to explain against
     * @param query       the criteria plus optional shaping
     * @param transaction the caller's transaction
     * @return the query plan value object
     * @throws XdStorageException           when the index does not exist for the class
     * @throws XdStorageConnectionException on a storage I/O failure
     */
    io.github.xdstorage.search.query.XdStorageQueryPlan explain(Class<?> cl, String indexName,
                                                                XdStorageSearchQuery query,
                                                                IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException;

    /**
     * FP-1b: transactional form of {@link #count(Class, String, XdStorageSearchQuery)} — the
     * count reflects exactly the given transaction's snapshot.
     *
     * @param cl          the entity class
     * @param indexName   the {@code indexName} of one of the class's declared search indexes
     * @param query       the query whose criteria define the matches (shaping is ignored)
     * @param transaction the transaction whose snapshot to count in
     * @return the number of matches in the transaction's snapshot
     * @throws XdStorageException           when the transaction is not alive or the
     *                                      class/index pair is unknown
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    long count(Class<?> cl, String indexName, XdStorageSearchQuery query,
               IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;

    /**
     * Feeds every object of the class visible in the current transaction's snapshot to the
     * watcher — a streaming scan without materializing the whole result collection. The
     * watcher runs inside the read; throwing from it fails the scan. Requires an open
     * transaction — see {@link #save(Object)}.
     *
     * @param <T>     the entity class
     * @param cl      the entity class
     * @param watcher the per-object callback; a {@code null} watcher is rejected
     * @throws XdStorageException           when no transaction is bound to the thread or
     *                                      the class has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    <T> void watch(Class<T> cl, IXdStorageWatcher<T> watcher) throws XdStorageException, XdStorageConnectionException;

    /**
     * Feeds every object of the class visible in the given transaction's snapshot to the
     * watcher — see {@link #watch(Class, IXdStorageWatcher)}.
     *
     * @param <T>         the entity class
     * @param cl          the entity class
     * @param watcher     the per-object callback
     * @param transaction the transaction to read in (must be alive)
     * @throws XdStorageException           when the transaction is not alive or the class
     *                                      has no id field
     * @throws XdStorageConnectionException on a storage-level I/O failure
     */
    @SuppressWarnings("unchecked")
    <T> void watch(Class<T> cl, IXdStorageWatcher<T> watcher, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException;
}
