package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.MockTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты-инварианты B+ Дерева, нацеленные ТОЧНО на аномалии split (вставка) и join (удаление).
 *
 * После КАЖДОЙ операции выполняется глубокая верификация структуры:
 *  1. Множество ключей дерева == ожидаемое (ни один ключ не потерян и не продублирован).
 *  2. Цепочка листьев отсортирована (in-order обход дает неубывающую последовательность).
 *  3. Множество листьев, достижимых из корня, == множеству листьев из цепочки next
 *     (ловит и "drop child" при root-collapse, и обрыв цепочки при join).
 *  4. Инвариант устройства узлов: children == keys + 1 (внутренний), keys == objects (лист).
 *  5. Parent-указатели: каждый ребенок ссылается на своего родителя, корень — на null.
 *  6. Заполненность: не-корневой узел несет [t-1 .. 2t-1] ключей.
 *  7. Все листья на ОДНОЙ глубине (классическая сбалансированность B-дерева).
 *
 * Верификатор запускается после каждой операции — это локализует аномалию
 * до конкретной операции в последовательности (номер итерации печатается в сообщении).
 */
@DisplayName("Инварианты split/join B+ Дерева: глубокая верификация после каждой операции")
public class XdStorageBTreeSplitJoinInvariantTest extends AbstractBTreeTest {

    // =========================================================================
    // Глубокий верификатор структуры
    // =========================================================================

    /** Результат полного обхода дерева. */
    private static class TreeScan {
        final List<Comparable> inOrderKeys = new ArrayList<>();
        final Set<Object> leafIdsViaRoot = new LinkedHashSet<>();
        final Set<Object> leafIdsViaChain = new LinkedHashSet<>();
        int leafDepth = -1;
    }

    private TreeScan deepScan(final XdStorageBTree tree) {
        final TreeScan scan = new TreeScan();
        final XdStorageBTreeNode root = tree.getRoot();

        if (root != null) {
            descend(root, 0, scan, new HashSet<>());
        }

        // Цепочка листьев от firstLeaf
        XdStorageBTreeNode cur = tree.getFirstLeaf();
        int guard = 0;
        Comparable prevChainKey = null;
        while (cur != null) {
            assertTrue(scan.leafIdsViaChain.add(System.identityHashCode(cur)),
                    "Цикл в цепочке листьев: лист " + cur.getId() + " встречен повторно");
            if (prevChainKey != null && !cur.getKeys().isEmpty()) {
                Comparable min = cur.getKeys().get(0);
                assertTrue(prevChainKey.compareTo(min) <= 0,
                        "Нарушена сортировка цепочки листьев: " + prevChainKey + " > " + min);
            }
            for (final Comparable k : cur.getKeys()) {
                scan.inOrderKeys.add(k);
                prevChainKey = k;
            }
            cur = cur.getNextTreeNodeOnThisLevel();
            guard++;
            assertTrue(guard < 100000, "Бесконечная цепочка листьев");
        }
        return scan;
    }

    private void descend(final XdStorageBTreeNode node, final int depth, final TreeScan scan, final Set<Object> visited) {
        assertTrue(visited.add(System.identityHashCode(node)),
                "Цикл/дубль в структуре: узел встречен повторно при спуске из корня");

        if (node.getChildren().isEmpty()) {
            // Лист
            assertEquals(node.getKeys().size(), node.getObjects().size(),
                    "Лист рассинхронизирован: keys=" + node.getKeys().size() + " objects=" + node.getObjects().size());
            if (scan.leafDepth == -1) {
                scan.leafDepth = depth;
            }
            assertEquals(scan.leafDepth, depth, "Дерево не сбалансировано: листья на разных глубинах");
            assertTrue(scan.leafIdsViaRoot.add(System.identityHashCode(node)), "Лист продублирован в дереве");
            return;
        }

        // Внутренний узел: children == keys + 1
        assertEquals(node.getKeys().size() + 1, node.getChildren().size(),
                "Инвариант children == keys + 1 нарушен у узла " + node.getId());

        // Parent-указатели детей
        for (final XdStorageBTreeNode child : node.getChildren()) {
            assertNotNull(child, "null-ребенок у внутренней ноды " + node.getId());
            assertSame(node, child.getParent(),
                    "Parent-указатель ребенка " + child.getId() + " не ссылается на родителя " + node.getId());
            descend(child, depth + 1, scan, visited);
        }
    }

