package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeNode;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.MockTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Проверка XdStorageSmartCloner: ВСЕ поля B+ Дерева (коэффициент t, multiple,
 * root, firstLeaf, id) обязаны переноситься из клонируемого объекта без потерь.
 *
 * Подозрение: при переносе дерева в кэш коэффициент t теряется (свежий newInstance()
 * в пер-транзакционной обертке имеет t=0), после чего сплиты считают t=0 и падают
 * на getKeys().get(t-1) == get(-1).
 */
@DisplayName("XdStorageSmartCloner: перенос всех полей B+ Дерева без потерь")
public class XdStorageSmartClonerTreeFieldsTest {

    private XdStorageSmartCloner cloner;
    private IXdStorage storage;
    private XdStorageBTree tree;

    private XdStorageTransaction tx(final String id) {
        final XdStorageTransaction tx = mock(XdStorageTransaction.class);
        when(tx.getTransactionId()).thenReturn(id);
        when(tx.getTimestart()).thenReturn(System.nanoTime());
        when(tx.getTimeout()).thenReturn(100L);
        return tx;
    }

    @BeforeEach
    public void setUp() {
        storage = mock(IXdStorage.class);
        final IXdStorageReferenceProvider provider =
                new XdStorageDefaultReferenceProvider(storage);
        cloner = new XdStorageSmartCloner(provider);

        // Дерево со ВСЕМИ значимыми полями: t=7, multiple=true, 2-уровневый граф
        tree = new XdStorageBTree(new XdStorageBTreeId(Integer.class, "fields-test"), true, 7);
        final XdStorageBTreeNode root = new XdStorageBTreeNode(tree, null);
        tree.setRoot(root);
        XdStorageBTreeNode prev = null;
        int key = 1;
        for (int i = 0; i < 3; ++i) {
            final XdStorageBTreeNode leaf = new XdStorageBTreeNode(tree, root);
            for (int j = 0; j < 5; ++j) {
                leaf.getKeys().add(key);
                leaf.getObjects().add("v-" + key);
                key++;
            }
            root.getChildren().add(leaf);
            if (prev == null) {
                tree.setFirstLeaf(leaf);
            } else {
                prev.setNextTreeNodeOnThisLevel(leaf);
            }
            prev = leaf;
        }
        root.getKeys().add(6);
        root.getKeys().add(11);
    }

    private void assertAllFieldsPreserved(final XdStorageBTree copy, final String context) {
        assertNotNull(copy, context + ": результат клонирования null");
        assertEquals(7, copy.getT(), context + ": КОЭФФИЦИЕНТ T ПОТЕРЯН");
        assertTrue(copy.isMultiple(), context + ": флаг multiple потерян");
        assertNotNull(copy.getId(), context + ": id потерян");
        assertNotNull(copy.getRoot(), context + ": root потерян");
        assertNotNull(copy.getFirstLeaf(), context + ": firstLeaf потерян");
        assertSame(tree.getRoot(), copy.getRoot(), context + ": root — ДРУГОЙ инстанс (граф расщеплен)");
        assertSame(tree.getFirstLeaf(), copy.getFirstLeaf(), context + ": firstLeaf — другой инстанс");
        assertEquals(15, chainSize(copy.getFirstLeaf()), context + ": ключи листьев потеряны");
    }

    private int chainSize(final XdStorageBTreeNode leaf) {
        int total = 0;
        XdStorageBTreeNode cur = leaf;
        int guard = 0;
        while (cur != null && guard++ < 1000) {
            total += cur.getKeys().size();
            cur = cur.getNextTreeNodeOnThisLevel();
        }
        return total;
    }

    // =========================================================================
    // 1. cloneAndWrap: канонический путь (инфраструктура -> живой инстанс)
    // =========================================================================

    @Test
    @DisplayName("cloneAndWrap: возвращает копию инстанса дерева (t, multiple, граф — без потерь)")
    public void cloneAndWrapReturnsLiveInstance() throws Exception {
        final Object result = cloner.cloneAndWrap(tree, storage, tx("tx-caw"));

        assertTrue(result instanceof XdStorageBTree, "Результат не дерево: " + result.getClass());
        final XdStorageBTree copy = (XdStorageBTree) result;
        assertNotSame(tree, copy, "cloneAndWrap вернул живой инстанс дерева вместо КОПИИ инстанса");
    }

