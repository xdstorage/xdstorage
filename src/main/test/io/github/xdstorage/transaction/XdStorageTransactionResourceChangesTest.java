package io.github.xdstorage.transaction;

import io.github.xdstorage.resource.IXdStorageInternalListener;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.trigger.XdStorageTriggerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collection;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Исправленный и полностью скомпилированный JUnit 5 тест для XdStorageTransactionResourceChanges.
 * Защищен от рассинхронизации пакетов перечисления XdStorageObjectOperationType.
 */
public class XdStorageTransactionResourceChangesTest {

    // Mock-ресурс СУБД для тестов
    private static class DummyResource implements IXdStorageResourceObject {
        @Override public Object getResourceId() { return "test_res"; }
        @Override public Class<?> getObjectsClass() { return Object.class; }
        @Override public long getObjectsCount() { return 0L; }
        @Override public boolean hasChanges(XdStorageTransaction tx) { return false; }
        @Override public Object getDao() { return null; }
        @Override public void prepare(XdStorageTransaction tx) {}
        @Override public void performFirstPhaseCommit(XdStorageTransaction tx, XdStorageTransactionResourceChanges r) {}
        @Override public void performSecondPhaseCommit(XdStorageTransaction tx, XdStorageTransactionResourceChanges r) {}
        @Override public void rollbackPerformingFirstPhaseCommit(XdStorageTransaction tx, Collection cs) {}
        @Override public void rollback(XdStorageTransaction tx) {}
        @Override public void release(XdStorageTransaction tx) {}
        @Override public void lockForCommit(XdStorageTransaction tx) {}
        @Override public void unlockAfterCommit(XdStorageTransaction tx) {}

        @Override
        public void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction) {

        }
    }

    // Mock-триггер менеджер для проверки вызовов
    private static class DummyTriggerManager extends XdStorageTriggerManager {
        private boolean performTriggersCalled = false;
        private Collection<XdStorageObjectChange> passedChanges;

        @Override
        public void performTriggers(XdStorageTransaction transaction, Collection<XdStorageObjectChange> changes) {
            this.performTriggersCalled = true;
            this.passedChanges = changes;
        }
    }

    private DummyResource mockResource;
    private XdStorageTransactionResourceChanges resourceChanges;

    @BeforeEach
    public void setUp() {
        mockResource = new DummyResource();
        resourceChanges = new XdStorageTransactionResourceChanges(mockResource);
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testInitialization_ShouldStoreResourceAndEmptyCollection() {
        assertSame(mockResource, resourceChanges.getResource());
        assertNotNull(resourceChanges.getChangesObjects());
        assertTrue(resourceChanges.getChangesObjects().isEmpty());
    }

    @Test
    public void testAddChangeObject_WithValidData_ShouldAccumulateCorrectly() {
        String objId = "user_42";
        String oldState = "{\"name\":\"Ivan\"}";
        String newState = "{\"name\":\"Ivan\",\"age\":30}";

        // Использование динамического определения типа для обхода нестыковок пакета енама
        io.github.xdstorage.resource.XdStorageObjectOperationType type = io.github.xdstorage.resource.XdStorageObjectOperationType.UPDATE;
        resourceChanges.addChangeObject(type, objId, oldState, newState);

        Collection<XdStorageObjectChange> changes = resourceChanges.getChangesObjects();
        assertEquals(1, changes.size());

        XdStorageObjectChange change = changes.iterator().next();
        assertEquals(type, change.type); // Проверка public final поля
        assertEquals(objId, change.id); //
        assertEquals(oldState, change.oldObject); //
        assertEquals(newState, change.newObject); //
    }

    @Test
    public void testPerformTriggers_ShouldForwardChangesToTriggerManager() {
        DummyTriggerManager mockTriggerManager = new DummyTriggerManager();
        io.github.xdstorage.resource.XdStorageObjectOperationType type = io.github.xdstorage.resource.XdStorageObjectOperationType.INSERT;
        resourceChanges.addChangeObject(type, "id_1", null, "data");

        resourceChanges.performTriggers(null, mockTriggerManager);

        assertTrue(mockTriggerManager.performTriggersCalled);
        assertSame(resourceChanges.getChangesObjects(), mockTriggerManager.passedChanges);
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testAddChangeObject_WithAllNullParameters_ShouldStoreSafely() {
        io.github.xdstorage.resource.XdStorageObjectOperationType type = io.github.xdstorage.resource.XdStorageObjectOperationType.DELETE;
        assertDoesNotThrow(() -> {
            resourceChanges.addChangeObject(type, null, null, null);
        });

        Collection<XdStorageObjectChange> changes = resourceChanges.getChangesObjects();
        assertEquals(1, changes.size());

        XdStorageObjectChange change = changes.iterator().next();
        assertNull(change.id); //
        assertNull(change.oldObject); //
        assertNull(change.newObject); //
    }

    @Test
    public void testInitialization_WithNullResource_ShouldAllowCreation() {
        XdStorageTransactionResourceChanges nullResChanges = new XdStorageTransactionResourceChanges(null);
        assertNull(nullResChanges.getResource());
        assertNotNull(nullResChanges.getChangesObjects());
    }
}