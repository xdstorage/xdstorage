package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexParentAutoId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Вторичные ключи: Сценарии со ссылками")
public class ForeignKeyReferenceScenariosTest extends AbstractForeignKeyBaseSpecificationTest {

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
    @DisplayName("Сценарий со ссылками: Удаление ребенка из коллекции родителя + Сброс parent в null + Апдейт обоих -> Успешный commit")
    void testClearLinkAndNullifyParentBackReferenceSuccess() throws Exception {
        tx = storage.beginTransaction();

        // 1. Читаем родительскую сущность и все дочерние сущности
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId targetChild = null;
        for (BTreeIndexChildAutoId c : parent.getChildren()) {
            if (c.getId().equals(childrenIds.get(0))) {
                targetChild = c;
                break;
            }
        }

        // 2. Из коллекции родительской сущности удаляем одну дочернюю сущность
        parent.getChildren().remove(targetChild);

        // 3. В дочерней сущности ссылка на родителя сбрасывается в null
        targetChild.setParent(null);

        // 4. Обновляем и родительскую, и дочернюю сущности
        storage.update(parent);
        storage.update(targetChild);

        // 5. Принятие транзакции должно пройти успешно (инварианты не нарушены)
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}
