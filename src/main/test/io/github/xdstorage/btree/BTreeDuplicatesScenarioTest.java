package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.MockTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted duplicate scenarios of a MULTI B+tree: identical pairs, duplicate runs crossing
 * equal separators on several levels, the delete/update semantics of a key with many
 * records, values of mixed/incomparable types, and the spec scenario (t=5; series of 7, 13,
 * 17 and 150 duplicates) re-checked with the full invariant set after every step.
 */
@DisplayName("B+Tree: точечные сценарии дубликатов ключей и значений")
public class BTreeDuplicatesScenarioTest {

    private BTreeMemoryNodeStorage storage;
    private MockTransaction tx;

    @BeforeEach
    void setUp() {
        storage = new BTreeMemoryNodeStorage();
        tx = new MockTransaction("tx-scenario") {
            @Override
            public long getTimeout() {
                return 5000L;
            }
        };
    }

    private XdStorageBTree multiTree(final int t) {
        return new XdStorageBTree(new XdStorageBTreeId(String.class, "scenario_multi_t" + t), true, t);
    }

    private BTreeStructureChecker.Shape verify(final XdStorageBTree tree) {
        return BTreeStructureChecker.verify(tree, storage);
    }

    private static List<Object> values(final String prefix, final int from, final int to) {
        final List<Object> values = new ArrayList<>();
        for (int i = from; i <= to; ++i) {
            values.add(prefix + i);
        }
        return values;
    }

    @Test
    @DisplayName("Спецификация (t=5): серии 7, 13, 17 — все инварианты после каждой вставки и удаления, корень схлопывается")
    void specSeriesKeepEveryInvariant() throws Exception {
        final XdStorageBTree tree = multiTree(5);
        for (final int series : new int[]{7, 13, 17}) {
            final String key = "key_series_" + series;
            for (int i = 1; i <= series; ++i) {
                tree.insert(key, "value_" + series + "_" + i, storage, tx);
                verify(tree);
            }
            assertEquals(values("value_" + series + "_", 1, series), tree.find(key, storage, tx));
            // the earliest duplicates sit LEFT of the equal separator — the original miss
            for (int i = 1; i <= series; ++i) {
                tree.delete(key, "value_" + series + "_" + i, storage, tx);
                verify(tree);
                assertEquals(values("value_" + series + "_", i + 1, series), tree.find(key, storage, tx));
            }
        }
        assertNull(tree.getRoot());
        assertTrue(storage.storedNodeIds().isEmpty());
    }

