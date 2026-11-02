package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.MockTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Property test of the B+tree against a reference model: seeded random operation sequences
 * with heavy key AND pair duplication; after EVERY operation the full structural invariant set
 * ({@link BTreeStructureChecker}, INV-1..INV-14) is verified and the entries must equal the
 * model exactly — for a MULTI tree the duplicates of a key in insertion order. Covers every
 * fill factor class (t=2 — the smallest, deepest trees; t=3; t=5 — the spec's tree).
 */
@DisplayName("B+Tree: свойства дерева с дубликатами против эталонной модели (все инварианты после каждой операции)")
public class BTreeDuplicatesPropertyTest {

    private static final int OPERATIONS = 1500;

    static Stream<Arguments> fillFactorsAndSeeds() {
        final List<Arguments> arguments = new ArrayList<>();
        for (final int t : new int[]{2, 3, 5}) {
            for (final long seed : new long[]{7L, 42L, 2026L}) {
                arguments.add(Arguments.of(t, seed));
            }
        }
        return arguments.stream();
    }

    private static MockTransaction tx() {
        return new MockTransaction("tx-property") {
            @Override
            public long getTimeout() {
                return 5000L;
            }
        };
    }

    private static XdStorageBTree multiTree(final int t) {
        return new XdStorageBTree(new XdStorageBTreeId(Integer.class, "property_multi_t" + t), true, t);
    }

    /** MULTI model: key → its records in insertion order. */
    private static List<String> expectedEntries(final TreeMap<Integer, List<String>> model) {
        final List<String> entries = new ArrayList<>();
        for (final Map.Entry<Integer, List<String>> e : model.entrySet()) {
            for (final String record : e.getValue()) {
                entries.add(e.getKey() + "=" + record);
            }
        }
        return entries;
    }

    private static void verify(final XdStorageBTree tree, final BTreeMemoryNodeStorage storage,
                               final TreeMap<Integer, List<String>> model, final String step) {
        final BTreeStructureChecker.Shape shape;
        try {
            shape = BTreeStructureChecker.verify(tree, storage);
        } catch (final AssertionError e) {
            throw new AssertionError(step + ": " + e.getMessage(), e);
        }
        assertEquals(expectedEntries(model), shape.entries(), step + ": entries differ from the model");
    }

    private static int randomKey(final Random random) {
        // 70% — five hot keys (long runs of duplicates crossing many leaves), 30% — spread keys
        return random.nextInt(10) < 7 ? 100 + random.nextInt(5) * 10 : random.nextInt(300);
    }

    @ParameterizedTest(name = "t={0}, seed={1}")
    @MethodSource("fillFactorsAndSeeds")
    @DisplayName("MULTI: insert / delete(k,v) / delete(k) / update / промахи — инварианты и содержимое после каждой операции")
    public void multiTreeMatchesModelAfterEveryOperation(final int t, final long seed) throws Exception {
        final Random random = new Random(seed);
        final BTreeMemoryNodeStorage storage = new BTreeMemoryNodeStorage();
        final MockTransaction tx = tx();
        final XdStorageBTree tree = multiTree(t);
        final TreeMap<Integer, List<String>> model = new TreeMap<>();
        int maxHeight = 0;

        for (int op = 0; op < OPERATIONS; ++op) {
            final int dice = random.nextInt(100);
            final String step;
            if (dice < 55 || model.isEmpty()) {
                // a small value space: identical (key, value) pairs are frequent
                final int key = randomKey(random);
                final String value = "v" + random.nextInt(6);
                tree.insert(key, value, storage, tx);
                model.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                step = "op " + op + " insert(" + key + "," + value + ")";
            } else if (dice < 80) {
                final int key = randomExistingKey(model, random);
                final List<String> records = model.get(key);
                final String value = records.get(random.nextInt(records.size()));
                tree.delete(key, value, storage, tx);
                records.remove(value); // the earliest occurrence, as the tree does
                if (records.isEmpty()) {
                    model.remove(key);
                }
                step = "op " + op + " delete(" + key + "," + value + ")";
            } else if (dice < 88) {
                final int key = randomExistingKey(model, random);
                tree.delete(key, storage, tx);
                final List<String> records = model.get(key);
                records.remove(0); // delete-by-key removes the earliest entry
                if (records.isEmpty()) {
                    model.remove(key);
                }
                step = "op " + op + " delete(" + key + ")";
            } else if (dice < 93) {
                final int key = randomExistingKey(model, random);
                final String value = "u" + random.nextInt(3);
                tree.update(key, value, storage, tx);
                Collections.fill(model.get(key), value);
                step = "op " + op + " update(" + key + "," + value + ")";
            } else {
                // a miss must fail loudly and leave the structure untouched
                final int key = randomKey(random);
                final String ghost = "ghost" + op;
                final List<String> before = BTreeStructureChecker.verify(tree, storage).entries();
                assertThrows(XdStorageException.class, () -> tree.delete(key, ghost, storage, tx),
                        "delete of the missing pair (" + key + "," + ghost + ") must fail loudly");
                if (!model.containsKey(key) && tree.getRoot() != null) {
                    assertThrows(XdStorageException.class, () -> tree.delete(key, storage, tx),
                            "delete of the missing key " + key + " must fail loudly");
                }
                assertEquals(before, BTreeStructureChecker.verify(tree, storage).entries(), "a missed delete changed the tree");
                step = "op " + op + " missed delete(" + key + ")";
            }

            verify(tree, storage, model, step);
            maxHeight = Math.max(maxHeight, BTreeStructureChecker.verify(tree).height());
            if (op % 100 == 0) {
                assertFindMatchesModel(tree, storage, tx, model, step);
            }
        }
        assertFindMatchesModel(tree, storage, tx, model, "after the random phase");
        assertTrue(maxHeight >= 3, "the scenario must grow a multi-level tree (height " + maxHeight + ")");

        // full purge in random pair order: the tree collapses to nothing, the storage is clean
        final List<int[]> order = new ArrayList<>();
        for (final Map.Entry<Integer, List<String>> e : model.entrySet()) {
            for (int i = 0; i < e.getValue().size(); ++i) {
                order.add(new int[]{e.getKey()});
            }
        }
        Collections.shuffle(order, random);
        for (final int[] holder : order) {
            final int key = holder[0];
            final List<String> records = model.get(key);
            final String value = records.get(random.nextInt(records.size()));
            tree.delete(key, value, storage, tx);
            records.remove(value);
            if (records.isEmpty()) {
                model.remove(key);
            }
            verify(tree, storage, model, "purge delete(" + key + "," + value + ")");
        }
        assertNull(tree.getRoot(), "a purged tree must collapse its root to null");
        assertNull(tree.getFirstLeaf(), "a purged tree must clear its first leaf");
        assertTrue(storage.storedNodeIds().isEmpty(), "node files left after the purge: " + storage.storedNodeIds());
    }

    @ParameterizedTest(name = "t={0}, seed={1}")
    @MethodSource("fillFactorsAndSeeds")
    @DisplayName("UNIQUE: insert / delete / update / повторная вставка — инварианты и содержимое после каждой операции")
    public void uniqueTreeMatchesModelAfterEveryOperation(final int t, final long seed) throws Exception {
        final Random random = new Random(seed);
        final BTreeMemoryNodeStorage storage = new BTreeMemoryNodeStorage();
        final MockTransaction tx = tx();
        final XdStorageBTree tree = new XdStorageBTree(new XdStorageBTreeId(Integer.class, "property_unique_t" + t), false, t);
        final TreeMap<Integer, List<String>> model = new TreeMap<>();

        for (int op = 0; op < OPERATIONS; ++op) {
            final int dice = random.nextInt(100);
            final int key = random.nextInt(400);
            final String step;
            if (dice < 55) {
                final String value = "r" + op;
                if (model.containsKey(key)) {
                    assertThrows(XdStorageException.class, () -> tree.insert(key, value, storage, tx),
                            "a unique tree must reject the duplicate key " + key);
                } else {
                    tree.insert(key, value, storage, tx);
                    model.put(key, new ArrayList<>(Collections.singletonList(value)));
                }
                step = "op " + op + " insert(" + key + ")";
            } else if (dice < 90) {
                if (model.containsKey(key)) {
                    tree.delete(key, storage, tx);
                    model.remove(key);
                } else if (tree.getRoot() != null) {
                    assertThrows(XdStorageException.class, () -> tree.delete(key, storage, tx),
                            "delete of the missing key " + key + " must fail loudly");
                }
                step = "op " + op + " delete(" + key + ")";
            } else {
                final String value = "w" + op;
                if (model.containsKey(key)) {
                    tree.update(key, value, storage, tx);
                    model.put(key, new ArrayList<>(Collections.singletonList(value)));
                } else if (tree.getRoot() != null) {
                    assertThrows(XdStorageException.class, () -> tree.update(key, value, storage, tx),
                            "update of the missing key " + key + " must fail loudly");
                }
                step = "op " + op + " update(" + key + ")";
            }
            verify(tree, storage, model, step);
        }
        assertFindMatchesModel(tree, storage, tx, model, "after the random phase");

        final List<Integer> keys = new ArrayList<>(model.keySet());
        Collections.shuffle(keys, random);
        for (final Integer key : keys) {
            tree.delete(key, storage, tx);
            model.remove(key);
            verify(tree, storage, model, "purge delete(" + key + ")");
        }
        assertNull(tree.getRoot(), "a purged tree must collapse its root to null");
        assertTrue(storage.storedNodeIds().isEmpty(), "node files left after the purge: " + storage.storedNodeIds());
    }

    private static int randomExistingKey(final TreeMap<Integer, List<String>> model, final Random random) {
        final List<Integer> keys = new ArrayList<>(model.keySet());
        return keys.get(random.nextInt(keys.size()));
    }

    private static void assertFindMatchesModel(final XdStorageBTree tree, final BTreeMemoryNodeStorage storage,
                                               final MockTransaction tx, final TreeMap<Integer, List<String>> model,
                                               final String step) throws Exception {
        for (final Map.Entry<Integer, List<String>> e : model.entrySet()) {
            final List<Object> found = tree.find(e.getKey(), storage, tx);
            if (!new ArrayList<Object>(e.getValue()).equals(found)) {
                fail(step + ": find(" + e.getKey() + ") returned " + found + ", the model holds " + e.getValue());
            }
        }
        assertTrue(tree.find(-1, storage, tx).isEmpty(), step + ": find of an absent key must be empty");
    }
}
