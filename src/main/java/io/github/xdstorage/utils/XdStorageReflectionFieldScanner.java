package io.github.xdstorage.utils;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * The persistence-field scanner: walks an entity class and its superclasses collecting
 * JavaBeans properties (get/is getters) that have BOTH a backing reflection field and a type-
 * matched setter. A getter without a field is a computed property (skipped); a getter without
 * a setter is dropped with a loud WARN — silently persisting a field that cannot be read back
 * would lose data. Stateless and thread-safe. Engine-internal — not part of the public API.
 */
public final class XdStorageReflectionFieldScanner {
    private static final Logger log = LoggerFactory.getLogger(XdStorageReflectionFieldScanner.class);

    private XdStorageReflectionFieldScanner() {}

    /**
     * Scans the class hierarchy for persistence properties and binds each with its setter,
     * backing field and shape info.
     *
     * @param cl the class to scan (superclasses up to {@code Object} included)
     * @return the persistence fields by field name, possibly empty
     */
    public static Map<String, XdStorageObjectField> scanFields(final Class<?> cl) {
        final Map<String, XdStorageObjectField> properties = new HashMap<>();
        for (Class<?> clazz = cl; !clazz.equals(Object.class); clazz = clazz.getSuperclass()) {
            for (final Method getter : clazz.getDeclaredMethods()) {
                final String name = getter.getName();
                if (getter.getParameterTypes().length == 0 && (name.startsWith("get") || name.startsWith("is"))) {
                    final Class<?> tmp = getter.getReturnType();
                    if (tmp == void.class || tmp == Void.class) continue;
                    try {
                        final String fieldName;
                        final Method setter;
                        if (name.startsWith("get")) {
                            fieldName = name.substring(3, 4).toLowerCase() + name.substring(4);
                            setter = findSetter(clazz, "set" + name.substring(3), tmp);
                        } else {
                            fieldName = name.substring(2, 3).toLowerCase() + name.substring(3);
                            setter = findSetter(clazz, "set" + name.substring(2), tmp);
                        }

                        final Field field = scanFieldInHierarchy(clazz, fieldName);
                        if (field == null) {
                            continue; // вычисляемый геттер без поля — не свойство персистентности
                        }
                        if (setter == null) {
                            // ГРОМКИЙ сигнал вместо былого молчаливого проглатывания: поле с геттером,
                            // но без парного setter'а выпадало из персистентности — потеря данных
                            // (пример: поле isActive + геттер getIsActive + setter setActive вместо setIsActive)
                            log.warn("Field '{}' of class {} has a getter '{}' but no matching setter '{}' — "
                                            + "the field is DROPPED from persistence (data loss on reload)!",
                                    fieldName, clazz.getName(), name, setterNameFor(name));
                            continue;
                        }
                        properties.put(fieldName, new XdStorageObjectField(field, setter, getter,
                                XdStorageClassMetadataRegistry.buildFieldInfo(field, getter)));
                    } catch (Exception ignored) {}
                }
            }
        }
        return properties;
    }

    private static String setterNameFor(final String getterName) {
        return (getterName.startsWith("get") ? "set" + getterName.substring(3) : "set" + getterName.substring(2));
    }

    private static Method findSetter(final Class<?> clazz, final String name, final Class<?> parameterType) {
        try {
            return clazz.getMethod(name, parameterType);
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }

    private static Field scanFieldInHierarchy(Class<?> clazz, String fieldName) {
        for (Class<?> sc = clazz; !searchClassEqualsObject(sc); sc = sc.getSuperclass()) {
            try {
                Field f = sc.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static boolean searchClassEqualsObject(Class<?> sc) {
        return sc == null || sc.equals(Object.class);
    }
}
