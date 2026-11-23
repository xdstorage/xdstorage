package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Инварианты B+ Дерева на сериях дубликатов ключей И значений (MULTI-индексы, R-46).
 *
 * <p>Отличие от {@link XdStorageBTreeSplitJoinInvariantTest}: там множества БЕЗ дубликатов
 * (Set ключей), здесь — мультимножества пар (ключ, значение): один ключ много раз, в том
 * числе полностью идентичные пары, снимаемые точечным delete по одной. Структурная часть —
 * канонические инварианты B+ дерева: балансировка (все листья на одной глубине),
 * children == keys + 1 у маршрутизаторов, паритет keys/objects в листах, насыщение нод
 * [t-1 .. 2t-1] у не-корня, сортировка внутри нод и вдоль цепи листьев, совпадение множеств
 * листьев при спуске из корня и при обходе цепи от firstLeaf, корректные parent-указатели.
 * Плюс инвариант полного демонтижа: после пурджа всех пар root == null, firstLeaf == null и
 * хранилище нод пусто (ни одной ноды-призрака). Плюс конкурентный инвариант: параллельные
 * вставки и точечные удаления дубликатов из нескольких потоков (дерево потокобезопасно).</p>
 */
@DisplayName("B+Tree Инварианты: дубликаты ключей и значений (MULTI, R-46)")
public class XdStorageBTreeDuplicateInvariantTest extends AbstractBTreeIntegrationTest {

    private static final int T = 5;

    /** Результат полного обхода дерева: пары цепи листьев + множества листьев двумя путями. */
    private static final class TreeScan {
        final List<String> chainPairs = new ArrayList<>();
        final Set<Object> leafIdsViaRoot = new HashSet<>();
        final Set<Object> leafIdsViaChain = new HashSet<>();
        int leafDepth = -1;
    }

    /** Полный структурный скан: спуск из корня + обход цепи листьев, все инварианты формы. */
    private TreeScan deepScan(final String context) {
        final TreeScan scan = new TreeScan();
        final XdStorageBTreeNode root = btree.getRoot();
        if (root != null) {
            descend(root, 0, scan, new HashSet<>(), context);
        }

        XdStorageBTreeNode cur = btree.getFirstLeaf();
        int guard = 0;
        String prevPair = null;
        while (cur != null) {
            assertTrue(scan.leafIdsViaChain.add(System.identityHashCode(cur)),
                    context + ": цикл в цепочке листьев (лист " + cur.getId() + " встречен повторно)");
            final List<Comparable> keys = cur.getKeys();
            final List<Object> objects = cur.getObjects();
            assertEquals(keys.size(), objects.size(),
                    context + ": лист " + cur.getId() + " рассинхронизирован (keys != objects)");
            for (int i = 0; i < keys.size(); ++i) {
                final String pair = keys.get(i) + "#" + objects.get(i);
                if (prevPair != null) {
                    final String prevKey = prevPair.substring(0, prevPair.indexOf('#'));
                    assertTrue(prevKey.compareTo((String) keys.get(i)) <= 0,
                            context + ": нарушение сортировки вдоль цепи: " + prevKey + " > " + keys.get(i));
                }
                scan.chainPairs.add(pair);
                prevPair = pair;
            }
            cur = cur.getNextTreeNodeOnThisLevel();
            assertTrue(++guard < 100_000, context + ": бесконечная цепочка листьев");
        }

        assertEquals(scan.leafIdsViaRoot, scan.leafIdsViaChain,
                context + ": рассинхрон корня и цепи листьев (split/join потерял или отсоединил лист)");
        return scan;
    }

