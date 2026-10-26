package io.github.xdstorage.code;

import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * Изолированный компонент декомпозиции (Поинт В).
 * Отвечает за генерацию геттеров и ленивой загрузки для unmodifiable-оберток.
 */
public class XdStorageUnmodifiableBlockGettersGenerator {

    /** Inert holder: the generator blocks are fully static. */
    public XdStorageUnmodifiableBlockGettersGenerator() {
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
     * parent getter returns the parent wrapper when present, and every other reference
     * is materialized through {@code XdStorageObjectUtils.wrapAsUnmodifiableObject}
     * against the view's storage and transaction.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateStrongGetters(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator.GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method getter : ctx.strongGetters) {
            final Class<?> returnType = getter.getReturnType();
            final Type genReturnType = getter.getGenericReturnType();
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(getter.getModifiers(), getter.getName(), getter.getTypeParameters(), returnType, genReturnType, getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");

            if (getter == ctx.parentGetter) {
                builder.append("\t\tif(parent != null) {\r\n\t\t\treturn (");
                buildTypeCast(builder, genReturnType, returnType);
                builder.append(")parent;\r\n\t\t} else {\r\n\t\t\treturn ").append(XdStorageObjectUtils.class.getName()).append(".wrapAsUnmodifiableObject(");
                builder.append("object.").append(gen.buildMethodCalling(getter.getName(), getter.getParameterTypes())).append(", storage, transaction);\r\n\t\t}\r\n");
            } else {
                builder.append("\t\treturn ").append(XdStorageObjectUtils.class.getName()).append(".wrapAsUnmodifiableObject(this, ");
                builder.append("object.").append(gen.buildMethodCalling(getter.getName(), getter.getParameterTypes())).append(", storage, transaction);\r\n");
            }
            builder.append("\t}\r\n");
        }
    }

    /**
     * Emits overriding lazy-load getters for the {@code XdStorageLoadByGetMethod}
     * fields of the context: on first access the referenced object is loaded through
     * the view's storage and transaction (a {@code null} reference skips the load);
     * storage failures mark the transaction rollback-only and surface as
     * {@code XdStorageRuntimeException}. The parent getter variant returns the parent
     * wrapper when present instead of loading.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateLoadByGetGetters(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator.GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method getter : ctx.strongLoadByGetGetters) {
            final Class<?> returnType = getter.getReturnType();
            final Type genReturnType = getter.getGenericReturnType();
            final String name = getter.getName();
            final String fieldName = name.substring(3, 4).toLowerCase() + name.substring(4);

            builder.append("\r\n\tprivate boolean ").append(fieldName).append("Loaded;\r\n");
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(getter.getModifiers(), name, getter.getTypeParameters(), returnType, genReturnType, getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");

            builder.append("\t\tif(!").append(fieldName).append("Loaded) {\r\n\t\t\ttry{\r\n");
            // null-стража: пустое ленивое поле не должно превращаться в storage.load(null)
            // (семейство simple-оберток делает ту же проверку)
            builder.append("\t\t\t\tObject toLoadObject = object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n");
            builder.append("\t\t\t\tif (toLoadObject != null) {\r\n\t\t\t\t\tstorage.load(toLoadObject, transaction);\r\n\t\t\t\t}\r\n");
            builder.append("\t\t\t\t").append(fieldName).append("Loaded = true;\r\n");
            builder.append("\t\t\t} catch(").append(XdStorageConnectionException.class.getName()).append(" | ").append(XdStorageException.class.getName()).append(" e) {\r\n");
            // RBCAUSE: the site descriptor names the lazy-loaded field and the entity class;
            // the caught storage failure is BOTH the mark's root cause and the thrown
            // exception's cause — the commit failure chains back to it
            builder.append("\t\t\t\ttransaction.markRollbackOnly(\"lazy-load of field '").append(fieldName)
                    .append("' on ").append(getter.getDeclaringClass().getSimpleName()).append("\", e);\r\n");
            builder.append("\t\t\t\tthrow new ").append(XdStorageRuntimeException.class.getName());
            builder.append("(\"couldn't load objects of field ").append(fieldName).append(", transaction \" + transaction.getTransactionId() + \" marked as rollback only\", e);\r\n");
            builder.append("\t\t\t}\r\n\t\t}\r\n");

            if (getter == ctx.parentGetter) {
                builder.append("\t\tif(parent != null) {\r\n\t\t\treturn (");
                buildTypeCast(builder, genReturnType, returnType);
                builder.append(")parent;\r\n\t\t} else {\r\n\t\t\treturn ").append(XdStorageObjectUtils.class.getName()).append(".wrapAsUnmodifiableObject(");
                builder.append("object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(", storage, transaction);\r\n\t\t}\r\n");
            } else {
                builder.append("\t\treturn ").append(XdStorageObjectUtils.class.getName()).append(".wrapAsUnmodifiableObject(this, ");
                builder.append("object.").append(gen.buildMethodCalling(name, getter.getParameterTypes())).append(", storage, transaction);\r\n");
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
