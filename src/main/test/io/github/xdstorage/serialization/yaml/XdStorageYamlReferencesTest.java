package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.entities.XdPlanet;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Юнит-тесты: Контур демаршаллинга чистых ORM-ссылок индексов СУБД (writeReferences / readReferences)")
public class XdStorageYamlReferencesTest {

    private IXdStorageSimpleTypeHelper simpleTypeHelper;
    private IXdStorageIdGenerator mockIdGenerator;
    private XdStorageServicesLocator mockLocator;

    private XdStorageYamlObjectsWriter yamlWriter;
    private XdStorageYamlObjectsReader yamlReader;

    @BeforeEach
    public void setUp() {
        mockLocator = mock(XdStorageServicesLocator.class);
        simpleTypeHelper = new XdStorageDefaultSimpleTypeHelper();
        mockIdGenerator = mock(IXdStorageIdGenerator.class);

        // Инстанцируем боевую YAML-пару фасадов
        yamlWriter = new XdStorageYamlObjectsWriter(mockLocator, simpleTypeHelper, mockIdGenerator);
        yamlReader = new XdStorageYamlObjectsReader(simpleTypeHelper);
    }

    @Test
    @DisplayName("Сквозной тест ссылок: Маршаллинг и демаршаллинг пакета чистых ID-ссылок СУБД")
    public void testYamlSubsystem_PureReferencesRoundTrip_ShouldRestoreCleanly() throws Exception {
        // =========================================================================
        // ШАГ 1: ПОДГОТОВКА ИСХОДНЫХ ОБЪЕКТОВ ДЛЯ СРЕЗА ИНДЕКСА
        // =========================================================================
        XdPlanet planet1 = new XdPlanet();
        planet1.setId(500L); // Идентификатор типа Long

        XdPlanet planet2 = new XdPlanet();
        planet2.setId(900L);

        Collection<Object> referencesToWrite = new ArrayList<>();
        referencesToWrite.add(planet1);
        referencesToWrite.add(planet2);

        // Извлекаем метаданные поля идентификатора для канонической выгрузки
        XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(XdPlanet.class);
        XdStorageObjectIdField idField = clInfo.getIdField();

        // =========================================================================
        // ШАГ 2: ВЫГРУЗКА ЧИСТЫХ ССЫЛОК НА ДИСК (КОНТУР ЗАПИСИ)
        // =========================================================================
        StringWriter stringWriter = new StringWriter();
        yamlWriter.writeReferences(stringWriter, idField, referencesToWrite);
        String outputYaml = stringWriter.toString();

        System.out.println("=========================================================================");
        System.out.println("📊 ФИЗИЧЕСКИЙ YAML СЛЕПОК ЧИСТЫХ ИНДЕКСНЫХ ССЫЛОК СУБД:");
        System.out.print(outputYaml);
        System.out.println("=========================================================================");

        // Верифицируем строгую геометрию пакетных ссылок (в файле не должно быть узла fields)
        assertTrue(outputYaml.contains("references:"), "Райтер потерял корневой маркер references!");
        assertTrue(outputYaml.contains("- reference:"), "Элементы не обернуты в узлы - reference!");
        assertTrue(outputYaml.contains("class: 'io.github.xdstorage.entities.XdPlanet'"));
        assertTrue(outputYaml.contains("objectId: '500'"));
        assertTrue(outputYaml.contains("objectId: '900'"));
        assertFalse(outputYaml.contains("fields:"),
                "🚨 ОШИБКА: Райтер ошибочно выгрузил тяжелые JavaBeans-свойства внутрь ссылки!");

        // =========================================================================
        // ШАГ 3: ВОССТАНОВЛЕНИЕ СВЯЗЕЙ ИЗ ФАЙЛА (КОНТУР ЧТЕНИЯ)
        // =========================================================================
        StringReader stringReader = new StringReader(outputYaml);
        Collection<Object> restoredReferences = yamlReader.readReferences(stringReader, idField);

        // =========================================================================
        // ШАГ 4: СНАЙПЕРСКАЯ ПРОВЕРИФИКАЦИЯ ВОССТАНОВЛЕННЫХ ТРАНЗАКЦИОННЫХ СВЯЗЕЙ
        // =========================================================================
        assertNotNull(restoredReferences, "Ридер ссылок вернул null!");
        assertEquals(2, restoredReferences.size(), "Количество восстановленных ссылок не совпадает!");

        List<Object> refList = new ArrayList<>(restoredReferences);

        // Верифицируем первый прокси-объект индекса
        Object firstObj = refList.get(0);
        assertTrue(firstObj instanceof XdPlanet, "Первый прокси-объект обязан иметь тип XdPlanet!");
        XdPlanet restoredPlanet1 = (XdPlanet) firstObj;
        assertEquals(Long.valueOf(500L), restoredPlanet1.getId(), "Идентификатор Long поврежден рефлексией!");
        assertNull(restoredPlanet1.getName(), "Тяжелые JavaBeans-поля обязаны остаться null в прокси-ссылке!");

        // Верифицируем второй прокси-объект индекса
        Object secondObj = refList.get(1);
        assertTrue(secondObj instanceof XdPlanet, "Второй прокси-объект обязан иметь тип XdPlanet!");
        XdPlanet restoredPlanet2 = (XdPlanet) secondObj;
        assertEquals(Long.valueOf(900L), restoredPlanet2.getId());
        assertNull(restoredPlanet2.getName());

        System.out.println("🎉 УСПЕХ! Изолированный юнит-тест транзакционных ORM-ссылок полностью позеленел!");
    }
}
