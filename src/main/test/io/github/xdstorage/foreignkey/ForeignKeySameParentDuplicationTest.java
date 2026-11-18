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
 * FK: Save same parent twice in different TXs -> idempotent / no duplicate.
 * Corresponds to FK_TEST_INSTRUCTIONS.md §4 P1 tests #5 and #6.
 */
@DisplayName("FK: Same-parent-saved-twice idempotence")
public class ForeignKeySameParentDuplicationTest {

    private IXdFileStorage storage;
    private String storagePath;
    private String storageName;

    @BeforeEach
    public void setUp() {
        storagePath = "./target/test_fk_same_parent_" + System.nanoTime();
        storageName = "fk_same_parent_" + System.nanoTime();
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

    /** #5: Save parent with child A, then save same parent with child B -> exactly 1 pair (child B). */
    @Test
    @DisplayName("testSaveSameParentTwiceDiffChild_NoDuplicate: parent+childA then parent+childB -> 1 pair (childB)")
    public void testSaveSameParentTwiceDiffChild_NoDuplicate() throws Exception {
        // TX0: save parent with child A
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parentA = new Parent();
        parentA.setId("parent-same-1");
        Child childA = new Child();
        childA.setId("child-same-A");
        parentA.addChild(childA);
        childA.setParent(parentA);
        storage.save(parentA, tx0);
        tx0.commit();

        // TX1: save same parent with child B
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parentB = new Parent();
        parentB.setId("parent-same-1");
        Child childB = new Child();
        childB.setId("child-same-B");
        parentB.addChild(childB);
        childB.setParent(parentB);
        storage.update(parentB, tx1);
        tx1.commit();

        // Verify: exactly 1 pair (child B), no child A
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-same-1", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Expected exactly 1 pair (idempotent), got " + children.size());
        assertTrue(children.stream().anyMatch(c -> "child-same-B".equals(String.valueOf(((Child) c).getId()))),
                "Child B should be the surviving pair");
        assertFalse(children.stream().anyMatch(c -> "child-same-A".equals(String.valueOf(((Child) c).getId()))),
                "Child A should NOT be present (later-wins)");
        verifyTx.commit();
    }

    /** #6: Save parent with same child twice -> exactly 1 pair (idempotent). */
    @Test
    @DisplayName("testSaveSameParentSameChild_Idempotent: parent+child twice -> 1 pair")
    public void testSaveSameParentSameChild_Idempotent() throws Exception {
        // TX0: save parent with child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parentA = new Parent();
        parentA.setId("parent-same-2");
        Child child = new Child();
        child.setId("child-same-2");
        parentA.addChild(child);
        child.setParent(parentA);
        storage.save(parentA, tx0);
        tx0.commit();

        // TX1: save same parent + same child again
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parentB = new Parent();
        parentB.setId("parent-same-2");
        Child child2 = new Child();
        child2.setId("child-same-2");
        parentB.addChild(child2);
        child2.setParent(parentB);
        storage.update(parentB, tx1);
        tx1.commit();

        // Verify: exactly 1 pair
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-same-2", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Expected exactly 1 pair (idempotent), got " + children.size());
        assertTrue(children.stream().anyMatch(c -> "child-same-2".equals(String.valueOf(((Child) c).getId()))));
        verifyTx.commit();
    }
}
