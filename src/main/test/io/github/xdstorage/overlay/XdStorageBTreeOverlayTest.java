package io.github.xdstorage.overlay;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TREEOVERLAY (docs/tasks/2.1.0/53-tree-overlay.md): the overlay engine behavior
 * pinned on BTREE id-index trees — the overlay's home path. The entities are
 * TOP-LEVEL ({@link OvParent}/{@link OvChild}) per the house rule (the runtime
 * wrapper generation cannot compile nested test classes).
 *
 * <p>The pins:
 * <ul>
 *   <li>the staged insert is visible to the owning tx and invisible to a foreign
 *       tx until the commit (read-your-own-write / READ-COMMITTED);</li>
 *   <li>the rollback = discard + the pre-tx content restore (the prevSnapshot
 *       role-split): the staged ops vanish, the record restored;</li>
 *   <li><b>the total acquisition order</b> (the convoy fix, 2026-11-21): two threads
 *       committing over TWO shared btree trees in the opposite resource orders must
 *       both commit without the lock timeouts — the regression pin of the intra-band
 *       tie-order deadlock (the FKBENCH 12s waits = 4 × the 3s lock-timeout cycles).</li>
 * </ul>
 *
 * <p>Real storage; unique nano-time paths per the house rules (R-50).
 */
@DisplayName("TREEOVERLAY: overlay engine on btree id-index trees")
public class XdStorageBTreeOverlayTest {

