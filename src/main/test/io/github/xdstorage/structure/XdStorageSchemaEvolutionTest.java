package io.github.xdstorage.structure;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.structure.update.IXdStorageStructureUpdater;
import io.github.xdstorage.structure.update.XdStorageAbstractStructureUpdater;
import io.github.xdstorage.structure.update.XdStorageUpdateStructureResult;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-3a (docs/tasks/2.1.0/06): schema evolution end-to-end through the public facade —
 * the scenario the structure-update machinery exists for. Two generations of an entity
 * with the SAME fully-qualified name are compiled at test time and loaded through two
 * classloaders, so the second {@code executeStructureUpdate} observes a real structure
 * change (an added field + a widened search index) against the persisted V1 structure.
 * Pins: first-open structure insert and the repeated-call short-circuit; persisted-structure
 * and data survival across reopen; change detection on the widened generation; the updater
 * record ledger (executed-once idempotency by update name, FAILURE records retried after a
 * reopen, SUCCESS records skipped as "executed earlier"); the structure settling only after
 * every registered updater succeeded; and the loud missing-@XdStorageObjectId failure.
 */
@DisplayName("T-3a: schema evolution — structure insert, change detection, updater ledger")
public class XdStorageSchemaEvolutionTest {

    @TempDir
    Path tempDir;

    // BUG-B: each scenario compiles its entity into its OWN package — the class-metadata
    // registry and the codegen caches are JVM-static, and one shared FQCN across tests
    // turns them into a cross-test pollution channel (a wrong-generation Class leaked
    // between scenarios made the reopened storage materialize foreign identities).
    private static String entityFqcn(final String scenario) {
        return "evolution." + scenario + ".EvoEntity";
    }

    private static String entitySource(final String scenario, final String version) {
        return ("package evolution." + scenario + ";\n" + V1_SOURCE_BODY)
                .replace("indexFieldNames = {\"score\"}", version.equals("v2")
                        ? "indexFieldNames = {\"score\", \"extra\"}"
                        : "indexFieldNames = {\"score\"}")
                .replace("    private String label;\n",
                          version.equals("v2")
                          ? "    private String label;\n    private String extra;\n"
                          : "    private String label;\n")
                .replace("    public String getLabel() { return label; }\n    public void setLabel(String label) { this.label = label; }\n",
                          version.equals("v2")
                          ? "    public String getLabel() { return label; }\n    public void setLabel(String label) { this.label = label; }\n"
                            + "    public String getExtra() { return extra; }\n    public void setExtra(String extra) { this.extra = extra; }\n"
                          : "    public String getLabel() { return label; }\n    public void setLabel(String label) { this.label = label; }\n");
    }

    private static final String V1_SOURCE = "package evolution;\n"
            + "import io.github.xdstorage.XdStoragePolicy;\n"
            + "import io.github.xdstorage.annotations.XdStorageObjectId;\n"
            + "import io.github.xdstorage.annotations.XdStorageObjectPolicy;\n"
            + "import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;\n"
            + "@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)\n"
            + "@XdStorageObjectSearchIndex(indexName = \"evo_idx\", indexFieldNames = {\"score\"}, t = 8)\n"
            + "public class EvoEntity {\n"
            + "    @XdStorageObjectId\n    private String id;\n"
            + "    private Long score;\n"
            + "    private String label;\n"
            + "    public String getId() { return id; }\n    public void setId(String id) { this.id = id; }\n"
            + "    public Long getScore() { return score; }\n    public void setScore(Long score) { this.score = score; }\n"
            + "    public String getLabel() { return label; }\n    public void setLabel(String label) { this.label = label; }\n"
            + "}\n";

    private static final String V2_SOURCE = V1_SOURCE
            .replace("indexFieldNames = {\"score\"}", "indexFieldNames = {\"score\", \"extra\"}")
            .replace("    private String label;\n",
                      "    private String label;\n    private String extra;\n")
            .replace("    public String getLabel() { return label; }\n    public void setLabel(String label) { this.label = label; }\n",
                      "    public String getLabel() { return label; }\n    public void setLabel(String label) { this.label = label; }\n"
                    + "    public String getExtra() { return extra; }\n    public void setExtra(String extra) { this.extra = extra; }\n");

