package io.github.xdstorage.foreignkey.index.hash;

import io.github.xdstorage.entities.foreignkeys.index.hash.HashIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.hash.HashIndexParentAutoId;
import io.github.xdstorage.entities.foreignkeys.index.hash.manual.HashIndexChildManualId;
import io.github.xdstorage.entities.foreignkeys.index.hash.manual.HashIndexParentManualId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hash-index mirror of ForeignKeyDifferentCollectionsTest (FOREIGN_KEYS.md §8).
 */
@DisplayName("Вторичные ключи (Hash): Разные типы коллекций детей (Set, Map, array)")
public class ForeignKeyDifferentCollectionsHashTest extends AbstractForeignKeyBaseSpecificationTest {

    @Test
    @DisplayName("1a. Вставка родителя с детьми в Set (Hash) — Успешный коммит")
    void testSetInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1b. Вставка только детей в Set (Hash) без родителя — Исключение")
    void testSetInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1c. Вставка только родителя без детей (Hash) — Успешный коммит")
    void testSetInsertParentOnlySuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1d. Удаление родителя с детьми в Set (Hash) — Ошибка")
    void testSetDeleteParentAloneThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();
        tx = storage.beginTransaction();
        HashIndexParentAutoId loaded = storage.load(HashIndexParentAutoId.class, parentId);
        storage.delete(loaded);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1e. Удаление одного ребенка из Set с обновлением родителя (Hash) — Успешно")
    void testSetDeleteOneChildAndUpdateParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashIndexParentAutoId loadedParent = storage.load(HashIndexParentAutoId.class, parentId);
        HashIndexChildAutoId loadedChild1 = storage.load(HashIndexChildAutoId.class, child1Id);
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);
        storage.delete(loadedChild1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1f. Удаление одного ребенка из Set БЕЗ обновления — Ошибка")
    void testSetDeleteOneChildWithoutParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashIndexChildAutoId loadedChild1 = storage.load(HashIndexChildAutoId.class, child1Id);
        storage.delete(loadedChild1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2a. Вставка родителя с детьми в Map values (Hash) — Успешный коммит")
    void testMapValuesInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        Map<String, HashIndexChildAutoId> childMap = new HashMap<>();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        childMap.put("k1", child1);
        childMap.put("k2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2b. Вставка только детей в Map values (Hash) без родителя — Исключение")
    void testMapValuesInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        Map<String, HashIndexChildAutoId> childMap = new HashMap<>();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        childMap.put("k1", child1);
        childMap.put("k2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2c. Вставка только родителя без детей (Hash) — Успешный коммит")
    void testMapValuesInsertParentOnlySuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashMap<String, HashIndexChildAutoId>().values());
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2d. Удаление родителя с детьми в Map (Hash) — Ошибка")
    void testMapValuesDeleteParentAloneThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        Map<String, HashIndexChildAutoId> childMap = new HashMap<>();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        childMap.put("k1", child1);
        childMap.put("k2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();
        tx = storage.beginTransaction();
        HashIndexParentAutoId loaded = storage.load(HashIndexParentAutoId.class, parentId);
        storage.delete(loaded);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2e. Удаление одного ребенка из Map с обновлением родителя (Hash) — Успешно")
    void testMapValuesDeleteOneChildAndUpdateParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        Map<String, HashIndexChildAutoId> childMap = new HashMap<>();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        childMap.put("k1", child1);
        childMap.put("k2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashIndexParentAutoId loadedParent = storage.load(HashIndexParentAutoId.class, parentId);
        HashIndexChildAutoId loadedChild1 = storage.load(HashIndexChildAutoId.class, child1Id);
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);
        storage.delete(loadedChild1);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2f. Удаление одного ребенка из Map БЕЗ обновления — Ошибка")
    void testMapValuesDeleteOneChildWithoutParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        Map<String, HashIndexChildAutoId> childMap = new HashMap<>();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        childMap.put("k1", child1);
        childMap.put("k2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashIndexChildAutoId loadedChild1 = storage.load(HashIndexChildAutoId.class, child1Id);
        storage.delete(loadedChild1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3a. Вставка родителя с детьми в Array (Hash) — Успешный коммит")
    void testArrayInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3b. Вставка только детей в Array (Hash) без родителя — Исключение")
    void testArrayInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3c. Вставка только родителя без детей (Hash) — Успешный коммит")
    void testArrayInsertParentOnlySuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3d. Удаление родителя с детьми в Array (Hash) — Ошибка")
    void testArrayDeleteParentAloneThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();
        tx = storage.beginTransaction();
        HashIndexParentAutoId loaded = storage.load(HashIndexParentAutoId.class, parentId);
        storage.delete(loaded);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3e. Удаление одного ребенка из Array с обновлением родителя (Hash) — Успешно")
    void testArrayDeleteOneChildAndUpdateParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashIndexParentAutoId loadedParent = storage.load(HashIndexParentAutoId.class, parentId);
        HashIndexChildAutoId loadedChild1 = storage.load(HashIndexChildAutoId.class, child1Id);
        List<HashIndexChildAutoId> remaining = new ArrayList<>(loadedParent.getChildren());
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
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long child1Id = child1.getId();
        tx = storage.beginTransaction();
        HashIndexChildAutoId loadedChild1 = storage.load(HashIndexChildAutoId.class, child1Id);
        storage.delete(loadedChild1);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4a. Вставка родителя с детьми в Set (Hash Manual) — Успешный коммит")
    void testSetManualIdInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId();
        parent.setChildren(new HashSet<>());
        parent.setId(10001L);
        HashIndexChildManualId child1 = new HashIndexChildManualId(); child1.setId(20001L); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildManualId child2 = new HashIndexChildManualId(); child2.setId(20002L); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4b. Вставка только детей в Set (Hash Manual) без родителя — Исключение")
    void testSetManualIdInsertChildrenOnlyWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId();
        parent.setChildren(new HashSet<>());
        parent.setId(30001L);
        HashIndexChildManualId child1 = new HashIndexChildManualId(); child1.setId(40001L); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildManualId child2 = new HashIndexChildManualId(); child2.setId(40002L); child2.setParent(parent); parent.addChild(child2);
        storage.save(child1);
        storage.save(child2);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("5a. Вставка родителя с детьми в Map values (Hash Manual) — Успешный коммит")
    void testMapValuesManualIdInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId();
        Map<String, HashIndexChildManualId> childMap = new HashMap<>();
        HashIndexChildManualId child1 = new HashIndexChildManualId(); child1.setId(90001L); child1.setParent(parent);
        HashIndexChildManualId child2 = new HashIndexChildManualId(); child2.setId(90002L); child2.setParent(parent);
        childMap.put("k1", child1);
        childMap.put("k2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        parent.setId(80001L);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("5b. Вставка только детей в Map values (Hash Manual) без родителя — Исключение")

    // ==================== CASCADE DELETE ====================

    void testSetCascadeDeleteAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        parent.setChildren(new HashSet<>());
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), c1 = child1.getId(), c2 = child2.getId();
        tx = storage.beginTransaction();
        storage.delete(storage.load(HashIndexChildAutoId.class, c1));
        storage.delete(storage.load(HashIndexChildAutoId.class, c2));
        storage.delete(storage.load(HashIndexParentAutoId.class, parentId));
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6. Каскадное удаление (Map values Hash) — Успешный коммит")
    void testMapValuesCascadeDeleteAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        Map<String, HashIndexChildAutoId> childMap = new HashMap<>();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        childMap.put("k1", child1);
        childMap.put("k2", child2);
        parent.setChildren(new ArrayList<>(childMap.values()));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), c1 = child1.getId(), c2 = child2.getId();
        tx = storage.beginTransaction();
        storage.delete(storage.load(HashIndexChildAutoId.class, c1));
        storage.delete(storage.load(HashIndexChildAutoId.class, c2));
        storage.delete(storage.load(HashIndexParentAutoId.class, parentId));
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6. Каскадное удаление (Array Hash) — Успешный коммит")
    void testArrayCascadeDeleteAllSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentAutoId parent = new HashIndexParentAutoId();
        HashIndexChildAutoId child1 = new HashIndexChildAutoId(); child1.setParent(parent);
        HashIndexChildAutoId child2 = new HashIndexChildAutoId(); child2.setParent(parent);
        parent.setChildren(new ArrayList<>(Arrays.asList(child1, child2)));
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId(), c1 = child1.getId(), c2 = child2.getId();
        tx = storage.beginTransaction();
        storage.delete(storage.load(HashIndexChildAutoId.class, c1));
        storage.delete(storage.load(HashIndexChildAutoId.class, c2));
        storage.delete(storage.load(HashIndexParentAutoId.class, parentId));
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}
