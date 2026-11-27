package io.github.xdstorage.codegen.jredemo;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A tiny APPLICATION for the JRE tests of docs/tasks/2.2.0/08 — the quickstart's lifecycle
 * as a plain {@code main}: open, commit two classes in one transaction, read back through
 * the secondary index and by id, reopen. {@code XdStorageJreRuntimeTest} runs it in a child
 * JVM on a JDK and on a runtime image without a compiler, with and without build-time
 * wrappers. Its normal output is exactly one line, {@value #OK}; a failure is an uncaught
 * exception, so the process ends with a non-zero status. The storage is always shut down —
 * the engine's worker threads are not daemons by design, and a {@code main} that leaves
 * them would keep a failed JVM alive. Test fixture — not part of any API.
 */
public final class JreDemo {

    /** The line a successful run prints. */
    public static final String OK = "DEMO-OK";

    private JreDemo() {
    }

    /**
     * Runs the lifecycle once.
     *
     * @param args {@code <storage folder>}
     * @throws Exception any failure of the lifecycle — left uncaught on purpose
     */
    public static void main(final String[] args) throws Exception {
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("jre-demo", args[0], 64);
        final List<String> german;
        final String loadedName;
        final Long loadedBalance;
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            try {
                storage.save(Arrays.asList(
                        new JreCustomer("c-1", "Alice", "DE"),
                        new JreCustomer("c-2", "Bruno", "FR"),
                        new JreCustomer("c-3", "Clara", "DE"),
                        new JreCustomer("c-4", "Dana", "US"),
                        new JreCustomer("c-5", "Emil", "DE")), tx);
                for (int i = 1; i <= 10; i++) {
                    storage.save(new JreAccount("a-" + i, "owner-" + i, (long) i * 100), tx);
                }
                storage.commitTransaction(tx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(tx);
                throw e;
            }

            final IXdStorageTransaction readTx = storage.beginTransaction(60_000L);
            try {
                german = storage.load(JreCustomer.class, "by_country",
                        new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS,
                                "DE"), null), readTx).stream().map(JreCustomer::getId).sorted()
                        .collect(Collectors.toList());
                final JreCustomer loaded = storage.load(JreCustomer.class, "c-1", readTx);
                loadedName = loaded.getName();
                loadedBalance = storage.load(JreAccount.class, "a-7", readTx).getBalance();
                storage.commitTransaction(readTx);
            } catch (final RuntimeException e) {
                storage.rollbackTransaction(readTx);
                throw e;
            }
        } finally {
            storage.shutdown();
        }

        final IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage("jre-demo-again", args[0], 64);
        final String afterRestart;
        try {
            final IXdStorageTransaction again = reopened.beginTransaction(60_000L);
            try {
                afterRestart = reopened.load(JreCustomer.class, "c-1", again).getName();
                reopened.commitTransaction(again);
            } catch (final RuntimeException e) {
                reopened.rollbackTransaction(again);
                throw e;
            }
        } finally {
            reopened.shutdown();
        }

        if (!"Alice".equals(loadedName) || !"Alice".equals(afterRestart)
                || !german.equals(Arrays.asList("c-1", "c-3", "c-5")) || !Long.valueOf(700L).equals(loadedBalance)) {
            throw new IllegalStateException("the demo read back something else: " + loadedName + " / "
                    + afterRestart + " / " + german + " / " + loadedBalance);
        }
        System.out.println(OK);
    }
}
