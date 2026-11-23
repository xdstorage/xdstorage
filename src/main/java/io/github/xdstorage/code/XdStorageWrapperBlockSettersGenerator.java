package io.github.xdstorage.code;

import java.lang.reflect.Method;

/**
 * Изолированный компонент декомпозиции (Поинт В).
 * Отвечает исключительно за генерацию мутирующих сеттеров и инфраструктурных методов.
 */
public class XdStorageWrapperBlockSettersGenerator {

    /** Inert holder: the generator blocks are fully static. */
    public XdStorageWrapperBlockSettersGenerator() {
    }

    /**
     * Emits overriding setters for the context: each generated setter forwards the
     * mutation to the wrapped object.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateSettersMethods(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator
            .GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method method : ctx.setters) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(method.getModifiers(), method.getName(), method
                    .getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method
                    .getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes()))
                    .append(" {\r\n");
            builder.append("\t\tobject.").append(gen.buildMethodCalling(method.getName(), method.getParameterTypes()))
                    .append(";\r\n\t}\r\n");
        }
    }

    /**
     * Emits overriding bodies for the remaining public methods (infrastructure calls
     * such as {@code clone()} or non-accessor methods): each forwards to the wrapped
     * object, returning its result for {@code clone()} and any non-void method.
     *
     * @param builder target of the generated source
     * @param ctx     method classification of the current generation run
     * @param gen     host generator supplying method-header emission
     */
    public static void generateClosedMethods(final StringBuilder builder, final XdStorageAbstractClassCodeGenerator
            .GenerationContext ctx, final XdStorageAbstractClassCodeGenerator gen) {
        for (Method method : ctx.toCloseMethods) {
            final Class<?> returnType = method.getReturnType();
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(gen.buildMethodDefinition(method.getModifiers(), method.getName(), method
                    .getTypeParameters(), returnType, method.getGenericReturnType(), method.getParameterTypes(), method
                    .getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");

            if ("clone".equalsIgnoreCase(method.getName()) || (returnType != null && returnType != void.class)) {
                builder.append("\t\treturn object.").append(gen.buildMethodCalling(method.getName(), method
                        .getParameterTypes())).append(";\r\n");
            } else {
                builder.append("\t\tobject.").append(gen.buildMethodCalling(method.getName(), method
                        .getParameterTypes())).append(";\r\n");
            }
            builder.append("\t}\r\n");
        }
    }
}
