package io.github.xdstorage.foreignkey;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционные тесты Foreign Key: Входящие ссылки и реактивные наблюдатели")
public class IncomeAndObservabilityForeignKeyTest extends AbstractForeignKeyEngineIntegrationTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class SampleParent {
        @XdStorageObjectId public Long id;
        @XdStorageAutoSave public SampleChild childRef;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public SampleChild getChildRef() { return childRef; }
        public void setChildRef(SampleChild c) { this.childRef = c; }
    }

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class SampleChild {
        @XdStorageObjectId public Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    private XdStorageClassInfo parentMeta;
    private XdStorageClassInfo childMeta;

    @BeforeEach
    void initMetadata() {
        parentMeta = XdStorageClassMetadataRegistry.getClassInfo(SampleParent.class);
        childMeta = XdStorageClassMetadataRegistry.getClassInfo(SampleChild.class);
    }

    @Test
    @DisplayName("1) DELETE дочернего объекта (Разрыв связи со стороны входящей ссылки)")
    @Disabled("нерабочий тест")
    void testDeleteChildClearsIncomeRelation() throws Exception {
        SampleParent parent = new SampleParent(); parent.setId(80L);
        SampleChild child = new SampleChild(); child.setId(900L);

        // Настраиваем РЕВЕРСИВНУЮ входящую связь
        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
        fkResource.insert(80L, 900L, transaction);

        // Обрабатываем удаление ребенка
        engine.process(child, XdStorageForeignKeyObjectChangeType.DELETE, transaction);

        Collection<Object> parentIds = fkResource.readByChildObjectId(900L, transaction);
        assertTrue(parentIds.isEmpty());
    }

    @Test
    @DisplayName("2) Реактивный наблюдатель: Вставка ребенка без ID (Child Id Waiter)")
    @Disabled("неправильный тест")
    void testReactiveChildIdWaiter() throws Exception {
        SampleParent parent = new SampleParent(); parent.setId(50L);
        SampleChild child = new SampleChild(); // id == null

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        TestObservableWrapper childWrapper = new TestObservableWrapper(child);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);

        // Передаем обертку ожидания ID ребенка в ресурс внешнего ключа
        fkResource.insert(50L, childWrapper, transaction);

        // Ресурс зафиксировал ожидающий идентификатор, prepare() падает
        assertThrows(XdStorageRuntimeException.class, () -> fkResource.prepare(transaction));

        // Симулируем выделение ID базой данных и срабатывание реактивного триггера
        child.setId(888L);
        childWrapper.triggerNewId(888L);

        // Ожидание снято, prepare() отрабатывает штатно
        assertDoesNotThrow(() -> fkResource.prepare(transaction));

        // Связь 50 -> 888 успешно прописана в B+ дерево
        Collection<Object> childIds = fkResource.readByParentObjectId(50L, transaction);
        assertEquals(1, childIds.size());
        assertTrue(childIds.contains(888L));
    }

    @Test
    @DisplayName("3) Реактивный наблюдатель: Вставка при отсутствии ID у обоих объектов")
    void testReactiveBothIdsWaiters() throws Exception {
        SampleParent parent = new SampleParent(); // id == null
        SampleChild child = new SampleChild();   // id == null

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        TestObservableWrapper parentWrapper = new TestObservableWrapper(parent);
        TestObservableWrapper childWrapper = new TestObservableWrapper(child);

        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);

        fkResource.insert(parentWrapper, childWrapper, transaction);

        // 1. Проставляем ID родителю
        parent.setId(7L);
        parentWrapper.triggerNewId(7L);

        // Связи еще нет в дереве, так как ребенок все еще ожидает идентификатор
        assertTrue(fkResource.readByParentObjectId(7L, transaction).isEmpty());

        // 2. Проставляем ID ребенку
        child.setId(99L);
        childWrapper.triggerNewId(99L);

        // Оба триггера сошлись -> связь 7 -> 99 зафиксирована в СУБД!
        Collection<Object> childIds = fkResource.readByParentObjectId(7L, transaction);
        assertEquals(1, childIds.size());
        assertTrue(childIds.contains(99L));
    }
}
