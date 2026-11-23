package io.github.xdstorage.search;

import io.github.xdstorage.AbstractXdStorageTest;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.search.data.XdStorageSearchIndexRecord;
import io.github.xdstorage.search.key.XdStorageSearchIndexKey;
import io.github.xdstorage.search.query.IXdStorageCriterion;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("XdStorageSearchManager: оркестрация индексирования и поиска (мок-окружение)")
public class XdStorageSearchManagerTest extends AbstractXdStorageTest {

    /** Доступ к protected buildIndexRecord из пакета тестов. */
    private static final class TestableSearchManager extends XdStorageSearchManager {
        private TestableSearchManager(final XdStorageServicesLocator services) {
            super(services);
        }

        private XdStorageSearchIndexRecord build(final XdStorageSearchIndex index, final Object object,
                                                 final XdStorageTransaction transaction) throws XdStorageException {
            try {
                return buildIndexRecord(index, object, transaction);
            } catch (final io.github.xdstorage.exceptions.XdStorageConnectionException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // =========================================================================
    // hasIndex
    // =========================================================================

    @Test
    @DisplayName("hasIndex: класс с аннотацией индекса - true, без - false")
    public void testHasIndex() {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);

        final ClassPolicyEntity indexed = new ClassPolicyEntity();
        assertTrue(manager.hasIndex(indexed));

        final SearchEnginePlainEntity plain = new SearchEnginePlainEntity();
        assertFalse(manager.hasIndex(plain));
    }

    // =========================================================================
    // insert: одиночный
    // =========================================================================

    @Test
    @DisplayName("insert(null) - операция без взаимодействий с ресурсами")
    public void testInsertNullObject() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);

        manager.insert(null, mockTx);

        verifyNoInteractions(mockResourcesManager);
    }

