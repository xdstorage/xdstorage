package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Выделенный компонент декомпозиции СУБД (Поинт В).
 * Отвечает исключительно за рефлексивный перенос данных из POJO в прокси СУБД.
 */
public class XdStorageClonerFieldPacker {

    /** Instantiation-only utility class — no instances. */
    public XdStorageClonerFieldPacker() {
    }

    /**
     * Copies every non-id field from the source object into the target (an engine
     * reference wrapper): primitives/enums/simple values as-is, {@code Date} values by
     * time, arrays/collections/maps deep-copied and recursed, and entity-typed values
     * replaced by the transaction's reference wrappers.
     *
     * @param target      the reference object receiving the field values
     * @param src         the source object providing the field values
     * @param clInfo      metadata of the source object's class (field map)
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the target reference belongs to
     * @param cloner      the orchestrator used to wrap entity-typed values
     * @throws Exception if reflective field access fails
     */
    public static void packFields(final Object target, final Object src, final XdStorageClassInfo clInfo,
                                  final IXdStorage storage, final IXdStorageTransaction transaction,
                                  final XdStorageSmartCloner cloner) throws Exception {

        final Map<String, XdStorageObjectField> fields = clInfo.getFields();
        for (final XdStorageObjectField field : fields.values()) {
            if (field.isIdField()) {
                continue;
            }
            final Object value = field.get(src);
            final Class<?> clValue = value != null ? value.getClass() : null;

            if (value == null || clValue.isPrimitive() || clValue.isEnum() || XdStorageObjectUtils.isSimpleType(clValue,
                    value)) {
                field.set(target, value);
            } else if (clValue == Date.class) {
                field.set(target, new Date(((Date) value).getTime()));
            } else if (clValue.isArray()) {
                field.set(target, XdStorageClonerArrayProcessor.cloneAndWrapArray(value, storage, transaction, cloner));
            } else if (value instanceof Collection<?>) {
                field.set(target, XdStorageClonerCollectionProcessor.cloneAndWrapCollection((Collection<?>) value,
                        storage, transaction, cloner));
            } else if (value instanceof Map<?, ?>) {
                field.set(target, XdStorageClonerCollectionProcessor.cloneAndWrapMap((Map<?, ?>) value, storage,
                        transaction, cloner));
            } else {
                field.set(target, cloner.internalCloneAsReferenceAndWrapObject(value, storage, transaction));
            }
        }
    }
}
