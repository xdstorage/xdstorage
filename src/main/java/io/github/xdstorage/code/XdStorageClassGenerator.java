package io.github.xdstorage.code;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
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
 * <p>A JDK is required at runtime: on a JRE (no system compiler), or when compilation
 * or loading fails, the generation methods log the loss and return an empty map, and
 * callers degrade to the dummy wrappers with broken {@code equals}/{@code hashCode} —
 * degradation is reported once in the log, cached for the JVM lifetime and never retried.
 * All public entry points are {@code synchronized}, so generation is thread-safe by
 * mutual exclusion. Engine-internal — not part of the public API.</p>
 */
public class XdStorageClassGenerator {

    /** Engine-internal holder: all functionality is static. */
    public XdStorageClassGenerator() {
    }

    private static final Logger log = LoggerFactory.getLogger(XdStorageClassGenerator.class);
    private static final String CODE_DIRECTORY = "gencode";
    private static final String CLASSES_PACKAGE = "io.github.xdstorage.code";

    // АРХИТЕКТУРНОЕ ИСПРАВЛЕНИЕ: Выносим URLClassLoader в единое статическое переиспользуемое поле СУБД!
    // Это полностью пресекает утечки памяти в Metaspace JVM и ликвидирует взаимные дедлоки
    // параллельных потоков при иерархическом вызове Class.forName()!
    private static final BridgingURLClassLoader sharedClassLoader;

    static {
        try {
            final File path = new File(CODE_DIRECTORY, CLASSES_PACKAGE.replace('.', '/'));
            if (!path.exists()) {
                path.mkdirs();
            }
            sharedClassLoader = new BridgingURLClassLoader(
                    new URL[]{new File(CODE_DIRECTORY).toURI().toURL()},
                    XdStorageClassGenerator.class.getClassLoader());
        } catch (Exception e) {
            throw new RuntimeException("Критическая ошибка инициализации транзакционного ClassLoader СУБД", e);
        }
    }

    /**
     * Generates, compiles and loads the unmodifiable wrapper subclass of the given
     * class — the {@code XdStorageIdentifiableObject}-specific generator for the
     * infrastructure base class, the generic one for entity classes.
     *
     * @param cl the class to wrap
     * @return the loaded generated class keyed by {@code cl}; empty when no system
     *         compiler is available, compilation fails or loading fails
     * @throws IOException propagated from the compilation task
     */
    public static synchronized Map<Class<?>, Class<?>> generateUnmodifiableWrapper(final Class<?> cl) throws IOException {
        final Map<String, String> generatedCode = new HashMap<>();
        if (cl == XdStorageIdentifiableObject.class) {
            new XdStorageUnmodifiableXdStorageObjectWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl, generatedCode);
        } else {
            new XdStorageUnmodifiableWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl, generatedCode);
        }
        return compileGeneratedClassesCode(cl, generatedCode);
    }

    /**
     * Generates, compiles and loads the simple (lazy-loading) wrapper subclass of the
     * given class.
     *
     * @param cl the entity class to wrap
     * @return the loaded generated class keyed by {@code cl}; empty when no system
     *         compiler is available, compilation fails or loading fails
     * @throws IOException propagated from the compilation task
     */
    public static synchronized Map<Class<?>, Class<?>> generateSimpleWrapper(final Class<?> cl) throws IOException {
        final Map<String, String> generatedCode = new HashMap<>();
        new XdStorageSimpleWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl, generatedCode);
        return compileGeneratedClassesCode(cl, generatedCode);
    }

    /**
     * Generates, compiles and loads the id-observable wrapper subclass of the given
     * class used by the observer service for deferred-id notification.
     *
     * @param cl the entity class to wrap
     * @return the loaded generated class keyed by {@code cl}; empty when no system
     *         compiler is available, compilation fails or loading fails
     * @throws IOException propagated from the compilation task
     */
    public static synchronized Map<Class<?>, Class<?>> generateObservableWrapper(final Class<?> cl) throws IOException {
        final Map<String, String> generatedCode = new HashMap<>();
        new XdStorageObservableWrapperClassCodeGenerator().generate(CLASSES_PACKAGE, cl, generatedCode);
        return compileGeneratedClassesCode(cl, generatedCode);
    }

    private static Map<Class<?>, Class<?>> compileGeneratedClassesCode(final Class<?> cl, final Map<String, String> generatedCode) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // SILENT-LOSS BARRIER: без компилятора (JRE) сгенерированные обертки не создаются,
            // движок уходит в dummy-обертки с поврежденными equals/hashCode — молчать нельзя.
            log.error("No system Java compiler available (running on a JRE?) — wrapper generation for {} "
                    + "silently degrades to dummy wrappers (broken equals/hashCode contracts!)", cl.getName());
            return new HashMap<>();
        }

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
            log.error("Wrapper compilation FAILED for {} ({} files): {}", cl.getName(), generatedCode.size(),
                    diagnostics.getDiagnostics());
            if (Boolean.getBoolean("xdstorage.dump.gencode")) {
                generatedCode.forEach((name, source) -> {
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/gencode-dump-" + name.replaceAll("[^A-Za-z0-9.$]", "_") + ".java"),
                                source.getBytes());
                    } catch (final Exception dumpError) {
                        log.error("gencode dump failed for {}", name, dumpError);
                    }
                });
            }
        }
        if (success) {
            try {
                // BUG-A/B: make the entity's own code source linkable through the shared loader
                // BEFORE the generated wrapper loads — the wrapper's supertype IS the entity.
                for (Class<?> c = cl; c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        final java.security.CodeSource source = c.getProtectionDomain().getCodeSource();
                        if (source != null && source.getLocation() != null) {
                            sharedClassLoader.addURL(source.getLocation());
                        }
                    } catch (final Exception e) {
                        log.debug("cannot bridge code source of {} into the shared loader", c.getName(), e);
                    }
                }
                final Map<Class<?>, Class<?>> result = new HashMap<>();
                for (final Map.Entry<String, String> entry : generatedCode.entrySet()) {
                    // Используем наш единый разделяемый sharedClassLoader СУБД
                    Class<?> clazz = Class.forName(entry.getKey(), true, sharedClassLoader);
                    // SILENTFAIL (docs/tasks/2.1.0/35): ключом служит КЛАСС, КОТОРЫЙ
                    // обертывает сгенерированный класс (его суперкласс), а не сущность-заказчик:
                    // прежний result.put(cl, clazz) затирал ключ один раз за сгенерированный
                    // класс, и мульти-классовый граф генерации терял все классы, кроме
                    // последнего загруженного.
                    final Class<?> wrapped = clazz.getSuperclass() != null ? clazz.getSuperclass() : cl;
                    result.put(wrapped, clazz);
                }
                return result;
            } catch (ClassNotFoundException e) {
                log.error("cannot load generated class", e);
            }
        }
        return new HashMap<>();
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
