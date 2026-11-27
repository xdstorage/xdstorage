package io.github.xdstorage.helpers;

import org.apache.commons.lang3.ClassUtils;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * The engine's built-in {@link IXdStorageSimpleTypeHelper}: classifies simple values,
 * renders them to their canonical on-disk strings and materializes them back. Fully
 * supports dynamic materialization of primitives, wrapper types and enums, and falls
 * back to any target type with a {@code String}-argument constructor (typical for id
 * types). {@code Class} values are resolved by name, with a registry fallback for
 * classes the application loaded through its own classloader.
 *
 * <p>The implementation is stateless and therefore thread-safe; a single instance is
 * shared by all readers and writers. Engine-internal — not part of the public API.</p>
 */
public class XdStorageDefaultSimpleTypeHelper implements IXdStorageSimpleTypeHelper {

    private static final Logger log = LoggerFactory.getLogger(XdStorageDefaultSimpleTypeHelper.class);

    /**
     * Creates the default helper. Stateless, so one shared instance is normally enough.
     */
    public XdStorageDefaultSimpleTypeHelper() {
    }

    @Override
    public boolean isSimpleType(final Class<?> cl, final Object object) {
        return cl == Class.class || cl == Date.class || cl == UUID.class
                || isJavaTimeSimple(cl)
                || cl.isEnum() || XdStorageObjectUtils.isSimpleType(cl, object);
    }

    /** UUID + the decided java.time set — stored as their canonical ISO-8601 strings. */
    private static boolean isJavaTimeSimple(final Class<?> cl) {
        return Instant.class.isAssignableFrom(cl)
                || LocalDate.class.isAssignableFrom(cl)
                || LocalDateTime.class.isAssignableFrom(cl)
                || Duration.class.isAssignableFrom(cl);
    }

    @Override
    public String simpleTypeToString(final Object object) {
        if (object instanceof Class<?>) {
            return ((Class<?>) object).getName();
        }
        return object.getClass() == Date.class ? String.valueOf(((Date) object).getTime()) : object.toString();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object simpleTypeFromString(final Class<?> cl, final String value) {
        if (cl == Class.class) {
            try {
                return ClassUtils.getClass(value);
            } catch (ClassNotFoundException e) {
                // BUG-A (docs/tasks/2.1.0/15): the value may belong to a class the application
                // loaded through its OWN classloader (an upgraded entity generation) — invisible
                // to ClassUtils. Resolve against the metadata registry before giving up: a null
                // here used to degrade journal/data records into null-keyed poison.
                final Class<?> loaded = io.github.xdstorage.utils.XdStorageClassMetadataRegistry
                        .findLoadedClassByName(value);
                if (loaded == null) {
                    log.error("class not found and not registered in this JVM: {}", value, e);
                }
                return loaded;
            }
        }
        if (cl == Boolean.class || cl == boolean.class)
            return Boolean.valueOf(value);
        if (cl == Character.class || cl == char.class)
            return Character.valueOf(value.charAt(0));
        if (cl == String.class)
            return value;
        if (cl == Date.class)
            return new Date(Long.valueOf(value));
        if (cl == UUID.class)
            return UUID.fromString(value);
        if (Instant.class.isAssignableFrom(cl))
            return Instant.parse(value);
        if (LocalDate.class.isAssignableFrom(cl))
            return LocalDate.parse(value);
        if (LocalDateTime.class.isAssignableFrom(cl))
            return LocalDateTime.parse(value);
        if (Duration.class.isAssignableFrom(cl))
            return Duration.parse(value);
        if (cl.isEnum()) {
            try {
                final Method m = cl.getMethod("valueOf", String.class);
                return m.invoke(cl, value);
            } catch (Exception e) {
                log.error("Failed to restore enum constant for class: " + cl.getName(), e);
                return null;
            }
        }

        try {
            final Constructor<?> c = cl.getConstructor(String.class);
            return c.newInstance(value);
        } catch (Exception e) {
            log.error("simple type from string error", e);
        }
        return null;
    }
}