    @Test
    @DisplayName("insert строит полную запись индекса (поля + все формы дочерних контейнеров) и делегирует ресурсу")
    public void testInsertBuildsFullRecordWithAllChildContainers() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);

        final SearchEngineShip ship = new SearchEngineShip();
        ship.setId(1L);
        ship.setName("Alpha");
        ship.setTonnage(100);
        ship.setCaptain(new SearchEngineCrewMember(10L, "Captain"));
        ship.addCrewMember(new SearchEngineCrewMember(11L, "Pilot"));
        ship.setPassengers(new SearchEngineCrewMember[]{new SearchEngineCrewMember(12L, "Steward")});
        ship.setVisitors(Collections.singletonMap("v1", new SearchEngineCrewMember(13L, "Inspector")));

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(SearchEngineShip.class);
        final IXdStorageSearchIndexDaoResource indexResource = mock(IXdStorageSearchIndexDaoResource.class);
        when(mockResourcesManager.lockSearchIndexResource(eq(clInfo), eq("ship_idx"), eq(mockTx)))
                .thenReturn(indexResource);

        final IXdStorageIndexDaoResource idResource = mock(IXdStorageIndexDaoResource.class);
        when(mockResourcesManager.lockIndexResource(eq(clInfo), eq(mockTx))).thenReturn(idResource);
        when(idResource.getObjectResourceId(1L, mockTx)).thenReturn("res-1");

        manager.insert(ship, mockTx);

        final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(indexResource).insert(captor.capture(), eq(mockTx));

        final XdStorageSearchIndexRecord record = (XdStorageSearchIndexRecord) captor.getValue();
        // все идентификаторы известны - запись без наблюдателей-оберток
        assertEquals(XdStorageSearchIndexRecord.class, record.getClass());
        assertEquals(1L, record.getId());
        assertEquals("res-1", record.getResourceId());
        assertEquals("Alpha", record.getPrimaryIndexValue());
        assertEquals(100, record.getFieldValue("tonnage"));

        final Map<Class<?>, Map<Object, Map<String, Map<String, Object>>>> children = record.getChildrenFieldValues();
        assertNotNull(children);
        final Map<Object, Map<String, Map<String, Object>>> byObject = children.get(SearchEngineCrewMember.class);
        assertEquals("Captain", byObject.get(10L).get("captain").get("rank"));
        assertEquals("Pilot", byObject.get(11L).get("crew").get("rank"));
        assertEquals("Steward", byObject.get(12L).get("passengers").get("rank"));
        assertEquals("Inspector", byObject.get(13L).get("visitors").get("rank"));
    }

    @Test
    @DisplayName("Пакетный insert: null-элементы и объекты с уже установленным ид пропускаются")
    public void testBatchInsertSkipsIdentifiedAndNulls() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);

        final SearchEngineShip alreadyIndexed = new SearchEngineShip();
        alreadyIndexed.setId(1L);
        alreadyIndexed.setName("Skip");

        final SearchEngineShip fresh = new SearchEngineShip();
        fresh.setName("Index");

        final IXdStorageSearchIndexDaoResource indexResource = mock(IXdStorageSearchIndexDaoResource.class);
        when(mockResourcesManager.lockSearchIndexResource(any(XdStorageClassInfo.class), eq("ship_idx"), eq(mockTx)))
                .thenReturn(indexResource);

        // R-23: пропуск по установленному ид - намеренный защитный барьер пакетной вставки
        manager.insert(Arrays.asList(alreadyIndexed, null, fresh), mockTx);

        final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(indexResource, times(1)).insert(captor.capture(), eq(mockTx));
        final XdStorageSearchIndexRecord record = (XdStorageSearchIndexRecord) captor.getValue();
        assertEquals("Index", record.getPrimaryIndexValue());
        assertNull(record.getId());
    }

    @Test
    @DisplayName("Отложенная финализация записи: ид назначаются фасадом ПОСЛЕ insert - запись достраивается " +
            "наблюдателями")
    public void testInsertDefersFinalizationUntilIdsAssigned() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);

        final SearchEngineShip ship = new SearchEngineShip();
        ship.setName("Nautilus");
        ship.setTonnage(50);
        final SearchEngineCrewMember captain = new SearchEngineCrewMember();
        captain.setRank("Captain");
        ship.setCaptain(captain);

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(SearchEngineShip.class);
        final IXdStorageSearchIndexDaoResource indexResource = mock(IXdStorageSearchIndexDaoResource.class);
        when(mockResourcesManager.lockSearchIndexResource(eq(clInfo), eq("ship_idx"), eq(mockTx)))
                .thenReturn(indexResource);
        final IXdStorageIndexDaoResource idResource = mock(IXdStorageIndexDaoResource.class);
        when(mockResourcesManager.lockIndexResource(eq(clInfo), eq(mockTx))).thenReturn(idResource);
        when(idResource.getObjectResourceId(55L, mockTx)).thenReturn("res-55");

        manager.insert(ship, mockTx);

        final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(indexResource).insert(captor.capture(), eq(mockTx));
        final XdStorageSearchIndexRecord record = (XdStorageSearchIndexRecord) captor.getValue();

        // запись вставлена незавершенной: ид и resourceId неизвестны
        assertNull(record.getId());
        assertNull(record.getResourceId());
        assertEquals("Nautilus", record.getPrimaryIndexValue());

        // фасад присваивает ид корню: капитан еще ждет - финализация отложена
        final IXdStorageIdObservableWrapper shipWrapper = XdStorageObserverService.getObservableWrapper(ship);
        ((SearchEngineShip) shipWrapper).setId(55L);
        assertNull(record.getId(), "капитан без ид - запись не может быть финализирована");

        // фасад присваивает ид потомку: все ид известны - запись достраивается
        final IXdStorageIdObservableWrapper captainWrapper = XdStorageObserverService.getObservableWrapper(captain);
        ((SearchEngineCrewMember) captainWrapper).setId(9L);

        assertEquals(55L, record.getId());
        assertEquals("res-55", record.getResourceId());
        assertEquals("Captain",
                record.getChildFieldValue("captain", SearchEngineCrewMember.class, "rank").iterator().next());
    }

    // =========================================================================
    // update / delete
    // =========================================================================

    @Test
    @DisplayName("update делегирует ресурсу запись с актуальными ид и первичным значением")
    public void testUpdateDelegatesToIndexResource() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);

        final ClassPolicyEntity entity = new ClassPolicyEntity();
        entity.setId(7L);
        entity.setValue("renamed");

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(ClassPolicyEntity.class);
        final IXdStorageSearchIndexDaoResource indexResource = mock(IXdStorageSearchIndexDaoResource.class);
        when(mockResourcesManager.lockSearchIndexResource(eq(clInfo), eq("idx_class_entity_value"), eq(mockTx)))
                .thenReturn(indexResource);
        final IXdStorageIndexDaoResource idResource = mock(IXdStorageIndexDaoResource.class);
        when(mockResourcesManager.lockIndexResource(eq(clInfo), eq(mockTx))).thenReturn(idResource);
        when(idResource.getObjectResourceId(7L, mockTx)).thenReturn("res-7");

        manager.update(entity, mockTx);

        final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(indexResource).update(captor.capture(), eq(mockTx));
        final XdStorageSearchIndexRecord record = (XdStorageSearchIndexRecord) captor.getValue();
        assertEquals(7L, record.getId());
        assertEquals("res-7", record.getResourceId());
        assertEquals("renamed", record.getPrimaryIndexValue());
    }

    @Test
    @DisplayName("delete делегирует ресурсу; пакетный delete вызывает ресурс для каждого объекта")
    public void testDeleteDelegates() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);

        final ClassPolicyEntity first = new ClassPolicyEntity();
        first.setId(1L);
        first.setValue("a");
        final ClassPolicyEntity second = new ClassPolicyEntity();
        second.setId(2L);
        second.setValue("b");

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(ClassPolicyEntity.class);
        final IXdStorageSearchIndexDaoResource indexResource = mock(IXdStorageSearchIndexDaoResource.class);
        when(mockResourcesManager.lockSearchIndexResource(eq(clInfo), eq("idx_class_entity_value"), eq(mockTx)))
                .thenReturn(indexResource);
        final IXdStorageIndexDaoResource idResource = mock(IXdStorageIndexDaoResource.class);
        when(mockResourcesManager.lockIndexResource(eq(clInfo), eq(mockTx))).thenReturn(idResource);
        when(idResource.getObjectResourceId(any(), eq(mockTx))).thenReturn("res");

        manager.delete(first, mockTx);
        manager.delete(Arrays.asList(first, second), mockTx);

        final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(indexResource, times(3)).delete(captor.capture(), eq(mockTx));
        final List<Object> records = captor.getAllValues();
        assertEquals(1L, ((XdStorageSearchIndexRecord) records.get(0)).getId());
        assertEquals(1L, ((XdStorageSearchIndexRecord) records.get(1)).getId());
        assertEquals(2L, ((XdStorageSearchIndexRecord) records.get(2)).getId());
    }

    // =========================================================================
    // search
    // =========================================================================

    @Test
    @DisplayName("search по несуществующему индексу бросает XdStorageException")
    public void testSearchUnknownIndexThrows() {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);
        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "x"), null);

        assertThrows(XdStorageException.class, () -> manager.search(ClassPolicyEntity.class, "missing_idx", query,
                mockTx));
    }

    @Test
    @DisplayName("search при отсутствии дерева в хранилище возвращает пустую коллекцию")
    public void testSearchWithoutTreeReturnsEmpty() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);
        final IXdStorage mockDb = mock(IXdStorage.class);
        when(mockServices.getStorage()).thenReturn(mockDb);
        when(mockDb.load(eq(XdStorageBTree.class), any(XdStorageBTreeId.class), eq(mockTx))).thenReturn(null);

        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "x"), null);

        final Collection<ClassPolicyEntity> result = manager.search(ClassPolicyEntity.class, "idx_class_entity_value",
                query, mockTx);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("search EQUALS: записи из дерева читаются по resourceId (StoreAsClassObjects) и дедуплицируются по ид")
    public void testSearchEqualsPathLoadsByResourceId() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);
        final IXdStorage mockDb = mock(IXdStorage.class);
        when(mockServices.getStorage()).thenReturn(mockDb);

        final XdStorageBTree tree = mock(XdStorageBTree.class);
        when(mockDb.load(eq(XdStorageBTree.class), any(XdStorageBTreeId.class), eq(mockTx))).thenReturn(tree);

        final XdStorageSearchIndexRecord first = new XdStorageSearchIndexRecord();
        first.setId(1L);
        first.setResourceId("res-1");
        final XdStorageSearchIndexRecord second = new XdStorageSearchIndexRecord();
        second.setId(2L);
        second.setResourceId("res-2");
        when(tree.find(any(XdStorageSearchIndexKey.class), eq(mockDb), eq(mockTx)))
                .thenReturn(Arrays.asList(first, second));

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(ClassPolicyEntity.class);
        final IXdStorageDaoResource dao = mock(IXdStorageDaoResource.class);
        when(mockResourcesManager.lockResource(eq("res-1"), eq(clInfo), eq(mockTx))).thenReturn(dao);
        when(mockResourcesManager.lockResource(eq("res-2"), eq(clInfo), eq(mockTx))).thenReturn(dao);
        final ClassPolicyEntity entity1 = new ClassPolicyEntity();
        entity1.setId(1L);
        entity1.setValue("alpha");
        final ClassPolicyEntity entity2 = new ClassPolicyEntity();
        entity2.setId(2L);
        entity2.setValue("alpha");
        when(dao.read(1L, mockTx)).thenReturn(entity1);
        when(dao.read(2L, mockTx)).thenReturn(entity2);

        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "alpha"), null);

        final Collection<ClassPolicyEntity> result = manager.search(ClassPolicyEntity.class, "idx_class_entity_value",
                query, mockTx);

        assertEquals(2, result.size());
        assertTrue(result.contains(entity1));
        assertTrue(result.contains(entity2));
    }

    @Test
    @DisplayName("search EQUALS: дополнительный критерий фильтрует записи дерева")
    public void testSearchEqualsWithFilteringCriterion() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);
        final IXdStorage mockDb = mock(IXdStorage.class);
        when(mockServices.getStorage()).thenReturn(mockDb);

        final XdStorageBTree tree = mock(XdStorageBTree.class);
        when(mockDb.load(eq(XdStorageBTree.class), any(XdStorageBTreeId.class), eq(mockTx))).thenReturn(tree);

        final XdStorageSearchIndexRecord matching = new XdStorageSearchIndexRecord();
        matching.setId(1L);
        matching.setResourceId("res-1");
        matching.setFieldValue("value", "alpha");
        final XdStorageSearchIndexRecord rejected = new XdStorageSearchIndexRecord();
        rejected.setId(2L);
        rejected.setResourceId("res-2");
        rejected.setFieldValue("value", "beta");
        when(tree.find(any(XdStorageSearchIndexKey.class), eq(mockDb), eq(mockTx)))
                .thenReturn(Arrays.asList(matching, rejected));

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(ClassPolicyEntity.class);
        final IXdStorageDaoResource dao = mock(IXdStorageDaoResource.class);
        when(mockResourcesManager.lockResource(anyString(), eq(clInfo), eq(mockTx))).thenReturn(dao);
        final ClassPolicyEntity entity = new ClassPolicyEntity();
        entity.setId(1L);
        entity.setValue("alpha");
        when(dao.read(1L, mockTx)).thenReturn(entity);

        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "primary"),
                new io.github.xdstorage.search.query.XdStorageCriterion("value", IXdStorageCriterionOperator.EQUALS,
                        "alpha"));

        final Collection<ClassPolicyEntity> result = manager.search(ClassPolicyEntity.class, "idx_class_entity_value",
                query, mockTx);

        assertEquals(1, result.size());
        assertSame(entity, result.iterator().next());
        verify(dao, times(1)).read(any(), eq(mockTx));
    }

    @Test
    @DisplayName("search NOT_EQUALS (сканирующий путь): primary-фильтр и критерий записи")
    public void testSearchNotEqualsScanPath() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);
        final IXdStorage mockDb = mock(IXdStorage.class);
        when(mockServices.getStorage()).thenReturn(mockDb);
        when(mockDb.load(eq(XdStorageBTree.class), any(XdStorageBTreeId.class), eq(mockTx)))
                .thenReturn(mock(XdStorageBTree.class));

        final XdStorageSearchIndexRecord kept = new XdStorageSearchIndexRecord();
        kept.setId(2L);
        kept.setResourceId("res-2");
        kept.setPrimaryIndexValue("beta");
        final XdStorageSearchIndexRecord filtered = new XdStorageSearchIndexRecord();
        filtered.setId(1L);
        filtered.setResourceId("res-1");
        filtered.setPrimaryIndexValue("alpha");

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(ClassPolicyEntity.class);
        final IXdStorageSearchIndexDaoResource indexResource = mock(IXdStorageSearchIndexDaoResource.class);
        when(mockResourcesManager.lockSearchIndexResource(eq(clInfo), eq("idx_class_entity_value"), eq(mockTx)))
                .thenReturn(indexResource);
        doAnswer(invocation -> {
            final IXdStorageWatcher<XdStorageSearchIndexRecord> watcher = invocation.getArgument(1);
            watcher.watch(kept);
            watcher.watch(filtered);
            return null;
        }).when(indexResource).watch(eq(mockTx), any());

        final IXdStorageDaoResource dao = mock(IXdStorageDaoResource.class);
        when(mockResourcesManager.lockResource(eq("res-2"), eq(clInfo), eq(mockTx))).thenReturn(dao);
        final ClassPolicyEntity entity = new ClassPolicyEntity();
        entity.setId(2L);
        entity.setValue("beta");
        when(dao.read(2L, mockTx)).thenReturn(entity);

        final XdStorageSearchQuery query = new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "alpha"), null);

        final Collection<ClassPolicyEntity> result = manager.search(ClassPolicyEntity.class, "idx_class_entity_value",
                query, mockTx);

        assertEquals(1, result.size());
        assertSame(entity, result.iterator().next());
    }

    @Test
    @DisplayName("search без primary-критерия: сканирование с фильтром по полю записи")
    public void testSearchNullPrimaryScanPath() throws Exception {
        final XdStorageSearchManager manager = new XdStorageSearchManager(mockServices);
        final IXdStorage mockDb = mock(IXdStorage.class);
        when(mockServices.getStorage()).thenReturn(mockDb);
        when(mockDb.load(eq(XdStorageBTree.class), any(XdStorageBTreeId.class), eq(mockTx)))
                .thenReturn(mock(XdStorageBTree.class));

        final XdStorageSearchIndexRecord withField = new XdStorageSearchIndexRecord();
        withField.setId(1L);
        withField.setResourceId("res-1");
        withField.setFieldValue("value", "target");
        final XdStorageSearchIndexRecord otherField = new XdStorageSearchIndexRecord();
        otherField.setId(2L);
        otherField.setResourceId("res-2");
        otherField.setFieldValue("value", "other");

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(ClassPolicyEntity.class);
        final IXdStorageSearchIndexDaoResource indexResource = mock(IXdStorageSearchIndexDaoResource.class);
        when(mockResourcesManager.lockSearchIndexResource(eq(clInfo), eq("idx_class_entity_value"), eq(mockTx)))
                .thenReturn(indexResource);
        doAnswer(invocation -> {
            final IXdStorageWatcher<XdStorageSearchIndexRecord> watcher = invocation.getArgument(1);
            watcher.watch(withField);
            watcher.watch(otherField);
            return null;
        }).when(indexResource).watch(eq(mockTx), any());

        final IXdStorageDaoResource dao = mock(IXdStorageDaoResource.class);
        when(mockResourcesManager.lockResource(eq("res-1"), eq(clInfo), eq(mockTx))).thenReturn(dao);
        final ClassPolicyEntity entity = new ClassPolicyEntity();
        entity.setId(1L);
        entity.setValue("target");
        when(dao.read(1L, mockTx)).thenReturn(entity);

        final XdStorageSearchQuery query = new XdStorageSearchQuery(null,
                new io.github.xdstorage.search.query.XdStorageCriterion("value", IXdStorageCriterionOperator.EQUALS,
                        "target"));

        final Collection<ClassPolicyEntity> result = manager.search(ClassPolicyEntity.class, "idx_class_entity_value",
                query, mockTx);

        assertEquals(1, result.size());
        assertSame(entity, result.iterator().next());
    }

    // =========================================================================
    // buildIndexRecord: прямой вызов
    // =========================================================================

    @Test
    @DisplayName("buildIndexRecord: null-значения дочерних контейнеров пропускаются без ошибок")
    public void testBuildIndexRecordSkipsNullChildren() throws Exception {
        final TestableSearchManager manager = new TestableSearchManager(mockServices);

        final SearchEngineShip ship = new SearchEngineShip();
        ship.setId(3L);
        ship.setName("Empty");
        ship.setTonnage(10);

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(SearchEngineShip.class);
        final XdStorageSearchIndex index = clInfo.getIndexes().get("ship_idx");

        final IXdStorageIndexDaoResource idResource = mock(IXdStorageIndexDaoResource.class);
        when(mockResourcesManager.lockIndexResource(any(XdStorageClassInfo.class), eq(mockTx))).thenReturn(idResource);
        when(idResource.getObjectResourceId(3L, mockTx)).thenReturn("res-3");

        final XdStorageSearchIndexRecord record = manager.build(index, ship, mockTx);

        assertEquals(3L, record.getId());
        assertEquals("res-3", record.getResourceId());
        assertEquals("Empty", record.getPrimaryIndexValue());
        assertEquals(10, record.getFieldValue("tonnage"));
        assertNull(record.getChildrenFieldValues());
    }
}
