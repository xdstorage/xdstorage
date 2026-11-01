package io.github.xdstorage.code;

import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.lang.reflect.*;
import java.util.Map;

/**
 * Исправленный stateless генератор для Unmodifiable XdStorage объектов (Поинт В / Г).
 * Полностью увязан с изолированным контекстом GenerationContext нити.
 */
public class XdStorageUnmodifiableXdStorageObjectWrapperClassCodeGenerator extends XdStorageAbstractClassCodeGenerator {

    /** Creates a stateless generator with lazy-load generation switched off. */
    public XdStorageUnmodifiableXdStorageObjectWrapperClassCodeGenerator() {
    }

    /**
     * Generates the read-only wrapper subclass of the engine's infrastructure base
     * class {@code XdStorageIdentifiableObject}: plain getters delegate to the wrapped
     * object, strong getters delegate directly (this wrapper carries no storage or
     * transaction, so no nested wrapper materialization), and setters plus closed
     * methods throw {@code XdStorageRuntimeException}.
     *
     * @param classesPackage target package of the generated class
     * @param cl             the infrastructure class to generate the wrapper for
     *                       (always {@code XdStorageIdentifiableObject} in practice)
     * @param code           accumulator map keyed by fully-qualified generated class name
     * @return the same accumulator map with the generated source added
     */
    @Override
    public Map<String, String> generate(final String classesPackage, final Class<?> cl, final Map<String, String> code) {
        // 1. Собираем методы во внутренний изолированный GenerationContext нити
        final GenerationContext ctx = collectMethodsAndClasses(cl);

        final StringBuilder builder = new StringBuilder();
        final String className = buildClassName(classesPackage, cl);

        builder.append("package ").append(classesPackage).append(";\r\n\r\n");
        builder.append("public class ").append(className).append(" extends ").append(getSourceTypeName(cl));
        builder.append(" implements ").append(IXdStorageUnmodifiableWrapper.class.getName()).append(" {\r\n");

        builder.append("\r\n\tprivate ").append(XdStorageIdentifiableObject.class.getName()).append(" object;\r\n");
        builder.append("\r\n\tprivate ").append(Object.class.getName()).append(" parent;\r\n");

        builder.append("\r\n\tpublic ").append(className).append("(");
        builder.append(Object.class.getName()).append(" parent, ");
        builder.append(XdStorageIdentifiableObject.class.getName()).append(" object) {\r\n");
        builder.append("\t\tthis.object = object;\r\n");
        builder.append("\t\tthis.parent = parent;\r\n");
        builder.append("\t}\r\n");

        builder.append("\r\n\tpublic static boolean isDummyUnmodifiableWrapper() {\r\n\t\treturn false;\r\n\t}\r\n");

        // 2. Генерируем блоки методов, делегируя контекст ctx наружу
        generateSimpleGetters(builder, ctx);
        generateStrongGetters(builder, ctx);
        generateSettersMethods(builder, ctx);
        generateClosedMethods(builder, ctx);
        generateObjectMethods(builder);

        builder.append("}\r\n");

        code.put(classesPackage + "." + className, builder.toString());
        return code;
    }

    private void generateStrongGetters(final StringBuilder builder, final GenerationContext ctx) {
        for (Method getter : ctx.strongGetters) {
            final int modifiers = getter.getModifiers();
            final TypeVariable<Method>[] typeParameters = getter.getTypeParameters();
            final Class<?> returnType = getter.getReturnType();
            final Type genReturnType = getter.getGenericReturnType();
            final String name = getter.getName();

            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(modifiers, name, typeParameters, returnType, genReturnType, getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");
            if (getter == ctx.parentGetter) {
                builder.append("\t\tif(parent != null) {\r\n\t\t\treturn (");
                buildReturnType(builder, genReturnType, returnType);
                builder.append(")parent;\r\n\t\t} else {\r\n\t\t\treturn (");
                buildReturnType(builder, genReturnType, returnType);
                // обертка инфраструктурного объекта не несет storage/transaction — прямая
                // делегация вместо wrapAsUnmodifiableObject (литерал getProperty("id") читал
                // не то хранилище и возвращал null)
                builder.append(")object.").append(buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n\t\t}\r\n");
            } else {
                builder.append("\t\treturn (");
                buildReturnType(builder, genReturnType, returnType);
                builder.append(")object.").append(buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n");
            }
            builder.append("\t}\r\n");
        }
    }

    private void generateSimpleGetters(final StringBuilder builder, final GenerationContext ctx) {
        for (Method getter : ctx.fieldsGetters) {
            final int modifiers = getter.getModifiers();
            final TypeVariable<Method>[] typeParameters = getter.getTypeParameters();
            final Class<?> returnType = getter.getReturnType();
            final Type genReturnType = getter.getGenericReturnType();
            final String name = getter.getName();

            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(modifiers, name, typeParameters, returnType, genReturnType, getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\treturn object.").append(buildMethodCalling(name, getter.getParameterTypes())).append(";\r\n");
            builder.append("\t}\r\n");
        }
    }

    private void generateSettersMethods(final StringBuilder builder, final GenerationContext ctx) {
        for (Method method : ctx.setters) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(method.getModifiers(), method.getName(), method.getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method.getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\tthrow new ").append(XdStorageRuntimeException.class.getName()).append("(\"modification of viewable object is not allowed\");\r\n");
            builder.append("\t}\r\n");
        }
    }

    private void generateClosedMethods(final StringBuilder builder, final GenerationContext ctx) {
        for (Method method : ctx.toCloseMethods) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(method.getModifiers(), method.getName(), method.getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method.getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\tthrow new ").append(XdStorageRuntimeException.class.getName()).append("(\"modification of viewable object is not allowed\");\r\n");
            builder.append("\t}\r\n");
        }
    }

    private void generateObjectMethods(final StringBuilder builder) {
        builder.append("\r\n\t@Override\r\n\tpublic int hashCode() {\r\n\t\treturn object.hashCode();\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic Object getWrappedObject__() {\r\n\t\treturn object;\r\n\t}\r\n");

        // Единый канон семейства: строгое сравнение враппер-к-врапперу с прямой
        // распаковкой getWrappedObject__() — без рефлекшна и без сравнения с голыми объектами
        builder.append("\r\n\t@Override\r\n\tpublic boolean equals(Object obj) {\r\n");
        builder.append("\t\tif (this == obj) return true;\r\n");
        builder.append("\t\tif (obj == null) return false;\r\n");
        builder.append("\t\tif (obj instanceof ").append(IXdStorageUnmodifiableWrapper.class.getName()).append(") {\r\n");
        builder.append("\t\t\treturn this.object.equals(((").append(IXdStorageUnmodifiableWrapper.class.getName()).append(") obj).getWrappedObject__());\r\n");
        builder.append("\t\t}\r\n");
        builder.append("\t\treturn false;\r\n");
        builder.append("\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic String toString() {\r\n\t\treturn object.toString();\r\n\t}\r\n");
    }

    private void buildReturnType(StringBuilder builder, Type genReturnType, Class<?> returnType) {
        if (genReturnType != null && (genReturnType instanceof ParameterizedType || genReturnType instanceof TypeVariable)) {
            builder.append(genReturnType.toString());
        } else {
            builder.append(getSourceTypeName(returnType));
        }
    }

    @Override
    protected String buildClassName(final String classesPackage, final Class<?> cl) {
        return cl.getSimpleName() + generationQualifier(cl) + "UnmodifiableXdStorageObjectWrapper";
    }
}
