package io.github.xdstorage.search.key;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/** The ordered search-tree key: the indexed PRIMARY value plus the owning object id,
 * tagged with the operation that produced it. All entries of one index — inserts, updates,
 * deletes and search seeks — share this key shape, and its {@link #compareTo(XdStorageSearchIndexKey)}
 * orders by the PRIMARY value first and the object id second, deliberately ignoring the
 * operation tag so the B+tree topology stays symmetric across operation kinds.
 *
 * <p>Persisted inside the search tree; deserialized instances come back with plain
 * (restored) value types. Instances are mutable and not thread-safe.
 *
 * <p>Engine-internal — not part of the public API. */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
public class XdStorageSearchIndexKey implements Comparable<XdStorageSearchIndexKey> {

    private Object value;

    private Object objectId;

    private XdStorageSearchIndexOperationType operation;

    /** Creates an empty key, for the deserializer. */
    public XdStorageSearchIndexKey() {

    }

    /**
     * Creates a key without an operation tag.
     *
     * @param value    the indexed PRIMARY value (the tree's ordering part)
     * @param objectId the id of the indexed object (may be {@code null} for a seek template)
     */
    public XdStorageSearchIndexKey(final Object value, final Object objectId) {
        this(value, objectId, null);
    }

    /**
     * Creates a fully tagged key.
     *
     * @param value     the indexed PRIMARY value (the tree's ordering part)
     * @param objectId  the id of the indexed object (may be {@code null} for a seek template)
     * @param operation the operation tag (may be {@code null}; not part of the ordering)
     */
    public XdStorageSearchIndexKey(final Object value, final Object objectId, final XdStorageSearchIndexOperationType
            operation) {
        this.value = value;
        this.objectId = objectId;
        this.operation = operation;
    }

    /**
     * The indexed PRIMARY value (the tree's ordering part).
     *
     * @return the primary value
     */
    public Object getValue() {
        return value;
    }

    /**
     * Replaces the indexed PRIMARY value.
     *
     * @param value the new primary value
     */
    public void setValue(final Object value) {
        this.value = value;
    }

    /**
     * The id of the indexed object this key points to.
     *
     * @return the object id (may be {@code null} on a seek template)
     */
    public Object getObjectId() {
        return objectId;
    }

    /**
     * Replaces the object id.
     *
     * @param objectId the new object id
     */
    public void setObjectId(final Object objectId) {
        this.objectId = objectId;
    }

    /**
     * Replaces the operation tag (never part of the ordering).
     *
     * @param operation the new operation tag
     */
    public void setOperation(final XdStorageSearchIndexOperationType operation) {
        this.operation = operation;
    }

    /**
     * Orders keys by the indexed PRIMARY value, then — when the values compare equal and
     * both keys carry an object id — by the object id. The operation tag is never compared:
     * insert-, update-, delete- and search-shaped keys of the same (value, objectId) pair
     * are equal, which keeps the tree topology symmetric. A {@code null} value sorts before
     * any non-null value; keys with a {@code null} objectId tie.
     *
     * @param o the key to compare against
     * @return a negative/zero/positive integer as this key orders before/equal/after {@code o}
     * @throws ClassCastException when the two primary values (or the two object ids) are of
     *         non-mutually-comparable types — mixed-type indexing fails loudly, never silently
     */
    @Override
    public int compareTo(final XdStorageSearchIndexKey o) {
        // === АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ СУБД (Корень всех сбоев маршрутизации Б+ Дерева): ===
        // Гарантируем абсолютную симметричность контракта compareTo для всех типов операций (Insert/Search/Update)!
        // Сначала сравниваем ключи по первичному значению индексируемого поля 'value'.
        int result = this.value == null ? (o.value == null ? 0 : -1) : (o.value == null ? 1 : ((Comparable) this.value)
                .compareTo(o.value));

        // Если первичные значения равны, и у ОБЕИХ сравниваемых записей присутствуют идентификаторы объектов objectId,
        // мы ОБЯЗАНЫ выполнить строгое уточняющее сравнение по objectId (ID планеты), вне зависимости от флагов
        // операций!
        // Прежняя логика игнорировала objectId в режиме Search, что ломало топологию B+ Дерева и вызывало крах
        // readByReference!
        if (result == 0 && this.objectId != null && o.objectId != null) {
            result = ((Comparable) this.objectId).compareTo(o.objectId);
        }

        return result;
    }


    /** Renders the key as its object id.
     *
     * @return the string form of the object id
     * @throws NullPointerException when the key carries no object id (e.g. a seek template) */
    @Override
    public String toString() {
        return objectId.toString();
    }
}
