package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("API Инварианты: Вставка и создание Foreign Keys")
@Disabled("нужно тестировать на реальном хранилище")
public class XdStorageForeignKeyInsertionTest {

    private static final String STORAGE_PATH = "./target/test_fk_insertion_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_insert_test", STORAGE_PATH, 128, FilesFormat.YAML);
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
    @DisplayName("Инвариант: Сохранение только дочернего объекта с @XdStorageParentObject без родителя должно выбрасывать исключение")
    public void testFK_SaveChildAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Child isolatedChild = new Child();
        isolatedChild.setId("isolated-child-id");
        // Родитель не задан или не сохранен в этой транзакции

        // ТРЕБОВАНИЕ: Одиночное сохранение дочернего объекта с обратной ссылкой должно порождать крах коммита!
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.save(isolatedChild, tx);
            tx.commit();
        }, "🚨 СУБД пропустила сохранение 'оторванного' дочернего объекта без родительского контекста!");
    }

    @Test
    @DisplayName("Инвариант: Повторное сохранение дубликата пары Parent-Child в FK-индекс должно отвергаться")
    public void testFK_DuplicatePair_ShouldBeRejected() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Parent parent = new Parent();
        parent.setId("parent-root");
        Child child = new Child();
        child.setId("child-leaf");

        parent.addChild(child);
        child.setParent(parent);

        storage.save(parent, tx);

        // Насильно провоцируем повторную вставку той же самой связи
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.save(child, tx); // Повторный проход для того же графа в рамках одной TX
            tx.commit();
        }, "🚨 СУБД допустила дублирование пары ключей в персистентном B+Tree внешних ключей!");
    }
}
