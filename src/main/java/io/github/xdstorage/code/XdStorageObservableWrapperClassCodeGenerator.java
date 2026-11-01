package io.github.xdstorage.code;

import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;

import java.lang.reflect.*;
import java.util.Map;

/**
 * Декомпозированный stateless генератор реактивных Observable оберток.
 * Наблюдатели хранятся в потокобезопасной очереди, а нотификация выполняется через poll().
 */
public class XdStorageObservableWrapperClassCodeGenerator extends XdStorageAbstractClassCodeGenerator {

    /** Creates a stateless generator with lazy-load generation switched off. */
    public XdStorageObservableWrapperClassCodeGenerator() {
    }

    /**
     * Generates the id-observable wrapper subclass source: a delegating subclass that
     * keeps an observer queue, fires {@code onNewIdIsSet} using poll() on the entity's id setter,
     * and delegates every other setter to the wrapped object.
     *
     * @param classesPackage target package of the generated class
     * @param cl             the entity class to generate the observable wrapper for
     * @param code           accumulator map keyed by fully-qualified generated class name
     * @return the same accumulator map with the generated source added
     */
    @Override
    public Map<String, String> generate(final String classesPackage, final Class<?> cl, final Map<String, String> code) {
        final GenerationContext ctx = collectMethodsAndClasses(cl);

        final StringBuilder builder = new StringBuilder();
        final String className = buildClassName(classesPackage, cl);

        appendPackageAndClassHeader(builder, classesPackage, className, cl);
        appendPrivateFields(builder, cl);
        appendConstructor(builder, className, cl);
        appendObserverRegistryMethods(builder);

        final Method targetIdSetter = findIdSetter(cl);
        if (targetIdSetter != null) {
            generateIdSetter(builder, className, targetIdSetter);
        }

        generateDelegatingSetters(builder, ctx, targetIdSetter);
        generateSimpleGetters(builder, ctx);
        generateStrongGetters(builder, ctx);
        generateClosedMethods(builder, ctx);
        generateObjectMethods(builder, className);

        builder.append("}\r\n");

        code.put(classesPackage + "." + className, builder.toString());
        return code;
    }

    // === АТОМАРНЫЕ УЗЛЫ ДЕКОМПОЗИЦИИ КЛАССА ===

    private void appendPackageAndClassHeader(final StringBuilder builder, final String classesPackage, final String className, final Class<?> cl) {
        builder.append("package ").append(classesPackage).append(";\r\n\r\n");
        builder.append("public class ").append(className).append(" extends ").append(getSourceTypeName(cl));
        builder.append(" implements ").append(IXdStorageIdObservableWrapper.class.getName()).append(" {\r\n");
    }

    private void appendPrivateFields(final StringBuilder builder, final Class<?> cl) {
        builder.append("\r\n\tprivate ").append(getSourceTypeName(cl)).append(" object;\r\n");
        // АРХИТЕКТУРНОЕ ИЗМЕНЕНИЕ: Перевод на ConcurrentLinkedQueue для безопасного извлечения через poll()
        builder.append("\r\n\tprivate java.util.Queue<").append(XdStorageAbstractIdObserver.class.getName())
                .append("> observers = new java.util.concurrent.ConcurrentLinkedQueue<>();\r\n");
    }

    private void appendConstructor(final StringBuilder builder, final String className, final Class<?> cl) {
        builder.append("\r\n\tpublic ").append(className).append("(").append(getSourceTypeName(cl)).append(" object) {\r\n");
        builder.append("\t\tthis.object = object;\r\n");
        builder.append("\t}\r\n");
    }

    private void appendObserverRegistryMethods(final StringBuilder builder) {
        builder.append("\r\n\tpublic void addObserver(final ").append(XdStorageAbstractIdObserver.class.getName()).append(" observer) {\r\n")
                .append("\t\tobservers.add(observer);\r\n").append("\t}\r\n");
    }

    // === ЛОГИКА СБОРКИ КОДОВЫХ БЛОКОВ МЕТОДОВ ===

    private Method findIdSetter(final Class<?> cl) {
        final String idFieldName = getIdFieldName(cl);
        if (idFieldName == null) return null;
        for (final Method method : getPublicMethods(cl)) {
            final String name = method.getName();
            if (name.startsWith("set") && name.length() > 3) {
                final String fieldName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                if (fieldName.equals(idFieldName)) {
                    return method;
                }
            }
        }
        return null;
    }

