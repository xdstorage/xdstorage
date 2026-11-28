package io.github.xdstorage.codegen;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sandbox of the JRE tests (FP-3, docs/tasks/2.2.0/08): a tiny APPLICATION — the classes
 * of package {@code io.github.xdstorage.codegen.jredemo} — started in a child JVM on the JDK
 * and on a runtime without the compiler, with and without build-time wrappers.
 *
 * <p>The runtime without the compiler is a jlink image of the engine's modules, linked once
 * per build directory. A JDK that cannot link one (no {@code jmods}) falls back to the JDK's
 * own {@code java} limited to those modules — the compiler is invisible all the same.</p>
 *
 * <p>Nothing here names a class this task ADDED to the engine: the generator is started by
 * name, as a build tool starts it. So the fixture compiles against the engine as it was
 * before the task — the red-before run needs exactly that. Test fixture — not part of any
 * API.</p>
 */
final class XdStorageJreSandbox {

    /** The application's main class. */
    static final String DEMO_MAIN = "io.github.xdstorage.codegen.jredemo.JreDemo";
    /** Where the engine reads the manifest of build-time wrappers. */
    static final String MANIFEST = "META-INF/xdstorage/aot-wrappers.properties";
    /** A fingerprint no wrapper was ever generated for. */
    static final String NO_SUCH_FINGERPRINT = "0000000000000000";
    /** The modules the engine needs at runtime — and no compiler among them. */
    static final String RUNTIME_MODULES = "java.base,java.logging,java.management,java.naming,"
            + "java.transaction.xa,java.xml,java.sql,jdk.unsupported";

    /** The generator's command-line entry. */
    private static final String GENERATOR_MAIN = "io.github.xdstorage.tools.GenerateWrappers";
    private static final String DEMO_CLASSES = "io/github/xdstorage/codegen/jredemo";
    private static final String IMAGE_DIRECTORY = "jre-image";
    private static final long CHILD_TIMEOUT_SECONDS = 120L;

    private static final Path TEST_CLASSES = codeSource();
    private static final Path TARGET_CLASSES = TEST_CLASSES.getParent().resolve("classes");
    private static final List<Path> ENGINE_ENTRIES = engineEntries();

    /** The image's java — {@code null} until it is linked, and for good when linking is ruled out. */
    private static Path runtimeJava;
    private static String runtimeJavaUnavailable;

    /** One run of the application in a child JVM. */
    static final class Run {
        /** The process's exit status, {@code -1} when it had to be killed. */
        final int exitCode;
        final boolean timedOut;
        final String output;
        private final Path workingDirectory;

        private Run(final int exitCode, final boolean timedOut, final String output,
                    final Path workingDirectory) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output;
            this.workingDirectory = workingDirectory;
        }

