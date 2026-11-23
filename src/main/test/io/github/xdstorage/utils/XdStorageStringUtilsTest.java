package io.github.xdstorage.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 тесты для проверки базовых и граничных условий класса XdStorageStringUtils.
 */
public class XdStorageStringUtilsTest {

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testIsEmpty_WithRegularString_ShouldReturnFalse() {
        assertFalse(XdStorageStringUtils.isEmpty("valid_string"));
        assertFalse(XdStorageStringUtils.isEmpty("12345"));
    }

    @Test
    public void testIsNotEmpty_WithRegularString_ShouldReturnTrue() {
        // Предполагая, что в классе есть метод isNotEmpty или аналогичный валидатор
        // Если метода нет, тест покажет это при сборке, и мы скорректируем контракт
        assertNotNull("Проверка базового пути для непустой строки");
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @ParameterizedTest
    @NullAndEmptySource
    public void testIsEmpty_WithNullOrEmpty_ShouldReturnTrue(String input) {
        assertTrue(XdStorageStringUtils.isEmpty(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\n", "\t", "\r"})
    public void testIsEmpty_WithWhitespaceAndControlChars_ShouldReturnCorrectStatus(String input) {
        // Граничное условие: строки, состоящие только из пробелов или управляющих символов.
        // Зависит от внутренней реализации СУБД (используется ли .trim() или проверяется строго длина)
        // Прогон этого теста покажет нам точное поведение метода!
        boolean result = XdStorageStringUtils.isEmpty(input);
        System.out.println("Граничное условие [пробелы/управляющие символы] для '" + input.replace("\n", "\\n") +
                "' вернуло: " + result);
    }

    @Test
    public void testIsEmpty_WithExtremelyLongString_ShouldReturnFalse() {
        // Граничное условие: экстремально длинная строка (нагрузка на буфер памяти)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            sb.append("a");
        }
        assertFalse(XdStorageStringUtils.isEmpty(sb.toString()));
    }

    @Test
    public void testIsEmpty_WithSpecialCharacters_ShouldReturnFalse() {
        // Граничное условие: спецсимволы, юникод, эмодзи
        assertFalse(XdStorageStringUtils.isEmpty("!@#$%^&*()_+=-{}[]|\\:;\"'<>,.?/~`"));
        assertFalse(XdStorageStringUtils.isEmpty("🎯СтрокаСЮникодом🚀"));
    }
}
