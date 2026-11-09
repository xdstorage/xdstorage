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

@DisplayName("API Инварианты: Барьеры удаления Foreign Keys с Авто-ID СУБД (Обязан упасть)")
public class XdStorageForeignKeyAutoIdDeletionTest {

    private static final String STORAGE_PATH = "./target/test_fk_autoid_deletion_db";
    private IXdFileStorage storage;
    private Long generatedParentId;
    private Long generatedChildId;

    @BeforeEach
    public void setUp() throws Exception {
        // ensure any prior storage with this name is shut down before re-opening
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_PATH));
        try {
            storage = XdStorageProvider.newOrGetFileStorage("fk_autoid_delete_test", STORAGE_PATH, 128, FilesFormat.YAML);
        } catch (Exception e) {
            deleteDir(new File(STORAGE_PATH));
            throw e;
        }

        // Преднаполняем базу связанным графом с автогенерацией ключей
        IXdStorageTransaction tx = storage.beginTransaction();
        BTreeIndexParentAutoId p = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId c = new BTreeIndexChildAutoId();
        p.addChild(c);
        c.setParent(p);

        storage.save(p, tx);
        tx.commit();

        // Запоминаем сгенерированные базой идентификаторы для тестов удаления
        generatedParentId = p.getId();
        generatedChildId = c.getId();
        assertNotNull(generatedParentId);
        assertNotNull(generatedChildId);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
        // always clean up — even if @BeforeEach failed
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
    @DisplayName("Инвариант: Попытка удалить родителя по сгенерированному ID при живом ребенке должна вызывать эксепшен")
    public void testFK_AutoId_DeleteParentAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        BTreeIndexParentAutoId parentInDb = storage.load(BTreeIndexParentAutoId.class, generatedParentId, tx);
        assertNotNull(parentInDb);

        // ТРЕБОВАНИЕ: Наличие записи в reverse FK-индексе по сгенерированному Long-ключу должно ветировать удаление!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.delete(parentInDb, tx);
            tx.commit();
        }, "🚨 СУБД позволила удалить родительский объект по автогенерированному ID, оставив ребенка сиротой!");

        tx.rollback();
    }

    @Test
    @DisplayName("Сценарий: Совместное каскадное удаление по сгенерированным числовым ID должно завершаться успехом")
    public void testFK_AutoId_DeleteBoth_ShouldSucceed() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        BTreeIndexParentAutoId p = storage.load(BTreeIndexParentAutoId.class, generatedParentId, tx);
        BTreeIndexChildAutoId c = storage.load(BTreeIndexChildAutoId.class, generatedChildId, tx);

        // Удаляем канонически — сначала зависимый, потом родительский
        storage.delete(c, tx);
        storage.delete(p, tx);

        // Связь разорвана чисто, коммит обязан пройти без ошибок
        assertDoesNotThrow(() -> tx.commit());
    }
}
