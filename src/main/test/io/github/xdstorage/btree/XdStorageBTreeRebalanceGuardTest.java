package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.MockTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-56: регрессионные пины стражей ребалансировки.
 * 1. РЕАЛЬНОЕ повреждение при стабильной версии структуры обязано оставаться
 *    ГРОМКИМ (сэмплированный валидатор не имеет права глотать коррупцию).
 * 2. Счетчики structureVersion/activeMutations не текут и не врут.
 * 3. SPLIT-ORPHAN: сплит, не нашедший себя в родителе, — громкий отказ вместо
 *    молчаливого сиротства листа (right оставался в цепочке, но был недостижим
 *    из корня — корневой канал "рассинхрона структуры").
 */
public class XdStorageBTreeRebalanceGuardTest extends AbstractBTreeTest {

    private XdStorageBTree tree;

    @BeforeEach
    public void setUpTree() {
        tree = new XdStorageBTree(new XdStorageBTreeId(Object.class, "id_index_guard"), false, 3);
    }

    @Test
    @DisplayName("Реальный разрыв цепочки при стабильной версии — громкий отказ валидатора")
    public void validatorThrowsOnRealDivergenceAtStableVersion() throws Exception {
        final MockTransaction tx = createTx("tx-guard-divergence");
        for (int i = 1; i <= 9; ++i) {
            tree.insert(i, "v" + i, storage, tx);
        }

        final long versionBefore = tree.currentStructureVersion();
        assertEquals(0, tree.activeMutationCount(), "Вне мутаций счетчик полетов обязан быть 0");

        // MockStorage не назначает id нодам — присваиваем явно (валидатор различает
        // ноды по id; null-id ломает visited-набор обхода раньше самого разрыва)
        assignIds(tree.getRoot(), new long[]{1});

        // ИСКУШЕНИЕ: лист-сирота в цепочке, недостижимый из корня
        final XdStorageBTreeNode orphan = new XdStorageBTreeNode(tree, null);
        orphan.setId(999L);
        XdStorageBTreeNode last = tree.getFirstLeaf();
        assertNotNull(last);
        while (last.getNextTreeNodeOnThisLevel() != null) {
            last = last.getNextTreeNodeOnThisLevel();
        }
        last.setNextTreeNodeOnThisLevel(orphan);

        // Валидатор работает с КОНКРЕТНЫМ классом транзакции (критические секции)
        final XdStorageTransaction validatorTx = mock(XdStorageTransaction.class);
        when(validatorTx.getTransactionId()).thenReturn("tx-guard-divergence");
        when(validatorTx.getTimeout()).thenReturn(100L);

        final XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> XdStorageBTreeInvariants.validateLeafChain(tree, storage, validatorTx, "guard-test"));

        assertTrue(thrown.getMessage().contains("рассинхрон структуры"),
                "Ожидался дамп расхождения множеств листьев, было: " + thrown.getMessage());
        assertEquals(versionBefore, tree.currentStructureVersion(),
                "Валидатор не мутирует структуру — версия обязана остаться стабильной");
        assertEquals(0, tree.activeMutationCount());
    }

    private static void assignIds(final XdStorageBTreeNode node, final long[] nextId) {
        if (node == null) {
            return;
        }
        if (node.getId() == null) {
            node.setId(nextId[0]++);
        }
        for (final XdStorageBTreeNode child : node.getChildren()) {
            assignIds(child, nextId);
        }
    }

    @Test
    @DisplayName("Счетчики мутаций: версия растет, полетов нет после завершения операций")
    public void mutationCountersTrackOperationsWithoutLeak() throws Exception {
        final MockTransaction tx = createTx("tx-guard-counters");
        final long version0 = tree.currentStructureVersion();

        tree.insert(1, "v1", storage, tx);
        tree.insert(2, "v2", storage, tx);
        tree.delete(2, storage, tx);

        assertEquals(0, tree.activeMutationCount(),
                "После завершения мутаций счетчик полетов обязан вернуться в 0");
        assertTrue(tree.currentStructureVersion() > version0,
                "Мутации обязаны инкрементировать версию структуры");
    }

    @Test
    @DisplayName("SPLIT-ORPHAN: сплит вне родителя — громкий отказ вместо молчаливого сиротства")
    public void splitOrphanIsLoudInsteadOfSilent() throws Exception {
        final MockTransaction tx = createTx("tx-guard-orphan");
        final int t = 4;

        final XdStorageBTreeNode fakeParent = new XdStorageBTreeNode(tree, null);
        fakeParent.setId(1L);
        fakeParent.getKeys().add(1000);
        final XdStorageBTreeNode otherChild = new XdStorageBTreeNode(tree, fakeParent);
        otherChild.setId(2L);
        otherChild.getKeys().add(500);
        otherChild.getObjects().add("other");
        fakeParent.getChildren().add(otherChild);
        tree.setRoot(fakeParent);

        // Полный лист L, ЧУЖОЙ для родителя: parent.children его не содержит
        final XdStorageBTreeNode orphanLeaf = new XdStorageBTreeNode(tree, fakeParent);
        orphanLeaf.setId(100L);
        for (int i = 0; i < 2 * t; ++i) {
            orphanLeaf.getKeys().add(100 + i);
            orphanLeaf.getObjects().add("v" + i);
        }

        // Приватный splitAndInsertIntoParent: лист обязан найти себя в детях родителя.
        // Молчаливый пропуск (до R-56) оставлял right в цепочке, но недостижимым из корня.
        final Method split = XdStorageBTreeNode.class.getDeclaredMethod(
                "splitAndInsertIntoParent", XdStorageBTreeNode.class,
                io.github.xdstorage.IXdStorage.class, io.github.xdstorage.transaction.IXdStorageTransaction.class);
        split.setAccessible(true);

        final XdStorageException thrown = assertThrows(XdStorageException.class,
                () -> {
                    try {
                        split.invoke(orphanLeaf, null, storage, tx);
                    } catch (final java.lang.reflect.InvocationTargetException e) {
                        throw (Exception) e.getCause();
                    }
                });

        assertTrue(thrown.getMessage().contains("cannot find itself in its parent"),
                "Ожидался громкий SPLIT-ORPHAN отказ, было: " + thrown.getMessage());
    }
}
