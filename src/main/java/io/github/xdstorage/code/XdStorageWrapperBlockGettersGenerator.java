package io.github.xdstorage.code;

import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Изолированный компонент декомпозиции (Поинт В).
 * Отвечает исключительно за генерацию простых, сильных и ленивых (load-by-get) геттеров.
 */
public class XdStorageWrapperBlockGettersGenerator {

    /** Inert holder: the generator blocks are fully static. */
    public XdStorageWrapperBlockGettersGenerator() {
    }

    /**
     * Emits overriding delegating getters for the simple-typed fields of the context:
     * each generated accessor forwards to the wrapped object.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateSimpleGetters(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator.GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method getter : ctx.fieldsGetters) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(getter.getModifiers(), getter.getName(), getter.getTypeParameters(), getter.getReturnType(), getter.getGenericReturnType(), getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\treturn object.").append(gen.buildMethodCalling(getter.getName(), getter.getParameterTypes())).append(";\r\n\t}\r\n");
        }
    }

    /**
     * Emits overriding getters for the strong (reference) fields of the context: the
     * parent getter returns the parent wrapper when present, every other reference
     * delegates directly to the wrapped object (the simple wrapper itself is the
     * lazy-loading proxy).
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateStrongGetters(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator.GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method getter : ctx.strongGetters) {
            final Class<?> returnType = getter.getReturnType();
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(getter.getModifiers(), getter.getName(), getter.getTypeParameters(), returnType, getter.getGenericReturnType(), getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");

            if (getter == ctx.parentGetter) {
                builder.append("\t\tif(parent != null) {\r\n\t\t\treturn (");
                buildTypeCast(builder, getter.getGenericReturnType(), returnType);
                builder.append(")parent;\r\n\t\t} else {\r\n\t\t\treturn object.").append(gen.buildMethodCalling(getter.getName(), getter.getParameterTypes())).append(";\r\n\t\t}\r\n");
            } else {
                builder.append("\t\treturn object.").append(gen.buildMethodCalling(getter.getName(), getter.getParameterTypes())).append(";\r\n");
            }
            builder.append("\t}\r\n");
        }
    }

    /**
     * Emits overriding lazy-load getters for the {@code XdStorageLoadByGetMethod}
     * fields of the context: generated with double-checked locking on the wrapper's
     * own lock, loading single references, arrays, collections and map values through
     * the storage's current transaction on first access. Invisible collection/map
     * elements are silently dropped from the iteration; any other load failure marks
     * the transaction rollback-only and surfaces as {@code XdStorageRuntimeException}.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateLoadByGetGetters(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator.GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method getter : ctx.strongLoadByGetGetters) {
            final Class<?> returnType = getter.getReturnType();
            final String name = getter.getName();
            final String fieldName = name.substring(3, 4).toLowerCase() + name.substring(4);

            builder.append("\r\n\tprivate volatile boolean ").append(fieldName).append("Loaded;\r\n"); // Делаем поле volatile
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(getter.getModifiers(), name, getter.getTypeParameters(), returnType, getter.getGenericReturnType(), getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");

            // ВНЕДРЯЕМ DOUBLE-CHECKED LOCKING НА БАЗЕ ВНУТРЕННЕГО LOCK__ ПРОКСИ-КЛАССА
            builder.append("\t\tif(!").append(fieldName).append("Loaded) {\r\n");
            builder.append("\t\t\tlock__();\r\n");
            builder.append("\t\t\ttry {\r\n");
            builder.append("\t\t\t\tif(!").append(fieldName).append("Loaded) {\r\n");
            builder.append("\t\t\t\t\t" + IXdStorageTransaction.class.getName() + " transaction = storage.getTransactionManager().getCurrentTransaction();\r\n");
            builder.append("\t\t\t\t\ttry{\r\n");

            if (returnType.isArray()) {
                builder.append("\t\t\t\t\t\t").append(XdStorageAbstractClassCodeGenerator.getSourceTypeName(returnType)).append(" toLoadCollection = object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n");
                builder.append("\t\t\t\t\t\tif (toLoadCollection != null) {\r\n\t\t\t\t\t\t\tfor (Object toLoadObject : toLoadCollection) {\r\n\t\t\t\t\t\t\t\tif (toLoadObject != null) {\r\n\t\t\t\t\t\t\t\t\tstorage.load(toLoadObject, transaction);\r\n\t\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t}\r\n");
            } else if (returnType.isAssignableFrom(Collection.class) || returnType.isAssignableFrom(List.class)) {
                builder.append("\t\t\t\t\t\t").append(XdStorageAbstractClassCodeGenerator.getSourceTypeName(returnType)).append("<? extends Object> toLoadCollection = object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n");
                builder.append("\t\t\t\t\t\tif (toLoadCollection != null) {\r\n\t\t\t\t\t\t\tfor (java.util.Iterator<?> toLoadIterator = toLoadCollection.iterator(); toLoadIterator.hasNext(); ) {\r\n\t\t\t\t\t\t\t\tObject toLoadObject = toLoadIterator.next();\r\n\t\t\t\t\t\t\t\tif (toLoadObject != null && ").append(XdStorageObjectUtils.class.getName()).append(".isSimpleWrappedObject(toLoadObject)) {\r\n\t\t\t\t\t\t\t\t\ttry {\r\n\t\t\t\t\t\t\t\t\t\tstorage.load(toLoadObject, transaction);\r\n\t\t\t\t\t\t\t\t\t} catch (io.github.xdstorage.exceptions.XdStorageReferenceInvisibleException invisible) {\r\n\t\t\t\t\t\t\t\t\t\t// R-58: element invisible in the reader snapshot - not part of the visible graph version\r\n\t\t\t\t\t\t\t\t\t\ttoLoadIterator.remove();\r\n\t\t\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t}\r\n");
            } else if (returnType.isAssignableFrom(Map.class)) {
                builder.append("\t\t\t\t\t\t").append(Collection.class.getName()).append("<? extends Object> toLoadCollection = object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(".values();\r\n");
                builder.append("\t\t\t\t\t\tif (toLoadCollection != null) {\r\n\t\t\t\t\t\t\tfor (java.util.Iterator<?> toLoadIterator = toLoadCollection.iterator(); toLoadIterator.hasNext(); ) {\r\n\t\t\t\t\t\t\t\tObject toLoadObject = toLoadIterator.next();\r\n\t\t\t\t\t\t\t\tif (toLoadObject != null && ").append(XdStorageObjectUtils.class.getName()).append(".isSimpleWrappedObject(toLoadObject)) {\r\n\t\t\t\t\t\t\t\t\ttry {\r\n\t\t\t\t\t\t\t\t\t\tstorage.load(toLoadObject, transaction);\r\n\t\t\t\t\t\t\t\t\t} catch (io.github.xdstorage.exceptions.XdStorageReferenceInvisibleException invisible) {\r\n\t\t\t\t\t\t\t\t\t\t// R-58: element invisible in the reader snapshot - not part of the visible graph version\r\n\t\t\t\t\t\t\t\t\t\ttoLoadIterator.remove();\r\n\t\t\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t\t}\r\n\t\t\t\t\t\t}\r\n");
            } else {
                builder.append("\t\t\t\t\t\tObject toLoadObject = object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n");
                builder.append("\t\t\t\t\t\tif (toLoadObject != null && ").append(XdStorageObjectUtils.class.getName()).append(".isSimpleWrappedObject(toLoadObject)) {\r\n");
                builder.append("\t\t\t\t\t\t\tstorage.load(toLoadObject, transaction);\r\n");
                builder.append("\t\t\t\t\t\t}\r\n");
            }

            builder.append("\t\t\t\t\t\t").append(fieldName).append("Loaded = true;\r\n\t\t\t\t\t} catch(").append(Throwable.class.getName()).append(" e) {\r\n");
            // RBCAUSE: the site descriptor names the lazy-loaded field and the entity class;
            // the caught failure is the mark's root cause (already chained into the throw)
            builder.append("\t\t\t\t\t\ttransaction.markRollbackOnly(\"lazy-load of field '").append(fieldName)
                    .append("' on ").append(getter.getDeclaringClass().getSimpleName()).append("\", e);\r\n\t\t\t\t\t\tthrow new ").append(XdStorageRuntimeException.class.getName());
            builder.append("(\"couldn't load objects of field ").append(fieldName).append(", transaction \" + transaction.getTransactionId() + \" marked as rollback only\", e);\r\n\t\t\t\t\t}\r\n");
            builder.append("\t\t\t\t}\r\n");
            builder.append("\t\t\t} finally {\r\n");
            builder.append("\t\t\t\tunlock__();\r\n");
            builder.append("\t\t\t}\r\n");
            builder.append("\t\t}\r\n");

            if (getter == ctx.parentGetter) {
                builder.append("\t\tif(parent != null) {\r\n\t\t\treturn (");
                buildTypeCast(builder, getter.getGenericReturnType(), returnType);
                builder.append(")parent;\r\n\t\t} else {\r\n\t\t\treturn object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n\t\t}\r\n");
            } else {
                builder.append("\t\treturn object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n");
            }
            builder.append("\t}\r\n");
        }
    }

    private static void buildTypeCast(StringBuilder builder, Type genReturnType, Class<?> returnType) {
        if (genReturnType != null && (genReturnType instanceof ParameterizedType || genReturnType instanceof TypeVariable)) {
            builder.append(genReturnType.toString());
        } else {
            builder.append(XdStorageAbstractClassCodeGenerator.getSourceTypeName(returnType));
        }
    }
}
