package io.github.xdstorage.code;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Финальный декомпозированный сервис генерации кода прокси ленивой загрузки (Поинт В).
 * Избавлен от сотен строк строковой сборки, делегируя её в специализированные суб-генераторы.
 */
public class XdStorageSimpleWrapperClassCodeGenerator extends XdStorageAbstractClassCodeGenerator {

    /** Creates the generator with lazy-load (load-by-get) accessors enabled. */
    public XdStorageSimpleWrapperClassCodeGenerator() {
        super(true);
    }

    /**
     * Generates the simple (lazy-loading) wrapper subclass source: a subclass carrying
     * the wrapped object, its parent reference and the engine storage, with reference
     * flag/lock plumbing, plain and lazy-load getters, delegating setters, closed
     * methods and wrapper-to-wrapper {@code equals}/{@code hashCode}.
     *
     * @param classesPackage target package of the generated class
     * @param cl             the entity class to generate the simple wrapper for; must
     *                       declare an {@code XdStorageObjectId}-annotated field
     * @param code           accumulator map keyed by fully-qualified generated class name
     * @return the same accumulator map with the generated source added
     * @throws IllegalStateException when the entity hierarchy has no id field (a wrapper
     *         without id plumbing would silently degrade)
     */
    @Override
    public Map<String, String> generate(final String classesPackage, final Class<?> cl, final Map<String, String> code) {
        final GenerationContext ctx = collectMethodsAndClasses(cl);

        final StringBuilder builder = new StringBuilder();
        final String className = buildClassName(classesPackage, cl);

        // Сборка каркаса исходного файла Java
        appendPackageAndClassHeader(builder, classesPackage, className, cl);
        appendPrivateFields(builder, cl);
        appendConstructor(builder, className, cl);
        appendDummyMarker(builder);

        // Делегирование генерации методов в выделенные изолированные классы-компоненты
        generateSimpleWrapperMethods(builder, cl);
        XdStorageWrapperBlockGettersGenerator.generateSimpleGetters(builder, ctx, this);
        XdStorageWrapperBlockGettersGenerator.generateStrongGetters(builder, ctx, this);
        XdStorageWrapperBlockGettersGenerator.generateLoadByGetGetters(builder, ctx, this);
        XdStorageWrapperBlockSettersGenerator.generateSettersMethods(builder, ctx, this);
        XdStorageWrapperBlockSettersGenerator.generateClosedMethods(builder, ctx, this);
        generateObjectMethods(builder);

        builder.append("}\r\n");

        code.put(classesPackage + "." + className, builder.toString());
        return code;
    }

    private void appendPackageAndClassHeader(final StringBuilder builder, final String classesPackage, final String className, final Class<?> cl) {
        builder.append("package ").append(classesPackage).append(";\r\n\r\n");
        builder.append("public class ").append(className).append(" extends ").append(getSourceTypeName(cl));
        builder.append(" implements ").append(IXdStorageSimpleWrapper.class.getName()).append(" {\r\n");
    }

    private void appendPrivateFields(final StringBuilder builder, final Class<?> cl) {
        builder.append("\r\n\tprivate ").append(getSourceTypeName(cl)).append(" object;\r\n");
        builder.append("\r\n\tprivate ").append(Object.class.getName()).append(" parent;\r\n");
        builder.append("\r\n\tprivate ").append(IXdStorage.class.getName()).append(" storage;\r\n");
        builder.append("\r\n\tprivate volatile boolean reference__ = true;\r\n");
        builder.append("\r\n\tprivate ").append(Lock.class.getName()).append(" lock__ = new ").append(ReentrantLock.class.getName()).append("();\r\n");
    }

    private void appendConstructor(final StringBuilder builder, final String className, final Class<?> cl) {
        builder.append("\r\n\tpublic ").append(className).append("(");
        builder.append(Object.class.getName()).append(" parent, ");
        builder.append(getSourceTypeName(cl)).append(" object, ");
        builder.append(IXdStorage.class.getName()).append(" storage");
        builder.append(") {\r\n");
        builder.append("\t\tthis.object = object;\r\n");
        builder.append("\t\tthis.parent = parent;\r\n");
        builder.append("\t\tthis.storage = storage;\r\n");
        builder.append("\t}\r\n");
    }

