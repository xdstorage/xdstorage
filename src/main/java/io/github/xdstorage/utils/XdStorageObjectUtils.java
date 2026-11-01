package io.github.xdstorage.utils;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.code.*;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.object.XdStorageObjectConverter;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

/**
 * The engine's central object helper facade: deep MVCC cloning, wrapper (proxy) creation and
 * recognition, entity-class resolution and the shared "simple type" predicate. Stateless
 * delegation over {@code XdStorageMvccGraphCloner}, {@code XdStorageProxyCompilationEngine} and
 * friends, so it is safe to call from any thread (per-object cloning is expected to happen
 * under the engine's own locking). Engine-internal — not part of the public API.
 */
public final class XdStorageObjectUtils {

    private XdStorageObjectUtils() {}

    /**
     * Deep-clones the object for the MVCC snapshot (see {@link XdStorageMvccGraphCloner});
     * answers {@code null} when {@code object} is {@code null} or the clone fails.
     *
     * @param <TObject> the static type of the object being cloned
     * @param object the source object, may be {@code null}
     * @return the deep clone, or {@code null} when cloning failed
     */
    public static <TObject> TObject cloneObject(final TObject object) {
        return XdStorageMvccGraphCloner.cloneObject(object);
    }

    /**
     * Copies the state of {@code reference} into {@code object}: identifiable objects delegate
     * to the converter, everything else to the same-type graph fill.
     *
     * @param reference the source instance whose state is copied
     * @param object the target instance receiving the state
     * @throws NullPointerException when either argument is {@code null}
     */
    public static void fillObject(final Object reference, final Object object) {
        if (object == null) throw new NullPointerException("Object cannot be null");
        if (reference == null) throw new NullPointerException("Object reference cannot be null");

        if (object instanceof XdStorageIdentifiableObject) {
            XdStorageObjectConverter.fillFrom(reference, (XdStorageIdentifiableObject) object);
        } else {
            XdStorageMvccGraphCloner.fillFromSameTypeObject(reference, object);
        }
    }

    /**
     * Probes whether the object can be wrapped in the generated unmodifiable wrapper:
     * {@code true} when the entity class has a REAL (non-dummy) generated wrapper — i.e.
     * the wrap will succeed — and {@code true} also on any probe failure ("assume
     * wrappable"). {@code false} only when the proxy engine answered with the DUMMY
     * wrapper (compilation failed). MSGHYGIENE (docs/tasks/2.1.0/39): the polarity was
     * re-verified against the generated {@code isDummyUnmodifiableWrapper} marker
     * (real wrapper = {@code false}) — the name reads true=wrappable and is CORRECT;
     * the confusing part was the double negation in the implementation, not the name.
     *
     * @param object the object to probe, must not be {@code null}
     * @return {@code true} when the entity class accepts an unmodifiable wrapper
     */
    public static boolean isWrappableObject(final Object object) {
        try {
            final Class<?> w = XdStorageProxyCompilationEngine.getClassUnmodifiableWrapper(getEntityClass(object.getClass()));
            return !((Boolean) XdStorageProxyCompilationEngine.getCheckingWrappableMethod(w).invoke(w));
        } catch (Exception e) { return true; }
    }

    /**
     * Deprecated id-observation wrapper; superseded by the simple/unmodifiable wrapper paths.
     *
     * @param object the entity instance to wrap, may be {@code null}
     * @return the observable wrapper, the object itself when already wrapped, or {@code null}
     *         when the object is {@code null} or the wrapper class cannot be instantiated
     */
    @Deprecated
    public static IXdStorageIdObservableWrapper wrapAsObservableObject(final Object object) {
        if (object == null) return null;
        if (object instanceof IXdStorageIdObservableWrapper) return (IXdStorageIdObservableWrapper) object;
        try {
            return (IXdStorageIdObservableWrapper) XdStorageProxyCompilationEngine.getClassObservableWrapper(object.getClass())
                    .getConstructor(object.getClass()).newInstance(object);
        } catch (Exception e) { return null; }
    }

    /**
     * Whether the object represents a lazy reference rather than a materialized entity:
     * asked of simple wrappers and (R-53) of the btree parser stubs marked
     * {@code IXdStorageReferenceMarked}; everything else answers {@code false}.
     *
     * @param object the object to test
     * @return {@code true} when the object is a not-yet-loaded reference
     */
    public static boolean isReference(final Object object) {
        if (object instanceof IXdStorageSimpleWrapper) return ((IXdStorageSimpleWrapper) object).isReference__();
        // R-53: парсерные стабы инфраструктуры B+Дерева (readReference -> newInstance) — не
        // обертки; опознаем их по маркер-интерфейсу, иначе холодная догрузка не срабатывала
        if (object instanceof IXdStorageReferenceMarked) return ((IXdStorageReferenceMarked) object).isReference();
        return false;
    }

