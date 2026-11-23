package io.github.xdstorage;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("2. Спецификации CRUD-операций СУБД и барьеров политик хранения")
public class XdStorageCrudOperationsTest extends AbstractXdStorageTest {

    @BeforeEach
    public void localSetUp() throws Exception {
        baseSetUp();
    }

    @Test
    @DisplayName("Fail-fast защита: вызов save() вне активной транзакции обязан бросать XdStorageException")
    public void testSave_OutsideTransaction_ShouldThrowException() {
        when(mockTxManager.getCurrentTransaction()).thenReturn(null); // Имитируем отсутствие транзакции
        assertThrows(XdStorageException.class, () -> storage.save(new AbstractXdStorageTest.ClassPolicyEntity()));
    }

    @Test
    @DisplayName("Fail-fast защита: вставка объекта без аннотации @XdStorageObjectId обязана бросать " +
            "XdStorageException")
    public void testSave_InvalidEntityWithoutIdField_ShouldThrowException() {
        assertThrows(XdStorageException.class, () -> storage.save(new AbstractXdStorageTest.InvalidEntity(), mockTx));
    }

    @Test
    @DisplayName("Политика StoreAsClassObjects: save() обязан блокировать IndexResource и вызывать insert")
    public void testSave_StoreAsClassObjectsPolicy_ShouldRouteToIndexResource() throws Exception {
        ClassPolicyEntity entity = new ClassPolicyEntity();
        IXdStorageIndexDaoResource mockIndexRes = mock(IXdStorageIndexDaoResource.class);
        when(mockResourcesManager.lockIndexResource(any(), eq(mockTx))).thenReturn(mockIndexRes);
        when(mockSearchManager.hasIndex(entity)).thenReturn(false);

        assertDoesNotThrow(() -> storage.save(entity, mockTx));

        verify(mockResourcesManager, times(1)).lockIndexResource(any(), eq(mockTx));
        verify(mockIndexRes, times(1)).insert(entity, mockTx);
    }

    @Test
    @DisplayName("Политика StoreAsSingleObject: save() обязан каскадно блокировать References и Object ресурсы")
    public void testSave_StoreAsSingleObjectPolicy_ShouldRouteToMultipleResources() throws Exception {
        SinglePolicyEntity entity = new SinglePolicyEntity();
        IXdStorageDaoResource mockRefRes = mock(IXdStorageDaoResource.class);
        IXdStorageDaoResource mockObjRes = mock(IXdStorageDaoResource.class);

        when(mockResourcesManager.lockReferencesResource(any(), eq(mockTx))).thenReturn(mockRefRes);
        when(mockResourcesManager.lockObjectResource(eq(entity), eq(mockTx))).thenReturn(mockObjRes);
        when(mockSearchManager.hasIndex(entity)).thenReturn(false);

        assertDoesNotThrow(() -> storage.save(entity, mockTx));

        verify(mockRefRes, times(1)).insertReference(entity, mockTx);
        verify(mockObjRes, times(1)).insert(entity, mockTx);
    }

    @Test
    @DisplayName("Метод load(Class, id) обязан возвращать запрошенный объект из легитимного ресурса")
    public void testLoad_ById_ShouldQueryAppropriateResource() throws Exception {
        IXdStorageIndexDaoResource mockIndexRes = mock(IXdStorageIndexDaoResource.class);
        ClassPolicyEntity dummyResult = new ClassPolicyEntity();

        when(mockResourcesManager.lockIndexResource(any(), eq(mockTx))).thenReturn(mockIndexRes);
        when(mockIndexRes.read(eq(42L), eq(mockTx))).thenReturn(dummyResult);

        ClassPolicyEntity loaded = storage.load(ClassPolicyEntity.class, 42L, mockTx);

        assertNotNull(loaded);
        verify(mockIndexRes, times(1)).read(eq(42L), eq(mockTx));
    }
}