    private void descend(final XdStorageBTreeNode node, final int depth, final TreeScan scan,
                         final Set<Object> visited, final String context) {
        assertTrue(visited.add(System.identityHashCode(node)),
                context + ": цикл/дубль в структуре при спуске из корня");

        final List<Comparable> keys = node.getKeys();
        for (int i = 1; i < keys.size(); ++i) {
            assertTrue(keys.get(i - 1).compareTo(keys.get(i)) <= 0,
                    context + ": ключи ноды " + node.getId() + " не отсортированы: "
                            + keys.get(i - 1) + " > " + keys.get(i));
        }

        if (node.getChildren().isEmpty()) {
            if (scan.leafDepth == -1) {
                scan.leafDepth = depth;
            }
            assertEquals(scan.leafDepth, depth,
                    context + ": дерево не сбалансировано — листья на разных глубинах");
            assertTrue(scan.leafIdsViaRoot.add(System.identityHashCode(node)),
                    context + ": лист продублирован в дереве");
            checkOccupancy(node, depth == 0, context);
            return;
        }

        assertEquals(keys.size() + 1, node.getChildren().size(),
                context + ": инвариант children == keys + 1 нарушен у ноды " + node.getId());
        checkOccupancy(node, depth == 0, context);
        for (final XdStorageBTreeNode child : node.getChildren()) {
            assertNotNull(child, context + ": null-ребенок у ноды " + node.getId());
            assertSame(node, child.getParent(),
                    context + ": parent-указатель ребенка " + child.getId()
                            + " не ссылается на родителя " + node.getId());
            descend(child, depth + 1, scan, visited, context);
        }
    }

    /** Насыщение: не-корневые ноды держат [t-1 .. 2t-1] ключей, корень не переполнен. */
    private void checkOccupancy(final XdStorageBTreeNode node, final boolean isRoot, final String context) {
        if (!isRoot) {
            assertTrue(node.getKeys().size() >= T - 1,
                    context + ": недозаполненная нода " + node.getId()
                            + " keys=" + node.getKeys().size() + " < t-1=" + (T - 1));
        }
        assertTrue(node.getKeys().size() <= 2 * T - 1,
                context + ": переполненная нода " + node.getId()
                        + " keys=" + node.getKeys().size() + " > 2t-1=" + (2 * T - 1));
    }

    /** Считает мультимножество пар в цепи листьев и сверяет с ожидаемым счётчиком. */
    private void verifyMultiset(final Map<String, Integer> expected, final String context) {
        final TreeScan scan = deepScan(context);
        final Map<String, Integer> actual = new LinkedHashMap<>();
        for (final String pair : scan.chainPairs) {
            actual.merge(pair, 1, Integer::sum);
        }
        assertEquals(expected, actual,
                context + ": мультимножество пар в дереве не равно ожидаемому (потеря/лишняя пара после точечных " +
                        "удалений)");
    }

    private void verifyFullyCollapsed(final String context) {
        assertNull(btree.getRoot(), context + ": корень не занулился после полного пурджа");
        assertNull(btree.getFirstLeaf(), context + ": firstLeaf не занулился после полного пурджа");
        assertTrue(nodeStorage.isEmpty(),
                context + ": в хранилище остались ноды-призраки после полного пурджа: " + nodeStorage.keySet());
    }

    private static String pairId(final String key, final String value) {
        return key + "#" + value;
    }

    /** Вычитает одну пару из ожидаемого мультимножества, убирая запись при достижении нуля. */
    private static void removeExpected(final Map<String, Integer> expected, final String id) {
        final int left = expected.merge(id, -1, Integer::sum);
        if (left <= 0) {
            expected.remove(id);
        }
    }

