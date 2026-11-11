package io.github.xdstorage.foreignkey.index.hash;

import io.github.xdstorage.entities.foreignkeys.index.hash.manual.HashIndexChildManualId;
import io.github.xdstorage.entities.foreignkeys.index.hash.manual.HashIndexParentManualId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Вторичные ключи (Hash Index): Сценарии с ручной установкой идентификаторов (Manual ID)")
public class ForeignKeyManualIdSpecificationTest extends AbstractForeignKeyBaseSpecificationTest {

    // --- ТЕСТОВЫЕ СУЩНОСТИ ДЛЯ КОНТУРА ХЭШ-ИНДЕКСОВ С РУЧНЫМ ID ---

    // =========================================================================
    // БЛОК 1: СЦЕНАРИИ СОХРАНЕНИЯ (MANUAL ID)
    // =========================================================================

    @Test
    @DisplayName("Сохранение родительской сущности без дочерних сущностей -> Успешный коммит")
    void testSaveParentWithoutChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId();
        parent.setId(3001L); // Идентификатор явно задается в тесте

        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("Сохранение одной дочерней сущности без родителя -> Исключение на commit")
    void testSaveSingleChildWithoutParentThrows() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId();
        parent.setId(3002L);

        HashIndexChildManualId child = new HashIndexChildManualId();
        child.setId(4002L); // Идентификатор явно задается в тесте
        child.setParent(parent);
        parent.addChild(child);

        storage.save(child);
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("Сохранение всех дочерних сущностей с сохранением родительской сущности -> Успешный commit")
    void testSaveAllChildrenWithParentSuccess() throws Exception {
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId();
        parent.setId(3003L);

        HashIndexChildManualId child1 = new HashIndexChildManualId();
        child1.setId(4003L); child1.setParent(parent); parent.addChild(child1);
        HashIndexChildManualId child2 = new HashIndexChildManualId();
        child2.setId(4004L); child2.setParent(parent); parent.addChild(child2);

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
        // Наполнение базовой валидной вселенной
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId(); parent.setId(5001L);
        HashIndexChildManualId child = new HashIndexChildManualId(); child.setId(6001L);
        child.setParent(parent); parent.addChild(child);
        storage.save(parent);  // каскад @XdStorageAutoSave сохраняет ребёнка; явный save ребёнка после связанного родителя — косяк теста (вердикт мейнтейнера)
        storage.commitTransaction(tx);

        // Мутация
        tx = storage.beginTransaction();
        HashIndexParentManualId loadedParent = storage.load(HashIndexParentManualId.class, 5001L);
        HashIndexChildManualId targetChild = loadedParent.getChildren().iterator().next();
        loadedParent.getChildren().remove(targetChild);

        storage.update(loadedParent); // Нарушение: дочерняя запись осталась сиротой
        assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
    }

    // =========================================================================
    // БЛОК 3: СЦЕНАРИИ УДАЛЕНИЯ И ССЫЛОК (MANUAL ID)
    // =========================================================================

    @Test
    @DisplayName("Сценарий со ссылками: Удаление ребенка из коллекции родителя + Сброс parent в null + Апдейт обоих -> Успешный commit")
    void testClearLinkAndNullifyParentBackReferenceSuccess() throws Exception {
        // Наполнение базовой валидной вселенной
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId(); parent.setId(7001L);
        HashIndexChildManualId child = new HashIndexChildManualId(); child.setId(8001L);
        child.setParent(parent); parent.addChild(child);
        storage.save(parent);  // каскад @XdStorageAutoSave сохраняет ребёнка; явный save ребёнка после связанного родителя — косяк теста (вердикт мейнтейнера)
        storage.commitTransaction(tx);

        // Операция со ссылками
        tx = storage.beginTransaction();
        HashIndexParentManualId loadedParent = storage.load(HashIndexParentManualId.class, 7001L);
        HashIndexChildManualId loadedChild = loadedParent.getChildren().iterator().next();

        loadedParent.getChildren().remove(loadedChild);
        loadedChild.setParent(null); // Явный разрыв обратной ссылки в тесте вручную

        storage.update(loadedParent);
        storage.update(loadedChild);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }

    @Test
    @DisplayName("Удаление родителя и всех детей в рамках одной TX -> Успешный commit")
    void testLoadAllAndPurgeAllSuccess() throws Exception {
        // Наполнение базовой валидной вселенной
        tx = storage.beginTransaction();
        HashIndexParentManualId parent = new HashIndexParentManualId(); parent.setId(9001L);
        HashIndexChildManualId child = new HashIndexChildManualId(); child.setId(9501L);
        child.setParent(parent); parent.addChild(child);
        storage.save(parent);  // каскад @XdStorageAutoSave сохраняет ребёнка; явный save ребёнка после связанного родителя — косяк теста (вердикт мейнтейнера)
        storage.commitTransaction(tx);

        // Полное каскадное удаление
        tx = storage.beginTransaction();
        HashIndexParentManualId loadedParent = storage.load(HashIndexParentManualId.class, 9001L);
        HashIndexChildManualId loadedChild = storage.load(HashIndexChildManualId.class, 9501L);

        storage.delete(loadedChild);
        storage.delete(loadedParent);

        assertDoesNotThrow(() -> storage.commitTransaction(tx));
    }
}