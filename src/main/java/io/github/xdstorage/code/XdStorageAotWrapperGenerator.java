package io.github.xdstorage.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The BUILD-TIME wrapper generator (FP-3, docs/tasks/2.2.0/08): generates and compiles, while
 * an application is built, the very wrapper classes the engine would otherwise compile at
 * runtime with the system compiler — and records them in the manifest the engine reads
 * instead ({@link XdStoragePreGeneratedWrappers}). An application built this way needs no
 * JDK and no writable working directory at runtime: it runs on a JRE.
 *
 * <p>Build tools call {@link #generate}. The work always runs against the engine version
 * found on the scanned classpath — the one whose runtime will look the wrappers up — never
 * against the copy the build tool itself was loaded with. The sources come from the same
 * generators the runtime uses, so a pre-generated wrapper and a runtime-generated one differ
 * in nothing but where they were compiled.</p>
 *
 * <p>The engine's own classes are entities too (B+trees, their nodes, index records): the
 * classpath is scanned as well as the application's classes, so they get their wrappers the
 * same way. Engine-internal — the Maven plugin is the supported entry point; this class is
 * public for build tools only.</p>
 */
public final class XdStorageAotWrapperGenerator {

    /** The manifest's resource path, inside the output directory. */
    public static final String MANIFEST_PATH = XdStoragePreGeneratedWrappers.MANIFEST_PATH;

    private static final Logger log = LoggerFactory.getLogger(XdStorageAotWrapperGenerator.class);

    private XdStorageAotWrapperGenerator() {
    }

    /** What one run produced. */
    public static final class Result {
        /** Binary names of the entity classes whose wrappers were written by this run. */
        public final List<String> generated = new ArrayList<>();
        /** Binary names of the entity classes whose wrappers on disk were already current. */
        public final List<String> upToDate = new ArrayList<>();
        /** Binary names of all wrapper classes written by this run. */
        public final List<String> wrappers = new ArrayList<>();
        /** How many classes were looked at — every class of the scanned roots, entity or not. */
        public int scannedClasses;

        Result(final int scannedClasses) {
            this.scannedClasses = scannedClasses;
        }

        /** {@link #generate} hands its result across the loader boundary as lines of text. */
        private static Result fromReport(final String[] report) {
            final Result result = new Result(Integer.parseInt(report[SCANNED]));
            lines(result.generated, report[GENERATED]);
            lines(result.upToDate, report[UP_TO_DATE]);
            lines(result.wrappers, report[WRAPPERS]);
            return result;
        }

        private static void lines(final List<String> into, final String report) {
            for (final String line : report.split("\n")) {
                if (!line.isEmpty()) {
                    into.add(line);
                }
            }
        }

        /** The entity classes this run dealt with, written or already current. */
        public int entities() {
            return generated.size() + upToDate.size();
        }
    }

    /**
     * Scans the roots for entity classes and writes their wrappers and the manifest into the
     * output directory. A wrapper whose recorded fingerprint and class file are already
     * current is not written again.
     *
     * @param roots directories and jars to scan for entity classes — the application's
     *              classes and everything on its classpath, since the engine's own classes
     *              need wrappers too
     * @param classpath everything else the wrappers must link against; joined with the
     *                   roots for the compile
     * @param outputDirectory where the wrapper class files and the manifest go — normally
     *                        the application's own classes directory, so they ship with it
     * @param release the Java release to compile the wrappers for ({@code "8"}, {@code "11"},
     *                …) — the application's own target; {@code null} for the build JDK's
     *                default
     * @return what the run produced
     * @throws IOException when there is no compiler in this JVM, a wrapper does not
     *                     compile, or the output cannot be written
     */
    public static Result generate(final List<Path> roots, final List<Path> classpath, final Path outputDirectory,
                                  final String release) throws IOException {
        final List<Path> search = new ArrayList<>(roots);
        search.addAll(classpath);
        final URLClassLoader isolated = new URLClassLoader(urls(search), platformLoader());
        try {
            final Class<?> own;
            try {
                own = Class.forName(XdStorageAotWrapperGenerator.class.getName(), true, isolated);
            } catch (final ClassNotFoundException e) {
                throw new IOException("no XdStorage engine was found on the scanned classpath (" + search
                        + ") — nothing to generate wrappers with", e);
            } catch (final LinkageError e) {
                throw new IOException("the engine on the scanned classpath cannot start — it needs a class that "
                        + "is not there (" + e + "); its dependencies belong on the classpath too", e);
            }
            final String[] report;
            try {
                final Method work = own.getMethod("generateHereReported", List.class, Path.class, String.class);
                report = (String[]) work.invoke(null, roots, outputDirectory, release);
            } catch (final InvocationTargetException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof NoClassDefFoundError) {
                    throw new IOException("the engine on the scanned classpath needs a class that is not there ("
                            + cause.getMessage() + ") — put the engine's dependencies on the classpath too", cause);
                }
                if (cause instanceof IOException) { throw (IOException) cause; }
                if (cause instanceof RuntimeException) { throw (RuntimeException) cause; }
                if (cause instanceof Error) { throw (Error) cause; }
                throw new IOException(cause);
            } catch (final ReflectiveOperationException e) {
                throw new IOException("the engine on the scanned classpath cannot run its build-time wrapper "
                        + "generator: " + e, e);
            }
            final Result result = Result.fromReport(report);
            log.info("XdStorage build-time wrappers: {} class(es) scanned, {} entity class(es) written, {} already "
                    + "current, {} wrapper class(es) into {}", result.scannedClasses, result.generated.size(),
                    result.upToDate.size(), result.wrappers.size(), outputDirectory);
            return result;
        } finally {
            isolated.close();
        }
    }

    /** The report's slots: the scanned count, then the generated, the up-to-date, the wrapper names. */
    private static final int SCANNED = 0;
    private static final int GENERATED = 1;
    private static final int UP_TO_DATE = 2;
    private static final int WRAPPERS = 3;

    /**
     * The work itself. It must run inside the engine copy that sits on the scanned classpath
     * — {@link #generate} arranges that; calling it directly is for that copy and for tests.
     *
     * @param roots directories and jars to scan; their loader is this class's own
     * @param outputDirectory where the wrapper class files and the manifest go
     * @param release the Java release to compile for, {@code null} for the build JDK default
     * @return what the run produced
     * @throws IOException when there is no compiler, a wrapper does not compile, or the
     *                     output cannot be written
     */
    public static Result generateHere(final List<Path> roots, final Path outputDirectory, final String release)
            throws IOException {
        return generateScanning(roots, outputDirectory, release);
    }

    /**
     * {@link #generateHere} for a caller in another loader — {@link #generate} calls it by
     * reflection on the engine copy it isolated, and a loader boundary passes only JDK types:
     * so the result travels as four strings (the scanned count; then the generated, the
     * up-to-date and the written wrapper names, one per line).
     *
     * @param roots directories and jars to scan
     * @param outputDirectory where the wrapper class files and the manifest go
     * @param release the Java release to compile for, {@code null} for the build JDK default
     * @return the report, in the fixed slot order
     * @throws IOException when there is no compiler, a wrapper does not compile, or the
     *                     output cannot be written
     */
    public static String[] generateHereReported(final List<Path> roots, final Path outputDirectory,
                                                final String release) throws IOException {
        final Result result = generateScanning(roots, outputDirectory, release);
        return new String[]{String.valueOf(result.scannedClasses), String.join("\n", result.generated),
                String.join("\n", result.upToDate), String.join("\n", result.wrappers)};
    }

    private static Result generateScanning(final List<Path> roots, final Path outputDirectory, final String release)
            throws IOException {
        final TreeSet<String> names = new TreeSet<>();
        for (final Path root : roots) {
            collectClassNames(root, names);
        }
        final ClassLoader engine = XdStorageAotWrapperGenerator.class.getClassLoader();
        final List<Class<?>> entities = new ArrayList<>();
        for (final String name : names) {
            final Class<?> cl;
            try {
                cl = Class.forName(name, false, engine);
            } catch (final ClassNotFoundException | LinkageError | SecurityException notLoadable) {
                continue;    // a class this classpath cannot link is no entity to generate for
            }
            if (isEntityToWrap(cl)) {
                entities.add(cl);
            }
        }
        final List<Path> classpath = new ArrayList<>(roots);
        classpath.addAll(engineUrls());
        final Result result = generateLoaded(entities, classpath, outputDirectory, release);
        result.scannedClasses = names.size();
        return result;
    }

    /**
     * Writes the wrappers of exactly these entity classes and records them in the manifest.
     * The classes must be loaded by the same loader as this generator.
     *
     * @param entities the entity classes as they are loaded now
     * @param classpath what the wrappers must link against (the engine included)
     * @param outputDirectory where the wrapper class files and the manifest go
     * @param release the Java release to compile for, {@code null} for the build JDK default
     * @return what the run produced
     * @throws IOException when there is no compiler, a wrapper does not compile, or the
     *                     output cannot be written
     */
    public static Result generateLoaded(final List<Class<?>> entities, final List<Path> classpath,
                                       final Path outputDirectory, final String release) throws IOException {
        final Result result = new Result(entities.size());
        final Properties manifest = readManifest(outputDirectory);
        final Map<String, String> sources = new TreeMap<>();
        final Map<String, String> expected = new TreeMap<>();     // entity -> "kind=wrapper|fingerprint;…"
        for (final Class<?> entity : entities) {
            final String record = recordFor(entity);
            if (record.equals(recorded(manifest, entity)) && wrappersPresent(outputDirectory, record)) {
                result.upToDate.add(entity.getName());
                continue;
            }
            try {
                XdStorageClassGenerator.simpleWrapperSource(entity, sources);
                XdStorageClassGenerator.unmodifiableWrapperSource(entity, sources);
                XdStorageClassGenerator.observableWrapperSource(entity, sources);
            } catch (final RuntimeException notWrappable) {
                throw new IOException("the wrappers of " + entity.getName() + " cannot be generated: "
                        + notWrappable.getMessage(), notWrappable);
            }
            expected.put(entity.getName(), record);
        }
        if (!expected.isEmpty()) {
            compile(sources.values(), sources.keySet(), classpath, outputDirectory, release);
        }
        for (final Map.Entry<String, String> entry : expected.entrySet()) {
            if (!wrappersPresent(outputDirectory, entry.getValue())) {
                throw new IOException("the wrappers of " + entry.getKey() + " were compiled but are not in "
                        + outputDirectory);
            }
            putRecord(manifest, entry.getKey(), entry.getValue());
            result.generated.add(entry.getKey());
            for (final String wrapper : wrapperNames(entry.getValue())) {
                result.wrappers.add(wrapper);
            }
        }
        if (!expected.isEmpty()) {
            writeManifest(manifest, outputDirectory);
        }
        return result;
    }

    // === what an entity's three wrappers are called, and the fingerprint they were built for ===

    private static String recordFor(final Class<?> entity) {
        final String fingerprint = XdStorageWrapperFingerprint.of(entity);
        final String pkg = XdStoragePreGeneratedWrappers.WRAPPERS_PACKAGE;
        // the generators' own naming — whatever qualifier they give the class
        final XdStorageAbstractClassCodeGenerator unmodifiable = entity
                == io.github.xdstorage.object.XdStorageIdentifiableObject.class
                ? new XdStorageUnmodifiableXdStorageObjectWrapperClassCodeGenerator()
                : new XdStorageUnmodifiableWrapperClassCodeGenerator();
        final String[] names = {
                new XdStorageSimpleWrapperClassCodeGenerator().buildClassName(pkg, entity),
                unmodifiable.buildClassName(pkg, entity),
                new XdStorageObservableWrapperClassCodeGenerator().buildClassName(pkg, entity)};
        final XdStoragePreGeneratedWrappers.Kind[] kinds = XdStoragePreGeneratedWrappers.Kind.values();
        final StringBuilder record = new StringBuilder();
        for (int i = 0; i < kinds.length; i++) {
            if (record.length() > 0) {
                record.append(';');
            }
            record.append(kinds[i].key).append('=').append(names[i]).append('|').append(fingerprint);
        }
        return record.toString();
    }

    /**
     * Records an entity's wrappers in the manifest: one property per wrapper kind.
     *
     * @param manifest the manifest being written
     * @param entity the entity's binary name
     * @param the record: {@code kind=wrapper|fingerprint} parts joined by {@code ;}
     */
    private static void putRecord(final Properties manifest, final String entity, final String record) {
        for (final String part : record.split(";")) {
            final int equals = part.indexOf('=');
            if (equals > 0) {
                manifest.setProperty(entity + "|" + part.substring(0, equals), part.substring(equals + 1));
            }
        }
    }

    /**
     * Reads an entity's record back from the manifest, in this class's order.
     *
     * @param manifest the manifest read from the output directory
     * @param entity the entity class
     * @return the record as {@link #putRecord} writes it, or {@code null} when a kind is missing
     */
    private static String recorded(final Properties manifest, final Class<?> entity) {
        final StringBuilder record = new StringBuilder();
        for (final XdStoragePreGeneratedWrappers.Kind kind : XdStoragePreGeneratedWrappers.Kind.values()) {
            final String value = manifest.getProperty(entity.getName() + "|" + kind.key);
            if (value == null) {
                return null;
            }
            if (record.length() > 0) {
                record.append(';');
            }
            record.append(kind.key).append('=').append(value);
        }
        return record.toString();
    }

    private static boolean isEntityToWrap(final Class<?> cl) {
        final int modifiers = cl.getModifiers();
        if (cl.isInterface() || cl.isAnnotation() || cl.isEnum() || Modifier.isAbstract(modifiers)
                || !isPublicThroughout(cl)) {
            return false;    // never instantiated, or not extendable from the wrappers' package
        }
        if (IXdStorageSimpleWrapper.class.isAssignableFrom(cl) || IXdStorageUnmodifiableWrapper.class
                .isAssignableFrom(cl) || io.github.xdstorage.observing.IXdStorageIdObservableWrapper.class
                .isAssignableFrom(cl)) {
            return false;    // a wrapper extends its entity and so inherits the id — it is no entity
        }
        return XdStorageWrapperFingerprint.isEntity(cl);
    }

    private static boolean isPublicThroughout(Class<?> cl) {
        for (Class<?> c = cl; c != null; c = c.getEnclosingClass()) {
            if (!Modifier.isPublic(c.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    // === the manifest ===

    private static Properties readManifest(final Path outputDirectory) throws IOException {
        final Properties manifest = new Properties();
        final Path file = outputDirectory.resolve(MANIFEST_PATH);
        if (Files.exists(file)) {
            try (java.io.InputStream in = Files.newInputStream(file)) {
                manifest.load(in);
            }
        }
        return manifest;
    }

    private static void writeManifest(final Properties manifest, final Path outputDirectory) throws IOException {
        final Path file = outputDirectory.resolve(MANIFEST_PATH);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            manifest.store(out, "XdStorage wrappers generated at build time (one entry per entity class; see "
                    + "XdStoragePreGeneratedWrappers). Regenerate when the entity classes or the engine change.");
        }
    }

    private static List<String> wrapperNames(final String record) {
        final List<String> names = new ArrayList<>();
        for (final String part : record.split(";")) {
            final int equals = part.indexOf('=');
            final int bar = part.indexOf('|', equals);
            if (equals > 0 && bar > equals) {
                names.add(XdStoragePreGeneratedWrappers.WRAPPERS_PACKAGE + "." + part.substring(equals + 1, bar));
            }
        }
        return names;
    }

    private static boolean wrappersPresent(final Path outputDirectory, final String record) {
        for (final String wrapper : wrapperNames(record)) {
            if (!Files.exists(outputDirectory.resolve(wrapper.replace('.', File.separatorChar) + ".class"))) {
                return false;
            }
        }
        return !record.isEmpty();
    }

    // === the compile ===

    private static void compile(final Iterable<String> sources, final Iterable<String> names, final List<Path>
            classpath, final Path outputDirectory, final String release) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("build-time wrapper generation needs a JDK: this JVM has no system Java "
                    + "compiler — run the build on a JDK");
        }
        final List<JavaFileObject> units = new ArrayList<>();
        final java.util.Iterator<String> namesIt = names.iterator();
        for (final String source : sources) {
            units.add(new XdStorageJavaSourceFromString(namesIt.next(), source));
        }
        final StringBuilder path = new StringBuilder();
        for (final Path entry : classpath) {
            if (path.length() > 0) {
                path.append(File.pathSeparator);
            }
            path.append(entry.toString());
        }
        final List<String> options = new ArrayList<>(Arrays.asList("-d", outputDirectory.toString(), "-classpath",
                path.toString(), "-encoding", "UTF-8", "-proc:none", "-nowarn"));
        if (release != null && !release.isEmpty()) {
            options.addAll(releaseOptions(release));
        }
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Files.createDirectories(outputDirectory);
        final boolean ok = compiler.getTask(null, null, diagnostics, options, null, units).call();
        if (!ok) {
            throw new IOException("the build-time wrapper sources did not compile (" + units.size() + " file(s)) "
                    + "for release " + (release == null ? "of the build JDK" : release) + ": " + diagnostics
                    .getDiagnostics());
        }
    }

    private static List<String> releaseOptions(final String release) throws IOException {
        final int requested;
        try {
            requested = Integer.parseInt(release.startsWith("1.") ? release.substring(2) : release);
        } catch (final NumberFormatException e) {
            throw new IOException("'" + release + "' is not a Java release number", e);
        }
        final int running = ownMajorVersion();
        if (requested > running) {
            throw new IOException("release " + requested + " needs JDK " + requested + " or newer; this build runs "
                    + "on JDK " + running);
        }
        if (running >= 9) {
            return Collections.singletonList("--release=" + requested);
        }
        if (requested > 8) {
            throw new IOException("release " + requested + " needs JDK 9 or newer; this build runs on JDK " + running);
        }
        return Arrays.asList("-source", String.valueOf(requested), "-target", String.valueOf(requested));
    }

    private static int ownMajorVersion() {
        final String specified = System.getProperty("java.specification.version", "1.8");
        return Integer.parseInt(specified.startsWith("1.") ? specified.substring(2) : specified);
    }

    // === scanning ===

    private static void collectClassNames(final Path root, final TreeSet<String> names) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    final String relative = root.relativize(p).toString();
                    names.add(relative.substring(0, relative.length() - ".class".length()).replace(File.separatorChar,
                            '.'));
                });
            }
            return;
        }
        try (JarFile jar = new JarFile(root.toFile())) {
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }
        } catch (final IOException notAJar) {
            log.debug("{} is neither a directory nor a jar — not scanned for entities ({})", root, notAJar
                    .toString());
        }
    }

    private static List<Path> engineUrls() {
        final ClassLoader engine = XdStorageAotWrapperGenerator.class.getClassLoader();
        final List<Path> urls = new ArrayList<>();
        if (engine instanceof URLClassLoader) {
            for (final URL url : ((URLClassLoader) engine).getURLs()) {
                urls.add(new File(url.getFile()).toPath());
            }
            return urls;
        }
        for (final String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                urls.add(new File(entry).toPath());
            }
        }
        return urls;
    }

    private static URL[] urls(final List<Path> paths) throws IOException {
        final List<URL> urls = new ArrayList<>();
        for (final Path path : paths) {
            if (Files.exists(path)) {
                urls.add(path.toUri().toURL());
            }
        }
        return urls.toArray(new URL[0]);
    }

    private static ClassLoader platformLoader() {
        return ClassLoader.getSystemClassLoader().getParent();
    }

}