    private void appendDummyMarker(final StringBuilder builder) {
        builder.append("\r\n\tpublic static boolean isDummySimpleWrapper() {\r\n\t\treturn false;\r\n\t}\r\n");
    }

    private void generateSimpleWrapperMethods(final StringBuilder builder, final Class<?> cl) {
        builder.append("\r\n\t@Override\r\n\tpublic Object getObjectId__() {");
        builder.append("\r\n\t\treturn object.").append(getObjectGetIdMethodName(cl)).append("();\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic void setReference__(boolean reference__) {");
        builder.append("\r\n\t\tthis.reference__ = reference__;\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic boolean isReference__() {");
        builder.append("\r\n\t\treturn this.reference__;\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic void lock__() {");
        builder.append("\r\n\t\tlock__.lock();\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic void unlock__() {");
        builder.append("\r\n\t\tlock__.unlock();\r\n\t}\r\n");
    }

    private String getObjectGetIdMethodName(final Class<?> cl) {
        final String idFieldName = getIdFieldName(cl);
        if (idFieldName == null) {
            // SILENT-LOSS BARRIER: раньше это падало голым NPE, а отсутствие геттера id
            // молча деградировало обертку в dummy — роняем генерацию с внятной причиной.
            throw new IllegalStateException("cannot generate simple wrapper for " + cl.getName()
                    + ": no field annotated with @XdStorageObjectId");
        }
        return "get" + Character.toUpperCase(idFieldName.charAt(0)) + idFieldName.substring(1);
    }

    private void generateObjectMethods(final StringBuilder builder) {
        builder.append("\r\n\t@Override\r\n\tpublic int hashCode() {\r\n\t\treturn object.hashCode();\r\n\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic Object getWrappedObject__() {\r\n\t\treturn object;\r\n\t}\r\n");

        // =========================================================================
        // ЭТАЛОННЫЙ ФИКС КОДОГЕНЕРАЦИИ (Строгий алгоритм распаковки по твоему канону):
        // Генерируем equals() в строгом соответствии с транзакционным инвариантом СУБД.
        // Сравнение идет СТРОГО враппер-к-врапперу. Любой чистый доменный объект
        // гарантированно возвращает false, пресекая рассинхронизацию ссылок в куче Java!
        // Распаковка через getWrappedObject__() — без рефлекшна на каждый вызов.
        // =========================================================================
        builder.append("\r\n\t@Override\r\n\tpublic boolean equals(Object obj) {\r\n");
        builder.append("\t\tif (this == obj) return true;\r\n");
        builder.append("\t\tif (obj == null) return false;\r\n\r\n");

        // 1) Проверяем объект-параметр: является ли он SimpleWrapper?
        builder.append("\t\tif (obj instanceof ").append(IXdStorageSimpleWrapper.class.getName()).append(") {\r\n");
        // 1.1) Если да — вытаскиваем обёрнутый объект напрямую и сравниваем со своим
        builder.append("\t\t\treturn this.object.equals(((").append(IXdStorageSimpleWrapper.class.getName()).append(") obj).getWrappedObject__());\r\n");
        builder.append("\t\t}\r\n\r\n");

        // 1.2) Если нет (прилетел чистый объект или другой тип) — эти объекты НЕ равны!
        builder.append("\t\treturn false;\r\n");
        builder.append("\t}\r\n");

        builder.append("\r\n\t@Override\r\n\tpublic String toString() {\r\n\t\treturn object.toString();\r\n\t}\r\n");
    }


    /**
     * Names the generated simple wrapper after its entity class.
     *
     * @param classesPackage target package of the generated class (unused by the
     *                       simple name scheme)
     * @param cl             the entity class being wrapped
     * @return {@code SimpleName + "SimpleWrapper"}
     */
    @Override
    protected String buildClassName(final String classesPackage, final Class<?> cl) {
        return cl.getSimpleName() + generationQualifier(cl) + "SimpleWrapper";
    }
}
