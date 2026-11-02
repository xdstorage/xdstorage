package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.MockTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Thread safety of a MULTI B+tree under duplicate-heavy load: concurrent writers churn pairs
 * of a few hot keys (permanent splits/merges across equal separators) while readers verify
 * every scan; afterwards the content must equal the union of the writers' models and the
 * full invariant set must hold (no leaked lock, no orphan node). Every scenario must finish
 * without a single exception — a lock timeout or a "does not exists" on a live pair is a
 * failure here.
 */
@Tag("stress")
@DisplayName("B+Tree: потокобезопасность MULTI-дерева под нагрузкой дубликатами")
public class BTreeDuplicatesConcurrencyStressTest {

    private static final int[] HOT_KEYS = {10, 20, 30};
    private static final int WITNESSES = 40;

    private BTreeMemoryNodeStorage storage;

    @BeforeEach
    void setUp() {
        storage = new BTreeMemoryNodeStorage();
    }

    private static MockTransaction tx(final String id) {
        return new MockTransaction(id) {
            @Override
            public long getTimeout() {
                return 5000L;
            }
        };
    }

    private static XdStorageBTree multiTree(final String name, final int t) {
        return new XdStorageBTree(new XdStorageBTreeId(Integer.class, name), true, t);
    }

    private static List<Object> witnesses(final int key) {
        final List<Object> values = new ArrayList<>();
        for (int i = 0; i < WITNESSES; ++i) {
            values.add("stable-" + key + "-" + i);
        }
        return values;
    }

    /** Runs the tasks concurrently from one start signal; any task exception fails the test. */
    private static void runConcurrently(final List<Callable<Void>> tasks, final long timeoutSeconds) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<Void>> futures = new ArrayList<>();
            for (final Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (final Future<Void> future : futures) {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not stop");
        }
    }

