package io.github.xdstorage.helpers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 тесты для проверки базовых и граничных условий класса XdStorageDefaultSimpleTypeHelper.
 */
public class XdStorageDefaultSimpleTypeHelperTest {

    private XdStorageDefaultSimpleTypeHelper typeHelper;

    @BeforeEach
    public void setUp() {
        typeHelper = new XdStorageDefaultSimpleTypeHelper();
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testIsSimpleType_WithStandardClasses_ShouldReturnTrue() {
        assertTrue(typeHelper.isSimpleType(Class.class, null));
        assertTrue(typeHelper.isSimpleType(Date.class, null));
        assertTrue(typeHelper.isSimpleType(UUID.class, null));
        assertTrue(typeHelper.isSimpleType(Instant.class, null));
        assertTrue(typeHelper.isSimpleType(LocalDate.class, null));
        assertTrue(typeHelper.isSimpleType(LocalDateTime.class, null));
        assertTrue(typeHelper.isSimpleType(Duration.class, null));
    }

    @Test
    public void testSimpleTypeToString_HappyPath() {
        // Проверяем каноничную маршализацию Class<?>
        assertEquals("java.lang.String", typeHelper.simpleTypeToString(String.class));

        // Проверяем маршализацию даты через Unix Timestamp
        long now = 1711833600000L; // Fixed timestamp
        Date testDate = new Date(now);
        assertEquals(String.valueOf(now), typeHelper.simpleTypeToString(testDate));

        // Проверяем обычный toString() для остальных типов
        assertEquals("123", typeHelper.simpleTypeToString(123));
    }

    @Test
    public void testSimpleTypeFromString_HappyPath() {
        // Базовые типы
        assertEquals(true, typeHelper.simpleTypeFromString(Boolean.class, "true"));
        assertEquals('A', typeHelper.simpleTypeFromString(Character.class, "A"));
        assertEquals("hello", typeHelper.simpleTypeFromString(String.class, "hello"));
        assertEquals(Class.class, typeHelper.simpleTypeFromString(Class.class, "java.lang.Class"));

        // Восстановление даты
        Date restoredDate = (Date) typeHelper.simpleTypeFromString(Date.class, "1711833600000");
        assertNotNull(restoredDate);
        assertEquals(1711833600000L, restoredDate.getTime());

        // Проверка восстановления числовых оберток через рефлексивный конструктор от String
        assertEquals(Long.valueOf(456L), typeHelper.simpleTypeFromString(Long.class, "456"));
        assertEquals(Integer.valueOf(789), typeHelper.simpleTypeFromString(Integer.class, "789"));

        // UUID
        UUID uuid = (UUID) typeHelper.simpleTypeFromString(UUID.class, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        assertNotNull(uuid);
        assertEquals(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"), uuid);

        // Instant
        Instant restoredInstant = (Instant) typeHelper.simpleTypeFromString(Instant.class, "2024-06-15T12:30:00Z");
        assertNotNull(restoredInstant);
        assertEquals(Instant.parse("2024-06-15T12:30:00Z"), restoredInstant);

        // LocalDate
        LocalDate restoredLocalDate = (LocalDate) typeHelper.simpleTypeFromString(LocalDate.class, "2023-11-01");
        assertNotNull(restoredLocalDate);
        assertEquals(LocalDate.parse("2023-11-01"), restoredLocalDate);

        // LocalDateTime
        LocalDateTime restoredLocalDateTime = (LocalDateTime) typeHelper.simpleTypeFromString(LocalDateTime.class,
                "2022-03-15T08:45:30");
        assertNotNull(restoredLocalDateTime);
        assertEquals(LocalDateTime.parse("2022-03-15T08:45:30"), restoredLocalDateTime);

        // Duration
        Duration restoredDuration = (Duration) typeHelper.simpleTypeFromString(Duration.class, "P7DT3H12M45S");
        assertNotNull(restoredDuration);
        assertEquals(Duration.ofDays(7).plusHours(3).plusMinutes(12).plusSeconds(45), restoredDuration);
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testSimpleTypeFromString_WithInvalidClassName_ShouldReturnNullSafely() {
        // Граничное условие: Попытка восстановить несуществующий класс Java
        // Метод должен перехватить ClassNotFoundException внутри и безопасно вернуть null
        Object result = typeHelper.simpleTypeFromString(Class.class, "org.ghost.NonExistentClass");
        assertNull(result);
    }

    @Test
    public void testSimpleTypeFromString_WithInvalidConstructorType_ShouldReturnNullSafely() {
        // Граничное условие: Класс, у которого нет конструктора принимающего String (например, сам Object)
        // Метод должен перехватить Exception рефлексии и вернуть null без падения рантайма СУБД
        Object result = typeHelper.simpleTypeFromString(Object.class, "some_value");
        assertNull(result);
    }

    @Test
    public void testSimpleTypeToString_WithNull_ShouldThrowNullPointerException() {
        // Граничное условие: Передача null в метод конвертации строки.
        // Так как на входе идет instanceof/getClass(), рантайм предсказуемо упадет с NPE
        assertThrows(NullPointerException.class, () -> {
            typeHelper.simpleTypeToString(null);
        });
    }
}
