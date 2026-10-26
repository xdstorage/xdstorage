package io.github.xdstorage.code;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import java.lang.reflect.Method;

/**
 * Изолированный компонент декомпозиции (Поинт В).
 * Защищает Snapshot-состояния от случайных изменений воркерами.
 */
public class XdStorageUnmodifiableBlockSettersGenerator {

    /** Inert holder: the generator blocks are fully static. */
    public XdStorageUnmodifiableBlockSettersGenerator() {
    }

    /**
     * Emits overriding setters that protect the snapshot view: every generated setter
     * marks the current transaction rollback-only and throws
     * {@code XdStorageRuntimeException} instead of mutating the wrapped object.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateSettersMethods(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator.GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method method : ctx.setters) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(method.getModifiers(), method.getName(), method.getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method.getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");
            // RBCAUSE: the site descriptor names the setter and the entity class — the
            // commit failure of the poisoned transaction says who marked it
            builder.append("\t\ttransaction.markRollbackOnly(\"modification of viewable object: setter '").append(method.getName())
                    .append("' on ").append(method.getDeclaringClass().getSimpleName()).append("\", null);\r\n");
            builder.append("\t\tthrow new ").append(XdStorageRuntimeException.class.getName()).append("(\"modification of viewable object is not allowed\");\r\n\t}\r\n");
        }
    }

    /**
     * Emits overriding bodies for the remaining public methods (infrastructure calls
     * such as {@code clone()} or non-accessor methods): each marks the current
     * transaction rollback-only and throws {@code XdStorageRuntimeException} instead
     * of invoking the wrapped object.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateClosedMethods(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator.GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method method : ctx.toCloseMethods) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(method.getModifiers(), method.getName(), method.getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method.getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");
            // RBCAUSE: the site descriptor names the closed method and the entity class
            builder.append("\t\ttransaction.markRollbackOnly(\"modification of viewable object: call of closed method '").append(method.getName())
                    .append("' on ").append(method.getDeclaringClass().getSimpleName()).append("\", null);\r\n");
            builder.append("\t\tthrow new ").append(XdStorageRuntimeException.class.getName()).append("(\"modification of viewable object is not allowed\");\r\n\t}\r\n");
        }
    }
}
