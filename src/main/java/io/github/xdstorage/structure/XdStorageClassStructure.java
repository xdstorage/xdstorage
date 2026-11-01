package io.github.xdstorage.structure;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The persisted structure record of one stored class — the engine's snapshot of the class's
 * storage policy, stored field types and search index definitions as of the last structure
 * check. Kept in the dedicated {@code XdStorageClassStructure} resource; the structure
 * manager rebuilds the snapshot from the class metadata and compares it with the persisted
 * one, so a difference means the class schema evolved and the registered migration scripts
 * must run.
 *
 * <p>Instances are built, mutated and read by the structure manager under its per-class
 * lock; the mutators are not synchronized on their own. Engine-internal — not part of the
 * public API.</p>
 */
public class XdStorageClassStructure {

    // BUG-B (docs/tasks/2.1.0/16): the record's persisted identity is the class NAME (the
    // FQCN), NOT the Class object. Two live generations of one FQCN (an application
    // upgrade) are ONE stored class with ONE structure record; a Class-object identity
    // keyed the record on whichever generation instance the registry resolved at write
    // time, so the next generation's read missed it and the evolution was invisible.
    @XdStorageObjectId
    private String type;

    private XdStoragePolicy policy;

    private Map<String, Class<?>> fields;

    private Map<String, XdStorageClassIndexStructure> indexes;

    /**
     * Creates an empty structure record.
     */
    public XdStorageClassStructure() {
    }

    /**
     * Returns the FQCN of the stored class whose schema this record snapshots.
     *
     * @return the fully-qualified name of the described stored class
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the FQCN of the stored class whose schema this record snapshots.
     *
     * @param type the fully-qualified name of the described stored class
     */
    public void setType(final String type) {
        this.type = type;
    }

    /**
     * Returns the storage policy captured for the class.
     *
     * @return the storage policy of the class, or {@code null} when not set yet
     */
    public XdStoragePolicy getPolicy() {
        return policy;
    }

    /**
     * Sets the storage policy captured for the class.
     *
     * @param policy the storage policy of the class
     */
    public void setPolicy(final XdStoragePolicy policy) {
        this.policy = policy;
    }

    /**
     * Returns the stored fields of the class snapshot.
     *
     * @return the stored field names mapped to their declared types,
     *         or {@code null} when no field has been added yet
     */
    public Map<String, Class<?>> getFields() {
        return fields;
    }

    /**
     * Replaces the stored field map of the snapshot.
     *
     * @param fields the stored field names mapped to their declared types
     */
    public void setFields(final Map<String, Class<?>> fields) {
        this.fields = fields;
    }

    /**
     * Adds one stored field to the snapshot, creating the field map on first use and
     * overwriting the type of an already present name.
     *
     * @param name the stored field name
     * @param cl   the declared type of the field
     */
    public void addField(final String name, final Class<?> cl) {
        if (fields == null) {
            fields = new HashMap<>();
        }
        fields.put(name, cl);
    }

    /**
     * Returns the search index snapshots of the class.
     *
     * @return the search index structures of the class keyed by index name,
     *         or {@code null} when no index has been added yet
     */
    public Map<String, XdStorageClassIndexStructure> getIndexes() {
        return indexes;
    }

    /**
     * Replaces the search index map of the snapshot.
     *
     * @param indexes the search index structures of the class keyed by index name
     */
    public void setIndexes(final Map<String, XdStorageClassIndexStructure> indexes) {
        this.indexes = indexes;
    }

    /**
     * Adds one search index to the snapshot, creating the index map on first use and
     * overwriting the structure of an already present index name.
     *
     * @param name  the search index name
     * @param index the structure snapshot of the index
     */
    public void addIndex(final String name, final XdStorageClassIndexStructure index) {
        if (indexes == null) {
            indexes = new HashMap<>();
        }
        indexes.put(name, index);
    }

    /**
     * Two structure records are equal when they describe the same class with the same
     * policy, fields and index structures — this is the comparison the structure manager
     * uses to detect schema evolution.
     *
     * @param o the object to compare with
     * @return {@code true} when {@code o} is a {@code XdStorageClassStructure} with equal
     *         type, policy, fields and indexes
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        XdStorageClassStructure that = (XdStorageClassStructure) o;
        return Objects.equals(type, that.type) &&
                policy == that.policy &&
                Objects.equals(fields, that.fields) &&
                Objects.equals(indexes, that.indexes);
    }

    // STRUCTFIX (docs/tasks/2.1.0/36): equals was overridden without hashCode — both
    // structure records land in hash-based collections; inconsistent lookup behavior.
    @Override
    public int hashCode() {
        return Objects.hash(type, policy, fields, indexes);
    }
}
