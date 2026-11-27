package io.github.xdstorage.codegen.maven;

import io.github.xdstorage.XdStorageProvider;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin goal, run as Maven would run it: against the engine of this build and a small
 * application's classes, through a project object in the shape Maven hands a mojo. What is
 * pinned: the wrappers land in the application's classes next to the manifest (the engine's
 * own classes included), a second run writes nothing, the release follows the project, and
 * a build without the engine on the classpath fails by name.
 */
class GenerateWrappersMojoTest {

    private static final String MANIFEST = "META-INF/xdstorage/aot-wrappers.properties";
    private static final String WRAPPERS = "io/github/xdstorage/code/";
    private static final String CUSTOMER = "shop.Customer";
    private static final byte JAVA_8 = 52;
    private static final byte JAVA_17 = 61;

    private static final String CUSTOMER_SOURCE = "package shop;\n"
            + "import io.github.xdstorage.annotations.XdStorageObjectId;\n"
            + "public class Customer {\n"
            + "    @XdStorageObjectId private String id;\n"
            + "    private String name;\n"
            + "    public String getId() { return id; }\n"
            + "    public void setId(String id) { this.id = id; }\n"
            + "    public String getName() { return name; }\n"
            + "    public void setName(String name) { this.name = name; }\n"
            + "}\n";

    /** The test's temporary root. */
    private Path work;
    /** The application's classes directory. */
    private Path classes;

    @BeforeEach
    void application() throws Exception {
        work = Files.createTempDirectory("xdstorage-codegen-");
        classes = Files.createDirectories(work.resolve("classes"));
        compileCustomer();
    }