    /**
     * Wraps the object into the generated simple wrapper (deferred single-object access); an
     * object the generated checker rejects stays unwrapped.
     *
     * @param <TObject> the static type of the wrapped object
     * @param object the entity instance to wrap, may be {@code null}
     * @param storage the owning storage handed to the wrapper
     * @param transaction the transaction context handed to the wrapper
     * @return the wrapper, the object itself when already a simple wrapper, or {@code null}
     *         when the object is {@code null} or not simply wrappable
     * @throws Exception when the wrapper class cannot be generated, looked up or instantiated
     */
    public static <TObject> IXdStorageSimpleWrapper wrapAsSimpleObject(final TObject object, final IXdStorage storage, final IXdStorageTransaction transaction) throws Exception {
        if (object == null) return null;
        if (object instanceof IXdStorageSimpleWrapper) return (IXdStorageSimpleWrapper) object;

        final Class<?> w = XdStorageProxyCompilationEngine.getClassSimpleWrapper(object.getClass());
        if ((Boolean) XdStorageProxyCompilationEngine.getCheckingSimpleWrappableMethod(w).invoke(w)) return null;
        return (IXdStorageSimpleWrapper) w.getConstructor(Object.class, object.getClass(), IXdStorage.class).newInstance(null, object, storage);
    }

    /**
     * Tests for the generated simple wrapper.
     *
     * @param object the object to test
     * @return {@code true} when the object is a generated simple wrapper
     */
    public static boolean isSimpleWrappedObject(final Object object) {
        return object instanceof IXdStorageSimpleWrapper;
    }

    /**
     * Whether the object can stand in for a stored entity by identity alone: an
     * {@code XdStorageIdentifiableObject} or a simple wrapper.
     *
     * @param obj the object to test
     * @return {@code true} when the object carries an id and can be persisted by reference
     */
    public static boolean isSimpleReference(final Object obj) {
        return obj instanceof io.github.xdstorage.object.XdStorageIdentifiableObject || obj instanceof io.github.xdstorage.code.IXdStorageSimpleWrapper;
    }

    /**
     * Convenience overload delegating to
     * {@link #wrapAsUnmodifiableObject(Object, Object, IXdStorage, IXdStorageTransaction)}
     * without a parent back-reference.
     *
     * @param <TObject> the static type of the wrapped object
     * @param object the object (or array/collection/map of objects) to wrap, may be {@code null}
     * @param storage the owning storage handed to the wrappers
     * @param transaction the transaction context handed to the wrappers
     * @return the wrapped object graph, or {@code null} when {@code object} is {@code null}
     *         or the wrapping is not possible
     */
    public static <TObject> TObject wrapAsUnmodifiableObject(final TObject object, final IXdStorage storage, final IXdStorageTransaction transaction) {
        return wrapAsUnmodifiableObject(null, object, storage, transaction);
    }

    /**
     * Recursively wraps the object graph into the generated unmodifiable (lazy-loading)
     * wrappers: simple values, enums and {@code Date}s pass through; arrays, collections and
     * maps are rebuilt element-wise; entities are unwrapped from their existing wrappers and
     * re-wrapped with the given parent, storage and transaction. Classes the generated checker
     * rejects (and any reflective failure) yield {@code null} for the whole call.
     *
     * @param <TObject> the static type of the wrapped object
     * @param parent the wrapped parent instance for back-references, may be {@code null}
     * @param object the object (or array/collection/map of objects) to wrap, may be {@code null}
     * @param storage the owning storage handed to the wrappers
     * @param transaction the transaction context handed to the wrappers
     * @return the wrapped object graph, or {@code null} when wrapping is not possible
     */
    @SuppressWarnings("unchecked")
    public static <TObject> TObject wrapAsUnmodifiableObject(final Object parent, TObject object, final IXdStorage storage, final IXdStorageTransaction transaction) {
        if (object == null) return null;
        final Class<?> cl = object.getClass();

        try {
            if (cl == Class.class || cl == Object.class || isSimpleType(cl, null)) return object;
            if (cl.isArray()) return wrapArrayAsUnmodifiableObjects(parent, object, storage, transaction);
            if (object instanceof Collection<?>) return (TObject) wrapCollectionAsUnmodifiableObjects(parent, (Collection<?>) object, storage, transaction);
            if (object instanceof Map<?, ?>) return (TObject) wrapMapAsUnmodifiableObjects(parent, (Map<?, ?>) object, storage, transaction);

            final Class<?> toWrapCl = (cl == XdStorageIdentifiableObject.class) ? ((XdStorageIdentifiableObject) object).getType() : (IXdStorageSimpleWrapper.class.isAssignableFrom(cl) ? getEntityClass(cl) : cl);
            if (IXdStorageSimpleWrapper.class.isAssignableFrom(cl)) object = getWrappedObjectOrSameObject(object);

            final Class<?> w = XdStorageProxyCompilationEngine.getClassUnmodifiableWrapper(toWrapCl);
            if ((Boolean) XdStorageProxyCompilationEngine.getCheckingWrappableMethod(w).invoke(w)) return null;
            return (TObject) w.getConstructor(Object.class, toWrapCl, IXdStorage.class, IXdStorageTransaction.class).newInstance(parent, object, storage, transaction);
        } catch (Exception e) { return null; }
    }

