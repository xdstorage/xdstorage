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

@DisplayName("Алгоритмический стресс-тест: Проверка целостности массивов ключей B+Tree")
@Tag("stress")
public class XdStorageBTreeArrayStressTest {

    private IXdStorage mockStorage;
    private IXdStorageTransaction mockTx;
    private XdStorageBTree bTree;

    @BeforeEach
    public void setUp() throws Exception {
        mockStorage = mock(IXdStorage.class);
        // Обучаем прокси-загрузчик возвращать инстансы нод In-Place из памяти кучи
        doAnswer(invocation -> invocation.getArgument(0))
                .when(mockStorage).load((Class<Object>) any(), any());

        mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(100L);

        // Инициализируем уникальное дерево с минимальным фактором t=2,
        // чтобы ноды сплитились и схлопывались максимально агрессивно (макс. емкость ноды = 3 ключа)
        XdStorageBTreeId id = new XdStorageBTreeId(Integer.class, "array_stress_index");
        bTree = new XdStorageBTree(id, false, 2);
    }

    // =========================================================================
    // ТЕСТ 1: ХАОТИЧЕСКОЕ ПЕРЕМЕШИВАНИЕ (Вставка случайного массива и полный пурдж)
    // =========================================================================
    @Test
    @DisplayName("Стресс-тест массивов: Случайное перемешивание вставок и удалений (Защита от потери разделителей)")
    public void testBTree_RandomArrayShuffle_ShouldMaintainIntegrity() throws Exception {
        int totalElements = 500;
        List<Integer> keys = new ArrayList<>();
        for (int i = 1; i <= totalElements; i++) {
            keys.add(i);
        }

        // Рандомизируем массивы для создания непредсказуемой топологии сплитов внутренних узлов
        Collections.shuffle(keys, new Random(42));

        // 1. Массовая вставка хаотичного массива
        for (Integer key : keys) {
            assertDoesNotThrow(() -> {
                bTree.insert(key, "Block_" + key, mockStorage, mockTx);
            }, "Крах массива ноды при вставке ключа: " + key);
        }

        // Проверяем сквозную видимость абсолютно каждого элемента до единого
        for (int i = 1; i <= totalElements; i++) {
            List<Object> res = bTree.find(i, mockStorage, mockTx);
            assertEquals(1, res.size(), "🚨 КЛЮЧ ПОТЕРЯН ПРИ ВСТАВКЕ/СПЛИТЕ! Элемент: " + i);
            assertEquals("Block_" + i, res.get(0));
        }

        // Перемешиваем массив заново для хаотичного удаления
        Collections.shuffle(keys, new Random(84));

        // 2. Массовое удаление и верификация инвариантов усыхания ярусов
        for (Integer key : keys) {
            assertDoesNotThrow(() -> {
                bTree.delete(key, mockStorage, mockTx);
            }, "Крах массива ноды при удалении ключа: " + key);

            // Проверяем, что удаленный элемент больше не находится
            List<Object> res = bTree.find(key, mockStorage, mockTx);
            assertTrue(res.isEmpty(), "Элемент должен быть удален: " + key);
        }

        // 3. Финальный инвариант полного пурджа
        assertNull(bTree.getRoot(), "После удаления всего массива корень обязан обнулиться!");
    }

    // =========================================================================
    // ТЕСТ 2: ПИЛOОБРАЗНЫЙ ПРЕССИНГ (Вставка пачки -> Удаление половины -> Повтор)
    // =========================================================================
    @Test
    @DisplayName("Стресс-тест массивов: Пилообразная нагрузка на заимствование ключей у соседей (Borrow/Join)")
    public void testBTree_SawtoothArrayLoad_ShouldNotDropKeys() throws Exception {
        // Имитируем каскадные волны, которые происходят при массовых операциях в СУБД
        int waves = 5;
        int batchSize = 100;
        int currentMax = 0;

        for (int w = 0; w < waves; w++) {
            // Волна вставки пачки элементов
            for (int i = 1; i <= batchSize; i++) {
                currentMax++;
                bTree.insert(currentMax, "Data_" + currentMax, mockStorage, mockTx);
            }

            // Волна удаления четных элементов (провоцируем массовое заимствование moveLeft/moveRight)
            for (int i = currentMax - batchSize + 1; i <= currentMax; i++) {
                if (i % 2 == 0) {
                    bTree.delete(i, mockStorage, mockTx);
                }
            }

            // Проверяем целостность нечетных элементов массива
            for (int i = 1; i <= currentMax; i++) {
                List<Object> res = bTree.find(i, mockStorage, mockTx);
                if (i % 2 != 0) {
                    assertEquals(1, res.size(), "🚨 КЛЮЧ ПОТЕРЯН ПРИ ПИЛООБРАЗНОМ СЛИЯНИИ! Элемент: " + i);
                } else {
                    assertTrue(res.isEmpty());
                }
            }
        }
    }
}
