package io.github.xdstorage.performance.sweep;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Verify write amplification of READ-ONLY transactions: begin/load-by-id/commit
 * with the Hash id index — do commits rewrite index.yaml / data fragments?
 * Prints XdStorageMetrics resourceWriteOuts/resourceWriteOutNanos delta around the phase.
 */
public final class SweepFindTxVerify {

    public static void main(final String[] args) throws Exception {
        final int n = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        final boolean btree = args.length > 1 && "btree".equals(args[1]);
        final Class<?> cl = btree ? SweepBTreeT64Entity.class : SweepHashEntity.class;
        final String tag = "findtx-" + (btree ? "btree" : "hash") + "-" + System.nanoTime();
        final Path dir = Paths.get("target", "sweep", "run-" + tag);
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("sweep-" + tag, dir.toString(), 512);
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++)
            ids.add(String.format("id-%08d", i));
        final IXdStorageTransaction tx = storage.beginTransaction(120000L);
        try {
            for (final String id : ids) {
                final Object e = cl.getDeclaredConstructor().newInstance();
                cl.getMethod("setId", String.class).invoke(e, id);
                cl.getMethod("setPayload", String.class).invoke(e, SweepHashEntity.class.getName());
                storage.save(e, tx);
            }
            storage.commitTransaction(tx);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
        Collections.shuffle(ids, new Random(42));
        final long outs0 = storage.getMetrics().get("resourceWriteOuts");
        final long nanos0 = storage.getMetrics().get("resourceWriteOutNanos");

        // 500 read-only transactions: begin -> load by id -> commit
        final long t0 = System.nanoTime();
        int misses = 0;
        for (int i = 0; i < 500; i++) {
            final IXdStorageTransaction readTx = storage.beginTransaction(120000L);
            try {
                final Object o = storage.load(cl, ids.get(i), readTx);
                if (o == null)
                    misses++;
                storage.commitTransaction(readTx);
            } catch (final Exception e) {
                storage.rollbackTransaction(readTx);
                throw e;
            }
        }
        final long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        final long outs = storage.getMetrics().get("resourceWriteOuts") - outs0;
        final long nanos = storage.getMetrics().get("resourceWriteOutNanos") - nanos0;

        System.out.printf("%s N=%d: 500 read-only tx in %d ms (%.2f ms/op), misses=%d, writeOuts=%d, " +
                "writeOutNanos=%d (%.2f ms), avg %.2f ms/file%n",
                btree ? "btree" : "hash", n, elapsedMs, elapsedMs / 500.0, misses, outs, nanos, nanos / 1e6,
                outs == 0 ? 0 : nanos / 1e6 / outs);
        storage.shutdown();
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
}
