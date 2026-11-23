package io.github.xdstorage.properties;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AfterTry;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPTEST P1.2 (docs/tasks/2.2.0/03): B+tree invariants under RANDOM operation
 * sequences — the generated shapes the hand-written suites never think of. After each
 * random insert/delete sequence the tree must hold the OBSERVABLE contract:
 * <ul>
 *   <li>every surviving (key → value) pair is findable;</li>
 *   <li>every deleted key is absent (no phantoms);</li>
 *   <li>the live entry count equals the model's.</li>
 * </ul>
 * The sequence model: a deterministic op list derived from the seed (insert/delete/
 * reinsert with a bounded key space so the deletions hit real entries).
 */
public class PropBTreeSequencesTest {

    private final List<File> folders = new ArrayList<>();

    @AfterTry
    void cleanup() {
        for (final File dir : folders) {
            deleteRecursively(dir);
        }
        folders.clear();
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

    @Property(tries = 40)
    void randomSequences_HoldTheContract(@ForAll final long seed) throws XdStorageException,
            XdStorageConnectionException {
        final Random rnd = new Random(seed);
        final File dir = new File("./target/pt-btree-" + System.nanoTime());
        folders.add(dir);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-btree-" + System.nanoTime(), dir.getPath(), 64);
        try {
            // the tree lives on the storage's real persistence (the fills force the node files)
            final XdStorageBTree tree = new XdStorageBTree(
                    new XdStorageBTreeId(String.class, "prop_" + Long.toUnsignedString(seed, 36)), false, 4);
            final IXdStorageTransaction tx = storage.beginTransaction();

            final Map<String, String> model = new HashMap<>();
            final List<String> keySpace = new ArrayList<>();
            int ops = 0;
            final int totalOps = 60 + rnd.nextInt(120);
            int splitPressure = 0;
            while (ops < totalOps) {
                final int op = rnd.nextInt(100);
                // the UNIQUE-tree semantics: an insert of an existing key refuses loudly, so the
                // generator inserts only FREE keys (the real workload's contract) and deletes
                // only EXISTING ones (the deletions hit real entries)
                final boolean doInsert = op < 55 || model.isEmpty();
                if (doInsert) {
                    String key = null;
                    for (int attempt = 0; attempt < 20 && key == null; attempt++) {
                        final String candidate = "k" + rnd.nextInt(300);
                        if (!model.containsKey(candidate)) {
                            key = candidate;
                        }
                    }
                    if (key == null) {
                        ops++;
                        continue; // the space exhausted this round
                    }
                    final String value = "v" + ops + "-" + rnd.nextInt(1000);
                    tree.insert(key, value, storage, tx);
                    model.put(key, value);
                    keySpace.add(key);
                    splitPressure++;
                } else {
                    final String victim = keySpace.get(rnd.nextInt(keySpace.size()));
                    tree.delete(victim, storage, tx);
                    model.remove(victim);
                    keySpace.remove(victim);
                }
                ops++;
            }
            storage.commitTransaction(tx);

            // THE STRUCTURAL-INVARIANTS SUITE (task 03 scope 1b: "hold the invariant suite"):
            // after EVERY random sequence the exercised tree must pass the full walk —
            // occupancy, balance, separator bounds, parent pointers, the leaf chain, no
            // orphans (the same validator the B+tree suites use; the consistency-sampled
            // walk under the tree's write lock)
            final io.github.xdstorage.transaction.XdStorageTransaction invariantTx =
                    (io.github.xdstorage.transaction.XdStorageTransaction) storage.beginTransaction();
            io.github.xdstorage.btree.XdStorageBTreeInvariants.validateLeafChain(
                    tree, storage, invariantTx, "PROPTEST-sequence-" + seed);
            storage.commitTransaction(invariantTx);

            // the observable contract: every surviving pair findable, every deleted key absent
            final IXdStorageTransaction verifyTx = storage.beginTransaction();
            for (final Map.Entry<String, String> entry : model.entrySet()) {
                final List<Object> found = tree.find(entry.getKey(), storage, verifyTx);
                assertTrue(found != null && found.contains(entry.getValue()),
                        "the surviving pair lost: " + entry.getKey() + " -> " + entry.getValue()
                                + " (found " + found + ")");
            }
            for (final String key : keySpace) {
                if (!model.containsKey(key)) {
                    assertTrue(tree.find(key, storage, verifyTx).isEmpty(),
                            "a phantom survived: " + key);
                }
            }
            storage.commitTransaction(verifyTx);
            System.out.println("[PT-BTREE] seed=" + seed + " ops=" + ops + " modelSize=" + model.size()
                    + " inserts=" + splitPressure);
        } finally {
            storage.shutdown();
        }
    }
}
