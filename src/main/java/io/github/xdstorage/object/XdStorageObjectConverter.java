package io.github.xdstorage.object;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Stateless converter between application entities and the engine's record layer.
 * {@code convertTo} projects an entity graph into {@code XdStorageIdentifiableObject}
 * records (class, id and per-field properties); {@code convertFrom} materializes a record
 * back into a new entity instance; {@code fillFrom} copies record properties into an
 * existing instance. Nested values are handled by kind: simple types and enums by
 * reference, {@code Date}s defensively copied, arrays, collections and maps deep-converted
 * preserving the runtime container type, embedded ({@code StoreWithParentObject}) children
 * inlined as nested records, referenced children reduced to id-only records, and parent
 * back-references stored by id and restored to the owning instance on the way back.
 *
 * <p>All methods are static and the class holds no state, so it is thread-safe. Conversion
 * failures of nested values are logged and yield {@code null} for the affected value; a
 * failing top-level {@code convertFrom} returns {@code null}. Entity classes must expose an
 * accessible no-arg constructor and an {@code @XdStorageObjectId} field. Engine-internal —
 * not part of the public API.</p>
 */
public class XdStorageObjectConverter {

    private static final Logger log = LoggerFactory.getLogger(XdStorageObjectConverter.class);

    /**
     * Static utility class — no instances needed.
     */
    public XdStorageObjectConverter() {
    }

    /**
     * Projects an application entity into its stored record form: the record carries the
     * entity class, its id and one property per stored field, with nested values converted
     * per the class rules (see the class documentation).
     *
     * @param <T>    the entity type
     * @param object the entity to convert
     * @return the record holding the entity's class, id and per-field properties
     */
    public static <T> XdStorageIdentifiableObject convertTo(final T object) {
        final Class<?> cl = object.getClass();

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        final XdStorageIdentifiableObject result = new XdStorageIdentifiableObject();
        result.setType(cl);

        final XdStorageObjectIdField idField = clInfo.getIdField();
        result.setId(idField.get(object));

        final Collection<XdStorageObjectField> fields = clInfo.getFields().values();
        for (final XdStorageObjectField field : fields) {
            final Object value = field.get(object);
            final Class<?> valueCl = value == null ? null : value.getClass();

            final String fieldName = field.getName();
            if (value == null || valueCl.isPrimitive() || valueCl.isEnum() || XdStorageObjectUtils.isSimpleType(valueCl,
                    null)) {
                result.setProperty(fieldName, value);
            } else if (valueCl == Date.class) {
                result.setProperty(fieldName, new Date(((Date) value).getTime()));
            } else if (valueCl.isArray()) {
                result.setProperty(fieldName, convertArrayTo(value));
            } else if (value instanceof Collection<?>) {
                result.setProperty(fieldName, convertCollectionTo((Collection<?>) value));
            } else if (value instanceof Map<?, ?>) {
                result.setProperty(fieldName, convertMapTo((Map<?, ?>) value));
            } else if (field.isParent()) {
                result.setProperty(fieldName, convertParentTo(value));
            } else {
                result.setProperty(fieldName, convertInternalTo(value));
            }
        }

        return result;
    }

    private static Object convertArrayTo(final Object array) {
        final int length = Array.getLength(array);
        final Object clone = Array.newInstance(array.getClass().getComponentType(), length);
        for (int i = 0; i < length; ++i) {
            final Object tmp = Array.get(array, i), value;
            final Class<?> cl = tmp != null ? tmp.getClass() : null;
            if (tmp == null || cl.isPrimitive() || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                value = tmp;
            } else if (cl == Date.class) {
                value = new Date(((Date) tmp).getTime());
            } else if (cl.isArray()) {
                value = convertArrayTo(tmp);
            } else if (tmp instanceof Collection<?>) {
                value = convertCollectionTo((Collection<?>) tmp);
            } else if (tmp instanceof Map<?, ?>) {
                value = convertMapTo((Map<?, ?>) tmp);
            } else {
                value = convertInternalTo(tmp);
            }
            Array.set(clone, i, value);
        }
        return clone;
    }

