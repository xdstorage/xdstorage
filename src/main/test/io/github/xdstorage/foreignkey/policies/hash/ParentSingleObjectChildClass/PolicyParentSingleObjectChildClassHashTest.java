package io.github.xdstorage.foreignkey.policies.hash.ParentSingleObjectChildClass;

import io.github.xdstorage.entities.foreignkeys.policies.hash.ParentSingleObjectChildClass.HashPolicyParentSingleObjectChildClass;
import io.github.xdstorage.entities.foreignkeys.policies.hash.ParentSingleObjectChildClass.HashPolicyChildClass;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.foreignkey.index.hash.AbstractForeignKeyBaseSpecificationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FK policy test: Parent=StoreAsSingleObject, Child=StoreAsClassObjects (Hash).
 * Tests all required scenarios:
 * 1. Children in different collections (Set, Map values, array)
 * 2. Children are inserted (FK veto for orphan children)
 * 3. Children are deleted (FK veto for orphaned parent reference)
 * 4. Parent is updated (part of children are deleted, part inserted)
 * 5. Database generated identifiers (auto-generated, FK resolved at commit)
 * 6. Manual identifiers (pre-set IDs, pending-registry validation at phase-1)
 */
@DisplayName("FK Store Policies (Hash): Parent=SingleObject, Child=ClassObjects")
public class PolicyParentSingleObjectChildClassHashTest extends AbstractForeignKeyBaseSpecificationTest {

    @Test
    @DisplayName("1a. Вставка родителя с детьми в Set — Успешный коммит")
    void testSetInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("1b. Вставка только детей в Set без родителя — Исключение")
    void testSetInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1c. Вставка Map values — Успешный коммит")
    void testMapValuesInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        Map<String, HashPolicyChildClass> childMap = new HashMap<>();
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1d. Вставка через array — Успешный коммит")
    void testArrayInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2a. Вставка только родителя — Успешный коммит")
    void testInsertParentOnlySuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("2b. Вставка child1 без родителя — Исключение (родитель не сохранён)")
    void testInsertChildWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent);
        storage.save(child1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3a. Удаление родителя с детьми — Ошибка")
    void testDeleteParentAloneThrows() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass loaded = storage.load(HashPolicyParentSingleObjectChildClass.class, parentId);
        storage.delete(loaded);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3b. Удаление одного ребенка с обновлением родителя — Успешно")
    void testDeleteOneChildAndUpdateParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass loadedParent = storage.load(HashPolicyParentSingleObjectChildClass.class, parentId);
        HashPolicyChildClass loadedChild1 = storage.load(HashPolicyChildClass.class, child1Id);
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);
        storage.delete(loadedChild1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3c. Удаление ребенка БЕЗ обновления — Ошибка")
    void testDeleteChildWithoutParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashPolicyChildClass loadedChild1 = storage.load(HashPolicyChildClass.class, child1Id);
        storage.delete(loadedChild1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4a. Обновление родителя: удалить child1, вставить child3 — Успешно")
    void testUpdateParentRemoveOneInsertOneSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass loadedParent = storage.load(HashPolicyParentSingleObjectChildClass.class, parentId);
        HashPolicyChildClass loadedChild1 = storage.load(HashPolicyChildClass.class, child1Id);
        loadedParent.getChildren().remove(loadedChild1);
        HashPolicyChildClass child3 = new HashPolicyChildClass(); child3.setParent(loadedParent);
        loadedParent.addChild(child3);
        storage.update(loadedParent);
        storage.delete(loadedChild1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4b. Обновление родителя: удалить child1, вставить child3 — Ошибка (без delete)")
    void testUpdateParentRemoveOneInsertOneNoDeleteThrows() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        parent.setChildren(new HashSet<>());
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        HashPolicyChildClass child2 = new HashPolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass loadedParent = storage.load(HashPolicyParentSingleObjectChildClass.class, parentId);
        HashPolicyChildClass loadedChild1 = storage.load(HashPolicyChildClass.class, child1Id);
        loadedParent.getChildren().remove(loadedChild1);
        HashPolicyChildClass child3 = new HashPolicyChildClass(); child3.setParent(loadedParent);
        loadedParent.addChild(child3);
        storage.update(loadedParent);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("5a. Auto-ID: parent saved first, children get IDs later — Успешно")
    void testAutoIdParentFirstChildrenLaterSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        HashPolicyChildClass child1 = new HashPolicyChildClass(); child1.setParent(parent);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();

        tx = storage.beginTransaction();
        HashPolicyChildClass savedChild1 = storage.load(HashPolicyChildClass.class, parentId);
        storage.save(child1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6a. Manual-ID child без родителя — Исключение")
    void testManualIdChildWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        HashPolicyChildClass child = new HashPolicyChildClass();
        child.setId(42L);
        child.setParent(parent);
        storage.save(child);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6b. Parent с AutoSave + Manual-ID child — Успешно")
    void testAutoSaveParentWithManualChildSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashPolicyParentSingleObjectChildClass parent = new HashPolicyParentSingleObjectChildClass();
        HashPolicyChildClass child = new HashPolicyChildClass();
        child.setId(42L);
        child.setParent(parent);
        parent.addChild(child);
        storage.save(parent);  // cascade saves child automatically
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
        assertEquals(42L, child.getId());
    }
}
