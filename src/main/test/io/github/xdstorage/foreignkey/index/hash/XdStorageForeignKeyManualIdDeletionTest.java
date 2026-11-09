package io.github.xdstorage.foreignkey.index.hash;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.index.hash.manual.HashIndexChildManualId;
import io.github.xdstorage.entities.foreignkeys.index.hash.manual.HashIndexParentManualId;
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

@DisplayName("API Инварианты: Барьеры удаления Foreign Keys на хэш-индексах с РУЧНЫМ заданием ID")
public class XdStorageForeignKeyManualIdDeletionTest {

    private static final String STORAGE_PATH = "./target/test_fk_hash_manualid_deletion_db";
    private IXdFileStorage storage;
    private String storageName;
    private String uniquePath;

    // Идентификаторы жестко фиксируются непосредственно в логике теста
    private final Long manualParentId = 111111L;
    private final Long manualChildId = 222222L;

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
        storageName = "fk_hash_manualid_test_" + UUID.randomUUID().toString().replace("-", "");
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
        HashIndexParentManualId p = new HashIndexParentManualId();
        p.setId(manualParentId);

        HashIndexChildManualId c = new HashIndexChildManualId();
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
    public void testFK_Hash_ManualId_DeleteParentAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        HashIndexParentManualId parentInDb = storage.load(HashIndexParentManualId.class, manualParentId, tx);

        // ТРЕБОВАНИЕ: Наличие записи в reverse Hash-индексе по ручному Long-ключу должно ветировать удаление!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.delete(parentInDb, tx);
            tx.commit();
        }, "🚨 СУБД позволила удалить родительский объект по ручному ID, оставив ребенка сиротой!");

        tx.rollback();
    }

    @Test
    @DisplayName("Сценарий: Совместное каскадное удаление по ручным числовым ID должно завершаться успехом")
    public void testFK_Hash_ManualId_DeleteBoth_ShouldSucceed() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        HashIndexParentManualId p = storage.load(HashIndexParentManualId.class, manualParentId, tx);
        HashIndexChildManualId c = storage.load(HashIndexChildManualId.class, manualChildId, tx);

        // Удаляем канонически — сначала зависимый, потом родительский
        storage.delete(c, tx);
        storage.delete(p, tx);

        assertDoesNotThrow(() -> tx.commit());
    }
}
