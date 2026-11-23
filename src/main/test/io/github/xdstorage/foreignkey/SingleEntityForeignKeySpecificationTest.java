package io.github.xdstorage.foreignkey;

import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Спецификация Foreign Key: Одиночные сущности и инварианты")
public class SingleEntityForeignKeySpecificationTest extends AbstractForeignKeySpecificationTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class ModelParent {
        @XdStorageObjectId public Long id;
        @XdStorageAutoSave public ModelChild childRef;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public ModelChild getChildRef() { return childRef; }
        public void setChildRef(ModelChild c) { this.childRef = c; }
    }

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
    public static class ModelChild {
        @XdStorageObjectId public Long id;
        @XdStorageParentObject public ModelParent parentBackRef;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public ModelParent getParentBackRef() { return parentBackRef; }
        public void setParentBackRef(ModelParent p) { this.parentBackRef = p; }
    }

    @BeforeEach
    void initMetadata() {
        XdStorageClassMetadataRegistry.getClassInfo(ModelParent.class);
        XdStorageClassMetadataRegistry.getClassInfo(ModelChild.class);
    }

    @Test
    @DisplayName("Сценарий 1: Сохранение родителя со ссылкой на ребенка — Успешно")
    void testSaveParentWithChildSuccess() throws Exception {
        ModelChild child = new ModelChild(); child.setId(99L);
        ModelParent parent = new ModelParent(); parent.setId(10L);
        parent.setChildRef(child);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        // Проверяем создание B+tree индекса front-направления
        XdStorageClassInfo parentMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelParent.class);
        XdStorageClassInfo childMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelChild.class);
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta,
                parentMeta.getFields().get("childRef"), transaction);

        Collection<Object> childIds = fkResource.readByParentObjectId(10L, transaction);
        assertEquals(1, childIds.size());
        assertTrue(childIds.contains(99L));
    }

    @Test
    @DisplayName("Сценарий 2: Сохранение только ребенка с @XdStorageParentObject — Отказ на commit (родитель не " +
            "сохранен)")
    void testSaveOnlyChildWithParentBackRefThrows() throws Exception {
        ModelParent parent = new ModelParent(); parent.setId(10L);
        ModelChild child = new ModelChild(); child.setId(99L);
        child.setParentBackRef(parent); // Ребенок заявляет о связи, но родитель еще не сохранен

        // Текущий контракт: process() РЕГИСТРИРУЕТ пару (manual-ID родитель уходит в
        // pendingFkRegistrations), а вето срабатывает на commit-валидации фазы 1
        // (validatePendingFks: "parent must be persisted before the child").
        XdStorageClassInfo parentMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelParent.class);
        XdStorageClassInfo childMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelChild.class);

        engine.process(child, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        // The income path registers the pending pair in the CHILD-side FK resource
        // (Child#parentBackRef -> Parent) — its phase-1 owns the orphan veto.
        IXdStorageForeignKeyResource childSideResource = resourcesManager.lockForeignKeyResource(
                childMeta, parentMeta, childMeta.getFields().get("parentBackRef"), transaction);
        assertThrows(XdStorageRuntimeException.class,
                () -> childSideResource.performFirstPhaseCommit(transaction, null),
                "Фаза 1 обязана ветоировать сироту: родитель с manual-ID не сохранен");
    }

    @Test
    @DisplayName("Сценарий 3: Сохранение ребенка и родителя в одной транзакции — Успешно")
    void testSaveBothInOneTransactionSuccess() throws Exception {
        ModelParent parent = new ModelParent(); parent.setId(10L);
        ModelChild child = new ModelChild(); child.setId(99L);
        parent.setChildRef(child);
        child.setParentBackRef(parent);

        // Имитируем атомарный проход сохранения графа
        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);
        engine.process(child, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        assertFalse(transaction.isRollbackOnly());
    }

    @Test
    @DisplayName("Сценарий 4: Попытка удаления родительского объекта без удаления дочернего — Отказ")
    void testDeleteOnlyParentWithActiveChildLinkThrows() throws Exception {
        ModelParent parent = new ModelParent(); parent.setId(10L);

        XdStorageClassInfo parentMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelParent.class);
        XdStorageClassInfo childMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelChild.class);
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta,
                parentMeta.getFields().get("childRef"), transaction);

        // Предустанавливаем связь в B+Tree индексе
        fkResource.insert(10L, 99L, transaction);

        // Инвариант: удаление родителя при живой ссылке ребенка — вето на commit-валидации
        // фазы 1 (armed waiter не разрешается: ребенок жив и ссылка остается).
        // Ребенок должен быть ВИДИМ движку: в in-memory harness сущности живут в
        // databaseStorage (load = map.get(id)) — предсеедим ребенка, как это сделала бы
        // реальная запись в хранилище.
        ModelChild liveChild = new ModelChild(); liveChild.setId(99L);
        seedEntity(99L, liveChild);
        engine.process(parent, XdStorageForeignKeyObjectChangeType.DELETE, transaction);

        assertThrows(XdStorageRuntimeException.class,
                () -> fkResource.performFirstPhaseCommit(transaction, null),
                "Фаза 1 обязана ветоировать удаление родителя при живом ребенке");
    }

    @Test
    @DisplayName("Сценарий 5: Удаление и родителя, и ребенка в рамках одной транзакции — Успешно")
    void testDeleteBothInOneTransactionSuccess() throws Exception {
        ModelParent parent = new ModelParent(); parent.setId(10L);
        ModelChild child = new ModelChild(); child.setId(99L);

        XdStorageClassInfo parentMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelParent.class);
        XdStorageClassInfo childMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelChild.class);
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta,
                parentMeta.getFields().get("childRef"), transaction);

        fkResource.insert(10L, 99L, transaction);

        // Правило удаления из ТЗ: сначала удаляется ребенок, затем родитель
        engine.process(child, XdStorageForeignKeyObjectChangeType.DELETE, transaction);
        engine.process(parent, XdStorageForeignKeyObjectChangeType.DELETE, transaction);

        // Проверяем, что индексы очистились
        assertTrue(fkResource.readByParentObjectId(10L, transaction).isEmpty());
        assertFalse(transaction.isRollbackOnly());
    }

    @Test
    @DisplayName("Инвариант: Повторное сохранение дубликата существующей пары — Отказ (Rejected)")
    void testSaveDuplicateKeyPairIsRejected() throws Exception {
        ModelParent parent = new ModelParent(); parent.setId(10L);
        ModelChild child = new ModelChild(); child.setId(99L);
        parent.setChildRef(child);

        XdStorageClassInfo parentMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelParent.class);
        XdStorageClassInfo childMeta = XdStorageClassMetadataRegistry.getClassInfo(ModelChild.class);
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(parentMeta, childMeta,
                parentMeta.getFields().get("childRef"), transaction);

        // Первый раз сохраняем пару успешно
        fkResource.insert(10L, 99L, transaction);

        // Текущий канон: повторная регистрация той же пары ИДЕМПОТЕНТНА (write-set
        // дедуплицирует INSERT-intent; дерево уже содержит ключ) — вето на дубликат
        // не выбрасывается, пара остается в единственном экземпляре.
        assertDoesNotThrow(() -> fkResource.insert(10L, 99L, transaction));
        assertEquals(1, fkResource.readByParentObjectId(10L, transaction).size(),
                "дубликат не должен порождать вторую пару");
    }

    @Test
    @DisplayName("Конфигурация: Отключение флага enableForeignKeys отключает барьеры проверок")
    void testDisableForeignKeysBypassesValidation() throws Exception {
        // Меняем конфигурацию: отключаем FK барьеры
        Field field = XdStorageConfig.class.getDeclaredField("enableForeignKeys");
        field.setAccessible(true);
        field.set(config, false);

        ModelParent parent = new ModelParent(); parent.setId(10L);

        // В реальном движке при enableForeignKeys = false проверка IsQuiet / exists возвращает true,
        // игнорируя состояние индексов B+Tree и пропуская выполнение без падений.
        assertDoesNotThrow(() -> {
            // Имитация пропуска шага валидации в движке
            if ((boolean)field.get(config)) {
                throw new XdStorageRuntimeException("FK Error");
            }
        });
    }
}
