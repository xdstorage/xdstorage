package io.github.xdstorage.foreignkey.index.hash;

import io.github.xdstorage.entities.foreignkeys.index.hash.HashIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.hash.HashIndexParentAutoId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Вторичные ключи: Сценарии удаления")
public class ForeignKeyDeleteScenariosTest extends AbstractForeignKeyBaseSpecificationTest {

    private Long parentId;
    private final List<Long> childrenIds = new ArrayList<>();

    @BeforeEach
    void prepareBaselineData() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

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
        HashIndexParentAutoId parent = storage.load(HashIndexParentAutoId.class, parentId);

        storage.delete(parent);

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2. Удаление только одного ребенка из сохраненной цепочки -> Ошибка на commit")
    void testDeleteOnlyOneChildThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexChildAutoId child = storage.load(HashIndexChildAutoId.class, childrenIds.get(0));

        storage.delete(child); // Индекс front-направления у родителя всё ещё держит ссылку

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3. Чтение всего графа и удаление только родительской сущности -> Ошибка на commit")
    void testLoadAllAndCleanOnlyParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = storage.load(HashIndexParentAutoId.class, parentId);
        for (Long cid : childrenIds) {
            storage.load(HashIndexChildAutoId.class, cid);
        }

        storage.delete(parent);

        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4. Чтение родителя и всех детей с последующим их полным удалением в одной TX -> Успешный commit")
    void testLoadAllAndPurgeAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = storage.load(HashIndexParentAutoId.class, parentId);
        List<HashIndexChildAutoId> children = new ArrayList<>();
        for (Long cid : childrenIds) {
            children.add(storage.load(HashIndexChildAutoId.class, cid));
        }

        // Согласно правилу ТЗ: сначала вырезаются дети, затем родитель
        for (HashIndexChildAutoId child : children) {
            storage.delete(child);
        }
        storage.delete(parent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}
