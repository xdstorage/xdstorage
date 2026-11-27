package io.github.xdstorage.tools;

import io.github.xdstorage.code.XdStorageAotWrapperGenerator;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * FP-3 (docs/tasks/2.2.0/08): the build-time wrapper generation as a command — for builds
 * that do not use the {@code xdstorage-codegen} Maven plugin (Gradle's {@code JavaExec}, Ant,
 * a script). It writes the wrapper classes the engine would otherwise compile at runtime, so
 * the application runs on a JRE and from a read-only working directory
 * (docs/EMBEDDING.md, Running on a JRE). The build itself needs a JDK.
 * <p>
 * Usage:
 * <pre>
 * java -cp &lt;xdstorage.jar with its dependencies&gt; io.github.xdstorage.tools.GenerateWrappers
 *      [--output &lt;dir&gt;] [--release &lt;n&gt;] [--classpath &lt;path&gt;] &lt;root&gt;...
 * </pre>
 * The roots are the directories and jars to scan for entity classes: the application's own
 * classes and everything on its runtime classpath, since the engine's own classes need
 * wrappers too. The wrappers and their manifest go to {@code --output} (default: the first
 * root) — normally the application's classes directory, so they ship with it. A wrapper whose
 * class and engine version are unchanged is not written again.
 *
 * <p>Unless {@code --classpath} says otherwise, everything on this JVM's own {@code -cp} is
 * linked against as well — so the engine's dependencies are found wherever the engine is.</p>
 */
public final class GenerateWrappers {

    private GenerateWrappers() {
        // utility class
    }

    /**
     * CLI entry point. Exits with status 1 on a usage error and 2 when the generation fails.
     *
     * @param args {@code [--output <dir>] [--release <n>] [--classpath <path>] <root>...}
     */
    public static void main(final String[] args) {
        final List<Path> roots = new ArrayList<>();
        final List<Path> classpath = new ArrayList<>();
        Path output = null;
        String release = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output":
                        output = Paths.get(args[++i]);
                        break;
                    case "--release":
                        release = args[++i];
                        break;
                    case "--classpath":
                        for (final String entry : args[++i].split(File.pathSeparator)) {
                            if (!entry.isEmpty()) {
                                classpath.add(Paths.get(entry));
                            }
                        }
                        break;
                    default:
                        if (args[i].startsWith("--")) {
                            usage("unknown option " + args[i]);
                            return;
                        }
                        roots.add(Paths.get(args[i]));
                }
            }
        } catch (final ArrayIndexOutOfBoundsException optionWithoutValue) {
            usage("an option is missing its value");
            return;
        }
        if (roots.isEmpty()) {
            usage("no root to scan is given");
            return;
        }
        if (classpath.isEmpty()) {
            // the JVM's own classpath already carries the engine's dependencies — the engine
            // copy the generator isolates needs them to start
            for (final String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    classpath.add(Paths.get(entry));
                }
            }
        }
        final Path target = output != null ? output : roots.get(0);
        try {
            final XdStorageAotWrapperGenerator.Result result =
                    XdStorageAotWrapperGenerator.generate(roots, classpath, target, release);
            System.out.println("xdstorage-codegen: " + result.scannedClasses + " class(es) scanned, "
                    + result.generated.size() + " entity class(es) written, " + result.upToDate.size()
                    + " already current, " + result.wrappers.size() + " wrapper class(es) into " + target);
        } catch (final Exception failure) {
            System.err.println("xdstorage-codegen: wrapper generation failed — " + failure.getMessage());
            System.exit(2);
        }
    }

    private static void usage(final String problem) {
        System.err.println("xdstorage-codegen: " + problem);
        System.err.println("Usage: java -cp <xdstorage.jar and its dependencies> "
                + "io.github.xdstorage.tools.GenerateWrappers [--output <dir>] [--release <n>] "
                + "[--classpath <path>] <root>...");
        System.err.println("  root         — a directory or jar to scan for entity classes (the application's "
                + "classes, its dependencies, the engine)");
        System.err.println("  --output     — where the wrappers and their manifest go (default: the first root)");
        System.err.println("  --release    — the Java release to compile for (8, 11, 17, ...; default: the JDK's)");
        System.err.println("  --classpath  — what the wrappers must link against and what need not be scanned");
        System.exit(1);
    }
}