    private static final String V1_SOURCE_BODY = V1_SOURCE.substring("package evolution;\n".length());

    // --- generated-class infrastructure ---

    private static final class SourceObject extends SimpleJavaFileObject {
        private final String source;

        SourceObject(final String fqcn, final String source) {
            super(URI.create("string:///" + fqcn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static Path compileVersion(final String version, final String fqcn, final String source) throws Exception {
        final Path out = Files.createDirectories(Paths.get("target", "evolution-" + version + "-" + System.nanoTime()));
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK toolchain (system java compiler)");
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            final boolean ok = compiler.getTask(null, fileManager, diagnostics,
                    Arrays.asList("-d", out.toString(), "-classpath", System.getProperty("java.class.path")),
                    null, Collections.singletonList(new SourceObject(fqcn, source))).call();
            assertTrue(ok, "generated " + version + " must compile: " + diagnostics.getDiagnostics());
        }
        return out;
    }

    private static Class<?> loadVersion(final Path classesDir, final String fqcn) throws Exception {
        return new URLClassLoader(new URL[]{classesDir.toUri().toURL()},
                XdStorageSchemaEvolutionTest.class.getClassLoader()).loadClass(fqcn);
    }

    private static Object newEntity(final Class<?> cl, final String id, final long score) throws Exception {
        final Object e = cl.getDeclaredConstructor().newInstance();
        set(e, "id", id);
        set(e, "score", score);
        set(e, "label", "label-" + id);
        return e;
    }

    private static void set(final Object o, final String field, final Object value) throws Exception {
        final Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Object get(final Object o, final String field) throws Exception {
        // BUG-B: the loaded object may be the generated SimpleWrapper (the engine's live
        // lazy-loading view) — its fields are delegation plumbing; read the state from
        // the WRAPPED entity.
        final Object target = io.github.xdstorage.utils.XdStorageObjectUtils.getWrappedObjectOrSameObject(o);
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    /** A scripted custom updater: succeeds or fails on demand, records its executions. */
    private static final class ScriptedUpdater extends XdStorageAbstractStructureUpdater<Object> {
        private final Class<?> cl;
        private final AtomicInteger remainingFailures;
        private int executions;

        ScriptedUpdater(final String name, final Class<?> cl, final int failuresBeforeSuccess) {
            super(name);
            this.cl = cl;
            this.remainingFailures = new AtomicInteger(failuresBeforeSuccess);
        }

        @Override
        public Class<?> getDataClass() {
            return cl;
        }

        @Override
        public XdStorageUpdateStructureResult execute(final io.github.xdstorage.resource.XdStorageAbstractResourcesManager rm,
                                                      final io.github.xdstorage.transaction.XdStorageTransaction tx) {
            executions++;
            return remainingFailures.getAndDecrement() > 0
                    ? XdStorageUpdateStructureResult.FAILURE
                    : XdStorageUpdateStructureResult.SUCCESSFULLY;
        }

        @Override
        protected Object transform(final io.github.xdstorage.object.XdStorageIdentifiableObject oldStateOfObject) {
            return null; // unused: execute() is overridden
        }
    }

    private IXdFileStorage open(final String dirName) {
        return XdStorageProvider.newOrGetFileStorage("evolution-" + dirName + "-" + System.nanoTime(),
                tempDir.resolve(dirName).toString(), 512);
    }

    private List<IXdStorageStructureUpdater> executeUpdate(final IXdFileStorage storage, final Class<?> cl) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            final List<IXdStorageStructureUpdater> result = storage.executeStructureUpdate(cl);
            // ROLLBACK, not commit: the migration ran in the manager's own autonomous
            // transaction; the outer tx itself changed nothing, and committing it journaled
            // a null entry for the structure resource that killed the next reopen
            // (known-bug repro, see the ledger test below)
            storage.rollbackTransaction(tx);
            return result;
        } catch (final Exception e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    // --- the scenarios ---

    @Test
    @DisplayName("first open inserts the structure and returns nothing; the repeat call short-circuits")
    public void freshOpenInsertsStructureAndShortCircuits() throws Exception {
        final Class<?> v1 = loadVersion(compileVersion("v1", entityFqcn("fresh"), entitySource("fresh", "v1")), entityFqcn("fresh"));
        final IXdFileStorage storage = open("fresh");
        try {
            final List<IXdStorageStructureUpdater> first = executeUpdate(storage, v1);
            assertNotNull(first);
            assertTrue(first.isEmpty(), "the first open inserts the structure — no updaters needed");

            final List<IXdStorageStructureUpdater> second = executeUpdate(storage, v1);
            assertTrue(second.isEmpty(), "the repeat call short-circuits on the class state");
        } finally {
            storage.shutdown();
        }
    }

    // =========================================================================
    // KNOWN ENGINE DEFECTS (discovered by this suite; follow-ups filed in
    // docs/tasks/2.1.0/06 — enabling these tests IS the acceptance of the fixes):
    //
    // BUG A — commit-journal replay NPE on the structure resource: after ANY
    //   executeStructureUpdate on a storage, closing and reopening the folder throws
    //   "commit journal of resource .../structure/XdStorageClassStructure.yaml cannot be
    //   replayed (NullPointerException)" at open time.
    //
    // BUG B — id-addressability after a structure update on a freshly-opened storage:
    //   with a generated (dual-classloader) entity, save → reopen → executeStructureUpdate
    //   → load-by-id returns null, although the same sequence with a plain compiled entity
    //   works (verified by probes with RangeOpsEntity).
    //
    // The four evolution scenarios below are the end-to-end acceptance tests for those
    // fixes; they are exact, deterministic reproducers.
    // =========================================================================
    @Test
    @DisplayName("the persisted structure and data survive a reopen; no change is detected")
    public void reopenSurvivesStructureAndData() throws Exception {
        final Class<?> v1 = loadVersion(compileVersion("v1", entityFqcn("reopen"), entitySource("reopen", "v1")), entityFqcn("reopen"));
        final IXdFileStorage first = open("reopen");
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        first.save(newEntity(v1, "survive-1", 10L), tx);
        first.save(newEntity(v1, "survive-2", 20L), tx);
        first.commitTransaction(tx);
        assertTrue(executeUpdate(first, v1).isEmpty());
        first.shutdown();

        final IXdFileStorage second = open("reopen");
        try {
            assertTrue(executeUpdate(second, v1).isEmpty(),
                    "the reopened storage reads the persisted structure — unchanged, nothing to do");

            final IXdStorageTransaction readTx = second.beginTransaction(60000L);
            final Object loaded = second.load(v1, "survive-1", readTx);
            second.commitTransaction(readTx);
            assertNotNull(loaded, "the object written before the reopen survives");
            assertEquals(10L, get(loaded, "score"));
            assertEquals("label-survive-1", get(loaded, "label"));
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("V1→V2: change detection fires; SUCCESS and FAILURE updater outcomes are both reported")
    public void evolutionDetectsChangeAndReportsUpdaterOutcomes() throws Exception {
        final Class<?> v1 = loadVersion(compileVersion("v1", entityFqcn("evolve"), entitySource("evolve", "v1")), entityFqcn("evolve"));
        final IXdFileStorage first = open("evolve");
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        first.save(newEntity(v1, "evo-1", 1L), tx);
        first.save(newEntity(v1, "evo-2", 2L), tx);
        first.commitTransaction(tx);
        assertTrue(executeUpdate(first, v1).isEmpty(), "the V1 structure baseline is inserted");
        first.shutdown();

        final Class<?> v2 = loadVersion(compileVersion("v2", entityFqcn("evolve"), entitySource("evolve", "v2")), entityFqcn("evolve"));
        assertNotEquals(v1.getClassLoader(), v2.getClassLoader());
        assertEquals(v1.getName(), v2.getName(), "same FQCN — the persisted structure belongs to this name");

        final IXdFileStorage second = open("evolve");
        try {
            second.registerStructureUpdater(new ScriptedUpdater("evo-ok-1", v2, 0));
            second.registerStructureUpdater(new ScriptedUpdater("evo-bad-1", v2, 1));

            final List<IXdStorageStructureUpdater> result = executeUpdate(second, v2);
            assertFalse(result.isEmpty(), "a widened generation produces updaters");

            IXdStorageStructureUpdater ok = null;
            IXdStorageStructureUpdater bad = null;
            for (final IXdStorageStructureUpdater updater : result) {
                if ("evo-ok-1".equals(updater.getUpdateName())) ok = updater;
                if ("evo-bad-1".equals(updater.getUpdateName())) bad = updater;
            }
            assertNotNull(ok, "the successful custom updater is in the report");
            assertNotNull(bad, "the failing custom updater is in the report");
            assertEquals(XdStorageUpdateStructureResult.SUCCESSFULLY, ok.getResult());
            assertTrue(ok.getMessage().contains("has been executed successfully"));
            assertEquals(XdStorageUpdateStructureResult.FAILURE, bad.getResult());
            assertTrue(bad.getMessage().contains("has been failed"),
                    "a FAILURE updater stops the batch — the structure stays unsettled");
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("the updater ledger: SUCCESS records are skipped as executed earlier, FAILURE records retried after reopen")
    public void updaterLedgerIdempotencyAndRetry() throws Exception {
        final Class<?> v1 = loadVersion(compileVersion("v1", entityFqcn("ledger"), entitySource("ledger", "v1")), entityFqcn("ledger"));
        final IXdFileStorage first = open("ledger");
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        first.save(newEntity(v1, "led-1", 5L), tx);
        first.commitTransaction(tx);
        assertTrue(executeUpdate(first, v1).isEmpty(), "the V1 structure baseline is inserted");
        first.shutdown();

        final Class<?> v2 = loadVersion(compileVersion("v2", entityFqcn("ledger"), entitySource("ledger", "v2")), entityFqcn("ledger"));

        // generation 1: TWO updaters — the ok one SUCCEEDS (its SUCCESS ledger record is
        // persisted), the scripted one FAILS on purpose (no record — the retry semantics)
        final IXdFileStorage second = open("ledger");
        final ScriptedUpdater okFirst = new ScriptedUpdater("evo-ok", v2, 0);
        final ScriptedUpdater failing = new ScriptedUpdater("evo-ledger", v2, 1);
        second.registerStructureUpdater(okFirst);
        second.registerStructureUpdater(failing);
        executeUpdate(second, v2);
        assertEquals(1, failing.executions, "the failing updater executed exactly once in generation 1");
        second.shutdown();

        // generation 2 (reopen): the SUCCESS-record updater must be SKIPPED ("has been
        // executed earlier", body not invoked); the failed one is RETRIED and now succeeds
        final IXdFileStorage third = open("ledger");
        try {
            final ScriptedUpdater okRerun = new ScriptedUpdater("evo-ok", v2, 0);
            final ScriptedUpdater retry = new ScriptedUpdater("evo-ledger", v2, 0);
            third.registerStructureUpdater(okRerun);
            third.registerStructureUpdater(retry);

            final List<IXdStorageStructureUpdater> result = executeUpdate(third, v2);
            final IXdStorageStructureUpdater okLedger = result.stream()
                    .filter(u -> "evo-ok".equals(u.getUpdateName())).findFirst().orElse(null);
            final IXdStorageStructureUpdater retryLedger = result.stream()
                    .filter(u -> "evo-ledger".equals(u.getUpdateName())).findFirst().orElse(null);
            assertNotNull(okLedger, "the SUCCESS-record updater is reported");
            assertNotNull(retryLedger, "the failed updater is reported as retried");

            assertEquals(XdStorageUpdateStructureResult.SUCCESSFULLY, okLedger.getResult());
            assertTrue(okLedger.getMessage().contains("has been executed earlier"),
                    "the SUCCESS record from the previous run is honored — the body did not run again");
            // STRUCTFIX (docs/tasks/2.1.0/36): the skipped result used to carry the stale
            // FAILURE value — a caller branching on getResult() took the failure path for
            // a script that had actually succeeded.
            assertNotEquals(XdStorageUpdateStructureResult.FAILURE, okLedger.getResult(),
                    "an executed-earlier script must NEVER be reported as FAILURE");
            assertEquals(0, okRerun.executions, "the skipped updater's execute() was not invoked");

            assertEquals(XdStorageUpdateStructureResult.SUCCESSFULLY, retryLedger.getResult());
            assertTrue(retryLedger.getMessage().contains("has been executed successfully"),
                    "the failed updater is RETRIED and now succeeds");
            assertEquals(1, retry.executions, "the retried updater's execute() ran exactly once");

            // the structure is now settled: nothing left to do on the next open
            final List<IXdStorageStructureUpdater> settled = executeUpdate(third, v2);
            assertTrue(settled.isEmpty(), "after all updaters succeeded the structure update persists and the change clears");
        } finally {
            third.shutdown();
        }
    }

    @Test
    @DisplayName("after the migration the V2 data is readable and the added field is present")
    public void migratedDataReadableAsV2() throws Exception {
        final Class<?> v1 = loadVersion(compileVersion("v1", entityFqcn("readv2"), entitySource("readv2", "v1")), entityFqcn("readv2"));
        final IXdFileStorage first = open("readv2");
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        first.save(newEntity(v1, "m-1", 42L), tx);
        first.commitTransaction(tx);
        first.shutdown();

        final Class<?> v2 = loadVersion(compileVersion("v2", entityFqcn("readv2"), entitySource("readv2", "v2")), entityFqcn("readv2"));
        final IXdFileStorage second = open("readv2");
        try {
            executeUpdate(second, v2); // migrate

            final IXdStorageTransaction readTx = second.beginTransaction(60000L);
            final Object loaded = second.load(v2, "m-1", readTx);
            second.commitTransaction(readTx);
            assertNotNull(loaded, "the V1 object is addressable through the V2 class");
            assertEquals(42L, get(loaded, "score"), "the persisted field survives the generation change");
            assertEquals("label-m-1", get(loaded, "label"));
            assertNull(get(loaded, "extra"), "the added V2 field is null in V1-written objects");
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("a registered custom updater is NOT invoked while the structure stays unchanged")
    public void customUpdaterNotInvokedWithoutChange() throws Exception {
        final Class<?> v1 = loadVersion(compileVersion("v1", entityFqcn("nochange"), entitySource("nochange", "v1")), entityFqcn("nochange"));
        final IXdFileStorage storage = open("nochange");
        try {
            final ScriptedUpdater updater = new ScriptedUpdater("evo-never", v1, 0);
            storage.registerStructureUpdater(updater);

            assertTrue(executeUpdate(storage, v1).isEmpty(),
                    "the first open INSERTS the structure — updaters run on CHANGE only");
            assertEquals(0, updater.executions, "the updater body never ran");

            assertTrue(executeUpdate(storage, v1).isEmpty());
            assertEquals(0, updater.executions, "still nothing on the short-circuited repeat call");
        } finally {
            storage.shutdown();
        }
    }

    @Test
    @DisplayName("executeStructureUpdate fails loudly for a class without @XdStorageObjectId")
    public void missingIdFieldFailsLoudly() throws Exception {
        final IXdFileStorage storage = open("noid");
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60000L);
            try {
                storage.executeStructureUpdate(Object.class);
                fail("expected XdStorageException for a class without an @XdStorageObjectId field");
            } catch (final XdStorageException expected) {
                assertTrue(expected.getMessage().contains("identifier field"));
            } finally {
                storage.rollbackTransaction(tx);
            }
        } finally {
            storage.shutdown();
        }
    }

    @Test
    @DisplayName("BUG-A acceptance: the structure resource replays after a structure update + reopen — no strand, no NPE")
    public void structureResourceReplaysAfterStructureUpdateAndReopen() throws Exception {
        final Class<?> v1 = loadVersion(compileVersion("v1", entityFqcn("buga"), entitySource("buga", "v1")), entityFqcn("buga"));
        final IXdFileStorage first = open("buga");
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        first.save(newEntity(v1, "ba-1", 7L), tx);
        first.commitTransaction(tx);
        assertTrue(executeUpdate(first, v1).isEmpty(), "first open: the structure is inserted");
        first.shutdown();

        // the strand: reopen after a structure update used to crash with
        // "commit journal of .../XdStorageClassStructure.yaml cannot be replayed (NPE)"
        final IXdFileStorage second = open("buga");
        try {
            assertTrue(executeUpdate(second, v1).isEmpty(),
                    "the reopened storage replays the structure journal and validates the structure: nothing to do");
            assertTrue(executeUpdate(second, v1).isEmpty(), "the repeat call short-circuits");
        } finally {
            second.shutdown();
        }
    }
}