    @Test
    @DisplayName("Писатели крутят пары горячих ключей, читатели видят неизменных свидетелей в каждом скане; итог = модель")
    void writersChurnHotKeysWhileReadersSeeStableWitnesses() throws Exception {
        final XdStorageBTree tree = multiTree("concurrent_churn", 3);
        final MockTransaction setupTx = tx("tx-setup");
        for (final int key : HOT_KEYS) {
            for (final Object witness : witnesses(key)) {
                tree.insert(key, witness, storage, setupTx);
            }
        }

        final int writers = 6;
        final int readers = 4;
        final int opsPerWriter = 1500;
        final AtomicBoolean writing = new AtomicBoolean(true);
        final AtomicInteger writersLeft = new AtomicInteger(writers);
        final AtomicInteger scans = new AtomicInteger();
        final List<Map<Integer, List<String>>> models = Collections.synchronizedList(new ArrayList<>());
        final List<Callable<Void>> tasks = new ArrayList<>();

        for (int w = 0; w < writers; ++w) {
            final int writer = w;
            tasks.add(() -> {
                final Random random = new Random(1000 + writer);
                final MockTransaction workerTx = tx("tx-writer-" + writer);
                final Map<Integer, List<String>> own = new HashMap<>();
                final List<int[]> live = new ArrayList<>(); // [key, serial]
                try {
                    for (int op = 0; op < opsPerWriter; ++op) {
                        if (live.isEmpty() || random.nextInt(100) < 60) {
                            final int key = random.nextInt(4) == 0 ? random.nextInt(40) : HOT_KEYS[random.nextInt(HOT_KEYS.length)];
                            final String value = "w" + writer + "-" + op;
                            tree.insert(key, value, storage, workerTx);
                            own.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                            live.add(new int[]{key, op});
                        } else {
                            final int[] victim = live.remove(random.nextInt(live.size()));
                            final String value = "w" + writer + "-" + victim[1];
                            tree.delete(victim[0], value, storage, workerTx);
                            own.get(victim[0]).remove(value);
                        }
                    }
                    models.add(own);
                    return null;
                } finally {
                    if (writersLeft.decrementAndGet() == 0) {
                        writing.set(false);
                    }
                }
            });
        }
        for (int r = 0; r < readers; ++r) {
            final int reader = r;
            tasks.add(() -> {
                final MockTransaction readerTx = tx("tx-reader-" + reader);
                while (writing.get()) {
                    for (final int key : HOT_KEYS) {
                        final List<Object> found = tree.find(key, storage, readerTx);
                        // the witnesses are the earliest entries of the key and are never deleted:
                        // every scan — atomic w.r.t. the writers — starts with them, in order
                        if (found.size() < WITNESSES || !found.subList(0, WITNESSES).equals(witnesses(key))) {
                            fail("scan of key " + key + " lost or reordered its stable witnesses: " + found);
                        }
                        final Set<Object> distinct = new HashSet<>(found);
                        assertEquals(found.size(), distinct.size(), "a scan of key " + key + " returned a record twice");
                    }
                    final List<Integer> order = new ArrayList<>();
                    tree.read(storage, readerTx, (key, value) -> order.add((Integer) key));
                    for (int i = 1; i < order.size(); ++i) {
                        assertTrue(order.get(i - 1) <= order.get(i), "a full scan is out of order at " + i);
                    }
                    scans.incrementAndGet();
                }
                return null;
            });
        }

        runConcurrently(tasks, 120);
        assertTrue(scans.get() > 0, "the readers never completed a scan");

        // content = witnesses + the union of the writers' surviving pairs
        final BTreeStructureChecker.Shape shape = BTreeStructureChecker.verify(tree, storage);
        final MockTransaction checkTx = tx("tx-check");
        int expectedSize = HOT_KEYS.length * WITNESSES;
        for (int key = 0; key < 40; ++key) {
            final List<Object> expected = new ArrayList<>();
            for (final Map<Integer, List<String>> own : models) {
                expected.addAll(own.getOrDefault(key, Collections.emptyList()));
            }
            expectedSize += expected.size();
            List<Object> found = new ArrayList<>(tree.find(key, storage, checkTx));
            final boolean hot = key == 10 || key == 20 || key == 30;
            if (hot) {
                assertEquals(witnesses(key), new ArrayList<>(found.subList(0, WITNESSES)), "witnesses of key " + key);
                found = new ArrayList<>(found.subList(WITNESSES, found.size()));
            }
            Collections.sort((List) expected);
            Collections.sort((List) found);
            assertEquals(expected, found, "records of key " + key + " differ from the writers' models");
        }
        assertEquals(expectedSize, shape.size(), "the tree holds records nobody inserted");

        // concurrent purge down to nothing
        final List<Callable<Void>> purge = new ArrayList<>();
        final int[] slot = {0};
        for (final Map<Integer, List<String>> own : new ArrayList<>(models)) {
            final int purger = slot[0]++;
            purge.add(() -> {
                final MockTransaction purgeTx = tx("tx-purge-" + purger);
                for (final Map.Entry<Integer, List<String>> e : own.entrySet()) {
                    for (final String value : e.getValue()) {
                        tree.delete(e.getKey(), value, storage, purgeTx);
                    }
                }
                return null;
            });
        }
        for (final int key : HOT_KEYS) {
            purge.add(() -> {
                final MockTransaction purgeTx = tx("tx-purge-witness-" + key);
                for (final Object witness : witnesses(key)) {
                    tree.delete(key, (Comparable) witness, storage, purgeTx);
                }
                return null;
            });
        }
        runConcurrently(purge, 120);
        BTreeStructureChecker.verify(tree, storage);
        assertNull(tree.getRoot(), "the purged tree must collapse its root");
        assertTrue(storage.storedNodeIds().isEmpty(), "orphan nodes after the concurrent purge: " + storage.storedNodeIds());
    }

