package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.entities.XdPlanet;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Юнит-тесты: Тотально типизированный парсер JavaBeans-объектов YAML")
public class XdStorageYamlBlockObjectsReaderTest {

    private IXdStorageSimpleTypeHelper simpleTypeHelper;

    @BeforeEach
    public void setUp() {
        simpleTypeHelper = new XdStorageDefaultSimpleTypeHelper();
    }

    @Test
    @DisplayName("Кейс 1: Сборка объекта по новой мета-модели и боксинг примитивов")
    public void testReadObject_StrictFieldsModel_ShouldPopulateJavaBeans() throws Exception {
        // Формат строго соответствует тройке name, type, value для каждого свойства
        String yamlObject =
                "\t\tobject:\r\n" +
                        "\t\t\tclass: 'io.github.xdstorage.entities.XdPlanet'\r\n" +
                        "\t\t\tfields:\r\n" +
                        "\t\t\t\t- field:\r\n" +
                        "\t\t\t\t\tname: 'id'\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.Long'\r\n" +
                        "\t\t\t\t\tvalue: 950\r\n" +
                        "\t\t\t\t- field:\r\n" +
                        "\t\t\t\t\tname: 'name'\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tvalue: 'Mars'\r\n" +
                        "\t\t\t\t- field:\r\n" +
                        "\t\t\t\t\tname: 'waterPercent'\r\n" +
                        "\t\t\t\t\ttype: 'int'\r\n" +
                        "\t\t\t\t\tvalue: 0\r\n";

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(yamlObject));
        tokenizer.nextToken(); // Потребляем маркер 'object:'

        Object result = XdStorageYamlBlockObjectsReader.readObject(tokenizer, 2, simpleTypeHelper);

        assertNotNull(result, "Парсер вернул null вместо готового инстанса!");
        assertTrue(result instanceof XdPlanet, "Создан неверный класс рантайма!");

        XdPlanet planet = (XdPlanet) result;
        assertEquals(Long.valueOf(950L), planet.getId(), "Идентификатор id Long поврежден рефлексией!");
        assertEquals("Mars", planet.getName(), "Строковое поле JavaBeans заполнено неверно!");
        assertEquals(0, planet.getWaterPercent(), "Нормализация примитива int дала сбой!");
    }

    @Test
    @DisplayName("Кейс 2: Новые тесты — Адаптивность к перемешанному порядку мета-полей (value перед name)")
    public void testReadObject_ShuffledMetaFields_ShouldParseSuccessfully() throws Exception {
        // Намеренно меняем местами value, type и name внутри блока - field:,
        // имитируя алфавитную или случайную сортировку Райтера СУБД.
        String shuffledYaml =
                "\t\tobject:\r\n" +
                        "\t\t\tclass: 'io.github.xdstorage.entities.XdPlanet'\r\n" +
                        "\t\t\tfields:\r\n" +
                        "\t\t\t\t- field:\r\n" +
                        "\t\t\t\t\tvalue: 'Venus'\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tname: 'name'\r\n"; // name стоит в самом конце!

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(shuffledYaml));
        tokenizer.nextToken(); // Потребляем 'object:'

        Object result = XdStorageYamlBlockObjectsReader.readObject(tokenizer, 2, simpleTypeHelper);

        assertNotNull(result);
        XdPlanet planet = (XdPlanet) result;
        assertEquals("Venus", planet.getName(),
                "Адаптивный цикл чтения пропустил мета-поля из-за измененного порядка!");
    }

    @Test
    @DisplayName("Кейс 3: Новые тесты — Fail-Safe барьер при неизвестном или мусорном имени поля")
    public void testReadObject_UnknownFieldInMetaModel_ShouldIgnoreAndNotCrash() throws Exception {
        String yamlWithTrashField =
                "\t\tobject:\r\n" +
                        "\t\t\tclass: 'io.github.xdstorage.entities.XdPlanet'\r\n" +
                        "\t\t\tfields:\r\n" +
                        "\t\t\t\t- field:\r\n" +
                        "\t\t\t\t\tname: 'phantomGhostProperty'\r\n" + // Поле отсутствует в XdPlanet
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tvalue: 'Boo'\r\n" +
                        "\t\t\t\t- field:\r\n" +
                        "\t\t\t\t\tname: 'name'\r\n" +
                        "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\t\tvalue: 'Stitch'\r\n";

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(yamlWithTrashField));
        tokenizer.nextToken();

        // Парсер не должен швырять исключения, он обязан мягко пропустить неизвестное поле
        Object result = assertDoesNotThrow(() ->
                        XdStorageYamlBlockObjectsReader.readObject(tokenizer, 2, simpleTypeHelper),
                "Парсер аварийно упал при встрече неизвестного JavaBeans-свойства!"
        );

        assertNotNull(result);
        XdPlanet planet = (XdPlanet) result;
        assertEquals("Stitch", planet.getName(), "Конвейер чтения сломался после пропуска неизвестного поля!");
    }

    @Test
    @DisplayName("Кейс 4: Новые тесты — Разбор пустой секции fields: без единого свойства")
    public void testReadObject_EmptyFieldsBlock_ShouldReturnCleanInstance() throws Exception {
        String emptyFieldsYaml =
                "\t\tobject:\r\n" +
                        "\t\t\tclass: 'io.github.xdstorage.entities.XdPlanet'\r\n" +
                        "\t\t\tfields:\r\n";

        XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(emptyFieldsYaml));
        tokenizer.nextToken();

        Object result = XdStorageYamlBlockObjectsReader.readObject(tokenizer, 2, simpleTypeHelper);

        assertNotNull(result);
        assertTrue(result instanceof XdPlanet);
        XdPlanet planet = (XdPlanet) result;
        assertNull(planet.getName(), "Поля обязаны остаться девственно чистыми (null)!");
    }
}
