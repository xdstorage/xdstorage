package io.github.xdstorage.utils;

import io.github.xdstorage.XdStoragePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("2. Спецификации иерархического сканирования метаданных классов и кэша")
public class XdStorageMetadataRegistryTest extends AbstractUtilsTest {

    @Test
    @DisplayName("Проверка XdStorageReflectionFieldScanner: извлечение JavaBeans полей по геттерам/сеттерам")
    public void testFieldScanner_ShouldExtractProperties() {
        Map<String, XdStorageObjectField> fields = XdStorageReflectionFieldScanner.scanFields(SampleEntity.class);

        assertNotNull(fields);
        assertTrue(fields.containsKey("id"));
        assertTrue(fields.containsKey("value"));

        XdStorageObjectField idProperty = fields.get("id");
        SampleEntity testInstance = new SampleEntity();

        // =========================================================================
        // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ ТЕСТА: Избавляемся от несуществующих методов.
        // Поскольку у класса XdStorageObjectField нет геттеров для setter/getter полей,
        // мы проверяем их корректность сквозным вызовом легитимных методов get() и set(),
        // что полностью устраняет любые ошибки компиляции Java символов!
        // =========================================================================
        assertDoesNotThrow(() -> idProperty.set(testInstance, 555L), "Сбой рефлексивного вызова setter.invoke()!");
        assertEquals(555L, (Long)idProperty.get(testInstance), "Сбой рефлексивного вызова getter.invoke()!");
    }

    @Test
    @DisplayName("Проверка кэширования XdStorageClassMetadataRegistry и политики по умолчанию")
    public void testMetadataRegistry_CacheAndPolicy_ShouldBeIdempotent() {
        XdStorageClassInfo info1 = XdStorageClassMetadataRegistry.getClassInfo(SampleEntity.class);
        XdStorageClassInfo info2 = XdStorageClassMetadataRegistry.getClassInfo(SampleEntity.class);

        assertSame(info1, info2, "Реестр метаданных СУБД обязан возвращать строго один и тот же кэшированный инстанс!");
        assertEquals(XdStoragePolicy.StoreWithParentObject, info1.getPolicy());
    }

    @Test
    @DisplayName("Иерархический поиск: сканер обязан находить инкапсулированные поля в суперклассах")
    public void testFieldScanner_SuperclassInheritance_ShouldFindField() {
        // Создаем анонимный подкласс, имитируя иерархию наследования JavaBeans сущности СУБД
        Class<?> extendedClass = new SampleEntity() {}.getClass();

        Map<String, XdStorageObjectField> fields = XdStorageReflectionFieldScanner.scanFields(extendedClass);

        assertTrue(fields.containsKey("id"),
                "Каскадный сканер обязан находить поля, объявленные в родительских классах!");
    }
}
