package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.code.ValueChild;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The other half of the crash tests (WAL, docs/tasks/2.2.0/06): a SEPARATE process that opens
 * storages, commits, and then stays alive until the test kills it with SIGKILL. No shutdown
 * runs, so no checkpoint runs: every folder it touched is left exactly as a crash leaves it —
 * the commits durable in their journals only, the lock file stale.
 *
 * <p>Not a test class (the name carries no {@code Test} suffix); started by
 * {@link XdStorageCrashedStorages#crashAfterCommits}. Engine-test fixture — not part of any
 * API.</p>
 */
public final class XdStorageCrashCommitter {

    /** One transaction over two classes — four journal appends (data + id-index per class). */
    public static final String TWO_CLASSES = "two-classes";

    /** Three transactions, three new records of one class — three batches in the class's id-index journal. */
    public static final String THREE_COMMITS = "three-commits";

    /** Two transactions on one record — two batches in one data journal. */
    public static final String UPDATE_TWICE = "update-twice";

    private XdStorageCrashCommitter() {
    }

    /**
     * Commits in every folder given, writes the ready marker and then never returns.
     *
     * @param args {@code [0]} the ready marker path; {@code [1..]} {@code <folder>|<scenario>}
     * @throws Exception when a commit fails — the process exits and the marker is never written
     */
    public static void main(final String[] args) throws Exception {
        for (int i = 1; i < args.length; i++) {
            final int bar = args[i].indexOf('|');
            commitAndStayAlive(java.nio.file.Paths.get(args[i].substring(0, bar)), args[i].substring(bar + 1));
        }
        Files.write(java.nio.file.Paths.get(args[0]), new byte[0]);
        Thread.sleep(Long.MAX_VALUE);   // until the test sends SIGKILL
    }

    static void commitAndStayAlive(final Path folder, final String scenario) throws Exception {
        Files.createDirectories(folder);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("crash-" + System.nanoTime(),
                folder.toString(), 128);
        switch (scenario) {
            case TWO_CLASSES: {
                final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
                storage.save(new BenchmarkEntity("c-1", "Alice"), tx);
                final ValueChild child = new ValueChild();
                child.setId(1L);
                child.setLabel("child");
                storage.save(child, tx);
                storage.commitTransaction(tx);
                break;
            }
            case THREE_COMMITS: {
                for (int i = 1; i <= 3; i++) {
                    final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
                    storage.save(new BenchmarkEntity("e-" + i, "v-" + i), tx);
                    storage.commitTransaction(tx);
                }
                break;
            }
            case UPDATE_TWICE: {
                IXdStorageTransaction tx = storage.beginTransaction(60_000L);
                storage.save(new BenchmarkEntity("u-1", "first"), tx);
                storage.commitTransaction(tx);
                tx = storage.beginTransaction(60_000L);
                final BenchmarkEntity loaded = storage.load(BenchmarkEntity.class, "u-1", tx);
                loaded.setPayload("second");
                storage.update(loaded, tx);
                storage.commitTransaction(tx);
                break;
            }
            default:
                throw new IllegalArgumentException("unknown crash scenario: " + scenario);
        }
    }
}
