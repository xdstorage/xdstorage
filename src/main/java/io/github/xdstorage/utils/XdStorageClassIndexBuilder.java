package io.github.xdstorage.utils;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;
import io.github.xdstorage.search.XdStorageSearchIndex;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Engine-internal builder of the secondary search indexes declared on an entity class with
 * {@code @XdStorageObjectSearchIndex} (repeatable), including the child-index attachments for
 * {@code @XdStorageObjectChildSearchIndex}-declared nested indexes. Stateless; all lookups go
 * through the class metadata registry, so it is safe to call from any thread. Not part of the
 * public API.
 */
public final class XdStorageClassIndexBuilder {
    private XdStorageClassIndexBuilder() {}

    /**
     * Builds the named search indexes of one entity class from its index annotations. Only
     * simple-typed index fields get field accessors; child indexes attach when the child class
     * declares a matching index name. A class without index annotations yields an empty map.
     *
     * @param cl the entity class annotated with {@code @XdStorageObjectSearchIndex}
     * @param fields the class's scanned fields, by field name (source of the accessors)
     * @return the search indexes by index name (an empty map when none are declared)
     */
    public static Map<String, XdStorageSearchIndex> buildIndexes(final Class<?> cl, final Map<String,
            XdStorageObjectField> fields) {
        if (!cl.isAnnotationPresent(XdStorageObjectSearchIndex.class)) return Collections.emptyMap();

        final Map<String, XdStorageSearchIndex> indexes = new HashMap<>();
        final XdStorageObjectIdField idField = XdStorageClassMetadataRegistry.getClassIdField(cl);

        for (final XdStorageObjectSearchIndex annotation : cl.getAnnotationsByType(XdStorageObjectSearchIndex.class)) {
            final XdStorageSearchIndex index = new XdStorageSearchIndex(idField, annotation.indexName(), annotation
                    .t());
            indexes.put(index.getName(), index);

            int i = 0;
            for (final String fieldName : annotation.indexFieldNames()) {
                final XdStorageObjectField field = fields.get(fieldName);
                if (i == 0) { index.setPrimaryField(field); ++i; }
                if (XdStorageObjectUtils.isSimpleType(field.getFieldInfo().getClazz(), null)) {
                    index.addFieldAccessor(fieldName, field);
                }
            }

            for (final XdStorageObjectChildSearchIndex childAnnotation : annotation.childrenIndexes()) {
                final String fname = childAnnotation.childFieldName();
                final XdStorageObjectField field = fields.get(fname);
                final XdStorageObjectFieldInfo fieldInfo = field.getFieldInfo();
                final String fieldClassIndex = childAnnotation.childClassIndexName();

                if (fieldInfo.isArray() || fieldInfo.isCollection()) {
                    final Class<?> type = fieldInfo.getValueClass();
                    if (XdStorageClassMetadataRegistry.getClassPolicy(type) == XdStoragePolicy.StoreWithParentObject) {
                        attachChildClassIndex(fname, cl, type, field, index, fieldClassIndex);
                    }
                } else if (fieldInfo.isMap()) {
                    final Type valueType = fieldInfo.getValueClass();
                    if (valueType instanceof Class<?>) {
                        attachChildClassIndex(fname, cl, (Class<?>) valueType, field, index, fieldClassIndex);
                    }
                } else if (!XdStorageObjectUtils.isSimpleType(fieldInfo.getClazz(), null)) {
                    attachChildClassIndex(fname, cl, fieldInfo.getClazz(), field, index, fieldClassIndex);
                }
            }
        }
        return indexes;
    }

    private static void attachChildClassIndex(final String childFieldName, final Class<?> cl, final Class<?> fieldType,
                                              final XdStorageObjectField accessor, final XdStorageSearchIndex index,
                                                      final String childIndexName) {
        final XdStorageObjectIdField idField = XdStorageClassMetadataRegistry.getClassIdField(fieldType);
        for (final XdStorageObjectSearchIndex annotation : fieldType.getAnnotationsByType(XdStorageObjectSearchIndex
                .class)) {
            if (annotation.indexName().equals(childIndexName)) {
                index.addChildIndex(childFieldName, childIndexName);
                index.addChildAccessor(childFieldName, fieldType, accessor);
                index.addChildIdField(fieldType, idField);

                final Map<String, XdStorageObjectField> fields = XdStorageClassMetadataRegistry
                        .getClassFields(fieldType);
                for (final String fieldName : annotation.indexFieldNames()) {
                    final XdStorageObjectField field = fields.get(fieldName);
                    if (XdStorageObjectUtils.isSimpleType(field.getFieldInfo().getClazz(), null)) {
                        index.addChildFieldAccessor(fieldType, fieldName, field);
                    }
                }
                break;
            }
        }
    }
}
