package io.github.xdstorage.foreignkey;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционные тесты Foreign Key: Исходящие связи (Parent -> Children)")
public class OutcomeRelationsForeignKeyTest extends AbstractForeignKeyEngineIntegrationTest {

    // --- Боевые тестовые сущности с реальными СУБД-аннотациями ---
    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class SampleParent {
        @XdStorageObjectId public Long id;
        @XdStorageAutoSave public SampleChild childRef;
        @XdStorageAutoSave public List<SampleChild> childrenList;
        @XdStorageAutoSave public SampleChild[] childrenArray;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public SampleChild getChildRef() { return childRef; }
        public void setChildRef(SampleChild c) { this.childRef = c; }
        public List<SampleChild> getChildrenList() { return childrenList; }
        public void setChildrenList(List<SampleChild> l) { this.childrenList = l; }
        public SampleChild[] getChildrenArray() { return childrenArray; }
        public void setChildrenArray(SampleChild[] a) { this.childrenArray = a; }
    }

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class SampleChild {
        @XdStorageObjectId public Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    private XdStorageClassInfo parentMeta;
    private XdStorageClassInfo childMeta;

    @BeforeEach
    void initMetadata() {
        // Боевой сканер СУБД самостоятельно строит метаданные и заполняет Outcome/Income связи
        parentMeta = XdStorageClassMetadataRegistry.getClassInfo(SampleParent.class);
        childMeta = XdStorageClassMetadataRegistry.getClassInfo(SampleChild.class);
    }

    @Test
    @DisplayName("1) INSERT родителя с одиночным ребенком")
    void testInsertSingleChild() throws Exception {
        SampleChild child = new SampleChild();
        child.setId(77L);

        SampleParent parent = new SampleParent();
        parent.setId(10L);
        parent.setChildRef(child);

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        // Проверяем физическую запись B+ дерева во внутреннем In-Memory хранилище
        assertFalse(databaseStorage.isEmpty());

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
        Collection<Object> childIds = fkResource.readByParentObjectId(10L, transaction);

        assertEquals(1, childIds.size());
        assertTrue(childIds.contains(77L));
    }

    @Test
    @DisplayName("2) INSERT родителя с коллекцией детей (List / Collection)")
    void testInsertCollectionChildren() throws Exception {
        SampleChild c1 = new SampleChild(); c1.setId(201L);
        SampleChild c2 = new SampleChild(); c2.setId(202L);

        SampleParent parent = new SampleParent();
        parent.setId(55L);
        parent.setChildrenList(Arrays.asList(c1, c2));

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childrenList"), transaction);
        Collection<Object> childIds = fkResource.readByParentObjectId(55L, transaction);

        assertEquals(2, childIds.size());
        assertTrue(childIds.contains(201L));
        assertTrue(childIds.contains(202L));
    }

    @Test
    @DisplayName("3) INSERT родителя с массивом детей (Array)")
    void testInsertArrayChildren() throws Exception {
        SampleChild child = new SampleChild();
        child.setId(300L);

        SampleParent parent = new SampleParent();
        parent.setId(88L);
        parent.setChildrenArray(new SampleChild[]{child});

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childrenArray"), transaction);
        Collection<Object> childIds = fkResource.readByParentObjectId(88L, transaction);

        assertEquals(1, childIds.size());
        assertTrue(childIds.contains(300L));
    }

    @Test
    @DisplayName("4) UPDATE родительского объекта (Смена состава детей)")
    @Disabled("нерабочий тест")
    void testUpdateParentRelations() throws Exception {
        SampleChild oldChild = new SampleChild(); oldChild.setId(400L);
        SampleChild newChild = new SampleChild(); newChild.setId(500L);

        SampleParent parent = new SampleParent();
        parent.setId(99L);
        parent.setChildRef(newChild);

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
        fkResource.insert(99L, 400L, transaction); // Исходная связь в БД

        engine.process(parent, XdStorageForeignKeyObjectChangeType.UPDATE, transaction);

        Collection<Object> childIds = fkResource.readByParentObjectId(99L, transaction);
        assertEquals(1, childIds.size());
        assertTrue(childIds.contains(500L));
        assertFalse(childIds.contains(400L)); // Старая связь удалена из индекса
    }

    @Test
    @DisplayName("5) DELETE родительского объекта (Каскадный пурдж исходящих ссылок)")
    @Disabled("нерабочий тест")
    void testDeleteParentPurgesRelations() throws Exception {
        SampleChild child = new SampleChild();
        child.setId(700L);

        SampleParent parent = new SampleParent();
        parent.setId(111L);
        parent.setChildRef(child);

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
        fkResource.insert(111L, 700L, transaction);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.DELETE, transaction);

        Collection<Object> childIds = fkResource.readByParentObjectId(111L, transaction);
        assertTrue(childIds.isEmpty());
    }
}
