package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexParentAutoId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("API Инварианты: Вставка Foreign Keys с Авто-ID СУБД")
public class XdStorageForeignKeyAutoIdInsertionTest {

    private static final String STORAGE_PATH = "./target/test_fk_autoid_insertion_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_autoid_insert_test", STORAGE_PATH, 128, FilesFormat.YAML);
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
    @DisplayName("Сценарий: Каскадная вставка графа с null-идентификаторами должна успешно проходить валидацию FK после генерации ID")
    public void testFK_AutoId_CascadeInsert_ShouldSucceed() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId(); // id = null
        BTreeIndexChildAutoId child = new BTreeIndexChildAutoId();   // id = null

        parent.addChild(child);
        child.setParent(parent);

        // ТРЕБОВАНИЕ: База сгенерирует ID внутри вызова save.
        // FK-менеджер обязан проверить связи на фазе prepareCommit уже по сгенерированным Long ключам!
        assertDoesNotThrow(() -> {
            storage.save(parent, tx);
            tx.commit();
        }, "🚨 Ошибка! СУБД выбросила исключение при каскадной валидации автогенерируемых Foreign Keys!");

        // Проверяем, что ID проставились и сущности связались на диске
        assertNotNull(parent.getId(), "Родителю не был присвоен автоматический ID!");
        assertNotNull(child.getId(), "Ребенку не был присвоен автоматический ID!");
    }

    @Test
    @DisplayName("Инвариант: Сохранение изолированного дочернего объекта с неинициализированным родителем должно падать")
    public void testFK_AutoId_SaveIsolatedChild_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        BTreeIndexChildAutoId isolatedChild = new BTreeIndexChildAutoId();
        BTreeIndexParentAutoId emptyParent = new BTreeIndexParentAutoId(); // Не сохранен в СУБД, id = null
        isolatedChild.setParent(emptyParent);

        // ТРЕБОВАНИЕ: Вставка дочернего элемента, у которого родитель не имеет сгенерированного ID, запрещена!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.save(isolatedChild, tx);
            tx.commit();
        }, "🚨 КРАХ ЦЕЛОСТНОСТИ! СУБД допустила сохранение ChildAutoId, чей родитель не имеет ID!");

        tx.rollback();
    }
}
