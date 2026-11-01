package io.github.xdstorage.foreignkey;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Спецификация Foreign Key: Тестирование структурных групп")
public class StructuralGroupsForeignKeySpecificationTest extends AbstractForeignKeySpecificationTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class ComplexParent {
        @XdStorageObjectId public Long id;
        @XdStorageAutoSave public GroupChild[] arrayChildren;
        @XdStorageAutoSave public Collection<GroupChild> collectionChildren;
        @XdStorageAutoSave public Map<String, GroupChild> mapChildren;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public GroupChild[] getArrayChildren() { return arrayChildren; }
        public void setArrayChildren(GroupChild[] a) { this.arrayChildren = a; }
        public Collection<GroupChild> getCollectionChildren() { return collectionChildren; }
        public void setCollectionChildren(Collection<GroupChild> c) { this.collectionChildren = c; }
        public Map<String, GroupChild> getMapChildren() { return mapChildren; }
        public void setMapChildren(Map<String, GroupChild> m) { this.mapChildren = m; }
    }

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class GroupChild {
        @XdStorageObjectId public Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    private io.github.xdstorage.utils.XdStorageClassInfo parentMeta;
    private io.github.xdstorage.utils.XdStorageClassInfo childMeta;

    @BeforeEach
    void initMetadata() {
        parentMeta = XdStorageClassMetadataRegistry.getClassInfo(ComplexParent.class);
        childMeta = XdStorageClassMetadataRegistry.getClassInfo(GroupChild.class);
    }

    @Test
    @DisplayName("Группа 1: Родитель имеет Array дочерних объектов")
    void testArrayChildrenGroup() throws Exception {
        GroupChild c1 = new GroupChild(); c1.setId(101L);
        ComplexParent parent = new ComplexParent(); parent.setId(10L);
        parent.setArrayChildren(new GroupChild[]{c1});

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("arrayChildren"), transaction);
        Collection<Object> links = fkResource.readByParentObjectId(10L, transaction);
        assertEquals(1, links.size());
        assertTrue(links.contains(101L));
    }

    @Test
    @DisplayName("Группа 2: Родитель имеет Collection дочерних объектов")
    void testCollectionChildrenGroup() throws Exception {
        GroupChild c1 = new GroupChild(); c1.setId(201L);
        ComplexParent parent = new ComplexParent(); parent.setId(20L);
        parent.setCollectionChildren(Collections.singletonList(c1));

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("collectionChildren"), transaction);
        Collection<Object> links = fkResource.readByParentObjectId(20L, transaction);
        assertEquals(1, links.size());
        assertTrue(links.contains(201L));
    }

    @Test
    @DisplayName("Группа 3: Родитель имеет Map дочерних объектов (Каскад по Values)")
    void testMapChildrenValuesGroup() throws Exception {
        GroupChild c1 = new GroupChild(); c1.setId(301L);
        ComplexParent parent = new ComplexParent(); parent.setId(30L);

        Map<String, GroupChild> map = new HashMap<>();
        map.put("ignored_key_string", c1); // Ключи игнорируются каскадом СУБД, проверяются только Values
        parent.setMapChildren(map);

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("mapChildren"), transaction);
        Collection<Object> links = fkResource.readByParentObjectId(30L, transaction);
        assertEquals(1, links.size());
        assertTrue(links.contains(301L));
    }

    @Test
    @DisplayName("Инвариант Групп: Удаление родителя при наличии хотя бы одного живого ребенка в коллекции — Отказ")
    @Disabled("Нужно тестировать нареальном хранилище")
    void testDeleteParentWithActiveCollectionChildrenThrows() throws Exception {
        ComplexParent parent = new ComplexParent(); parent.setId(55L);

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("collectionChildren"), transaction);
        // Эмулируем наличие связи в базе данных СУБД
        fkResource.insert(55L, 999L, transaction);

        // Согласно ТЗ: Удаление коллекции детей не завершено -> отказ коммита
        assertThrows(XdStorageRuntimeException.class, () -> {
            engine.process(parent, XdStorageForeignKeyObjectChangeType.DELETE, transaction);
        });
    }
}
