package io.github.xdstorage.btree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("B+Tree Интеграция: Точечные сценарии дубликатов")
public class TargetedDuplicateBTreeTest extends AbstractBTreeIntegrationTest {

    @Test
    @DisplayName("1) Вставка и удаление серий из 7, 13 и 17 одинаковых ключей")
    void testTargetedDuplicateSeries() throws Exception {
        // --- ЭТАП 1: Тестируем серию из 7 одинаковых ключей ---
        String key7 = "key_series_7";
        for (int i = 1; i <= 7; i++) {
            btree.insert(key7, "value_7_" + i, storageFacade, transaction);
        }

        List<Object> res7 = btree.find(key7, storageFacade, transaction);
        assertEquals(7, res7.size(), "Должно быть ровно 7 дубликатов ключа");

        // Точечно удаляем 3 элемента из 7
        btree.delete(key7, "value_7_2", storageFacade, transaction);
        btree.delete(key7, "value_7_5", storageFacade, transaction);
        btree.delete(key7, "value_7_7", storageFacade, transaction);

        assertEquals(4, btree.find(key7, storageFacade, transaction).size(), "После удаления должно остаться 4 дубликата");

        // Зачищаем оставшиеся 4
        btree.delete(key7, "value_7_1", storageFacade, transaction);
        btree.delete(key7, "value_7_3", storageFacade, transaction);
        btree.delete(key7, "value_7_4", storageFacade, transaction);
        btree.delete(key7, "value_7_6", storageFacade, transaction);

        assertTrue(btree.find(key7, storageFacade, transaction).isEmpty(), "Серия из 7 ключей должна полностью удалиться");


        // --- ЭТАП 2: Тестируем серию из 13 одинаковых ключей (Спровоцирует split при t=5) ---
        String key13 = "key_series_13";
        for (int i = 1; i <= 13; i++) {
            btree.insert(key13, "value_13_" + i, storageFacade, transaction);
        }

        // Проверяем, что из-за превышения 2t-1 (9 ключей на ноду) корень расщепился, но find() нашел все 13 штук через горизонтальный обход
        List<Object> res13 = btree.find(key13, storageFacade, transaction);
        assertEquals(13, res13.size());

        // Выборочно удаляем элементы, провоцируя обратное заимствование (borrow) или слияние (join)
        for (int i = 1; i <= 13; i++) {
            btree.delete(key13, "value_13_" + i, storageFacade, transaction);
        }
        assertTrue(btree.find(key13, storageFacade, transaction).isEmpty());


        // --- ЭТАП 3: Тестируем серию из 17 одинаковых ключей ---
        String key17 = "key_series_17";
        for (int i = 1; i <= 17; i++) {
            btree.insert(key17, "value_17_" + i, storageFacade, transaction);
        }

        assertEquals(17, btree.find(key17, storageFacade, transaction).size());

        // Тотальное каскадное удаление всей серии
        for (int i = 1; i <= 17; i++) {
            btree.delete(key17, "value_17_" + i, storageFacade, transaction);
        }

        assertTrue(btree.find(key17, storageFacade, transaction).isEmpty());

        // Инвариант полного очищения СУБД: после пурджа всех элементов корень дерева должен схлопнуться в null
        assertNull(btree.getRoot(), "Корень пустого дерева обязан занулиться согласно канону слияния");
    }
}
