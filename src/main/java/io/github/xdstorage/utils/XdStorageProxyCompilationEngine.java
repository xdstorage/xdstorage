package io.github.xdstorage.utils;

import io.github.xdstorage.code.*;
import io.github.xdstorage.exceptions.XdStorageWrapperGenerationException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Codegen cache for the engine's wrapper proxies (simple, unmodifiable, observable): each
 * entity class gets its wrappers once per JVM, cached in {@link ConcurrentHashMap}s.
 *
 * <p>Where a wrapper comes from (FP-3, docs/tasks/2.2.0/08), in order:
 * <ol>
 *   <li>a wrapper generated while the application was BUILT, found through its manifest on
 *       the entity's own class loader ({@link XdStoragePreGeneratedWrappers}) — no compiler,
 *       no {@code ./gencode}; this is the path that lets the engine run on a JRE;</li>
 *   <li>the runtime compiler ({@link XdStorageClassGenerator}) — a JDK, compilation
 *       serialized by a global lock, a per-thread generation stack that short-circuits
 *       self-referencing entity graphs to the dummy wrappers;</li>
 *   <li>neither: {@link XdStorageWrapperGenerationException} — at the first use of the
 *       class, and for a runtime that has no path at all already when a storage is opened
 *       ({@link #ensureWrapperSupply}). Until 2.2.0 the engine logged one ERROR and went
 *       on with the dummy wrappers.</li>
 * </ol>
 * Engine-internal — not part of the public API.
 */
public final class XdStorageProxyCompilationEngine {
    private static final Map<Class<?>, Class<?>> classesUnmodifiableWrappers = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Class<?>> classesSimpleWrappers = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Class<?>> classesObservableWrappers = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> checkingWrappableMethod = new ConcurrentHashMap<>();

    private static final ReentrantLock COMPILATION_LOCK = new ReentrantLock();
    private static final ThreadLocal<Set<Class<?>>> currentThreadCompilationStack = ThreadLocal
            .withInitial(HashSet::new);

    private XdStorageProxyCompilationEngine() {}

    /**
     * Returns the simple wrapper of the class: the pre-generated one when the classpath
     * carries it, else one compiled on first use; classes caught in a self-referencing
     * generation on this thread resolve to the dummy wrapper.
     *
     * @param cl the entity class to wrap
     * @return the wrapper class, or the dummy wrapper for a self-referencing generation
     * @throws IOException when the runtime source generation or compilation fails
     * @throws XdStorageWrapperGenerationException when neither path delivers a wrapper
     */
    public static Class<?> getClassSimpleWrapper(final Class<?> cl) throws IOException {
        Class<?> existing = classesSimpleWrappers.get(cl);
        if (existing != null) return existing;

        if (currentThreadCompilationStack.get().contains(cl)) return XdStorageDummySimpleWrapper.class;

        existing = XdStoragePreGeneratedWrappers.lookup(XdStoragePreGeneratedWrappers.Kind.SIMPLE, cl);
        if (existing != null) {
            classesSimpleWrappers.put(cl, existing);
            return existing;
        }

        COMPILATION_LOCK.lock();
        try {
            existing = classesSimpleWrappers.get(cl);
            if (existing != null) return existing;

            currentThreadCompilationStack.get().add(cl);
            try {
                final Map<Class<?>, Class<?>> gen = compileAtRuntime(cl, XdStoragePreGeneratedWrappers.Kind.SIMPLE,
                        new RuntimeGeneration() {
                    /** {@inheritDoc} */
                    @Override
                    Map<Class<?>, Class<?>> generate() throws IOException {
                        return XdStorageClassGenerator.generateSimpleWrapper(cl);
                    }
                });
                cacheAll(classesSimpleWrappers, cl, gen);
            } finally { currentThreadCompilationStack.get().remove(cl); }
            return classesSimpleWrappers.get(cl);
        } catch (IOException e) { classesSimpleWrappers.remove(cl); throw e; } finally { COMPILATION_LOCK.unlock(); }
    }

    /**
     * Returns the unmodifiable (lazy-loading) wrapper of the class: the pre-generated one
     * when the classpath carries it, else one compiled on first use; classes caught in a
     * self-referencing generation on this thread resolve to the dummy wrapper.
     *
     * @param cl the entity class to wrap
     * @return the wrapper class, or the dummy wrapper for a self-referencing generation
     * @throws IOException when the runtime source generation or compilation fails
     * @throws XdStorageWrapperGenerationException when neither path delivers a wrapper
     */
    public static Class<?> getClassUnmodifiableWrapper(final Class<?> cl) throws IOException {
        Class<?> existing = classesUnmodifiableWrappers.get(cl);
        if (existing != null) return existing;

        if (currentThreadCompilationStack.get().contains(cl)) return XdStorageDummyUnmodifiableWrapper.class;

        existing = XdStoragePreGeneratedWrappers.lookup(XdStoragePreGeneratedWrappers.Kind.UNMODIFIABLE, cl);
        if (existing != null) {
            classesUnmodifiableWrappers.put(cl, existing);
            return existing;
        }

        COMPILATION_LOCK.lock();
        try {
            existing = classesUnmodifiableWrappers.get(cl);
            if (existing != null) return existing;

            currentThreadCompilationStack.get().add(cl);
            try {
                final Map<Class<?>, Class<?>> gen = compileAtRuntime(cl,
                        XdStoragePreGeneratedWrappers.Kind.UNMODIFIABLE, new RuntimeGeneration() {
                    /** {@inheritDoc} */
                    @Override
                    Map<Class<?>, Class<?>> generate() throws IOException {
                        return XdStorageClassGenerator.generateUnmodifiableWrapper(cl);
                    }
                });
                cacheAll(classesUnmodifiableWrappers, cl, gen);
            } finally { currentThreadCompilationStack.get().remove(cl); }
            return classesUnmodifiableWrappers.get(cl);
        } catch (IOException e) { classesUnmodifiableWrappers.remove(cl); throw e; } finally { COMPILATION_LOCK
                .unlock(); }
    }

    /**
     * Returns the observable wrapper of the class: the pre-generated one when the classpath
     * carries it, else one compiled on first use under the global compilation lock (no
     * recursion guard on this path).
     *
     * @param cl the entity class to wrap
     * @return the wrapper class
     * @throws IOException when the runtime source generation or compilation fails
     * @throws XdStorageWrapperGenerationException when neither path delivers a wrapper
     */
    public static Class<?> getClassObservableWrapper(final Class<?> cl) throws IOException {
        Class<?> existing = classesObservableWrappers.get(cl);
        if (existing != null) return existing;

        existing = XdStoragePreGeneratedWrappers.lookup(XdStoragePreGeneratedWrappers.Kind.OBSERVABLE, cl);
        if (existing != null) {
            classesObservableWrappers.put(cl, existing);
            return existing;
        }

        COMPILATION_LOCK.lock();
        try {
            existing = classesObservableWrappers.get(cl);
            if (existing != null) return existing;

            final Map<Class<?>, Class<?>> gen = compileAtRuntime(cl,
                    XdStoragePreGeneratedWrappers.Kind.OBSERVABLE, new RuntimeGeneration() {
                /** {@inheritDoc} */
                @Override
                Map<Class<?>, Class<?>> generate() throws IOException {
                    return XdStorageClassGenerator.generateObservableWrapper(cl);
                }
            });
            cacheAll(classesObservableWrappers, cl, gen);
            return classesObservableWrappers.get(cl);
        } finally { COMPILATION_LOCK.unlock(); }
    }

    /**
     * The STARTUP check (FP-3, docs/tasks/2.2.0/08): whether this runtime can deliver
     * wrapper classes AT ALL — asked by {@code XdStorageProvider} when a storage is opened,
     * for a class every storage needs. It compiles nothing and so costs nothing on the paths
     * that work; it only turns the two ways of having no wrapper into a refusal AT STARTUP
     * instead of a failure at the first use of a class:
     * a runtime without the compiler and no pre-generated wrapper, and a working directory
     * that cannot receive {@code ./gencode}.
     *
     * @param probe an entity class every storage needs — the engine's own record base class
     * @throws XdStorageWrapperGenerationException when neither path can deliver a wrapper
     */
    public static void ensureWrapperSupply(final Class<?> probe) {
        if (XdStoragePreGeneratedWrappers.lookup(XdStoragePreGeneratedWrappers.Kind.SIMPLE, probe) != null) {
            return;    // build-time wrappers: no compiler and no directory are needed
        }
        boolean compiler = true;
        try {
            // the compiler API's own module — {@code java.compiler} on 9+, {@code rt.jar} on 8;
            // absent from a JRE image and from a limited module graph
            Class.forName("javax.tools.JavaCompiler", false, XdStorageProxyCompilationEngine.class.getClassLoader());
        } catch (final ClassNotFoundException | LinkageError noCompilerApi) {
            compiler = false;
        }
        if (!compiler) {
            final String stale = XdStoragePreGeneratedWrappers.staleDescription(
                    XdStoragePreGeneratedWrappers.Kind.SIMPLE, probe);
            if (stale != null) {
                throw XdStorageWrapperGenerationException.stale(probe, stale);
            }
            throw XdStorageWrapperGenerationException.noCompiler(probe);
        }
        XdStorageClassGenerator.ensureCodeDirectory();
    }

    /** One runtime generation to run — the three lookups differ only in what they generate. */
    private abstract static class RuntimeGeneration {
        /** Runs the generation.
         *
         * @return the generated wrappers keyed by wrapped class
         * @throws IOException when the generation fails on I/O
         */
        abstract Map<Class<?>, Class<?>> generate() throws IOException;
    }

    /**
     * Runs one runtime generation and makes every failure of it a named exception. A runtime
     * image without the {@code java.compiler} module cannot even link the compiler API: the
     * {@code NoClassDefFoundError} raised there means "no compiler" like a {@code null}
     * compiler does — and until 2.2.0 it escaped the engine as an unnamed error.
     *
     * @param cl the class a wrapper was requested for
     * @param kind the wrapper kind — to tell a stale pre-generated wrapper from none
     * @param generation the runtime generation to run
     * @return the generated wrappers keyed by wrapped class
     * @throws IOException when the generation fails on I/O
     */
    private static Map<Class<?>, Class<?>> compileAtRuntime(final Class<?> cl,
                                                            final XdStoragePreGeneratedWrappers.Kind kind,
                                                            final RuntimeGeneration generation) throws IOException {
        final Map<Class<?>, Class<?>> generated;
        try {
            generated = generation.generate();
        } catch (final XdStorageWrapperGenerationException failure) {
            throw failure.isCompilerMissing() ? withoutCompiler(cl, kind, failure.getCause()) : failure;
        } catch (final NoClassDefFoundError error) {
            if (XdStorageWrapperGenerationException.isMissingCompilerApi(error)) {
                throw withoutCompiler(cl, kind, error);
            }
            throw error;
        }
        if (generated.get(cl) == null) {
            throw new XdStorageWrapperGenerationException("XdStorage cannot create the wrapper class for "
                    + cl.getName() + ": the runtime generation produced no wrapper for it");
        }
        return generated;
    }

    /**
     * The failure of a runtime that has no Java compiler. It names a STALE pre-generated
     * wrapper when the classpath carries one — "no wrapper at all" would send the operator
     * looking in the wrong place, and only a JDK can compile over a stale wrapper.
     *
     * @param cl the class a wrapper was requested for
     * @param kind the wrapper kind
     * @param cause what revealed the missing compiler, may be {@code null}
     * @return the exception to throw
     */
    private static XdStorageWrapperGenerationException withoutCompiler(
            final Class<?> cl, final XdStoragePreGeneratedWrappers.Kind kind, final Throwable cause) {
        final String stale = XdStoragePreGeneratedWrappers.staleDescription(kind, cl);
        return stale != null ? XdStorageWrapperGenerationException.stale(cl, stale)
                : XdStorageWrapperGenerationException.noCompiler(cl, cause);
    }

    /**
     * Puts every generated wrapper into the cache — a generation may cover a whole graph.
     *
     * @param cache the kind's cache
     * @param requested the class the wrapper was requested for
     * @param generated the generation's result
     */
    private static void cacheAll(final Map<Class<?>, Class<?>> cache, final Class<?> requested,
                                final Map<Class<?>, Class<?>> generated) {
        for (final Map.Entry<Class<?>, Class<?>> e : generated.entrySet()) {
            if (e.getValue() != null) cache.put(e.getKey(), e.getValue());
        }
    }

    /**
     * The cached {@code isDummyUnmodifiableWrapper} marker method of a wrapper class.
     *
     * @param wrapperClass the generated wrapper class
     * @return the reflective method handle, or {@code null} when the class does not declare it
     */
    public static Method getCheckingWrappableMethod(final Class<?> wrapperClass) {
        return checkingWrappableMethod.computeIfAbsent(wrapperClass, cl -> {
            try { return cl.getDeclaredMethod("isDummyUnmodifiableWrapper"); } catch (Exception e) { return null; }
        });
    }

    /**
     * The cached {@code isDummySimpleWrapper} marker method of a wrapper class.
     *
     * @param wrapperClass the generated wrapper class
     * @return the reflective method handle, or {@code null} when the class does not declare it
     */
    public static Method getCheckingSimpleWrappableMethod(final Class<?> wrapperClass) {
        return checkingWrappableMethod.computeIfAbsent(wrapperClass, cl -> {
            try { return cl.getDeclaredMethod("isDummySimpleWrapper"); } catch (Exception e) { return null; }
        });
    }

    /**
     * Reverse look-up over the unmodifiable-wrapper cache: the entity class a generated wrapper
     * was compiled for (identity comparison).
     *
     * @param clWrapper the wrapper class to resolve
     * @return the wrapped entity class, or {@code null} when the class is not a known wrapper
     */
    public static Class<?> getWrappedClass(final Class<?> clWrapper) {
        for (final Map.Entry<Class<?>, Class<?>> entry : classesUnmodifiableWrappers.entrySet()) {
            if (entry.getValue() == clWrapper) return entry.getKey();
        }
        return null;
    }
}
