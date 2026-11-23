package io.github.xdstorage.factories;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("2. Тестирование глубокого копирования сложных структур данных (Массивы/Коллекции)")
public class XdStorageSmartClonerDataStructuresTest extends AbstractClonerTest {

    @Test
    @DisplayName("cloneAndWrapArray() должен корректно копировать и изолировать массивы примитивов")
    public void testArrayProcessor_PrimitiveArray_ShouldCloneAndIsolate() throws Exception {
        int[] srcArray = new int[]{10, 20, 30};

        Object cloneObj = XdStorageClonerArrayProcessor.cloneAndWrapArray(srcArray, mockStorage, mockTx, smartCloner);

        assertNotNull(cloneObj);
        assertTrue(cloneObj.getClass().isArray());
        assertNotSame(srcArray, cloneObj, "Массив обязан быть физически скопирован в куче Java!");
        assertArrayEquals(srcArray, (int[]) cloneObj);
    }

    @Test
    @DisplayName("unwrapAndCloneArray() должен корректно восстанавливать многомерные массивы")
    public void testArrayProcessor_MultiDimensionalArray_ShouldUnwrapCleanly() throws Exception {
        String[][] srcMatrix = new String[][]{{"A", "B"}, {"C", "D"}};

        Object cloneObj = XdStorageClonerArrayProcessor.unwrapAndCloneArray(srcMatrix, smartCloner);

        assertNotNull(cloneObj);
        String[][] clonedMatrix = (String[][]) cloneObj;
        assertNotSame(srcMatrix, clonedMatrix);
        assertArrayEquals(srcMatrix[0], clonedMatrix[0]);
        assertArrayEquals(srcMatrix[1], clonedMatrix[1]);
    }

    @Test
    @DisplayName("cloneAndWrapCollection() обязан клонировать стейт стандартных списков Java")
    public void testCollectionProcessor_List_ShouldCloneDeeply() throws Exception {
        List<String> srcList = new ArrayList<>(Arrays.asList("Galaxy", "Universe"));

        Object result = XdStorageClonerCollectionProcessor.cloneAndWrapCollection(srcList, mockStorage, mockTx,
                smartCloner);

        assertTrue(result instanceof List);
        List<?> clonedList = (List<?>) result;
        assertNotSame(srcList, clonedList);
        assertEquals(srcList.size(), clonedList.size());
        assertEquals("Galaxy", clonedList.get(0));
    }

    @Test
    @DisplayName("cloneAndWrapMap() должен изолировать ключи и значения ассоциативных карт")
    public void testCollectionProcessor_Map_ShouldIsolateState() throws Exception {
        Map<String, Date> srcMap = new HashMap<>();
        Date originalDate = new Date();
        srcMap.put("timestamp", originalDate);

        Object result = XdStorageClonerCollectionProcessor.cloneAndWrapMap(srcMap, mockStorage, mockTx, smartCloner);

        assertTrue(result instanceof Map);
        Map<?, ?> clonedMap = (Map<?, ?>) result;
        assertNotSame(srcMap, clonedMap);

        Date clonedDate = (Date) clonedMap.get("timestamp");
        assertNotSame(originalDate, clonedDate, "Объекты дат класса java.util.Date обязаны глубоко переклонироваться!");
        assertEquals(originalDate.getTime(), clonedDate.getTime());
    }
}
