package io.github.xdstorage.transaction;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;
import io.github.xdstorage.code.XdStorageSimpleWrapperClassCodeGenerator;
import io.github.xdstorage.code.XdStorageUnmodifiableWrapperClassCodeGenerator;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBCAUSE (docs/tasks/2.1.0/34) — the cause-capture suite: every rollback-only marking
 * site records a first-cause (site descriptor, root exception, tx age, cascade source),
 * the commit failures name the site and chain the root exception, the first mark wins
 * and later marks only count.
 */
public class XdStorageRollbackOnlyCauseTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    @XdStorageObjectSearchIndex(indexName = "rb_idx", indexFieldNames = {"name"}, t = 8)
    public static class RbEntity {
        @XdStorageObjectId
        private Long id;
        private String name;

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
    }

    private static final String STORAGE_NAME = "rbcause-" + System.nanoTime();
    private static final String STORAGE_DIR = "./target/rbcause-db-" + System.nanoTime();

    private IXdFileStorage storage;

    @BeforeEach
    public void openStorage() {
        deleteDir(new File(STORAGE_DIR));
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 200);
    }

    @AfterEach
    public void closeStorage() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
        deleteDir(new File(STORAGE_DIR));
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    private XdStorageTransaction beginTx(final long timeout) {
        return (XdStorageTransaction) storage.beginTransaction(timeout);
    }

    // =========================================================================
    // The capture contract: first cause wins, later marks count
    // =========================================================================

    @Test
    public void noArgMark_RecordsUnspecifiedSite() {
        final XdStorageTransaction tx = beginTx(15000L);
        tx.markRollbackOnly();

        final XdStorageRollbackOnlyCause cause = tx.getRollbackOnlyCause();
        assertNotNull(cause, "the no-arg mark must record a cause");
        assertEquals("unspecified", cause.getSite());
        assertNull(cause.getCause());
        assertNull(cause.getCascadedFrom());
        assertEquals(1, cause.getRepeatMarks());
        assertEquals(tx.getTransactionId(), cause.getMarkedByTransactionId());
        assertTrue(cause.getTxAgeMillis() >= 0);
        assertTrue(cause.getMarkedAtEpochMillis() > 0);
    }

    @Test
    public void firstCauseWins_SecondMarkOnlyCounts() {
        final XdStorageTransaction tx = beginTx(15000L);
        final RuntimeException firstFailure = new RuntimeException("lock-timeout at the record");
        final RuntimeException secondFailure = new RuntimeException("unrelated later failure");

        tx.markRollbackOnly("veto A: the record is locked", firstFailure);
        tx.markRollbackOnly("veto B: something else", secondFailure);

        final XdStorageRollbackOnlyCause cause = tx.getRollbackOnlyCause();
        assertEquals("veto A: the record is locked", cause.getSite(), "the FIRST cause must win");
        assertSame(firstFailure, cause.getCause(), "the first root exception must stay recorded");
        assertEquals(2, cause.getRepeatMarks(), "the second mark must be counted, not overwrite");
    }

    @Test
    public void commitFailure_NamesSiteAndChainsRootCause() {
        final XdStorageTransaction tx = beginTx(15000L);
        final RuntimeException rootFailure = new RuntimeException("the original lazy-load failure");
        tx.markRollbackOnly("lazy-load of field 'crew' on Ship", rootFailure);

        final XdStorageRuntimeException failure = assertThrows(XdStorageRuntimeException.class, tx::commit);
        assertTrue(failure.getMessage().contains("marked as rollback only"),
                "the historical message must stay: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("lazy-load of field 'crew' on Ship"),
                "the commit failure must name the marking site: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("tx-age"), "the commit failure must state the tx age: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("the original lazy-load failure"),
                "the commit failure must carry the root message: " + failure.getMessage());
        assertSame(rootFailure, failure.getCause(), "getCause() must hand back the original exception");
    }

    // =========================================================================
    // Site #5: the nested-rollback cascade (up)
    // =========================================================================

    @Test
    public void nestedFailureCascadesUp_ParentCauseNamesSource() {
        final XdStorageTransaction parent = beginTx(15000L);
        final IXdStorageTransaction nested = storage.beginTransaction(parent, 5000L);
        final RuntimeException nestedFailure = new RuntimeException("nested graph failure");

        nested.markRollbackOnly("nested failure of the trigger graph", nestedFailure);

        assertTrue(parent.isRollbackOnly(), "the parent must be poisoned by the nested failure (cascade up)");
        final XdStorageRollbackOnlyCause cause = parent.getRollbackOnlyCause();
        assertEquals("nested failure of the trigger graph", cause.getSite());
        assertSame(nestedFailure, cause.getCause());
        assertEquals(nested.getTransactionId(), cause.getCascadedFrom(), "the cascade source must be recorded");
        // the parent's downward cascade marks the failing nested back (historical semantics):
        // the nested's own record names the parent as the cascade source
        assertTrue(nested.isRollbackOnly(), "the parent's downward cascade marks the nested as well");
        assertEquals(parent.getTransactionId(), nested.getRollbackOnlyCause().getCascadedFrom());

        ((XdStorageTransaction) parent).waitForFinish();
    }

    // =========================================================================
    // Site #4: the downward cascade of an isolated self-and-children mark
    // =========================================================================

    @Test
    public void explicitVetoCascadesDown_ChildCauseNamesParent() {
        final XdStorageTransaction parent = beginTx(15000L);
        final IXdStorageTransaction nested = storage.beginTransaction(parent, 5000L);

        ((XdStorageTransaction) parent).markSelfAndChildrenRollbackOnly("explicit veto by the application", null);

        assertTrue(nested.isRollbackOnly(), "the nested tx must follow the downward cascade");
        final XdStorageRollbackOnlyCause nestedCause = nested.getRollbackOnlyCause();
        assertEquals("explicit veto by the application", nestedCause.getSite());
        assertEquals(parent.getTransactionId(), nestedCause.getCascadedFrom());

        ((XdStorageTransaction) parent).waitForFinish();
    }

    // =========================================================================
    // Sites #3/#4: the parent force-sweeps of abandoned internal transactions
    // =========================================================================

    @Test
    public void parentForceSweepsSameThreadAbandonedInternal_InternalCauseNamesSweep() {
        final XdStorageTransaction parent = beginTx(15000L);
        final IXdStorageTransaction internal = storage.beginTransaction(parent, 5000L);
        // abandoned on purpose: the same thread never commits/rolls it back

        parent.commit(); // skipOrWaitForInternalTransactions force-sweeps, then the parent commits cleanly

        assertTrue(internal.isRollbackOnly(), "the abandoned internal tx must be swept");
        final XdStorageRollbackOnlyCause cause = internal.getRollbackOnlyCause();
        assertNotNull(cause, "the sweep mark must record its cause");
        assertTrue(cause.getSite().startsWith("parent force-sweep of same-thread abandoned internal tx"),
                "the sweep site must be named: " + cause.getSite());
        assertEquals(parent.getTransactionId(), cause.getCascadedFrom());
    }

    @Test
    public void parentForceSweepsExpiredInternal_ParentPoisonedWithCascadeSite() throws Exception {
        final XdStorageTransaction parent = beginTx(15000L);
        // the internal must be bound to a DIFFERENT thread: the same-thread branch sweeps it
        // isolated (R-41, no parent poisoning); the cross-thread expiry cascade poisons (R-40)
        final java.util.concurrent.atomic.AtomicReference<IXdStorageTransaction> internalRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Thread worker = new Thread(() -> internalRef.set(storage.beginTransaction(parent, 50L)));
        worker.start();
        worker.join(5000L);
        final IXdStorageTransaction internal = internalRef.get();
        assertNotNull(internal, "the worker must have begun the internal tx");
        Thread.sleep(900); // expire past the R-40 grace window (timeout + 500 ms)

        // The cascade poisons the parent DURING its commit; the engine's mid-commit
        // failure path compensates and rolls back silently (historical semantics) —
        // the poisoning is asserted on the state and the captured cause.
        parent.commit();

        assertTrue(parent.isRollbackOnly(), "the ACID cascade must poison the parent");
        final XdStorageRollbackOnlyCause cause = parent.getRollbackOnlyCause();
        assertTrue(cause.getSite().startsWith("parent force-sweep of expired internal tx"),
                "the sweep site must be named: " + cause.getSite());
        assertTrue(cause.getSite().contains("age 9"), "the sweep site must carry the expiry evidence: " + cause.getSite());
        assertEquals(internal.getTransactionId(), cause.getCascadedFrom());

        ((XdStorageTransaction) parent).waitForFinish();
    }

    // =========================================================================
    // Site #1: the generated unmodifiable wrapper's setter (behavioral + source)
    // =========================================================================

    @Test
    public void unmodifiableSetterMark_NamesSetterAndClass() {
        final XdStorageTransaction tx = beginTx(15000L);
        // a TOP-LEVEL entity: nested fixture classes cannot be runtime-codegen'd into wrappers
        final io.github.xdstorage.entities.XdPlanet entity = new io.github.xdstorage.entities.XdPlanet();
        entity.setId(1L);
        entity.setName("before");

        final io.github.xdstorage.entities.XdPlanet view =
                XdStorageObjectUtils.wrapAsUnmodifiableObject(entity, storage, tx);
        assertNotNull(view, "the unmodifiable wrapper must compile for a top-level entity");
        assertThrows(XdStorageRuntimeException.class, () -> view.setName("after"),
                "the unmodifiable view must refuse the setter");

        assertTrue(tx.isRollbackOnly(), "the wrapper setter must mark the transaction");
        final XdStorageRollbackOnlyCause cause = tx.getRollbackOnlyCause();
        assertTrue(cause.getSite().contains("setter 'setName'") && cause.getSite().contains("XdPlanet"),
                "the site must name the setter and the entity class: " + cause.getSite());
    }

    @Test
    public void generatedWrapperSource_ContainsSiteDescriptors() {
        final Map<String, String> unmodifiableCode = new HashMap<>();
        new XdStorageUnmodifiableWrapperClassCodeGenerator().generate("xdstorage.rbcause.gentest", RbEntity.class, unmodifiableCode);
        final String unmodifiableSource = String.join("\n", unmodifiableCode.values());
        assertTrue(unmodifiableSource.contains("markRollbackOnly(\"modification of viewable object: setter 'setName' on RbEntity\", null)"),
                "the generated setter must emit the site descriptor");

        final Map<String, String> simpleCode = new HashMap<>();
        new XdStorageSimpleWrapperClassCodeGenerator().generate("xdstorage.rbcause.gentest",
                io.github.xdstorage.entities.XdStarSystem.class, simpleCode);
        final String simpleSource = String.join("\n", simpleCode.values());
        assertTrue(simpleSource.contains("markRollbackOnly(\"lazy-load of field '"),
                "the generated lazy-load getter must emit the site descriptor with the field name");
    }

    // =========================================================================
    // Site #2: the search-read failure marks with the indexed class named
    // =========================================================================

    @Test
    public void searchReadFailure_MarksWithNamedSite() throws Exception {
        // Save through one storage generation...
        final IXdStorageTransaction saveTx = storage.beginTransaction(15000L);
        for (int i = 0; i < 3; ++i) {
            final RbEntity e = new RbEntity();
            e.setName("entity-" + i);
            storage.save(e, saveTx);
        }
        saveTx.commit();
        storage.shutdown();

        // ...corrupt EVERY entity fragment on disk (the layout is per-object files)...
        final List<Path> fragments = findFragmentFiles();
        assertFalse(fragments.isEmpty(), "the entity fragment files must exist under the storage dir");
        for (final Path fragment : fragments) {
            Files.write(fragment, "objects:\n\t- object:\n\t\tclass: 'not a parseable graph'".getBytes(StandardCharsets.UTF_8));
        }

        // ...and reopen: the search-read materialization fails and must mark with a named site
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 200);
        final XdStorageTransaction searchTx = beginTx(15000L);
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "entity-1"), null);
        // the corrupt fragment fails the search-read materialization (any outcome —
        // throw or partial result — must have marked the transaction with a named site)
        try {
            storage.load(RbEntity.class, "rb_idx", query, searchTx);
        } catch (final Exception expectedSearchFailure) {
            // the loud path — the cause is captured below
        }
        assertTrue(searchTx.isRollbackOnly(), "the search-read failure must mark the transaction");
        final XdStorageRollbackOnlyCause cause = searchTx.getRollbackOnlyCause();
        assertTrue(cause.getSite().startsWith("search-read failure of RbEntity"),
                "the site must name the indexed class: " + cause.getSite());
        assertNotNull(cause.getCause(), "the search failure's root exception must be captured");
    }

    private List<Path> findFragmentFiles() throws Exception {
        try (Stream<Path> walk = Files.walk(new File(STORAGE_DIR).toPath())) {
            final List<Path> found = new ArrayList<>();
            walk.filter(p -> {
                final String path = p.toString();
                return path.endsWith(".yaml") && path.contains("RbEntity")
                        && !path.contains("XdStorageBTree") && !path.endsWith("references.yaml")
                        && !path.endsWith("index.yaml");
            }).forEach(found::add);
            return found;
        }
    }

    // =========================================================================
    // Observability: ONE warn per transaction at the first mark
    // =========================================================================

    @Test
    public void warnLoggedOncePerTransaction_RepeatMarksNotLogged() {
        final LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();
        final ListCaptureAppender capture = new ListCaptureAppender();
        capture.start();
        config.getLoggerConfig("io.github.xdstorage.transaction.XdStorageTransaction")
                .addAppender(capture, Level.WARN, null);
        ctx.updateLoggers();
        try {
            final XdStorageTransaction tx = beginTx(15000L);
            tx.markRollbackOnly("first site", new RuntimeException("boom"));
            tx.markRollbackOnly("second site", null);
            tx.markRollbackOnly("third site", null);

            long markWarns = 0;
            for (final LogEvent event : capture.events) {
                if (event.getMessage() != null
                        && String.valueOf(event.getMessage().getFormattedMessage()).contains("marked as rollback only by")) {
                    ++markWarns;
                }
            }
            assertEquals(1, markWarns, "exactly ONE warn at the first mark — repeat marks stay in the counter");
            assertEquals(3, tx.getRollbackOnlyCause().getRepeatMarks());
        } finally {
            config.getLoggerConfig("io.github.xdstorage.transaction.XdStorageTransaction")
                    .removeAppender("rbcause-capture");
            ctx.updateLoggers();
            capture.stop();
        }
    }

    /** Minimal in-test capture appender (log4j2's ListAppender moved to log4j-core-test). */
    private static final class ListCaptureAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private ListCaptureAppender() {
            super("rbcause-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            synchronized (events) {
                events.add(event.toImmutable());
            }
        }
    }
}
