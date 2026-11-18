package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FK: Update parent's children collection (P1 tests #14, #15).
 */
@DisplayName("FK: Update parent's children collection")
public class ForeignKeyUpdateTest {

    private IXdFileStorage storage;
    private String storagePath;
    private String storageName;

    @BeforeEach
    public void setUp() {
        storagePath = "./target/test_fk_update_" + System.nanoTime();
        storageName = "fk_update_" + System.nanoTime();
        deleteDir(new File(storagePath));
        storage = XdStorageProvider.newOrGetFileStorage(storageName, storagePath, 128, FilesFormat.YAML);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) { storage.shutdown(); }
        deleteDir(new File(storagePath));
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }

    /** #14: Update parent: remove child A, add child B -> success (child A still exists independently). */
    @Test
    @DisplayName("testUpdateRemoveAAddB: remove A, add B -> success, pair is B")
    public void testUpdateRemoveAAddB() throws Exception {
        // Baseline: parent + child A
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("parent-update-1");
        Child childA = new Child();
        childA.setId("child-update-A");
        parent.addChild(childA);
        childA.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: update parent — remove A from collection, add B
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parentUpdate = storage.load(Parent.class, "parent-update-1", tx1);
        Collection<?> children = parentUpdate.getChildren();
        if (children instanceof java.util.ArrayList) {
            ((java.util.ArrayList<?>) children).clear();
        } else if (children instanceof java.util.HashSet) {
            ((java.util.HashSet<?>) children).clear();
        }
        Child childB = new Child();
        childB.setId("child-update-B");
        parentUpdate.addChild(childB);
        childB.setParent(parentUpdate);
        storage.update(parentUpdate, tx1);
        tx1.commit();

        // Verify: exactly 1 pair (child B)
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-update-1", verifyTx);
        assertNotNull(loaded);
        Collection<?> children2 = loaded.getChildren();
        assertEquals(1, children2.size(), "Should have exactly 1 pair (child B)");
        assertTrue(children2.stream().anyMatch(c -> "child-update-B".equals(String.valueOf(((Child) c).getId()))));
        assertFalse(children2.stream().anyMatch(c -> "child-update-A".equals(String.valueOf(((Child) c).getId()))));
        verifyTx.commit();
    }

    /** #15: Update parent: remove child A, don't delete -> child A still exists independently. */
    @Test
    @DisplayName("testUpdateRemoveAKeepA: remove A from collection -> A survives as independent entity")
    public void testUpdateRemoveAKeepA() throws Exception {
        // Baseline: parent + child A
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("parent-update-2");
        Child childA = new Child();
        childA.setId("child-update-A2");
        parent.addChild(childA);
        childA.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: remove child A from parent's collection (but don't delete A)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parentUpdate = storage.load(Parent.class, "parent-update-2", tx1);
        Collection<?> children = parentUpdate.getChildren();
        if (children instanceof java.util.ArrayList) {
            ((java.util.ArrayList<?>) children).clear();
        } else if (children instanceof java.util.HashSet) {
            ((java.util.HashSet<?>) children).clear();
        }
        childA.setParent(null);
        storage.update(parentUpdate, tx1);
        tx1.commit();

        // Verify: child A still exists but no FK pair
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loadedChild = storage.load(Child.class, "child-update-A2", verifyTx);
        assertNotNull(loadedChild, "Child A should still exist as independent entity");
        Parent loadedParent = storage.load(Parent.class, "parent-update-2", verifyTx);
        assertNotNull(loadedParent);
        Collection<?> children2 = loadedParent.getChildren();
        assertTrue(children2.isEmpty(), "No FK pairs should remain (A removed from parent)");
        verifyTx.commit();
    }
}
