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

@DisplayName("Вторичные ключи: Сценарии удаления")
public class ForeignKeyDeleteScenariosTest extends AbstractForeignKeyBaseSpecificationTest {

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
    @DisplayName("1. Прямое удаление родительской сущности без удаления детей -> Ошибка на commit")
    void testDeleteOnlyParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);

        storage.delete(parent);

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2. Удаление только одного ребенка из сохраненной цепочки -> Ошибка на commit")
    void testDeleteOnlyOneChildThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId child = storage.load(BTreeIndexChildAutoId.class, childrenIds.get(0));

        storage.delete(child); // Индекс front-направления у родителя всё ещё держит ссылку

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3. Чтение всего графа и удаление только родительской сущности -> Ошибка на commit")
    void testLoadAllAndCleanOnlyParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);
        for (Long cid : childrenIds) {
            storage.load(BTreeIndexChildAutoId.class, cid);
        }

        storage.delete(parent);

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4. Чтение родителя и всех детей с последующим их полным удалением в одной TX -> Успешный commit")
    void testLoadAllAndPurgeAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = storage.load(BTreeIndexParentAutoId.class, parentId);
        List<BTreeIndexChildAutoId> children = new ArrayList<>();
        for (Long cid : childrenIds) {
            children.add(storage.load(BTreeIndexChildAutoId.class, cid));
        }

        // Согласно правилу ТЗ: сначала вырезаются дети, затем родитель
        for (BTreeIndexChildAutoId child : children) {
            storage.delete(child);
        }
        storage.delete(parent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}
