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
 * FK: Delete-then-insert on same pair -> later-wins semantics.
 * Corresponds to FK_TEST_INSTRUCTIONS.md §4 P1 tests #7 and #8.
 */
@DisplayName("FK: Delete-then-insert later-wins")
public class ForeignKeyLaterWinsTest {

    private IXdFileStorage storage;
    private String storagePath;
    private String storageName;

    @BeforeEach
    public void setUp() {
        storagePath = "./target/test_fk_later_wins_" + System.nanoTime();
        storageName = "fk_later_wins_" + System.nanoTime();
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

    /** #7: Save parent+child, delete child (remove from collection first), save parent again -> child restored (later-wins). */
    @Test
    @DisplayName("testDeleteChildReinsertSameParent_ChildRestored: delete child, re-save parent -> child back")
    public void testDeleteChildReinsertSameParent_ChildRestored() throws Exception {
        // TX0: save parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("parent-lw-1");
        Child child = new Child();
        child.setId("child-lw-1");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: delete child — сначала удалить child из parent.children (FK-5)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parentLoaded = storage.load(Parent.class, "parent-lw-1", tx1);
        Child childDel = storage.load(Child.class, "child-lw-1", tx1);
        // FK-5: удаление child требует удаления из parent.children
        parentLoaded.getChildren().remove(childDel);
        // MASSFILL: повторная запись существующего родителя — UPDATE (save = insert-only,
        // повторный save дал бы "is exists" по id-индексу).
        storage.update(parentLoaded, tx1);
        storage.delete(childDel, tx1);
        tx1.commit();

        // TX2: save parent again (should re-establish FK pair). parentAgain — НОВЫЙ POJO
        // с существующим id: это тоже UPDATE (id-index знает id).
        IXdStorageTransaction tx2 = storage.beginTransaction();
        Parent parentAgain = new Parent();
        parentAgain.setId("parent-lw-1");
        Child childAgain = new Child();
        childAgain.setId("child-lw-1");
        parentAgain.addChild(childAgain);
        childAgain.setParent(parentAgain);
        // MASSFILL: parentAgain — НОВЫЙ POJO с СУЩЕСТВУЮЩИМ id ("parent-lw-1"): это
        // UPDATE (повторный save дал бы "is exists" по id-индексу родителя).
        storage.update(parentAgain, tx2);
        tx2.commit();

        // Verify: pair exists again
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-lw-1", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Child should be restored via parent re-save");
        assertTrue(children.stream().anyMatch(c -> "child-lw-1".equals(String.valueOf(((Child) c).getId()))));
        verifyTx.commit();
    }

    /** #8: Delete child THEN parent in one TX -> success, no FK pairs remain. */
    @Test
    @DisplayName("testDeleteChildThenParentSameTx_Success: child then parent in same TX -> 0 pairs")
    public void testDeleteChildThenParentSameTx_Success() throws Exception {
        // TX0: save parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("parent-lw-2");
        Child child = new Child();
        child.setId("child-lw-2");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: delete child THEN parent in same TX (canonical order)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Child childDel = storage.load(Child.class, "child-lw-2", tx1);
        storage.delete(childDel, tx1);
        Parent parentDel = storage.load(Parent.class, "parent-lw-2", tx1);
        storage.delete(parentDel, tx1);
        tx1.commit();

        // Verify: no pairs remain
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-lw-2", verifyTx);
        assertNull(loaded, "Parent should be deleted");
        Child loadedChild = storage.load(Child.class, "child-lw-2", verifyTx);
        assertNull(loadedChild, "Child should be deleted");
        verifyTx.commit();
    }
}
