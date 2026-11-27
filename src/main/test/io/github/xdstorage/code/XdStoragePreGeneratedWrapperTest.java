package io.github.xdstorage.code;

import io.github.xdstorage.code.XdStoragePreGeneratedWrappers.Kind;
import io.github.xdstorage.codegen.jredemo.JreAccount;
import io.github.xdstorage.codegen.jredemo.JreCustomer;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.utils.XdStorageProxyCompilationEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FP-3 (docs/tasks/2.2.0/08) — the BUILD-TIME wrappers: the generator writes them into the
 * application's classes with a manifest, and the engine takes them from there instead of
 * compiling. Pinned here: what makes a pre-generated wrapper acceptable (the class it was
 * generated for, as it is loaded NOW; the loader that loaded the class), what the engine
 * does with one that is not (refuses, and says why), and that the fingerprint it is judged
 * by follows the shape of the class and nothing else — not the order a JVM lists methods in.
 *
 * <p>The application's classes are loaded by their own loader, from a directory — as an
 * application loads them — never by this test's loader.</p>
 */
@DisplayName("Обёртки сборки (2.2.0/08): манифест, отпечаток, поиск")
class XdStoragePreGeneratedWrapperTest {

    private static final String DEMO_PACKAGE = "io.github.xdstorage.codegen.jredemo";
    private static final String DEMO_CLASSES = "io/github/xdstorage/codegen/jredemo";
    private static final List<Class<?>> DEMO_ENTITIES = Collections.<Class<?>>unmodifiableList(
            Arrays.asList(JreAccount.class, JreCustomer.class));
    private static final String NO_SUCH_FINGERPRINT = "0000000000000000";

    private static final Path TEST_CLASSES = codeSource();
    private static final Path TARGET_CLASSES = TEST_CLASSES.getParent().resolve("classes");

    /** The test's own temporary root. */
    private Path work;
    /** The application's classes directory: the demo classes, then their wrappers and the manifest. */
    private Path classes;

    @BeforeEach
    void applicationClasses() throws IOException {
        work = Files.createTempDirectory("xdstorage-aot-");
        classes = Files.createDirectories(work.resolve("classes"));
        try (Stream<Path> files = Files.walk(TEST_CLASSES.resolve(DEMO_CLASSES))) {
            for (final Path file : (Iterable<Path>) files::iterator) {
                if (file.toString().endsWith(".class")) {
                    final Path target = classes.resolve(TEST_CLASSES.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The roots of the classes compiled on the spot. */
    private final List<Path> compiledRoots = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        delete(work);
        for (final Path root : compiledRoots) {
            delete(root);
        }
    }

    // === what the engine accepts ===

    @Test
    @DisplayName("1. Обёртка найдена, порождена от этого класса, загружена тем же загрузчиком, своего вида")
    void lookupReturnsTheWrapperGeneratedForTheLoadedClass() throws Exception {
        generate();

        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            for (final Class<?> entity : demoEntitiesIn(application)) {
                for (final Kind kind : Kind.values()) {
                    final Class<?> wrapper = XdStoragePreGeneratedWrappers.lookup(kind, entity);
                    assertNotNull(wrapper, kind + " wrapper of " + entity.getName());
                    assertSame(entity, wrapper.getSuperclass(), kind + " wrapper of " + entity.getName());
                    assertSame(application, wrapper.getClassLoader());
                    assertTrue(kindMarker(kind).isAssignableFrom(wrapper), "the manifest's " + kind + " names a "
                            + "wrapper that is not one: " + wrapper);
                }
            }
        }
    }

    @Test
    @DisplayName("2. Манифеста нет в загрузчике класса: обёртка не найдена, генерация при первом обращении")
    void lookupFindsNothingWithoutAManifest() throws Exception {
        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            for (final Class<?> entity : demoEntitiesIn(application)) {
                for (final Kind kind : Kind.values()) {
                    assertNull(XdStoragePreGeneratedWrappers.lookup(kind, entity), kind + " of " + entity.getName());
                    assertNull(XdStoragePreGeneratedWrappers.staleDescription(kind, entity));
                }
            }
        }
    }

    @Test
    @DisplayName("3. Вид обёртки не генерировался: не найден, остальные виды находятся")
    void aKindThatWasNotGeneratedIsNotFound() throws Exception {
        generate();
        final Properties manifest = manifest();
        manifest.remove(JreCustomer.class.getName() + "|" + Kind.OBSERVABLE.key);
        store(manifest);

        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            final Class<?> entity = application.entity(JreCustomer.class);
            assertNull(XdStoragePreGeneratedWrappers.lookup(Kind.OBSERVABLE, entity));
            assertNull(XdStoragePreGeneratedWrappers.staleDescription(Kind.OBSERVABLE, entity));
            assertNotNull(XdStoragePreGeneratedWrappers.lookup(Kind.SIMPLE, entity));
        }
    }

    @Test
    @DisplayName("4. Отпечаток в манифесте не совпал: обёртка отклонена и описана как STALE, остальные виды — нет")
    void aStaleWrapperIsRefusedAndDescribed() throws Exception {
        generate();
        final Properties manifest = manifest();
        final String entry = manifest.getProperty(JreCustomer.class.getName() + "|" + Kind.SIMPLE.key);
        manifest.setProperty(JreCustomer.class.getName() + "|" + Kind.SIMPLE.key,
                entry.substring(0, entry.indexOf('|') + 1) + NO_SUCH_FINGERPRINT);
        store(manifest);

        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            final Class<?> entity = application.entity(JreCustomer.class);
            assertNull(XdStoragePreGeneratedWrappers.lookup(Kind.SIMPLE, entity));
            assertEquals("generated for fingerprint " + NO_SUCH_FINGERPRINT + ", the class as loaded has "
                    + XdStorageWrapperFingerprint.of(entity), XdStoragePreGeneratedWrappers.staleDescription(
                    Kind.SIMPLE, entity));
            assertNull(XdStoragePreGeneratedWrappers.staleDescription(Kind.UNMODIFIABLE, entity));
            assertNotNull(XdStoragePreGeneratedWrappers.lookup(Kind.UNMODIFIABLE, entity));
        }
    }

