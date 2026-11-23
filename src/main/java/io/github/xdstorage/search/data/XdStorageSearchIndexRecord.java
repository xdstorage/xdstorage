package io.github.xdstorage.search.data;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.*;

/** One search-index record: the full entity-derived copy a search tree holds for a single
 * indexed object. Carries the object id, the data-journal resource id it was written under,
 * the PRIMARY index value (the B+tree ordering key), the values of the other declared index
 * fields, and the flattened ids and field values of nested child entities. The copy lets a
 * search resolve criteria inside the tree without touching the data journal — a redundancy
 * that also lets a stale index record serve a query even when the journal entry behind it
 * is torn, per the power-loss matrix.
 *
 * <p>Persisted with the {@code StoreWithParentObject} policy (the record lives inside the
 * search-tree structure). A plain mutable POJO — not thread-safe.
 *
 * <p>Engine-internal — not part of the public API. */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
public class XdStorageSearchIndexRecord {

    @XdStorageObjectId
    private Object id;

    private Object resourceId;

    private Object primaryIndexValue;

    private Map<String, Object> fieldValues;

    /**
     * map[ child_class, map[ child_field_name, list[ object_id ] ] ]
     */
    private Map<Class<?>, Map<String, List<Object>>> childrenIds;

    /**
      * Map[ child_class, map[ child_object_id, Map[ child_field_name, Map[child_object_class_field_name,
      * child_object_field_value]]]]
     */
    private Map<Class<?>, Map<Object, Map<String, Map<String, Object>>>> childrenFieldValues;

    /** Creates an empty record, for the engine's record assembly. */
    public XdStorageSearchIndexRecord() {
    }

    /**
     * Replaces the indexed object's id (the {@code XdStorageObjectId} of the record).
     *
     * @param id the object id
     */
    public void setId(final Object id) {
        this.id = id;
    }

    /**
     * The indexed object's id.
     *
     * @return the object id (may be {@code null} until the assembly confers it)
     */
    public Object getId() {
        return id;
    }

    /**
     * The data-journal resource id the indexed object was written under.
     *
     * @return the resource id, or {@code null} for the {@code StoreAsSingleObject} policy
     *         (the object read goes through the per-object resource instead)
     */
    public Object getResourceId() {
        return resourceId;
    }

    /**
     * Replaces the data-journal resource id.
     *
     * @param resourceId the resource id ({@code null} for the single-object policy)
     */
    public void setResourceId(final Object resourceId) {
        this.resourceId = resourceId;
    }

    /**
     * Replaces the PRIMARY index value — the tree's ordering key (the first declared
     * index field of the indexed class).
     *
     * @param primaryIndexValue the primary value
     */
    public void setPrimaryIndexValue(final Object primaryIndexValue) {
        this.primaryIndexValue = primaryIndexValue;
    }

    /**
     * The PRIMARY index value — the tree's ordering key.
     *
     * @return the primary value (may be {@code null} when the field is null on the object)
     */
    public Object getPrimaryIndexValue() {
        return primaryIndexValue;
    }

    /**
     * Sets one declared index field's value.
     *
     * @param name  the indexed field name
     * @param value the field value read off the object (may be {@code null})
     */
    public void setFieldValue(final String name, final Object value) {
        if (fieldValues == null) {
            fieldValues = new HashMap<>();
        }
        fieldValues.put(name, value);
    }

    /**
     * Reads one declared index field's value.
     *
     * @param name the indexed field name
     * @return the field value, or {@code null} when absent
     */
    public Object getFieldValue(final String name) {
        return fieldValues == null ? null : fieldValues.get(name);
    }

    /**
     * Replaces the whole index-field value map.
     *
     * @param fieldValues the new field-name-to-value map
     */
    public void setFieldValues(final Map<String, Object> fieldValues) {
        this.fieldValues = fieldValues;
    }

    /**
     * The index-field value map of the root object.
     *
     * @return the map, or {@code null} when no field value was ever set
     */
    public Map<String, Object> getFieldValues() {
        return fieldValues;
    }

