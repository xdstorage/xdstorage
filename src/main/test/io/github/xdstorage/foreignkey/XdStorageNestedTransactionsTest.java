package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.DummyNode;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("API Инварианты: Вложенные транзакции (Nested Transactions)")
public class XdStorageNestedTransactionsTest {

    private static final String STORAGE_PATH = "./target/test_nested_tx_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("nested_tx_test", STORAGE_PATH, 128, FilesFormat.YAML);
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
    @DisplayName("Ролбэк вложенной транзакции не должен приводить к аннулированию родительских изменений")
    public void testNestedTx_RollbackShouldNotPoisonParent() throws Exception {
        // 1. Стартуем родителя
        IXdStorageTransaction parentTx = storage.beginTransaction();
        DummyNode pNode = new DummyNode("parent-root");
        storage.save(pNode, parentTx);

        // 2. Открываем вложенную сессию внутри родителя
        IXdStorageTransaction nestedTx = storage.beginTransaction(parentTx);
        DummyNode cNode = new DummyNode("child-leaf");
        storage.save(cNode, nestedTx);

        // 3. Откатываем строго вложенную транзакцию
        storage.rollbackTransaction(nestedTx);

        // 4. Фиксируем родителя — он должен успешно закоммитить свою часть
        assertDoesNotThrow(() -> storage.commitTransaction(parentTx),
                "Ролбэк вложенной транзакции нелегитимно отравил родительский контекст!");

        // Проверяем финальную видимость объектов на диске
        IXdStorageTransaction readTx = storage.beginTransaction();
        assertTrue(storage.has(DummyNode.class, "parent-root", readTx));
        assertFalse(storage.has(DummyNode.class, "child-leaf", readTx), "Объект вложенной транзакции утек на диск вопреки ролбэку!");
        readTx.commit();
    }
}
