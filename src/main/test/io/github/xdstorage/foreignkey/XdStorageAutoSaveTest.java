package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.Item;
import io.github.xdstorage.entities.foreignkeys.Order;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("API Инварианты: Каскадное сохранение @XdStorageAutoSave")
public class XdStorageAutoSaveTest {

    private static final String STORAGE_PATH = "./target/test_autosave_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("autosave_test", STORAGE_PATH, 128, FilesFormat.YAML);
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
    @DisplayName("Вставка родителя должна каскадно генерировать ID и сохранять дочерние объекты")
    public void testAutoSave_InsertCascade_ShouldPopulateChild() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        io.github.xdstorage.entities.foreignkeys.Order order = new io.github.xdstorage.entities.foreignkeys.Order();
        order.setId("order-100");

        Item item = new Item(null, "Processor_CPU"); // ID пустой, должен сгенерироваться СУБД
        order.addItem(item);

        // Сохраняем только родителя. Аннотация @XdStorageAutoSave обязана запустить каскад
        storage.save(order, tx);

        tx.commit();

        // Проверяем в новой транзакции чтения, долетели ли байты дочернего объекта на диск
        IXdStorageTransaction readTx = storage.beginTransaction();
        Collection<Item> itemsInDb = storage.load(Item.class, readTx);

        assertEquals(1, itemsInDb.size(), "Дочерний объект не был каскадно сохранен в БД!");
        assertNotNull(itemsInDb.iterator().next().getId(), "СУБД не сгенерировала ID для каскадного объекта!");
        readTx.commit();
    }

    @Test
    @DisplayName("Ролбэк родительской транзакции должен полностью аннулировать каскадные дочерние вставки")
    public void testAutoSave_Rollback_ShouldCancelCascade() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        io.github.xdstorage.entities.foreignkeys.Order order = new io.github.xdstorage.entities.foreignkeys.Order();
        order.setId("order-200");
        order.addItem(new Item(null, "RAM_Memory"));

        storage.save(order, tx);
        tx.rollback(); // Откатываем

        IXdStorageTransaction checkTx = storage.beginTransaction();
        assertTrue(storage.load(Order.class, checkTx).isEmpty());
        assertTrue(storage.load(Item.class, checkTx).isEmpty(), "Дочерний каскадный объект утек на диск после ролбэка!");
        checkTx.commit();
    }
}
