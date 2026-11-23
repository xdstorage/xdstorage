package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.entities.XdPlanet;
import io.github.xdstorage.services.XdStorageServicesLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Юнит-тесты: Тотально типизированный маршаллер объектов XdStorageYamlBlockObjectsWriter")
public class XdStorageYamlBlockObjectsWriterTest {

    private XdStorageServicesLocator mockLocator;
    private IXdStorageSimpleTypeHelper simpleTypeHelper;
    private IXdStorageIdGenerator mockIdGenerator;

    @BeforeEach
    public void setUp() {
        mockLocator = mock(XdStorageServicesLocator.class);
        simpleTypeHelper = new XdStorageDefaultSimpleTypeHelper();
        mockIdGenerator = mock(IXdStorageIdGenerator.class);
    }

    @Test
    @DisplayName("Кейс 1: Экранирование кавычек в строковых скалярах")
    public void testEncode_SingleQuote_ShouldBeDuplicated() {
        String dirtyValue = "Planet 'Omega'";
        String cleanValue = XdStorageYamlBlockObjectsWriter.encode(dirtyValue);
        assertEquals("Planet ''Omega''", cleanValue, "Нарушен канонический инвариант YAML-экранирования кавычек!");
    }

    @Test
    @DisplayName("Кейс 2: Маршаллинг JavaBeans — генерация строгой структуры name/type/value для полей")
    public void testWriteObjectData_ValidEntity_ShouldGenerateStrictMetaStructure() throws Exception {
        StringWriter writer = new StringWriter();
        XdStorageYamlEmitter emitter = new XdStorageYamlEmitter(writer);

        XdPlanet planet = new XdPlanet();
        planet.setId(500L);
        planet.setName("Tatooine");
        planet.setWaterPercent(0);

        XdStorageYamlBlockObjectsWriter.writeObjectData(planet, emitter, mockLocator, simpleTypeHelper,
                mockIdGenerator);

        String result = writer.toString();

        // Проверяем метаданные самого класса
        assertTrue(result.contains("class: 'io.github.xdstorage.entities.XdPlanet'"));
        assertTrue(result.contains("fields:"));

        // =========================================================================
        // СНАЙПЕРСКИЙ СИНТАКСИЧЕСКИЙ ФИКС: Все скалярные значения пишутся в кавычках!
        // =========================================================================
        assertTrue(result.contains("name: 'id'"));
        assertTrue(result.contains("type: 'java.lang.Long'"));
        assertTrue(result.contains("value: '500'")); // Добавлены кавычки

        assertTrue(result.contains("name: 'name'"));
        assertTrue(result.contains("type: 'java.lang.String'"));
        assertTrue(result.contains("value: 'Tatooine'"));

        assertTrue(result.contains("name: 'waterPercent'"));
        assertTrue(result.contains("type: 'int'"));
        assertTrue(result.contains("value: '0'")); // Добавлены кавычки
    }
}
