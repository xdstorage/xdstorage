package io.github.xdstorage.code;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.annotations.XdStorageLoadByGetMethod;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Полностью stateless базовый генератор кода (Поинт В / Г).
 * Исключает изменяемые поля класса для предотвращения затирания данных при рекурсивной генерации прокси.
 */
public abstract class XdStorageAbstractClassCodeGenerator {

    /** Shared SLF4J logger for this generator and all its subclasses. */
    protected static final Logger log = LoggerFactory.getLogger(XdStorageAbstractClassCodeGenerator.class);

    /** Whether strong getters whose backing field carries {@code XdStorageLoadByGetMethod} are generated as lazy-load accessors. */
    protected final boolean generateLoadByGet;

    /**
     * Контекст собираемых методов, изолированный в рамках одного вызова.
     */
    protected static class GenerationContext {

        /** Creates an empty context for a single generation run. */
        protected GenerationContext() {
        }

        /** The getter of the {@code XdStorageParentObject}-annotated field, or {@code null} when the class has no parent getter. */
        public Method parentGetter;

        /** Getters returning simple-typed fields (emitted as plain delegating accessors). */
        public final List<Method> fieldsGetters = new ArrayList<>();

        /** Getters returning non-simple (reference) fields (emitted with wrapper materialization). */
        public final List<Method> strongGetters = new ArrayList<>();

        /** All {@code set*} methods of the entity class. */
        public final List<Method> setters = new ArrayList<>();

        /** Strong getters whose backing field carries {@code XdStorageLoadByGetMethod} (emitted as lazy-load accessors). */
        public final List<Method> strongLoadByGetGetters = new ArrayList<>();

        /** Public methods that are neither accessors nor {@code equals}/{@code hashCode}/{@code toString} (emitted as closed/delegating methods). */
        public final List<Method> toCloseMethods = new ArrayList<>();
    }

    /** Creates a generator that does not emit lazy-load accessors. */
    protected XdStorageAbstractClassCodeGenerator() {
        this(false);
    }

    /**
     * Creates a generator with an explicit lazy-load (load-by-get) switch.
     *
     * @param generateLoadByGet whether {@code XdStorageLoadByGetMethod}-annotated strong
     *        getters are generated as lazy-load accessors
     */
    protected XdStorageAbstractClassCodeGenerator(final boolean generateLoadByGet) {
        this.generateLoadByGet = generateLoadByGet;
    }

    /**
     * Generates the wrapper subclass source for the given entity class and adds it to
     * the accumulator. Implementations must stay stateless: all per-call state lives in
     * a local {@code GenerationContext}, so concurrent generations cannot clobber each other.
     *
     * @param classesPackage target package of the generated class
     * @param cl             the entity class to generate a wrapper for
     * @param code           accumulator map keyed by fully-qualified generated class name
     * @return the same accumulator map with the generated source added
     */
    public abstract Map<String, String> generate(final String classesPackage, final Class<?> cl, final Map<String, String> code);

    /**
     * Возвращает имя класса в форме, пригодной для подстановки в генерируемый исходный код Java.
     * Бинарное имя вложенного класса (Outer$Inner) не разрешается компилятором в исходнике,
     * поэтому member-классы обязаны эмититься через точку (Outer.Inner).
     *
     * @param cl the type to render (arrays render as {@code Component[]})
     * @return the source-form type name
     */
    protected static String getSourceTypeName(final Class<?> cl) {
        if (cl.isArray()) {
            return getSourceTypeName(cl.getComponentType()) + "[]";
        }
        if (!cl.isMemberClass()) {
            return cl.getName();
        }
        return getSourceTypeName(cl.getEnclosingClass()) + "." + cl.getSimpleName();
    }

