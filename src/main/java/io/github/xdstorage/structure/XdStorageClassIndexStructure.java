package io.github.xdstorage.structure;

import io.github.xdstorage.annotations.XdStorageObjectId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The persisted structure record of one search index of a stored class: which fields the
 * index covers and, for the nested (child) object fields it traverses, the property types
 * of each child class. Captured alongside {@code XdStorageClassStructure} so the structure
 * manager can detect that an index definition changed between runs and schedule its
 * re-indexing migration.
 *
 * <p>Instances are built, mutated and read by the structure manager under its per-class
 * lock; the mutators are not synchronized on their own. Engine-internal — not part of the
 * public API.</p>
 */
public class XdStorageClassIndexStructure {

    @XdStorageObjectId
    private XdStorageClassIndexStructureId id;

    private Map<String, Class<?>> fields;

    private Map<Class<?>, Map<String, Class<?>>> childrenFields;

    /**
     * Creates an empty index structure record.
     */
    public XdStorageClassIndexStructure() {
    }

    /**
     * Returns the composite identifier of this index structure record.
     *
     * @return the composite identifier (owning class and index name) of this record
     */
    public XdStorageClassIndexStructureId getId() {
        return id;
    }

    /**
     * Sets the composite identifier of this index structure record.
     *
     * @param id the composite identifier (owning class and index name) of this record
     */
    public void setId(XdStorageClassIndexStructureId id) {
        this.id = id;
    }

    /**
     * Returns the fields this index covers.
     *
     * @return the indexed field names mapped to their declared types,
     *         or {@code null} when no field has been added yet
     */
    public Map<String, Class<?>> getFields() {
        return fields;
    }

    /**
     * Replaces the indexed field map of the snapshot.
     *
     * @param fields the indexed field names mapped to their declared types
     */
    public void setFields(final Map<String, Class<?>> fields) {
        this.fields = fields;
    }

    /**
     * Adds one indexed field to the snapshot, creating the field map on first use and
     * overwriting the type of an already present name.
     *
     * @param name the indexed field name
     * @param cl   the declared type of the field
     */
    public void addField(final String name, final Class<?> cl) {
        if (fields == null) {
            fields = new HashMap<>();
        }
        fields.put(name, cl);
    }

    /**
     * Returns the indexed properties of the nested child object classes.
     *
     * @return the child object classes mapped to their indexed property names and property
     *         types, or {@code null} when no child field has been added yet
     */
    public Map<Class<?>, Map<String, Class<?>>> getChildrenFields() {
        return childrenFields;
    }

    /**
     * Replaces the child fields map of the snapshot.
     *
     * @param childrenFields the child object classes mapped to their indexed property
     *                       names and property types
     */
    public void setChildrenFields(final Map<Class<?>, Map<String, Class<?>>> childrenFields) {
        this.childrenFields = childrenFields;
    }

    /**
     * Adds one indexed property of a nested (child) object field to the snapshot, creating
     * the needed maps on first use.
     *
     * @param cl      the child object class the property belongs to
     * @param name    the property name within the child object
     * @param valueCl the declared type of the property
     */
    public void addChildField(final Class<?> cl, final String name, final Class<?> valueCl) {
        if (childrenFields == null) {
            childrenFields = new HashMap<>();
        }

        Map<String, Class<?>> fields = childrenFields.get(cl);
        if (fields == null) {
            childrenFields.put(cl, fields = new HashMap<>());
        }

        fields.put(name, valueCl);
    }

    /**
     * Two index structure records are equal when their identifiers, indexed fields and
     * child fields are equal — this is the comparison the structure manager uses to detect
     * that an index definition changed.
     *
     * @param o the object to compare with
     * @return {@code true} when {@code o} is a {@code XdStorageClassIndexStructure} with
     *         equal id, fields and children fields
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        XdStorageClassIndexStructure that = (XdStorageClassIndexStructure) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(fields, that.fields) &&
                Objects.equals(childrenFields, that.childrenFields);
    }

    // STRUCTFIX (docs/tasks/2.1.0/36): consistent hashCode for the overridden equals.
    @Override
    public int hashCode() {
        return Objects.hash(id, fields, childrenFields);
    }
}
