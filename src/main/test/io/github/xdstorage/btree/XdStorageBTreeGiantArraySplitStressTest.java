package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Алгоритмический TDD-тест: Массовые сплиты различных ключей при t=5")
@Tag("stress")
public class XdStorageBTreeGiantArraySplitStressTest {

    private IXdStorage mockStorage;
    private IXdStorageTransaction mockTx;

    @BeforeEach
    public void setUp() throws Exception {
        mockStorage = mock(IXdStorage.class);
        mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(10000L); // Повышенный таймаут для глубоких обходов

        // Обучаем мок беспрепятственно возвращать ноды In-Place из оперативной памяти кучи
        doAnswer(invocation -> invocation.getArgument(0)).when(mockStorage).load((Class<Object>) any(), any());
        doAnswer(invocation -> invocation.getArgument(0)).when(mockStorage).load((Collection<?>) any());
    }

    @Test
    @DisplayName("Воспроизведение бага: Крах навигации find() при лавине хаотичных вставок в дерево t=5")
    public void testBTree_MassDifferentKeysSplit_ShouldMaintainRoutingSymmetry() throws Exception {
        // Устанавливаем жесткий фактор ветвления t = 5 по твоему требованию!
        // Максимальная емкость страницы = 9 ключей. 10-й элемент взрывает ноду сплитом.
        XdStorageBTreeId id = new XdStorageBTreeId(Integer.class, "tdd_giant_array_index");
        XdStorageBTree bTree = new XdStorageBTree(id, true, 5);

        System.out.println("=== ЗАПУСК МАССOВOГO TDD-ВOСПРOИЗВEДEНИЯ: t=5 ===");

        // Генерируем массив из 400 различных уникальных ключей, чтобы гарантированно
        // построить глубокое размашистое дерево на 3-4 яруса вверх
        int totalElements = 400;
        List<Integer> keys = new ArrayList<>();
        for (int i = 1; i <= totalElements; i++) {
            keys.add(i);
        }

        // Яростно перемешиваем массив детерминированным сидом (Random 42), полностью имитируя
        // хаотичное поведение HashSet СУБД, раскидывающее сплиты роутеров в случайные стороны
        Collections.shuffle(keys, new Random(42));

        // 1. Пошагово вставляем лавину различных ключей через фасад Б+ Дерева
        for (int i = 0; i < keys.size(); i++) {
            int key = keys.get(i);
            bTree.insert(key, "Payload_Data_For_Key_" + key + "_Step_" + i, mockStorage, mockTx);
        }

        System.out.println("-> Набивка 400 элементов завершена. Промежуточные маршрутизаторы переполнены и " +
                "расщеплены.");
        System.out.println("-> Начинаем сквозную верификацию доступности каждого ключа...");

        // 2. Генеральный скан: Пытаемся найти абсолютно каждый вставленный элемент.
        // На старом коде СУБД (где if (compareResult == 0) косит границы и уводит направо i+1)
        // из-за каскадных сдвигов медиан на верхних этажах роутеры ослепнут, и часть ключей
        // безвозвратно улетит в "слепую зону", выдав размер коллекции Actual: 0!
        for (int i = 0; i < keys.size(); i++) {
            int key = keys.get(i);
            List<Object> searchResult = bTree.find(key, mockStorage, mockTx);

            assertNotNull(searchResult, "Дерево выдало null вместо коллекции для ключа: " + key);

            // Если навигатор промахнулся мимо дочерней страницы — тест с треском упадет, доказав наличие бага!
            assertFalse(searchResult.isEmpty(),
                    "🚨 БАГ МАССOВOЙ ВСТАВКИ ВOСПРOИЗВEДEН! Потерян доступ к ключу '" + key + "' на шаге верификации " +
                            i
                            + " после лавинных сплитов внутренних этажей дерева t=5!");
        }

        System.out.println("🎉 ТРИУМФ! Все 400 хаотичных ключей успешно найдены (Правки работают идеально)!");
    }
}
