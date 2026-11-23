package io.github.xdstorage.utils;

import io.github.xdstorage.code.IXdStorageUnmodifiableWrapper;
import io.github.xdstorage.code.IXdStorageSimpleWrapper;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.object.XdStorageObjectConverter;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.Map;

/**
 * The MVCC snapshot graph cloner: builds an in-memory deep copy of an object graph so that
 * transaction writes never mutate an object handed to (or read from) another transaction.
 * Cloning state lives in the thread-confined {@link XdStorageCloningContext}: cycles terminate,
 * repeated references stay single-instance, and the caller's wrapper objects are unwrapped to
 * the underlying entity first. Engine-internal — not part of the public API.
 */
public final class XdStorageMvccGraphCloner {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(XdStorageMvccGraphCloner.class);

    private XdStorageMvccGraphCloner() {}

    /**
     * Deep-clones one object for the transaction's snapshot: wrappers are unwrapped,
     * {@code XdStorageIdentifiableObject} holders are converted, everything else is copied
     * through {@link XdStoragePojoCloner} under the thread's visited map (the root call clears
     * it when done). A clone failure is logged and yields {@code null} — callers must treat a
     * {@code null} result as a data-loss signal, never write it into an object field.
     *
     * @param <TObject> the static type of the object being cloned
     * @param object the source object, may be {@code null}
     * @return the deep clone, or {@code null} when {@code object} is {@code null} or cloning failed
     */
    @SuppressWarnings("unchecked")
    public static <TObject> TObject cloneObject(final TObject object) {
        if (object == null) return null;

        final Map<Object, Object> visited = XdStorageCloningContext.get();

        // =========================================================================
        // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ (Разрыв зацикливаний на фазе входа фасада):
        // Опережающая проверка visited предотвращает дублирование корневых сущностей
        // при каскадных вызовах fillObject, убирая ошибку Expected: SourceData, Actual: null!
        // =========================================================================
        if (visited.containsKey(object)) {
            return (TObject) visited.get(object);
        }

        try {
            final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
            if (cl == Date.class) return (TObject) new Date(((Date) object).getTime());
            if (object instanceof XdStorageIdentifiableObject) return (TObject) XdStorageObjectConverter
                    .convertFrom((XdStorageIdentifiableObject) object);

            if (object instanceof IXdStorageUnmodifiableWrapper) {
                final Field field = XdStorageObjectUtils.getUnmodifiableWrapperObjectField(cl);
                field.setAccessible(true);
                final Object wrappedObject = field.get(object);
                if (wrappedObject instanceof XdStorageIdentifiableObject) return (TObject) XdStorageObjectConverter
                        .convertFrom((XdStorageIdentifiableObject) wrappedObject);
                return cloneObject((TObject) wrappedObject);
            }

            boolean isRootCall = XdStorageCloningContext.isRootCall();
            try {
                return (TObject) XdStoragePojoCloner.clonePojo(null, object, visited, true);
            } finally {
                if (isRootCall) XdStorageCloningContext.clear();
            }
        } catch (final Exception e) {
            // R-44: отказ клонирования ОБЯЗАН быть виден: молчаливый null раньше
            // записывался в поле-детей и терял данные (исчезновение вложенных объектов).
            log.error("cloneObject({}) failed — returning null (data-loss risk!)", object.getClass().getName(), e);
            return null;
        }
    }

    /**
     * Copies the state of {@code object} into the existing same-type instance {@code reference}
     * (field references rebinding through the thread's visited map), the in-place variant of
     * {@link #cloneObject(Object)} used to refresh a previously returned object. Identifiable
     * objects delegate to the converter; failures are swallowed (the reference keeps its prior
     * state) and the root call clears the visited map.
     *
     * @param reference the target instance receiving the copied state, same runtime type as {@code object}
     * @param object the source instance whose state is copied
     */
    public static void fillFromSameTypeObject(final Object reference, final Object object) {
        final Map<Object, Object> visited = XdStorageCloningContext.get();
        boolean isRootCall = XdStorageCloningContext.isRootCall();

        // Регистрируем базовую перевязку ссылок в контекст
        visited.put(object, reference);
        try {
            XdStoragePojoCloner.clonePojo(reference, object, visited);
        } catch (Exception ignored) {
        } finally {
            if (isRootCall) XdStorageCloningContext.clear();
        }
    }
}
