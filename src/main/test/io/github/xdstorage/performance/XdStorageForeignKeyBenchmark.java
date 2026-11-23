package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.performance.entities.FkBenchChild;
import io.github.xdstorage.performance.entities.FkBenchParent;
import io.github.xdstorage.performance.entities.FkPlainChild;
import io.github.xdstorage.performance.entities.FkPlainParent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FKBENCH (docs/tasks/2.1.0/50): the FK-cost A/B benchmark — the canonical family
 * (task 13's conditions protocol: run via scripts/run-benchmarks.sh, which owns the
 * quiet-load gate, the WARN-console logging config, the JSON results + the run
 * manifest). Surefire-invisible (not a *Test class); YAML format; the disclosed
 * config flags (the fragment size 100, the journal on).
 *
 * <p>The arms (the A/B isolates the FK-ENGINE cost from the annotation/cascade cost):
 * <ul>
 *   <li>{@code fkEnabled=true} — the annotated entity graph + the FK engine ON (the
 *       default): every save/update/delete of a related object maintains the front +
 *       reverse relation B+trees;</li>
 *   <li>{@code fkEnabled=false} — the SAME annotated graph with the FK engine OFF:
 *       the cascade still runs, no FK trees;</li>
 *   <li>the {@code plain} benchmarks — the un-annotated twin graph (no AutoSave, no
 *       ParentObject), FK OFF: the pure storage cost of the same object count. The
 *       decomposition: fk_on − fk_off = the FK-engine cost; fk_off − plain = the
 *       cascade cost.</li>
 * </ul>
 *
 * <p>The shapes (the task's scope):
 * <ul>
 *   <li>{@code cascadeSave} — one parent with N children per op (the AutoSave
 *       cascade → 2×N FK B+tree entries at fk on);</li>
 *   <li>{@code childDelete} — delete one preloaded child (the FK integrity check +
 *       the entry removal);</li>
 *   <li>{@code parentUpdate} — load → touch a payload field → update (the FK
 *       validation walks the N relations);</li>
 *   <li>{@code fkLookup} — load(child) → getParent(): the reverse traversal READ
 *       path (the pure reverse-tree probe stays inside childDelete's integrity
 *       check — disclosed in the report);</li>
 *   <li>the {@code plain} twins of the first three.</li>
 * </ul>
 *
 * <p>The axes: childrenPerParent = 1|10 (the relation density), parents = 1000|10000
 * (the dataset size). The relation count (the FK B+tree depth driver) = parents ×
 * childrenPerParent ∈ {1k, 10k, 100k}: the (1000,10) vs (10000,1) pair holds the
 * relations CONSTANT at 10k while the dataset grows 10× — the density-vs-dataset
 * decomposition. The 100-children × 10000-parents combo (=1M relations) is excluded
 * from the matrix as runtime-prohibitive (the preload alone ≈ minutes per trial) —
 * disclosed in the task file; the density trend is established by 1→10.
 *
 * <p>The per-trial setup PRELOADS the dataset (the batched saves, 200/tx) — the
 * preload cost is NOT part of the measured windows (the JMH measurement starts
 * after the setup); the delete ops cycle over the preloaded pool (disclosed: a
 * trial may drain a bounded fraction of the pool — the pools ≥ the trial's op
 * budget at these settings).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Threads(1)
public class XdStorageForeignKeyBenchmark {

    private static final long TX_TIMEOUT = 30000L;
    private static final int PRELOAD_BATCH = 200;

    // =========================================================================
    // The FK-family state: the annotated graph, the FK engine on/off
    // =========================================================================
    @State(Scope.Benchmark)
    public static class FkState {

        @Param({"true", "false"})
        public boolean fkEnabled;

        @Param({"1", "10"})
        public int childrenPerParent;

        @Param({"1000", "10000"})
        public int parents;

        IXdFileStorage storage;
        File folder;
        long nextParentId;
        /** The preloaded child ids — the delete/lookup cursor cycles over this pool. */
        List<String> childIds;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            this.folder = new File("./target/fkbench-" + System.nanoTime());
            deleteRecursively(this.folder);
            final XdStorageConfig config = new XdStorageConfig.Builder()
                    .journalWrites(true)
                    .setEnableForeignKeys(this.fkEnabled)
                    .build();
            this.storage = XdStorageProvider.newOrGetFileStorage("fkbench-" + System.nanoTime(), this.folder.getPath(),
                    100, config);

            // the preloaded dataset: parents × childrenPerParent relations
            this.childIds = new ArrayList<>();
            for (int p = 0; p < this.parents; p++) {
                final String parentId = "p" + p;
                final IXdStorageTransaction tx = this.storage.beginTransaction(TX_TIMEOUT);
                final FkBenchParent parent = new FkBenchParent();
                parent.setId(parentId);
                parent.setLabel("parent-" + p);
                for (int c = 0; c < this.childrenPerParent; c++) {
                    final FkBenchChild child = new FkBenchChild();
                    child.setId(parentId + "-c" + c);
                    child.setLabel("child-" + p + "-" + c);
                    child.setParent(parent);
                    this.storage.save(child, tx);
                    parent.addChild(child);
                }
                this.storage.save(parent, tx);
                this.storage.commitTransaction(tx);
                for (int c = 0; c < this.childrenPerParent; c++) {
                    this.childIds.add(parentId + "-c" + c);
                }
            }
            this.nextParentId = this.parents;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                this.storage.shutdown();
            } catch (final Exception ignored) {
            }
            deleteRecursively(this.folder);
        }
    }

    // =========================================================================
    // The control-family state: the un-annotated twin graph, FK off
    // =========================================================================
    @State(Scope.Benchmark)
    public static class PlainState {

        @Param({"1", "10"})
        public int childrenPerParent;

        @Param({"1000", "10000"})
        public int parents;

        IXdFileStorage storage;
        File folder;
        long nextParentId;
        List<String> childIds;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            this.folder = new File("./target/fkbench-plain-" + System.nanoTime());
            deleteRecursively(this.folder);
            final XdStorageConfig config = new XdStorageConfig.Builder()
                    .journalWrites(true)
                    .setEnableForeignKeys(false)
                    .build();
            this.storage = XdStorageProvider.newOrGetFileStorage("fkbench-plain-" + System.nanoTime(), this.folder
                    .getPath(), 100, config);

            this.childIds = new ArrayList<>();
            for (int p = 0; p < this.parents; p++) {
                final String parentId = "p" + p;
                final IXdStorageTransaction tx = this.storage.beginTransaction(TX_TIMEOUT);
                final FkPlainParent parent = new FkPlainParent();
                parent.setId(parentId);
                parent.setLabel("parent-" + p);
                for (int c = 0; c < this.childrenPerParent; c++) {
                    final FkPlainChild child = new FkPlainChild();
                    child.setId(parentId + "-c" + c);
                    child.setLabel("child-" + p + "-" + c);
                    this.storage.save(child, tx);
                    parent.addChild(child);
                }
                this.storage.save(parent, tx);
                this.storage.commitTransaction(tx);
                for (int c = 0; c < this.childrenPerParent; c++) {
                    this.childIds.add(parentId + "-c" + c);
                }
            }
            this.nextParentId = this.parents;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                this.storage.shutdown();
            } catch (final Exception ignored) {
            }
            deleteRecursively(this.folder);
        }
    }

    // =========================================================================
    // cascadeSave: one parent + N children per op (the AutoSave cascade / the explicit saves)
    // =========================================================================

    @Benchmark
    public String cascadeSave(final FkState state) throws Exception {
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final long p = state.nextParentId++;
            final FkBenchParent parent = new FkBenchParent();
            parent.setId("p" + p);
            parent.setLabel("parent-" + p);
            for (int c = 0; c < state.childrenPerParent; c++) {
                final FkBenchChild child = new FkBenchChild();
                child.setId("p" + p + "-c" + c);
                child.setLabel("child-" + p + "-" + c);
                child.setParent(parent);
                parent.addChild(child);
            }
            state.storage.save(parent, tx); // the AutoSave cascade persists the children
            state.storage.commitTransaction(tx);
            return parent.getId();
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public String plainCascadeSave(final PlainState state) throws Exception {
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final long p = state.nextParentId++;
            final FkPlainParent parent = new FkPlainParent();
            parent.setId("p" + p);
            parent.setLabel("parent-" + p);
            // no AutoSave: the same durable object count via the explicit saves
            for (int c = 0; c < state.childrenPerParent; c++) {
                final FkPlainChild child = new FkPlainChild();
                child.setId("p" + p + "-c" + c);
                child.setLabel("child-" + p + "-" + c);
                state.storage.save(child, tx);
                parent.addChild(child);
            }
            state.storage.save(parent, tx);
            state.storage.commitTransaction(tx);
            return parent.getId();
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    // =========================================================================
    // childDelete: the CONTRACT-CORRECT FK removal cycle (CANON 2026-11-20: a
    // delete-child-alone while the parent's collection still references it is a
    // designed VETO). The cycle: sync the parent's collection → delete the child →
    // commit; then the RESTORE cycle (re-add + update → the AutoSave cascade) keeps
    // the pool stable across the trial. BOTH arms run the same cycle, so the A/B
    // delta isolates the FK cost (the veto checks + the relation trees).
    // =========================================================================

    @Benchmark
    public String childDelete(final FkState state, final UpdateCursor cursor) throws Exception {
        final int idx = (int) (cursor.next++ % state.childIds.size());
        final String childId = state.childIds.get(idx);
        final String parentId = "p" + idx / Math.max(1, state.childrenPerParent);
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final FkBenchParent parent = state.storage.load(FkBenchParent.class, parentId, tx);
            final FkBenchChild child = state.storage.load(FkBenchChild.class, childId, tx);
            assertNotNullQuietly(child);
            final java.util.Iterator<FkBenchChild> it = parent.getChildren().iterator();
            while (it.hasNext()) {
                if (childId.equals(it.next().getId())) {
                    it.remove();
                }
            }
            state.storage.update(parent, tx);
            state.storage.delete(child, tx);
            state.storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
        // the restore cycle: re-add to the collection + update (the AutoSave cascade persists)
        final IXdStorageTransaction restoreTx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final FkBenchParent parent = state.storage.load(FkBenchParent.class, parentId, restoreTx);
            final FkBenchChild restored = new FkBenchChild();
            restored.setId(childId);
            restored.setLabel("child-restored-" + idx);
            restored.setParent(parent);
            parent.getChildren().add(restored);
            state.storage.update(parent, restoreTx);
            state.storage.commitTransaction(restoreTx);
            return childId;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(restoreTx);
            throw e;
        }
    }

    @Benchmark
    public String plainChildDelete(final PlainState state, final UpdateCursor cursor) throws Exception {
        final int idx = (int) (cursor.next++ % state.childIds.size());
        final String childId = state.childIds.get(idx);
        final String parentId = "p" + idx / Math.max(1, state.childrenPerParent);
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final FkPlainParent parent = state.storage.load(FkPlainParent.class, parentId, tx);
            final FkPlainChild child = state.storage.load(FkPlainChild.class, childId, tx);
            assertNotNullQuietly(child);
            final java.util.Iterator<FkPlainChild> it = parent.getChildren().iterator();
            while (it.hasNext()) {
                if (childId.equals(it.next().getId())) {
                    it.remove();
                }
            }
            state.storage.update(parent, tx);
            state.storage.delete(child, tx);
            state.storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
        final IXdStorageTransaction restoreTx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            // the plain arm restores with a NEW id (the unique-id invariant rejects the
            // same-id save here — the base-file unique index has no FK overlay); the pool
            // ENTRY is replaced by the new id so the next cycle loads a live object
            final String restoredId = childId + "-r" + cursor.next;
            final FkPlainChild restored = new FkPlainChild();
            restored.setId(restoredId);
            restored.setLabel("child-restored-" + idx);
            state.storage.save(restored, restoreTx);
            state.childIds.set(idx, restoredId);
            state.storage.commitTransaction(restoreTx);
            return childId;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(restoreTx);
            throw e;
        }
    }

    private static void assertNotNullQuietly(final Object o) {
        if (o == null) {
            throw new IllegalStateException("the pooled entity is missing (the pool invariant broken)");
        }
    }

    // =========================================================================
    // parentUpdate: load → touch a payload field → update (the FK validation over N relations)
    // =========================================================================

    @Benchmark
    public String parentUpdate(final FkState state, final UpdateCursor cursor) throws Exception {
        final String parentId = "p" + (cursor.next++ % state.parents);
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final FkBenchParent parent = state.storage.load(FkBenchParent.class, parentId, tx);
            parent.setLabel("parent-" + parentId + "-" + cursor.next);
            state.storage.update(parent, tx);
            state.storage.commitTransaction(tx);
            return parentId;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public String plainParentUpdate(final PlainState state, final UpdateCursor cursor) throws Exception {
        final String parentId = "p" + (cursor.next++ % state.parents);
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final FkPlainParent parent = state.storage.load(FkPlainParent.class, parentId, tx);
            parent.setLabel("parent-" + parentId + "-" + cursor.next);
            state.storage.update(parent, tx);
            state.storage.commitTransaction(tx);
            return parentId;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    /** The update/lookup cursor (Threads(1) — a plain long is safe). */
    @State(Scope.Thread)
    public static class UpdateCursor {
        long next;
    }

    // =========================================================================
    // fkLookup: load(child) → getParent() — the reverse traversal READ path
    // =========================================================================

    @Benchmark
    public Object fkLookup(final FkState state, final UpdateCursor cursor) throws Exception {
        final String childId = state.childIds.get((int) (cursor.next++ % state.childIds.size()));
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final FkBenchChild child = state.storage.load(FkBenchChild.class, childId, tx);
            final Object parent = child.getParent();
            state.storage.commitTransaction(tx);
            return parent;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Benchmark
    public Object plainLookup(final PlainState state, final UpdateCursor cursor) throws Exception {
        final String childId = state.childIds.get((int) (cursor.next++ % state.childIds.size()));
        final IXdStorageTransaction tx = state.storage.beginTransaction(TX_TIMEOUT);
        try {
            final FkPlainChild child = state.storage.load(FkPlainChild.class, childId, tx);
            final String label = child.getLabel();
            state.storage.commitTransaction(tx);
            return label;
        } catch (final RuntimeException e) {
            state.storage.rollbackTransaction(tx);
            throw e;
        }
    }

    private static void deleteRecursively(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteRecursively(f);
            }
        }
        file.delete();
    }
}
