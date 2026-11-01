package io.github.xdstorage.btree;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Unit-тесты API: Точечное удаление пар Ключ + Значение в B+ Дереве")
public class XdStorageBTreeTargetedDeleteApiTest {

    private static final String STORAGE_PATH = "./target/btree_api_delete_db";
    private IXdFileStorage storage;
    private XdStorageBTreeId treeId;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        // Инициализируем быстрое файловое хранилище
        storage = XdStorageProvider.newOrGetFileStorage("api_delete_test", STORAGE_PATH, 128, FilesFormat.YAML);
        treeId = new XdStorageBTreeId(String.class, "api_fk_index");
    }

    @AfterEach
    public void tearDown() {
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
    @DisplayName("Проверка удаления: Удаление конкретного дубликата должно оставлять остальные нетронутыми")
    public void testDelete_SpecificKeyPair_ShouldLeaveOthers() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        // Создаем MULTI-индекс внешних ключей (multiple = true), t = 3
        XdStorageBTree bTree = new XdStorageBTree(treeId, true, 3);
        storage.save(bTree, tx);

        String parentKey = "Parent_1";

        // Связываем одного родителя с тремя разными дочерними значениями
        bTree.insert(parentKey, "Child_A", storage, tx);
        bTree.insert(parentKey, "Child_B", storage, tx);
        bTree.insert(parentKey, "Child_C", storage, tx);

        storage.save(bTree, tx);
        tx.commit();

        // Открываем транзакцию точечного удаления
        IXdStorageTransaction txDelete = storage.beginTransaction();
        XdStorageBTree treeToMutate = storage.load(XdStorageBTree.class, treeId, txDelete);

        // ВЫЗOВ НАШEГO МEТOДA: Удаляем строго конкретную пару
        treeToMutate.delete(parentKey, "Child_B", storage, txDelete);

        storage.update(treeToMutate, txDelete);
        txDelete.commit();

        // Верифицируем результат в независимой Snapshot-сессии чтения
        IXdStorageTransaction txVerify = storage.beginTransaction();
        XdStorageBTree verifyTree = storage.load(XdStorageBTree.class, treeId, txVerify);

        List<Object> values = verifyTree.find(parentKey, storage, txVerify);

        // Ассерты инварианта
        assertNotNull(values, " find() вернул null для живого ключа!");
        assertEquals(2, values.size(), "Количество дубликатов после удаления пары вычислено неверно!");
        assertTrue(values.contains("Child_A"), "Ошибочно удален левый сосед А!");
        assertTrue(values.contains("Child_C"), "Ошибочно удален правый сосед С!");
        assertFalse(values.contains("Child_B"), "🚨 КРАХ МEТOДA! Целевое значение 'Child_B' не было вырезано из листа!");

        txVerify.commit();
    }

    @Test
    @DisplayName("Проверка исключений: Удаление несуществующего значения на валидном ключе должно бросать Loud-отказ")
    public void testDelete_ValidKeyMissingValue_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        XdStorageBTree bTree = new XdStorageBTree(treeId, true, 3);
        storage.save(bTree, tx);

        String parentKey = "Parent_2";
        bTree.insert(parentKey, "Real_Child", storage, tx);
        storage.save(bTree, tx);
        tx.commit();

        // Попытка удалить фантомного ребенка 'Ghost_Child' на живом ключе 'Parent_2'
        IXdStorageTransaction txFail = storage.beginTransaction();
        XdStorageBTree activeTree = storage.load(XdStorageBTree.class, treeId, txFail);

        assertThrows(XdStorageException.class, () -> {
            activeTree.delete(parentKey, "Ghost_Child", storage, txFail);
        }, "🚨 СУБД пропустила no-op удаление пары с несуществующим значением без выброса ошибки!");

        txFail.rollback();
    }

    @Test
    @DisplayName("Проверка исключений: Удаление на полностью отсутствующем ключе должно бросать Loud-отказ")
    public void testDelete_MissingKey_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        XdStorageBTree bTree = new XdStorageBTree(treeId, true, 3);
        storage.save(bTree, tx);
        tx.commit();

        IXdStorageTransaction txFail = storage.beginTransaction();
        XdStorageBTree activeTree = storage.load(XdStorageBTree.class, treeId, txFail);

        assertThrows(XdStorageException.class, () -> {
            activeTree.delete("Ghost_Parent_Key", "Any_Child", storage, txFail);
        }, "🚨 СУБД пропустила удаление на несуществующем ключе без выброса ошибки!");

        txFail.rollback();
    }
}