    /**
     * Emits the source header of one generated method: the (always public) modifier,
     * generic type parameters, return type, parameter list and {@code throws} clause.
     * Generic types are emitted through their reflection string form; array and member
     * class names are rendered in source form.
     *
     * @param modifiers         visibility modifiers of the source method (the generated
     *                          method is always emitted public)
     * @param name              method name to emit
     * @param typeParameters    generic type parameters of the source method
     * @param returnType        erased return type of the source method
     * @param genReturnType     generic return type used verbatim when generic
     * @param parameterTypes    erased parameter types
     * @param genParameterTypes generic parameter types used verbatim when generic
     * @param exceptionTypes    declared checked exceptions to re-emit
     * @return the method header source, without body
     */
    protected String buildMethodDefinition(final int modifiers, final String name, final TypeVariable<Method>[] typeParameters, final Class<?> returnType,
                                           final Type genReturnType, final Class<?>[] parameterTypes, final Type[] genParameterTypes,
                                           final Class<?>[] exceptionTypes) {
        final StringBuilder builder = new StringBuilder();
        // MSGHYGIENE (docs/tasks/2.1.0/39): the modifiers parameter was ignored and the
        // header was always 'public'. Decision RECORDED: wired now via Modifier.toString
        // (interface implementations still emit public — their reflected modifiers carry
        // public/abstract, and 'abstract' is stripped: generated bodies have real bodies).
        String modifierString = java.lang.reflect.Modifier.toString(modifiers);
        if (modifierString.contains("abstract")) {
            modifierString = modifierString.replace("abstract", "").trim();
        }
        if (modifierString.isEmpty()) {
            modifierString = "public";
        }
        builder.append(modifierString).append(" ");
        if (typeParameters != null && typeParameters.length > 0) {
            builder.append("<");
            for (int i = 0; i < typeParameters.length; ++i) {
                builder.append(typeParameters[i].toString());
                if (i < typeParameters.length - 1) {
                    builder.append(", ");
                }
            }
            builder.append("> ");
        }
        if (genReturnType != null && (genReturnType instanceof ParameterizedType || genReturnType instanceof TypeVariable)) {
            builder.append(genReturnType.toString());
        } else {
            builder.append(getSourceTypeName(returnType));
        }
        builder.append(" ").append(name).append("(");
        if (genParameterTypes != null && genParameterTypes.length > 0) {
            for (int i = 0; i < genParameterTypes.length; ++i) {
                final Type type = genParameterTypes[i];
                if (type instanceof Class<?>) {
                    builder.append(getSourceTypeName(parameterTypes[i])).append(" ").append("var").append(i);
                } else if (type instanceof ParameterizedType || type instanceof TypeVariable) {
                    builder.append(type.toString()).append(" ").append("var").append(i);
                }
                if (i < parameterTypes.length - 1) {
                    builder.append(", ");
                }
            }
        } else {
            for (int i = 0; i < parameterTypes.length; ++i) {
                builder.append(getSourceTypeName(parameterTypes[i])).append(" ").append("var").append(i);
                if (i < parameterTypes.length - 1) {
                    builder.append(", ");
                }
            }
        }
        builder.append(")");

        if (exceptionTypes.length > 0) {
            builder.append(" throws ");
            for(int i = 0; i < exceptionTypes.length; ++i) {
                builder.append(getSourceTypeName(exceptionTypes[i]));
                if (i < exceptionTypes.length - 1) {
                    builder.append(',');
                }
            }
        }
        return builder.toString();
    }

    /**
     * Emits the argument list of a generated forwarding call: the method name followed
     * by the generated parameter names ({@code var0}, {@code var1}, ...).
     *
     * @param name           method name to call
     * @param parameterTypes parameter types; only the array length matters here
     * @return the {@code name(var0, var1, ...)} call source
     */
    protected String buildMethodCalling(final String name, final Class<?>[] parameterTypes) {
        final StringBuilder builder = new StringBuilder();
        builder.append(name).append("(");
        for (int i = 0; i < parameterTypes.length; ++i) {
            builder.append("var").append(i);
            if (i < parameterTypes.length - 1) {
                builder.append(", ");
            }
        }
        builder.append(")");
        return builder.toString();
    }

