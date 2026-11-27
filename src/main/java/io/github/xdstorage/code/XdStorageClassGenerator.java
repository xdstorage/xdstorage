package io.github.xdstorage.code;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageWrapperGenerationException;
import io.github.xdstorage.object.XdStorageIdentifiableObject;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime wrapper codegen entry point: builds wrapper subclass sources with the
 * dedicated code generators, compiles them in-process with the system Java compiler
 * and loads the results through a single shared {@code URLClassLoader} over
 * {@code ./gencode} that is pinned for the JVM's lifetime. The loader also bridges in
 * the code sources of the entity hierarchy so application-loaded entities stay linkable.
 *
 * <p>This is the RUNTIME half of wrapper generation and the fallback of the build-time one
 * (FP-3, docs/tasks/2.2.0/08): {@link XdStorageProxyCompilationEngine} first looks for a
 * wrapper generated while the application was built. A JDK is required for this half — on
 * a runtime without a compiler, or when compilation or loading fails, the generation
 * methods throw {@link io.github.xdstorage.exceptions.XdStorageWrapperGenerationException}
 * (until 2.2.0 they logged the loss and returned an empty map, and callers went on with the
 * dummy wrappers). All public entry points are {@code synchronized}, so generation is
 * thread-safe by mutual exclusion. Engine-internal — not part of the public API.</p>
 */
public class XdStorageClassGenerator {

    /** Engine-internal holder: all functionality is static. */
    public XdStorageClassGenerator() {
    }

    private static final Logger log = LoggerFactory.getLogger(XdStorageClassGenerator.class);
    private static final String CODE_DIRECTORY = "gencode";
    static final String CLASSES_PACKAGE = "io.github.xdstorage.code";

    // АРХИТЕКТУРНОЕ ИСПРАВЛЕНИЕ: Выносим URLClassLoader в единое статическое переиспользуемое поле СУБД!
    // Это полностью пресекает утечки памяти в Metaspace JVM и ликвидирует взаимные дедлоки
    // параллельных потоков при иерархическом вызове Class.forName()!
    /**
     * FP-3 (docs/tasks/2.2.0/08): the directory and the loader appear only when the RUNTIME
     * path is actually taken. An application whose wrappers were generated at build time
     * never gets here, so it never needs a writable working directory. (Until 2.2.0 the
     * static initializer created {@value CODE_DIRECTORY} the moment this class was loaded.)
     */
    private static volatile BridgingURLClassLoader sharedClassLoader;

    private static BridgingURLClassLoader sharedClassLoader() {
        BridgingURLClassLoader loader = sharedClassLoader;
        if (loader == null) {
            synchronized (XdStorageClassGenerator.class) {
                loader = sharedClassLoader;
                if (loader == null) {
                    ensureCodeDirectory();
                    try {
                        loader = new BridgingURLClassLoader(
                                new URL[]{new File(CODE_DIRECTORY).toURI().toURL()},
                                XdStorageClassGenerator.class.getClassLoader());
                        sharedClassLoader = loader;
                    } catch (final IOException | RuntimeException e) {
                        throw new RuntimeException("Критическая ошибка инициализации транзакционного ClassLoader СУБД ("
                                + CODE_DIRECTORY + " under " + new File(".").getAbsolutePath() + ")", e);
                    }
                }
            }
        }
        return loader;
    }

    /**
     * The directory the runtime compilation writes to ({@value CODE_DIRECTORY}). Checked
     * BEFORE the compiler runs: in a read-only working directory the compiler would fail
     * on its first write with a "could not create parent directories" diagnostic — true,
     * but it names the symptom and not the way out. Until 2.2.0 this case degraded to the
     * dummy wrappers behind one log line.
     *
     * @throws XdStorageWrapperGenerationException when the directory cannot be created
     */
    public static void ensureCodeDirectory() {
        final File path = new File(CODE_DIRECTORY, CLASSES_PACKAGE.replace('.', '/'));
        if (!path.exists() && !path.mkdirs() && !path.exists()) {
            throw XdStorageWrapperGenerationException.noWritableDirectory(path,
                    new IOException("cannot create " + path));
        }
    }

    /**
     * Generates, compiles and loads the unmodifiable wrapper subclass of the given
     * class — the {@code XdStorageIdentifiableObject}-specific generator for the
     * infrastructure base class, the generic one for entity classes.
     *
     * @param cl the class to wrap
     * @return the loaded generated class keyed by {@code cl}
     * @throws IOException propagated from the compilation task
     * @throws io.github.xdstorage.exceptions.XdStorageWrapperGenerationException when this
     *         runtime has no compiler, or the wrapper does not compile or load
     */
    public static synchronized Map<Class<?>, Class<?>> generateUnmodifiableWrapper(final Class<?> cl) throws IOException
            {
        final Map<String, String> generatedCode = new HashMap<>();
        unmodifiableWrapperSource(cl, generatedCode);
        return compileGeneratedClassesCode(cl, generatedCode);
    }

