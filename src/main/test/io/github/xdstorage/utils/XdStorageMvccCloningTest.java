package io.github.xdstorage.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("1. Спецификации глубокого MVCC-клонирования и Thread-Local контекста")
public class XdStorageMvccCloningTest extends AbstractUtilsTest {

    @Test
    @DisplayName("Проверка контракта XdStorageCloningContext: очистка и корневые вызовы")
    public void testCloningContext_StateManagement() {
        assertTrue(XdStorageCloningContext.isRootCall());

        Map<Object, Object> contextMap = XdStorageCloningContext.get();
        assertNotNull(contextMap);

        contextMap.put("Key", "Clone");
        assertFalse(XdStorageCloningContext.isRootCall());

        XdStorageCloningContext.clear();
        assertTrue(XdStorageCloningContext.isRootCall());
    }

    @Test
    @DisplayName("Клонирование плоских JavaBeans: изоляция стейта полей")
    public void testPojoCloner_FlatEntity_ShouldCloneAndIsolate() {
        SampleEntity src = new SampleEntity();
        src.setId(100L);
        src.setValue("Original_State");

        SampleEntity cloned = XdStorageMvccGraphCloner.cloneObject(src);

        assertNotNull(cloned);
        assertNotSame(src, cloned, "Инстансы в куче Java обязаны быть разными!");
        assertEquals(src.getId(), cloned.getId());
        assertEquals(src.getValue(), cloned.getValue());
    }

    @Test
    @DisplayName("Клонирование коллекций и массивов: глубокая копия java.util.Date")
    public void testCollectionCloner_ArrayAndListWithDates() {
        List<Date> srcList = new ArrayList<>();
        Date originalDate = new Date();
        srcList.add(originalDate);

        Object clonedObj = XdStorageMvccGraphCloner.cloneObject(srcList);

        assertTrue(clonedObj instanceof List);
        List<?> clonedList = (List<?>) clonedObj;
        assertNotSame(srcList, clonedList);

        Date clonedDate = (Date) clonedList.get(0);
        assertNotSame(originalDate, clonedDate,
                "Объекты java.util.Date обязаны глубоко переклонироваться во избежание MVCC-утечек версий!");
        assertEquals(originalDate.getTime(), clonedDate.getTime());
    }

    @Test
    @DisplayName("Защита от StackOverflow: Успешный разрыв циклических перекрестных зависимостей (Circular Dependency)")
    public void testMvccGraphCloner_CircularDependency_ShouldHandleCleanly() {
        CyclicParentEntity parent = new CyclicParentEntity();
        parent.setId(1L);
        CyclicChildEntity child = new CyclicChildEntity();
        child.setId(2L);

        parent.setChild(child);
        child.setParent(parent); // Петля!

        // Запуск верификации: сквозной Thread-Local контекст обязан разорвать цикл
        assertDoesNotThrow(() -> {
            CyclicParentEntity clonedParent = XdStorageMvccGraphCloner.cloneObject(parent);
            assertNotNull(clonedParent);
            assertNotSame(parent, clonedParent);
            assertSame(clonedParent, clonedParent.getChild().getParent(),
                    "Циклический указатель обязан перевязаться на новый клон родителя!");
        }, "Критический сбой! Движок клонирования СУБД свалился в StackOverflowError!");
    }
}
