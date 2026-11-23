package io.github.xdstorage.resource;

import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("1. Жизненный цикл кэша: заполнение, двухфазный коммит и откат")
public class XdStorageCacheLifecycleTest extends AbstractCacheResourceTest {

    @Test
    @DisplayName("Первичное заполнение кэша и подсчет объектов")
    public void testFillCache_AndCount_HappyPath() {
        TestEntity entity1 = new TestEntity("A");
        TestEntity entity2 = new TestEntity("B");

        resourceCache.fillCache(Arrays.asList(entity1, entity2));

        assertEquals(2, resourceCache.getObjectsCount());
        assertTrue(resourceCache.hasObject((long) entity1.hashCode()));
        assertTrue(resourceCache.hasObject((long) entity2.hashCode()));
    }

    @Test
    @DisplayName("Успешный сквозной цикл транзакции: Prepare -> Perform -> Commit")
    public void testCommit_FullLifecycle_HappyPath() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-commit-cycle");
        TestEntity entity = new TestEntity("CommitMe");
        long id = 111L;
        when(mockIdField.get(entity)).thenReturn(id);
        when(mockCloner.unwrapAndClone(entity)).thenReturn(entity);

        resourceCache.insert(entity, tx);
        assertTrue(resourceCache.hasChanges(tx));

        XdStorageTransactionResourceChanges mockCollector = mock(XdStorageTransactionResourceChanges.class);

        assertDoesNotThrow(() -> resourceCache.prepareCommit(tx, mockCollector));
        assertDoesNotThrow(() -> resourceCache.performCommit(tx, mockCollector));
        assertDoesNotThrow(() -> resourceCache.commit(tx));

        assertFalse(resourceCache.hasChanges(tx));

        XdStorageTransaction txNewReader = mockTransaction("tx-new-reader");
        when(mockCloner.cloneAndWrap(eq(entity), any(), eq(txNewReader))).thenReturn(entity);
        Object finalResult = resourceCache.read(id, txNewReader);
        assertSame(entity, finalResult);
    }

    @Test
    @DisplayName("Откат (Rollback) должен полностью очищать транзакционные изменения")
    public void testRollback_ShouldRevertChangesCleanly() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-rollback");
        TestEntity entity = new TestEntity("RollbackMe");
        long id = 222L;
        when(mockIdField.get(entity)).thenReturn(id);
        when(mockCloner.unwrapAndClone(entity)).thenReturn(entity);

        resourceCache.insert(entity, tx);
        assertTrue(resourceCache.hasChanges(tx));

        assertDoesNotThrow(() -> resourceCache.rollback(tx));

        assertFalse(resourceCache.hasChanges(tx));
        assertNull(resourceCache.read(id, mockTransaction("tx-other")));
    }
}
