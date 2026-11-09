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
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("API Инварианты: Барьеры удаления Foreign Keys с РУЧНЫМ заданием ID (Обязан упасть)")
public class XdStorageForeignKeyManualIdDeletionTest {

    private static final String STORAGE_PATH = "./target/test_fk_manualid_deletion_db";
    private IXdFileStorage storage;
    private String storageName;
    private String uniquePath;

    // Идентификаторы явно задаются в тесте до начала работы СУБД
    private final Long manualParentId = 55555L;
    private final Long manualChildId = 99999L;

    private void clearStorageRegistry(String name) {
        try {
            Field storagesField = XdStorageProvider.class.getDeclaredField("storages");
            storagesField.setAccessible(true);
            Map<String, IXdFileStorage> map = (Map<String, IXdFileStorage>) storagesField.get(null);
            map.remove(name);
        } catch (Throwable ignored) {}
    }

    @BeforeEach
    public void setUp() throws Exception {
        // unique name + path to guarantee isolation from all other tests
        storageName = "fk_manualid_test_" + UUID.randomUUID().toString().replace("-", "");
        uniquePath = STORAGE_PATH + "_" + UUID.randomUUID().toString().replace("-", "");

        // ensure any prior storage with this name is shut down before re-opening
        if (storage != null) {
            storage.shutdown();
        }
        clearStorageRegistry(storageName);
        deleteDir(new File(uniquePath));
        storage = XdStorageProvider.newOrGetFileStorage(storageName, uniquePath, 128, FilesFormat.YAML);

        // Преднаполняем базу связанным графом с явным ручным указанием ID
        IXdStorageTransaction tx = storage.beginTransaction();
        BTreeIndexParentManualId p = new BTreeIndexParentManualId();
        p.setId(manualParentId);

        BTreeIndexChildManualId c = new BTreeIndexChildManualId();
        c.setId(manualChildId);

        p.addChild(c);
        c.setParent(p);

        // Save parent — @XdStorageAutoSave on 'children' field cascades child save automatically.
        // Do NOT save child explicitly — that would be a double-insert.
        storage.save(p, tx);
        tx.commit();
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
        // always clean up — even if @BeforeEach failed
        clearStorageRegistry(storageName);
        deleteDir(new File(uniquePath));
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
