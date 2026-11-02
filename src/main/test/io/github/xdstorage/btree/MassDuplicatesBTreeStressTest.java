package io.github.xdstorage.btree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("stress")
@DisplayName("B+Tree Интеграция: Нагрузочный стресс-тест MULTI-индекса")
public class MassDuplicatesBTreeStressTest extends AbstractBTreeIntegrationTest {

    @Test
    @DisplayName("2) Нагрузочный тест на лавинообразную вставку дубликатов с каскадным пурджем страниц")
    void testMassDuplicatesLoadAndCleanStress() throws Exception {
        int duplicateCount = 150; // Генерируем 150 дубликатов одного ключа (гарантирует глубокий многоуровневый split ярусов при t=5)
        String hotKey = "hot_key_stress";

        // Массовый накат дубликатов в СУБД
        for (int i = 1; i <= duplicateCount; i++) {
            btree.insert(hotKey, "stress_payload_" + i, storageFacade, transaction);
        }

        // Перемешиваем ключи для имитации хаотичной пользовательской нагрузки на соседних страницах
        String mixedKey = "mixed_key_interleaved";
        for (int i = 1; i <= 50; i++) {
            btree.insert(mixedKey, "mixed_payload_" + i, storageFacade, transaction);
        }

        // Проверяем атомарность среза данных: find() обязан выгрести все 150 элементов, несмотря на расщепление страниц
        List<Object> hotResults = btree.find(hotKey, storageFacade, transaction);
        assertEquals(duplicateCount, hotResults.size(), "СУБД потеряла дубликаты при сплите страниц!");

        assertEquals(50, btree.find(mixedKey, storageFacade, transaction).size());

        // --- ЭТАП ВТОРОЙ: Стресс-пурдж (Удаление в случайном порядке) ---
        // Перемешиваем порядок удаления полезной нагрузки, чтобы создать максимальное количество асимметричных перекосов в B+ дереве
        List<Integer> deleteOrder = new ArrayList<>();
        for (int i = 1; i <= duplicateCount; i++) {
            deleteOrder.add(i);
        }
        Collections.shuffle(deleteOrder);

        for (int index : deleteOrder) {
            // Вызываем прецизионный метод удаления конкретной пары Ключ + Значение
            assertDoesNotThrow(() -> {
                btree.delete(hotKey, "stress_payload_" + index, storageFacade, transaction);
            }, "Схлопывание яруса вызвало IndexOutOfBoundsException или ConcurrentModificationException");
        }

        // Горячая серия полностью зачищена
        assertTrue(btree.find(hotKey, storageFacade, transaction).isEmpty());

        // Зачищаем перемежающуюся серию
        for (int i = 1; i <= 50; i++) {
            btree.delete(mixedKey, "mixed_payload_" + i, storageFacade, transaction);
        }

        // Полный контроль целостности: База абсолютно чиста, граф нод корректно демонтирован
        assertTrue(btree.find(mixedKey, storageFacade, transaction).isEmpty());
        assertNull(btree.getRoot(), "Многоярусная структура дерева не смогла полностью схлопнуться (остался висячий корень-сирота)");
        assertTrue(nodeStorage.isEmpty(), "В репозитории СУБД остались висячие файлы нод-призраков после полного удаления данных");
    }
}