    /**
     * Classifies the public overridable methods of the entity class into the buckets of
     * a fresh {@code GenerationContext}: simple/strong/lazy-load getters, setters,
     * the parent getter and closed methods. Individual classification failures are
     * logged and skipped, not fatal.
     *
     * @param cl the entity class to inspect
     * @return the filled context, local to this generation call
     */
    protected GenerationContext collectMethodsAndClasses(final Class<?> cl) {
        final GenerationContext ctx = new GenerationContext();
        final Method[] methods = getPublicMethods(cl);
        for (final Method method : methods) {
            final String name = method.getName();
            final Class<?> tmp = method.getReturnType();
            try {
                if (name.startsWith("get")) {
                    if (XdStorageObjectUtils.isSimpleType(tmp, null)) {
                        ctx.fieldsGetters.add(method);
                    } else {
                        if (generateLoadByGet && isLoadByGet(cl, method)) {
                            ctx.strongLoadByGetGetters.add(method);
                        } else {
                            ctx.strongGetters.add(method);
                        }
                    }
                    try {
                        if (isParentGetter(cl, method)) {
                            ctx.parentGetter = method;
                        }
                    } catch (final NoSuchFieldException e) {
                        log.debug("field was not found", e);
                    }
                } else if (name.startsWith("is")) {
                    if (XdStorageObjectUtils.isSimpleType(tmp, null)) {
                        ctx.fieldsGetters.add(method);
                    } else {
                        ctx.strongGetters.add(method);
                    }
                } else if (name.startsWith("set")) {
                    ctx.setters.add(method);
                } else if (!name.equals("equals") && !name.equals("hashCode") && !name.equals("toString")) {
                    ctx.toCloseMethods.add(method);
                }
            } catch (Exception e) {
                log.warn("collection methods and classes error", e);
            }
        }
        return ctx;
    }

    /**
     * Finds the id field of the entity class, walking up the superclass chain.
     *
     * @param cl the entity class to inspect
     * @return the name of the field annotated with {@code XdStorageObjectId},
     *         or {@code null} when the class hierarchy declares no id field
     */
    protected String getIdFieldName(final Class<?> cl) {
        for (Class<?> clazz = cl; !clazz.equals(Object.class); clazz = clazz.getSuperclass()) {
            final Field[] tmp = clazz.getDeclaredFields();
            for (final Field field : tmp) {
                if (field.getAnnotation(XdStorageObjectId.class) != null) {
                    return field.getName();
                }
            }
        }
        return null;
    }

    /**
     * Whether the given {@code getXxx} method is the parent-object getter: its backing
     * field (derived from the method name) must be annotated with {@code XdStorageParentObject}.
     *
     * @param cl     the entity class declaring the field
     * @param method the {@code getXxx} method to test
     * @return {@code true} when the backing field is the parent-object field
     * @throws NoSuchFieldException when no backing field matches the method name
     */
    protected boolean isParentGetter(final Class<?> cl, final Method method) throws NoSuchFieldException {
        final String name = method.getName();
        final String fieldName = name.substring(3, 4).toLowerCase() + name.substring(4);
        final Field field = cl.getDeclaredField(fieldName);
        return field.isAnnotationPresent(XdStorageParentObject.class);
    }

    /**
     * Whether the given {@code getXxx} method is a lazy-load getter: its backing field
     * (derived from the method name) must be annotated with {@code XdStorageLoadByGetMethod}.
     * Any reflection failure yields {@code false}.
     *
     * @param cl     the entity class declaring the field
     * @param method the {@code getXxx} method to test
     * @return {@code true} when the backing field requests load-by-get semantics
     */
    protected boolean isLoadByGet(final Class<?> cl, final Method method) {
        try {
            final String name = method.getName();
            final String fieldName = name.substring(3, 4).toLowerCase() + name.substring(4);
            final Field field = cl.getDeclaredField(fieldName);
            return field.isAnnotationPresent(XdStorageLoadByGetMethod.class);
        } catch(final Exception e) {
            return false;
        }
    }

