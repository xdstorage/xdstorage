package io.github.xdstorage.serialization;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционные тесты: Персистентная запись и чтение составных POJO-объектов")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class XdStorageCompositeObjectPersistentTest {

    private static final String STORAGE_PATH = "./target/composite_object_db";
    private static IXdStorage storage = null;

    // Константы для эталонной верификации полей объекта
    private static final long RECORD_ID = 999888777666L;
    private static final String NODE_NAME = "ACID-Composite-Engine-Node-01";
    private static final int NODE_VERSION = 1042;
    private static final double NODE_THRESHOLD = 99.8542;
    private static final boolean NODE_ACTIVE = true;

    @BeforeAll
    public static void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        // Инициализируем настоящее дисковое хранилище
        storage = XdStorageProvider.newOrGetFileStorage("composite_test", STORAGE_PATH, 200);
    }

    @AfterAll
    public static void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_PATH));
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    @Test
    @Order(1)
    @DisplayName("Шаг 1: Транзакционная запись составного POJO-объекта со множеством примитивов на диск")
    public void step1_writeCompositeObjectToDisk() throws Exception {
        System.out.println("=== НАЧАЛО ШАГА 1: ЗАПИСЬ СОСТАВНОГО ОБЪЕКТА ===");

        IXdStorageTransaction writeTx = storage.beginTransaction();
        try {
            // Создаем эталонный объект
            CompositeDataNode node = new CompositeDataNode(
                    RECORD_ID,
                    NODE_NAME,
                    NODE_VERSION,
                    NODE_THRESHOLD,
                    NODE_ACTIVE
            );

            // Сохраняем объект через фасад СУБД
            storage.save(node, writeTx);

            // Закрываем двухфазный коммит и принудительно сбрасываем байты на диск
            writeTx.commit();
            System.out.println(" -> Составной POJO-объект успешно сохранен и закоммичен.");
        } catch (Throwable e) {
            writeTx.rollback();
            System.err.println("🚨 Крах СУБД при физической записи составного объекта:");
            throw e;
        }
    }

    @Test
    @Order(2)
    @DisplayName("Шаг 2: Чтение составного объекта с диска новой транзакцией и побитовая проверка полей")
    public void step2_readAndVerifyCompositeObject() throws Exception {
        System.out.println("=== НАЧАЛО ШАГА 2: ВЕРИФИКАЦИЯ СОСТАВНОГО ОБЪЕКТА ===");

        // Квантовый барьер для завершения операций ввода-вывода файловой системы OS
        Thread.sleep(5);

        IXdStorageTransaction readTx = storage.beginTransaction();
        try {
            // Загружаем коллекцию объектов нашего тестового класса с диска
            Collection<CompositeDataNode> loadedNodes = storage.load(CompositeDataNode.class, readTx);

            assertNotNull(loadedNodes, "СУБД вернула null вместо коллекции составных объектов!");
            assertFalse(loadedNodes.isEmpty(), "🚨 ПОТЕРЯ ОБЪЕКТА! Таблица класса CompositeDataNode пуста на диске.");
            assertEquals(1, loadedNodes.size(), "Количество объектов в выборке не совпадает с ожидаемым!");

            // Извлекаем десериализованный объект из Snapshot-снимка базы
            CompositeDataNode verifiedNode = loadedNodes.iterator().next();
            assertNotNull(verifiedNode, "Считанный объект оказался null!");

            // =========================================================================
            // СКВОЗНАЯ ВЕРИФИКАЦИЯ МАРШАЛИНГА ПОЛЕЙ ПРИМИТИВОВ И ОБЁРТОК:
            // Проверяем каждое поле отдельно, контролируя побайтовое смещение рефлексии
            // =========================================================================
            System.out.println("📊 ПРОВЕРКА ПОБИТОВОЙ ЦЕЛОСТНОСТИ ПОЛЕЙ ОБЪЕКТА:");

            System.out.println("  -> Проверка Long (idgeneration)...");
            assertEquals(RECORD_ID, verifiedNode.getIdgeneration(), "🚨 ИСКАЖЕНИЕ ПОЛЯ LONG (idgeneration)!");

            System.out.println("  -> Проверка String (nodeName)...");
            assertEquals(NODE_NAME, verifiedNode.getNodeName(), "🚨 ИСКАЖЕНИЕ ПОЛЯ STRING (nodeName)!");

            System.out.println("  -> Проверка int (version)...");
            assertEquals(NODE_VERSION, verifiedNode.getVersion(), "🚨 ИСКАЖЕНИЕ ПОЛЯ INT (version)!");

            System.out.println("  -> Проверка double (threshold)...");
            assertEquals(NODE_THRESHOLD, verifiedNode.getThreshold(), 0.00001, "🚨 ИСКАЖЕНИЕ ПОЛЯ DOUBLE (threshold)!");

            System.out.println("  -> Проверка boolean (isActive)...");
            assertEquals(NODE_ACTIVE, verifiedNode.getIsActive(), "🚨 ИСКАЖЕНИЕ ПОЛЯ BOOLEAN (isActive)!");

            readTx.commit();
            System.out.println("🎉 УСПЕХ! Рефлексивный маршалинг составных объектов и примитивных полей работает " +
                    "идеально!");
        } catch (Throwable e) {
            readTx.rollback();
            System.err.println("🚨 Крах СУБД на этапе верификации полей составного объекта:");
            throw e;
        }
    }
}
