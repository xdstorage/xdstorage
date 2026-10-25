package io.github.xdstorage.utils;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Deep-clone helpers for the container members of an object graph (arrays, collections, maps)
 * used by the MVCC snapshot machinery: simple values, enums and {@code Date}s are copied
 * element-wise, nested containers recurse, entities delegate to {@link XdStoragePojoCloner};
 * the shared {@code visited} map keeps cycles and repeats single-instance. The source
 * containers are expected to be engine-owned (mutation quiesced) during the walk.
 * Engine-internal — not part of the public API.
 */
public final class XdStorageCollectionCloner {
    private XdStorageCollectionCloner() {}

    /**
     * Clones an array element-wise into a new array of the same component type; null,
     * primitive, enum and simple-typed elements are shared, everything else is deep-copied.
     *
     * @param parent the clone of the array's owner (used to rewire back-references), may be {@code null}
     * @param array the source array (a real array object, not {@code null})
     * @param visited the identity map of already-cloned sources to their clones
     * @return the cloned array of the same length and component type
     * @throws Exception when a nested entity clone fails (delegated reflective instantiation)
     */
    public static Object cloneArray(final Object parent, final Object array, final Map<Object, Object> visited) throws Exception {
        final int length = Array.getLength(array);
        final Object clone = Array.newInstance(array.getClass().getComponentType(), length);
        for (int i = 0; i < length; ++i) {
            final Object tmp = Array.get(array, i), value;
            final Class<?> cl = tmp != null ? tmp.getClass() : null;
            if (tmp == null || cl.isPrimitive() || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, tmp)) {
                value = tmp;
            } else if (cl == Date.class) {
                value = new Date(((Date) tmp).getTime());
            } else if (cl.isArray()) {
                value = cloneArray(parent, tmp, visited);
            } else if (tmp instanceof Collection<?>) {
                value = cloneCollection(parent, tmp, visited);
            } else if (tmp instanceof Map<?, ?>) {
                value = cloneMap(parent, tmp, visited);
            } else {
                value = XdStoragePojoCloner.clonePojo(null, tmp, visited);
            }
            Array.set(clone, i, value);
        }
        return clone;
    }

    /**
     * Clones a collection into a fresh instance of the same class, element-wise: simple values
     * are shared, {@code Date}s and nested containers are copied, entities are deep-cloned.
     * Any failure (e.g. a source class without a no-arg constructor) is swallowed and yields
     * {@code null}.
     *
     * @param parent the clone of the collection's owner (used to rewire back-references), may be {@code null}
     * @param collection the source collection, may be {@code null}
     * @param visited the identity map of already-cloned sources to their clones
     * @return the cloned collection (empty when the source is empty)
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when a source
     *         element cannot be cloned (never a silent {@code null})
     */
    @SuppressWarnings("unchecked")
    public static Object cloneCollection(final Object parent, final Object collection, final Map<Object, Object> visited) {
        try {
            if (collection == null) return null;
            final Collection<Object> src = (Collection<Object>) collection;
            if (src.isEmpty()) {
                return newCollectionInstance(src);
            }

            final Collection<Object> clone = newCollectionInstance(src);
            Object[] arr = src.toArray(new Object[0]);
            for (final Object object : arr) {
                final Class<?> cl = object != null ? object.getClass() : null;
                if (object == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, object)) {
                    clone.add(object);
                } else if (cl == Date.class) {
                    clone.add(new Date(((Date) object).getTime()));
                } else if (cl.isArray()) {
                    clone.add(cloneArray(parent, object, visited));
                } else if (object instanceof Collection<?>) {
                    clone.add(cloneCollection(parent, object, visited));
                } else if (object instanceof Map<?, ?>) {
                    clone.add(cloneMap(parent, object, visited));
                } else {
                    clone.add(XdStoragePojoCloner.clonePojo(null, object, visited));
                }
            }
            return clone;
        } catch (final Exception e) {
            // SILENTFAIL (docs/tasks/2.1.0/35): проглоченная ошибка клонирования делала
            // MVCC-снимок молча теряющим коллекцию (семейство R-44) — говорим громко.
            throw new io.github.xdstorage.exceptions.XdStorageRuntimeException(
                    "cannot clone a collection of " + collection.getClass().getName()
                            + " — a snapshot that silently drops it would be data loss", e);
        }
    }

    /**
     * materializes the clone container for a source collection whose class cannot be
     * reflectively re-instantiated (immutable/wrapper/foreign containers): the closest
     * MUTABLE semantic equivalent is chosen — the exact {@code Collections.synchronized*}
     * wrapper classes when recognized, then the source's semantic interface
     * (SortedSet → TreeSet preserving natural order, Set → HashSet, Queue/Deque →
     * LinkedList, everything else → ArrayList/LinkedList per {@link RandomAccess}).
     * The clone's CLASS may therefore be coarser than the source's — its CONTENTS and
     * iteration semantics are what the snapshot preserves.
     */
    private static Collection<Object> newCollectionInstance(final Collection<Object> src) {
        final Class<? extends Collection> collectionClass = src.getClass();
        try {
            return collectionClass.newInstance();
        } catch (final Exception e) {
            if (collectionClass == Collections.synchronizedList(new ArrayList<>()).getClass()
                    || collectionClass == Collections.synchronizedList(new LinkedList<>()).getClass()) {
                // EMPTY containers: the caller's element loop fills the clone —
                // pre-populating from src would duplicate every element.
                return Collections.synchronizedList(
                        src instanceof LinkedList<?> ? new LinkedList<>() : new ArrayList<>());
            }
            if (collectionClass == Collections.synchronizedSet(new HashSet<>()).getClass()) {
                return Collections.synchronizedSet(new HashSet<>());
            }
            if (src instanceof SortedSet<?>) {
                return new TreeSet<>();
            }
            if (src instanceof Set<?>) {
                return new HashSet<>();
            }
            if (src instanceof Deque<?> || src instanceof Queue<?>) {
                return new LinkedList<>();
            }
            return new ArrayList<>();
        }
    }

    /**
     * materializes the clone container for a source MAP whose class cannot be reflectively
     * re-instantiated: the synchronized-map wrapper when recognized, SortedMap → TreeMap
     * (natural order), everything else → HashMap. See {@link #newCollectionInstance}.
     */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMapInstance(final Map<Object, Object> src) {
        final Class<?> mapClass = src.getClass();
        try {
            return (Map<Object, Object>) mapClass.newInstance();
        } catch (final Exception e) {
            if (mapClass == Collections.synchronizedMap(new HashMap<Object, Object>()).getClass()) {
                return Collections.synchronizedMap(new HashMap<>());
            }
            if (src instanceof SortedMap<?, ?>) {
                return new TreeMap<>();
            }
            return new HashMap<>();
        }
    }

    /**
     * Clones a map into a fresh instance of the same class: keys and values are copied with the
     * same simple/Date/entity rules (keys only ever delegate to {@link XdStoragePojoCloner}),
     * entries with a null key are dropped, cycles resolve through the visited map. Any failure
     * (e.g. a source class without a no-arg constructor) is swallowed and yields {@code null}.
     *
     * @param parent the clone of the map's owner (used to rewire back-references), may be {@code null}
     * @param map the source map, may be {@code null}
     * @param visited the identity map of already-cloned sources to their clones
     * @return the cloned map (empty when the source is empty)
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when a key or value
     *         cannot be cloned (never a silent {@code null})
     */
    @SuppressWarnings("unchecked")
    public static Object cloneMap(final Object parent, final Object map, final Map<Object, Object> visited) {
        try {
            if (map == null) return null;
            final Map<Object, Object> src = (Map<Object, Object>) map;
            if (src.isEmpty()) {
                return newMapInstance(src);
            }

            final Map<Object, Object> clone = newMapInstance(src);
            for (final Map.Entry<?, ?> entry : src.entrySet()) {
                final Object keytmp = entry.getKey(), key;
                Class<?> cl = keytmp != null ? keytmp.getClass() : null;
                if (keytmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, keytmp)) key = keytmp;
                else if (cl == Date.class) key = new Date(((Date) keytmp).getTime());
                else key = XdStoragePojoCloner.clonePojo(null, keytmp, visited);

                final Object valuetmp = entry.getValue(), value;
                cl = valuetmp != null ? valuetmp.getClass() : null;
                if (valuetmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, valuetmp)) value = valuetmp;
                else if (cl == Date.class) value = new Date(((Date) valuetmp).getTime());
                else value = XdStoragePojoCloner.clonePojo(null, valuetmp, visited);

                if (key != null) clone.put(key, value);
            }
            return clone;
        } catch (final Exception e) {
            // SILENTFAIL: см. cloneCollection — молчаливая потеря карты в снимке недопустима.
            throw new io.github.xdstorage.exceptions.XdStorageRuntimeException(
                    "cannot clone a map of " + map.getClass().getName()
                            + " — a snapshot that silently drops it would be data loss", e);
        }
    }
}
