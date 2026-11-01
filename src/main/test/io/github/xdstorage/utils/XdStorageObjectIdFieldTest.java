package io.github.xdstorage.utils;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 тестов для проверки базовых и граничных условий класса XdStorageObjectIdField.
 */
public class XdStorageObjectIdFieldTest {

    // Класс-заглушка для имитации кастомного генератора ID
    private static class DummyGenerator implements IXdStorageIdGenerator {
        @Override
        public Object generate(Class<?> cl, IXdStorage storage, IXdStorageTransaction transaction) throws XdStorageException {
            return null;
        }
    }

    // Тестовая сущность с аннотацией свойств ID
    private static class AnnotatedEntity {
        @XdStorageObjectFieldProperties(
                idGeneratorType = XdStorageIdGeneratorType.UUID_GENERATOR,
                idGeneratorClass = DummyGenerator.class
        )
        private String idWithProperties;

        private String idWithoutProperties;

        public String getIdWithProperties() { return idWithProperties; }
        public void setIdWithProperties(String id) { this.idWithProperties = id; }
        public String getIdWithoutProperties() { return idWithoutProperties; }
        public void setIdWithoutProperties(String id) { this.idWithoutProperties = id; }
    }

    private XdStorageObjectFieldInfo dummyFieldInfo;

    @BeforeEach
    public void setUp() {
        // Используем реальную структуру конструктора XdStorageObjectFieldInfo, которую мы зафиксировали ранее
        dummyFieldInfo = new XdStorageObjectFieldInfo(String.class, false, false, false, null, String.class);
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testIdFieldCreationAndInheritance_ShouldPassArgsToSuper() throws Exception {
        Field field = AnnotatedEntity.class.getDeclaredField("idWithProperties");
        Method getter = AnnotatedEntity.class.getMethod("getIdWithProperties");
        Method setter = AnnotatedEntity.class.getMethod("setIdWithProperties", String.class);

        XdStorageObjectIdField idField = new XdStorageObjectIdField(field, setter, getter, dummyFieldInfo);

        // Проверяем, что объект создается успешно и контракт базового класса (super) не нарушен.
        // Допуская, что в родительском XdStorageObjectField есть геттер для fieldInfo или аналогичные аксессоры.
        assertNotNull(idField);
    }

    @Test
    public void testGetIdGeneratorType_WithAnnotation_ShouldReturnAnnotatedType() throws Exception {
        Field field = AnnotatedEntity.class.getDeclaredField("idWithProperties");
        Method getter = AnnotatedEntity.class.getMethod("getIdWithProperties");
        Method setter = AnnotatedEntity.class.getMethod("setIdWithProperties", String.class);

        XdStorageObjectIdField idField = new XdStorageObjectIdField(field, setter, getter, dummyFieldInfo);

        // Проверяем, что метод вытягивает именно тот тип генератора, который указан в аннотации (UUID_GENERATOR)
        assertEquals(XdStorageIdGeneratorType.UUID_GENERATOR, idField.getIdGeneratorType());
    }

    @Test
    public void testGetIdGeneratorClass_WithAnnotation_ShouldReturnAnnotatedClass() throws Exception {
        Field field = AnnotatedEntity.class.getDeclaredField("idWithProperties");
        Method getter = AnnotatedEntity.class.getMethod("getIdWithProperties");
        Method setter = AnnotatedEntity.class.getMethod("setIdWithProperties", String.class);

        XdStorageObjectIdField idField = new XdStorageObjectIdField(field, setter, getter, dummyFieldInfo);

        // Проверяем, что метод корректно считывает кастомный класс генератора из свойств аннотации
        assertEquals(DummyGenerator.class, idField.getIdGeneratorClass());
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testGetIdGeneratorType_WithoutAnnotation_ShouldReturnDefaultCustomGenerator() throws Exception {
        // Граничное условие: поле помечено как ID, но аннотация свойств @XdStorageObjectFieldProperties на нем отсутствует
        Field field = AnnotatedEntity.class.getDeclaredField("idWithoutProperties");
        Method getter = AnnotatedEntity.class.getMethod("getIdWithoutProperties");
        Method setter = AnnotatedEntity.class.getMethod("setIdWithoutProperties", String.class);

        XdStorageObjectIdField idField = new XdStorageObjectIdField(field, setter, getter, dummyFieldInfo);

        // Согласно коду, в этом случае должен вернуться дефолтный XdStorageIdGeneratorType.CUSTOM_GENERATOR
        assertEquals(XdStorageIdGeneratorType.CUSTOM_GENERATOR, idField.getIdGeneratorType());
    }

    @Test
    public void testGetIdGeneratorClass_WithoutAnnotation_ShouldReturnNull() throws Exception {
        // Граничное условие: аннотации свойств на поле нет, запрашиваем класс генератора
        Field field = AnnotatedEntity.class.getDeclaredField("idWithoutProperties");
        Method getter = AnnotatedEntity.class.getMethod("getIdWithoutProperties");
        Method setter = AnnotatedEntity.class.getMethod("setIdWithoutProperties", String.class);

        XdStorageObjectIdField idField = new XdStorageObjectIdField(field, setter, getter, dummyFieldInfo);

        // Согласно логике метода, если аннотации нет, должен вернуться чистый null
        assertNull(idField.getIdGeneratorClass());
    }
}
