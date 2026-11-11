package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexChildManualId;
import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexParentManualId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("API Инварианты: Барьеры удаления Foreign Keys с РУЧНЫМ заданием ID (Обязан упасть)")
public class XdStorageForeignKeyManualIdDeletionTest {

    private static final String STORAGE_PATH = "./target/test_fk_manualid_deletion_db";
    private IXdFileStorage storage;

    // Идентификаторы явно задаются в тесте до начала работы СУБД
    private final Long manualParentId = 55555L;
    private final Long manualChildId = 99999L;

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_manualid_delete_test", STORAGE_PATH, 128, FilesFormat.YAML);

        // Преднаполняем базу связанным графом с явным ручным указанием ID
        IXdStorageTransaction tx = storage.beginTransaction();
        BTreeIndexParentManualId p = new BTreeIndexParentManualId();
        p.setId(manualParentId);

        BTreeIndexChildManualId c = new BTreeIndexChildManualId();
        c.setId(manualChildId);

        p.addChild(c);
        c.setParent(p);

        storage.save(p, tx);
         // каскад @XdStorageAutoSave сохраняет ребёнка; явный save ребёнка после связанного родителя — косяк теста (вердикт мейнтейнера)
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
    @DisplayName("Инвариант: Попытка удалить родителя по явно заданному ID при живом ребенке должна вызывать эксепшен")
    public void testFK_ManualId_DeleteParentAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        BTreeIndexParentManualId parentInDb = storage.load(BTreeIndexParentManualId.class, manualParentId, tx);

        // ТРЕБОВАНИЕ: Наличие записи в reverse B+Tree индексе по ручному Long-ключу должно ветировать удаление!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.delete(parentInDb, tx);
            tx.commit();
        }, "🚨 СУБД позволила удалить родительский объект по ручному ID, оставив ребенка сиротой!");

        tx.rollback();
    }

    @Test
    @DisplayName("Сценарий: Совместное каскадное удаление по ручным числовым ID должно завершаться успехом")
    public void testFK_ManualId_DeleteBoth_ShouldSucceed() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        BTreeIndexParentManualId p = storage.load(BTreeIndexParentManualId.class, manualParentId, tx);
        BTreeIndexChildManualId c = storage.load(BTreeIndexChildManualId.class, manualChildId, tx);

        // Удаляем канонически — сначала зависимый, потом родительский
        storage.delete(c, tx);
        storage.delete(p, tx);

        // Связь разорвана чисто, коммит обязан пройти без ошибок
        assertDoesNotThrow(() -> tx.commit());
    }
}