    private void generateDelegatingSetters(final StringBuilder builder, final GenerationContext ctx, final Method excludedIdSetter) {
        for (Method method : ctx.setters) {
            if (excludedIdSetter != null
                    && method.getName().equals(excludedIdSetter.getName())
                    && java.util.Arrays.equals(method.getParameterTypes(), excludedIdSetter.getParameterTypes())) {
                continue;
            }
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(method.getModifiers(), method.getName(), method.getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method.getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\tobject.").append(buildMethodCalling(method.getName(), method.getParameterTypes())).append(";\r\n\t}\r\n");
        }
    }

    /**
     * Модифицированный генератор сеттера идентификатора СУБД.
     * Оповещение и выгрузка наблюдателей выполняются в цикле через метод poll().
     */
    private void generateIdSetter(final StringBuilder builder, final String className, final Method method) {
        builder.append("\r\n\t@Override\r\n\t");
        builder.append(buildMethodDefinition(method.getModifiers(), method.getName(), method.getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method.getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");
        builder.append("\t\tobject.").append(buildMethodCalling(method.getName(), method.getParameterTypes())).append(";\r\n");

        // РЕАКТИВНЫЙ ФИКС ОЧЕРЕДИ: Вытесняем элементы по одному через poll() до полного опустошения
        builder.append("\r\n\t\t").append(XdStorageAbstractIdObserver.class.getName()).append(" obs;\r\n")
                .append("\t\twhile ((obs = observers.poll()) != null) {\r\n")
                .append("\t\t\tobs.onNewIdIsSet(").append(className).append(".this, var0);\r\n")
                .append("\t\t}\r\n\t}\r\n");
    }

    private void generateStrongGetters(final StringBuilder builder, final GenerationContext ctx) {
        for (Method getter : ctx.strongGetters) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(getter.getModifiers(), getter.getName(), getter.getTypeParameters(), getter.getReturnType(), getter.getGenericReturnType(), getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\treturn object.").append(buildMethodCalling(getter.getName(), getter.getParameterTypes())).append(";\r\n");
            builder.append("\t}\r\n");
        }
    }

    private void generateClosedMethods(final StringBuilder builder, final GenerationContext ctx) {
        for (Method method : ctx.toCloseMethods) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(method.getModifiers(), method.getName(), method.getTypeParameters(), method.getReturnType(), method.getGenericReturnType(), method.getParameterTypes(), method.getGenericParameterTypes(), method.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\tthrow new ").append(XdStorageRuntimeException.class.getName()).append("(\"modification of observable object is not allowed\");\r\n");
            builder.append("\t}\r\n");
        }
    }

    private void generateSimpleGetters(final StringBuilder builder, final GenerationContext ctx) {
        for (Method getter : ctx.fieldsGetters) {
            builder.append("\r\n\t@Override\r\n\t");
            builder.append(buildMethodDefinition(getter.getModifiers(), getter.getName(), getter.getTypeParameters(), getter.getReturnType(), getter.getGenericReturnType(), getter.getParameterTypes(), getter.getGenericParameterTypes(), getter.getExceptionTypes())).append(" {\r\n");
            builder.append("\t\treturn object.").append(buildMethodCalling(getter.getName(), getter.getParameterTypes())).append(";\r\n");
            builder.append("\t}\r\n");
        }
    }

    private void generateObjectMethods(final StringBuilder builder, final String className) {
        builder.append("\r\n\t@Override\r\n\tpublic int hashCode() {\r\n\t\treturn object.hashCode();\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic Object getWrappedObject__() {\r\n\t\treturn object;\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic boolean equals(Object obj) {\r\n");
        builder.append("\t\tif (this == obj) return true;\r\n");
        builder.append("\t\tif (obj == null) return false;\r\n\r\n");

        builder.append("\t\tif (obj instanceof ").append(IXdStorageIdObservableWrapper.class.getName()).append(") {\r\n");
        builder.append("\t\t\treturn this.object.equals(((").append(IXdStorageIdObservableWrapper.class.getName()).append(") obj).getWrappedObject__());\r\n");
        builder.append("\t\t}\r\n\r\n");

        builder.append("\t\treturn false;\r\n");
        builder.append("\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic String toString() {\r\n\t\treturn object.toString();\r\n\t}\r\n");
    }

    @Override
    protected String buildClassName(final String classesPackage, final Class<?> cl) {
        return cl.getSimpleName() + "IdObservableWrapper";
    }
}