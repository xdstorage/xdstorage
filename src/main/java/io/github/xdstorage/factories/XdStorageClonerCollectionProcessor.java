package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Выделенный stateless-компонент декомпозиции (Поинт В).
 * Инкапсулирует глубокое копирование структур данных Collection, List и Map.
 */
public class XdStorageClonerCollectionProcessor {

    /** Instantiation-only utility class — no instances. */
    public XdStorageClonerCollectionProcessor() {
    }

    /**
     * Deep-copies the given collection into a fresh collection of the same runtime class,
     * leaving nulls/enums/simple values as-is, copying {@code Date} values by time,
     * recursing into nested arrays/collections/maps, and replacing entity-typed elements
     * with the transaction's reference wrappers.
     *
     * @param src         the collection to clone and wrap
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the cloned graph belongs to
     * @param cloner      the orchestrator used to wrap entity-typed elements
     * @return the cloned collection of the same runtime class
     * @throws Exception if the collection's runtime class cannot be instantiated
     */
    @SuppressWarnings("unchecked")
    public static Object cloneAndWrapCollection(final Collection<?> src, final IXdStorage storage, final IXdStorageTransaction transaction, final XdStorageSmartCloner cloner) throws Exception {
        // SILENTFAIL ladder (docs/tasks/2.1.0/35): non-instantiable containers (immutable/
        // wrapper classes like Collections$EmptyList) fall back to the closest mutable
        // semantic equivalent instead of failing the clone.
        final Collection<Object> clone = io.github.xdstorage.utils.XdStorageCollectionCloner.newCollectionInstance((Collection<Object>) src);
        for (final Object object : src) {
            final Class<?> cl = object != null ? XdStorageObjectUtils.getEntityClass(object.getClass()) : null;
            if (object == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, object)) {
                clone.add(object);
            } else if (cl == Date.class) {
                clone.add(new Date(((Date) object).getTime()));
            } else if (cl.isArray()) {
                clone.add(XdStorageClonerArrayProcessor.cloneAndWrapArray(object, storage, transaction, cloner));
            } else if (object instanceof Collection<?>) {
                clone.add(cloneAndWrapCollection((Collection<?>) object, storage, transaction, cloner));
            } else if (object instanceof Map<?, ?>) {
                clone.add(cloneAndWrapMap((Map<?, ?>) object, storage, transaction, cloner));
            } else {
                clone.add(cloner.internalCloneAsReferenceAndWrapObject(object, storage, transaction));
            }
        }
        return clone;
    }

    /**
     * Deep-copies the given map into a fresh map of the same runtime class: keys and
     * values are processed like collection elements — entity-typed keys and values are
     * replaced by the transaction's reference wrappers, nested arrays/collections/maps are
     * recursed into, {@code Date} values are copied by time.
     *
     * @param src         the map to clone and wrap
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the cloned graph belongs to
     * @param cloner      the orchestrator used to wrap entity-typed keys and values
     * @return the cloned map of the same runtime class
     * @throws Exception if the map's runtime class cannot be instantiated
     */
    @SuppressWarnings("unchecked")
    public static Object cloneAndWrapMap(final Map<?, ?> src, final IXdStorage storage, final IXdStorageTransaction transaction, final XdStorageSmartCloner cloner) throws Exception {
        final Map<Object, Object> clone = io.github.xdstorage.utils.XdStorageCollectionCloner.newMapInstance((Map<Object, Object>) src);
        for (final Map.Entry<?, ?> entry : src.entrySet()) {
            final Object keyvalue = entry.getKey(), key;
            Class<?> clValue = keyvalue != null ? XdStorageObjectUtils.getEntityClass(keyvalue.getClass()) : null;
            if (keyvalue == null || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue, keyvalue)) {
                key = keyvalue;
            } else if (clValue == Date.class) {
                key = new Date(((Date) keyvalue).getTime());
            } else {
                key = cloner.internalCloneAsReferenceAndWrapObject(keyvalue, storage, transaction);
            }

            Object value = entry.getValue();
            clValue = value != null ? XdStorageObjectUtils.getEntityClass(value.getClass()) : null;
            if (value == null || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue, value)) {
                // Ничего не делаем
            } else if (clValue == Date.class) {
                value = new Date(((Date) value).getTime());
            } else if (clValue.isArray()) {
                value = XdStorageClonerArrayProcessor.cloneAndWrapArray(value, storage, transaction, cloner);
            } else if (value instanceof Collection<?>) {
                value = cloneAndWrapCollection((Collection<?>) value, storage, transaction, cloner);
            } else if (value instanceof Map<?, ?>) {
                value = cloneAndWrapMap((Map<?, ?>) value, storage, transaction, cloner);
            } else {
                value = cloner.internalCloneAsReferenceAndWrapObject(value, storage, transaction);
            }
            clone.put(key, value);
        }
        return clone;
    }

    /**
     * Reverse direction: deep-copies the given collection into a plain collection of the
     * same runtime class, unwrapping reference wrappers back into POJOs (recursing into
     * nested arrays/collections/maps).
     *
     * @param src    the (possibly wrapped) collection to unwrap and clone
     * @param cloner the orchestrator used to unwrap entity-typed elements
     * @return the plain clone of the same runtime class
     * @throws Exception if the collection's runtime class cannot be instantiated
     */
    @SuppressWarnings("unchecked")
    public static Object unwrapAndCloneCollection(final Collection<?> src, final XdStorageSmartCloner cloner) throws Exception {
        // SILENTFAIL ladder (docs/tasks/2.1.0/35): non-instantiable containers (immutable/
        // wrapper classes like Collections$EmptyList) fall back to the closest mutable
        // semantic equivalent instead of failing the clone.
        final Collection<Object> clone = io.github.xdstorage.utils.XdStorageCollectionCloner.newCollectionInstance((Collection<Object>) src);
        for (final Object object : src) {
            final Class<?> cl = object != null ? XdStorageObjectUtils.getEntityClass(object.getClass()) : null;
            if (object == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, object)) {
                clone.add(object);
            } else if (cl == Date.class) {
                clone.add(new Date(((Date) object).getTime()));
            } else if (cl.isArray()) {
                clone.add(XdStorageClonerArrayProcessor.unwrapAndCloneArray(object, cloner));
            } else if (object instanceof Collection<?>) {
                clone.add(unwrapAndCloneCollection((Collection<?>) object, cloner));
            } else if (object instanceof Map<?, ?>) {
                clone.add(unwrapAndCloneMap((Map<?, ?>) object, cloner));
            } else {
                clone.add(cloner.internalUnwrapAndCloneAsReference(object));
            }
        }
        return clone;
    }

    /**
     * Reverse direction: deep-copies the given map into a plain map of the same runtime
     * class, unwrapping reference-wrapped keys and values back into POJOs (recursing into
     * nested arrays/collections/maps); entries whose unwrapped key or value is
     * {@code null} are dropped.
     *
     * @param src    the (possibly wrapped) map to unwrap and clone
     * @param cloner the orchestrator used to unwrap entity-typed keys and values
     * @return the plain clone of the same runtime class
     * @throws Exception if the map's runtime class cannot be instantiated
     */
    @SuppressWarnings("unchecked")
    public static Object unwrapAndCloneMap(final Map<?, ?> src, final XdStorageSmartCloner cloner) throws Exception {
        final Map<Object, Object> clone = io.github.xdstorage.utils.XdStorageCollectionCloner.newMapInstance((Map<Object, Object>) src);
        for (final Map.Entry<?, ?> entry : src.entrySet()) {
            final Object keyvalue = entry.getKey(), key;
            Class<?> clValue = keyvalue != null ? XdStorageObjectUtils.getEntityClass(keyvalue.getClass()) : null;
            if (keyvalue == null || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue, keyvalue)) {
                key = keyvalue;
            } else if (clValue == Date.class) {
                key = new Date(((Date) keyvalue).getTime());
            } else {
                key = cloner.internalUnwrapAndCloneAsReference(keyvalue);
            }

            Object value = entry.getValue();
            clValue = value != null ? XdStorageObjectUtils.getEntityClass(value.getClass()) : null;
            if (value == null || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue, value)) {
                // Ничего не делаем
            } else if (clValue == Date.class) {
                value = new Date(((Date) value).getTime());
            } else if (clValue.isArray()) {
                value = XdStorageClonerArrayProcessor.unwrapAndCloneArray(value, cloner);
            } else if (value instanceof Collection<?>) {
                value = unwrapAndCloneCollection((Collection<?>) value, cloner);
            } else if (value instanceof Map<?, ?>) {
                value = unwrapAndCloneMap((Map<?, ?>) value, cloner);
            } else {
                value = cloner.internalUnwrapAndCloneAsReference(value);
            }

            if (key != null && value != null) {
                clone.put(key, value);
            }
        }
        return clone;
    }
}
