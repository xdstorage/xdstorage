package io.github.xdstorage.utils;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.search.XdStorageSearchIndex;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-entity metadata record of the engine: storage policy, id-index type and id field,
 * the scanned persistence fields and the declared search indexes, plus the
 * {@code @XdStorageAutoSave} cascade fields. Built once per class by
 * {@link XdStorageClassMetadataRegistry} under its registry lock and then used read-only from
 * the engine runtime; the public setters exist for the builder and are not thread-safe.
 * Engine-internal — not part of the public API.
 */
public class XdStorageClassInfo {

    private Class<?> clazz;

    private XdStoragePolicy policy;

    private XdStorageIndexType indexType;

    private int indexFillingValue;

    private XdStorageObjectIdField idField;

    private Map<String, XdStorageObjectField> fields = new LinkedHashMap<>();

    private Map<String, XdStorageSearchIndex> indexes = new HashMap<>();

    // AUTOSAVE (docs/tasks/2.1.0/22): the @XdStorageAutoSave annotated fields of this class,
    // built by the metadata registry from the scanned fields (inherited included)
    private final java.util.List<XdStorageAutoSaveFieldInfo> autoSaveFields = new java.util.ArrayList<>();

    /** Creates an empty metadata record for the registry builder to populate. */
    public XdStorageClassInfo() {
    }

    /**
     * Creates a metadata record for the given class; the remaining attributes are filled in
     * by the registry builder.
     *
     * @param clazz the entity class this metadata describes
     */
    public XdStorageClassInfo(Class<?> clazz) {
        this.clazz = clazz;
    }

    /**
     * Returns the entity class this metadata describes (may be {@code null} until set).
     *
     * @return the entity class this metadata describes (may be {@code null} until set)
     */
    public Class<?> getClazz() {
        return clazz;
    }

    void setClazz(final Class<?> clazz) {
        this.clazz = clazz;
    }

    /**
     * Returns the storage policy in effect (embedded vs independent resource); may be {@code null} until built.
     *
     * @return the storage policy in effect (embedded vs independent resource); may be {@code null} until built
     */
    public XdStoragePolicy getPolicy() {
        return policy;
    }

    void setPolicy(XdStoragePolicy policy) {
        this.policy = policy;
    }

    /**
     * Returns the id-index structure type (Hash unless annotated otherwise); may be {@code null} until built.
     *
     * @return the id-index structure type (Hash unless annotated otherwise); may be {@code null} until built
     */
    public XdStorageIndexType getIndexType() {
        return indexType;
    }

    /**
     * Sets the id-index structure type (registry-builder step).
     *
     * @param indexType the id-index structure type
     */
    public void setIndexType(XdStorageIndexType indexType) {
        this.indexType = indexType;
    }

    /**
     * Returns the id-index fill factor configured via {@code @XdStorageObjectIdIndexType} (0 until built).
     *
     * @return the id-index fill factor configured via {@code @XdStorageObjectIdIndexType} (0 until built)
     */
    public int getIndexFillingValue() {
        return indexFillingValue;
    }

    /**
     * Sets the id-index fill factor (registry-builder step).
     *
     * @param indexFillingValue the fill factor percentage
     */
    public void setIndexFillingValue(int indexFillingValue) {
        this.indexFillingValue = indexFillingValue;
    }

    /**
     * Returns the id field accessor, or {@code null} when the class carries no {@code @XdStorageObjectId} field.
     *
     * @return the id field accessor, or {@code null} when the class carries no {@code @XdStorageObjectId} field
     */
    public XdStorageObjectIdField getIdField() {
        return idField;
    }

    public void setIdField(XdStorageObjectIdField idField) {
        this.idField = idField;
    }

    /**
     * Returns the {@code @XdStorageAutoSave} cascade fields (in scan order); never {@code null}, possibly empty.
     *
     * @return the {@code @XdStorageAutoSave} cascade fields (in scan order); never {@code null}, possibly empty
     */
    public java.util.List<XdStorageAutoSaveFieldInfo> getAutoSaveFields() {
        return autoSaveFields;
    }

    public void addAutoSaveField(final XdStorageAutoSaveFieldInfo info) {
        autoSaveFields.add(info);
    }

    /**
     * Returns the persistence fields by field name (getters with a backing field and a setter); never {@code null}.
     *
     * @return the persistence fields by field name (getters with a backing field and a setter); never {@code null}
     */
    public Map<String, XdStorageObjectField> getFields() {
        return fields;
    }

    void setFields(Map<String, XdStorageObjectField> fields) {
        this.fields = fields;
    }

    /**
     * Returns the declared search indexes by index name; never {@code null}, possibly empty.
     *
     * @return the declared search indexes by index name; never {@code null}, possibly empty
     */
    public Map<String, XdStorageSearchIndex> getIndexes() {
        return indexes;
    }

    void setIndexes(Map<String, XdStorageSearchIndex> indexes) {
        this.indexes = indexes;
    }
}
