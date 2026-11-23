package io.github.xdstorage.utils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached locator of the wrapped-entity reflection field ({@code object}) inside the engine's
 * generated wrapper classes (simple, unmodifiable, observable). Lookups are cached per class in
 * {@link ConcurrentHashMap}s; results are immutable {@link java.lang.reflect.Field} handles,
 * so the locator is safe to use from any thread. Engine-internal — not part of the public API.
 */
public final class XdStorageWrapperFieldLocator {
    private static final Map<Class<?>, Field> simpleFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> unmodifiableFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> observableFields = new ConcurrentHashMap<>();

    private XdStorageWrapperFieldLocator() {}

    /**
     * The wrapped-entity field of a generated simple wrapper.
     *
     * @param clWrapper the wrapper class
     * @return the accessible {@code object} field, or {@code null} when the class has none
     */
    public static Field getSimpleWrapperObjectField(final Class<?> clWrapper) {
        return simpleFields.computeIfAbsent(clWrapper, cl -> {
            try { Field f = cl.getDeclaredField("object"); f.setAccessible(true); return f; } catch (Exception e) {
                    return null; }
        });
    }

    /**
     * The wrapped-entity field of a generated unmodifiable wrapper.
     *
     * @param clWrapper the wrapper class
     * @return the accessible {@code object} field, or {@code null} when the class has none
     */
    public static Field getUnmodifiableWrapperObjectField(final Class<?> clWrapper) {
        return unmodifiableFields.computeIfAbsent(clWrapper, cl -> {
            try { Field f = cl.getDeclaredField("object"); f.setAccessible(true); return f; } catch (Exception e) {
                    return null; }
        });
    }

    /**
     * The wrapped-entity field of a generated observable wrapper.
     *
     * @param clWrapper the wrapper class
     * @return the accessible {@code object} field, or {@code null} when the class has none
     */
    public static Field getObservableWrapperObjectField(final Class<?> clWrapper) {
        return observableFields.computeIfAbsent(clWrapper, cl -> {
            try { Field f = cl.getDeclaredField("object"); f.setAccessible(true); return f; } catch (Exception e) {
                    return null; }
        });
    }
}
