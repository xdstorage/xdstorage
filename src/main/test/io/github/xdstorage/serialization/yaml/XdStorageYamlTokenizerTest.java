package io.github.xdstorage.serialization.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Юнит-тест: Проверка построчного самописного YAML-токенизатора")
public class XdStorageYamlTokenizerTest {

    @Test
    @DisplayName("Разбор структуры: проверка подсчета табуляций, ключей и детекции списков")
    public void testTokenizer_ValidYamlStructure_ShouldExtractTokensCorrectly() throws Exception {
        // Имитируем реальный кусок файла XdGalaxy.yaml на табуляциях и одинарных кавычках
        String testYaml =
                "objects:\r\n" +
                        "\t- object:\r\n" +
                        "\t\tclass: 'io.github.xdstorage.entities.XdGalaxy'\r\n" +
                        "\t\tname: 'MilkyWay'\r\n" +
                        "\t\tsystems:\r\n" +
                        "\t\t\tcollection:\r\n" +
                        "\t\t\t\t- reference:\r\n" +
                        "\t\t\t\t\tclass: 'io.github.xdstorage.entities.XdStarSystem'\r\n" +
                        "\t\t\t\t- null\r\n"; // проверка явного null в массиве

        StringReader stringReader = new StringReader(testYaml);
        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(stringReader);

        // 1. Строка: "objects:"
        YamlLineToken token = tokenizer.nextToken();
        assertNotNull(token);
        assertEquals(0, token.level, "Неверный уровень корня!");
        assertEquals("objects", token.key);
        assertNull(token.value);
        assertFalse(token.isListItem);

        // 2. Строка: "\t- object:"
        token = tokenizer.nextToken();
        assertNotNull(token);
        assertEquals(1, token.level, "Неверный уровень вложенности для элемента списка!");
        assertEquals("object", token.key);
        assertNull(token.value);
        assertTrue(token.isListItem, "Маркер списка '- ' не был распознан!");

        // 3. Строка: "\t\tclass: 'io.github.xdstorage.entities.XdGalaxy'"
        token = tokenizer.nextToken();
        assertNotNull(token);
        assertEquals(2, token.level);
        assertEquals("class", token.key);
        assertEquals("io.github.xdstorage.entities.XdGalaxy", token.value,
                "Строковое значение класса искажено или потеряно!");
        assertFalse(token.isListItem);

        // 4. Строка: "\t\tname: 'MilkyWay'"
        token = tokenizer.nextToken();
        assertNotNull(token);
        assertEquals("name", token.key);
        assertEquals("MilkyWay", token.value);

        // Пропускаем systems (level 2) и collection (level 3)
        tokenizer.nextToken(); // systems
        tokenizer.nextToken(); // collection

        // 7. Строка: "\t\t\t\t- reference:"
        token = tokenizer.nextToken();
        assertNotNull(token);
        assertEquals(4, token.level);
        assertEquals("reference", token.key);
        assertTrue(token.isListItem);

        // Пропускаем class внутри reference (level 5)
        tokenizer.nextToken();

        // 9. Строка: "\t\t\t\t- null" (Явный маркер null в коллекции)
        token = tokenizer.nextToken();
        assertNotNull(token);
        assertEquals(4, token.level);
        assertEquals("null", token.key);
        assertNull(token.value, "Явный маркер null обязан десериализоваться в физический Java null!");
        assertTrue(token.isListItem);

        // Итоговый маркер конца файла (EOF)
        assertNull(tokenizer.nextToken(), "Токенизатор должен вернуть null при достижении конца потока!");
    }

    @Test
    @DisplayName("Распаковка кавычек: проверка корректности обработки дублированных одинарных кавычек")
    public void testTokenizer_DuplicatedQuotes_ShouldUnwrapCorrectly() throws Exception {
        // Имитируем название планеты, содержащее внутренние кавычки (Planet 'Omega')
        String yamlWithQuotes = "name: 'Planet ''Omega'''\r\n";

        StringReader stringReader = new StringReader(yamlWithQuotes);
        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(stringReader);

        YamlLineToken token = tokenizer.nextToken();
        assertNotNull(token);
        assertEquals("name", token.key);

        // Твой канонический алгоритм должен вернуть чистую строку: Planet 'Omega'
        assertEquals("Planet 'Omega'", token.value,
                "🚨 Ошибка распаковки: дублированные одинарные кавычки '' не превратились в одну кавычку!");
    }
}
