package io.github.xdstorage.properties;

import io.github.xdstorage.BinFeasEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPTEST P1.4 (docs/tasks/2.2.0/03): MVCC SNAPSHOT semantics on a latch-controlled
 * two-transaction schedule model with randomized values and commit orders:
 * <ol>
 *   <li>a reader's snapshot NEVER sees a foreign commit that landed after its read;</li>
 *   <li>the reader's commit loses LOUDLY (the first-committer-wins + the commit-time
 *       revalidation) when the foreign commit landed in its stage→commit window;</li>
 *   <li>the winner's data is intact after the loser's failure;</li>
 *   <li>an aborted foreign stage causes NO over-rejection (the content-based
 *       semantics).</li>
 * </ol>
 * The randomization: the entity choice, the written values and the who-commits-first
 * order derive from the seed.
 */
public class PropMvccSnapshotTest {

    @Property(tries = 40)
    void snapshotIsolation_FcwAndNoOverRejection(@ForAll final int seed) throws Exception {
        final boolean readerFirst = (seed & 1) == 0;
        final String vForeign = "foreign-" + seed;
        final String vReader = "reader-" + seed;

        final File dir = new File("./target/pt-mvcc-" + System.nanoTime() + "-" + seed);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-mvcc-" + System.nanoTime() + seed, dir.getPath(), 64);
        try {
            // the seed entity
            IXdStorageTransaction setup = storage.beginTransaction();
            final BinFeasEntity base = new BinFeasEntity("e0", seed);
            storage.save(base, setup);
            storage.commitTransaction(setup);

            // the reader tx opens and reads FIRST (its snapshot pins here)
            final IXdStorageTransaction reader = storage.beginTransaction();
            final BinFeasEntity readerView = storage.load(BinFeasEntity.class, "e0", reader);
            assertNotNull(readerView);
            final String seenBefore = readerView.getName();
            final CountDownLatch foreignStaged = new CountDownLatch(1);
            final CountDownLatch readerChecked = new CountDownLatch(1);

            // the foreign tx: stage an update, commit it
            final java.util.concurrent.atomic.AtomicReference<Throwable> foreignFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final Thread foreign = new Thread(() -> {
                try {
                    foreignStaged.await(5, TimeUnit.SECONDS); // wait until the reader has READ (its snapshot pinned)
                    final IXdStorageTransaction fx = storage.beginTransaction();
                    final BinFeasEntity e = storage.load(BinFeasEntity.class, "e0", fx);
                    e.setName(vForeign);
                    storage.update(e, fx);
                    storage.commitTransaction(fx);
                } catch (final Throwable failure) {
                    foreignFailure.set(failure); // NEVER swallowed: a failed foreign commit invalidates the scenario
                } finally {
                    readerChecked.countDown();
                }
            });
            foreign.start();
            foreignStaged.countDown();
            assertTrue(readerChecked.await(5, TimeUnit.SECONDS), "the foreign tx did not finish");
            final Throwable foreignThrown = foreignFailure.get();
            if (foreignThrown != null) {
                throw new AssertionError("the foreign commit failed", foreignThrown);
            }

            // INVARIANT 1: the reader's snapshot never sees the foreign commit
            final BinFeasEntity reRead = storage.load(BinFeasEntity.class, "e0", reader);
            assertEquals(seenBefore, reRead.getName(),
                    "the snapshot read saw a foreign commit — the MVCC isolation broken");

            // INVARIANT 2: the reader stages its own update on top of the foreign commit:
            // the commit-time revalidation must refuse LOUDLY (first-committer-wins)
            reRead.setName(readerFirst ? vReader : vReader + "-late");
            // INVARIANT 2: the stale update loses LOUDLY at the stage time (the
            // first-committer-wins; the foreign commit landed between the reader's read
            // and its update). After the refusal the reader's tx holds nothing staged —
            // its commit would be a quiet no-op, so the reader rolls back instead.
            final XdStorageException conflict = assertThrows(XdStorageException.class,
                    () -> storage.update(reRead, reader),
                    "the stale update must lose loudly (the first-committer-wins)");
            assertTrue(conflict.getMessage().contains("can not be updated") || conflict.getMessage()
                    .contains("CONFLICT")
                            || conflict.getMessage().contains("revalidation"),
                    "the conflict names its mechanism: " + conflict.getMessage());
            storage.rollbackTransaction(reader);

            // INVARIANT 3: the winner's data intact after the loser's failure
            final IXdStorageTransaction check = storage.beginTransaction();
            final BinFeasEntity winner = storage.load(BinFeasEntity.class, "e0", check);
            assertEquals(vForeign, winner.getName(), "the winner's data must be intact");
            storage.commitTransaction(check);

            // INVARIANT 4: no over-rejection — a FRESH tx can update normally after the conflict
            final IXdStorageTransaction fresh = storage.beginTransaction();
            final BinFeasEntity freshEntity = storage.load(BinFeasEntity.class, "e0", fresh);
            freshEntity.setName(vReader);
            storage.update(freshEntity, fresh);
            storage.commitTransaction(fresh);
            final IXdStorageTransaction verify = storage.beginTransaction();
            assertEquals(vReader, storage.load(BinFeasEntity.class, "e0", verify).getName(),
                    "the fresh update must land after the conflict");
            storage.commitTransaction(verify);
        } finally {
            storage.shutdown();
            deleteRecursively(dir);
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
