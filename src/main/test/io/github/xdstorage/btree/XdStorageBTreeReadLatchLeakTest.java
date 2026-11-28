package io.github.xdstorage.btree;

import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * LKLEAK (docs/tasks/2.2.0/38, R-35 resolved): the tree read latch must NOT leak when a
 * walk/descent throws — the tree must stay writable (from ANOTHER thread; the same thread
 * would pass via the lone-reader upgrade and mask the leak) after the caught exception.
 *
 * <p>THE HONEST HISTORY (2026-11-28 audit): the intake's "throw before the coupling point
 * leaks the latch" was verified against HEAD with deterministic probes (all three walks:
 * the parity barrier on the FIRST leaf — previous = tree; on the SECOND leaf — previous =
 * node; the restore walk) — <b>no leak is reproducible at HEAD</b>: the piecemeal guards
 * (the hop-1 unconditional {@code finally} in find, the transfer-discipline outer
 * {@code finally} in the walks) already cover every unwind. Red-before is therefore NOT
 * producible for the corrupt-node path — the pins lock the OBSERVABLE contract, and the
 * entry-level release-on-unwind delta guard (XdStorageReadWriteLock.readHoldCount) makes
 * the invariant STRUCTURAL: a future walk reshuffle cannot silently reopen the hole.
 * The stale R-35 "open" comment in findPhysical was replaced by the guard.</p>
 */
@DisplayName("LKLEAK: дерево остаётся записываемым после исключения в обходе (утечки read-лоча нет)")
public class XdStorageBTreeReadLatchLeakTest extends AbstractBTreeIntegrationTest {

    private void corruptFirstLeaf() throws Exception {
        for (int i = 0; i < 3; ++i) {
            btree.insert("k" + i, "v" + i, storageFacade, transaction);
        }
        final XdStorageBTreeNode firstLeaf = btree.getFirstLeaf();
        assertNotNull(firstLeaf);
        firstLeaf.getObjects().remove(0);
        assertTrue(firstLeaf.getKeys().size() != firstLeaf.getObjects().size(), "corruption in place");
    }

    /** The tree must stay writable from a SECOND thread (the leaked latch is per-thread — the same thread passes via lone-reader upgrade). */
    private void assertWritableFromAnotherThread(final String context) throws InterruptedException {
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Exception> writerResult = new AtomicReference<>();
        final Thread writer = new Thread(() -> {
            try {
                go.await(2, TimeUnit.SECONDS);
                final XdStorageTransaction tx = new XdStorageTransaction(null, "THREAD_WRITER", null, 2000L, "TX_WRITER") {
                    @Override public void startCriticalSection(boolean r) { }
                    @Override public void finishCriticalSection() { }
                };
                btree.insert("k9", "v9", storageFacade, tx);
            } catch (final Exception e) {
                writerResult.set(e);
            }
        });
        writer.start();
        go.countDown();
        writer.join(8000);
        if (writer.isAlive()) {
            fail(context + ": the writer thread is STUCK (the tree read latch is leaked)");
        }
        if (writerResult.get() != null) {
            fail(context + ": the tree is NOT writable after the caught walk exception: " + writerResult.get().getMessage());
        }
    }

    @Test
    @DisplayName("LKLEAK 1: parity-throw на ПЕРВОМ листе (previous = дерево) — readRange — дерево записываемо")
    public void testFirstLeafParityThrowDoesNotLeakTreeLatch() throws Exception {
        corruptFirstLeaf();
        final AtomicReference<Exception> walkThrew = new AtomicReference<>();
        try {
            btree.readRange("a", true, "z", true, storageFacade, transaction, (key, value) -> { });
        } catch (final Exception expected) {
            walkThrew.set(expected);
        }
        assertNotNull(walkThrew.get(), "the parity barrier must have thrown (the corrupt-node path)");
        assertWritableFromAnotherThread("first-leaf parity throw");
    }

    @Test
    @DisplayName("LKLEAK 2: parity-throw на ВТОРОМ листе (итерация k>1) — readRange — дерево записываемо")
    public void testSecondLeafParityThrowDoesNotLeakTreeLatch() throws Exception {
        for (int i = 0; i < 30; ++i) {
            btree.insert("k" + i, "v" + i, storageFacade, transaction);
        }
        final XdStorageBTreeNode second = btree.getFirstLeaf().getNextTreeNodeOnThisLevel();
        assertNotNull(second, "the tree must have a second leaf (30 pairs, t=5)");
        second.getObjects().remove(0);

        final AtomicReference<Exception> walkThrew = new AtomicReference<>();
        try {
            btree.readRange("a", true, "z", true, storageFacade, transaction, (key, value) -> { });
        } catch (final Exception expected) {
            walkThrew.set(expected);
        }
        assertNotNull(walkThrew.get(), "the parity barrier must have thrown");
        assertWritableFromAnotherThread("second-leaf parity throw");
    }

    @Test
    @DisplayName("LKLEAK 3: parity-throw под restoreSearchKeyTypes (walk реопена) — дерево записываемо")
    public void testRestoreWalkThrowDoesNotLeakTreeLatch() throws Exception {
        corruptFirstLeaf();
        final AtomicReference<Exception> walkThrew = new AtomicReference<>();
        try {
            btree.restoreSearchKeyTypes(o -> o, storageFacade, transaction);
        } catch (final Exception expected) {
            walkThrew.set(expected);
        }
        assertNotNull(walkThrew.get(), "the parity barrier must have thrown");
        assertWritableFromAnotherThread("restore-walk parity throw");
    }

    @Test
    @DisplayName("LKLEAK 4: viewer бросает ПОСЛЕ сцепления (итерация 1) — дерево записываемо")
    public void testViewerThrowAfterCouplingDoesNotLeakTreeLatch() throws Exception {
        for (int i = 0; i < 3; ++i) {
            btree.insert("k" + i, "v" + i, storageFacade, transaction);
        }
        final AtomicReference<Exception> walkThrew = new AtomicReference<>();
        try {
            btree.readRange("a", true, "z", true, storageFacade, transaction, (key, value) -> {
                throw new IllegalStateException("the viewer exploded mid-walk");
            });
        } catch (final Exception expected) {
            walkThrew.set(expected);
        }
        assertNotNull(walkThrew.get(), "the viewer exception must propagate");
        assertWritableFromAnotherThread("viewer throw after the coupling");
    }
}
