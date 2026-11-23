package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Экстремальный стресс-тест: Гигантские конфигурации B+Tree (t=50, 10000+ элементов)")
@Tag("stress")
public class XdStorageBTreeGiantMassiveStressTest {

    private IXdStorage mockStorage;
    private IXdStorageTransaction mockTx;
    private XdStorageBTree bTree;

    @BeforeEach
    public void setUp() throws Exception {
        mockStorage = mock(IXdStorage.class);
        // Заставляем мок мгновенно возвращать те же объекты In-Place из оперативной памяти
        doAnswer(invocation -> invocation.getArgument(0))
                .when(mockStorage).load((Class<Object>) any(), any());

        mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(30000L); // Большой таймаут для гигантских проходов

        // Инициализируем уникальное дерево с ОГРOМНЫМ фактором ветвления t=50!
        // Максимальная емкость одной ноды = 99 ключей, 100 детей.
        XdStorageBTreeId id = new XdStorageBTreeId(Integer.class, "giant_stress_index");
        bTree = new XdStorageBTree(id, false, 50);
    }

    @Test
    @DisplayName("Штурм гигантского массива: 10 000 вставок и каскадных удалений (Защита от слепоты маршрутизаторов)")
    public void testBTree_GiantArray50NodesLoad_ShouldNotLoseSingleKey() throws Exception {
        int totalElements = 12000; // Огромный массив, гарантирующий глубокое ветвление этажей
        List<Integer> keys = new ArrayList<>();
        for (int i = 1; i <= totalElements; i++) {
            keys.add(i);
        }

        // Шаг 1: Рандомизируем массив для создания жесткой нелинейной топологии сплитов внутренних этажей
        Collections.shuffle(keys, new Random(1337));

        System.out.println("🚀 Сценарий 1: Лавинообразная вставка " + totalElements + " элементов в дерево t=50...");
        for (Integer key : keys) {
            bTree.insert(key, "Resource_Block_" + key, mockStorage, mockTx);
        }
        System.out.println("✅ Фаза вставки завершена. Проверяем тотальную доступность всех ключей...");

        // Верифицируем доступность каждого элемента. Если дерево "ослепло" — мы поймаем точный потерянный ID!
        for (int i = 1; i <= totalElements; i++) {
            List<Object> res = bTree.find(i, mockStorage, mockTx);
            assertEquals(1, res.size(), "🚨 КЛЮЧ ПОТЕРЯН НА ГИГАНТСКОЙ ВСТАВКЕ! Элемент: " + i);
            assertEquals("Resource_Block_" + i, res.get(0));
        }
        System.out.println("⭐ Все " + totalElements + " элементов успешно найдены! Потерь при вставке нет.");

        // Шаг 2: Хаотичное обновление стейтов
        System.out.println("🔄 Сценарий 2: Массовое хаотичное обновление массивов...");
        Collections.shuffle(keys, new Random(7331));
        for (Integer key : keys) {
            bTree.update(key, "Updated_Block_" + key, mockStorage, mockTx);
        }

        // Шаг 3: Агрессивное каскадное удаление (провоцируем массовое схлопывание гигантских внутренних узлов)
        System.out.println("💥 Сценарий 3: Агрессивное каскадное удаление массива элементов...");
        // Удаляем сначала половину элементов (только четные), чтобы проверить логику Borrow/Join на огромных массивах
        // соседей
        for (int i = 1; i <= totalElements; i++) {
            if (i % 2 == 0) {
                bTree.delete(i, mockStorage, mockTx);
            }
        }

        System.out.println("🔍 Проверяем целостность нечетных ключей после удаления половины дерева...");
        for (int i = 1; i <= totalElements; i++) {
            List<Object> res = bTree.find(i, mockStorage, mockTx);
            if (i % 2 != 0) {
                assertEquals(1, res.size(), "🚨 КЛЮЧ ПОТЕРЯН ПРИ КАСКАДНOМ СЛИЯНИИ ГИГАНТСКИХ НOД! Элемент: " + i);
                assertEquals("Updated_Block_" + i, res.get(0));
            } else {
                assertTrue(res.isEmpty(), "Четный элемент обязан отсутствовать: " + i);
            }
        }

        // Выкашиваем оставшуюся нечетную половину
        System.out.println("🧹 Выкашиваем оставшуюся нечетную половину массива...");
        for (int i = 1; i <= totalElements; i++) {
            if (i % 2 != 0) {
                bTree.delete(i, mockStorage, mockTx);
            }
        }

        // Итоговый инвариант абсолютной зачистки
        assertNull(bTree.getRoot(),
                "После полного пурджа 12 000 элементов гигантский корень дерева обязан обнулиться!");
        System.out.println("🎉 Триумф! Гигантское дерево t=50 полностью очищено без единой потери ключей!");
    }
}
