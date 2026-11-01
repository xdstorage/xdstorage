package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("B+Tree Инварианты: Прецизионное удаление пар Ключ + Значение")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class XdStorageBTreeTargetedDeleteInvariantTest {

    private static final String STORAGE_PATH = "./target/btree_target_delete_db";
    private static IXdStorage storage = null;
    private static XdStorageBTreeId treeId;

    @BeforeAll
    public static void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("target_delete_test", STORAGE_PATH, 128, FilesFormat.YAML);
        treeId = new XdStorageBTreeId(String.class, "fk_index_test");
    }

    @AfterAll
    public static void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_PATH));
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }

    @Test
    @Order(1)
    @DisplayName("Инвариант: Вставка дубликатов и прецизионное удаление без зацепления соседних элементов")
    public void testTargetedDelete_ShouldRemoveOnlySpecificPair() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        // Создаем MULTI-дерево (multiple = true), фактор ветвления t = 2 (минимальный для частых сплитов)
        XdStorageBTree bTree = new XdStorageBTree(treeId, true, 2);
        storage.save(bTree, tx);

        String testKey = "Parent_Key_01";

        // Сажаем 3 дубликата на один и тот же ключ (симуляция 3-х детей у одного родителя)
        bTree.insert(testKey, "Child_Value_A", storage, tx);
        bTree.insert(testKey, "Child_Value_B", storage, tx);
        bTree.insert(testKey, "Child_Value_C", storage, tx);

        storage.save(bTree, tx);
        tx.commit();

        // Открываем транзакцию МУТАЦИИ
        IXdStorageTransaction txMutate = storage.beginTransaction();
        XdStorageBTree loadedTree = storage.load(XdStorageBTree.class, treeId, txMutate);

        // Удаляем строго ЦЕНТРАЛЬНЫЙ элемент дубликатов
        loadedTree.delete(testKey, "Child_Value_B", storage, txMutate);

        storage.update(loadedTree, txMutate);
        txMutate.commit();

        // Верифицируем Snapshot-состояние в новой транзакции чтения
        IXdStorageTransaction txVerify = storage.beginTransaction();
        XdStorageBTree verifyTree = storage.load(XdStorageBTree.class, treeId, txVerify);

        List<Object> remainingValues = verifyTree.find(testKey, storage, txVerify);

        // Инвариант: Должно остаться ровно 2 элемента, Child_Value_B обязан испариться
        assertEquals(2, remainingValues.size(), "СУБД нарушила состав дубликатов при прецизионном удалении!");
        assertTrue(remainingValues.contains("Child_Value_A"), "Потерян соседний левый дубликат А!");
        assertTrue(remainingValues.contains("Child_Value_C"), "Потерян соседний правый дубликат С!");
        assertFalse(remainingValues.contains("Child_Value_B"), "🚨 КРАХ ИНВАРИАНТА! Целевое удаленное значение всё еще содержится в индексе!");

        // Прогоняем встроенный тотальный валидатор Кнута для проверки связности ярусов
        assertDoesNotThrow(() -> {
            XdStorageBTreeInvariants.validateLeafChain(verifyTree, storage, (XdStorageTransaction) txVerify, "TargetedDeleteVerification");
        }, "🚨 СТРУКТУРНОЕ ПОВРЕЖДЕНИЕ! Прецизионное удаление рассинхронизировало пары keys/children или порвало цепь листьев!");

        txVerify.commit();
    }

    @Test
    @Order(2)
    @DisplayName("Инвариант: Попытка удалить несуществующую пару Ключ + Значение должна падать loud-отказом")
    public void testTargetedDelete_NonExistentPair_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        XdStorageBTree bTree = storage.load(XdStorageBTree.class, treeId, tx);

        // Попытка удалить пару, где ключ совпадает, но значение другое, либо ключ отсутствует полностью
        assertThrows(XdStorageException.class, () -> {
            bTree.delete("Parent_Key_01", "Ghost_Value", storage, tx);
        }, "🚨 СБОЙ ОГРАНИЧЕНИЯ! СУБД пропустила no-op удаление фантомной пары ключей без эксепшена!");

        tx.rollback();
    }
}
