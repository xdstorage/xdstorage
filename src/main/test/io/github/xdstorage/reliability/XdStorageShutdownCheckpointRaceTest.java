package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The checkpoint that runs OUTSIDE a commit — {@code checkpoint()}, {@code shutdown()} and
 * {@code backupTo(Path)} all fold the journals through {@code checkpointJournalOnShutdown()} —
 * against a commit that is BETWEEN ITS PHASES: its redo is already in the data journal, nothing
 * is published in the cache yet.
 *
 * <p>The fold writes the committed view and deletes the journal. A commit in that window is in
 * neither: a fold that runs there writes a base without it and deletes the redo that held it —
 * a commit that returned success, gone at the next open. The third run of the Windows leg
 * (2026-11-28, {@code XdStorageBackupUnderLoadStressTest}) lost one committed pair of sixteen
 * in a backup copy; the window is as wide as the other resources' phase-1 appends of the same
 * commit.</p>
 *
 * <p><b>How a commit is parked between the phases, with no seam in the engine.</b> The phase-1
 * order is the data resource first and the id index after it, and every journal append runs
 * under the monitor of the journal file's canonical path
 * ({@link XdStorageYamlCommitJournal#lockFor}). A thread that holds the id-index journal's
 * monitor stops the committing thread inside that append: the data redo is on disk, phase 2 has
 * not begun. The test holds the monitor through a separate thread and lets go of it when the
 * engine under test has shown its behaviour.</p>
 */
@DisplayName("SHUTCKPT (2.2.0/51): a commit between its phases is not cut by a checkpoint outside it")
class XdStorageShutdownCheckpointRaceTest {

    private static final String DATA_DIR = "data/" + BenchmarkEntity.class.getSimpleName() + "/"
            + XdStorageYamlCommitJournal.JOURNAL_DIR;
    private static final String INDEX_JOURNAL = "index-journal.yaml";
    private static final long TIMEOUT_MILLIS = 60_000L;
    private static final java.util.concurrent.atomic.AtomicInteger FOLDERS =
            new java.util.concurrent.atomic.AtomicInteger();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("checkpoint() while a commit is between its phases: the commit survives the reopen")
    void checkpointDoesNotCutACommitBetweenItsPhases() throws Exception {
        final Path folder = folder();
        final IXdFileStorage storage = open(folder);
        save(storage, "seed", "before");
        final ParkedCommit parked = parkACommit(storage, "in-flight", folder);
        try {
            storage.checkpoint();
        } finally {
            parked.release();
        }
        parked.awaitCommitted();
        storage.shutdown();

        assertReopenHolds(folder, "seed");
        assertReopenHolds(folder, "in-flight");
    }

    @Test
    @DisplayName("shutdown() lets a commit in flight finish, and its checkpoint folds it")
    void shutdownWaitsForACommitInFlight() throws Exception {
        final Path folder = folder();
        final IXdFileStorage storage = open(folder);
        save(storage, "seed", "before");
        final ParkedCommit parked = parkACommit(storage, "in-flight", folder);
        final Thread closer = new Thread(storage::shutdown, "shutdown-checkpoint-race");
        parked.whileParked(() -> {
            closer.start();
            closer.join(300L);
            assertTrue(closer.isAlive(), "shutdown returned while a commit was still between its phases");
        });
        closer.join(TimeUnit.SECONDS.toMillis(30L));
        assertFalse(closer.isAlive(), "shutdown did not return once the commit had finished");
        parked.awaitCommitted();

        assertEquals(0L, dataJournalBytes(folder), "the commit was folded into the base — a journal is left: "
                + dataJournals(folder));
        assertReopenHolds(folder, "seed");
        assertReopenHolds(folder, "in-flight");
    }

    @Test
    @DisplayName("after shutdown() returned a late commit is refused — through the facade and through the"
            + " transaction's own commit()")
    void aCommitAfterShutdownIsRefused() throws Exception {
        assertLateCommitRefused("through the facade", (storage, late) -> storage.commitTransaction(late));
        assertLateCommitRefused("through the transaction's own commit()", (storage, late) -> late.commit());
    }

    private interface LateCommit {
        void commit(IXdFileStorage storage, IXdStorageTransaction late) throws Exception;
    }

    /** Stages one record, shuts the storage down, then tries to commit it: refused, and nothing on disk. */
    private void assertLateCommitRefused(final String route, final LateCommit lateCommit) throws Exception {
        final Path folder = folder();
        final IXdFileStorage storage = open(folder);
        save(storage, "seed", "before");
        final IXdStorageTransaction late = storage.beginTransaction(TIMEOUT_MILLIS);
        storage.save(new BenchmarkEntity("late", "after"), late);
        storage.shutdown();

        assertThrows(RuntimeException.class, () -> lateCommit.commit(storage, late),
                "a commit after shutdown must be refused " + route);
        assertEquals(0L, dataJournalBytes(folder), "the refused commit left something in the journal " + route);
        assertReopenHolds(folder, "seed");
        final IXdFileStorage check = open(folder);
        try {
            final IXdStorageTransaction tx = check.beginTransaction(TIMEOUT_MILLIS);
            assertNull(check.load(BenchmarkEntity.class, "late", tx), "the refused commit reached the disk " + route);
            check.rollbackTransaction(tx);
        } finally {
            check.shutdown();
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** A fresh storage folder: every call its own (the late-commit test shuts two storages down). */
    private Path folder() throws IOException {
        return Files.createDirectories(tempDir.resolve("db-" + FOLDERS.incrementAndGet())).toRealPath();
    }

    private static IXdFileStorage open(final Path folder) {
        return XdStorageProvider.newOrGetFileStorage("shutckpt-" + System.nanoTime(), folder.toString(), 64,
                FilesFormat.YAML, XdStorageConfig.builder().journalWrites(true).build());
    }

    private static void save(final IXdFileStorage storage, final String id, final String payload)
            throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(TIMEOUT_MILLIS);
        storage.save(new BenchmarkEntity(id, payload), tx);
        storage.commitTransaction(tx);
    }

    /** A committing thread stopped between the phases, and the holder that stops it. */
    private static final class ParkedCommit {
        private final Thread committer;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch committed = new CountDownLatch(1);
        private final CountDownLatch letGo = new CountDownLatch(1);
        private final Thread holder;

        private ParkedCommit(final IXdFileStorage storage, final String id, final Object indexAppendLock)
                throws InterruptedException {
            final CountDownLatch held = new CountDownLatch(1);
            holder = new Thread(() -> {
                synchronized (indexAppendLock) {
                    held.countDown();
                    try {
                        letGo.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "id-index-append-lock-holder");
            holder.start();
            assertTrue(held.await(30L, TimeUnit.SECONDS), "the holder never took the id-index append lock");
            committer = new Thread(() -> {
                try {
                    save(storage, id, "between the phases");
                } catch (final Throwable t) {
                    failure.set(t);
                } finally {
                    committed.countDown();
                }
            }, "commit-between-the-phases");
        }

        private boolean parkedInTheIndexAppend() {
            if (committer.getState() != Thread.State.BLOCKED) {
                return false;
            }
            final StackTraceElement[] stack = committer.getStackTrace();
            return stack.length > 0 && "append".equals(stack[0].getMethodName())
                    && XdStorageYamlCommitJournal.class.getName().equals(stack[0].getClassName());
        }

        private void release() {
            letGo.countDown();
        }

        private void awaitCommitted() throws InterruptedException {
            assertTrue(committed.await(30L, TimeUnit.SECONDS), "the parked commit never finished");
            assertNull(failure.get(), "the parked commit failed");
            holder.join(TimeUnit.SECONDS.toMillis(30L));
        }

        /** The action runs while the commit is parked; the holder lets go when it has returned. */
        private void whileParked(final Action action) throws Exception {
            try {
                action.run();
            } finally {
                letGo.countDown();
            }
        }
    }

    /** Something the test does while the commit is parked. */
    private interface Action {
        void run() throws Exception;
    }

    /**
     * Starts a commit of one record and stops it between the phases: the committing thread is
     * inside the id-index journal's append — after the data journal took its redo.
     */
    private static ParkedCommit parkACommit(final IXdFileStorage storage, final String id, final Path folder)
            throws Exception {
        final long journalBefore = dataJournalBytes(folder);
        final Object indexAppendLock = XdStorageYamlCommitJournal.lockFor(
                folder.resolve(DATA_DIR).resolve(INDEX_JOURNAL));
        final ParkedCommit parked = new ParkedCommit(storage, id, indexAppendLock);
        parked.committer.start();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        while ((!parked.parkedInTheIndexAppend() || dataJournalBytes(folder) <= journalBefore)
                && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(parked.parkedInTheIndexAppend(), "the committing thread did not stop in the id-index append");
        assertTrue(dataJournalBytes(folder) > journalBefore, "the data journal did not take the redo first");
        return parked;
    }

    private static List<Path> dataJournals(final Path folder) throws IOException {
        final List<Path> journals = new ArrayList<>();
        final Path dir = folder.resolve(DATA_DIR);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (final Path file : (Iterable<Path>) files::iterator) {
                    if (file.getFileName().toString().endsWith(XdStorageYamlCommitJournal.JOURNAL_SUFFIX)
                            && !file.getFileName().toString().equals(INDEX_JOURNAL)) {
                        journals.add(file);
                    }
                }
            }
        }
        return journals;
    }

    private static long dataJournalBytes(final Path folder) throws IOException {
        long bytes = 0L;
        for (final Path journal : dataJournals(folder)) {
            bytes += Files.size(journal);
        }
        return bytes;
    }

    private static void assertReopenHolds(final Path folder, final String id) throws Exception {
        final IXdFileStorage reopened = open(folder);
        try {
            final IXdStorageTransaction tx = reopened.beginTransaction(TIMEOUT_MILLIS);
            final BenchmarkEntity loaded = reopened.load(BenchmarkEntity.class, id, tx);
            assertNotNull(loaded,
                    "the record '" + id + "' is gone after the reopen — a committed transaction was lost");
            tx.commit();
        } finally {
            reopened.shutdown();
        }
    }
}
