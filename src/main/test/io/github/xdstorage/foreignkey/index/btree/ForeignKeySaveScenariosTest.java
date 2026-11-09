package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexParentAutoId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Вторичные ключи: Сценарии сохранения")
public class ForeignKeySaveScenariosTest extends AbstractForeignKeyBaseSpecificationTest {

    @Test
    @DisplayName("1. Сохранение родительской сущности без дочерних сущностей -> Успешный коммит")
    void testSaveParentWithoutChildrenThrows() throws Exception {
        tx = storage.beginTransaction();

        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        // Наполняем дочернюю ссылку, но не добавляем её в коллекцию родителя
        BTreeIndexChildAutoId child = new BTreeIndexChildAutoId();
        child.setParent(parent);

        storage.save(parent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2. Сохранение одной дочерней сущности без родителя -> Исключение на commit")
    void testSaveSingleChildWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();

        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child = new BTreeIndexChildAutoId();
        child.setParent(parent);
        parent.addChild(child);

        storage.save(child); // Родителю не вызван save()

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3. Сохранение всех дочерних сущностей без родителя -> Исключение на commit")
    void testSaveAllChildrenWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();

        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(child1);
        storage.save(child2);

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4. Сохранение всех дочерних сущностей с сохранением родительской сущности -> Успешный commit")
    void testSaveAllChildrenWithParentSuccess() throws Exception {
        tx = storage.beginTransaction();

        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(child1);
        storage.save(child2);
        storage.save(parent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}
