package io.github.xdstorage.utils;

/**
 * Immutable shape descriptor of one persistence field: the declared class, whether the field is
 * an array, collection or map, and (for parameterized declarations) the map key class and the
 * element/value class. Built by {@code XdStorageClassMetadataRegistry.buildFieldInfo}; safe to
 * share across threads. Engine-internal — not part of the public API.
 */
public class XdStorageObjectFieldInfo {

    private Class<?> clazz;

    private boolean isArray;

    private boolean isCollection;

    private boolean isMap;

    private Class<?> mapKeyClass;

    private Class<?> valueClass;

    /**
     * Creates the descriptor (registry-builder step).
     *
     * @param clazz the declared field class
     * @param isArray {@code true} when the field is array-typed
     * @param isCollection {@code true} when the field is {@code Collection}-typed
     * @param isMap {@code true} when the field is {@code Map}-typed
     * @param mapKeyClass the raw class of the map key type, {@code null} for non-map fields
     * @param valueClass the element/value class (component type for arrays, type argument for
     *                   collections and maps, the declared class otherwise)
     */
    public XdStorageObjectFieldInfo(final Class<?> clazz, final boolean isArray, final boolean isCollection, final boolean isMap, final Class<?> mapKeyClass, final Class<?> valueClass) {
        this.clazz = clazz;
        this.isArray = isArray;
        this.isCollection = isCollection;
        this.isMap = isMap;
        this.mapKeyClass = mapKeyClass;
        this.valueClass = valueClass;
    }



    /**
     * Returns the declared field class.
     *
     * @return the declared field class
     */
    public Class<?> getClazz() {
        return clazz;
    }

    /**
     * Returns {@code true} when the field is array-typed.
     *
     * @return {@code true} when the field is array-typed
     */
    public boolean isArray() {
        return isArray;
    }

    /**
     * Returns {@code true} when the field is {@code Collection}-typed.
     *
     * @return {@code true} when the field is {@code Collection}-typed
     */
    public boolean isCollection() {
        return isCollection;
    }

    /**
     * Returns {@code true} when the field is {@code Map}-typed.
     *
     * @return {@code true} when the field is {@code Map}-typed
     */
    public boolean isMap() {
        return isMap;
    }

    /**
     * Returns the raw class of the map key type, or {@code null} for non-map fields.
     *
     * @return the raw class of the map key type, or {@code null} for non-map fields
     */
    public Class<?> getMapKeyClass() {
        return mapKeyClass;
    }

    /**
     * Returns the element/value class (component type for arrays, type argument for collections/maps).
     *
     * @return the element/value class (component type for arrays, type argument for collections/maps)
     */
    public Class<?> getValueClass() {
        return valueClass;
    }

    @Override
    public String toString() {
        return "XdStorageObjectFieldInfo{" +
                "clazz=" + clazz +
                '}';
    }
}
