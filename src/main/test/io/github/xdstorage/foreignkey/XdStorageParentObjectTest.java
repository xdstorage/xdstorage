package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.Company;
import io.github.xdstorage.entities.foreignkeys.Employee;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("API Инварианты: Обратные ссылки @XdStorageParentObject")
public class XdStorageParentObjectTest {

    private static final String STORAGE_PATH = "./target/test_parent_obj_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("parent_test", STORAGE_PATH, 128, FilesFormat.YAML);
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
    @DisplayName("Загрузка дочернего элемента должна автоматически инжектировать ссылку на родительский объект")
    public void testParentObject_ShouldBackReferenceAutomatically() throws Exception {
        IXdStorageTransaction writeTx = storage.beginTransaction();
        Company comp = new Company();
        comp.setId("company-42");
        Employee emp = new Employee("emp-77");
        emp.setParentCompany(comp);
        comp.addEmployee(emp);

        storage.save(comp, writeTx);
        writeTx.commit();

        // Открываем чистую транзакцию чтения и поднимаем дочерний объект напрямую
        IXdStorageTransaction readTx = storage.beginTransaction();
        Employee loadedEmp = storage.load(Employee.class, "emp-77", readTx);

        assertNotNull(loadedEmp, "Дочерний объект не найден на диске!");
        assertNotNull(loadedEmp.getParentCompany(), "🚨 КРАХ КОНТРАКТА СУБД! Поле @XdStorageParentObject осталось null!");
        assertEquals("company-42", loadedEmp.getParentCompany().getId(), "Ссылка указывает на некорректного родителя!");
        readTx.commit();
    }
}
