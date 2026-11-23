package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Архитектурные тесты инвариантов: Структурные сплиты и слияния B+ Дерева")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class XdStorageBTreeStructureInvariantTest {

    private static final String STORAGE_PATH = "./target/btree_invariant_db";
    private static IXdStorage storage = null;
    private static XdStorageBTreeId treeId;

    private static final int ELEMENTS_COUNT = 300; // Количество элементов для глубокой структуры ярусов
    private static List<Integer> keysSequence;

    @BeforeAll
    public static void setUp() throws Exception {
        deleteDir(new File(STORAGE_PATH));
        // Инициализируем настоящий файловый провайдер СУБД с размером фрагмента 200
        storage = XdStorageProvider.newOrGetFileStorage("btree_invariant", STORAGE_PATH, 200);
        treeId = new XdStorageBTreeId(Integer.class, "invariant_index");

        // Генерируем хаотичную последовательность ключей для создания случайного порядка вставки/удаления
        keysSequence = new ArrayList<>();
        for (int i = 1; i <= ELEMENTS_COUNT; i++) {
            keysSequence.add(i);
        }
        // Используем жесткий сид (seed=42) для 100% воспроизводимости хаоса
        Collections.shuffle(keysSequence, new Random(42));
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
    @DisplayName("Инвариант 1: Создание большой структуры (Массовая случайная вставка и сплиты ярусов, t=3)")
    public void testBTree_MassRandomInsert_ShouldMaintainTopology() throws Exception {
        System.out.println("=== СТАРТ ИНВАРИАНТА 1: НАПОЛНEНИE СТРУКТУРЫ ДEРEВА ===");

        IXdStorageTransaction txWrite = storage.beginTransaction();
        // Создаем множественное дерево индексов (multiple = true), фактор ветвления t = 3
        // Максимальная емкость ноды = 5 ключей. 6-й элемент взрывает страницу сплитом.
        XdStorageBTree bTree = new XdStorageBTree(treeId, true, 3);
        storage.save(bTree, txWrite);

        try {
            for (Integer key : keysSequence) {
                String resourceRef = "Resource_Payload_For_Key_" + key;
                // Вставляем элементы через дерево
                bTree.insert(key, resourceRef, storage, txWrite);
            }

            // Накатываем двухфазный коммит сохранения страниц на диск
            storage.save(bTree, txWrite);
            txWrite.commit();
            System.out.println(String.format(" -> Успешно вставлено %d элементов в случайном порядке.",
                    ELEMENTS_COUNT));
        } catch (Throwable e) {
            txWrite.rollback();
            System.err.println("🚨 Крах дерева на фазе массовой вставки/расщепления страниц:");
            throw e;
        }

        // Проверяем, что абсолютно все элементы доступны для чтения сразу после фиксации структуры
        IXdStorageTransaction txVerify = storage.beginTransaction();
        try {
            XdStorageBTree loadedTree = storage.load(XdStorageBTree.class, treeId, txVerify);
            assertNotNull(loadedTree, "СУБД не смогла загрузить дерево с диска!");

            System.out.println(" -> Проверяем доступность ключей после сплитов...");
            for (Integer key : keysSequence) {
                List<Object> results = loadedTree.find(key, storage, txVerify);
                assertNotNull(results, "Метод find() вернул null для ключа: " + key);
                assertFalse(results.isEmpty(), "🚨 ПОТEРЯ КЛЮЧА! Навигатор поиска промахнулся мимо листа для ключа: " +
                        key);
            }
            txVerify.commit();
            System.out.println("🎉 ИНВАРИАНТ 1 ВЗЯТ: Структура дерева после сплитов абсолютно консистентна!");
        } catch (Throwable e) {
            txVerify.rollback();
            throw e;
        }
    }

    @Test
    @Order(2)
    @DisplayName("Инвариант 2: Полное удаление структуры (Массовое случайное удаление и слияние ярусов, t=3)")
    public void testBTree_MassRandomDelete_ShouldCollapseToEmpty() throws Exception {
        System.out.println("=== СТАРТ ИНВАРИАНТА 2: ПОЛНOE УДАЛEНИE СТРУКТУРЫ ===");

        // Будем удалять элементы в другом случайном порядке, чтобы устроить дереву максимальный стресс
        List<Integer> deleteSequence = new ArrayList<>(keysSequence);
        Collections.shuffle(deleteSequence, new Random(24));

        IXdStorageTransaction txDelete = storage.beginTransaction();
        try {
            XdStorageBTree bTree = storage.load(XdStorageBTree.class, treeId, txDelete);
            assertNotNull(bTree, "Не удалось загрузить дерево для фазы удаления!");

            int deletedCounter = 0;
            for (Integer key : deleteSequence) {
                List<Object> currentValues = bTree.find(key, storage, txDelete);

                if (currentValues != null && !currentValues.isEmpty()) {
                    for (Object cval : currentValues) {
                        // Вызываем физическое удаление ключа из B+ структуры
                        // Это спровоцирует каскадные joinWithLeftNeighbor / joinWithRightNeighbor
                        bTree.delete(key, storage, txDelete);
                    }
                    deletedCounter++;

                    // Каждые 50 удалений проверяем, что оставшиеся в дереве ключи по-прежнему доступны
                    // и навигаторы слияния не «ослепили» промежуточные ярусы
                    if (deletedCounter % 50 == 0) {
                        for (int m = deletedCounter; m < deleteSequence.size(); m++) {
                            Integer remainingKey = deleteSequence.get(m);
                            List<Object> remainingValues = bTree.find(remainingKey, storage, txDelete);
                            assertNotNull(remainingValues, "Сбой навигации при слиянии! Null для ключа: " +
                                    remainingKey);
                            assertFalse(remainingValues.isEmpty(),
                                    "🚨 СТРУКТУРНЫЙ СБОЙ СЛИЯНИЯ! Дерево потеряло доступ "
                                            + "к живому ключу '" + remainingKey + "' после удаления "
                                            + deletedCounter + " элементов!");
                        }
                    }
                }
            }

//            storage.save(bTree, txDelete);
            txDelete.commit();
            System.out.println(" -> Все элементы успешно удалены. Проверяем корень...");
        } catch (Throwable e) {
            txDelete.rollback();
            System.err.println("🚨 Крах дерева на фазе массового слияния/схлопывания страниц:");
            throw e;
        }

        // Финальная верификация: дерево должно быть абсолютно пустым
        IXdStorageTransaction txFinal = storage.beginTransaction();
        try {
            XdStorageBTree finalTree = storage.load(XdStorageBTree.class, treeId, txFinal);
            assertNotNull(finalTree);

            // Проверяем любой ключ — поиск обязан вернуть пустую коллекцию, а не эксепшен
            List<Object> finalResult = finalTree.find(42, storage, txFinal);
            assertTrue(finalResult == null || finalResult.isEmpty(), "Дерево не очистилось до конца!");

            txFinal.commit();
            System.out.println("🎉 ИНВАРИАНТ 2 ВЗЯТ: Полное каскадное удаление структуры выполнено без единой ошибки!");
        } catch (Throwable e) {
            txFinal.rollback();
            throw e;
        }
    }
}