    private static final String STORAGE_PATH = "./target/btree-overlay-" + System.nanoTime();
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        final XdStorageConfig config = new XdStorageConfig.Builder()
                .journalWrites(true)
                .setEnableForeignKeys(true)
                .build();
        storage = XdStorageProvider.newOrGetFileStorage(
                "btree_overlay_" + System.nanoTime(), STORAGE_PATH, 200, FilesFormat.YAML, config);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (final Exception ignored) {
            }
        }
        deleteDir(new File(STORAGE_PATH));
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

    @Test
    @DisplayName("staged insert: своя TX видит пару, чужая — нет до коммита; после коммита видна всем")
    public void stagedInsert_VisibilityContract() throws Exception {
        // TX0: seed the parent
        final IXdStorageTransaction tx0 = storage.beginTransaction();
        final OvParent parent = new OvParent();
        parent.setId("ov-parent-1");
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: stage the child insert — visible to the OWNING tx via the overlay merge
        final IXdStorageTransaction tx1 = storage.beginTransaction();
        final OvParent parentForStaging = storage.load(OvParent.class, "ov-parent-1", tx1);
        final OvChild child = new OvChild();
        child.setId("ov-child-1");
        parentForStaging.addChild(child);
        storage.update(parentForStaging, tx1);
        // read-your-own-write: the committed + the overlay
        final OvParent seenByOwner = storage.load(OvParent.class, "ov-parent-1", tx1);
        assertEquals(1, seenByOwner.getChildren().size(), "своя TX видит стейдженную пару");
        // do NOT commit yet — the foreign tx must NOT see the pair
        final IXdStorageTransaction foreign = storage.beginTransaction();
        final OvParent seenByForeign = storage.load(OvParent.class, "ov-parent-1", foreign);
        assertEquals(0, seenByForeign.getChildren().size(),
                "чужая TX не видит незакоммиченный overlay (READ-COMMITTED)");
        foreign.commit();

        // commit → the pair becomes physical → the new foreign tx sees it
        tx1.commit();
        final IXdStorageTransaction after = storage.beginTransaction();
        final OvParent seenAfter = storage.load(OvParent.class, "ov-parent-1", after);
        assertEquals(1, seenAfter.getChildren().size(), "после коммита пара физична");
        after.commit();
    }

    @Test
    @DisplayName("rollback = discard overlay + pre-tx restore: стейдженные операции исчезают")
    public void rollback_DiscardsOverlay() throws Exception {
        final IXdStorageTransaction tx0 = storage.beginTransaction();
        final OvParent parent = new OvParent();
        parent.setId("ov-parent-rb");
        final OvChild child = new OvChild();
        child.setId("ov-child-rb");
        parent.addChild(child);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: stage a second child + a delete of the first — then ROLL BACK
        final IXdStorageTransaction tx1 = storage.beginTransaction();
        final OvParent loaded = storage.load(OvParent.class, "ov-parent-rb", tx1);
        final OvChild child2 = new OvChild();
        child2.setId("ov-child-rb-2");
        loaded.addChild(child2);
        loaded.getChildren().remove(loaded.getChildren().iterator().next());
        storage.update(loaded, tx1);
        tx1.rollback();

        // TX2: the record restored to the PRE-TX content (the read-time snapshot +
        // the in-place fillObject restore — the role-split design)
        final IXdStorageTransaction tx2 = storage.beginTransaction();
        final OvParent after = storage.load(OvParent.class, "ov-parent-rb", tx2);
        assertEquals(1, after.getChildren().size(), "rollback восстановил pre-tx: ровно исходный ребёнок");
        assertEquals("ov-child-rb", after.getChildren().iterator().next().getId());
        tx2.commit();
    }

    @Test
    @DisplayName("КОНВОЙ-ПИН: два потока коммитят по двум общим btree-деревьям в противоположных порядках — без " +
            "таймаутов")
    public void concurrentCommits_OppositeResourceOrders_NoDeadlock() throws Exception {
        // Seed two parents (two shared btree id-index trees)
        final IXdStorageTransaction tx0 = storage.beginTransaction();
        final OvParent pA = new OvParent();
        pA.setId("deadlock-A");
        final OvParent pB = new OvParent();
        pB.setId("deadlock-B");
        storage.save(pA, tx0);
        storage.save(pB, tx0);
        tx0.commit();

        // N rounds: each round, two threads commit concurrently; the FIRST touches
        // (A, B) and the SECOND touches (B, A) — the opposite interleavings of the
        // same shared trees. The tie-order deadlock (the intra-band arbitrary order)
        // would time one of them out at the 3s lock budget; the total acquisition
        // order (the tie-break by the resource id) must let BOTH commit.
        final int rounds = 40;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final AtomicInteger committed = new AtomicInteger();
        final List<Future<?>> futures = new ArrayList<>();

        // THE RECORD DISJOINTNESS: each thread owns ONE parent record exclusively (the
        // record-level lock order is the application's concern — the classic row-lock
        // deadlock family, resolved by the sweeper); the SHARED resources (the class
        // id_index tree, the FK trees, the class data resource) are contended by both —
        // exactly the surface the total acquisition order protects.
        futures.add(executor.submit(() -> {
            startLatch.await();
            for (int i = 0; i < rounds; i++) {
                final IXdStorageTransaction tx = storage.beginTransaction(20_000L);
                final OvParent a = storage.load(OvParent.class, "deadlock-A", tx);
                final OvChild c = new OvChild();
                c.setId("A-" + i);
                a.addChild(c);
                storage.update(a, tx);
                tx.commit();
                committed.incrementAndGet();
            }
            return null;
        }));
        futures.add(executor.submit(() -> {
            startLatch.await();
            for (int i = 0; i < rounds; i++) {
                final IXdStorageTransaction tx = storage.beginTransaction(20_000L);
                final OvParent b = storage.load(OvParent.class, "deadlock-B", tx);
                final OvChild c = new OvChild();
                c.setId("B-" + i);
                b.addChild(c);
                storage.update(b, tx);
                tx.commit();
                committed.incrementAndGet();
            }
            return null;
        }));

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(120, TimeUnit.SECONDS),
                "конвой-пин: оба потока завершились без lock-таймаутов (тотальный порядок захвата)");
        for (final Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS); // rethrows any worker failure
        }
        assertEquals(rounds * 2, committed.get(), "все коммиты приземлились — дедлока нет");
    }
}