    @Test
    @DisplayName("Инвариант 1: структура и мультимножество серий дубликатов через сплиты и join-ы (t=5)")
    // TRIAGE (2026-11-23): ENABLED — «переполнение ноды» исправлено (OVERFLOW,
    // docs/tasks/2.2.0/33): chain-ветка вставки мутировала терминальный лист диапазона
    // дубликатов без сплит-проверки, лист рос безгранично; сплит теперь запускается
    // на nextLeaf в кадре chain-ветки. Инвариант 5 держит полный структурный гейт.
    public void testDuplicateSeriesStructuralInvariantsAcrossSplitsAndJoins() throws Exception {
        final Map<String, Integer> expected = new HashMap<>();

        // Перемешанная вставка трёх серий разной длины: 12 (split), 3, 25 (многоярусный split)
        final List<String[]> plan = new ArrayList<>();
        for (int i = 1; i <= 25; ++i) {
            if (i <= 12) { plan.add(new String[]{"alpha", "a_" + i}); }
            if (i <= 3) { plan.add(new String[]{"beta", "b_" + i}); }
            plan.add(new String[]{"gamma", "g_" + i});
        }
        plan.add(new String[]{"delta", "d_1"});
        Collections.shuffle(plan, new Random(7));
        for (final String[] kv : plan) {
            btree.insert(kv[0], kv[1], storageFacade, transaction);
            expected.merge(pairId(kv[0], kv[1]), 1, Integer::sum);
        }
        verifyMultiset(expected, "после вставки серий");
        assertEquals(12, btree.find("alpha", storageFacade, transaction).size());
        assertEquals(25, btree.find("gamma", storageFacade, transaction).size());

        // Частичный пурдж в шафле: половина каждой серии
        final List<String[]> victims = new ArrayList<>();
        for (int i = 1; i <= 25; i += 2) {
            if (i <= 12) { victims.add(new String[]{"alpha", "a_" + i}); }
            victims.add(new String[]{"gamma", "g_" + i});
        }
        Collections.shuffle(victims, new Random(11));
        for (final String[] kv : victims) {
            btree.delete(kv[0], kv[1], storageFacade, transaction);
            removeExpected(expected, pairId(kv[0], kv[1]));
        }
        verifyMultiset(expected, "после половины точечных удалений");

        // Громкий отказ на отсутствующую пару — и дерево не повреждается
        assertThrows(XdStorageException.class,
                () -> btree.delete("alpha", "a_1", storageFacade, transaction),
                "удаление уже удалённой пары обязано громко падать");
        verifyMultiset(expected, "после попытки удаления отсутствующей пары");

        // Полная зачистка в шафле
        final List<String[]> rest = new ArrayList<>();
        for (int i = 1; i <= 25; ++i) {
            if (i <= 12) { rest.add(new String[]{"alpha", "a_" + i}); }
            if (i <= 3) { rest.add(new String[]{"beta", "b_" + i}); }
            rest.add(new String[]{"gamma", "g_" + i});
        }
        rest.add(new String[]{"delta", "d_1"});
        Collections.shuffle(rest, new Random(13));
        for (final String[] kv : rest) {
            final String id = pairId(kv[0], kv[1]);
            if (expected.containsKey(id)) {
                btree.delete(kv[0], kv[1], storageFacade, transaction);
                removeExpected(expected, id);
            }
        }
        verifyMultiset(new HashMap<>(), "после полного пурджа");
        verifyFullyCollapsed("после полного пурджа");
    }

    @Test
    @DisplayName("Инвариант 2: полностью идентичные пары (ключ, значение) удаляются строго по одной")
    public void testIdenticalPairsRemovedOneByOne() throws Exception {
        final String key = "dup_key";
        final String sameValue = "identical_payload";
        for (int i = 0; i < 10; ++i) {
            btree.insert(key, sameValue, storageFacade, transaction);
        }
        assertEquals(10, btree.find(key, storageFacade, transaction).size(),
                "десять идентичных пар обязаны храниться все");

        for (int remaining = 9; remaining >= 0; --remaining) {
            btree.delete(key, sameValue, storageFacade, transaction);
            assertEquals(remaining, btree.find(key, storageFacade, transaction).size(),
                    "после снятия одной идентичной пары должно остаться ровно " + remaining);
        }
        verifyFullyCollapsed("после поштучного снятия идентичных пар");
    }

    @Test
    @DisplayName("Инвариант 3: 20 ключей по 9 дубликатов, полный пурдж без нод-призраков")
    // TRIAGE (2026-11-23): ENABLED — «переполнение ноды» исправлено (OVERFLOW,
    // docs/tasks/2.2.0/33): см. triage-комментарий Инварианта 1.
    public void testManyKeysEachWithDuplicatesFullPurge() throws Exception {
        final int keysCount = 20;
        final int duplicatesPerKey = 9; // 2t-1: ровно на границе сплита
        final List<String[]> all = new ArrayList<>();
        for (int k = 0; k < keysCount; ++k) {
            for (int d = 0; d < duplicatesPerKey; ++d) {
                all.add(new String[]{"key_" + k, "val_" + k + "_" + d});
            }
        }
        Collections.shuffle(all, new Random(42));
        for (final String[] kv : all) {
            btree.insert(kv[0], kv[1], storageFacade, transaction);
        }

        final Map<String, Integer> expected = new HashMap<>();
        for (final String[] kv : all) {
            expected.merge(pairId(kv[0], kv[1]), 1, Integer::sum);
        }
        verifyMultiset(expected, "после вставки 180 пар");

        Collections.shuffle(all, new Random(99));
        for (final String[] kv : all) {
            btree.delete(kv[0], kv[1], storageFacade, transaction);
        }
        for (int k = 0; k < keysCount; ++k) {
            assertTrue(btree.find("key_" + k, storageFacade, transaction).isEmpty(),
                    "серия key_" + k + " не зачистилась до конца");
        }
        verifyFullyCollapsed("после пурджа 180 пар в шафле");
    }

