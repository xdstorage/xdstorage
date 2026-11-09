package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexChildManualId;
import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexParentManualId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Вторичные ключи: Сценарии с ручной установкой идентификаторов (Manual ID)")
public class ForeignKeyManualIdSpecificationTest extends AbstractForeignKeyBaseSpecificationTest {

    // --- ТЕСТОВЫЕ СУЩНОСТИ С РУЧНЫМ УСТАНОВЛЕНИЕМ ID ---

    // =========================================================================
    // БЛОК 1: СЦЕНАРИИ СОХРАНЕНИЯ (MANUAL ID)
    // =========================================================================

    @Test
    @DisplayName("Сохранение родителя без дочерних сущностей -> Успешный коммит")
    void testSaveParentWithoutChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId();
        parent.setId(1001L); // Идентификатор устанавливается в самом тесте

        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("Сохранение одной дочерней сущности без родителя -> Исключение на commit")
    void testSaveSingleChildWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId();
        parent.setId(1002L);

        BTreeIndexChildManualId child = new BTreeIndexChildManualId();
        child.setId(2002L); // Идентификатор устанавливается в самом тесте
        child.setParent(parent);
        parent.addChild(child);

        storage.save(child);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("Сохранение всех дочерних сущностей с сохранением родительской сущности -> Успешный commit")
    void testSaveAllChildrenWithParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId();
        parent.setId(1003L);

        BTreeIndexChildManualId child1 = new BTreeIndexChildManualId();
        child1.setId(2003L); child1.setParent(parent); parent.addChild(child1);
        BTreeIndexChildManualId child2 = new BTreeIndexChildManualId();
        child2.setId(2004L); child2.setParent(parent); parent.addChild(child2);

        storage.save(child1);
        storage.save(child2);
        storage.save(parent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 2: СЦЕНАРИИ ОБНОВЛЕНИЯ (MANUAL ID)
    // =========================================================================

    @Test
    @DisplayName("Удаление ребенка из коллекции родителя и обновление только родителя -> Ошибка на commit")
    void testUpdateOnlyParentAfterRemovingChildThrows() throws Exception {
        // Базовое наполнение
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId(); parent.setId(5001L);
        BTreeIndexChildManualId child = new BTreeIndexChildManualId(); child.setId(6001L);
        child.setParent(parent); parent.addChild(child);
        storage.save(parent);
        storage.commitTransaction(tx);

        // Операция обновления
        tx = storage.beginTransaction();
        BTreeIndexParentManualId loadedParent = storage.load(BTreeIndexParentManualId.class, 5001L);
        BTreeIndexChildManualId targetChild = loadedParent.getChildren().iterator().next();
        loadedParent.getChildren().remove(targetChild);

        storage.update(loadedParent);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 3: СЦЕНАРИИ УДАЛЕНИЯ И ССЫЛОК (MANUAL ID)
    // =========================================================================

    @Test
    @DisplayName("Сценарий со ссылками: Удаление ребенка из коллекции родителя + Сброс parent в null + Апдейт обоих -> Успешный commit")
    void testClearLinkAndNullifyParentBackReferenceSuccess() throws Exception {
        // Базовое наполнение
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId(); parent.setId(7001L);
        BTreeIndexChildManualId child = new BTreeIndexChildManualId(); child.setId(8001L);
        child.setParent(parent); parent.addChild(child);
        storage.save(parent);
        storage.commitTransaction(tx);

        // Работа со ссылками
        tx = storage.beginTransaction();
        BTreeIndexParentManualId loadedParent = storage.load(BTreeIndexParentManualId.class, 7001L);
        BTreeIndexChildManualId loadedChild = loadedParent.getChildren().iterator().next();

        loadedParent.getChildren().remove(loadedChild);
        loadedChild.setParent(null); // Сбрасываем ссылку на родителя в тесте вручную

        storage.update(loadedParent);
        storage.update(loadedChild);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("Удаление родителя и ребенка совместно в одной TX -> Успешный commit")
    void testLoadAllAndPurgeAllSuccess() throws Exception {
        // Базовое наполнение
        tx = storage.beginTransaction();
        BTreeIndexParentManualId parent = new BTreeIndexParentManualId(); parent.setId(9001L);
        BTreeIndexChildManualId child = new BTreeIndexChildManualId(); child.setId(9501L);
        child.setParent(parent); parent.addChild(child);
        storage.save(parent);
        storage.commitTransaction(tx);

        // Каноническое каскадное удаление графа
        tx = storage.beginTransaction();
        BTreeIndexParentManualId loadedParent = storage.load(BTreeIndexParentManualId.class, 9001L);
        BTreeIndexChildManualId loadedChild = storage.load(BTreeIndexChildManualId.class, 9501L);

        storage.delete(loadedChild);
        storage.delete(loadedParent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}