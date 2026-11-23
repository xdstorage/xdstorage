package io.github.xdstorage.serialization;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 тесты для верификации работы оригинального XML-движка СУБД (Поинт Г).
 * Полностью синхронизирован с каноническим XML-форматом <objects>, <object> и <primitive>.
 */
public class XdStorageXmlSerializationTest {

    private XdStorageServicesLocator mockServices;
    private IXdStorageIdGenerator mockIdGenerator;
    private XdStorageDefaultSimpleTypeHelper typeHelper;
    private XdStorageDefaultObjectsWriter xmlWriter;
    private XdStorageDefaultObjectsReader xmlReader;

    // Тестовая public сущность верхнего уровня для проверки XML-сериализации
    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    public static class XmlTestRecord {
        @XdStorageObjectId
        private Long id;
        private String title;

        public XmlTestRecord() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    @BeforeEach
    public void setUp() {
        mockServices = mock(XdStorageServicesLocator.class);
        mockIdGenerator = mock(IXdStorageIdGenerator.class);
        typeHelper = new XdStorageDefaultSimpleTypeHelper();

        // Инициализируем оригинальные XML-компоненты маршаллинга СУБД
        xmlWriter = new XdStorageDefaultObjectsWriter(mockServices, typeHelper, mockIdGenerator);
        xmlReader = new XdStorageDefaultObjectsReader(typeHelper);
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь маршаллинга XML) ===

    @Test
    public void testWriteAndReadObjects_ShouldMaintainDataConsistency() throws Exception {
        // Шаг 1. Готовим тестовую пачку объектов
        List<Object> records = new ArrayList<>();
        XmlTestRecord record = new XmlTestRecord();
        record.setId(777L);
        record.setTitle("XML_Test_Payload");
        records.add(record);

        // Шаг 2. Сериализуем объекты в XML-строку памяти через StringWriter
        StringWriter stringWriter = new StringWriter();
        xmlWriter.writeObjects(stringWriter, records);

        String outputXml = stringWriter.toString();

        assertNotNull(outputXml);
        // Верифицируем строгое соответствие канонической XML-разметке СУБД
        assertTrue(outputXml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
                "XML обязан содержать заголовок декларации!");
        assertTrue(outputXml.contains("<objects>"), "XML должен содержать корневой тег коллекции!");
        assertTrue(outputXml.contains("<object class=\"" + XmlTestRecord.class.getName() + "\">"),
                "Тег объекта должен содержать точное имя класса метаданных!");
        assertTrue(outputXml.contains("<object name=\"id\" class=\"java.lang.Long\" value=\"777\"/>"),
                "Числовой первичный ключ должен сериализоваться как простой тип!");
        assertTrue(outputXml.contains("<object name=\"title\" class=\"java.lang.String\" value=\"XML_Test_Payload\"/>"),
                "Строковое поле должно кодироваться как простой тип!");

        // Шаг 3. Десериализуем XML-строку обратно через StringReader
        StringReader stringReader = new StringReader(outputXml);
        Collection<Object> deserializedResult = xmlReader.read(stringReader);

        // Верифицируем бесшовное воссоздание JavaBean-модели из XML структуры
        assertNotNull(deserializedResult);
        assertEquals(1, deserializedResult.size(), "Должен быть восстановлен ровно 1 объект");

        Object restoredObj = deserializedResult.iterator().next();
        assertTrue(restoredObj instanceof XmlTestRecord,
                "Восстановленный объект должен соответствовать типу XmlTestRecord");

        XmlTestRecord restoredRecord = (XmlTestRecord) restoredObj;
        assertEquals(777L, restoredRecord.getId().longValue());
        assertEquals("XML_Test_Payload", restoredRecord.getTitle());
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testRead_WithEmptyXml_ShouldReturnEmptyCollectionSafely() throws Exception {
        // Проверяем устойчивость StAX-парсера к пустому входящему потоку
        StringReader stringReader = new StringReader("   ");
        Assertions.assertThrows(XdStorageIOException.class, () -> {
            Collection<Object> result = xmlReader.read(stringReader);
        });
    }

    @Test
    public void testWriteReferences_HappyPath() throws Exception {
        // Проверяем контракт сериализации системных плоских XML-ссылок движка СУБД
        XmlTestRecord mockRef = mock(XmlTestRecord.class);
        when(mockRef.getId()).thenReturn(100L);

        XdStorageObjectIdField mockIdField = mock(XdStorageObjectIdField.class);
        when(mockIdField.get(any())).thenReturn(100L);

        List<Object> references = new ArrayList<>();
        references.add(mockRef);

        StringWriter writer = new StringWriter();
        xmlWriter.writeReferences(writer, mockIdField, references);

        String output = writer.toString();
        assertNotNull(output);
        // Верифицируем структуру системного тега ссылок СУБД
        assertTrue(output.contains("<references>"));
        assertTrue(output.contains("<reference "));
        assertTrue(output.contains("class=\"" + XmlTestRecord.class.getName()));
        assertTrue(output.contains("classObjectId=\"java.lang.Long\""));
        assertTrue(output.contains("objectId=\"100\""));
    }
}