        /** Whether the runtime compilation left its {@code gencode} directory in the working directory. */
        boolean compiledAtRuntime() {
            return Files.exists(workingDirectory.resolve("gencode"));
        }
    }

    private final Path root;
    private final Path consumer;

    private XdStorageJreSandbox(final Path root) throws IOException {
        this.root = root;
        this.consumer = Files.createDirectories(root.resolve("consumer"));
        try (Stream<Path> classes = Files.walk(TEST_CLASSES.resolve(DEMO_CLASSES))) {
            for (final Path file : (Iterable<Path>) classes::iterator) {
                if (file.toString().endsWith(".class")) {
                    final Path target = consumer.resolve(TEST_CLASSES.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Deletes the sandbox — its temporary directory with everything the runs left there. */
    void delete() throws IOException {
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

    /** A fresh sandbox in its own temporary directory: the application's classes, nothing else. */
    static XdStorageJreSandbox fresh() throws IOException {
        return new XdStorageJreSandbox(Files.createTempDirectory("xdstorage-jre-"));
    }

    /** The JDK's java — the one running the tests. */
    static Path jdkJava() {
        return Paths.get(System.getProperty("java.home"), "bin", "java");
    }

    /**
     * The command that starts a runtime WITHOUT the compiler.
     *
     * @return the image's java, or the JDK's java limited to {@link #RUNTIME_MODULES}
     * @throws Exception when the image cannot be linked
     */
    static synchronized List<String> runtimeCommand() throws Exception {
        if (runtimeJava == null && runtimeJavaUnavailable == null) {
            linkRuntimeImage();
        }
        return runtimeJava != null ? Arrays.asList(runtimeJava.toString())
                : Arrays.asList(jdkJava().toString(), "--limit-modules", RUNTIME_MODULES);
    }

    /** How the runtime without the compiler is made — for the reports. */
    static synchronized String runtimeDescription() throws Exception {
        runtimeCommand();
        return runtimeJava != null ? "the jlink image " + runtimeJava : "the JDK's java with --limit-modules ("
                + runtimeJavaUnavailable + ")";
    }

    /**
     * Generates the build-time wrappers of the application's classes AND of the engine's own
     * classes into the application's classes directory — as a build tool does.
     */
    void generateBuildTimeWrappers() throws Exception {
        final List<String> command = new ArrayList<>(Arrays.asList(jdkJava().toString(), "-cp",
                join(TARGET_CLASSES, runtimeDependencies()), GENERATOR_MAIN, "--release", "8", "--output",
                consumer.toString(), consumer.toString(), TARGET_CLASSES.toString()));
        final int exit = start(command, root, root.resolve("generate.log"));
        if (exit != 0) {
            fail("the build-time wrapper generation failed (exit " + exit + "):\n"
                    + read(root.resolve("generate.log")));
        }
        assertTrue(Files.exists(consumer.resolve(MANIFEST)), "no manifest was written into " + consumer);
    }

    /**
     * Makes the manifest disagree with the class it describes — what a wrapper generated
     * before the class (or the engine) changed looks like at runtime.
     *
     * @param entity the class whose recorded wrappers turn stale
     */
    void makeWrappersStale(final Class<?> entity) throws IOException {
        final Path file = consumer.resolve(MANIFEST);
        final Properties manifest = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            manifest.load(in);
        }
        for (final String kind : Arrays.asList("simple", "unmodifiable", "observable")) {
            final String entry = manifest.getProperty(entity.getName() + "|" + kind);
            assertNotNull(entry, "no " + kind + " wrapper of " + entity.getName() + " in the manifest");
            manifest.setProperty(entity.getName() + "|" + kind,
                    entry.substring(0, entry.indexOf('|') + 1) + NO_SUCH_FINGERPRINT);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            manifest.store(out, null);
        }
    }

    /**
     * Runs the application once.
     *
     * @param runtimeCommand           the java to start, with its VM options
     * @param label                    names the run's working directory and log
     * @param readOnlyWorkingDirectory whether the working directory rejects {@code ./gencode}
     * @return what the run did
     */
    Run run(final List<String> runtimeCommand, final String label, final boolean readOnlyWorkingDirectory)
            throws Exception {
        final Path working = Files.createDirectories(root.resolve(label));
        final Path data = Files.createDirectories(root.resolve(label + "-data"));
        if (readOnlyWorkingDirectory) {
            // Windows cannot set this scenario up at all: its JDK never marks a directory read-only
            // (File.setWritable(false) returns false for every directory — WinNTFileSystem's
            // setPermission0 leaves FILE_ATTRIBUTE_DIRECTORY alone), and the attribute would not
            // keep a child from being created there anyway. POSIX root writes into read-only
            // directories as well. Either way the scenario is not set up — skipped, and reported so.
            final boolean sealed = working.toFile().setWritable(false, false) && !Files.isWritable(working);
            assumeTrue(sealed, "this platform or account cannot make the working directory read-only"
                    + " (Windows never marks a directory read-only) — the read-only working directory"
                    + " cannot be set up");
        }
        try {
            final List<String> command = new ArrayList<>(runtimeCommand);
            command.addAll(Arrays.asList("-cp", join(consumer, ENGINE_ENTRIES), DEMO_MAIN, data.toString()));
            final Path log = root.resolve(label + ".log");
            final long started = System.nanoTime();
            final int exit = start(command, working, log);
            final Run run = new Run(exit, exit < 0, "[ran " + (System.nanoTime() - started) / 1_000_000L
                    + " ms]\n" + read(log), working);
            assertTrue(!run.timedOut, "the application did not end within " + CHILD_TIMEOUT_SECONDS
                    + " s — a failure must not leave a JVM alive:\n" + run.output);
            return run;
        } finally {
            if (readOnlyWorkingDirectory) {
                working.toFile().setWritable(true, false);
            }
        }
    }

    // === the child processes ===

    /** Starts a command in {@code directory}, its output into {@code log}; {@code -1} when it had to be killed. */
    private static int start(final List<String> command, final Path directory, final Path log) throws Exception {
        final Process child = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(30L, TimeUnit.SECONDS);
            return -1;
        }
        return child.exitValue();
    }

    private static void linkRuntimeImage() throws Exception {
        final Path home = Paths.get(System.getProperty("java.home"));
        final Path jlink = home.resolve("bin").resolve("jlink");
        if (!Files.isDirectory(home.resolve("jmods")) || !Files.isExecutable(jlink)) {
            runtimeJavaUnavailable = "no jlink or jmods in " + home;
            return;
        }
        final Path image = TARGET_CLASSES.getParent().resolve(IMAGE_DIRECTORY);
        final Path java = image.resolve("bin").resolve("java");
        if (!Files.isExecutable(java)) {
            final Path log = TARGET_CLASSES.getParent().resolve(IMAGE_DIRECTORY + ".log");
            final int exit = start(Arrays.asList(jlink.toString(), "--add-modules", RUNTIME_MODULES, "--output",
                    image.toString(), "--strip-debug", "--no-header-files", "--no-man-pages"),
                    TARGET_CLASSES.getParent(), log);
            if (exit != 0 || !Files.isExecutable(java)) {
                runtimeJavaUnavailable = "jlink exited with " + exit + ": " + (Files.exists(log) ? read(log) : "");
                return;
            }
        }
        runtimeJava = java;
    }

    // === what the child JVMs need on their classpath ===

    private static Path codeSource() {
        try {
            return Paths.get(XdStorageJreSandbox.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The engine's classes and its runtime dependencies: what a child JVM needs besides the application. */
    private static List<Path> engineEntries() {
        final List<Path> entries = new ArrayList<>();
        entries.add(TARGET_CLASSES);
        entries.addAll(runtimeDependencies());
        return entries;
    }

    /** The engine's runtime dependencies, picked from the test classpath by their file names. */
    private static List<Path> runtimeDependencies() {
        final List<Path> jars = new ArrayList<>();
        for (final String entry : System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path")).split(File.pathSeparator)) {
            final String name = new File(entry).getName();
            if (name.startsWith("commons-lang3-") || name.startsWith("slf4j-api-")) {
                jars.add(Paths.get(entry));
            }
        }
        return jars;
    }

    private static String join(final Path first, final List<Path> rest) {
        final StringBuilder out = new StringBuilder(first.toString());
        for (final Path entry : rest) {
            out.append(File.pathSeparator).append(entry);
        }
        return out.toString();
    }

    private static String read(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
