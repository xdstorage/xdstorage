package io.github.xdstorage.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;

/**
 * The BUILD-TIME path of wrapper generation (FP-3, docs/tasks/2.2.0/08): wrappers generated
 * and compiled while the application is built, shipped inside the application's own
 * classes, found at runtime through one small manifest. With them the engine needs no
 * compiler and never touches {@code ./gencode} — which is what lets it run on a JRE and
 * from a read-only working directory.
 *
 * <p>The manifest is {@link #MANIFEST_PATH}, read through the ENTITY's class loader (so it
 * sits next to the entity it describes, in the same classes directory or jar). One entry
 * per entity and wrapper kind: {@code <entity>|<kind>=<wrapper simple name>|<fingerprint>}.
 * A wrapper is accepted only when it was generated for the class as it is loaded NOW (the
 * fingerprint, see {@link XdStorageWrapperFingerprint}) and when it is the entity class's
 * own loader that defines it — never a wrapper loaded from somewhere else.</p>
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public final class XdStoragePreGeneratedWrappers {

    /** The manifest's resource path inside the application's classes. */
    public static final String MANIFEST_PATH = "META-INF/xdstorage/aot-wrappers.properties";

    /** The package every wrapper class lives in, generated at build time or at runtime. */
    static final String WRAPPERS_PACKAGE = XdStorageClassGenerator.CLASSES_PACKAGE;

    private static final Logger log = LoggerFactory.getLogger(XdStoragePreGeneratedWrappers.class);

    /** The three wrapper kinds the engine asks for; {@code suffix} is the manifest's and the generator's. */
    public enum Kind {
        /** The lazy-loading proxy of an entity. */
        SIMPLE("simple"),
        /** The read-only view of an entity. */
        UNMODIFIABLE("unmodifiable"),
        /** The deprecated id-observation wrapper. */
        OBSERVABLE("observable");

        final String key;

        Kind(final String key) {
            this.key = key;
        }
    }

    /** One merged manifest per entity class (empty when there is none); it lives and dies with
     * the class, so no loader is pinned. */
    private static final ClassValue<Properties> MANIFESTS = new ClassValue<Properties>() {
        @Override
        protected Properties computeValue(final Class<?> type) {
            return readManifest(type.getClassLoader());
        }
    };

    private XdStoragePreGeneratedWrappers() {
    }

    /**
     * The pre-generated wrapper of the given kind for the entity class, when the classpath
     * carries a usable one.
     *
     * @param kind the wrapper kind
     * @param entity the class as it is loaded in this JVM
     * @return the wrapper class, or {@code null} when there is none the engine may use — the
     *         caller then generates at runtime
     */
    public static Class<?> lookup(final Kind kind, final Class<?> entity) {
        final ClassLoader loader = entity.getClassLoader();
        if (loader == null) {
            return null;    // a bootstrap class is never an application entity
        }
        final Properties manifest;
        try {
            manifest = MANIFESTS.get(entity);
        } catch (final RuntimeException unreadable) {
            log.warn("the pre-generated wrapper manifest of {} cannot be read — falling back to runtime "
                    + "generation: {}", entity.getName(), unreadable.toString());
            return null;
        }
        if (manifest.isEmpty()) {
            return null;    // nothing was pre-generated where this class comes from
        }
        final String entry = manifest.getProperty(entity.getName() + "|" + kind.key);
        if (entry == null) {
            return null;
        }
        final int bar = entry.indexOf('|');
        final String wrapperName = bar < 0 ? entry : entry.substring(0, bar);
        final String stale = stale(kind, entity, manifest);
        if (stale != null) {
            log.warn("the pre-generated {} wrapper for {} is STALE: {} — falling back to runtime generation; "
                    + "rebuild the application to refresh the wrappers", kind.key, entity.getName(), stale);
            return null;
        }
        final Class<?> candidate;
        try {
            candidate = Class.forName(WRAPPERS_PACKAGE + "." + wrapperName, false, loader);
        } catch (final ClassNotFoundException | LinkageError missing) {
            log.warn("the pre-generated wrapper manifest names {} for {} but the class cannot be loaded ({}) — "
                    + "falling back to runtime generation", wrapperName, entity.getName(), missing.toString());
            return null;
        }
        if (candidate.getSuperclass() != entity || candidate.getClassLoader() != loader) {
            log.warn("the pre-generated wrapper {} does not extend this copy of {} — falling back to runtime "
                    + "generation", wrapperName, entity.getName());
            return null;
        }
        log.debug("using the pre-generated {} wrapper {} for {}", kind.key, candidate.getName(), entity.getName());
        return candidate;
    }

    /**
     * Describes a pre-generated wrapper of this kind that IS on the classpath but STALE: it
     * was generated for another shape of the class (or by another engine version). It lets a
     * runtime without a compiler say "stale" instead of "none".
     *
     * @param kind the wrapper kind
     * @param entity the class as it is loaded in this JVM
     * @return {@code generated for fingerprint <recorded>, the class as loaded has <expected>},
     *         or {@code null} when there is no stale wrapper of this kind
     */
    public static String staleDescription(final Kind kind, final Class<?> entity) {
        if (entity.getClassLoader() == null) {
            return null;
        }
        try {
            return stale(kind, entity, MANIFESTS.get(entity));
        } catch (final RuntimeException unreadable) {
            return null;
        }
    }

    private static String stale(final Kind kind, final Class<?> entity, final Properties manifest) {
        if (manifest == null || manifest.isEmpty()) {
            return null;
        }
        final String entry = manifest.getProperty(entity.getName() + "|" + kind.key);
        if (entry == null) {
            return null;
        }
        final int bar = entry.indexOf('|');
        final String fingerprint = bar < 0 ? "" : entry.substring(bar + 1);
        final String expected = XdStorageWrapperFingerprint.of(entity);
        return expected.equals(fingerprint) ? null
                : "generated for fingerprint " + fingerprint + ", the class as loaded has " + expected;
    }

    /** Merges every manifest the loader can see (several jars on one classpath may each carry one). */
    static Properties readManifest(final ClassLoader loader) {
        final Properties merged = new Properties();
        try {
            final Enumeration<java.net.URL> urls = loader.getResources(MANIFEST_PATH);
            while (urls.hasMoreElements()) {
                try (InputStream in = urls.nextElement().openStream()) {
                    merged.load(in);
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read " + MANIFEST_PATH + ": " + e, e);
        }
        return merged;
    }
}
