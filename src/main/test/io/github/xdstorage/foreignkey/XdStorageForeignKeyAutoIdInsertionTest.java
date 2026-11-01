package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.ChildAutoId;
import io.github.xdstorage.entities.foreignkeys.ParentAutoId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

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

        ParentAutoId parent = new ParentAutoId(); // id = null
        ChildAutoId child = new ChildAutoId();   // id = null

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

        ChildAutoId isolatedChild = new ChildAutoId();
        ParentAutoId emptyParent = new ParentAutoId(); // Не сохранен в СУБД, id = null
        isolatedChild.setParent(emptyParent);

        // ТРЕБОВАНИЕ: Вставка дочернего элемента, у которого родитель не имеет сгенерированного ID, запрещена!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.save(isolatedChild, tx);
            tx.commit();
        }, "🚨 КРАХ ЦЕЛОСТНОСТИ! СУБД допустила сохранение ChildAutoId, чей родитель не имеет ID!");

        tx.rollback();
    }
}
