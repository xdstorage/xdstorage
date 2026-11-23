package io.github.xdstorage.search;

import io.github.xdstorage.utils.XdStorageDummyObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.utils.XdStoragePair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XdStorageSearchIndex: метаданные поискового индекса")
public class XdStorageSearchIndexTest {

    private static XdStorageSearchIndex newIndex() {
        return new XdStorageSearchIndex(
                XdStorageClassMetadataRegistry.getClassIdField(SearchEngineShip.class), "ship_idx", 8);
    }

    private static Map<String, XdStorageObjectField> fieldsOf(final Class<?> cl) {
        // регистрируем метаданные класса, прежде чем запрашивать его полевые аксессоры
        XdStorageObjectUtils.getClassInfo(cl);
        return XdStorageClassMetadataRegistry.getClassFields(cl);
    }

    @Test
    @DisplayName("Конструктор: имя, коэффициент заполнения и поле идентификатора")
    public void testConstructor() throws Exception {
        final XdStorageSearchIndex index = newIndex();

        assertEquals("ship_idx", index.getName());
        assertEquals(8, index.getIndexFillingValue());
        // getClassIdField создает новый аксессор на каждый вызов - проверяем работоспособность, а не идентичность
        assertNotNull(index.getIdField());
        assertEquals("id", index.getIdField().getName());

        final SearchEngineShip ship = new SearchEngineShip();
        ship.setId(9L);
        final Object shipId = index.getIdField().get(ship);
        assertEquals(9L, shipId);
    }

    @Test
    @DisplayName("Первичное поле: установка и чтение")
    public void testPrimaryField() {
        final XdStorageSearchIndex index = newIndex();

        assertNull(index.getPrimaryField());

        final XdStorageObjectField nameField = fieldsOf(SearchEngineShip.class).get("name");
        index.setPrimaryField(nameField);

        assertSame(nameField, index.getPrimaryField());
    }

    @Test
    @DisplayName("Полевые аксессоры: пустые по умолчанию, накапливаются в порядке добавления")
    public void testFieldAccessors() {
        final XdStorageSearchIndex index = newIndex();

        assertTrue(index.getFieldAccesors().isEmpty());

        final XdStorageObjectField nameField = fieldsOf(SearchEngineShip.class).get("name");
        final XdStorageObjectField tonnageField = fieldsOf(SearchEngineShip.class).get("tonnage");
        index.addFieldAccessor("name", nameField);
        index.addFieldAccessor("tonnage", tonnageField);

        final Map<String, XdStorageObjectField> accessors = index.getFieldAccesors();
        assertEquals(2, accessors.size());
        assertSame(nameField, accessors.get("name"));
        assertSame(tonnageField, accessors.get("tonnage"));
    }

    @Test
    @DisplayName("Дочерние индексы: пустые по умолчанию, регистрируются по имени поля")
    public void testChildrenIndexes() {
        final XdStorageSearchIndex index = newIndex();

        assertTrue(index.getChildrenIndexes().isEmpty());

        index.addChildIndex("crew", "crew_idx");
        index.addChildIndex("captain", "crew_idx");

        final Map<String, String> children = index.getChildrenIndexes();
        assertEquals("crew_idx", children.get("crew"));
        assertEquals("crew_idx", children.get("captain"));
    }

    @Test
    @DisplayName("Дочерние аксессоры: группировка списка пар по классу потомка")
    public void testChildAccessors() {
        final XdStorageSearchIndex index = newIndex();

        assertTrue(index.getChildAccessors().isEmpty());

        final XdStorageObjectField crewField = fieldsOf(SearchEngineShip.class).get("crew");
        final XdStorageObjectField captainField = fieldsOf(SearchEngineShip.class).get("captain");
        index.addChildAccessor("crew", SearchEngineCrewMember.class, crewField);
        index.addChildAccessor("captain", SearchEngineCrewMember.class, captainField);

        final Collection<List<XdStoragePair<String, XdStorageObjectField>>> accessors = index.getChildAccessors();
        assertEquals(1, accessors.size());
        final List<XdStoragePair<String, XdStorageObjectField>> pairs = accessors.iterator().next();
        assertEquals(2, pairs.size());
        assertEquals("crew", pairs.get(0).a);
        assertSame(crewField, pairs.get(0).b);
        assertEquals("captain", pairs.get(1).a);
    }

    @Test
    @DisplayName("Поле идентификатора потомка: заглушка до первой регистрации, null для незарегистрированного класса")
    public void testChildIdFields() {
        final XdStorageSearchIndex index = newIndex();

        // до создания карты полей ид потомков возвращается заглушка
        assertSame(XdStorageDummyObjectIdField.Instance, index.getChildIdField(SearchEngineCrewMember.class));

        index.addChildIdField(SearchEngineCrewMember.class, XdStorageClassMetadataRegistry
                .getClassIdField(SearchEngineCrewMember.class));

        assertNotNull(index.getChildIdField(SearchEngineCrewMember.class));
        // после инициализации карты незарегистрированный класс дает null (а не заглушку)
        assertNull(index.getChildIdField(String.class));
    }

    @Test
    @DisplayName("Полевые аксессоры потомков: пустые по умолчанию и для незарегистрированного класса")
    public void testChildFieldAccessors() {
        final XdStorageSearchIndex index = newIndex();

        assertTrue(index.getChildFieldAccesors(SearchEngineCrewMember.class).isEmpty());

        final XdStorageObjectField rankField = fieldsOf(SearchEngineCrewMember.class).get("rank");
        index.addChildFieldAccessor(SearchEngineCrewMember.class, "rank", rankField);

        final Map<String, XdStorageObjectField> accessors = index.getChildFieldAccesors(SearchEngineCrewMember.class);
        assertEquals(1, accessors.size());
        assertSame(rankField, accessors.get("rank"));

        // незарегистрированный класс потомка при ИНИЦИАЛИЗИРОВАННОЙ карте дает null (а не пустую карту)
        assertNull(index.getChildFieldAccesors(String.class));
    }
}