    @AfterEach
    void tearDown() throws IOException {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(work)) {
            walk.forEach(files::add);
        }
        Collections.reverse(files);
        for (final Path file : files) {
            file.toFile().delete();
        }
    }

    @Test
    @DisplayName("1. Обёртки приложения И движка лежат в классах приложения вместе с манифестом")
    void writesTheWrappersOfTheApplicationAndTheEngineIntoTheClasses() throws Exception {
        mojo(null, false, dependencies()).execute();

        final Properties manifest = manifest();
        for (final String kind : kinds()) {
            final String entry = manifest.getProperty(CUSTOMER + "|" + kind);
            assertNotNull(entry, kind + " wrapper of the application's entity");
            assertTrue(Files.exists(classes.resolve(WRAPPERS + entry.substring(0, entry.indexOf('|')) + ".class")),
                    "the class file of the " + kind + " wrapper");
        }
        assertNotNull(manifest.getProperty("io.github.xdstorage.object.XdStorageIdentifiableObject|simple"),
                "the engine's own classes are entities too");
        assertNotNull(manifest.getProperty("io.github.xdstorage.btree.XdStorageBTree|simple"));
    }

    @Test
    @DisplayName("2. Повторный запуск плагина: ни манифест, ни обёртки не переписаны")
    void aSecondRunWritesNothing() throws Exception {
        mojo(null, false, dependencies()).execute();
        final byte[] manifestBefore = Files.readAllBytes(classes.resolve(MANIFEST));
        final byte[][] wrappersBefore = wrapperFiles();

        mojo(null, false, dependencies()).execute();

        assertArrayEquals(manifestBefore, Files.readAllBytes(classes.resolve(MANIFEST)));
        assertArrayEquals(wrappersBefore, wrapperFiles());
    }

    @Test
    @DisplayName("3. Без указаний обёртки собраны под Java 8 — как байткод движка")
    void theWrappersAreBuiltForJava8ByDefault() throws Exception {
        mojo(null, false, dependencies()).execute();

        assertEquals(JAVA_8, classFileMajorVersion(wrapperFile("simple")));
    }

    @Test
    @DisplayName("4. maven.compiler.release проекта задаёт release обёрток")
    void theReleaseFollowsTheCompilerPropertyOfTheProject() throws Exception {
        final MavenProject project = project(dependencies());
        project.getProperties().setProperty("maven.compiler.release", "17");

        new GenerateWrappersMojo(classes.toFile(), null, null, false, project).execute();

        assertEquals(JAVA_17, classFileMajorVersion(wrapperFile("simple")));
    }

    @Test
    @DisplayName("5. Параметр release важнее свойства проекта; «1.8» понимается как 8")
    void theParameterWinsAndAcceptsTheOldSpelling() throws Exception {
        final MavenProject project = project(dependencies());
        project.getProperties().setProperty("maven.compiler.release", "17");

        new GenerateWrappersMojo(classes.toFile(), null, "1.8", false, project).execute();

        assertEquals(JAVA_8, classFileMajorVersion(wrapperFile("simple")));
    }

    @Test
    @DisplayName("6. skip=true: ничего не сгенерировано")
    void skipsWhenAsked() throws Exception {
        mojo(null, true, dependencies()).execute();

        assertFalse(Files.exists(classes.resolve(MANIFEST)));
    }

    @Test
    @DisplayName("7. Движка нет в classpath проекта: сборка падает с именем причины")
    void failsTheBuildWhenTheEngineIsNotOnTheClasspath() {
        final MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> mojo(null, false, Collections.<Path>emptyList()).execute());

        assertTrue(failure.getMessage().contains("no XdStorage engine is on the compile classpath"),
                failure.getMessage());
    }

    // === fixtures ===

    private GenerateWrappersMojo mojo(final String release, final boolean skip,
                                      final List<Path> dependencies) throws Exception {
        return new GenerateWrappersMojo(classes.toFile(), null, release, skip, project(dependencies));
    }

    /**
     * A project in the shape Maven hands a mojo after dependency resolution: an output
     * directory, and compile-scoped artifacts with files — from which the project reports its
     * compile classpath.
     */
    private MavenProject project(final List<Path> dependencies) {
        final Build build = new Build();
        build.setOutputDirectory(classes.toString());
        final Model model = new Model();
        model.setBuild(build);
        final MavenProject project = new MavenProject(model);
        final Set<Artifact> artifacts = new LinkedHashSet<>();
        int i = 0;
        for (final Path dependency : dependencies) {
            final DefaultArtifactHandler handler = new DefaultArtifactHandler();
            handler.setAddedToClasspath(true);
            final Artifact artifact = new DefaultArtifact("test", "dependency-" + i++, "1", Artifact.SCOPE_COMPILE,
                    "jar", null, handler);
            artifact.setFile(dependency.toFile());
            artifacts.add(artifact);
        }
        project.setArtifacts(artifacts);
        return project;
    }

    /** The engine of this build and its runtime dependencies — the application's only production dependencies. */
    private static List<Path> dependencies() {
        final List<Path> entries = new ArrayList<>();
        entries.add(engineLocation());
        for (final String entry : System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")).split(File.pathSeparator)) {
            final String name = new File(entry).getName();
            if (name.startsWith("commons-lang3-") || name.startsWith("slf4j-api-")) {
                entries.add(Paths.get(entry));
            }
        }
        return entries;
    }

    private static Path engineLocation() {
        try {
            return Paths.get(XdStorageProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compiles the application's entity into the classes directory, against the engine. */
    private void compileCustomer() throws IOException {
        final Path source = Files.createDirectories(work.resolve("src/shop")).resolve("Customer.java");
        Files.write(source, CUSTOMER_SOURCE.getBytes(StandardCharsets.UTF_8));
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the build runs on a JDK");
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            final Boolean ok = compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-d", classes.toString(), "-classpath", engineLocation().toString(), "-proc:none",
                            "-nowarn", "--release", "8"), null,
                    manager.getJavaFileObjects(source.toFile())).call();
            assertTrue(Boolean.TRUE.equals(ok), () -> "the entity did not compile: " + diagnostics.getDiagnostics());
        }
    }

    private static List<String> kinds() {
        return Arrays.asList("simple", "unmodifiable", "observable");
    }

    private Properties manifest() throws IOException {
        final Properties manifest = new Properties();
        try (InputStream in = Files.newInputStream(classes.resolve(MANIFEST))) {
            manifest.load(in);
        }
        return manifest;
    }

    private Path wrapperFile(final String kind) throws IOException {
        final String entry = manifest().getProperty(CUSTOMER + "|" + kind);
        assertNotNull(entry, kind + " wrapper of the application's entity");
        return classes.resolve(WRAPPERS + entry.substring(0, entry.indexOf('|')) + ".class");
    }

    /** Every wrapper class file of the application's classes. */
    private byte[][] wrapperFiles() throws IOException {
        final List<Path> classFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes.resolve(WRAPPERS))) {
            classFiles.addAll(walk.filter(file -> file.toString().endsWith(".class")).collect(Collectors.toList()));
        }
        classFiles.sort(Path::compareTo);
        final byte[][] files = new byte[classFiles.size()][];
        for (int i = 0; i < files.length; i++) {
            files[i] = Files.readAllBytes(classFiles.get(i));
        }
        return files;
    }

    private static byte classFileMajorVersion(final Path classFile) throws IOException {
        final byte[] header = new byte[8];
        try (InputStream in = Files.newInputStream(classFile)) {
            assertEquals(8, in.read(header), "the class file header of " + classFile);
        }
        return header[7];
    }
}
