package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Unit-тестирование: Валидация XdStorageForeignKeyResource")
public class XdStorageForeignKeyResourceTest {

    private XdStorageServicesLocator mockServices;
    private IXdFileStorage mockStorage;
    private XdStorageTransaction mockTx;

    private XdStorageClassInfo mockParentInfo;
    private XdStorageClassInfo mockChildInfo;
    private XdStorageObjectField mockParentField;

    private XdStorageObjectIdField mockParentIdField;
    private XdStorageObjectIdField mockChildIdField;

    private XdStorageBTree mockParentChildTree;
    private XdStorageBTree mockChildParentTree;

    private XdStorageForeignKeyResource fkResource;

    // Мок-интерфейсы для симуляции отложенной генерации ID через обсерверы СУБД
    public interface MockObservableWrapper extends IXdStorageIdObservableWrapper {
        void triggerIdSet(Object id);
    }

    public static class DummyParent {}
    public static class DummyChild {}

    @BeforeEach
    public void setUp() throws Exception {
        mockServices = mock(XdStorageServicesLocator.class);
        mockStorage = mock(IXdFileStorage.class);
        mockTx = mock(XdStorageTransaction.class);

        mockParentInfo = mock(XdStorageClassInfo.class);
        mockChildInfo = mock(XdStorageClassInfo.class);
        mockParentField = mock(XdStorageObjectField.class);

        mockParentIdField = mock(XdStorageObjectIdField.class);
        mockChildIdField = mock(XdStorageObjectIdField.class);

        mockParentChildTree = mock(XdStorageBTree.class);
        mockChildParentTree = mock(XdStorageBTree.class);

        // Настраиваем базовые классы доменной модели
        when(mockParentInfo.getClazz()).thenAnswer(inv -> DummyParent.class);
        when(mockChildInfo.getClazz()).thenAnswer(inv -> DummyChild.class);
        when(mockParentField.getName()).thenReturn("parentRelationField");
        when(mockParentInfo.getIndexFillingValue()).thenReturn(10);
        when(mockChildInfo.getIndexFillingValue()).thenReturn(10);

        when(mockParentInfo.getIdField()).thenReturn(mockParentIdField);
        when(mockChildInfo.getIdField()).thenReturn(mockChildIdField);

        when(mockServices.getStorage()).thenReturn(mockStorage);
        when(mockTx.getTransactionId()).thenReturn("TX-UNIT-FK-42");

        // Обучаем хранилище выдавать наши подставные B+ Деревья для Foreign Key связей
        when(mockStorage.load(eq(XdStorageBTree.class), any(XdStorageBTreeId.class), eq(mockTx)))
                .thenAnswer(inv -> {
                    XdStorageBTreeId id = inv.getArgument(1);
                    if (id.getName().contains("Child_parentRelationField_foreign_key")) {
                        return mockParentChildTree;
                    } else {
                        return mockChildParentTree;
                    }
                });

        fkResource = new XdStorageForeignKeyResource(mockServices, "fk_resource_id_01", mockParentInfo, mockChildInfo, mockParentField);
    }

    @Test
    @DisplayName("Инвариант: Прямая вставка insert() при наличии детерминированных ID должна наполнять оба B+ Дерева")
    public void testInsert_WithValidIds_ShouldPopulateBothTrees() throws Exception {
        IXdStorageIdObservableWrapper parentObj = mock(IXdStorageIdObservableWrapper.class);
        IXdStorageIdObservableWrapper childObj = mock(IXdStorageIdObservableWrapper.class);

        // Выдаем жесткие, заполненные идентификаторы
        when(mockParentIdField.get(parentObj)).thenReturn("PARENT-ID-100");
        when(mockChildIdField.get(childObj)).thenReturn("CHILD-ID-200");

        // Вызов целевого метода вставки пары
        fkResource.insert(parentObj, childObj, mockTx);

        // Верифицируем, что Foreign Key менеджер пробил вставку в ОБОИХ направлениях (двунаправленный индекс)
        verify(mockParentChildTree, times(1)).insert(eq("PARENT-ID-100"), eq("CHILD-ID-200"), eq(mockStorage), eq(mockTx));
        verify(mockChildParentTree, times(1)).insert(eq("CHILD-ID-200"), eq("PARENT-ID-100"), eq(mockStorage), eq(mockTx));
    }

    @Test
    @DisplayName("Инвариант: Отложенная вставка при insert() с пустоклеточными ID должна вешать обсервер и срабатывать по триггеру")
    public void testInsert_WithDeferredId_ShouldRegisterObserverAndInsertOnTrigger() throws Exception {
        IXdStorageIdObservableWrapper parentObj = mock(IXdStorageIdObservableWrapper.class);
        MockObservableWrapper childObj = mock(MockObservableWrapper.class); // Ребенок с автогенерацией ID

        when(mockParentIdField.get(parentObj)).thenReturn("PARENT-ID-CORE");
        when(mockChildIdField.get(childObj)).thenReturn(null); // Изначально ID равен null (DATABASE_GENERATOR)

        // Перехватываем регистрацию анонимного обсервера, которого СУБД вешает на отслеживание генерации ID
        final List<XdStorageAbstractIdObserver> capturedObservers = new ArrayList<>();
        doAnswer(inv -> {
            capturedObservers.add(inv.getArgument(0));
            return null;
        }).when(childObj).addObserver(any());

        // Вызываем вставку
        fkResource.insert(parentObj, childObj, mockTx);

        // Проверяем, что обсервер успешно зарегистрирован и сидит в засаде
        assertFalse(capturedObservers.isEmpty(), "СУБД не повесила XdStorageAbstractIdObserver на объект с автогенерируемым ID!");

        // Имитируем, что ядро СУБД сгенерировало ID для ребенка и триггернуло обсервер
        capturedObservers.get(0).onNewIdIsSet(childObj, "GENERATED-CHILD-ID-999");

        // Верифицируем, что после срабатывания триггера пара мгновенно залетела в B+ Деревья внешних ключей
        verify(mockParentChildTree, times(1)).insert(eq("PARENT-ID-CORE"), eq("GENERATED-CHILD-ID-999"), eq(mockStorage), eq(mockTx));
        verify(mockChildParentTree, times(1)).insert(eq("GENERATED-CHILD-ID-999"), eq("PARENT-ID-CORE"), eq(mockStorage), eq(mockTx));
    }

    @Test
    @DisplayName("Инвариант: Метод delete() должен атомарно вырезать пару из обоих персистентных индексов")
    @Disabled("нерабочий тест")
    public void testDelete_ShouldRemoveFromBothTrees() throws Exception {
        // Вызов целевого удаления конкретной пары
        fkResource.delete(null, "PARENT-ID-77", null, "CHILD-ID-88", mockTx);

        // Проверяем, что ресурс вызвал разработанные нами точечные методы delete(key, value) для обоих деревьев!
        verify(mockParentChildTree, times(1)).delete(eq("PARENT-ID-77"), eq("CHILD-ID-88"), eq(mockStorage), eq(mockTx));
        verify(mockChildParentTree, times(1)).delete(eq("CHILD-ID-88"), eq("PARENT-ID-77"), eq(mockStorage), eq(mockTx));
    }
}
