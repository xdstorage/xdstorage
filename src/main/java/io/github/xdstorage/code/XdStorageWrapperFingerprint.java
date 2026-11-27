package io.github.xdstorage.code;

import io.github.xdstorage.utils.XdStorageVersion;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The GENERATION FINGERPRINT of an entity class (FP-3, docs/tasks/2.2.0/08): a digest of
 * everything the wrapper generators read from the class. The build-time generator records
 * it in the manifest next to each wrapper it writes; the runtime computes it again for the
 * class it actually holds and refuses a pre-generated wrapper whose fingerprint differs —
 * the wrapper was generated for another shape of the class (a field added since without a
 * rebuild) or by another engine version. Without the check such a wrapper would silently
 * miss the new getter.
 *
 * <p>The description is built from raw reflection facts by this class alone — never from
 * {@code toString()} of reflection objects — and its parts are SORTED, because
 * {@code getDeclaredMethods()} returns its methods in an order no specification guarantees:
 * a JDK and a jlink image listed the same entity's methods in two different orders, and an
 * order-dependent fingerprint then called a perfectly good wrapper stale on the image. So a
 * wrapper generated on one JVM is accepted on another. Engine-internal — not part of the
 * public API.</p>
 */
final class XdStorageWrapperFingerprint {

    /** Bumped whenever this class changes what it digests. */
    private static final String SCHEME = "xdstorage-wrapper-fingerprint/1";

    private XdStorageWrapperFingerprint() {
    }

    /**
     * Computes the fingerprint of the entity class as it is loaded right now.
     *
     * @param entity the class a wrapper is generated for
     * @return 16 lower-case hex characters
     */
    static String of(final Class<?> entity) {
        final StringBuilder shape = new StringBuilder(SCHEME).append('|').append(XdStorageVersion.getVersion())
                .append('|').append(entity.getName());
        final XdStorageSimpleWrapperClassCodeGenerator probe = new XdStorageSimpleWrapperClassCodeGenerator();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            shape.append('|').append(c.getName());
        }
        shape.append("|id=").append(probe.getIdFieldName(entity));
        final XdStorageAbstractClassCodeGenerator.GenerationContext ctx = probe.collectMethodsAndClasses(entity);
        shape.append("|parent=").append(ctx.parentGetter == null ? "" : signature(ctx.parentGetter));
        appendAll(shape, "|simple:", ctx.fieldsGetters);
        appendAll(shape, "|strong:", ctx.strongGetters);
        appendAll(shape, "|lazy:", ctx.strongLoadByGetGetters);
        appendAll(shape, "|set:", ctx.setters);
        appendAll(shape, "|closed:", ctx.toCloseMethods);
        return Long.toHexString(fnv1a64(shape.toString()));
    }

    private static void appendAll(final StringBuilder shape, final String label, final List<Method> methods) {
        final List<String> signatures = new ArrayList<>(methods.size());
        for (final Method method : methods) {
            signatures.add(signature(method));
        }
        Collections.sort(signatures);
        shape.append(label);
        for (final String signature : signatures) {
            shape.append(signature).append(';');
        }
    }

    /** Name, parameter types, return type and declared exceptions — generics included, modifiers not. */
    private static String signature(final Method method) {
        final StringBuilder out = new StringBuilder(method.getName()).append('(');
        final Type[] parameters = method.getGenericParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(type(parameters[i]));
        }
        out.append("):").append(type(method.getGenericReturnType()));
        for (final Type exception : method.getGenericExceptionTypes()) {
            out.append('!').append(type(exception));
        }
        return out.toString();
    }

    private static String type(final Type type) {
        if (type instanceof Class<?>) {
            return ((Class<?>) type).getName();
        }
        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterized = (ParameterizedType) type;
            final StringBuilder out = new StringBuilder(type(parameterized.getRawType())).append('<');
            appendJoined(out, parameterized.getActualTypeArguments());
            return out.append('>').toString();
        }
        if (type instanceof GenericArrayType) {
            return type(((GenericArrayType) type).getGenericComponentType()) + "[]";
        }
        if (type instanceof TypeVariable<?>) {
            final TypeVariable<?> variable = (TypeVariable<?>) type;
            final StringBuilder out = new StringBuilder(variable.getName()).append(':');
            appendJoined(out, variable.getBounds());
            return out.toString();
        }
        if (type instanceof WildcardType) {
            final WildcardType wildcard = (WildcardType) type;
            final StringBuilder out = new StringBuilder("?");
            if (wildcard.getLowerBounds().length > 0) {
                out.append("super");
                appendJoined(out, wildcard.getLowerBounds());
            }
            for (final Type bound : wildcard.getUpperBounds()) {
                if (bound != Object.class) {
                    out.append("extends").append(type(bound));
                }
            }
            return out.toString();
        }
        return type.getTypeName();
    }

    private static void appendJoined(final StringBuilder out, final Type[] types) {
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(type(types[i]));
        }
    }

    private static long fnv1a64(final String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** Whether the class is an entity: an {@code XdStorageObjectId}-annotated field in its own hierarchy. */
    static boolean isEntity(final Class<?> cl) {
        for (Class<?> c = cl; c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(io.github.xdstorage.annotations.XdStorageObjectId.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
