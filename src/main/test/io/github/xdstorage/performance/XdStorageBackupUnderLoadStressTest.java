package io.github.xdstorage.performance;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.entities.XdStorageMigrateOrder;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BACKUP (docs/tasks/2.2.0/15): {@code backupTo} while other threads commit.
 *
 * <p>Four workers commit PAIRS — two records in one transaction — until the storage is
 * closed under them. What a consistent copy can promise, and what is asserted: a
 * transaction is never cut in half. Every pair in the copy is whole, and every pair that
 * had committed when {@code backupTo} returned is in the copy; the same holds for the
 * source when it is reopened, and the source still works afterwards. Workers that fail
 * for a reason other than the shutdown (a lock timeout, a veto) are counted, not hidden;
 * they must not be the majority.</p>
 */
@Tag("stress")
@DisplayName("BACKUP (2.2.0/15): копия во время записи — ни одной половины транзакции")
class XdStorageBackupUnderLoadStressTest {

    private static final int WORKERS = 4;
    private static final int PAIRS_BEFORE_THE_COPY = 10;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("1. Четыре писателя коммитят пары, backupTo закрывает хранилище под ними: только целые пары")
    void theCopyHoldsWholePairsAndTheSourceReopens() throws Exception {
        final Path source = tempDir.resolve("source");
        final Path backup = tempDir.resolve("backup");
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("backup-load-" + System.nanoTime(),
                source.toString(), 64, FilesFormat.YAML, XdStorageConfig.builder().journalWrites(true).build());
        final AtomicInteger committed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicReference<Throwable> unexpected = new AtomicReference<>();
        final ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        final CountDownLatch stopped = new CountDownLatch(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            final int worker = w;
            pool.submit(() -> {
                try {
                    for (int k = 1; ; k++) {
                        final IXdStorageTransaction tx = storage.beginTransaction(5_000L);
                        try {
                            storage.save(new XdStorageMigrateOrder("w" + worker + "-" + k + "-a", "pair-" + k,
                                    (long) k), tx);
                            storage.save(new XdStorageMigrateOrder("w" + worker + "-" + k + "-b", "pair-" + k,
                                    (long) k), tx);
                            storage.commitTransaction(tx);
                            committed.incrementAndGet();
                        } catch (final RuntimeException e) {
                            rollBackQuietly(storage, tx);
                            if (isShutDown(e)) {
                                return;
                            }
                            failed.incrementAndGet();
                        }
                    }
                } catch (final Throwable t) {
                    if (!isShutDown(t)) {
                        unexpected.compareAndSet(null, t);
                    }
                } finally {
                    stopped.countDown();
                }
            });
        }

        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        while (committed.get() < PAIRS_BEFORE_THE_COPY && System.nanoTime() < deadline
                && unexpected.get() == null) {
            Thread.sleep(10L);
        }
        assertNull(unexpected.get(), "a worker failed for a reason other than the shutdown");
        assertTrue(committed.get() >= PAIRS_BEFORE_THE_COPY, "the load never got going: " + committed.get()
                + " pair(s) committed, " + failed.get() + " failed");

        storage.backupTo(backup);
        final int committedWhenCopied = committed.get();

        assertTrue(stopped.await(30L, TimeUnit.SECONDS), "the workers did not stop once the storage closed");
        pool.shutdownNow();
        assertNull(unexpected.get(), "a worker failed for a reason other than the shutdown");
        assertTrue(failed.get() < committed.get(), "most transactions failed under load: " + failed.get()
                + " failed, " + committed.get() + " committed");

        // ---- the copy: whole pairs only, and every pair committed before the copy ----
        final int pairsInCopy = wholePairs(backup.toString());
        assertTrue(pairsInCopy >= committedWhenCopied, "the copy holds " + pairsInCopy + " pair(s), but "
                + committedWhenCopied + " had committed when backupTo returned");
        assertFalse(XdStorageProvider.verify(backup.toString()).verdict == XdStorageVerifyReport.Verdict.CORRUPT,
                "the copy does not verify: " + XdStorageProvider.verify(backup.toString()));

        // ---- the source: the same promise, and it is operable afterwards ----
        final int pairsInSource = wholePairs(source.toString());
        assertTrue(pairsInSource >= committedWhenCopied, "the source holds " + pairsInSource + " pair(s), but "
                + committedWhenCopied + " had committed");
        final IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage("backup-load-again-"
                + System.nanoTime(), source.toString(), 64);
        try {
            final IXdStorageTransaction tx = reopened.beginTransaction(60_000L);
            reopened.save(new XdStorageMigrateOrder("after-reopen", "after", 1L), tx);
            reopened.commitTransaction(tx);
            final IXdStorageTransaction readTx = reopened.beginTransaction(60_000L);
            assertEquals("after", reopened.load(XdStorageMigrateOrder.class, "after-reopen", readTx).getName());
            readTx.commit();
        } finally {
            reopened.shutdown();
        }
    }

    /** Opens the folder and counts the pairs of which both halves are there; a lone half fails the test. */
    private static int wholePairs(final String folder) throws Exception {
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("backup-load-check-" + System.nanoTime(),
                folder, 64);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            final Set<String> ids = new HashSet<>();
            for (final XdStorageMigrateOrder order : storage.load(XdStorageMigrateOrder.class,
                    new IXdStoragePredicate<XdStorageMigrateOrder>() {
                        @Override
                        public boolean passed(final XdStorageMigrateOrder candidate) {
                            return candidate.getId().startsWith("w");
                        }
                    }, tx)) {
                ids.add(order.getId());
            }
            storage.rollbackTransaction(tx);
            final List<String> loneHalves = new ArrayList<>();
            int pairs = 0;
            for (final String id : ids) {
                final String partner = id.endsWith("-a") ? id.substring(0, id.length() - 1) + "b"
                        : id.substring(0, id.length() - 1) + "a";
                if (!ids.contains(partner)) {
                    loneHalves.add(id);
                } else if (id.endsWith("-a")) {
                    pairs++;
                }
            }
            assertTrue(loneHalves.isEmpty(), "records whose partner is missing — a transaction cut in half: "
                    + loneHalves);
            return pairs;
        } finally {
            storage.shutdown();
        }
    }

    private static void rollBackQuietly(final IXdFileStorage storage, final IXdStorageTransaction tx) {
        try {
            storage.rollbackTransaction(tx);
        } catch (final RuntimeException alreadyFinished) {
            // the transaction is over either way
        }
    }

    private static boolean isShutDown(final Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("is shut down")) {
                return true;
            }
        }
        return false;
    }
}
