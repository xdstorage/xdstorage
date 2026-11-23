package io.github.xdstorage.utils;

import io.github.xdstorage.code.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Codegen cache for the engine's runtime-generated wrapper proxies (simple, unmodifiable,
 * observable): each entity class is compiled to a wrapper subclass once per JVM, cached in
 * {@link ConcurrentHashMap}s, with compilation serialized by a global lock and a per-thread
 * generation stack that short-circuits self-referencing entity graphs to the dummy wrappers.
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
     * Returns the generated simple wrapper of the class, compiling it on first use; classes
     * caught in a self-referencing generation on this thread resolve to the dummy wrapper.
     *
     * @param cl the entity class to wrap
     * @return the generated wrapper class, or the dummy wrapper when nothing was generated
     * @throws IOException when source generation or compilation fails (the cache stays clean)
     */
    public static Class<?> getClassSimpleWrapper(final Class<?> cl) throws IOException {
        Class<?> existing = classesSimpleWrappers.get(cl);
        if (existing != null) return existing;

        if (currentThreadCompilationStack.get().contains(cl)) return XdStorageDummySimpleWrapper.class;

        COMPILATION_LOCK.lock();
        try {
            existing = classesSimpleWrappers.get(cl);
            if (existing != null) return existing;

            currentThreadCompilationStack.get().add(cl);
            try {
                Map<Class<?>, Class<?>> gen = XdStorageClassGenerator.generateSimpleWrapper(cl);
                if (gen.isEmpty()) {
                    classesSimpleWrappers.put(cl, XdStorageDummySimpleWrapper.class);
                } else {
                    for (Map.Entry<Class<?>, Class<?>> e : gen.entrySet()) {
                        if (e.getValue() != null) classesSimpleWrappers.put(e.getKey(), e.getValue());
                    }
                }
            } finally { currentThreadCompilationStack.get().remove(cl); }
            return classesSimpleWrappers.get(cl);
        } catch (IOException e) { classesSimpleWrappers.remove(cl); throw e; } finally { COMPILATION_LOCK.unlock(); }
    }

    /**
     * Returns the generated unmodifiable (lazy-loading) wrapper of the class, compiling it on
     * first use; classes caught in a self-referencing generation on this thread resolve to the
     * dummy wrapper.
     *
     * @param cl the entity class to wrap
     * @return the generated wrapper class, or the dummy wrapper when nothing was generated
     * @throws IOException when source generation or compilation fails (the cache stays clean)
     */
    public static Class<?> getClassUnmodifiableWrapper(final Class<?> cl) throws IOException {
        Class<?> existing = classesUnmodifiableWrappers.get(cl);
        if (existing != null) return existing;

        if (currentThreadCompilationStack.get().contains(cl)) return XdStorageDummyUnmodifiableWrapper.class;

        COMPILATION_LOCK.lock();
        try {
            existing = classesUnmodifiableWrappers.get(cl);
            if (existing != null) return existing;

            currentThreadCompilationStack.get().add(cl);
            try {
                Map<Class<?>, Class<?>> gen = XdStorageClassGenerator.generateUnmodifiableWrapper(cl);
                if (gen.isEmpty()) {
                    classesUnmodifiableWrappers.put(cl, XdStorageDummyUnmodifiableWrapper.class);
                } else {
                    for (Map.Entry<Class<?>, Class<?>> e : gen.entrySet()) {
                        if (e.getValue() != null) classesUnmodifiableWrappers.put(e.getKey(), e.getValue());
                    }
                }
            } finally { currentThreadCompilationStack.get().remove(cl); }
            return classesUnmodifiableWrappers.get(cl);
        } catch (IOException e) { classesUnmodifiableWrappers.remove(cl); throw e; } finally { COMPILATION_LOCK
                .unlock(); }
    }

    /**
     * Returns the generated observable wrapper of the class, compiling it on first use under
     * the global compilation lock (no recursion guard on this path).
     *
     * @param cl the entity class to wrap
     * @return the generated wrapper class, or the dummy wrapper when nothing was generated
     * @throws IOException when source generation or compilation fails
     */
    public static Class<?> getClassObservableWrapper(final Class<?> cl) throws IOException {
        Class<?> existing = classesObservableWrappers.get(cl);
        if (existing != null) return existing;

        COMPILATION_LOCK.lock();
        try {
            existing = classesObservableWrappers.get(cl);
            if (existing != null) return existing;

            Map<Class<?>, Class<?>> gen = XdStorageClassGenerator.generateObservableWrapper(cl);
            for (Map.Entry<Class<?>, Class<?>> e : gen.entrySet()) {
                if (e.getValue() != null) classesObservableWrappers.put(e.getKey(), e.getValue());
            }
            return classesObservableWrappers.get(cl);
        } finally { COMPILATION_LOCK.unlock(); }
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