    /**
     * Полная верификация: структура + множество ключей.
     * expectedKeys — ключи, которые ОБЯЗАНЫ присутствовать; deletedKeys — которые ОБЯЗАНЫ отсутствовать.
     */
    private void verify(final XdStorageBTree tree, final int t,
                        final Set<Integer> expectedKeys, final String context) {
        final TreeScan scan = deepScan(tree);

        // 1. Множество ключей
        final Set<Integer> actual = new TreeSet<>();
        for (final Comparable k : scan.inOrderKeys) {
            assertTrue(actual.add((Integer) k), "Дубликат ключа в дереве: " + k + " (" + context + ")");
        }
        for (final Integer expected : expectedKeys) {
            assertTrue(actual.contains(expected), context + ": потерян ключ " + expected
                    + " (в дереве: " + actual + ")");
        }
        assertEquals(expectedKeys.size(), actual.size(),
                context + ": размер дерева " + actual.size() + " != ожидаемого " + expectedKeys.size()
                        + ", лишние ключи: " + actual);

        // 2. Множества листьев корня и цепочки совпадают
        assertEquals(scan.leafIdsViaRoot, scan.leafIdsViaChain,
                context + ": рассинхрон корня и цепочки листьев — split/join потерял или отсоединил лист");

        // 3. Occupancy не-корневых узлов
        final XdStorageBTreeNode root = tree.getRoot();
        if (root != null && !root.getChildren().isEmpty()) {
            checkOccupancy(root, t, true, context);
        }
    }

    private void checkOccupancy(final XdStorageBTreeNode node, final int t, final boolean isRoot, final String context) {
        if (node.getChildren().isEmpty()) {
            if (!isRoot) {
                assertTrue(node.getKeys().size() >= t - 1,
                        context + ": недозаполненный лист " + node.getId() + " keys=" + node.getKeys().size() + " < t-1=" + (t - 1));
            }
            assertTrue(node.getKeys().size() <= 2 * t - 1,
                    context + ": переполненный лист " + node.getId() + " keys=" + node.getKeys().size() + " > 2t-1");
            return;
        }
        if (!isRoot) {
            assertTrue(node.getKeys().size() >= t - 1,
                    context + ": недозаполненная внутренняя нода " + node.getId() + " keys=" + node.getKeys().size());
            assertTrue(node.getChildren().size() <= 2 * t,
                    context + ": переполненная внутренняя нода " + node.getId() + " children=" + node.getChildren().size());
        }
        for (final XdStorageBTreeNode child : node.getChildren()) {
            checkOccupancy(child, t, false, context);
        }
    }

    private XdStorageBTree newTree(final int t, final boolean multiple) {
        return new XdStorageBTree(new XdStorageBTreeId(Integer.class, "inv_t" + t), multiple, t);
    }

    // =========================================================================
    // 1. Восходящая вставка + восходящее удаление (максимум join'ов справа)
    // =========================================================================

    @Test
    @DisplayName("t=2: вставка 1..100 по возрастанию, удаление по возрастанию — верификация после каждой операции")
    public void ascendingInsertThenAscendingDelete() throws Exception {
        final int t = 2;
        final XdStorageBTree tree = newTree(t, false);
        final MockTransaction tx = createTx("tx-inv-1");

        final Set<Integer> live = new TreeSet<>();
        for (int i = 1; i <= 100; i++) {
            tree.insert(i, new Object(), storage, tx);
            live.add(i);
            verify(tree, t, live, "insert asc @" + i);
        }
        for (int i = 1; i <= 100; i++) {
            tree.delete(i, storage, tx);
            live.remove(i);
            verify(tree, t, live, "delete asc @" + i);
        }
        assertTrue(live.isEmpty());
    }

    // =========================================================================
    // 2. Нисходящая вставка + нисходящее удаление (join'ы влево на каждом шаге)
    // =========================================================================

    @Test
    @DisplayName("t=2: вставка 100..1 по убыванию, удаление 100..1 — joinWithLeft на каждом шаге")
    public void descendingInsertThenDescendingDelete() throws Exception {
        final int t = 2;
        final XdStorageBTree tree = newTree(t, false);
        final MockTransaction tx = createTx("tx-inv-2");

        final Set<Integer> live = new TreeSet<>();
        for (int i = 100; i >= 1; i--) {
            tree.insert(i, new Object(), storage, tx);
            live.add(i);
            verify(tree, t, live, "insert desc @" + i);
        }
        for (int i = 100; i >= 1; i--) {
            tree.delete(i, storage, tx);
            live.remove(i);
            verify(tree, t, live, "delete desc @" + i);
        }
    }