    // =========================================================================
    // 2. unwrapAndClone: one-level распаковка
    // =========================================================================

    @Test
    @DisplayName("unwrapAndClone: все поля дерева переносятся в результат")
    public void unwrapAndCloneTransfersAllFields() throws Exception {
        final Object result = cloner.unwrapAndClone(tree);

        assertTrue(result instanceof XdStorageBTree, "Результат не дерево: " + result.getClass());
        final XdStorageBTree copy = (XdStorageBTree) result;
        assertAllFieldsPreserved(copy, "unwrapAndClone");
    }

    // =========================================================================
    // 3. fillAndWrap: SimpleWrapper-ссылка становится пасс-тру к канону
    // =========================================================================

    @Test
    @DisplayName("fillAndWrap: SimpleWrapper-ссылка указывает на канонический граф с t без потерь")
    public void fillAndWrapWiresReferenceToCanonical() throws Exception {
        final XdStorageBTree stub = new XdStorageBTree(); // свежий пустой (t=0)
        final XdStorageTransaction tx = tx("tx-faw");

        cloner.fillAndWrap(stub, tree, storage, tx);

        assertEquals(7, stub.getT(), "t потерян при fillAndWrap");
        assertTrue(stub.isMultiple(), "multiple потерян при fillAndWrap");
        assertNotNull(stub.getRoot(), "root потерян при fillAndWrap");
        assertEquals(15, chainSize(stub.getFirstLeaf()), "Цепочка листьев неполная после fillAndWrap");
    }

    // =========================================================================
    // 4. Глубокий клон (cloneObject через MvccGraphCloner)
    // =========================================================================

    @Test
    @DisplayName("cloneObject: глубокий клон дерева сохраняет t/multiple/граф")
    public void cloneObjectPreservesAllFields() {
        final Object result = XdStorageObjectUtils.cloneObject(tree);

        assertTrue(result instanceof XdStorageBTree, "Результат не дерево: " + result.getClass());
        final XdStorageBTree copy = (XdStorageBTree) result;
        assertEquals(7, copy.getT(), "t потерян при глубоком клоне");
        assertTrue(copy.isMultiple(), "multiple потерян");
        assertNotNull(copy.getRoot(), "root потерян");
        assertNotNull(copy.getFirstLeaf(), "firstLeaf потерян");
        assertEquals(15, chainSize(copy.getFirstLeaf()), "Ключи листьев потеряны при глубоком клоне");
    }

    // =========================================================================
    // 5. Полный цикл переноса в кэш: insert -> read -> канонический инстанс с t
    // =========================================================================

    @Test
    @DisplayName("Перенос в кэш: insert+read возвращают дерево с t=7 и полным графом")
    public void cacheRoundTripPreservesT() throws Exception {
        final io.github.xdstorage.utils.XdStorageObjectIdField idField =
                mock(io.github.xdstorage.utils.XdStorageObjectIdField.class);
        when(idField.get(any())).thenAnswer(
                inv -> inv.getArgument(0) instanceof XdStorageBTree
                        ? ((XdStorageBTree) inv.getArgument(0)).getId() : null);
        XdStorageServicesLocator locator = mock(io.github.xdstorage.services.XdStorageServicesLocator.class);
        IXdStorageTransactionManager txManager = mock(IXdStorageTransactionManager.class);
        when(locator.getTransactionsManager()).thenReturn(txManager);
        when(txManager.isTransactionAlive(any())).thenReturn(true);

        final io.github.xdstorage.resource.XdStorageResourceCache cache =
                new io.github.xdstorage.resource.XdStorageResourceCache(
                        locator,
                        XdStorageBTree.class,
                        idField);
        final XdStorageTransaction tx = tx("tx-cache");

        cache.insert(tree, tx);
        final Object readBack = cache.read(tree.getId(), tx);

        assertNotNull(readBack, "Дерево не прочиталось из кэша");
        final XdStorageBTree unwrapped = (XdStorageBTree)
                XdStorageObjectUtils.getWrappedObjectOrSameObject(readBack);
        assertEquals(7, unwrapped.getT(), "T ПОТЕРЯН ПРИ ПЕРЕНОСЕ В КЭШ");
        assertTrue(unwrapped.isMultiple(), "multiple потерян при переносе в кэш");
        assertEquals(15, chainSize(unwrapped.getFirstLeaf()), "Граф листьев потерян при переносе");
    }

}
