package io.github.xdstorage.utils;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageParentObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

/**
 * A persistence field accessor of an entity: the reflection field bound with its getter and
 * setter, plus the shape info and the annotation-derived flags (id field, parent back-link,
 * string size). Instances are immutable after construction and safe to share across threads;
 * reflective access failures are logged and swallowed ({@code get} answers {@code null}).
 * Engine-internal — not part of the public API.
 */
public class XdStorageObjectField {

    private static final Logger log = LoggerFactory.getLogger(XdStorageObjectField.class);

    /** The underlying reflection field, made accessible by the scanner. */
    public final Field field;

    private final Method setter;

    private final Method getter;

    private final XdStorageObjectFieldInfo fieldInfo;

    /**
     * Binds the field with its accessors (registry-builder step).
     *
     * @param field the reflection field
     * @param setter the field's setter (may be {@code null} only for degenerate holders)
     * @param getter the field's getter (may be {@code null} only for degenerate holders)
     * @param fieldInfo the shape descriptor built from the field's declared types
     */
    public XdStorageObjectField(final Field field, final Method setter, final Method getter, final XdStorageObjectFieldInfo fieldInfo) {
        this.field = field;
        this.setter = setter;
        this.getter = getter;
        this.fieldInfo = fieldInfo;
    }

    /**
     * Returns {@code true} when the field carries {@code @XdStorageObjectId}.
     *
     * @return {@code true} when the field carries {@code @XdStorageObjectId}
     */
    public boolean isIdField() {
        return field.isAnnotationPresent(XdStorageObjectId.class);
    }

    /**
     * Returns the field's JavaBeans name.
     *
     * @return the field's JavaBeans name
     */
    public String getName() {
        return field != null ? field.getName() : null;
    }

    /**
     * Returns {@code true} when the field carries {@code @XdStorageParentObject} (the parent back-link).
     *
     * @return {@code true} when the field carries {@code @XdStorageParentObject} (the parent back-link)
     */
    public boolean isParent() {
        return field.isAnnotationPresent(XdStorageParentObject.class);
    }

    /**
     * Returns {@code true} when the declared type is {@code String} or {@code char[]}.
     *
     * @return {@code true} when the declared type is {@code String} or {@code char[]}
     */
    public boolean isStringField() {
        final Class<?> cl = field.getType();
        return cl == String.class || cl == char[].class;
    }

    /**
     * Returns the maximum stored string length from {@code @XdStorageObjectFieldProperties}, 4000 by default.
     *
     * @return the maximum stored string length from {@code @XdStorageObjectFieldProperties}, 4000 by default
     */
    public long getStringSize() {
        if (field.isAnnotationPresent(XdStorageObjectFieldProperties.class)) {
            return field.getAnnotation(XdStorageObjectFieldProperties.class).length();
        }
        return 4000;
    }

    /**
     * Returns the shape descriptor of the field's declared types.
     *
     * @return the shape descriptor of the field's declared types
     */
    public XdStorageObjectFieldInfo getFieldInfo() {
        return fieldInfo;
    }

    /**
     * Writes the value through the bound setter; a reflective failure is logged and swallowed,
     * leaving the target object unchanged.
     *
     * @param obj the target instance
     * @param value the value to write
     * @param <T> the value type
     */
    public <T> void set(final Object obj, final T value) {
        try {
            setter.invoke(obj, value);
        } catch (final IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            log.error("cannot set property {}{}", getName(), obj != null ? " of " + obj.getClass() : " for null object", e);
        }
    }

    /**
     * Reads the value through the bound getter; a reflective failure is logged and answered
     * with {@code null}.
     *
     * @param obj the source instance
     * @param <T> the value type
     * @return the current field value, or {@code null} when the reflective read failed
     */
    @SuppressWarnings("unchecked")
    public <T> T get(final Object obj) {
        try {
            return (T) getter.invoke(obj, new Object[]{});
        } catch (final IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            log.error("cannot get property {}{}", getName(), obj != null ? " of " + obj.getClass() : " from null object", e);
        }
        return null;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        final String name = getName();
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        XdStorageObjectField other = (XdStorageObjectField) obj;
        final String name = getName(), otherName = other.getName();
        if (name == null) {
            if (otherName != null)
                return false;
        } else if (!name.equals(otherName))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "XdStorageObjectField{" +
                "field=" + field.getName() +
                '}';
    }
}