package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("API Инварианты: Барьеры удаления Foreign Keys (Обязаны упасть)")
public class XdStorageForeignKeyDeletionTest {

    private static final String STORAGE_PATH = "./target/test_fk_deletion_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_delete_test", STORAGE_PATH, 128, FilesFormat.YAML);

        // Преднаполняем базу связанной структурой для тестов удаления
        IXdStorageTransaction tx = storage.beginTransaction();
        Parent p = new Parent();
        p.setId("master-parent");
        Child c = new Child();
        c.setId("slave-child");

        p.addChild(c);
        c.setParent(p);

        storage.save(p, tx);
        tx.commit();
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
    @DisplayName("Инвариант: Удаление только родительского объекта при наличии живой ссылки в дочернем объекте должно выбрасывать исключение")
    public void testFK_DeleteParentAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Parent p = storage.load(Parent.class, "master-parent", tx);
        assertNotNull(p);

        // ТРЕБОВАНИЕ: Попытка удалить родителя без зачистки ссылки в ребенке (@XdStorageParentObject) обязана упасть!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.delete(p, tx);
            tx.commit();
        }, "🚨 СБОЙ ЦЕЛОСТНОСТИ! СУБД позволила удалить родительский объект, оставив дочерний объект сиротой!");

        tx.rollback();
    }

    @Test
    @DisplayName("Инвариант: Удаление дочернего объекта без удаления ссылки из коллекции родителя должно выбрасывать исключение")
    public void testFK_DeleteChildAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Child c = storage.load(Child.class, "slave-child", tx);
        assertNotNull(c);

        // ТРЕБОВАНИЕ: Попытка удалить дочерний объект, когда родитель (@XdStorageAutoSave) все еще ссылается на него, обязана упасть!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.delete(c, tx);
            tx.commit();
        }, "🚨 СБОЙ ЦЕЛОСТНОСТИ! СУБД позволила удалить дочерний объект, оставив висячую ссылку в коллекции родителя!");

        tx.rollback();
    }

    @Test
    @DisplayName("Сценарий коммита: Совместное каскадное удаление и родителя, и дочернего объекта в одной транзакции должно проходить успешно")
    public void testFK_DeleteBothInOneTransaction_ShouldSucceed() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Parent p = storage.load(Parent.class, "master-parent", tx);
        Child c = storage.load(Child.class, "slave-child", tx);

        // Порядок канонического удаления: сначала дочерний, потом родительский
        storage.delete(c, tx);
        storage.delete(p, tx);

        // Так как удалены оба, Foreign Key не нарушен, коммит обязан пройти гладко!
        assertDoesNotThrow(() -> tx.commit());
    }
}
