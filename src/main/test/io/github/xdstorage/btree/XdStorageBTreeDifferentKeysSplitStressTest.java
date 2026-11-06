package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Алгоритмический TDD-тест: Сплиты внутренних узлов при различных ключах")
@Tag("stress")
public class XdStorageBTreeDifferentKeysSplitStressTest {

    private IXdStorage mockStorage;
    private IXdStorageTransaction mockTx;

    @BeforeEach
    public void setUp() throws Exception {
        mockStorage = mock(IXdStorage.class);
        mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(100L);

        // Обучаем мок возвращать ноды In-Place из оперативной памяти
        doAnswer(invocation -> invocation.getArgument(0)).when(mockStorage).load((Class<Object>) any(), any());
        doAnswer(invocation -> invocation.getArgument(0)).when(mockStorage).load((Collection<?>) any());
    }

    @Test
    @DisplayName("Воспроизведение бага: Потеря маршрутизации различных ключей при сплитах маршрутизаторов")
    public void testBTree_DifferentKeysSplit_ShouldMaintainRoutingSymmetry() throws Exception {
        // Инициализируем множественное дерево (multiple = true), фактор t = 2 (емкость ноды = 3 ключа)
        XdStorageBTreeId id = new XdStorageBTreeId(Integer.class, "tdd_different_keys_index");
        XdStorageBTree bTree = new XdStorageBTree(id, true, 2);

        System.out.println("=== ЗАПУСК TDD-ВOСПРOИЗВEДEНИЯ С РАЗЛИЧНЫМИ КЛЮЧАМИ ===");

        // Специально подобранная неупорядоченная последовательность различных ключей,
        // которая при t=2 гарантированно переполнит листы, вытолкнет медианы в корень
        // и создаст ситуацию, где внутренний ключ-разделитель совпадет со следующим при вставке!
        int[] chaoticKeys = new int[]{10, 20, 30, 40, 50, 20, 25, 35, 15};

        // 1. Пошагово вставляем пачку различных ключей
        for (int i = 0; i < chaoticKeys.length; i++) {
            int key = chaoticKeys[i];
            bTree.insert(key, "Resource_Ref_For_Key_" + key + "_Idx_" + i, mockStorage, mockTx);
            System.out.println(" -> Вставлен ключ: " + key);
        }

        System.out.println("-> Набивка дерева завершена. Проверяем доступность каждого вставленного ключа...");

        // 2. Валидируем Snapshot-видимость каждого элемента.
        // На старом коде из-за асимметрии знаков (if (compareResult == 0) уводящего направо i+1)
        // часть элементов улетит на соседние листы «не в те» ветки, и find() вернет пустой список!
        for (int i = 0; i < chaoticKeys.length; i++) {
            int key = chaoticKeys[i];
            List<Object> searchResult = bTree.find(key, mockStorage, mockTx);

            assertNotNull(searchResult, "Дерево вернуло null вместо коллекции для ключа: " + key);

            // Если из-за перекоса разделителей при вставке различных ключей навигатор ослеп — мы поймаем ассерт!
            assertFalse(searchResult.isEmpty(),
                    "🚨 БАГ ВOСПРOИЗВEДEН! Потерян доступ к ключу '" + key + "' на позиции " + i + " после ребалансировки внутренних узлов!");
        }

        System.out.println("🎉 ПОБEДА! Все различные ключи успешно найдены (Правки работают исправно)!");
    }
}