    private static <TObject> TObject wrapArrayAsUnmodifiableObjects(final Object parent, final TObject arr, final IXdStorage storage, final IXdStorageTransaction transaction) {
        final Object[] src = (Object[]) arr;
        final Object[] dst = (Object[]) Array.newInstance(arr.getClass().getComponentType(), src.length);
        for (int i = 0; i < src.length; ++i) dst[i] = wrapAsUnmodifiableObject(parent, src[i], storage, transaction);
        return (TObject) dst;
    }

    private static Collection<?> wrapCollectionAsUnmodifiableObjects(final Object parent, final Collection<?> src, final IXdStorage storage, final IXdStorageTransaction transaction) throws Exception {
        if (src.isEmpty()) return Collections.emptyList();
        final Collection<Object> clone = (Collection<Object>) src.getClass().newInstance();
        for (final Object object : src) {
            final Class<?> cl = object != null ? object.getClass() : null;
            if (object == null || cl.isEnum() || isSimpleType(cl, object)) clone.add(object);
            else if (cl == Date.class) clone.add(new Date(((Date) object).getTime()));
            else if (cl.isArray()) clone.add(wrapArrayAsUnmodifiableObjects(parent, object, storage, transaction));
            else if (object instanceof Collection<?>) clone.add(wrapCollectionAsUnmodifiableObjects(parent, (Collection<?>) object, storage, transaction));
            else if (object instanceof Map<?, ?>) clone.add(wrapMapAsUnmodifiableObjects(parent, (Map<?, ?>) object, storage, transaction));
            else clone.add(wrapAsUnmodifiableObject(parent, object, storage, transaction));
        }
        return clone;
    }

    private static Map<?, ?> wrapMapAsUnmodifiableObjects(final Object parent, final Map<?, ?> src, final IXdStorage storage, final IXdStorageTransaction transaction) throws Exception {
        if (src.isEmpty()) return Collections.emptyMap();
        final Map<Object, Object> clone = (Map<Object, Object>) src.getClass().newInstance();
        for (final Map.Entry<?, ?> entry : src.entrySet()) {
            final Object keytmp = entry.getKey(), key;
            Class<?> cl = keytmp != null ? keytmp.getClass() : null;
            if (keytmp == null || cl.isEnum() || isSimpleType(cl, keytmp)) key = keytmp;
            else if (cl == Date.class) key = new Date(((Date) keytmp).getTime());
            else key = wrapAsUnmodifiableObject(parent, keytmp, storage, transaction);

            final Object valuetmp = entry.getValue(), value;
            cl = valuetmp != null ? valuetmp.getClass() : null;
            if (valuetmp == null || cl.isEnum() || isSimpleType(cl, valuetmp)) value = valuetmp;
            else if (cl == Date.class) value = new Date(((Date) valuetmp).getTime());
            else if (cl.isArray()) value = wrapArrayAsUnmodifiableObjects(parent, valuetmp, storage, transaction);
            else if (valuetmp instanceof Collection<?>) value = wrapCollectionAsUnmodifiableObjects(parent, (Collection<?>) valuetmp, storage, transaction);
            else if (valuetmp instanceof Map<?, ?>) value = wrapMapAsUnmodifiableObjects(parent, (Map<?, ?>) valuetmp, storage, transaction);
            else value = wrapAsUnmodifiableObject(parent, valuetmp, storage, transaction);

            clone.put(key, value);
        }
        return clone;
    }

