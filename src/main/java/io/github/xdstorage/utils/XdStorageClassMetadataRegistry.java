package io.github.xdstorage.utils;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.search.XdStorageSearchIndex;
import io.github.xdstorage.code.IXdStorageUnmodifiableWrapper;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The JVM-wide registry of per-entity metadata ({@link XdStorageClassInfo}) and the class
 * resolver used by the serialization layer. Metadata is built lazily per class: lookups are
 * lock-free against a {@link ConcurrentHashMap}, the first build of a class runs under a
 * global {@link java.util.concurrent.locks.ReentrantLock} (double-checked). Engine-internal —
 * not part of the public API.
 */
public final class XdStorageClassMetadataRegistry {
    private static final Map<Class<?>, XdStorageClassInfo> classesInfo = new ConcurrentHashMap<>();
    private static final ReentrantLock REGISTRY_LOCK = new ReentrantLock();

    private XdStorageClassMetadataRegistry() {}

    /**
     * BUG-A (docs/tasks/2.1.0/15): Class.forName-compatible resolver for the serialization
     * layer — the registry first (an already-registered class is returned as the EXACT
     * instance the engine uses, whatever classloader loaded it — re-deriving the identity
     * through a different loader degraded journal/data replay into null-keyed poison),
     * then TCCL, then the caller's loader. Throws ClassNotFoundException with the original
     * semantics so the readers' error paths stay intact.
     *
     * @param name the fully-qualified binary class name to resolve
     * @return the resolved {@link Class}
     * @throws ClassNotFoundException when no registered class matches and every loader path fails
     */
    public static Class<?> resolveOrThrow(final String name) throws ClassNotFoundException {
        // REGISTRY FIRST: the exact Class instance this storage already uses. Trying loaders
        // first can hit a partial view (a factory loader finding the .class bytes but not the
        // annotations they reference → NoClassDefFoundError at defineClass) — the identity of
        // an already-registered class must never be re-derived through a different loader.
        final Class<?> loaded = findLoadedClassByName(name);
        if (loaded != null) {
            return loaded;
        }
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (final ClassNotFoundException ignored) {
        }
        return Class.forName(name);
    }

    /**
     * Best-effort resolver: the registry first, then TCCL, then the caller's loader.
     *
     * @param name the fully-qualified binary class name to resolve
     * @return the resolved {@link Class}, or {@code null} when no registered or loadable class matches
     */
    public static Class<?> findLoadedClassByName(final String name) {
        if (name == null) {
            return null;
        }
        for (final Class<?> cl : classesInfo.keySet()) {
            if (cl.getName().equals(name)) {
                return cl;
            }
        }
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (final Throwable ignored) {
        }
        try {
            return Class.forName(name);
        } catch (final Throwable ignored) {
        }
        return null;
    }

    /**
     * Returns the cached metadata for the class, building it on first use: policy, id-index
     * information, id field, scanned fields, search indexes and the autosave cascade fields.
     *
     * @param cl the entity class (a generated wrapper class is resolved to its entity)
     * @return the metadata, shared JVM-wide for the class
     */
    public static XdStorageClassInfo getClassInfo(Class<?> cl) {
        cl = XdStorageObjectUtils.getEntityClass(cl);
        XdStorageClassInfo info = classesInfo.get(cl);
        if (info != null) return info;

        REGISTRY_LOCK.lock();
        try {
            info = classesInfo.get(cl);
            if (info != null) return info;

            final XdStorageClassInfo newInfo = new XdStorageClassInfo();
            newInfo.setClazz(cl);
            newInfo.setPolicy(getClassPolicy(cl));
            setIndexInformation(newInfo, cl);
            newInfo.setIdField(getClassIdField(cl));

            final Map<String, XdStorageObjectField> fields = XdStorageReflectionFieldScanner.scanFields(cl);
            newInfo.setFields(fields);
            newInfo.setIndexes(XdStorageClassIndexBuilder.buildIndexes(cl, fields));
            // AUTOSAVE (docs/tasks/2.1.0/22): collect the @XdStorageAutoSave annotated fields
            // (inherited included — the scanner walks superclasses) into the class info
            for (final XdStorageObjectField field : fields.values()) {
                if (XdStorageAutoSaveFieldInfo.isAutoSaveField(field.field)) {
                    newInfo.addAutoSaveField(XdStorageAutoSaveFieldInfo.of(field));
                }
            }

            classesInfo.put(cl, newInfo);
            return newInfo;
        } finally {
            REGISTRY_LOCK.unlock();
        }
    }

