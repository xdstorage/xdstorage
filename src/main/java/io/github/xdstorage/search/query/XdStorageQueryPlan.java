package io.github.xdstorage.search.query;

import java.util.Collections;
import java.util.List;

/**
 * EXPLAIN (docs/tasks/2.1.0/25): план выполнения поискового запроса — ЧТО ДВИЖОК СДЕЛАЛ БЫ,
 * без исполнения. Чистое значение: индекс, путь (seek / ordered range walk / scan), форма
 * границ диапазона, критерии-постфильтры, причина деградации и сводка shaping. Строится из
 * ТОЙ ЖЕ классификации, которой живет исполнение ({@code XdStorageSearchManager.handleQuery}),
 * поэтому план не лжет.
 *
 * <p>Пути: {@code INDEX_SEEK} — точный поиск по ключу поискового B+дерева
 * ({@code EQUALS}); {@code INDEX_RANGE_WALK} — упорядоченный ограниченный обход листовой
 * цепи с ранним прекращением на верхней границе ({@code GT/GTE/LT/LTE/BETWEEN/IN}, FP-1a);
 * {@code SCAN} — полный обход индекса с постфильтрацией ({@code NOT_EQUALS}, пустой запрос,
 * неуникальные операторы, деградации).</p>
 *
 * <p>Деградации (fallbackReason): индексное дерево еще не построено; в запросе есть
 * неуникальный/составной критерий (SCAN-альтернатива); у класса Hash id-индекс и запрос —
 * диапазон (контракт FP-1a: диапазон на Hash id-индексе → scan). План read-only: записей не
 * материализует, сайд-эффектов не имеет.</p>
 */
public class XdStorageQueryPlan {

    /** The query execution path the engine would take. */
    public enum Path {
        /** Exact-key lookup in the search B+tree. */
        INDEX_SEEK,
        /** Ordered bounded walk of the leaf chain with early termination (FP-1a). */
        INDEX_RANGE_WALK,
        /** Full index traversal with per-record post-filtering. */
        SCAN
    }

    private final String indexName;
    private final String idIndexType;
    private final Path path;
    private final String boundShape;
    private final List<String> postFilters;
    private final List<String> steps;
    private final String fallbackReason;
    private final String shaping;

    /**
     * Builds the plan snapshot; the list arguments are defensively copied unmodifiable.
     *
     * @param indexName      the search index the query was routed to
     * @param idIndexType    the id-index structure type of the entity's class ("BTree"/"Hash")
     * @param path           the execution path the engine would take
     * @param boundShape     the range bound shape (null when the path is not a range walk)
     * @param postFilters    the criteria evaluated per fetched record, one line each
     * @param steps          the per-OR-alternative routing steps, one line each
     * @param fallbackReason why the plan degraded from the ideal path (null when it did not)
     * @param shaping        the ORDER BY/LIMIT/OFFSET shaping summary (null when unshaped)
     */
    public XdStorageQueryPlan(final String indexName, final String idIndexType, final Path path,
                              final String boundShape, final List<String> postFilters,
                              final List<String> steps, final String fallbackReason, final String shaping) {
        this.indexName = indexName;
        this.idIndexType = idIndexType;
        this.path = path;
        this.boundShape = boundShape;
        this.postFilters = postFilters != null ? Collections.unmodifiableList(postFilters) : Collections.emptyList();
        this.steps = steps != null ? Collections.unmodifiableList(steps) : Collections.emptyList();
        this.fallbackReason = fallbackReason;
        this.shaping = shaping;
    }

    /**
     * The search index the query was routed to.
     * @return the search index name
     */
    public String getIndexName() {
        return indexName;
    }

    /**
     * The id-index structure type of the entity's class ("BTree"/"Hash").
     * @return the id-index structure type
     */
    public String getIdIndexType() {
        return idIndexType;
    }

    /**
     * The execution path the engine would take.
     * @return the execution path
     */
    public Path getPath() {
        return path;
    }

    /**
     * The range bound shape (null when the path is not a range walk).
     * @return the range bound shape, or null
     */
    public String getBoundShape() {
        return boundShape;
    }

    /**
     * The criteria evaluated per fetched record (post-filters), one line each.
     * @return the unmodifiable post-filter lines; empty when none
     */
    public List<String> getPostFilters() {
        return postFilters;
    }

    /**
     * The per-OR-alternative routing steps (one line each).
     * @return the unmodifiable routing step lines; empty when none
     */
    public List<String> getSteps() {
        return steps;
    }

    /**
     * Why the plan degraded from the ideal path, or {@code null} when it did not.
     * @return the degradation reason, or null
     */
    public String getFallbackReason() {
        return fallbackReason;
    }

    /**
     * The ORDER BY/LIMIT/OFFSET shaping summary, or {@code null} when unshaped.
     * @return the shaping summary, or null
     */
    public String getShaping() {
        return shaping;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("plan[").append(path).append("] index=").append(indexName);
        if (fallbackReason != null) {
            builder.append(" fallback=").append(fallbackReason);
        }
        if (boundShape != null) {
            builder.append(" bounds=").append(boundShape);
        }
        if (!postFilters.isEmpty()) {
            builder.append(" postFilters=").append(postFilters);
        }
        if (shaping != null) {
            builder.append(" shaping=").append(shaping);
        }
        builder.append(" idIndex=").append(idIndexType);
        return builder.toString();
    }
}