    /**
     * Collects the public methods of the entity class that a generated wrapper can
     * override: walking from the class up the hierarchy (excluding {@code Object}),
     * static, final and synthetic (bridge) methods are skipped, and redeclarations in
     * superclasses collapse onto the most derived one. The walk order makes the
     * returned array most-derived-first.
     *
     * @param cl the entity class to inspect
     * @return the unique overridable public methods, most derived declaration first
     */
    protected static Method[] getPublicMethods(final Class<?> cl) {
        final List<Method> result = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (Class<?> clazz = cl; !clazz.equals(Object.class); clazz = clazz.getSuperclass()) {
            final Method[] tmp = clazz.getDeclaredMethods();
            for (final Method method : tmp) {
                final int modifiers = method.getModifiers();
                if ((modifiers & Modifier.PUBLIC) == 0) continue;
                // static/final методы нельзя переопределить в генерируемой обертке — их эмиссия
                // ломает компиляцию и молча деградирует обертку в dummy; synthetic (bridge) методы
                // дублируют свою целевую декларацию
                if ((modifiers & (Modifier.STATIC | Modifier.FINAL)) != 0 || method.isSynthetic()) continue;
                // обход идет от наследника вверх: первая декларация — самая производная,
                // повторное переопределение в предке не эмитится вторым копией метода
                if (!seen.add(method.getName() + java.util.Arrays.toString(method.getParameterTypes()))) continue;
                result.add(method);
            }
        }
        return result.toArray(new Method[result.size()]);
    }

    /**
     * Builds the simple class name of the generated wrapper for the entity class.
     *
     * @param classesPackage target package of the generated class (unused by simple
     *                       name-based schemes)
     * @param cl             the entity class being wrapped
     * @return the generated class simple name
     */
    protected abstract String buildClassName(final String classesPackage, final Class<?> cl);

    /**
     * BUG-B (docs/tasks/2.1.0/16), GENERATION-AWARE WRAPPER IDENTITY (the codegen
     * contract decision, recorded in MASTER-PLAN § codegen): the generated wrapper's
     * simple name carries a QUALIFIER derived from the entity's DEFINING CLASSLOADER
     * whenever that loader is not the engine's own. Two live generations of one FQCN
     * (an application upgrade: the old generation on disk, the new one loaded through
     * the app's classloader) then get DISTINCT wrapper classes in the shared codegen
     * loader instead of colliding on one NAME-based wrapper (the first generation's
     * wrapper won {@code Class.forName} and the other generation failed instantiation
     * with {@code NoSuchMethodException} on its constructor).
     *
     * <p>The qualifier ALWAYS disambiguates on the entity class's identity: a
     * deterministic hash of the FQN (two distinct nested classes sharing a SimpleName
     * — the watch()-trio collision — get distinct wrappers; the hash is stable across
     * JVM runs, so a warm {@code ./gencode} directory stays reusable), plus the
     * loader-identity part whenever the entity's loader is not the engine's own. The
     * identity-hash part is stable within one JVM lifetime, which is the only
     * lifetime a generated wrapper class has; stale .class files from previous runs
     * (old name scheme included) are never requested again and are harmless.</p>
     *
     * @param cl the entity class the wrapper is generated for
     * @return {@code "_<fqn-hash>"} for engine-loaded classes,
     *         {@code "_<fqn-hash>_gen<id>"} otherwise
     */
    protected static String generationQualifier(final Class<?> cl) {
        final ClassLoader entityLoader = cl.getClassLoader();
        final ClassLoader engineLoader = XdStorageAbstractClassCodeGenerator.class.getClassLoader();
        // КОЛЛИЗИЯ ПРОСТЫХ ИМЁН (ResourceCache watch-трио): имя генерации строится по
        // SimpleName, а квалификатор был ПУСТ для классов движкового класслоадера — два
        // разных вложенных класса с одним простым именем (A$TestEntity и B$TestEntity)
        // получали ОДНО имя сгенерированной обёртки. Первый defineClass занимал имя
        // навсегда; генерация второго «успешно» подгружала ЧУЖУЮ обёртку, ключ карты
        // (суперкласс загруженного класса) приходился на первого — движок возвращал
        // null, wrapAsUnmodifiableObject молча деградировал в null, и watch() отдавал
        // колбэку null (NPE "getValue() ... because wrappedObject is null") во всех
        // последующих тестах JVM. Дискриминатор детерминирован (хеш FQN): стабилен
        // между запусками, устаревшие .class в ./gencode с прежними именами просто
        // никогда не запрашиваются (см. javadoc выше).
        final String nameQualifier = "_" + Integer.toHexString(cl.getName().hashCode());
        if (entityLoader == null || entityLoader == engineLoader) {
            return nameQualifier;
        }
        return nameQualifier + "_gen" + Integer.toHexString(System.identityHashCode(entityLoader));
    }
}
