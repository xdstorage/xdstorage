package io.github.xdstorage.codegen;

import io.github.xdstorage.codegen.jredemo.JreCustomer;
import io.github.xdstorage.codegen.jredemo.JreDemo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FP-3 (docs/tasks/2.2.0/08) — an application on a runtime WITHOUT the Java compiler (a JRE,
 * a jlink image without {@code java.compiler}). Until 2.2.0 the engine needed the compiler at
 * the first use of every entity class: there it died in an unnamed {@code NoClassDefFoundError}
 * — or, with a read-only working directory, went on with the dummy wrappers behind one log
 * line. Now the wrappers can be generated while the application is built, and when neither
 * path delivers one the engine refuses at STARTUP — when the storage is opened — by name,
 * with the way out, and the process ends.
 *
 * <p>Every test starts the application ({@link JreDemo}) in a child JVM; the runtime without
 * the compiler is a jlink image linked once per build directory (XdStorageJreSandbox).</p>
 */
@DisplayName("JRE (2.2.0/08): приложение на среде без компилятора")
class XdStorageJreRuntimeTest {

    private static final String NO_COMPILER = "no pre-generated wrapper is on the classpath and this runtime has "
            + "no Java compiler";
    private static final String FIX = "Fix: generate the wrappers at build time";

    /** The test's own sandbox — the application's classes, the runs' directories. */
    private XdStorageJreSandbox sandbox;

    @AfterEach
    void tearDown() throws IOException {
        if (sandbox != null) {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("1. JDK без обёрток сборки: обёртки компилируются при первом обращении (контроль)")
    void jdkWithoutBuildTimeWrappersCompilesThemAtRuntime() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();

        final XdStorageJreSandbox.Run run = sandbox.run(jdk(), "jdk-plain", false);

        assertEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains(JreDemo.OK), run.output);
        assertTrue(run.compiledAtRuntime(), "the runtime path compiles into ./gencode:\n" + run.output);
    }

    @Test
    @DisplayName("2. JRE без обёрток сборки: отказ ПРИ ОТКРЫТИИ хранилища — с причиной и способом исправления")
    void jreWithoutBuildTimeWrappersFailsLoudlyAtStartup() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();

        final XdStorageJreSandbox.Run run = sandbox.run(jre(), "jre-plain", false);

        assertNotEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains(NO_COMPILER), run.output);
        assertTrue(run.output.contains(FIX), run.output);
        assertTrue(run.output.contains("XdStorageProvider.newOrGetFileStorage"), "the refusal must come at the "
                + "open, before any object is touched:\n" + run.output);
        assertFalse(run.output.contains(JreDemo.OK), run.output);
        assertFalse(run.compiledAtRuntime(), run.output);
    }

    @Test
    @DisplayName("3. JRE с обёртками сборки: приложение работает и ничего не компилирует")
    void jreWithBuildTimeWrappersRunsAndCompilesNothing() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();
        sandbox.generateBuildTimeWrappers();

        final XdStorageJreSandbox.Run run = sandbox.run(jre(), "jre-wrapped", false);

        assertEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains(JreDemo.OK), run.output);
        assertFalse(run.compiledAtRuntime(), run.output);
    }

    @Test
    @DisplayName("4. JDK с обёртками сборки: обёртки сборки используются и там, ничего не компилируется")
    void jdkWithBuildTimeWrappersCompilesNothing() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();
        sandbox.generateBuildTimeWrappers();

        final XdStorageJreSandbox.Run run = sandbox.run(jdk(), "jdk-wrapped", false);

        assertEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains(JreDemo.OK), run.output);
        assertFalse(run.compiledAtRuntime(), run.output);
    }

    @Test
    @DisplayName("5. JRE, обёртки сборки, рабочий каталог только для чтения: приложение работает")
    void buildTimeWrappersNeedNoWritableWorkingDirectory() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();
        sandbox.generateBuildTimeWrappers();

        final XdStorageJreSandbox.Run run = sandbox.run(jre(), "jre-readonly", true);

        assertEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains(JreDemo.OK), run.output);
    }

    @Test
    @DisplayName("6. JDK без обёрток сборки, каталог только для чтения: отказ при открытии называет каталог и способ")
    void readOnlyWorkingDirectoryWithoutBuildTimeWrappersFailsLoudly() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();

        final XdStorageJreSandbox.Run run = sandbox.run(jdk(), "jdk-readonly", true);

        assertNotEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains("the working directory is not writable"), run.output);
        assertTrue(run.output.contains(FIX), run.output);
        assertTrue(run.output.contains("XdStorageProvider.newOrGetFileStorage"), run.output);
        assertFalse(run.output.contains(JreDemo.OK), run.output);
    }

    @Test
    @DisplayName("7. JRE, устаревшая обёртка сборки (отпечаток не совпал): отказ с именем STALE, без ./gencode")
    void staleBuildTimeWrapperOnJreIsRefusedByName() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();
        sandbox.generateBuildTimeWrappers();
        sandbox.makeWrappersStale(JreCustomer.class);

        final XdStorageJreSandbox.Run run = sandbox.run(jre(), "jre-stale", false);

        assertNotEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains("it is STALE"), run.output);
        assertTrue(run.output.contains("generated for fingerprint " + XdStorageJreSandbox.NO_SUCH_FINGERPRINT),
                run.output);
        assertTrue(run.output.contains(JreCustomer.class.getName()), "the class it failed for:\n" + run.output);
        assertFalse(run.output.contains("XdStorageProvider.newOrGetFileStorage"), "the engine's own wrappers are "
                + "current — the open succeeds, the application's class fails at its first use:\n" + run.output);
        assertFalse(run.output.contains(NO_COMPILER), "a stale wrapper is not a missing one:\n" + run.output);
        assertFalse(run.compiledAtRuntime(), run.output);
    }

    @Test
    @DisplayName("8. JDK, обёртка сборки устарела: обёртка перекомпилируется при первом обращении")
    void staleBuildTimeWrapperOnJdkIsCompiledOver() throws Exception {
        sandbox = XdStorageJreSandbox.fresh();
        sandbox.generateBuildTimeWrappers();
        sandbox.makeWrappersStale(JreCustomer.class);

        final XdStorageJreSandbox.Run run = sandbox.run(jdk(), "jdk-stale", false);

        assertEquals(0, run.exitCode, run.output);
        assertTrue(run.output.contains(JreDemo.OK), run.output);
        assertTrue(run.compiledAtRuntime(), run.output);
    }

    private static List<String> jdk() {
        return Collections.singletonList(XdStorageJreSandbox.jdkJava().toString());
    }

    private static List<String> jre() throws Exception {
        return XdStorageJreSandbox.runtimeCommand();
    }
}