    @Test
    @DisplayName("Конкурентный delete(k) снимает ровно столько записей, сколько было: без двойных удалений и потерь")
    void concurrentDeleteByKeyDrainsExactly() throws Exception {
        final XdStorageBTree tree = multiTree("concurrent_drain", 2);
        final MockTransaction setupTx = tx("tx-setup");
        final int hotCount = 800;
        for (int i = 0; i < hotCount; ++i) {
            tree.insert(7, "d" + i, storage, setupTx);
            if (i % 8 == 0) {
                tree.insert(6, "left" + i, storage, setupTx);
                tree.insert(8, "right" + i, storage, setupTx);
            }
        }
        final List<Object> leftBefore = tree.find(6, storage, setupTx);
        final List<Object> rightBefore = tree.find(8, storage, setupTx);

        final AtomicInteger removed = new AtomicInteger();
        final List<Callable<Void>> tasks = new ArrayList<>();
        for (int w = 0; w < 8; ++w) {
            final int worker = w;
            tasks.add(() -> {
                final MockTransaction workerTx = tx("tx-drain-" + worker);
                while (true) {
                    try {
                        tree.delete(7, storage, workerTx);
                        removed.incrementAndGet();
                    } catch (final XdStorageException e) {
                        if (String.valueOf(e.getMessage()).contains("does not exists")) {
                            return null; // the key is drained
                        }
                        throw e;
                    }
                }
            });
        }
        runConcurrently(tasks, 120);

        assertEquals(hotCount, removed.get(), "every record must be removed exactly once");
        BTreeStructureChecker.verify(tree, storage);
        assertTrue(tree.find(7, storage, setupTx).isEmpty());
        assertEquals(leftBefore, tree.find(6, storage, setupTx), "the left neighbour key was touched");
        assertEquals(rightBefore, tree.find(8, storage, setupTx), "the right neighbour key was touched");
    }

    @Test
    @DisplayName("Конкурентные вставки и удаления ОДИНАКОВОЙ пары (k, v): мультимножество сходится к нулю")
    void concurrentIdenticalPairs() throws Exception {
        final XdStorageBTree tree = multiTree("concurrent_identical", 3);
        final int workers = 8;
        final int perWorker = 300;
        final List<Callable<Void>> inserts = new ArrayList<>();
        for (int w = 0; w < workers; ++w) {
            final int worker = w;
            inserts.add(() -> {
                final MockTransaction workerTx = tx("tx-same-ins-" + worker);
                for (int i = 0; i < perWorker; ++i) {
                    tree.insert(5, "same", storage, workerTx);
                    if (i % 10 == 0) {
                        tree.insert(100 + worker, "marker" + i, storage, workerTx);
                    }
                }
                return null;
            });
        }
        runConcurrently(inserts, 120);
        BTreeStructureChecker.verify(tree, storage);
        assertEquals(workers * perWorker, tree.find(5, storage, tx("tx-count")).size());

        final List<Callable<Void>> deletes = new ArrayList<>();
        for (int w = 0; w < workers; ++w) {
            final int worker = w;
            deletes.add(() -> {
                final MockTransaction workerTx = tx("tx-same-del-" + worker);
                for (int i = 0; i < perWorker; ++i) {
                    tree.delete(5, "same", storage, workerTx);
                }
                return null;
            });
            deletes.add(() -> {
                final MockTransaction readerTx = tx("tx-same-read-" + worker);
                for (int i = 0; i < 200; ++i) {
                    final List<Object> found = tree.find(5, storage, readerTx);
                    assertTrue(found.stream().allMatch("same"::equals), "foreign record under key 5: " + found);
                }
                return null;
            });
        }
        runConcurrently(deletes, 120);
        BTreeStructureChecker.verify(tree, storage);
        assertTrue(tree.find(5, storage, tx("tx-count")).isEmpty());
        for (int w = 0; w < workers; ++w) {
            assertEquals(perWorker / 10, tree.find(100 + w, storage, tx("tx-count")).size(), "markers of worker " + w);
        }
    }
}