    private static Object convertCollectionTo(final Collection<?> collection) {
        try {
            final Collection<Object> clone = (Collection<Object>) collection.getClass().newInstance();
            for (final Object object : (Collection<?>) collection) {
                final Class<?> cl = object != null ? object.getClass() : null;
                if (object == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, object)) {
                    clone.add(object);
                } else if (cl == Date.class) {
                    clone.add(new Date(((Date) object).getTime()));
                } else if (cl.isArray()) {
                    clone.add(convertArrayTo(object));
                } else if (object instanceof Collection<?>) {
                    clone.add(convertCollectionTo((Collection<?>) object));
                } else if (object instanceof Map<?, ?>) {
                    clone.add(convertMapTo((Map<?, ?>) object));
                } else {
                    clone.add(convertInternalTo(object));
                }
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform collection error", e);
        }
        return null;
    }

    private static Object convertMapTo(final Map<?, ?> map) {
        try {
            final Map<Object, Object> clone = (Map<Object, Object>) map.getClass().newInstance();
            for (final Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {

                final Object keytmp = entry.getKey(), key;
                Class<?> cl = keytmp != null ? keytmp.getClass() : null;
                if (keytmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    key = keytmp;
                } else if (cl == Date.class) {
                    key = new Date(((Date) keytmp).getTime());
                } else if (cl.isArray()) {
                    key = convertArrayTo(keytmp);
                } else if (keytmp instanceof Collection<?>) {
                    key = convertCollectionTo((Collection<?>) keytmp);
                } else if (keytmp instanceof Map<?, ?>) {
                    key = convertMapTo((Map<?, ?>) keytmp);
                } else {
                    key = convertInternalTo(keytmp);
                }

                final Object valuetmp = entry.getValue(), value;
                cl = valuetmp != null ? valuetmp.getClass() : null;
                if (valuetmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    value = valuetmp;
                } else if (cl == Date.class) {
                    value = new Date(((Date) valuetmp).getTime());
                } else if (cl.isArray()) {
                    value = convertArrayTo(valuetmp);
                } else if (valuetmp instanceof Collection<?>) {
                    value = convertCollectionTo((Collection<?>) valuetmp);
                } else if (valuetmp instanceof Map<?, ?>) {
                    value = convertMapTo((Map<?, ?>) valuetmp);
                } else {
                    value = convertInternalTo(valuetmp);
                }

                clone.put(key, value);
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform map error", e);
        }
        return null;
    }

    private static Object convertParentTo(final Object object) {
        try {
            if (object instanceof Class<?>) {
                return object;
            }
            final Class<?> cl = object.getClass();
            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStorageIdentifiableObject clone = new XdStorageIdentifiableObject();
            clone.setType(cl);

            final XdStorageObjectIdField idField = clInfo.getIdField();
            clone.setProperty(idField.getName(), idField.get(object));

            return clone;
        } catch (Exception e) {
            log.warn("transform object error", e);
        }
        return null;
    }

    private static Object convertInternalTo(final Object object) {
        try {
            if (object instanceof Class<?>) {
                return object;
            }
            final Class<?> cl = object.getClass();
            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStorageIdentifiableObject clone = new XdStorageIdentifiableObject();
            clone.setType(cl);

            final XdStorageObjectIdField idField = clInfo.getIdField();
            clone.setId(idField.get(object));

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy != XdStoragePolicy.StoreWithParentObject) {
                return clone;
            }

            final Collection<XdStorageObjectField> fields = clInfo.getFields().values();
            for (final XdStorageObjectField field : fields) {
                final Object tmp = field.get(object), value;
                final Class<?> tmpCl = tmp != null ? tmp.getClass() : null;
                if (tmp == null || tmpCl.isEnum() || XdStorageObjectUtils.isSimpleType(tmpCl, null)) {
                    value = tmp;
                } else if (tmpCl == Date.class) {
                    value = new Date(((Date) tmp).getTime());
                } else if (tmpCl.isArray()) {
                    value = convertArrayTo(tmp);
                } else if (tmp instanceof Collection<?>) {
                    value = convertCollectionTo((Collection<?>) tmp);
                } else if (tmp instanceof Map<?, ?>) {
                    value = convertMapTo((Map<?, ?>) tmp);
                } else {
                    value = convertInternalTo(tmp);
                }
                clone.setProperty(field.getName(), value);
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform object error", e);
        }
        return null;
    }

    /**
     * Materializes a new entity instance from its stored record: creates the entity with
     * its no-arg constructor, restores the id and converts every recorded property back
     * into the matching field — nested records into embedded children, and parent fields
     * back to the owning instance.
     *
     * @param <T>    the entity type stored in the record
     * @param object the stored record to materialize
     * @return the new entity instance, or {@code null} when the conversion fails (the
     *         failure is logged)
     */
    public static <T> T convertFrom(final XdStorageIdentifiableObject object) {
        try {
            final Class<T> cl = (Class<T>) object.getType();
            final T result = cl.newInstance();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStorageObjectIdField idField = clInfo.getIdField();
            idField.set(result, object.getId());

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy != XdStoragePolicy.StoreWithParentObject) {
                return result;
            }

            final Collection<XdStorageObjectField> fields = clInfo.getFields().values();
            for (final XdStorageObjectField field : fields) {
                final Object value = object.getProperty(field.getName());
                final Class<?> valueCl = value == null ? null : value.getClass();

                final String fieldName = field.getName();
                if (value == null || valueCl.isPrimitive() || valueCl.isEnum() || XdStorageObjectUtils
                        .isSimpleType(valueCl, null)) {
                    field.set(result, value);
                } else if (valueCl == Date.class) {
                    field.set(result, new Date(((Date) value).getTime()));
                } else if (value instanceof XdStorageIdentifiableObject) {
                    field.set(result, convertFrom((XdStorageIdentifiableObject) value));
                } else if (valueCl.isArray()) {
                    field.set(result, convertArrayFrom(result, value));
                } else if (value instanceof Collection<?>) {
                    field.set(result, convertCollectionFrom(result, (Collection<?>) value));
                } else if (value instanceof Map<?, ?>) {
                    field.set(result, convertMapFrom(result, (Map<?, ?>) value));
                } else {
                    field.set(result, convertInternalFrom(result, value));
                }
            }

            return result;
        } catch (Exception e) {
            log.error("transform object error", e);
        }
        return null;
    }

    private static Object convertArrayFrom(final Object parent, final Object array) {
        final int length = Array.getLength(array);
        final Object clone = Array.newInstance(array.getClass().getComponentType(), length);
        for (int i = 0; i < length; ++i) {
            final Object tmp = Array.get(array, i), value;
            final Class<?> cl = tmp != null ? tmp.getClass() : null;
            if (tmp == null || cl.isPrimitive() || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                value = tmp;
            } else if (cl == Date.class) {
                value = new Date(((Date) tmp).getTime());
            } else if (tmp instanceof XdStorageIdentifiableObject) {
                value = convertFrom((XdStorageIdentifiableObject) tmp);
            } else if (cl.isArray()) {
                value = convertArrayFrom(parent, tmp);
            } else if (tmp instanceof Collection<?>) {
                value = convertCollectionFrom(parent, (Collection<?>) tmp);
            } else if (tmp instanceof Map<?, ?>) {
                value = convertMapFrom(parent, (Map<?, ?>) tmp);
            } else {
                value = convertInternalFrom(parent, tmp);
            }
            Array.set(clone, i, value);
        }
        return clone;
    }

    private static Object convertCollectionFrom(final Object parent, final Collection<?> collection) {
        try {
            final Collection<Object> clone = (Collection<Object>) collection.getClass().newInstance();
            for (final Object object : collection) {
                final Class<?> cl = object != null ? object.getClass() : null;
                if (object == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    clone.add(object);
                } else if (cl == Date.class) {
                    clone.add(new Date(((Date) object).getTime()));
                } else if (object instanceof XdStorageIdentifiableObject) {
                    clone.add(convertFrom((XdStorageIdentifiableObject) object));
                } else if (cl.isArray()) {
                    clone.add(convertArrayFrom(parent, object));
                } else if (object instanceof Collection<?>) {
                    clone.add(convertCollectionFrom(parent, (Collection<?>) object));
                } else if (object instanceof Map<?, ?>) {
                    clone.add(convertMapFrom(parent, (Map<?, ?>) object));
                } else {
                    clone.add(convertInternalFrom(parent, object));
                }
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform collection error", e);
        }
        return null;
    }

    private static Object convertMapFrom(final Object parent, final Map<?, ?> map) {
        try {
            final Map<Object, Object> clone = (Map<Object, Object>) map.getClass().newInstance();
            for (final Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {

                final Object keytmp = entry.getKey(), key;
                Class<?> cl = keytmp != null ? keytmp.getClass() : null;
                if (keytmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    key = keytmp;
                } else if (cl == Date.class) {
                    key = new Date(((Date) keytmp).getTime());
                } else if (keytmp instanceof XdStorageIdentifiableObject) {
                    key = convertFrom((XdStorageIdentifiableObject) keytmp);
                } else if (cl.isArray()) {
                    key = convertArrayFrom(parent, keytmp);
                } else if (keytmp instanceof Collection<?>) {
                    key = convertCollectionFrom(parent, (Collection<?>) keytmp);
                } else if (keytmp instanceof Map<?, ?>) {
                    key = convertMapFrom(parent, (Map<?, ?>) keytmp);
                } else {
                    key = convertInternalFrom(parent, keytmp);
                }

                final Object valuetmp = entry.getValue(), value;
                cl = valuetmp != null ? valuetmp.getClass() : null;
                if (valuetmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    value = valuetmp;
                } else if (cl == Date.class) {
                    value = new Date(((Date) valuetmp).getTime());
                } else if (valuetmp instanceof XdStorageIdentifiableObject) {
                    value = convertFrom((XdStorageIdentifiableObject) valuetmp);
                } else if (cl.isArray()) {
                    value = convertArrayFrom(parent, valuetmp);
                } else if (valuetmp instanceof Collection<?>) {
                    value = convertCollectionFrom(parent, (Collection<?>) valuetmp);
                } else if (valuetmp instanceof Map<?, ?>) {
                    value = convertMapFrom(parent, (Map<?, ?>) valuetmp);
                } else {
                    value = convertInternalFrom(parent, valuetmp);
                }

                clone.put(key, value);
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform map error", e);
        }
        return null;
    }

    private static Object convertInternalFrom(final Object parent, final Object object) {
        try {
            if (object instanceof Class<?>) {
                return object;
            }
            final Class<?> cl = object.getClass();
            final Object clone = cl.newInstance();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStorageObjectIdField idField = clInfo.getIdField();
            if (cl == XdStorageIdentifiableObject.class) {
                idField.set(clone, ((XdStorageIdentifiableObject) object).getId());
            } else {
                idField.set(clone, idField.get(object));
            }

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy != XdStoragePolicy.StoreWithParentObject) {
                return clone;
            }

            final Collection<XdStorageObjectField> fields = clInfo.getFields().values();
            for (final XdStorageObjectField field : fields) {
                final Object tmp = field.get(object), value;
                final Class<?> tmpCl = tmp != null ? tmp.getClass() : null;
                if (tmp == null || tmpCl.isEnum() || XdStorageObjectUtils.isSimpleType(tmpCl, null)) {
                    value = tmp;
                } else if (tmpCl == Date.class) {
                    value = new Date(((Date) tmp).getTime());
                } else if (tmp instanceof XdStorageIdentifiableObject) {
                    value = convertFrom((XdStorageIdentifiableObject) tmp);
                } else if (tmpCl.isArray()) {
                    value = convertArrayFrom(parent, tmp);
                } else if (tmp instanceof Collection<?>) {
                    value = convertCollectionFrom(parent, (Collection<?>) tmp);
                } else if (tmp instanceof Map<?, ?>) {
                    value = convertMapFrom(parent, (Map<?, ?>) tmp);
                } else if (field.isParent()) {
                    value = parent;
                } else {
                    value = convertInternalFrom(clone, tmp);
                }
                field.set(clone, value);
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform object error", e);
        }
        return null;
    }

    /**
     * Copies the record's properties into an existing entity instance — unlike
     * {@code convertFrom} it does not create one, which suits refreshing already materialized
     * objects. Nested values are converted per the class rules (see the class documentation).
     *
     * @param reference the entity instance to fill in place
     * @param object    the stored record to read the property values from
     */
    public static void fillFrom(final Object reference, final XdStorageIdentifiableObject object) {
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(reference.getClass());
        final Collection<XdStorageObjectField> properties = clInfo.getFields().values();
        for (final XdStorageObjectField property : properties) {
            final Object tmp = object.getProperty(property.getName());
            final Class<?> cl = tmp != null ? tmp.getClass() : null;
            if (tmp == null || cl.isPrimitive() || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                property.set(reference, tmp);
            } else if (cl == Date.class) {
                property.set(reference, new Date(((Date) tmp).getTime()));
            } else if (cl.isArray()) {
                property.set(reference, fillArrayFrom(reference, tmp));
            } else if (tmp instanceof Collection<?>) {
                property.set(reference, fillCollectionFrom(reference, tmp));
            } else if (tmp instanceof Map<?, ?>) {
                property.set(reference, fillMapFrom(reference, tmp));
            } else {
                property.set(reference, internalFillObjectFrom(reference, tmp));
            }
        }
    }

    private static Object fillArrayFrom(final Object parent, final Object array) {
        final int length = Array.getLength(array);
        final Class<?> componentType;
        if (length == 0) {
            componentType = Object.class;
        } else {
            final Object tmp = Array.get(array, 0);
            if (tmp instanceof XdStorageIdentifiableObject) {
                componentType = ((XdStorageIdentifiableObject) tmp).getType();
            } else {
                componentType = array.getClass().getComponentType();
            }
        }
        final Object clone = Array.newInstance(componentType, length);
        for (int i = 0; i < length; ++i) {
            final Object tmp = Array.get(array, i), value;
            final Class<?> cl = tmp != null ? tmp.getClass() : null;
            if (tmp == null || cl.isPrimitive() || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                value = tmp;
            } else if (cl == Date.class) {
                value = new Date(((Date) tmp).getTime());
            } else if (cl.isArray()) {
                value = fillArrayFrom(parent, tmp);
            } else if (tmp instanceof Collection<?>) {
                value = fillCollectionFrom(parent, tmp);
            } else if (tmp instanceof Map<?, ?>) {
                value = fillMapFrom(parent, tmp);
            } else {
                value = internalFillObjectFrom(parent, tmp);
            }
            Array.set(clone, i, value);
        }
        return clone;
    }

    private static Object fillCollectionFrom(final Object parent, final Object collection) {
        try {
            final Collection<Object> clone = (Collection<Object>) collection.getClass().newInstance();
            for (final Object object : (Collection<?>) collection) {
                final Class<?> cl = object != null ? object.getClass() : null;
                if (object == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    clone.add(object);
                } else if (cl == Date.class) {
                    clone.add(new Date(((Date) object).getTime()));
                } else if (cl.isArray()) {
                    clone.add(fillArrayFrom(parent, object));
                } else if (object instanceof Collection<?>) {
                    clone.add(fillCollectionFrom(parent, object));
                } else if (object instanceof Map<?, ?>) {
                    clone.add(fillMapFrom(parent, object));
                } else {
                    clone.add(internalFillObjectFrom(parent, object));
                }
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform collection error", e);
        }
        return null;
    }

    private static Object fillMapFrom(final Object parent, final Object map) {
        try {
            final Map<Object, Object> clone = (Map<Object, Object>) map.getClass().newInstance();
            for (final Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {

                final Object keytmp = entry.getKey(), key;
                Class<?> cl = keytmp != null ? keytmp.getClass() : null;
                if (keytmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    key = keytmp;
                } else if (cl == Date.class) {
                    key = new Date(((Date) keytmp).getTime());
                } else if (cl.isArray()) {
                    key = fillArrayFrom(parent, keytmp);
                } else if (keytmp instanceof Collection<?>) {
                    key = fillCollectionFrom(parent, keytmp);
                } else if (keytmp instanceof Map<?, ?>) {
                    key = fillMapFrom(parent, keytmp);
                } else {
                    key = internalFillObjectFrom(parent, keytmp);
                }

                final Object valuetmp = entry.getValue(), value;
                cl = valuetmp != null ? valuetmp.getClass() : null;
                if (valuetmp == null || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, null)) {
                    value = valuetmp;
                } else if (cl == Date.class) {
                    value = new Date(((Date) valuetmp).getTime());
                } else if (cl.isArray()) {
                    value = fillArrayFrom(parent, valuetmp);
                } else if (valuetmp instanceof Collection<?>) {
                    value = fillCollectionFrom(parent, valuetmp);
                } else if (valuetmp instanceof Map<?, ?>) {
                    value = fillMapFrom(parent, valuetmp);
                } else {
                    value = internalFillObjectFrom(parent, valuetmp);
                }

                clone.put(key, value);
            }
            return clone;
        } catch (Exception e) {
            log.warn("clone object map error", e);
        }
        return null;
    }

    private static Object internalFillObjectFrom(final Object parent, final Object object) {
        try {
            if (object instanceof Class<?>) {
                return object;
            }
            final boolean isXdStorageObject = object instanceof XdStorageIdentifiableObject;

            final Class<?> cl;
            if (isXdStorageObject) {
                cl = ((XdStorageIdentifiableObject) object).getType();
            } else {
                cl = object.getClass();
            }
            final Object clone = cl.newInstance();

            final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

            final XdStorageObjectIdField idField = clInfo.getIdField();
            if (isXdStorageObject) {
                idField.set(clone, ((XdStorageIdentifiableObject) object).getId());
            } else {
                idField.set(clone, idField.get(object));
            }

            final XdStoragePolicy policy = clInfo.getPolicy();
            if (policy != XdStoragePolicy.StoreWithParentObject) {
                return clone;
            }

            final Collection<XdStorageObjectField> fields = clInfo.getFields().values();
            for (final XdStorageObjectField field : fields) {
                final Object tmp, value;
                if (isXdStorageObject) {
                    tmp = ((XdStorageIdentifiableObject) object).getProperty(field.getName());
                } else {
                    tmp = field.get(object);
                }
                final Class<?> tmpCl = tmp != null ? tmp.getClass() : null;
                if (tmp == null || tmpCl.isEnum() || XdStorageObjectUtils.isSimpleType(tmpCl, null)) {
                    value = tmp;
                } else if (tmpCl == Date.class) {
                    value = new Date(((Date) tmp).getTime());
                } else if (tmpCl.isArray()) {
                    value = fillArrayFrom(parent, tmp);
                } else if (tmp instanceof Collection<?>) {
                    value = fillCollectionFrom(parent, (Collection<?>) tmp);
                } else if (tmp instanceof Map<?, ?>) {
                    value = fillMapFrom(parent, (Map<?, ?>) tmp);
                } else if (field.isParent()) {
                    value = parent;
                } else {
                    value = internalFillObjectFrom(clone, tmp);
                }
                field.set(clone, value);
            }
            return clone;
        } catch (Exception e) {
            log.warn("transform object error", e);
        }
        return null;
    }
}