    @Test
    @DisplayName("Спецификация (t=5): 150 горячих дубликатов + 50 соседних, случайный пурдж — инварианты после каждого шага")
    void specMassDuplicatesKeepEveryInvariant() throws Exception {
        final XdStorageBTree tree = multiTree(5);
        for (int i = 1; i <= 150; ++i) {
            tree.insert("hot_key_stress", "stress_payload_" + i, storage, tx);
        }
        for (int i = 1; i <= 50; ++i) {
            tree.insert("mixed_key_interleaved", "mixed_payload_" + i, storage, tx);
        }
        final BTreeStructureChecker.Shape full = verify(tree);
        assertTrue(full.height() >= 3, "150 duplicates at t=5 must build a multi-level tree, height " + full.height());

        final List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= 150; ++i) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(150));
        final List<Object> expected = values("stress_payload_", 1, 150);
        for (final int i : order) {
            tree.delete("hot_key_stress", "stress_payload_" + i, storage, tx);
            expected.remove("stress_payload_" + i);
            verify(tree);
        }
        assertEquals(expected, tree.find("hot_key_stress", storage, tx));
        assertEquals(values("mixed_payload_", 1, 50), tree.find("mixed_key_interleaved", storage, tx));
        for (int i = 1; i <= 50; ++i) {
            tree.delete("mixed_key_interleaved", "mixed_payload_" + i, storage, tx);
            verify(tree);
        }
        assertNull(tree.getRoot());
        assertTrue(storage.storedNodeIds().isEmpty());
    }

    @ParameterizedTest(name = "t={0}")
    @ValueSource(ints = {2, 3, 5})
    @DisplayName("Одинаковые пары (k, v): хранятся все, delete(k, v) снимает ровно одно вхождение")
    void identicalPairsAreAMultiset(final int t) throws Exception {
        final XdStorageBTree tree = multiTree(t);
        final int copies = 6 * t;
        for (int i = 0; i < copies; ++i) {
            tree.insert("k", "same", storage, tx);
            tree.insert("k", "other", storage, tx);
        }
        verify(tree);
        assertEquals(2 * copies, tree.find("k", storage, tx).size());
        for (int left = copies - 1; left >= 0; --left) {
            tree.delete("k", "same", storage, tx);
            verify(tree);
            final List<Object> found = tree.find("k", storage, tx);
            assertEquals(left, Collections.frequency(found, "same"), "exactly one copy must go per delete");
            assertEquals(copies, Collections.frequency(found, "other"), "the other value must stay intact");
        }
        assertThrows(XdStorageException.class, () -> tree.delete("k", "same", storage, tx));
        verify(tree);
    }

    @ParameterizedTest(name = "t={0}")
    @ValueSource(ints = {2, 3, 5})
    @DisplayName("Дубликаты сохраняют порядок вставки через сплиты и слияния")
    void duplicatesKeepInsertionOrder(final int t) throws Exception {
        final XdStorageBTree tree = multiTree(t);
        final List<Object> expected = values("d", 0, 199);
        for (final Object value : expected) {
            tree.insert("dup", value, storage, tx);
            tree.insert("a" + value, "noise", storage, tx); // neighbours below the hot key
        }
        verify(tree);
        assertEquals(expected, tree.find("dup", storage, tx));

        // remove every third one: the survivors keep their relative order
        for (int i = 0; i < 200; i += 3) {
            tree.delete("dup", "d" + i, storage, tx);
            expected.remove("d" + i);
        }
        verify(tree);
        assertEquals(expected, tree.find("dup", storage, tx));
    }

    @Test
    @DisplayName("Удаление пар слева от равного разделителя на нескольких ярусах (t=2, 2000 дубликатов, глубокое дерево)")
    void deleteFromLeftOfEqualSeparatorsInADeepTree() throws Exception {
        final XdStorageBTree tree = multiTree(2);
        for (int i = 0; i < 2000; ++i) {
            tree.insert("deep", i, storage, tx);
        }
        final BTreeStructureChecker.Shape shape = verify(tree);
        assertTrue(shape.height() >= 6, "2000 duplicates at t=2 must build a deep tree, height " + shape.height());
        // ascending = always the leftmost copy: the descent must look left of equal separators
        for (int i = 0; i < 2000; ++i) {
            tree.delete("deep", i, storage, tx);
            if (i % 97 == 0) {
                verify(tree);
            }
        }
        assertNull(tree.getRoot());
        assertTrue(storage.storedNodeIds().isEmpty());
    }

    @Test
    @DisplayName("delete(k) удаляет самую раннюю запись ключа, повторный вызов — следующую; промах громкий")
    void deleteByKeyRemovesTheEarliestEntry() throws Exception {
        final XdStorageBTree tree = multiTree(2);
        for (int i = 0; i < 25; ++i) {
            tree.insert("k", "r" + i, storage, tx);
            tree.insert("j", "s" + i, storage, tx);
        }
        for (int i = 0; i < 25; ++i) {
            tree.delete("k", storage, tx);
            verify(tree);
            assertEquals(values("r", i + 1, 24), tree.find("k", storage, tx));
        }
        assertThrows(XdStorageException.class, () -> tree.delete("k", storage, tx));
        assertEquals(values("s", 0, 24), tree.find("j", storage, tx));
        verify(tree);
    }

    @Test
    @DisplayName("update(k, v) на MULTI-дереве заменяет все записи ключа и сохраняет их количество")
    void updateReplacesEveryRecordOfTheKey() throws Exception {
        final XdStorageBTree tree = multiTree(2);
        for (int i = 0; i < 30; ++i) {
            tree.insert("k", "r" + i, storage, tx);
            tree.insert("m", "keep" + i, storage, tx);
        }
        tree.update("k", "new", storage, tx);
        verify(tree);
        assertEquals(Collections.nCopies(30, "new"), tree.find("k", storage, tx));
        assertEquals(values("keep", 0, 29), tree.find("m", storage, tx));
        assertThrows(XdStorageException.class, () -> tree.update("absent", "x", storage, tx));
    }

    @Test
    @DisplayName("Одно значение под разными ключами: удаление пары не задевает тот же value соседних ключей")
    void sameValueUnderDifferentKeys() throws Exception {
        final XdStorageBTree tree = multiTree(3);
        for (int k = 0; k < 40; ++k) {
            for (int copy = 0; copy < 3; ++copy) {
                tree.insert("key" + (k % 4), "shared", storage, tx);
            }
        }
        verify(tree);
        for (int copy = 0; copy < 30; ++copy) {
            tree.delete("key2", "shared", storage, tx);
            verify(tree);
        }
        assertTrue(tree.find("key2", storage, tx).isEmpty());
        for (final String other : Arrays.asList("key0", "key1", "key3")) {
            assertEquals(30, tree.find(other, storage, tx).size(), other + " lost a record");
        }
    }

    @Test
    @DisplayName("Пропущенная пара на живом ключе: громкий отказ без изменения структуры")
    void missedPairLeavesTheTreeIntact() throws Exception {
        final XdStorageBTree tree = multiTree(2);
        for (int i = 0; i < 40; ++i) {
            tree.insert("k", "r" + i, storage, tx);
        }
        final List<String> before = verify(tree).entries();
        assertThrows(XdStorageException.class, () -> tree.delete("k", "ghost", storage, tx));
        assertThrows(XdStorageException.class, () -> tree.delete("absent", "r1", storage, tx));
        assertThrows(XdStorageException.class, () -> tree.delete("absent", storage, tx));
        assertEquals(before, verify(tree).entries());
    }

    @Test
    @DisplayName("Значения разных и несравнимых типов под одним ключом: точечное удаление без ClassCastException")
    void mixedAndIncomparableRecordTypes() throws Exception {
        final XdStorageBTree tree = multiTree(2);
        final Object opaque = new Object();
        for (int i = 0; i < 10; ++i) {
            tree.insert("k", i, storage, tx);
            tree.insert("k", "s" + i, storage, tx);
            tree.insert("k", (long) i, storage, tx);
        }
        tree.insert("k", opaque, storage, tx);
        verify(tree);

        tree.delete("k", "s3", storage, tx);       // String among Integer/Long records
        tree.delete("k", 3L, storage, tx);         // Long — must not delete the Integer 3
        assertTrue(tree.find("k", storage, tx).contains(3), "the Integer 3 must survive the Long 3 delete");
        tree.delete("k", 3, storage, tx);
        verify(tree);
        final List<Object> found = tree.find("k", storage, tx);
        assertTrue(!found.contains("s3") && !found.contains(3) && !found.contains(3L) && found.contains(opaque));
        assertEquals(28, found.size());
    }

    @Test
    @DisplayName("Цикл «наполнить до многоярусного дерева — опустошить» повторяется без утечек нод")
    void refillAfterFullCollapse() throws Exception {
        final XdStorageBTree tree = multiTree(3);
        for (int round = 0; round < 5; ++round) {
            for (int i = 0; i < 120; ++i) {
                tree.insert(i % 3 == 0 ? "a" : "b", round + "-" + i, storage, tx);
            }
            assertTrue(verify(tree).height() >= 3);
            for (int i = 119; i >= 0; --i) {
                tree.delete(i % 3 == 0 ? "a" : "b", round + "-" + i, storage, tx);
            }
            verify(tree);
            assertNull(tree.getRoot(), "round " + round + ": the root must collapse");
            assertTrue(storage.storedNodeIds().isEmpty(), "round " + round + ": leaked nodes " + storage.storedNodeIds());
        }
    }
}
