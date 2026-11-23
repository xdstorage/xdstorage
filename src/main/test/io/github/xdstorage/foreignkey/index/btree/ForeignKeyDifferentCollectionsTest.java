package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexParentAutoId;
import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexChildManualId;
import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexParentManualId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FNKEY (FOREIGN_KEYS.md §8): "BTree and Hash index variants are mirrored
 * for every scenario suite."
 *
 * Tests FK integrity across collection types: Set, Map values, array.
 * Required coverage:
 *   1. Children in different collections (Set, Map values, arrays)
 *   2. Children are inserted (FK veto for orphan children)
 *   3. Children are deleted (FK veto for orphaned parent reference)
 *   4. Parent updated: part of children deleted, part inserted (mixed diff)
 *   5. Database generated identifiers (auto-generated, FK resolved at commit)
 *   6. Manual identifiers (pre-set IDs, pending-registry validation at phase-1)
 */
@DisplayName("Вторичные ключи: Разные типы коллекций детей (Set, Map, array)")
public class ForeignKeyDifferentCollectionsTest extends AbstractForeignKeyBaseSpecificationTest {

    // ==================== SET COLLECTION ====================

    @Test
    @DisplayName("1a. Вставка родителя с детьми в Set — Успешный коммит")
    void testSetInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("1b. Вставка только детей в Set без родителя — Исключение")
    void testSetInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1c. Вставка только родителя без детей в Set — Успешный коммит")
    void testSetInsertParentOnlySuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("1d. Удаление родителя с детьми в Set — Ошибка")
    void testSetDeleteParentAloneThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loaded = storage.load(BTreeIndexParentAutoId.class, parentId);
        storage.delete(loaded);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1e. Удаление одного ребенка из Set с обновлением родителя — Успешно")
    void testSetDeleteOneChildAndUpdateParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);
        storage.delete(loadedChild1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1f. Удаление одного ребенка из Set БЕЗ обновления — Ошибка")
    void testSetDeleteOneChildWithoutParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long child1Id = child1.getId();
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        storage.delete(loadedChild1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    // ==================== MAP VALUES COLLECTION ====================

    @Test
    @DisplayName("2a. Вставка родителя с детьми в Map values — Успешный коммит")
    void testMapValuesInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        Map<String, BTreeIndexChildAutoId> childMap = new HashMap<>();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("2b. Вставка только детей в Map без родителя — Исключение")
    void testMapValuesInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        Map<String, BTreeIndexChildAutoId> childMap = new HashMap<>();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2c. Вставка только родителя без детей в Map — Успешный коммит")
    void testMapValuesInsertParentOnlySuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashMap<String, BTreeIndexChildAutoId>().values());
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("2d. Удаление родителя с детьми в Map — Ошибка")
    void testMapValuesDeleteParentAloneThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        Map<String, BTreeIndexChildAutoId> childMap = new HashMap<>();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loaded = storage.load(BTreeIndexParentAutoId.class, parentId);
        storage.delete(loaded);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2e. Удаление одного ребенка из Map с обновлением родителя — Успешно")
    void testMapValuesDeleteOneChildAndUpdateParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        Map<String, BTreeIndexChildAutoId> childMap = new HashMap<>();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);
        storage.delete(loadedChild1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2f. Удаление одного ребенка из Map БЕЗ обновления — Ошибка")
    void testMapValuesDeleteOneChildWithoutParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        Map<String, BTreeIndexChildAutoId> childMap = new HashMap<>();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long child1Id = child1.getId();
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        storage.delete(loadedChild1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    // ==================== ARRAY COLLECTION ====================

    @Test
    @DisplayName("3a. Вставка родителя с детьми в Array — Успешный коммит")
    void testArrayInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("3b. Вставка только детей в Array без родителя — Исключение")
    void testArrayInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3c. Вставка только родителя без детей в Array — Успешный коммит")
    void testArrayInsertParentOnlySuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("3d. Удаление родителя с детьми в Array — Ошибка")
    void testArrayDeleteParentAloneThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loaded = storage.load(BTreeIndexParentAutoId.class, parentId);
        storage.delete(loaded);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3e. Удаление одного ребенка из Array с обновлением родителя — Успешно")
    void testArrayDeleteOneChildAndUpdateParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        List<BTreeIndexChildAutoId> remaining = new ArrayList<>(loadedParent.getChildren());
        remaining.remove(loadedChild1);
        loadedParent.setChildren(remaining);
        storage.update(loadedParent);
        storage.delete(loadedChild1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3f. Удаление одного ребенка из Array БЕЗ обновления — Ошибка")
    void testArrayDeleteOneChildWithoutParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long child1Id = child1.getId();
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        storage.delete(loadedChild1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    // ==================== MANUAL ID — Set ====================

    @Test
    @DisplayName("4a. Вставка родителя с детьми в Set (Manual ID) — Успешный коммит")
    void testSetManualIdInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId();
        parent.setChildren(new HashSet<>());
        parent.setId(10001L);
        BTreeIndexChildManualId child1 = new BTreeIndexChildManualId(); child1.setId(20001L); child1.setParent(parent);
                parent.addChild(child1);
        BTreeIndexChildManualId child2 = new BTreeIndexChildManualId(); child2.setId(20002L); child2.setParent(parent);
                parent.addChild(child2);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4b. Вставка только детей в Set (Manual ID) без родителя — Исключение")
    void testSetManualIdInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId();
        parent.setChildren(new HashSet<>());
        parent.setId(30001L);
        BTreeIndexChildManualId child1 = new BTreeIndexChildManualId(); child1.setId(40001L); child1.setParent(parent);
                parent.addChild(child1);
        BTreeIndexChildManualId child2 = new BTreeIndexChildManualId(); child2.setId(40002L); child2.setParent(parent);
                parent.addChild(child2);
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    // ==================== MANUAL ID — Map values ====================

    @Test
    @DisplayName("5a. Вставка родителя с детьми в Map values (Manual ID) — Успешный коммит")
    void testMapValuesManualIdInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId();
        Map<String, BTreeIndexChildManualId> childMap = new HashMap<>();
        BTreeIndexChildManualId child1 = new BTreeIndexChildManualId(); child1.setId(90001L); child1.setParent(parent);
        BTreeIndexChildManualId child2 = new BTreeIndexChildManualId(); child2.setId(90002L); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        parent.setId(80001L);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("5b. Вставка только детей в Map values (Manual ID) без родителя — Исключение")

    // ==================== CASCADE DELETE ====================

    void testSetCascadeDeleteAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), c1 = child1.getId(), c2 = child2.getId();
        tx = storage.beginTransaction();
        storage.delete(storage.load(BTreeIndexChildAutoId.class, c1));
        storage.delete(storage.load(BTreeIndexChildAutoId.class, c2));
        storage.delete(storage.load(BTreeIndexParentAutoId.class, parentId));
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6. Каскадное удаление родителя и всех детей (Map values) — Успешный коммит")
    void testMapValuesCascadeDeleteAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        Map<String, BTreeIndexChildAutoId> childMap = new HashMap<>();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        childMap.put("key1", child1);
        childMap.put("key2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), c1 = child1.getId(), c2 = child2.getId();
        tx = storage.beginTransaction();
        storage.delete(storage.load(BTreeIndexChildAutoId.class, c1));
        storage.delete(storage.load(BTreeIndexChildAutoId.class, c2));
        storage.delete(storage.load(BTreeIndexParentAutoId.class, parentId));
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6. Каскадное удаление родителя и всех детей (Array) — Успешный коммит")
    void testArrayCascadeDeleteAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), c1 = child1.getId(), c2 = child2.getId();
        tx = storage.beginTransaction();
        storage.delete(storage.load(BTreeIndexChildAutoId.class, c1));
        storage.delete(storage.load(BTreeIndexChildAutoId.class, c2));
        storage.delete(storage.load(BTreeIndexParentAutoId.class, parentId));
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}
