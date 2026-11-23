package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.services.XdStorageServicesLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Монументальный интеграционный тест: Сквозной цикл тотально типизированного YAML Round-Trip")
public class XdStorageYamlRoundTripTest {

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

        // Инстанцируем нашу декомпозированную YAML-пару СУБД
        yamlWriter = new XdStorageYamlObjectsWriter(mockLocator, simpleTypeHelper, mockIdGenerator);
        yamlReader = new XdStorageYamlObjectsReader(simpleTypeHelper);
    }

    @Test
    @DisplayName("Сквозной Round-Trip: глубокая проверка Сериализация -> Десериализация полиморфного мета-графа")
    public void testYamlSubsystem_StrictMetaGraphRoundTrip_ShouldRestoreCleanly() throws Exception {
        // =========================================================================
        // ШАГ 1: СБОРКА СЛОЖНОГО ГЕТЕРОГЕННОГО ГРАФА СУЩНОСТЕЙ В ПАМЯТИ
        // =========================================================================
        XdUniverse originalUniverse = new XdUniverse();
        originalUniverse.setId("universe-alpha-7");

        XdGalaxy galaxy = new XdGalaxy();
        galaxy.setId("galaxy-milkyway");

        // Вложенный инлайн-объект (Политика StoreWithParentObject)
        XdBlackHole hole = new XdBlackHole();
        hole.setId("hole-supermassive");
        hole.setMass(4500000L);
        hole.setIsEventHorizonActive(true);
        galaxy.setHole(hole);

        XdStarSystem system = new XdStarSystem();
        system.setId("system-sol-100");
        system.setNewName("Sol-''System''"); // Проверка экранирования кавычек

        XdPlanet earth = new XdPlanet();
        earth.setId(800L);
        earth.setName("Earth-'Blue'-Planet");
        earth.setWaterPercent(2); // Примитивный int

        system.addPlanet(earth);
        galaxy.addSystem(system);
        originalUniverse.addGalaxy(galaxy);

        // =========================================================================
        // СНАЙПЕРСКИЙ ФИКС: Добавляем независимый ORM-корень в контекст СУБД!
        // Теперь Райтер честно запишет тело Звёздной системы, и граф сойдётся!
        // =========================================================================
        Collection<Object> contextObjects = new ArrayList<>();
        contextObjects.add(originalUniverse);
        contextObjects.add(galaxy);
        contextObjects.add(system); // <-- ОБЯЗАТЕЛЬНЫЙ КОРЕНЬ НЕЗАВИСИМОЙ ТАБЛИЦЫ СИСТЕМ!
        contextObjects.add(earth);

        // =========================================================================
        // ШАГ 2: КОНТУР ЗАПИСИ (МАРШАЛЛИНГ В СТРОГИЙ YAML С ТИПАМИ)
        // =========================================================================
        StringWriter stringWriter = new StringWriter();
        yamlWriter.writeObjects(stringWriter, contextObjects);
        String outputYaml = stringWriter.toString();

        System.out.println("=========================================================================");
        System.out.println("📊 ФИЗИЧЕСКИЙ YAML СЛЕПОК ТОТАЛЬНО ТИПИЗИРОВАННОЙ ВСЕЛЕННОЙ НА ДИСКЕ СУБД:");
        System.out.print(outputYaml);
        System.out.println("=========================================================================");

        // =========================================================================
        // ВЕРИФИКАЦИЯ ГЕOМЕТРИИ ЗАПИСИ (Зажимаем Райтер под новые контракты)
        // =========================================================================
        assertTrue(outputYaml.contains("fields:"), "Райтер потерял корневой узел метамодели fields!");
        assertTrue(outputYaml.contains("name: 'waterPercent'"), "Поле примитива не получило мета-узел имени!");
        assertTrue(outputYaml.contains("type: 'int'"), "Тип примитива int не сохранился на диск!");
        assertTrue(outputYaml.contains("- item:"), "Элементы коллекций не обернуты в каноничный узел - item!");

        // =========================================================================
        // ШАГ 3: КОНТУР ЧТЕНИЯ (ДЕconvertАЦИЯ И СБОРКА ИЗ YAML-СТРОКИ)
        // =========================================================================
        StringReader stringReader = new StringReader(outputYaml);
        Collection<Object> restoredObjects = yamlReader.readObjects(stringReader);

        // =========================================================================
        // ШАГ 4: СНАЙПЕРСКАЯ АДАПТИВНАЯ ВЕРИФИКАЦИЯ ВОССТАНОВЛЕННОГО ГРАФА
        // =========================================================================
        assertNotNull(restoredObjects);
        assertEquals(4, restoredObjects.size(), "Корневая коллекция должна содержать ровно 2 объекта таблиц!");

        XdUniverse restoredUniverse = null;
        XdGalaxy restoredGalaxy = null;
        XdStarSystem restoredSystem = null;
        XdPlanet restoredEarth = null;

        for (Object obj : restoredObjects) {
            if (obj instanceof XdUniverse) {
                restoredUniverse = (XdUniverse) obj;
            } else if (obj instanceof XdGalaxy) {
                restoredGalaxy = (XdGalaxy) obj;
            } else if (obj instanceof XdStarSystem) {
                restoredSystem = (XdStarSystem) obj;
            } else if (obj instanceof XdPlanet) {
                restoredEarth = (XdPlanet) obj;
            }
        }

        assertNotNull(restoredUniverse, "Глава Вселенной потеряна при демаршаллинге!");
        assertNotNull(restoredGalaxy, "Тело Галактики потеряно при демаршаллинге!");

        assertEquals("universe-alpha-7", restoredUniverse.getId());
        assertEquals("galaxy-milkyway", restoredGalaxy.getId());

        // Проверяем глубокую рекурсию вложенной Черной Дыры
        XdBlackHole restoredHole = restoredGalaxy.getHole();
        assertNotNull(restoredHole, "Внедренная Черная Дыра (StoreWithParentObject) была потеряна!");
        assertEquals("hole-supermassive", restoredHole.getId());
        assertEquals(4500000L, restoredHole.getMass());
        assertTrue(restoredHole.getIsEventHorizonActive());

        // Спускаемся по графу в звездную систему
        Collection<XdStarSystem> systems = restoredGalaxy.getSystems();
        assertEquals(1, systems.size());
        assertEquals("system-sol-100", restoredSystem.getId());
        assertEquals("Sol-''System''", restoredSystem.getNewName(), "Экранированные кавычки повреждены!");

        // Проверяем цепочку коллекций и примитивов планет
        Collection<XdPlanet> planets = restoredSystem.getPlanets();
        assertEquals(1, planets.size());

        assertEquals(Long.valueOf(800L), restoredEarth.getId(), "Рефлексивный тип Long поврежден!");
        assertEquals("Earth-'Blue'-Planet", restoredEarth.getName(), "Кавычки имени планеты искажены!");
        assertEquals(2, restoredEarth.getWaterPercent(), "Примитив int утерян при каскадном чтении по типам!");

        System.out.println("🎉 ТРИУМФ! Полный сквозной мета-тест Round-Trip успешно зафиксирован!");
    }
}