    /**
     * The engine's "simple type" predicate: primitives, {@code Number}, {@code Boolean},
     * {@code Character} and {@code String}. The second argument is accepted for call-site
     * symmetry with the other predicates and is not consulted.
     *
     * @param cl the class to test
     * @param object an optional instance of the class (ignored)
     * @return {@code true} when the class is stored inline without a nested record
     */
    public static boolean isSimpleType(final Class<?> cl, final Object object) {
        return cl.isPrimitive() || Number.class.isAssignableFrom(cl) || cl == Boolean.class || cl == Character.class || cl == String.class;
    }

    /**
     * Resolves a generated wrapper class to its entity class; plain entity classes pass through.
     *
     * @param cl the class to resolve
     * @return the entity class behind a wrapper, or {@code cl} itself when it is not a wrapper
     */
    public static Class<?> getEntityClass(final Class<?> cl) {
        return (IXdStorageIdObservableWrapper.class.isAssignableFrom(cl) || IXdStorageSimpleWrapper.class.isAssignableFrom(cl)) ? cl.getSuperclass() : cl;
    }

    /** Delegates to {@link XdStorageClassMetadataRegistry#getClassInfo(Class)} (builds metadata on first use).
     *
     * @param cl the entity class (a generated wrapper class is resolved to its entity)
     * @return the class's shared metadata record
     */
    public static XdStorageClassInfo getClassInfo(Class<?> cl) {
        return XdStorageClassMetadataRegistry.getClassInfo(cl);
    }

    /**
     * Reverse look-up of the unmodifiable wrapper: the entity class a generated wrapper was
     * compiled for, found by scanning the engine's wrapper cache.
     *
     * @param clWrapper the wrapper class to resolve
     * @return the wrapped entity class, or {@code null} when the class is not a known wrapper
     */
    public static Class<?> getMergedClass(final Class<?> clWrapper) {
        return XdStorageProxyCompilationEngine.getWrappedClass(clWrapper);
    }

    /**
     * Same contract as {@link #getMergedClass(Class)}: the entity class behind a generated
     * unmodifiable wrapper.
     *
     * @param clWrapper the wrapper class to resolve
     * @return the wrapped entity class, or {@code null} when the class is not a known wrapper
     */
    public static Class<?> getWrappedClass(final Class<?> clWrapper) {
        return XdStorageProxyCompilationEngine.getWrappedClass(clWrapper);
    }

    /**
     * The reflection field holding the wrapped entity inside a generated simple wrapper.
     *
     * @param clWrapper the wrapper class
     * @return the accessible {@code object} field, or {@code null} when the class has none
     */
    public static Field getSimpleWrapperObjectField(final Class<?> clWrapper) {
        return XdStorageWrapperFieldLocator.getSimpleWrapperObjectField(clWrapper);
    }

    /**
     * The reflection field holding the wrapped entity inside a generated unmodifiable wrapper.
     *
     * @param clWrapper the wrapper class
     * @return the accessible {@code object} field, or {@code null} when the class has none
     */
    public static Field getUnmodifiableWrapperObjectField(final Class<?> clWrapper) {
        return XdStorageWrapperFieldLocator.getUnmodifiableWrapperObjectField(clWrapper);
    }

    /**
     * The reflection field holding the wrapped entity inside a generated observable wrapper.
     *
     * @param clWrapper the wrapper class
     * @return the accessible {@code object} field, or {@code null} when the class has none
     */
    public static Field getObservableWrapperObjectField(final Class<?> clWrapper) {
        return XdStorageWrapperFieldLocator.getObservableWrapperObjectField(clWrapper);
    }

    /**
     * Peels every wrapper layer (simple, unmodifiable, observable) off the object, recursively,
     * until the plain entity instance is reached; non-wrapper objects pass through unchanged.
     *
     * @param <T> the static type of the unwrapped object
     * @param object the possibly wrapped object
     * @return the innermost entity instance
     * @throws RuntimeException when reflective field access on a wrapper fails
     */
    @SuppressWarnings("unchecked")
    public static <T> T getWrappedObjectOrSameObject(final Object object) {
        final Class<?> cl = object.getClass();
        try {
            if (IXdStorageSimpleWrapper.class.isAssignableFrom(cl)) return (T) getWrappedObjectOrSameObject(getSimpleWrapperObjectField(cl).get(object));
            if (IXdStorageUnmodifiableWrapper.class.isAssignableFrom(cl)) return (T) getWrappedObjectOrSameObject(getUnmodifiableWrapperObjectField(cl).get(object));
            if (IXdStorageIdObservableWrapper.class.isAssignableFrom(cl)) return (T) getWrappedObjectOrSameObject(getObservableWrapperObjectField(cl).get(object));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        return (T) object;
    }
}