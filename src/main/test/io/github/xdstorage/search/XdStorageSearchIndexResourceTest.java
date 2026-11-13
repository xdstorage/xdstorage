package io.github.xdstorage.search;

import io.github.xdstorage.search.data.XdStorageSearchKeyIndexRecord;
import io.github.xdstorage.serialization.IXdStorageIOFactory;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("XdStorageSearchIndexResource: транзакционная обвязка ресурса индекса")
public class XdStorageSearchIndexResourceTest {

    private XdStorageServicesLocator services;
    private XdStorageAbstractResourcesManager manager;
    private XdStorageTransaction tx;
    private XdStorageSearchIndexResource resource;

    @BeforeEach
    public void setUp() {
        services = mock(XdStorageServicesLocator.class);
        manager = mock(XdStorageAbstractResourcesManager.class);
        tx = mock(XdStorageTransaction.class);

        final IXdStorageIOFactory factory = mock(IXdStorageIOFactory.class);
        when(factory.newInstanceReader()).thenReturn(mock(IXdStorageObjectsReader.class));
        when(factory.newInstanceWriter()).thenReturn(mock(IXdStorageObjectsWriter.class));

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(SearchEngineShip.class);
        resource = new XdStorageSearchIndexResource(services, manager, "ship_idx", "idx-file-1", clInfo, factory);
    }

    @Test
    @DisplayName("Ресурс описан записью key-дерева и хранит свой идентификатор")
    public void testResourceIdentity() {
        assertEquals("idx-file-1", resource.getResourceId());
        assertEquals("idx-file-1", resource.getFileName());
        assertEquals(XdStorageSearchKeyIndexRecord.class, resource.getObjectsClass());
        assertSame(resource, resource.getDao());
    }

    @Test
    @DisplayName("Счетчик объектов и признак изменений: ресурс делегирует дереву Б+Дерева")
    public void testCountersAndChanges() throws Exception {
        assertEquals(0, resource.getObjectsCount());
        assertFalse(resource.hasChanges(tx));
    }

    @Test
    @DisplayName("Двухфазный коммит и откат - безопасные no-op (данные живут в деревьях)")
    public void testCommitPhasesAreNoOp() throws Exception {
        resource.prepare(tx);
        resource.performFirstPhaseCommit(tx, null);
        resource.performSecondPhaseCommit(tx, null);
        resource.rollbackPerformingFirstPhaseCommit(tx, Collections.emptyList());
        resource.rollback(tx);
        resource.lockForCommit(tx);
        resource.unlockAfterCommit(tx);
    }

    @Test
    @DisplayName("release возвращает ресурс менеджеру ресурсов")
    public void testReleaseDelegatesToManager() {
        resource.release(tx);

        verify(manager, times(1)).releaseResource(resource);
    }
}
