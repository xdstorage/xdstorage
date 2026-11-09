package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexParentAutoId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Вторичные ключи: Сценарии обновления")
public class ForeignKeyUpdateScenariosTest extends AbstractForeignKeyBaseSpecificationTest {

    private Long parentId;
    private final List<Long> childrenIds = new ArrayList<>();

    @BeforeEach
    void prepareBaselineData() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        parentId = parent.getId();
        childrenIds.add(child1.getId());
        childrenIds.add(child2.getId());
    }

    @Test
    @DisplayName("1. Удаление ребенка из коллекции родителя и обновление только родителя -> Ошибка на commit")
    void testUpdateOnlyParentAfterRemovingChildThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);

        // Находим и удаляем первого ребенка локально из коллекции
        BTreeIndexChildAutoId targetChild = parent.getChildren().iterator().next();
        parent.getChildren().remove(targetChild);

        storage.update(parent); // Дочерний узел не удален из СУБД

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2. Удаление ребенка из коллекции родителя и удаление только ребенка (без апдейта родителя) -> Ошибка на commit")
    void testDeleteOnlyChildWithoutUpdatingParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId targetChild = parent.getChildren().iterator().next();

        storage.delete(targetChild); // Родительский узел не обновлен, ссылка в индексе зависла

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3. Удаление ребенка из коллекции родителя, обновление родителя и удаление ребенка -> Успешный commit")
    void testUpdateParentAndDeleteChildSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);

        BTreeIndexChildAutoId targetChild = null;
        for (BTreeIndexChildAutoId c : parent.getChildren()) {
            if (c.getId().equals(childrenIds.get(0))) { targetChild = c; break; }
        }

        parent.getChildren().remove(targetChild);

        storage.update(parent);
        storage.delete(targetChild);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4. Удаление всех детей из коллекции родителя и обновление только родителя -> Ошибка на commit")
    void testUpdateOnlyParentAfterRemovingAllChildrenThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);

        parent.getChildren().clear();
        storage.update(parent);

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("5. Удаление всех детей из коллекции родителя, обновление родителя и удаление всех детей -> Успешный commit")
    void testUpdateParentAndDeleteAllChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);

        List<BTreeIndexChildAutoId> toDelete = new ArrayList<>(parent.getChildren());
        parent.getChildren().clear();

        storage.update(parent);
        for (BTreeIndexChildAutoId child : toDelete) {
            storage.delete(child);
        }

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6. Удаление ребенка из коллекции родителя, обновление родителя и удаление другого ребенка -> Ошибка на commit")
    void testUpdateParentAndDeleteAnotherChildSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);

        BTreeIndexChildAutoId targetChild = null;
        BTreeIndexChildAutoId deleteChild = null;
        for (BTreeIndexChildAutoId c : parent.getChildren()) {
            if (c.getId().equals(childrenIds.get(0))) { targetChild = c; }
            else { deleteChild = c; }
        }


        parent.getChildren().remove(targetChild);

        storage.update(parent);
        storage.delete(deleteChild);

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }
}
