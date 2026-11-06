package io.github.xdstorage.utils;

import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The accessor of an entity's {@code @XdStorageObjectId} field, extending the plain field
 * accessor with the annotation-declared id generation configuration. Immutable after
 * construction and safe to share across threads. Engine-internal — not part of the public API.
 */
public class XdStorageObjectIdField extends XdStorageObjectField {

    /**
     * Binds the id field with its accessors (registry-builder step).
     *
     * @param field the {@code @XdStorageObjectId}-annotated reflection field
     * @param setter the id setter
     * @param getter the id getter
     * @param fieldInfo the shape descriptor built from the field's declared types
     */
    public XdStorageObjectIdField(final Field field, final Method setter, final Method getter, final XdStorageObjectFieldInfo fieldInfo) {
        super(field, setter, getter, fieldInfo);
    }

    /**
     * Returns the configured id generator type, {@code CUSTOM_GENERATOR} when the field has no properties annotation.
     *
     * @return the configured id generator type, {@code CUSTOM_GENERATOR} when the field has no properties annotation
     */
    public XdStorageIdGeneratorType getIdGeneratorType() {
        if (field.isAnnotationPresent(XdStorageObjectFieldProperties.class)) {
            return field.getAnnotation(XdStorageObjectFieldProperties.class).idGeneratorType();
        }
        return XdStorageIdGeneratorType.CUSTOM_GENERATOR;
    }

    /**
     * Returns the configured custom id generator class, or {@code null} when the field has no properties annotation.
     *
     * @return the configured custom id generator class, or {@code null} when the field has no properties annotation
     */
    public Class<? extends IXdStorageIdGenerator> getIdGeneratorClass() {
        if (field.isAnnotationPresent(XdStorageObjectFieldProperties.class)) {
            return field.getAnnotation(XdStorageObjectFieldProperties.class).idGeneratorClass();
        }
        return null;
    }

    @Override
    public String toString() {
        return "XdStorageObjectIdField{" +
                "field=" + field.getName() +
                '}';
    }
}