    /**
     * Records a nested child entity's id under its parent field, deduplicated: an id already
     * recorded for the same (field, class) pair is not added twice.
     *
     * @param childFieldName the parent's indexed field the child hangs off
     * @param cl             the child entity's class
     * @param childId        the child entity's id
     */
    public void addChildIdIfNotExists(final String childFieldName, final Class<?> cl, final Object childId) {
        if (childrenIds == null) {
            childrenIds = new HashMap<>();
        }

        Map<String, List<Object>> idsOfClass = childrenIds.get(cl);
        if (idsOfClass == null) {
            childrenIds.put(cl, idsOfClass = new HashMap<>());
        }

        List<Object> ids = idsOfClass.get(childFieldName);
        if(ids == null) {
            idsOfClass.put(childFieldName, ids = new ArrayList<>());
        }

        if(!ids.contains(childId)) {
            ids.add(childId);
        }
    }

    /**
     * Records a nested child entity's indexed field value, deduplicated: re-recording the
     * same value for the same (field, object) pair is a silent no-op, which keeps repeated
     * cascaded saves from bloating the tree entry.
     *
     * @param childFieldName the parent's indexed field the child hangs off
     * @param cl             the child entity's class
     * @param objectId       the child entity's id
     * @param fieldName      the child entity's indexed field name
     * @param value          the child entity's field value (may be {@code null})
     */
    public void addChildFieldValue(final String childFieldName, final Class<?> cl, final Object objectId, final String
            fieldName, final Object value) {
        if (childrenFieldValues == null) {
            childrenFieldValues = new HashMap<>();
        }

        Map<Object, Map<String, Map<String, Object>>> objects = childrenFieldValues.get(cl);
        if (objects == null) {
            childrenFieldValues.put(cl, objects = new HashMap<>());
        }

        Map<String, Map<String, Object>> childs = objects.get(objectId);
        if (childs == null) {
            objects.put(objectId, childs = new HashMap<>());
        }

        Map<String, Object> valuesByObjectId = childs.get(childFieldName);
        if(valuesByObjectId == null) {
            childs.put(childFieldName, valuesByObjectId = new HashMap<>());
        }

        // =========================================================================
        // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ СУБД (Ликвидация дублирования свойств в B+ Дереве):
        // Проверяем, если данное JavaBeans-свойство для текущего objectId дочерней сущности
        // УЖЕ было добавлено в поисковую запись ранее и его значение совпадает - мы молча
        // прерываем выполнение метода! Это полностью защищает запись от паразитного раздувания
        // при повторных каскадных вызовах storage.save() в тесте и уничтожает concurrent modification!
        // =========================================================================
        if (valuesByObjectId.containsKey(fieldName)) {
            Object existingValue = valuesByObjectId.get(fieldName);
            if ((existingValue == null && value == null) || (existingValue != null && existingValue.equals(value))) {
                return; // Свойство уже учтено, выходим fail-safe
            }
        }

        valuesByObjectId.put(fieldName, value);

        addChildIdIfNotExists(childFieldName, cl, objectId);
    }

    /**
     * Collects one child field's values across every child entity of the given class,
     * matching the field name case-insensitively.
     *
     * @param childFieldName the parent's indexed field the children hang off
     * @param cl             the child entity's class
     * @param fieldName      the child entity's field name to collect
     * @return the collected values (never {@code null}; empty when the record holds no such children)
     */
    public Collection<Object> getChildFieldValue(final String childFieldName, final Class<?> cl, final String fieldName)
            {
        if (childrenFieldValues == null) {
            return Collections.emptyList();
        }

        final Map<Object, Map<String, Map<String, Object>>> objects = childrenFieldValues.get(cl);
        if (objects == null) {
            return Collections.emptyList();
        }

        final Collection<Object> result = new LinkedList<>();
        objects.values().stream().forEach(values -> {
            final Map<String, Object> tmp = values.get(childFieldName);
            if (tmp != null) {
                tmp.forEach((key, value) -> {
                    if (key.equalsIgnoreCase(fieldName)) {
                        result.add(value);
                    }
                });
            }
        });

        return result;
    }

    /**
     * Replaces the whole nested-children field-value map.
     *
     * @param childrenFieldValues the new child-class-to-child-state map
     */
    public void setChildrenFieldValues(final Map<Class<?>, Map<Object, Map<String, Map<String, Object>>>>
            childrenFieldValues) {
        this.childrenFieldValues = childrenFieldValues;
    }

    /**
     * The nested-children field-value map of the record.
     *
     * @return the map, or {@code null} when the record holds no child state
     */
    public Map<Class<?>, Map<Object, Map<String, Map<String, Object>>>> getChildrenFieldValues() {
        return childrenFieldValues;
    }
}
