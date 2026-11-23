package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Юнит-тесты: Тотально типизированный парсер контейнеров YAML (Коллекции и Мапы)")
public class XdStorageYamlBlockContainersReaderTest {

    private IXdStorageSimpleTypeHelper simpleTypeHelper;

    @BeforeEach
    public void setUp() {
        simpleTypeHelper = new XdStorageDefaultSimpleTypeHelper();
    }

    @Test
    @DisplayName("Кейс 1: Разбор ArrayList с использованием строгой структуры - item: type/value")
    public void testReadCollection_StrictItemModel_ShouldRestoreCleanly() throws Exception {
        // Каждый элемент коллекции теперь оборачивается в явный тип и значение
        String yamlCollection =
                "\t\tcollection:\r\n" +
                        "\t\t\tclass: 'java.util.ArrayList'\r\n" +
                        "\t\t\t- item:\r\n" +
                        "\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\tvalue: 'Mercury'\r\n" +
                        "\t\t\t- item:\r\n" +
                        "\t\t\t\ttype: 'null'\r\n" +
                        "\t\t\t\tvalue: null\r\n" +
                        "\t\t\t- item:\r\n" +
                        "\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\tvalue: 'Venus'\r\n";

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(yamlCollection));

        YamlLineToken startToken = tokenizer.peekToken();
        int parentLevel = startToken.level;
        tokenizer.nextToken(); // Поглощаем 'collection:' для выравнивания каретки

        @SuppressWarnings("unchecked")
        Collection<Object> result = (Collection<Object>) XdStorageYamlBlockContainersReader.readCollection(
                tokenizer, parentLevel, simpleTypeHelper
        );

        assertNotNull(result, "Парсер вернул null для коллекции!");
        assertEquals(3, result.size(), "Размер восстановленной коллекции не совпадает!");

        List<Object> list = new ArrayList<>(result);
        assertEquals("Mercury", list.get(0));
        assertNull(list.get(1), "Элемент типа 'null' обязан превратиться в физический Java null!");
        assertEquals("Venus", list.get(2));
    }

    @Test
    @DisplayName("Кейс 2: Разбор HashMap со строгой структурой entry -> key/value -> type/value")
    public void testReadMap_StrictEntryModel_ShouldRestoreProperties() throws Exception {
        // Ключи и значения внутри entry имеют свои явные независимые типы
        String yamlMap =
                "\t\tmap:\r\n" +
                        "\t\t\tclass: 'java.util.HashMap'\r\n" +
                        "\t\t\t- entry:\r\n" +
                        "\t\t\t\tkey:\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tvalue: 'sector'\r\n" +
                        "\t\t\t\tvalue:\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tvalue: 'Sector-7G'\r\n" +
                        "\t\t\t- entry:\r\n" +
                        "\t\t\t\tkey:\r\n" +
                        // Специфичный ключ-энум ядра СУБД
                        "\t\t\t\t\ttype: 'io.github.xdstorage.entities.map.TestEnum'\r\n" +
                        "\t\t\t\t\tvalue: 'VALUE1'\r\n" +
                        "\t\t\t\tvalue:\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.Object'\r\n" + // Полноценный полиморфный Object из MapStoringTest
                        "\t\t\t\t\tvalue:\r\n" +
                        "\t\t\t\t\t\tobject:\r\n" +
                        "\t\t\t\t\t\t\tclass: 'java.lang.Object'\r\n" +
                        "\t\t\t\t\t\t\tfields:\r\n";

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(yamlMap));

        YamlLineToken startToken = tokenizer.peekToken();
        int parentLevel = startToken.level;
        tokenizer.nextToken(); // Поглощаем 'map:'

        @SuppressWarnings("unchecked")
        Map<Object, Object> result = (Map<Object, Object>) XdStorageYamlBlockContainersReader.readMap(
                tokenizer, simpleTypeHelper, parentLevel
        );

        assertNotNull(result, "Мапа вернула null при демаршаллинге!");
        assertEquals(2, result.size(), "Количество записей в HashMap не совпадает!");
        assertEquals("Sector-7G", result.get("sector"), "Строковое значение мапы повреждено!");

        // Верифицируем динамическое восстановление типов без хардкода
        Object enumKey = null;
        for (Object key : result.keySet()) {
            if (key.getClass().isEnum() && "VALUE1".equals(key.toString())) {
                enumKey = key;
                break;
            }
        }
        assertNotNull(enumKey, "Ключ-энум потерял свой тип и деградировал в String!");
        Object polyObject = result.get(enumKey);
        assertNotNull(polyObject, "Полиморфное значение Object потеряно!");
        assertEquals(Object.class, polyObject.getClass(), "Экземпляр java.lang.Object восстановлен некорректно!");
    }

    @Test
    @DisplayName("Кейс 3: Новые тесты — Устойчивость к перепутанному порядку мета-полей внутри - item:")
    public void testReadCollection_ShuffledItemFields_ShouldParseCorrectly() throws Exception {
        // Ставим value перед type, проверяя независимость конвейера Ридера от порядка записи
        String shuffledItemYaml =
                "\t\tcollection:\r\n" +
                        "\t\t\tclass: 'java.util.ArrayList'\r\n" +
                        "\t\t\t- item:\r\n" +
                        "\t\t\t\tvalue: 'Jupiter'\r\n" +
                        "\t\t\t\ttype: 'java.lang.String'\r\n";

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(shuffledItemYaml));
        YamlLineToken startToken = tokenizer.peekToken();
        tokenizer.nextToken(); // Поглощаем 'collection:'

        @SuppressWarnings("unchecked")
        Collection<Object> result = (Collection<Object>) XdStorageYamlBlockContainersReader.readCollection(
                tokenizer, startToken.level, simpleTypeHelper
        );

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Jupiter", result.iterator().next(),
                "Парсер потерял значение из-за изменения порядка type/value!");
    }

    @Test
    @DisplayName("Кейс 4: Новые тесты — Мягкий пропуск (Fail-Safe) поврежденных или пустых entry")
    public void testReadMap_CorruptedEntryBlock_ShouldIgnoreAndNotCrash() throws Exception {
        String corruptedMapYaml =
                "\t\tmap:\r\n" +
                        "\t\t\tclass: 'java.util.HashMap'\r\n" +
                        "\t\t\t- entry:\r\n" +
                        "\t\t\t\tkey_broken_property: 'some_value'\r\n" + // Невалидная структура
                        "\t\t\t- entry:\r\n" +
                        "\t\t\t\tkey:\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tvalue: 'validKey'\r\n" +
                        "\t\t\t\tvalue:\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tvalue: 'validValue'\r\n";

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(corruptedMapYaml));
        YamlLineToken startToken = tokenizer.peekToken();
        tokenizer.nextToken(); // Поглощаем 'map:'

        Map<Object, Object> result = (Map<Object, Object>) assertDoesNotThrow(() ->
                        XdStorageYamlBlockContainersReader.readMap(tokenizer, simpleTypeHelper, startToken.level),
                "Парсер коллекций аварийно упал при синтаксическом повреждении блока entry!"
        );

        assertNotNull(result);
        assertEquals(1, result.size(), "Битый блок entry должен быть отброшен, а валидный — успешно сохранен!");
        assertEquals("validValue", result.get("validKey"));
    }
}
