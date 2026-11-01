package io.github.xdstorage.foreignkey;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционные тесты Foreign Key: Покрытие зон 'implement me'")
public class ImplementMeForeignKeyTest extends AbstractForeignKeyEngineIntegrationTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class SampleParent {
        @XdStorageObjectId public Long id;
        @XdStorageAutoSave public SampleChild childRef;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public SampleChild getChildRef() { return childRef; }
        public void setChildRef(SampleChild c) { this.childRef = c; }
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
        parentMeta = XdStorageClassMetadataRegistry.getClassInfo(SampleParent.class);
        childMeta = XdStorageClassMetadataRegistry.getClassInfo(SampleChild.class);

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);
    }

    @Test
    @DisplayName("1) INCOME - DELETE: Объект вставлен и удален в одной транзакции (childObjectId == null)")
    void testIncomeRelationsInsertAndFieldsDeletedInOneTransaction() throws Exception {
        SampleChild child = new SampleChild(); // id == null, имитируем транзитную вставку

        // Перебиваем поведение idField метаданных, имитируя, что ID еще не присвоен на фазе удаления
        childMeta.getIdField().set(child, null);

        // Сейчас этот вызов упадет с XdStorageException("implement me please when it was inserted...")
        assertDoesNotThrow(() -> {
            engine.process(child, XdStorageForeignKeyObjectChangeType.DELETE, transaction);
        });
    }

//    @Test
//    @DisplayName("2) OUTCOME - DELETE: Удаление по инстансам, когда у ребенка id == null")
//    void testOutcomeDeleteWithChildIdNull() throws Exception {
//        SampleParent parent = new SampleParent();
//        parent.setId(10L);
//        SampleChild child = new SampleChild(); // id == null
//
//        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
//
//        // Сейчас этот вызов упадет с XdStorageException("implement me") во второй ветке delete(Object, Object)
//        XdStorageException exception = assertThrows(XdStorageException.class, () -> {
//            fkResource.delete(parent, child, transaction);
//        });
//
//        assertEquals("implement me", exception.getMessage());
//    }
//
//    @Test
//    @DisplayName("3) OUTCOME - DELETE: Удаление, когда у родителя id == null (delete по объектам)")
//    void testOutcomeDeleteWithParentIdNullByObjects() throws Exception {
//        SampleParent parent = new SampleParent(); // id == null
//        SampleChild child = new SampleChild();
//        child.setId(77L);
//
//        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
//
//        // Сейчас этот вызов упадет с XdStorageException("implement me") в третьей ветке delete(Object, Object)
//        XdStorageException exception = assertThrows(XdStorageException.class, () -> {
//            fkResource.delete(parent, child, transaction);
//        });
//
//        assertEquals("implement me", exception.getMessage());
//    }
//
//    @Test
//    @DisplayName("4) OUTCOME - DELETE: Удаление, когда у родителя id == null (delete по объекту и childObjectId)")
//    void testOutcomeDeleteWithParentIdNullByObjectId() throws Exception {
//        SampleParent parent = new SampleParent(); // id == null
//        Long childObjectId = 88L;
//
//        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
//
//        // Сейчас этот вызов упадет с XdStorageException("implement me") внутри delete(Object, Comparable, Transaction)
//        XdStorageException exception = assertThrows(XdStorageException.class, () -> {
//            fkResource.delete(parent, childObjectId, transaction);
//        });
//
//        assertEquals("implement me", exception.getMessage());
//    }
//
//    @Test
//    @DisplayName("5) OUTCOME - DELETE: Удаление, когда у ребенка id == null (delete по parentObjectId и объекту)")
//    void testOutcomeDeleteWithChildIdNullByObjectId() throws Exception {
//        Long parentObjectId = 99L;
//        SampleChild child = new SampleChild(); // id == null
//
//        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
//
//        // Сейчас этот вызов упадет с XdStorageException("implement me") внутри delete(Comparable, Object, Transaction)
//        XdStorageException exception = assertThrows(XdStorageException.class, () -> {
//            fkResource.delete(parentObjectId, child, transaction);
//        });
//
//        assertEquals("implement me", exception.getMessage());
//    }
}
