package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Алгоритмический TDD-тест: Проверка расхождения навигаторов вставки и поиска")
@Tag("stress")
public class XdStorageBTreeMultipleSplitStressTest {

    private IXdStorage mockStorage;
    private IXdStorageTransaction mockTx;

    @BeforeEach
    public void setUp() throws Exception {
        mockStorage = mock(IXdStorage.class);
        mockTx = mock(IXdStorageTransaction.class);
        when(mockTx.getTimeout()).thenReturn(100L);

        // Обучаем мок возвращать ноды In-Place из оперативной памяти для чистоты замера алгоритма
        doAnswer(invocation -> invocation.getArgument(0))
                .when(mockStorage).load((Class<Object>) any(), any());
        doAnswer(invocation -> invocation.getArgument(0)).when(mockStorage).load((Collection<?>) any());
    }

    @Test
    @DisplayName("Воспроизведение бага: Потеря дубликатов ключей при каскадных сплитах внутренних узлов")
    public void testBTree_MultipleKeysSplit_ShouldMaintainRoutingSymmetry() throws Exception {
        // Инициализируем множественное дерево (multiple = true) с фактором t = 2 (макс емкость ноды = 3 ключа)
        XdStorageBTreeId id = new XdStorageBTreeId(String.class, "tdd_multiple_index");
        XdStorageBTree bTree = new XdStorageBTree(id, true, 2);

        // Целевой ключ для проверки дубликатов планет
        final String targetKey = "star-system-id-777";
        final int duplicatesCount = 6; // Количество элементов, гарантирующее сплит листов и выталкивание разделителя в корень

        System.out.println("=== ЗАПУСК ПOШАГOВOГO TDD-ВOСПРOИЗВEДEНИЯ БАГА ===");

        // 1. Вставляем пачку дубликатов. При t=2 четвертая и последующие вставки
        // спровоцируют splitAndCreateNewRoot() и вытолкнут targetKey наверх в роли разделителя!
        for (int i = 0; i < duplicatesCount; i++) {
            bTree.insert(targetKey, "Planet_Resource_Ref_" + i, mockStorage, mockTx);
        }

        // Разбавляем дерево правым граничным ключом, чтобы укрепить правую ветку маршрутизатора
        bTree.insert("star-system-id-999", "Boundary_Planet", mockStorage, mockTx);

        System.out.println("-> Пачка дубликатов вставлена. Структура внутренних разделителей перестроена.");
        System.out.println("-> Выполняем точечный поиск find() по ключу: " + targetKey);

        // 2. Вызываем вертикальный поиск СУБД
        List<Object> searchResult = bTree.find(targetKey, mockStorage, mockTx);

        // =========================================================================
        // КРИТИЧEСКИЙ ИНВАРИАНТ: Из-за рассинхрона знаков (вставка ушла направо,
        // а поиск ушел в самый хвост), на старом коде СУБД найдет Либо 0 элементов,
        // Либо часть данных будет обрезана!
        // =========================================================================
        assertNotNull(searchResult, "Дерево вернуло null вместо коллекции элементов!");

        System.out.println("📊 РEЗУЛЬТАТЫ АЛГOРИТМИЧEСКOГO ТEСТИРOВАНИЯ:");
        System.out.println("🔹 Ожидали дубликатов в Snapshot-снимке: " + duplicatesCount);
        System.out.println("🔹 Фактически извлечено из B+ Дерева: " + searchResult.size());

        assertEquals(duplicatesCount, searchResult.size(),
                "🚨 БАГ ВOСПРOИЗВEДEН! Навигатор поиска ослеп и промахнулся мимо дочерней страницы из-за асимметрии сплита!");

        System.out.println("🎉 ПОБEДА! Тест успешно прошел (Правки работают исправно)!");
    }
}
