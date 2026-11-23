package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Выделенный stateless-компонент декомпозиции (Поинт В).
 * Инкапсулирует рекурсивную обработку и глубокое копирование массивов Java.
 */
public class XdStorageClonerArrayProcessor {

    /** Instantiation-only utility class — no instances. */
    public XdStorageClonerArrayProcessor() {
    }

    /**
     * Deep-copies the given array, leaving primitives/enums/simple values as-is, copying
     * {@code Date} values by time, recursing into nested arrays/collections/maps, and
     * replacing entity-typed elements with the transaction's reference wrappers.
     *
     * @param src         the array to clone and wrap (must be an array)
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the cloned graph belongs to
     * @param cloner      the orchestrator used to wrap entity-typed elements
     * @return the cloned array of the same component type and length
     * @throws Exception if reflective access to the array elements fails
     */
    public static Object cloneAndWrapArray(final Object src, final IXdStorage storage, final IXdStorageTransaction
            transaction, final XdStorageSmartCloner cloner) throws Exception {
        final int length = Array.getLength(src);
        final Object clone = Array.newInstance(src.getClass().getComponentType(), length);
        for (int i = 0; i < length; ++i) {
            Object value = Array.get(src, i);
            final Class<?> clValue = value != null ? XdStorageObjectUtils.getEntityClass(value.getClass()) : null;
            if (value == null || clValue.isPrimitive() || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue,
                    value)) {
                // Ничего не делаем для простых типов
            } else if (clValue == Date.class) {
                value = new Date(((Date) value).getTime());
            } else if (clValue.isArray()) {
                value = cloneAndWrapArray(value, storage, transaction, cloner);
            } else if (value instanceof Collection<?>) {
                value = XdStorageClonerCollectionProcessor.cloneAndWrapCollection((Collection<?>) value, storage,
                        transaction, cloner);
            } else if (value instanceof Map<?, ?>) {
                value = XdStorageClonerCollectionProcessor.cloneAndWrapMap((Map<?, ?>) value, storage, transaction,
                        cloner);
            } else {
                value = cloner.internalCloneAsReferenceAndWrapObject(value, storage, transaction);
            }
            Array.set(clone, i, value);
        }
        return clone;
    }

    /**
     * Reverse direction: deep-copies the given array, unwrapping reference wrappers back
     * into plain POJOs (recursing into nested arrays/collections/maps); primitives, enums,
     * simple values and {@code Date}s are handled as in the wrap direction.
     *
     * @param src    the (possibly wrapped) array to unwrap and clone (must be an array)
     * @param cloner the orchestrator used to unwrap entity-typed elements
     * @return the plain clone of the same component type and length
     * @throws Exception if reflective access to the array elements fails
     */
    public static Object unwrapAndCloneArray(final Object src, final XdStorageSmartCloner cloner) throws Exception {
        final int length = Array.getLength(src);
        final Object clone = Array.newInstance(src.getClass().getComponentType(), length);
        for (int i = 0; i < length; ++i) {
            Object value = Array.get(src, i);
            final Class<?> clValue = value != null ? XdStorageObjectUtils.getEntityClass(value.getClass()) : null;
            if (value == null || clValue.isPrimitive() || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue,
                    value)) {
                // Ничего не делаем
            } else if (clValue == Date.class) {
                value = new Date(((Date) value).getTime());
            } else if (clValue.isArray()) {
                value = unwrapAndCloneArray(value, cloner);
            } else if (value instanceof Collection<?>) {
                value = XdStorageClonerCollectionProcessor.unwrapAndCloneCollection((Collection<?>) value, cloner);
            } else if (value instanceof Map<?, ?>) {
                value = XdStorageClonerCollectionProcessor.unwrapAndCloneMap((Map<?, ?>) value, cloner);
            } else {
                value = cloner.internalUnwrapAndCloneAsReference(value);
            }
            Array.set(clone, i, value);
        }
        return clone;
    }
}