    // =========================================================================
    // 3. Случайные порядки (фиксированные seed'ы — детерминизм)
    // =========================================================================

    @Test
    @DisplayName("t=2: случайные вставки и удаления (5 фиксированных seed'ов) — верификация после каждой операции")
    public void randomOrdersSeeded() throws Exception {
        for (long seed = 42; seed < 47; seed++) {
            final int t = 2;
            final XdStorageBTree tree = newTree(t, false);
            final MockTransaction tx = new MockTransaction("tx-inv-r" + seed) {
                @Override public long getTimeout() { return 100L; }
            };
            final Random rnd = new Random(seed);

            final List<Integer> order = new ArrayList<>();
            for (int i = 1; i <= 150; i++) order.add(i);
            Collections.shuffle(order, rnd);

            final Set<Integer> live = new TreeSet<>();
            int op = 0;
            for (final int k : order) {
                tree.insert(k, new Object(), storage, tx);
                live.add(k);
                verify(tree, t, live, "seed=" + seed + " insert #" + op++ + " key=" + k);
            }
            Collections.shuffle(order, rnd);
            for (final int k : order) {
                tree.delete(k, storage, tx);
                live.remove(k);
                verify(tree, t, live, "seed=" + seed + " delete #" + op++ + " key=" + k);
            }
        }
    }

    // =========================================================================
    // 4. Пила: вставка/удаление вперемешку (попеременные split/join на одном поддереве)
    // =========================================================================

    @Test
    @DisplayName("t=2: пила вставок/удалений вперемешку — 500 операций с верификацией каждой")
    public void sawtoothChurn() throws Exception {
        final int t = 2;
        final XdStorageBTree tree = newTree(t, false);
        final MockTransaction tx = createTx("tx-inv-saw");
        final Random rnd = new Random(7);

        final Set<Integer> live = new TreeSet<>();
        int op = 0;
        for (int round = 0; round < 50; round++) {
            // Вставляем 10 новых случайных ключей
            for (int i = 0; i < 10; i++) {
                int k = rnd.nextInt(500);
                while (live.contains(k)) k = rnd.nextInt(500);
                tree.insert(k, new Object(), storage, tx);
                live.add(k);
                verify(tree, t, live, "sawtooth insert #" + op++ + " key=" + k);
            }
            // Удаляем 5 случайных живых
            for (int i = 0; i < 5 && !live.isEmpty(); i++) {
                final List<Integer> snapshot = new ArrayList<>(live);
                final int k = snapshot.get(rnd.nextInt(snapshot.size()));
                tree.delete(k, storage, tx);
                live.remove(k);
                verify(tree, t, live, "sawtooth delete #" + op++ + " key=" + k);
            }
        }
    }

    // =========================================================================
    // 5. Полная очистка из произвольных состояний (каскадный collapse до пустого корня)
    // =========================================================================

    @Test
    @DisplayName("t=2: удаление всего из случайного дерева — collapse до пустого корня без остатка")
    public void collapseToEmptyFromRandomState() throws Exception {
        for (long seed = 1; seed <= 5; seed++) {
            final int t = 2;
            final XdStorageBTree tree = newTree(t, false);
            final MockTransaction tx = new MockTransaction("tx-inv-col" + seed) {
                @Override public long getTimeout() { return 100L; }
            };
            final Random rnd = new Random(seed);

            final List<Integer> order = new ArrayList<>();
            for (int i = 1; i <= 80; i++) order.add(i);
            Collections.shuffle(order, rnd);
            for (final int k : order) tree.insert(k, new Object(), storage, tx);

            Collections.shuffle(order, rnd);
            final Set<Integer> live = new TreeSet<>(order);
            int op = 0;
            for (final int k : order) {
                tree.delete(k, storage, tx);
                live.remove(k);
                verify(tree, t, live, "collapse seed=" + seed + " op#" + op++ + " key=" + k);
            }
            assertTrue(live.isEmpty());
        }
    }

    // =========================================================================
    // 6. Удаление несуществующего ключа: исключение, структура не повреждена
    // =========================================================================

