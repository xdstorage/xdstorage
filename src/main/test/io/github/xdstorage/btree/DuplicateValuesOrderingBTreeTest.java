package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("B+Tree Интеграция: Упорядоченность дубликатов по значению")
public class DuplicateValuesOrderingBTreeTest extends AbstractBTreeIntegrationTest {

    @Test
    @DisplayName("Проверка, что при одинаковых ключах вставка упорядочивает элементы по возрастанию их значений")
    void testDuplicatesAreOrderedByValue() throws Exception {
        final String sharedKey = "multi_index_shared_key";

        // Хот-палитра значений: вставляем дубликаты хаотично (не по порядку)
        // Движок B+ дерева обязан применить Comparable-контракт значения внутри листа
        String v1 = "value_B_medium";
        String v2 = "value_A_lowest";
        String v3 = "value_D_highest";
        String v4 = "value_C_high";

        // 1. Хаотичная вставка дубликатов ключа
        btree.insert(sharedKey, v1, storageFacade, transaction); // Б
        btree.insert(sharedKey, v2, storageFacade, transaction); // А (должен уйти в начало листа)
        btree.insert(sharedKey, v3, storageFacade, transaction); // Д (должен уйти в конец листа)
        btree.insert(sharedKey, v4, storageFacade, transaction); // В (должен встать между Б и Д)

        // 2. Извлекаем элементы через btree.read(), который делает честный, упорядоченный
        // обход leaf-цепочки под read-локами нод.
        final List<String> extractedValues = new ArrayList<>();

        btree.read(storageFacade, transaction, new IXdStorageBTreeViewer() {
            @Override
            public void look(Comparable key, Object value) {
                if (key.equals(sharedKey)) {
                    extractedValues.add((String) value);
                }
            }
        });

        // 3. Проверяем размер коллекции
        assertEquals(4, extractedValues.size(), "СУБД потеряла дубликаты при сортировке внутри ноды");

        // 4. Проверяем строгий лексикографический порядок (А -> Б -> В -> Д)
        assertEquals(v2, extractedValues.get(0), "Элемент 'value_A_lowest' должен быть первым в цепочке");
        assertEquals(v1, extractedValues.get(1), "Элемент 'value_B_medium' должен быть вторым в цепочке");
        assertEquals(v4, extractedValues.get(2), "Элемент 'value_C_high' должен быть третьим в цепочке");
        assertEquals(v3, extractedValues.get(3), "Элемент 'value_D_highest' должен быть четвертым в цепочке");

        // 5. Запускаем прецизионное удаление из середины отсортированного списка
        // Проверяем, что findChildAndDeletePair не промахивается мимо листов
        assertDoesNotThrow(() -> {
            btree.delete(sharedKey, v4, storageFacade, transaction); // Удаляем 'value_C_high'
        }, "Точечное удаление дубликата из отсортированного листа выбросило ошибку");

        // Проверяем состав элементов после точечного удаления
        final List<String> postDeleteValues = new ArrayList<>();
        btree.read(storageFacade, transaction, (key, value) -> {
            if (key.equals(sharedKey)) {
                postDeleteValues.add((String) value);
            }
        });

        assertEquals(3, postDeleteValues.size());
        assertEquals(v2, postDeleteValues.get(0));
        assertEquals(v1, postDeleteValues.get(1));
        assertEquals(v3, postDeleteValues.get(2));
    }
}
