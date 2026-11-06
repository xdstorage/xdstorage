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

@DisplayName("API Инварианты: Барьеры удаления Foreign Keys с Авто-ID СУБД (Обязан упасть)")
public class XdStorageForeignKeyAutoIdDeletionTest {

    private static final String STORAGE_PATH = "./target/test_fk_autoid_deletion_db";
    private IXdFileStorage storage;
    private Long generatedParentId;
    private Long generatedChildId;

    @BeforeEach
    public void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_autoid_delete_test", STORAGE_PATH, 128, FilesFormat.YAML);

        // Преднаполняем базу связанным графом с автогенерацией ключей
        IXdStorageTransaction tx = storage.beginTransaction();
        ParentAutoId p = new ParentAutoId();
        ChildAutoId c = new ChildAutoId();
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
    @DisplayName("Инвариант: Попытка удалить родителя по сгенерированному ID при живом ребенке должна вызывать эксепшен")
    public void testFK_AutoId_DeleteParentAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        ParentAutoId parentInDb = storage.load(ParentAutoId.class, generatedParentId, tx);
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

        ParentAutoId p = storage.load(ParentAutoId.class, generatedParentId, tx);
        ChildAutoId c = storage.load(ChildAutoId.class, generatedChildId, tx);

        // Удаляем канонически — сначала зависимый, потом родительский
        storage.delete(c, tx);
        storage.delete(p, tx);

        // Связь разорвана чисто, коммит обязан пройти без ошибок
        assertDoesNotThrow(() -> tx.commit());
    }
}