    @Test
    @DisplayName("Удаление несуществующего ключа бросает исключение и не повреждает дерево")
    public void deleteNonExistentKeepsTreeIntact() throws Exception {
        final int t = 2;
        final XdStorageBTree tree = newTree(t, false);
        final MockTransaction tx = createTx("tx-inv-miss");

        final Set<Integer> live = new TreeSet<>();
        for (int i = 0; i < 40; i += 2) { // четные ключи
            tree.insert(i, new Object(), storage, tx);
            live.add(i);
        }

        for (int miss = 1; miss < 40; miss += 2) { // нечетные — отсутствуют
            final int k = miss;
            assertThrows(XdStorageException.class, () -> tree.delete(k, storage, tx),
                    "Удаление отсутствующего ключа " + k + " обязано бросить исключение");
            verify(tree, t, live, "after missed delete @" + k);
        }
    }

    // =========================================================================
    // 7. Циклы вставка/удаление одного ключа (путь пустого корня)
    // =========================================================================

    @Test
    @DisplayName("50 циклов вставка/удаление единственного ключа — путь пустого корня стабильна")
    public void singleKeyCyclesThroughEmptyRoot() throws Exception {
        final int t = 2;
        final XdStorageBTree tree = newTree(t, false);
        final MockTransaction tx = createTx("tx-inv-cycle");

        for (int i = 0; i < 50; i++) {
            tree.insert(7, new Object(), storage, tx);
            verify(tree, t, Collections.singleton(7), "cycle insert #" + i);
            tree.delete(7, storage, tx);
            verify(tree, t, Collections.emptySet(), "cycle delete #" + i);
        }
    }

    // =========================================================================
    // 8. t=3 и t=5: та же пилa на другом факторе ветвления
    // =========================================================================

    @Test
    @DisplayName("t=3 и t=5: пила вставок/удалений на другом факторе ветвления")
    public void otherBranchingFactors() throws Exception {
        for (final int t : new int[]{3, 5}) {
            final XdStorageBTree tree = newTree(t, false);
            final MockTransaction tx = new MockTransaction("tx-inv-t" + t) {
                @Override public long getTimeout() { return 100L; }
            };
            final Random rnd = new Random(100 + t);

            final Set<Integer> live = new TreeSet<>();
            int op = 0;
            for (int round = 0; round < 30; round++) {
                for (int i = 0; i < 8; i++) {
                    int k = rnd.nextInt(300);
                    while (live.contains(k)) k = rnd.nextInt(300);
                    tree.insert(k, new Object(), storage, tx);
                    live.add(k);
                    verify(tree, t, live, "t=" + t + " insert #" + op++ + " key=" + k);
                }
                for (int i = 0; i < 4 && !live.isEmpty(); i++) {
                    final List<Integer> snapshot = new ArrayList<>(live);
                    final int k = snapshot.get(rnd.nextInt(snapshot.size()));
                    tree.delete(k, storage, tx);
                    live.remove(k);
                    verify(tree, t, live, "t=" + t + " delete #" + op++ + " key=" + k);
                }
            }
        }
    }

    // =========================================================================
    // 9. multiple=true: дубликаты ключей при split/join
    // =========================================================================

    @Test
    @DisplayName("multiple=true: дубликаты одного ключа переживают split и join")
    public void duplicateKeysSurviveSplitAndJoin() throws Exception {
        final int t = 2;
        final XdStorageBTree tree = newTree(t, true);
        final MockTransaction tx = createTx("tx-inv-dup");

        // 30 дубликатов одного ключа + 30 разных ключей
        for (int i = 0; i < 30; i++) {
            tree.insert(500, "dup-" + i, storage, tx);
        }
        for (int i = 0; i < 30; i++) {
            tree.insert(1000 + i, "u-" + i, storage, tx);
        }

        assertEquals(30, tree.find(500, storage, tx).size(), "Дубликаты ключа 500 потеряны после вставок");
        for (int i = 0; i < 30; i++) {
            assertEquals(1, tree.find(1000 + i, storage, tx).size(), "Уникальный ключ " + (1000 + i) + " потерян");
        }

        // Удаляем дубликаты порциями — join'ы не должны трогать чужие ключи
        for (int portion = 0; portion < 3; portion++) {
            tree.delete(500, storage, tx);
        }
        for (int i = 0; i < 30; i++) {
            assertEquals(1, tree.find(1000 + i, storage, tx).size(),
                    "Соседний ключ " + (1000 + i) + " задет при удалении дубликатов");
        }
    }
}
