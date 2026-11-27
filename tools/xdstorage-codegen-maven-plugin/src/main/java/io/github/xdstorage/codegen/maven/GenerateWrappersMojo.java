package io.github.xdstorage.codegen.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the XdStorage wrapper classes while the application is built (FP-3,
 * docs/tasks/2.2.0/08). The engine gives every entity class its wrapper — the transactional
 * access, the lazy loading — and compiles it on first use when the application runs; on a
 * JRE, or from a read-only working directory, it cannot. Wrappers generated here ship inside
 * the application's own classes with a small manifest, and the engine takes them from there:
 * no compiler, no {@code ./gencode}.
 *
 * <p>The plugin carries no dependency on the engine. It finds the engine on the application's
 * compile classpath and runs the generator of THAT engine, isolated: the wrappers are made by
 * the version the application will run, never by a copy the plugin was built with.</p>
 *
 * <p>Everything on the compile classpath is scanned — the application's classes, the engine's,
 * a model shared through another module's jar. A wrapper whose class and engine version are
 * unchanged is not written again, so the goal is cheap in an incremental build.</p>
 */
@Mojo(name = "generate-wrappers", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class GenerateWrappersMojo extends AbstractMojo {

    /** The engine's build-time generator — named, not linked. */
    private static final String GENERATOR = "io.github.xdstorage.code.XdStorageAotWrapperGenerator";
    /** The slots of the generator's report: the scanned count, then three name lists, one per line. */
    private static final int SCANNED = 0;
    private static final int ENTITIES_WRITTEN = 1;
    private static final int ENTITIES_CURRENT = 2;
    private static final int WRAPPERS_WRITTEN = 3;
    /** The compiler plugin's own default target: a wrapper built for 8 runs on every later Java. */
    private static final String DEFAULT_RELEASE = "8";

    /**
     * The application's classes: scanned for entity classes, and where the wrappers and their
     * manifest are written — so they ship with the application.
     */
    @Parameter(required = true, defaultValue = "${project.build.outputDirectory}")
    private File classesDirectory;

    /** More directories and jars to scan for entity classes, besides the compile classpath. */
    @Parameter
    private List<String> additionalRoots;

    /**
     * The Java release the wrappers are compiled for. Unset: {@code maven.compiler.release}
     * when the project has it, else {@code 8}.
     */
    @Parameter(property = "xdstorage.codegen.release")
    private String release;

    /** Skips the generation altogether. */
    @Parameter(property = "xdstorage.codegen.skip", defaultValue = "false")
    private boolean skip;

    /** The project being built. */
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /** Maven's entry. */
    public GenerateWrappersMojo() {
    }

    /** The same mojo with its parameters set — for the plugin's own tests. */
    GenerateWrappersMojo(final File classesDirectory, final List<String> additionalRoots, final String release,
                         final boolean skip, final MavenProject project) {
        this.classesDirectory = classesDirectory;
        this.additionalRoots = additionalRoots;
        this.release = release;
        this.skip = skip;
        this.project = project;
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("XdStorage build-time wrappers: skipped");
            return;
        }
        final List<Path> roots = new ArrayList<>();
        roots.add(classesDirectory.toPath());
        if (additionalRoots != null) {
            for (final String root : additionalRoots) {
                roots.add(Paths.get(root));
            }
        }
        final List<Path> classpath = new ArrayList<>();
        try {
            for (final String entry : project.getCompileClasspathElements()) {
                if (entry == null || entry.isEmpty()) {
                    continue;
                }
                final Path path = Paths.get(entry);
                if (Files.exists(path) && !roots.contains(path)) {
                    classpath.add(path);
                }
            }
        } catch (final DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("the compile classpath is not resolved: " + e.getMessage(), e);
        }
        // every entry is scanned: the engine's own classes are entities, and so may be a model
        // shared through another module's jar
        roots.addAll(classpath);

        final String target = effectiveRelease();
        getLog().info("XdStorage build-time wrappers: scanning " + roots.size() + " classpath entries, release "
                + target);
        final URLClassLoader loader = new URLClassLoader(urls(roots), ClassLoader.getSystemClassLoader().getParent());
        try {
            final Class<?> generator = Class.forName(GENERATOR, true, loader);
            final String[] report = (String[]) generator
                    .getMethod("generateHereReported", List.class, Path.class, String.class)
                    .invoke(null, roots, classesDirectory.toPath(), target);
            getLog().info("XdStorage build-time wrappers: " + report[SCANNED] + " class(es) scanned, "
                    + lines(report[ENTITIES_WRITTEN]) + " entity class(es) written, " + lines(report[ENTITIES_CURRENT])
                    + " already current, " + lines(report[WRAPPERS_WRITTEN]) + " wrapper class(es) into "
                    + classesDirectory);
        } catch (final InvocationTargetException e) {
            throw failure(e.getCause());
        } catch (final ClassNotFoundException e) {
            throw new MojoExecutionException("no XdStorage engine is on the compile classpath — the plugin is "
                    + "for a project that depends on io.github.xdstorage:xdstorage", e);
        } catch (final LinkageError | ReflectiveOperationException e) {
            throw new MojoExecutionException("the XdStorage engine on the compile classpath cannot run its "
                    + "build-time wrapper generator (" + e + ") — are the engine's dependencies on the classpath "
                    + "too?", e);
        } finally {
            try {
                loader.close();
            } catch (final IOException e) {
                getLog().debug("the generator's loader did not close: " + e);
            }
        }
    }

    private static MojoExecutionException failure(final Throwable cause) {
        if (cause instanceof IOException) {
            return new MojoExecutionException("XdStorage build-time wrappers: " + cause.getMessage(), cause);
        }
        if (cause instanceof LinkageError) {
            return new MojoExecutionException("XdStorage build-time wrappers: the engine needs a class that is "
                    + "not on the compile classpath (" + cause + ")", cause);
        }
        return new MojoExecutionException("XdStorage build-time wrappers: " + cause, cause);
    }

    /** The parameter, else the project's compiler release property, else the compiler plugin's default target. */
    private String effectiveRelease() {
        if (release != null && !release.trim().isEmpty()) {
            return normalise(release);
        }
        String configured = project.getProperties().getProperty("maven.compiler.release");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getProperty("maven.compiler.release");
        }
        return configured == null || configured.trim().isEmpty() ? DEFAULT_RELEASE : normalise(configured);
    }

    private static String normalise(final String release) {
        return release.trim().replaceFirst("^1\\.", "");
    }

    private static int lines(final String report) {
        return report.isEmpty() ? 0 : report.split("\n").length;
    }

    private static URL[] urls(final List<Path> paths) throws MojoExecutionException {
        final List<URL> urls = new ArrayList<>();
        for (final Path path : paths) {
            if (Files.exists(path)) {
                try {
                    urls.add(path.toUri().toURL());
                } catch (final MalformedURLException e) {
                    throw new MojoExecutionException("cannot put " + path + " on a classpath: " + e.getMessage(), e);
                }
            }
        }
        return urls.toArray(new URL[0]);
    }
}
