package io.github.xdstorage.factories;

import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Выделенный компонент декомпозиции СУБД (Поинт В).
 * Отвечает исключительно за рефлексивное восстановление POJO из прокси-оберток.
 */
public class XdStorageClonerFieldUnpacker {

    /** Instantiation-only utility class — no instances. */
    public XdStorageClonerFieldUnpacker() {
    }

    /**
     * Restores every non-id field from the (possibly wrapped) source object into a plain
     * POJO target: primitives/enums/simple values as-is, {@code Date} values by time,
     * arrays/collections/maps unwrapped and recursed, and reference-wrapped entity values
     * cloned back into plain objects.
     *
     * @param target the plain POJO receiving the restored field values
     * @param src    the (possibly wrapped) source object providing the field values
     * @param clInfo metadata of the target object's class (field map)
     * @param cloner the orchestrator used to unwrap entity-typed values
     * @throws Exception if reflective field access fails
     */
    public static void unpackFields(final Object target, final Object src, final XdStorageClassInfo clInfo,
                                    final XdStorageSmartCloner cloner) throws Exception {

        final Map<String, XdStorageObjectField> fields = clInfo.getFields();
        for (final XdStorageObjectField field : fields.values()) {
            if (field.isIdField()) {
                continue;
            }
            final Object value = field.get(src);
            final Class<?> clValue = value != null ? XdStorageObjectUtils.getEntityClass(value.getClass()) : null;

            if (value == null || clValue.isPrimitive() || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue,
                    value)) {
                field.set(target, value);
            } else if (clValue == Date.class) {
                field.set(target, new Date(((Date) value).getTime()));
            } else if (clValue.isArray()) {
                field.set(target, XdStorageClonerArrayProcessor.unwrapAndCloneArray(value, cloner));
            } else if (value instanceof Collection<?>) {
                field.set(target, XdStorageClonerCollectionProcessor.unwrapAndCloneCollection((Collection<?>) value,
                        cloner));
            } else if (value instanceof Map<?, ?>) {
                field.set(target, XdStorageClonerCollectionProcessor.unwrapAndCloneMap((Map<?, ?>) value, cloner));
            } else {
                field.set(target, cloner.internalUnwrapAndCloneAsReference(value));
            }
        }
    }
}
