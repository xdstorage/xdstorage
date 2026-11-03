package io.github.xdstorage.btree;

import io.github.xdstorage.exceptions.XdStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("stress")
@DisplayName("B+Tree Интеграция: Стресс-тест большого количества дубликатов")
public class MassDuplicatesOrderingBTreeStressTest extends AbstractBTreeIntegrationTest {

    @Test
    @DisplayName("Стресс-тест: Вставка 100 дубликатов в случайном порядке, проверка глобальной сортировки и каскадное удаление")
    void testMassDuplicatesOrderingAndPurge() throws Exception {
        final String targetKey = "mass_duplicate_hot_key";
        final int totalDuplicates = 100;

        // 1. Генерируем 100 уникальных payloads, упорядоченных по значению (например, value_000, value_001...)
        List<String> valuesToSend = new ArrayList<>();
        for (int i = 0; i < totalDuplicates; i++) {
            // Форматируем с ведущими нулями, чтобы лексикографическое сравнение строк совпадало с числовым
            valuesToSend.add(String.format("value_%03d", i));
        }

        // 2. Перемешиваем список, чтобы вставка шла в абсолютно хаотичном порядке
        List<String> shuffledValues = new ArrayList<>(valuesToSend);
        Collections.shuffle(shuffledValues);

        // 3. Массовый накат дубликатов. Дерево будет постоянно двоиться, расти вверх и вбок
        for (String value : shuffledValues) {
            btree.insert(targetKey, value, storageFacade, transaction);
        }

        // 4. Считываем данные через эталонный горизонтальный обход leaf-цепочки
        final List<String> extractedValues = new ArrayList<>();
        btree.read(storageFacade, transaction, new IXdStorageBTreeViewer() {
            @Override
            public void look(Comparable key, Object value) {
                if (key.equals(targetKey)) {
                    extractedValues.add((String) value);
                }
            }
        });

        // 5. ПРОВЕРКА ИНВАРИАНТА 1: Все 100 элементов обязаны вернуться из СУБД
        assertEquals(totalDuplicates, extractedValues.size(), "Часть дубликатов потерялась при каскадных сплитах нод!");

        // 6. ПРОВЕРКА ИНВАРИАНТА 2: Глобальная монотонность по значению (должно быть строго value_000, value_001 ... value_099)
        for (int i = 0; i < totalDuplicates; i++) {
            assertEquals(valuesToSend.get(i), extractedValues.get(i),
                    String.format("Нарушен глобальный порядок дубликатов в цепочке листьев на позиции %d", i));
        }

        // 7. СТРЕСС-УДАЛЕНИЕ: Удаляем элементы в обратном (убывающем) порядке — от value_099 до value_000
        // Это заставит навигатор спуска постоянно сталкиваться с равными разделителями на внутренних этажах
        // и проверять fail-safe бэктрекинг (блок с перехватом "does not exists")
        for (int i = totalDuplicates - 1; i >= 0; i--) {
            final String valueToDelete = valuesToSend.get(i);
            assertDoesNotThrow(() -> {
                btree.delete(targetKey, valueToDelete, storageFacade, transaction);
            }, String.format("Навигатор удаления промахнулся или сломался на элементе %s при каскадном сжатии", valueToDelete));
        }

        // 8. ПРОВЕРКА ИНВАРИАНТА 3: Полная зачистка графа
        // find() должен вернуть пустой список, корень btree должен схлопнуться в null, а хранилище нод — опустеть
        assertTrue(btree.find(targetKey, storageFacade, transaction).isEmpty());
        assertNull(btree.getRoot(), "После полного удаления дубликатов дерево не смогло схлопнуть корень в null");
        assertTrue(nodeStorage.isEmpty(), "В памяти СУБД остались висячие файлы-сироты удаленных страниц B+ дерева");
    }
}