    /**
     * Generates only the SOURCE of the unmodifiable wrapper subclass — what the build-time
     * generator compiles where the application is built ({@link XdStorageAotWrapperGenerator}).
     *
     * @param cl the class to wrap
     * @param generatedCode accumulator map keyed by fully-qualified generated class name
     */
    public static void unmodifiableWrapperSource(final Class<?> cl, final Map<String, String> generatedCode) {
        if (cl == XdStorageIdentifiableObject.class) {
            new XdStorageUnmodifiableXdStorageObjectWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl,
                    generatedCode);
        } else {
            new XdStorageUnmodifiableWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl, generatedCode);
        }
    }

    /**
     * Generates only the SOURCE of the simple (lazy-loading) wrapper subclass.
     *
     * @param cl the entity class to wrap
     * @param generatedCode accumulator map keyed by fully-qualified generated class name
     */
    public static void simpleWrapperSource(final Class<?> cl, final Map<String, String> generatedCode) {
        new XdStorageSimpleWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl, generatedCode);
    }

    /**
     * Generates only the SOURCE of the id-observable wrapper subclass.
     *
     * @param cl the entity class to wrap
     * @param generatedCode accumulator map keyed by fully-qualified generated class name
     */
    public static void observableWrapperSource(final Class<?> cl, final Map<String, String> generatedCode) {
        new XdStorageObservableWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl, generatedCode);
    }

    /**
     * Generates, compiles and loads the simple (lazy-loading) wrapper subclass of the
     * given class.
     *
     * @param cl the entity class to wrap
     * @return the loaded generated class keyed by {@code cl}
     * @throws IOException propagated from the compilation task
     * @throws io.github.xdstorage.exceptions.XdStorageWrapperGenerationException when this
     *         runtime has no compiler, or the wrapper does not compile or load
     */
    public static synchronized Map<Class<?>, Class<?>> generateSimpleWrapper(final Class<?> cl) throws IOException {
        final Map<String, String> generatedCode = new HashMap<>();
        simpleWrapperSource(cl, generatedCode);
        return compileGeneratedClassesCode(cl, generatedCode);
    }

    /**
     * Generates, compiles and loads the id-observable wrapper subclass of the given
     * class used by the observer service for deferred-id notification.
     *
     * @param cl the entity class to wrap
     * @return the loaded generated class keyed by {@code cl}
     * @throws IOException propagated from the compilation task
     * @throws io.github.xdstorage.exceptions.XdStorageWrapperGenerationException when this
     *         runtime has no compiler, or the wrapper does not compile or load
     */
    public static synchronized Map<Class<?>, Class<?>> generateObservableWrapper(final Class<?> cl) throws
            IOException {
        final Map<String, String> generatedCode = new HashMap<>();
        observableWrapperSource(cl, generatedCode);
        return compileGeneratedClassesCode(cl, generatedCode);
    }

    private static Map<Class<?>, Class<?>> compileGeneratedClassesCode(final Class<?> cl, final Map<String, String>
            generatedCode) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // FP-3 (docs/tasks/2.2.0/08): until 2.2.0 this logged one ERROR and went on with
            // the dummy wrappers; without a wrapper the engine is not usable — say so, loudly.
            throw io.github.xdstorage.exceptions.XdStorageWrapperGenerationException.noCompiler(cl);
        }
        ensureCodeDirectory();

        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        final XdStorageJavaSourceFromString[] files = new XdStorageJavaSourceFromString[generatedCode.size()];
        int i = 0;
        for (final Map.Entry<String, String> entry : generatedCode.entrySet()) {
            files[i++] = new XdStorageJavaSourceFromString(entry.getKey(), entry.getValue());
        }

        final Iterable<? extends JavaFileObject> compilationUnits = Arrays.asList(files);
        // R-48: под манифест-бутером surefire System.getProperty("java.class.path") содержит
        // только booter.jar - рантайм-компилятор оберток не видел классы сущностей и молча
        // деградировал в dummy-обертки (а чтение сущностей падало в CHM.put(null)).
        // Расширяем classpath кодовыми источниками генерируемого графа: сама сущность,
        // ее суперклассы и ядро СУБД. В обычном запуске это no-op (они уже в java.class.path).
        String currentClasspath = buildCompilationClasspath(cl);
        final Iterable<String> options = Arrays.asList("-d", CODE_DIRECTORY, "-classpath", currentClasspath);
        JavaCompiler.CompilationTask task = compiler.getTask(null, null, diagnostics, options, null, compilationUnits);

        boolean success = task.call();
        if (!success) {
            // SILENT-LOSS BARRIER: ошибка компиляции обертки раньше проглатывалась — движок
            // уходил в dummy-обертки без единого следа в логе. Печатаем диагностику компилятора.
            final String report = "the wrapper source for " + cl.getName() + " was generated but did not compile ("
                    + generatedCode.size() + " file(s)): " + diagnostics.getDiagnostics();
            log.error(report);
            if (Boolean.getBoolean("xdstorage.dump.gencode")) {
                generatedCode.forEach((name, source) -> {
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/gencode-dump-" + name
                                .replaceAll("[^A-Za-z0-9.$]", "_") + ".java"),
                                source.getBytes());
                    } catch (final Exception dumpError) {
                        log.error("gencode dump failed for {}", name, dumpError);
                    }
                });
            }
            throw new io.github.xdstorage.exceptions.XdStorageWrapperGenerationException(report);
        }
        try {
            // BUG-A/B: make the entity's own code source linkable through the shared loader
            // BEFORE the generated wrapper loads — the wrapper's supertype IS the entity.
            for (Class<?> c = cl; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    final java.security.CodeSource source = c.getProtectionDomain().getCodeSource();
                    if (source != null && source.getLocation() != null) {
                        sharedClassLoader().addURL(source.getLocation());
                    }
                } catch (final Exception e) {
                    log.debug("cannot bridge code source of {} into the shared loader", c.getName(), e);
                }
            }
            final Map<Class<?>, Class<?>> result = new HashMap<>();
            for (final Map.Entry<String, String> entry : generatedCode.entrySet()) {
                // Используем наш единый разделяемый sharedClassLoader СУБД
                Class<?> clazz = Class.forName(entry.getKey(), true, sharedClassLoader());
                // SILENTFAIL (docs/tasks/2.1.0/35): ключом служит КЛАСС, КОТОРЫЙ
                // обертывает сгенерированный класс (его суперкласс), а не сущность-заказчик:
                // прежний result.put(cl, clazz) затирал ключ один раз за сгенерированный
                // класс, и мульти-классовый граф генерации терял все классы, кроме
                // последнего загруженного.
                final Class<?> wrapped = clazz.getSuperclass() != null ? clazz.getSuperclass() : cl;
                result.put(wrapped, clazz);
            }
            return result;
        } catch (ClassNotFoundException | LinkageError e) {
            final String report = "the wrapper for " + cl.getName()
                    + " compiled at runtime but cannot be loaded: " + e;
            log.error(report, e);
            throw new io.github.xdstorage.exceptions.XdStorageWrapperGenerationException(report, e);
        }
    }

    /**
     * BUG-A/B (docs/tasks/2.1.0/15, /16): the shared loader can BRIDGE in the code source of
     * application-loaded entity classes — an entity the app provided through its own
     * classloader (an upgraded generation) must become visible here, or the generated wrapper
     * fails to LINK (NoClassDefFoundError on the entity supertype at defineClass).
     */
    /**
     * Child of the shared loader that re-opens {@code addURL} so generated wrappers can
     * link against entity classes supplied through arbitrary application classloaders.
     */
    private static final class BridgingURLClassLoader extends URLClassLoader {
        BridgingURLClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public void addURL(final URL url) {
            super.addURL(url);
        }

        /**
         * BUG-B (docs/tasks/2.1.0/16): the bridged entity URLs let this loader DEFINE ITS
         * OWN COPY of an entity class the application had already loaded through its own
         * classloader — two live classes of one FQCN, and every generated wrapper linked
         * against the shared copy failed the application's {@code instanceof}/field
         * access ("object is not an instance of declaring class"). An entity class the
         * engine has already REGISTERED is therefore delegated to the registry's exact
         * instance instead of being defined here; only unregistered names (the generated
         * wrappers themselves, bridged entities not yet registered) are defined locally.
         */
        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            final Class<?> registered = io.github.xdstorage.utils.XdStorageClassMetadataRegistry
                    .findLoadedClassByName(name);
            if (registered != null) {
                if (resolve) {
                    resolveClass(registered);
                }
                return registered;
            }
            return super.loadClass(name, resolve);
        }
    }

    private static String buildCompilationClasspath(final Class<?> cl) {
        final java.util.Set<String> entries = new java.util.LinkedHashSet<>();
        entries.addAll(Arrays.asList(System.getProperty("java.class.path", "").split(File.pathSeparator)));
        entries.removeIf(String::isEmpty);

        // кодовые источники иерархии сущности (обычно target/test-classes или jar сущности)
        for (Class<?> c = cl; c != null && c != Object.class; c = c.getSuperclass()) {
            addCodeSource(entries, c);
        }
        // кодовый источник ядра СУБД (все движковые типы, на которые ссылается обертка)
        addCodeSource(entries, XdStorageClassGenerator.class);
        addCodeSource(entries, io.github.xdstorage.observing.XdStorageAbstractIdObserver.class);

        return String.join(File.pathSeparator, entries);
    }

    private static void addCodeSource(final java.util.Set<String> entries, final Class<?> c) {
        try {
            final java.security.CodeSource source = c.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                entries.add(new File(source.getLocation().toURI()).getAbsolutePath());
            }
        } catch (final Exception e) {
            log.debug("cannot resolve code source of {}", c.getName(), e);
        }
    }
}