    @Test
    @DisplayName("Инвариант 4: конкурентные вставки и точечные удаления дубликатов из потоков без потерь")
    public void testConcurrentDuplicateInsertAndTargetedDeleteIsThreadSafe() throws Exception {
        final int hotKeys = 4;
        final int pairsPerKey = 50;

        final List<String[]> all = new ArrayList<>();
        for (int k = 0; k < hotKeys; ++k) {
            for (int d = 1; d <= pairsPerKey; ++d) {
                all.add(new String[]{"hot_" + k, "payload_" + k + "_" + d});
            }
        }
        Collections.shuffle(all, new Random(5));

        final List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicLong txSeq = new AtomicLong();
        all.parallelStream().forEach(kv -> {
            try {
                btree.insert(kv[0], kv[1], storageFacade, workerTx(txSeq.incrementAndGet()));
            } catch (final Throwable t) {
                errors.add(t);
            }
        });
        assertTrue(errors.isEmpty(), "конкурентные вставки дубликатов упали: " + errors);

        for (int k = 0; k < hotKeys; ++k) {
            assertEquals(pairsPerKey, btree.find("hot_" + k, storageFacade, transaction).size(),
                    "после конкурентных вставок серия hot_" + k + " потеряла пары");
        }

        Collections.shuffle(all, new Random(6));
        all.parallelStream().forEach(kv -> {
            try {
                btree.delete(kv[0], kv[1], storageFacade, workerTx(txSeq.incrementAndGet()));
            } catch (final Throwable t) {
                errors.add(t);
            }
        });
        assertTrue(errors.isEmpty(), "конкурентные точечные удаления дубликатов упали: " + errors);

        for (int k = 0; k < hotKeys; ++k) {
            assertTrue(btree.find("hot_" + k, storageFacade, transaction).isEmpty(),
                    "после конкурентного пурджа серия hot_" + k + " не пуста");
        }
        verifyFullyCollapsed("после конкурентного полного пурджа");
    }

    /** Контент-скан цепи листьев: мультимножество пар + сортировка вдоль цепи + паритет keys/objects. */
    private Map<String, Integer> scanChainMultiset(final String context) {
        final Map<String, Integer> actual = new LinkedHashMap<>();
        XdStorageBTreeNode cur = btree.getFirstLeaf();
        int guard = 0;
        String prevPair = null;
        while (cur != null) {
            final List<Comparable> keys = cur.getKeys();
            final List<Object> objects = cur.getObjects();
            assertEquals(keys.size(), objects.size(),
                    context + ": лист " + cur.getId() + " рассинхронизирован (keys != objects)");
            for (int i = 0; i < keys.size(); ++i) {
                final String pair = keys.get(i) + "#" + objects.get(i);
                if (prevPair != null) {
                    final String prevKey = prevPair.substring(0, prevPair.indexOf('#'));
                    assertTrue(prevKey.compareTo((String) keys.get(i)) <= 0,
                            context + ": нарушение сортировки вдоль цепи: " + prevKey + " > " + keys.get(i));
                }
                actual.merge(pair, 1, Integer::sum);
                prevPair = pair;
            }
            cur = cur.getNextTreeNodeOnThisLevel();
            assertTrue(++guard < 100_000, context + ": бесконечная цепочка листьев");
        }
        return actual;
    }

    /** Контент-гейт DUPRACE-прохода: мультимножество пар цепи. */
    private void verifyChainContent(final Map<String, Integer> expected, final String context) {
        assertEquals(expected, scanChainMultiset(context),
                context + ": мультимножество пар в цепи не равно ожидаемому (потеря/лишняя пара)");
    }