    /**
     * Resolves the storage policy of a class: the {@code @XdStorageObjectPolicy} annotation on
     * the class itself or the nearest superclass carrying one, defaulting to the embedded
     * {@code StoreWithParentObject}; primitives are always embedded.
     *
     * @param cl the class to resolve (may be an {@code @XdStorageObjectId}-less type)
     * @return the policy in effect, never {@code null}
     */
    public static XdStoragePolicy getClassPolicy(final Class<?> cl) {
        if (cl.isPrimitive()) return XdStoragePolicy.StoreWithParentObject;
        XdStoragePolicy policy = null;
        for (Class<?> clazz = cl; !clazz.equals(Object.class); clazz = clazz.getSuperclass()) {
            if (clazz.isAnnotationPresent(XdStorageObjectPolicy.class)) {
                policy = clazz.getAnnotation(XdStorageObjectPolicy.class).policy();
                break;
            }
        }
        return policy != null ? policy : XdStoragePolicy.StoreWithParentObject;
    }

    /**
     * Locates the {@code @XdStorageObjectId}-annotated field of the class (superclass hierarchy
     * included, wrappers unwrapped) and binds it with its getter and setter. Wrapper classes are
     * resolved to their entity class first; any reflective failure yields {@code null}.
     *
     * @param cl the class to inspect
     * @return the id field accessor, or {@code null} when the class has no id field
     */
    public static XdStorageObjectIdField getClassIdField(final Class<?> cl) {
        if (IXdStorageUnmodifiableWrapper.class.isAssignableFrom(cl)) {
            return getClassIdField(XdStorageObjectUtils.getWrappedClass(cl));
        }
        try {
            for (Class<?> clazz = cl; !clazz.equals(Object.class); clazz = clazz.getSuperclass()) {
                for (final Field field : clazz.getDeclaredFields()) {
                    if (field.isAnnotationPresent(XdStorageObjectId.class)) {
                        final String name = field.getName();
                        final String suffix = name.substring(0, 1).toUpperCase() + name.substring(1);
                        final Method getter = cl.getMethod("get" + suffix);
                        final Method setter = cl.getMethod("set" + suffix, getter.getReturnType());
                        return new XdStorageObjectIdField(field, setter, getter, buildFieldInfo(field, getter));
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Scans the class's persistence fields fresh from reflection (superclass hierarchy
     * included); no registry caching on this path.
     *
     * @param cl the class to scan
     * @return the fields by field name, possibly empty
     */
    public static Map<String, XdStorageObjectField> getClassFields(final Class<?> cl) {
        return XdStorageReflectionFieldScanner.scanFields(cl);
    }

    /**
     * Derives the shape information of one field from the declared types: array, collection or
     * map flag, and — for parameterized declarations read through the getter's generic return
     * type — the map key class and the element/value class.
     *
     * @param field the reflection field
     * @param getter the field's getter, the source of the generic parameter types
     * @return the immutable shape descriptor
     */
    public static XdStorageObjectFieldInfo buildFieldInfo(final Field field, final Method getter) {
        Class<?> fieldClass = field.getType(), mapKeyClass = null, valueClass;
        boolean isArray = false, isCollection = false, isMap = false;

        if (fieldClass.isArray()) {
            isArray = true;
            valueClass = fieldClass.getComponentType();
        } else if (Collection.class.isAssignableFrom(fieldClass)) {
            isCollection = true;
            valueClass = (Class<?>) ((ParameterizedType) getter.getGenericReturnType()).getActualTypeArguments()[0];
        } else if (Map.class.isAssignableFrom(fieldClass)) {
            isMap = true;
            Object p = ((ParameterizedType) getter.getGenericReturnType()).getActualTypeArguments()[0];
            mapKeyClass = p instanceof ParameterizedType ? ((Class<?>)((ParameterizedType)p).getRawType()) : (Class<?>)p;
            p = ((ParameterizedType) getter.getGenericReturnType()).getActualTypeArguments()[1];
            valueClass = p instanceof ParameterizedType ? ((Class<?>)((ParameterizedType)p).getRawType()) : (Class<?>)p;
        } else {
            valueClass = fieldClass;
        }
        return new XdStorageObjectFieldInfo(fieldClass, isArray, isCollection, isMap, mapKeyClass, valueClass);
    }

    private static void setIndexInformation(final XdStorageClassInfo info, final Class<?> cl) {
        if (cl.isPrimitive()) { info.setIndexType(XdStorageIndexType.Hash); info.setIndexFillingValue(500); return; }
        for (Class<?> sc = cl; !sc.equals(Object.class); sc = sc.getSuperclass()) {
            if (sc.isAnnotationPresent(XdStorageObjectIdIndexType.class)) {
                final XdStorageObjectIdIndexType a = sc.getAnnotation(XdStorageObjectIdIndexType.class);
                info.setIndexType(a.indexType());
                info.setIndexFillingValue(a.t());
                return;
            }
        }
        info.setIndexType(XdStorageIndexType.Hash); info.setIndexFillingValue(500);
    }
}