    @Test
    @DisplayName("5. Класса-файла обёртки нет: обёртка не найдена, остальные виды находятся")
    void aWrapperMissingFromTheClassesIsNotFound() throws Exception {
        generate();
        final String entry = manifest().getProperty(JreCustomer.class.getName() + "|" + Kind.SIMPLE.key);
        Files.delete(classes.resolve(XdStoragePreGeneratedWrappers.WRAPPERS_PACKAGE.replace('.', '/') + "/"
                + entry.substring(0, entry.indexOf('|')) + ".class"));

        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            final Class<?> entity = application.entity(JreCustomer.class);
            assertNull(XdStoragePreGeneratedWrappers.lookup(Kind.SIMPLE, entity));
            assertNotNull(XdStoragePreGeneratedWrappers.lookup(Kind.UNMODIFIABLE, entity));
        }
    }

    @Test
    @DisplayName("6. Манифест называет обёртку другого класса: отклонена")
    void aWrapperOfAnotherEntityIsRefused() throws Exception {
        generate();
        final Properties manifest = manifest();
        manifest.setProperty(JreCustomer.class.getName() + "|" + Kind.SIMPLE.key,
                manifest.getProperty(JreAccount.class.getName() + "|" + Kind.SIMPLE.key));
        store(manifest);

        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            assertNull(XdStoragePreGeneratedWrappers.lookup(Kind.SIMPLE, application.entity(JreCustomer.class)));
        }
    }

    @Test
    @DisplayName("7. Движок берёт обёртку сборки для всех трёх видов — без компиляции")
    void theEngineUsesTheBuildTimeWrapperForEveryKind() throws Exception {
        generate();

        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            final Class<?> entity = application.entity(JreCustomer.class);
            assertSame(XdStoragePreGeneratedWrappers.lookup(Kind.SIMPLE, entity),
                    XdStorageProxyCompilationEngine.getClassSimpleWrapper(entity));
            assertSame(XdStoragePreGeneratedWrappers.lookup(Kind.UNMODIFIABLE, entity),
                    XdStorageProxyCompilationEngine.getClassUnmodifiableWrapper(entity));
            assertSame(XdStoragePreGeneratedWrappers.lookup(Kind.OBSERVABLE, entity),
                    XdStorageProxyCompilationEngine.getClassObservableWrapper(entity));
        }
    }

    // === what the generator does ===

    @Test
    @DisplayName("8. Повторная генерация: ни одного файла не переписано, все обёртки признаны текущими")
    void aSecondRunWritesNothing() throws Exception {
        generate();
        final byte[] manifestBefore = Files.readAllBytes(classes.resolve(XdStoragePreGeneratedWrappers.MANIFEST_PATH));
        final List<byte[]> wrappersBefore = wrapperFiles();

        final XdStorageAotWrapperGenerator.Result second = generate();

        assertTrue(second.generated.isEmpty(), second.generated.toString());
        assertEquals(Arrays.asList(JreAccount.class.getName(), JreCustomer.class.getName()), second.upToDate);
        assertTrue(second.wrappers.isEmpty());
        assertArrayEquals(manifestBefore,
                Files.readAllBytes(classes.resolve(XdStoragePreGeneratedWrappers.MANIFEST_PATH)));
        assertNotEquals(0, wrappersBefore.size());
        assertArrayEquals(toArray(wrappersBefore), toArray(wrapperFiles()));
    }

    @Test
    @DisplayName("9. Запись в манифесте разошлась с классом: обёртки класса переписаны, запись восстановлена")
    void aChangedRecordIsRegenerated() throws Exception {
        generate();
        final String entry = manifest().getProperty(JreCustomer.class.getName() + "|" + Kind.SIMPLE.key);
        final Properties tampered = manifest();
        tampered.setProperty(JreCustomer.class.getName() + "|" + Kind.SIMPLE.key,
                entry.substring(0, entry.indexOf('|') + 1) + NO_SUCH_FINGERPRINT);
        store(tampered);

        final XdStorageAotWrapperGenerator.Result repair = generate();

        assertEquals(Collections.singletonList(JreCustomer.class.getName()), repair.generated);
        assertEquals(Collections.singletonList(JreAccount.class.getName()), repair.upToDate);
        assertEquals(Kind.values().length, repair.wrappers.size());
        assertEquals(entry, manifest().getProperty(JreCustomer.class.getName() + "|" + Kind.SIMPLE.key));
    }

    @Test
    @DisplayName("10. Путь изоляции (плагин, командная строка): генерация против движка на сканируемом classpath")
    void theIsolatedPathGeneratesForTheApplicationAndTheEngine() throws Exception {
        final XdStorageAotWrapperGenerator.Result result = XdStorageAotWrapperGenerator.generate(
                Arrays.asList(classes, TARGET_CLASSES), runtimeDependencies(), classes, "8");

        assertTrue(result.generated.containsAll(Arrays.asList(JreAccount.class.getName(),
                JreCustomer.class.getName())), result.generated.toString());
        assertTrue(result.generated.size() > DEMO_ENTITIES.size(), "the engine's own classes are entities too: "
                + result.generated);
        assertEquals(3 * result.entities(), result.wrappers.size());
        try (ApplicationLoader application = new ApplicationLoader(classes)) {
            assertNotNull(XdStoragePreGeneratedWrappers.lookup(Kind.SIMPLE, application.entity(JreCustomer.class)));
        }
    }

    @Test
    @DisplayName("11. Скан упаковывает только классы с идентификатором — из всего движка")
    void theScanWrapsEntitiesAndNothingElse() throws Exception {
        final Path output = Files.createDirectories(work.resolve("engine-wrappers"));
        final ClassLoader loader = XdStoragePreGeneratedWrapperTest.class.getClassLoader();

        final XdStorageAotWrapperGenerator.Result result = XdStorageAotWrapperGenerator.generateHere(
                Collections.singletonList(TARGET_CLASSES), output, "8");

        assertTrue(result.entities() > 0, "the engine's own classes are entities too: " + result);
        for (final String name : result.generated) {
            assertTrue(XdStorageWrapperFingerprint.isEntity(Class.forName(name, false, loader)),
                    "wrapped without an id: " + name);
        }
        assertFalse(result.generated.contains(JreCustomer.class.getName()), "not on the scanned root");
    }

    // === the fingerprint ===

    @Test
    @DisplayName("12. Отпечаток не зависит от порядка объявления членов")
    void theFingerprintDoesNotDependOnDeclarationOrder() throws Exception {
        final Class<?> first = compiled(SAME_MEMBERS_ONE_ORDER).get(0);
        final Class<?> sameAgain = compiled(SAME_MEMBERS_ONE_ORDER).get(0);
        final Class<?> otherOrder = compiled(SAME_MEMBERS_OTHER_ORDER).get(0);

        assertEquals(XdStorageWrapperFingerprint.of(first), XdStorageWrapperFingerprint.of(sameAgain));
        assertEquals(XdStorageWrapperFingerprint.of(first), XdStorageWrapperFingerprint.of(otherOrder));
    }

    @Test
    @DisplayName("13. Отпечаток меняется вместе с формой класса: новый метод, другое поле-идентификатор")
    void theFingerprintFollowsTheShapeOfTheClass() throws Exception {
        final String shape = XdStorageWrapperFingerprint.of(compiled(SAME_MEMBERS_ONE_ORDER).get(0));

        assertNotEquals(shape, XdStorageWrapperFingerprint.of(compiled(SAME_MEMBERS_PLUS_METHOD).get(0)));
        assertNotEquals(shape, XdStorageWrapperFingerprint.of(compiled(SAME_MEMBERS_OTHER_ID).get(0)));
    }

    @Test
    @DisplayName("14. Отпечаток меняется вместе с аннотацией, которую читает генератор (ленивое поле)")
    void theFingerprintFollowsTheAnnotationsTheGeneratorsRead() throws Exception {
        final String eager = XdStorageWrapperFingerprint.of(compiled(SAME_MEMBERS_PLAIN_COLLECTION).get(0));

        assertNotEquals(eager, XdStorageWrapperFingerprint.of(compiled(SAME_MEMBERS_LAZY_COLLECTION).get(0)));
    }

    // === fixtures ===

    private XdStorageAotWrapperGenerator.Result generate() throws IOException {
        return XdStorageAotWrapperGenerator.generateLoaded(DEMO_ENTITIES, testClasspath(), classes, "8");
    }

    /** The demo entities as the APPLICATION's loader holds them. */
    private static List<Class<?>> demoEntitiesIn(final ApplicationLoader application) throws ClassNotFoundException {
        final List<Class<?>> entities = new ArrayList<>();
        for (final Class<?> entity : DEMO_ENTITIES) {
            entities.add(application.entity(entity));
        }
        return entities;
    }

    private static Class<?> kindMarker(final Kind kind) {
        switch (kind) {
            case SIMPLE:
                return IXdStorageSimpleWrapper.class;
            case UNMODIFIABLE:
                return IXdStorageUnmodifiableWrapper.class;
            default:
                return IXdStorageIdObservableWrapper.class;
        }
    }

    private Properties manifest() throws IOException {
        final Properties manifest = new Properties();
        try (InputStream in = Files.newInputStream(classes.resolve(XdStoragePreGeneratedWrappers.MANIFEST_PATH))) {
            manifest.load(in);
        }
        return manifest;
    }

    private void store(final Properties manifest) throws IOException {
        try (OutputStream out = Files.newOutputStream(
                classes.resolve(XdStoragePreGeneratedWrappers.MANIFEST_PATH))) {
            manifest.store(out, null);
        }
    }

    /** Every file of the application's classes directory except the manifest and the demo classes. */
    private List<byte[]> wrapperFiles() throws IOException {
        final List<byte[]> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            for (final Path file : (Iterable<Path>) walk::iterator) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".class") && !file.toString().contains(DEMO_CLASSES)) {
                    files.add(Files.readAllBytes(file));
                }
            }
        }
        return files;
    }

    private static byte[][] toArray(final List<byte[]> files) {
        return files.toArray(new byte[0][]);
    }

    /** The application's own loader: its classes — and the wrappers generated for them — come
     * from the classes directory, never from this test's loader. */
    private static final class ApplicationLoader extends URLClassLoader {
        ApplicationLoader(final Path classes) throws IOException {
            super(new URL[]{classes.toUri().toURL()}, XdStoragePreGeneratedWrapperTest.class.getClassLoader());
        }

        Class<?> entity(final Class<?> nameDonor) throws ClassNotFoundException {
            final Class<?> entity = loadClass(nameDonor.getName());
            assertNotSame(nameDonor, entity, "the application must hold its own copy of the class");
            return entity;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(DEMO_PACKAGE + ".")
                    && !name.startsWith(XdStoragePreGeneratedWrappers.WRAPPERS_PACKAGE + ".")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (final ClassNotFoundException notHere) {
                        return super.loadClass(name, resolve);    // the engine's own classes of that package
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    // === classes compiled on the spot, for the fingerprint ===

    private static final String HEAD = "package fp;\nimport io.github.xdstorage.annotations.XdStorageObjectId;\n";
    private static final String ID_ACCESS = "    public String getId() { return id; }\n"
            + "    public void setId(String id) { this.id = id; }\n";
    private static final String NAME_FIELD = "    private String name;\n";
    private static final String NAME_ACCESS = "    public String getName() { return name; }\n"
            + "    public void setName(String name) { this.name = name; }\n";

    private static final String SAME_MEMBERS_ONE_ORDER = HEAD + "public class Same {\n"
            + "    @XdStorageObjectId private String id;\n" + NAME_FIELD + ID_ACCESS + NAME_ACCESS + "}\n";
    private static final String SAME_MEMBERS_OTHER_ORDER = HEAD + "public class Same {\n" + NAME_FIELD
            + "    @XdStorageObjectId private String id;\n" + NAME_ACCESS + ID_ACCESS + "}\n";
    private static final String SAME_MEMBERS_PLUS_METHOD = HEAD + "public class Same {\n"
            + "    @XdStorageObjectId private String id;\n" + NAME_FIELD + ID_ACCESS + NAME_ACCESS
            + "    public String getNote() { return null; }\n" + "}\n";
    private static final String SAME_MEMBERS_OTHER_ID = HEAD + "public class Same {\n"
            + "    @XdStorageObjectId private String key;\n" + NAME_FIELD
            + "    public String getKey() { return key; }\n"
            + "    public void setKey(String key) { this.key = key; }\n" + NAME_ACCESS + "}\n";
    private static final String COLLECTION_ACCESS = "    private java.util.List<String> tags;\n"
            + "    public java.util.List<String> getTags() { return tags; }\n"
            + "    public void setTags(java.util.List<String> tags) { this.tags = tags; }\n";
    private static final String SAME_MEMBERS_PLAIN_COLLECTION = HEAD + "public class Same {\n"
            + "    @XdStorageObjectId private String id;\n" + NAME_FIELD + COLLECTION_ACCESS + ID_ACCESS + NAME_ACCESS
            + "}\n";
    private static final String SAME_MEMBERS_LAZY_COLLECTION = HEAD
            + "import io.github.xdstorage.annotations.XdStorageLoadByGetMethod;\n" + "public class Same {\n"
            + "    @XdStorageObjectId private String id;\n" + NAME_FIELD
            + "    @XdStorageLoadByGetMethod private java.util.List<String> tags;\n"
            + "    public java.util.List<String> getTags() { return tags; }\n"
            + "    public void setTags(java.util.List<String> tags) { this.tags = tags; }\n" + ID_ACCESS + NAME_ACCESS
            + "}\n";

    /** Compiles the sources — one class of package {@code fp} per source — into a directory of their own. */
    private Path compile(final String... sources) throws IOException {
        final Path root = Files.createTempDirectory("xdstorage-fp-");
        compiledRoots.add(root);
        final Path sourceDirectory = Files.createDirectories(root.resolve("src/fp"));
        final List<File> files = new ArrayList<>();
        for (final String source : sources) {
            final String name = source.replaceFirst("(?s).*public class (\\w+) .*", "$1");
            final Path file = sourceDirectory.resolve(name + ".java");
            Files.write(file, source.getBytes(StandardCharsets.UTF_8));
            files.add(file.toFile());
        }
        final Path classes = Files.createDirectories(root.resolve("classes"));
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the build runs on a JDK");
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            final String classpath = String.join(File.pathSeparator, testClasspathNames());
            final List<String> options = Arrays.asList("-d", classes.toString(), "-classpath", classpath,
                    "-proc:none", "-nowarn", "--release", "8");
            final Boolean ok = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromFiles(files)).call();
            assertTrue(Boolean.TRUE.equals(ok), () -> "the fixture sources did not compile: "
                    + diagnostics.getDiagnostics());
        }
        return classes;
    }

    /** The classes compiled from the sources, each in a loader of its own — so that two variants of one
     * name never meet. */
    private List<Class<?>> compiled(final String... sources) throws Exception {
        final Path classes = compile(sources);
        final URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                XdStoragePreGeneratedWrapperTest.class.getClassLoader());
        final List<Class<?>> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (final Path file : (Iterable<Path>) files::iterator) {
                if (file.toString().endsWith(".class")) {
                    final String name = classes.relativize(file).toString();
                    loaded.add(Class.forName(name.substring(0, name.length() - ".class".length())
                            .replace(File.separatorChar, '.'), false, loader));
                }
            }
        }
        return loaded;
    }

    private static List<Path> testClasspath() {
        final List<Path> entries = new ArrayList<>();
        for (final String entry : testClasspathNames()) {
            entries.add(Paths.get(entry));
        }
        return entries;
    }

    private static List<String> testClasspathNames() {
        return Arrays.asList(System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")).split(File.pathSeparator));
    }

    /** The engine's runtime dependencies, picked from the test classpath by their file names. */
    private static List<Path> runtimeDependencies() {
        final List<Path> jars = new ArrayList<>();
        for (final Path entry : testClasspath()) {
            final String name = entry.getFileName().toString();
            if (name.startsWith("commons-lang3-") || name.startsWith("slf4j-api-")) {
                jars.add(entry);
            }
        }
        return jars;
    }

    private static Path codeSource() {
        try {
            return Paths.get(XdStoragePreGeneratedWrapperTest.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (final java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void delete(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(files::add);
        }
        files.sort(java.util.Comparator.reverseOrder());
        for (final Path file : files) {
            file.toFile().setWritable(true);
            file.toFile().delete();
        }
    }
}