    @Test
    @DisplayName("Инвариант 5 (DUPRACE): серия ключа через несколько листьев — точечное удаление находит пару в " +
            "ЛЮБОМ листе диапазона")
    public void testTargetedDeleteFindsPairAcrossMultiLeafDuplicateSpan() throws Exception {
        final String targetKey = "span_a";
        final String neighborKey = "span_b";
        final int perKey = 30; // t=5 → лист держит до 2t-1=9 пар; 30 пар → диапазон ключа из ≥4 листьев

        final List<String[]> plan = new ArrayList<>();
        for (int i = 1; i <= perKey; ++i) {
            plan.add(new String[]{targetKey, "a_" + i});
            plan.add(new String[]{neighborKey, "b_" + i});
        }
        Collections.shuffle(plan, new Random(21));
        for (final String[] kv : plan) {
            btree.insert(kv[0], kv[1], storageFacade, transaction);
        }
        assertEquals(perKey, btree.find(targetKey, storageFacade, transaction).size(),
                "серия target потеряла пары при вставке");
        assertEquals(perKey, btree.find(neighborKey, storageFacade, transaction).size(),
                "соседняя серия потеряла пары при вставке");

        final Map<String, Integer> expected = new HashMap<>();
        for (final String[] kv : plan) {
            expected.merge(pairId(kv[0], kv[1]), 1, Integer::sum);
        }

        // ПОЛНЫЙ структурный гейт (verifyMultiset = deepScan: баланс, occupancy [t-1..2t-1],
        // children == keys + 1, паритет, сортировка цепи): переполнение листов серийными
        // дубликатами исправлено (OVERFLOW, docs/tasks/2.2.0/33 — сплит терминального листа
        // chain-ветки), поэтому гейт контента поднят обратно до структурного скана.

        // ОБРАТНЫЙ порядок вставки: значения серии отсортированы по листьям (вставка сортирует
        // дубликаты по значению), a_30 живёт в ХВОСТОВОМ листе диапазона, а спуск приземляется
        // в ПЕРВЫЙ лист — каждая добыча требует прохода по цепи листьев (DUPRACE fix), включая
        // ребаланс дренируемых хвостовых листьев внутри прохода.
        for (int i = perKey; i >= 1; --i) {
            btree.delete(targetKey, "a_" + i, storageFacade, transaction);
            removeExpected(expected, pairId(targetKey, "a_" + i));
            assertEquals(i - 1, btree.find(targetKey, storageFacade, transaction).size(),
                    "после удаления a_" + i + " в серии осталось не " + (i - 1));
        }
        verifyMultiset(expected, "после снятия серии target: соседняя серия не тронута, структура валидна");
        assertEquals(perKey, btree.find(neighborKey, storageFacade, transaction).size(),
                "соседняя серия потеряла пары при проходе по диапазону target");

        // Честный отказ на отсутствующей паре очищенного диапазона (проход по цепи должен
        // остановиться на границе диапазона и громко отказаться — не зациклиться и не ретраить)
        assertThrows(XdStorageException.class,
                () -> btree.delete(targetKey, "a_1", storageFacade, transaction),
                "удаление отсутствующей пары очищенного диапазона обязано громко падать");
        verifyMultiset(expected, "после попытки удаления отсутствующей пары");

        // Полная зачистка соседней серии — тоже обратный порядок, её диапазон тоже многолистовой
        for (int i = perKey; i >= 1; --i) {
            btree.delete(neighborKey, "b_" + i, storageFacade, transaction);
            removeExpected(expected, pairId(neighborKey, "b_" + i));
        }
        verifyMultiset(new HashMap<>(), "после полного пурджа");
        verifyFullyCollapsed("после полного пурджа");
    }

    /** Рабочая транзакция потока — по образцу харнесса, уникальный id на воркера. */
    private static XdStorageTransaction workerTx(final long seq) {
        return new XdStorageTransaction(null, "THREAD_" + seq, null, 5000L, "TX_" + seq) {
            @Override public void startCriticalSection(boolean read) {}
            @Override public void finishCriticalSection() {}
        };
    }
}
