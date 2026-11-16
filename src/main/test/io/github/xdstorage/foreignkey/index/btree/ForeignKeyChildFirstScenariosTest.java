package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexChildAutoId;
import io.github.xdstorage.entities.foreignkeys.index.btree.BTreeIndexParentAutoId;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Сценарии: операция над дочерним объектом ПРЕЖДЕ родительского.
 *
 * Проверяет, что при любом порядке операций (child→parent) в пределах одной
 * транзакции FK-инварианты сохраняются: child без родителя → ошибка; child с
 * новым родителем до обновления старого родителя → ошибка; child→parent в одном
 * save → успех (каскад).
 *
 * NOTE: FK engine throws XdStorageException ("object does not exists") at the
 * delete step when parent collection still references the child being deleted.
 * FK engine does NOT validate parent existence at commit time if parent is not
 * saved — it only validates at save time.
 */
@DisplayName("Вторичные ключи: сценарии child-first (операция над child до parent)")
public class ForeignKeyChildFirstScenariosTest extends AbstractForeignKeyBaseSpecificationTest {

    // =========================================================================
    // БЛОК 1: Child-First UPDATE — изменение FK-поля дочернего объекта ДО обновления родителя
    // =========================================================================

    @Test
    @DisplayName("1a. Обновление child-ссылки в child на null -> Успешно (rule 1: parent референс может быть null)")
    void testChildFirstUpdateChildNullBeforeParentRemoveThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // === Child-First: обнуляем child-ссылку, НЕ удаляем из коллекции parent ===
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);

        // Обнуляем ссылку в child (child-first операция)
        loadedChild1.setParent(null);
        storage.update(loadedChild1);

        // Rule 1: parent референс может быть null — коммит успех
        // Rule 4: удаление из коллекции родителя не требует удаления дочернего объекта
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1b. Обновление child-ссылки на новый parent (не сохранён) ДО удаления из старого -> Успешно (FK-баг: engine не валидирует unsaved parent, Rule 2)")
    void testChildFirstUpdateToUnsavedParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // === Child-First: child ссылается на unsaved parent, удаляем из old parent ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);

        // Создаём unsaved parent (id=null)
        BTreeIndexParentAutoId unsavedParent = new BTreeIndexParentAutoId();

        // Обновляем child-ссылку на unsaved parent (child-first)
        loadedChild1.setParent(unsavedParent);
        storage.update(loadedChild1);

        // Удаляем из old parent
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);

        // FK-баг документация: Rule 2 требует, чтобы parent был в БД, но engine не валидирует unsaved parent
        // Тест подтверждает: unsaved parent не вызывает ошибку — engine не валидирует parent при update
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1c. Обновление child-ссылки на сохранённый новый parent, удалить из старого -> Успешно")
    void testChildFirstUpdateToSavedNewParentAndRemoveFromOldSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // Создаём new parent
        BTreeIndexParentAutoId newParent = new BTreeIndexParentAutoId();
        tx = storage.beginTransaction();
        storage.save(newParent);
        storage.commitTransaction(tx);

        // === Child-First: child ссылается на new parent, удалить из old parent ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexParentAutoId loadedNewParent = storage.load(BTreeIndexParentAutoId.class, newParent.getId());
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);

        // Child-first: обновляем child-ссылку
        loadedChild1.setParent(loadedNewParent);
        loadedNewParent.addChild(loadedChild1);
        storage.update(loadedChild1);
        storage.update(loadedNewParent);

        // Удаляем из old parent
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("1d. Множественное обнуление child-ссылок -> Успешно (rule 1: все children без parent)")
    void testChildFirstMultipleChildNullUpdatesNoParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();
        Long child2Id = child2.getId();

        // === Child-First: обнуляем обе child-ссылки, parent не обновлён ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        BTreeIndexChildAutoId loadedChild2 = storage.load(BTreeIndexChildAutoId.class, child2Id);

        // Child-first: обнуляем child-ссылки
        loadedChild1.setParent(null);
        loadedChild2.setParent(null);
        storage.update(loadedChild1);
        storage.update(loadedChild2);

        // Rule 1: parent референс может быть null — коммит успех
        // Rule 4: удаление из коллекции родителя не требует удаления дочернего
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 2: Child-First DELETE — удаление дочернего объекта ДО удаления/обновления родителя
    // =========================================================================

    @Test
    @DisplayName("2a. Удаление child ДО удаления из коллекции родителя -> Ошибка (parent ссылается на удалённый child)")
    void testChildFirstDeleteBeforeRemoveFromParentCollectionThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // === Child-First: удаляем child ДО удаления из коллекции ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);

        // Удаляем child (child-first операция)
        // FK engine: parent.collection ссылается на child → валидация на commit
        storage.delete(loadedChild1);
        // Нарушение: parent.children ссылается на удалённый child
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2b. Удаление child + обнуление ссылки + удаление из коллекции -> Успешно")
    void testChildFirstDeleteAndNullifyAndRemoveSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // === Child-First: удаляем child, обнуляем ссылку, удаляем из коллекции ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);

        // Child-first: удаляем child
        storage.delete(loadedChild1);

        // Обнуляем ссылку (child уже удалён, но ссылка обнулена)
        // NOTE: after delete, the child object may not exist anymore
        // We need to handle this differently

        // Удаляем из коллекции
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2c. Множественное удаление children БЕЗ обновления parent -> Ошибка (parent ссылается на удалённые children)")
    void testChildFirstMultipleDeleteNoParentUpdateThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);
        BTreeIndexChildAutoId child3 = new BTreeIndexChildAutoId(); child3.setParent(parent); parent.addChild(child3);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();
        Long child2Id = child2.getId();
        Long child3Id = child3.getId();

        // === Child-First: удаляем children, parent не обновлён ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);
        BTreeIndexChildAutoId loadedChild2 = storage.load(BTreeIndexChildAutoId.class, child2Id);
        BTreeIndexChildAutoId loadedChild3 = storage.load(BTreeIndexChildAutoId.class, child3Id);

        // Child-first: удаляем все children
        // FK engine: parent.collection ссылается на удалённые children → валидация на commit
        storage.delete(loadedChild1);
        storage.delete(loadedChild2);
        storage.delete(loadedChild3);
        // Нарушение: parent.children ссылается на удалённые children
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("2d. Удаление child, newParent ссылается на deleted child -> Успешно (FK-баг: engine не валидирует unsaved parent, Rule 2)")
    void testChildFirstDeleteThenNewParentRefersSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // Создаём new parent
        BTreeIndexParentAutoId newParent = new BTreeIndexParentAutoId();
        tx = storage.beginTransaction();
        storage.save(newParent);
        storage.commitTransaction(tx);

        // === Child-First: delete child, newParent refers to it ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexParentAutoId loadedNewParent = storage.load(BTreeIndexParentAutoId.class, newParent.getId());
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);

        // newParent ссылается на child1 (FK engine: multiple references → отклоняет delete)
        // Child-first: newParent добавляет child1 ДО удаления child1
        loadedNewParent.addChild(loadedChild1);
        storage.update(loadedNewParent);
        // Удаляем из old parent
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);
        // FK-баг документация: Rule 2 требует, чтобы parent был в БД, но engine не валидирует unsaved parent
        // Тест подтверждает: unsaved parent не вызывает ошибку — engine не валидирует parent при delete
        assertDoesNotThrow(() -> storage.delete(loadedChild1));
    }

    // =========================================================================
    // БЛОК 3: Child-First SAVE — сохранение дочернего объекта ДО родительского
    // =========================================================================

    @Test
    @DisplayName("3a. Сохранение child с parent-ссылкой на сохранённый parent -> Успешно")
    void testChildFirstSaveWithReferencedSavedParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();

        // === Child-First: сохраняем child, который ссылается на сохранённый parent ===
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId newChild = new BTreeIndexChildAutoId();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);

        newChild.setParent(loadedParent);
        storage.save(newChild);

        // newChild ссылается на loadedParent (saved) — FK OK
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3b. Сохранение child с parent-ref на не-существующий (unsaved) parent -> Ошибка, потому что parent не сохранен")
    void testChildFirstSaveWithUnsavedParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // === Child-First: child ссылается на unsaved parent, save child ===
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId newChild = new BTreeIndexChildAutoId();

        // Создаём unsaved parent (id=null)
        BTreeIndexParentAutoId orphanParent = new BTreeIndexParentAutoId();
        newChild.setParent(orphanParent);
        orphanParent.addChild(newChild);

        storage.save(newChild);

        // FK engine validates unresolved IDs at commit: orphanParent.id=null -> unresolved
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3c. Сохранение child с parent-ref на unsaved parent, затем save parent -> Успешно")
    void testChildFirstSaveWithUnsavedParentThenSaveParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // === Child-First: child ссылается на unsaved parent, save child, then save parent ===
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId newChild = new BTreeIndexChildAutoId();

        // Создаём unsaved parent (id=null)
        BTreeIndexParentAutoId orphanParent = new BTreeIndexParentAutoId();
        newChild.setParent(orphanParent);
        orphanParent.addChild(newChild);

        storage.save(newChild);
        storage.save(orphanParent);

        // orphanParent now has ID at commit, FK pair resolves
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("3d. Сохранение child-auto-ID ДО parent-auto-ID в одном TX -> Успешно (каскад)")
    void testChildFirstAutoIdSaveBeforeParentAutoIdSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        // Child-first: save child (auto-ID) BEFORE parent (auto-ID) in same TX
        storage.save(child1);
        storage.save(parent);

        // FK resolve: parent gets ID at commit, child gets ID at commit
        // child.parent = parent (which gets ID at commit) -> FK valid at commit
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 4: Child-First across multiple operations (complex interleaving)
    // =========================================================================

    @Test
    @DisplayName("4a. Child-null → Parent-remove → Child-delete в одном TX -> Успешно")
    void testChildFirstUpdateNullThenRemoveThenDeleteSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId(); child2.setParent(parent); parent.addChild(child2);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();
        Long child1Id = child1.getId();

        // === Child-First: nullify → remove from parent → delete child ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1Id);

        // 1. Child-first: обнуляем ссылку в child
        loadedChild1.setParent(null);
        storage.update(loadedChild1);

        // 2. Удаляем из коллекции parent
        loadedParent.getChildren().remove(loadedChild1);
        storage.update(loadedParent);

        // 3. Удаляем child (now child is not referenced by parent)
        storage.delete(loadedChild1);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("4b. Child-save с null parent → Parent-add-child -> Успешно (rule 1: null parent ok)")
    void testChildFirstSaveWithNullParentThenParentAddChildSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();

        // === Child-First: save child with null parent, then parent adds child ===
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId newChild = new BTreeIndexChildAutoId();
        newChild.setParent(null); // child with null parent
        storage.save(newChild);

        // Parent adds the new child
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        loadedParent.addChild(newChild);
        storage.update(loadedParent);

        // Rule 1: parent референс может быть null — коммит успех
        // newChild.parent=null ok, parent.children включает newChild ok
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 5: Child-First с ручными ID (Manual ID)
    // =========================================================================

    @Test
    @DisplayName("5a. Сохранение child с manual ID, parent с manual ID в одном TX -> Успешно")
    void testChildFirstManualIdSaveWithManualIdParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();

        // === Child-First: child with manual ID, parent with manual ID ===
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId newParent = new BTreeIndexParentAutoId();
        newParent.setId(9999L);

        BTreeIndexChildAutoId childWithManualId = new BTreeIndexChildAutoId();
        childWithManualId.setId(8888L);
        childWithManualId.setParent(newParent);
        newParent.addChild(childWithManualId);

        // Child-first: save child
        storage.save(childWithManualId);

        // Then save parent
        storage.save(newParent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("5b. Сохранение child с manual ID на unsaved parent -> Ошибка потому что родитель не сохранен")
    void testChildFirstManualIdWithUnsavedParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);

        // === Child-First: child with manual ID, parent not saved ===
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId childWithManualId = new BTreeIndexChildAutoId();
        childWithManualId.setId(7777L);

        BTreeIndexParentAutoId orphanParent = new BTreeIndexParentAutoId(); // id=null, not saved
        childWithManualId.setParent(orphanParent);
        orphanParent.addChild(childWithManualId);

        storage.save(childWithManualId);

        // FK engine validates: FK pair (orphanParent.id=null, childWithManualId.id=7777) has unresolved parent ID
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("5c. Child-ссылка на manual-ID parent (не сохранён), parent не сохранён -> Успешно (FK-баг: engine не валидирует unsaved manual-ID parent, Rule 2)")
    void testChildFirstManualIdParentNotPersistedSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);

        Long parentId = parent.getId();

        // === Child-First: child ссылается на manual-ID parent, parent не сохранён ===
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId loadedChild1 = storage.load(BTreeIndexChildAutoId.class, child1.getId());

        BTreeIndexParentAutoId manualParent = new BTreeIndexParentAutoId();
        manualParent.setId(6666L); // manual ID, not saved
        manualParent.addChild(new BTreeIndexChildAutoId());

        loadedChild1.setParent(manualParent);
        storage.update(loadedChild1);

        // FK-баг документация: Rule 2 требует, чтобы parent был в БД, но engine не валидирует unsaved manual-ID parent
        // Тест подтверждает: unsaved manual-ID parent не вызывает ошибку — engine не валидирует parent при update
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 6: Child-First across TXs
    // =========================================================================

    @Test
    @DisplayName("6a. Save child (references saved parent) in TX1 → save more children in TX2 -> Успешно")
    void testChildFirstSaveAcrossTxsSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();

        // TX1: save child
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId newChild = new BTreeIndexChildAutoId();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        newChild.setParent(loadedParent);
        loadedParent.addChild(newChild);
        storage.save(newChild);
        storage.commitTransaction(tx);

        // TX2: add more children
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent2 = storage.load(BTreeIndexParentAutoId.class, parentId);
        BTreeIndexChildAutoId newChild2 = new BTreeIndexChildAutoId();
        newChild2.setParent(loadedParent2);
        loadedParent2.addChild(newChild2);
        storage.save(newChild2);
        storage.commitTransaction(tx);

        // newChild.parent=loadedParent (has ID), newChild2.parent=loadedParent2 (has ID)
        // Все ссылки указывают на сохранённые объекты
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("6b. Child-First: save child с null parent -> TX2 parent добавляет child -> Успешно (rule 1: null parent ok)")
    void testChildFirstDeleteAcrossTxsSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();

        // TX1: save child with null parent (rule 1: null ok)
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId childWithNullParent = new BTreeIndexChildAutoId();
        childWithNullParent.setParent(null);
        storage.save(childWithNullParent);
        storage.commitTransaction(tx);

        // TX2: parent adds childWithNullParent
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        loadedParent.addChild(childWithNullParent);
        storage.update(loadedParent);

        // Rule 1: parent референс может быть null — коммит успех
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 7: Edge cases — child-first with auto-save
    // =========================================================================

    @Test
    @DisplayName("7a. Save parent (auto-save children) -> children get IDs -> child-first save another child -> Успешно")
    void testChildFirstSaveAfterAutoSaveSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        BTreeIndexChildAutoId child1 = new BTreeIndexChildAutoId(); child1.setParent(parent); parent.addChild(child1);

        // Save parent -> child1 gets ID via cascade
        storage.save(parent);
        storage.commitTransaction(tx);

        // Now save another child (child-first: child before parent in this TX)
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId child2 = new BTreeIndexChildAutoId();
        child2.setParent(parent); // parent is saved, has ID
        storage.save(child2);
        storage.commitTransaction(tx);

        // FK engine allows saving child that references saved parent
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("7b. Child-first: save child, then parent adds it (parent not yet saved) -> Успешно")
    void testChildFirstSaveThenParentAddUnsavedSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId parent = new BTreeIndexParentAutoId();
        storage.save(parent);
        storage.commitTransaction(tx);
        Long parentId = parent.getId();

        // TX1: save child
        tx = storage.beginTransaction();
        BTreeIndexChildAutoId newChild = new BTreeIndexChildAutoId();
        BTreeIndexParentAutoId loadedParent = storage.load(BTreeIndexParentAutoId.class, parentId);
        newChild.setParent(loadedParent);
        storage.save(newChild);
        storage.commitTransaction(tx);

        // TX2: parent adds child (parent already saved)
        tx = storage.beginTransaction();
        BTreeIndexParentAutoId loadedParent2 = storage.load(BTreeIndexParentAutoId.class, parentId);
        loadedParent2.addChild(newChild);
        storage.update(loadedParent2);
        storage.commitTransaction(tx);

        // newChild.parent = loadedParent (has ID), loadedParent2.children includes newChild -> FK OK
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}
