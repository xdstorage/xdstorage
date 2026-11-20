package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FK insertion veto tests:
 *   - Save child alone (parent not saved) → veto at commit
 *   - Delete parent with live children → veto at commit
 *   - Delete child with parent reference → veto at commit
 */
@DisplayName("FK: Insertion/deletion vetoes")
public class XdStorageForeignKeyInsertionVetoTest {

    private static final String STORAGE_PATH = "./target/test_fk_veto_db_" + System.nanoTime();
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        if (storage != null) storage.shutdown();
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_veto_test_1", STORAGE_PATH, 128, FilesFormat.YAML);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_PATH));
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }

    /** Save child alone (parent not saved in this TX) → FK veto at commit. */
    @Test
    @DisplayName("testSaveChildAlone_VetoOnCommit: save child w/ unsaved parent → veto")
    public void testSaveChildAlone_VetoOnCommit() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);

        Child child = new Child();
        child.setId("child-alone-1");
        child.setParent(unsavedParent);

        storage.save(child, tx);

        // Commit should veto because parent was never persisted
        assertThrows(XdStorageRuntimeException.class, () -> tx.commit(),
                "FK-veto failed: save child with unsaved parent should be rejected at commit!");

        tx.rollback();
    }

    /** Save parent + child in same TX → no veto. */
    @Test
    @DisplayName("testSaveParentAndChild_VetoOnCommit: parent+child same TX → no veto")
    public void testSaveParentAndChild_VetoOnCommit() throws Exception {
        // Clean DB first
        storage.shutdown();
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_veto_test_1", STORAGE_PATH, 128, FilesFormat.YAML);

        IXdStorageTransaction tx = storage.beginTransaction();

        Parent parent = new Parent();
        parent.setId("parent-1");
        Child child = new Child();
        child.setId("child-1");
        parent.addChild(child);
        child.setParent(parent);

        // NOTE: storage.save(parent) triggers cascade which saves the Child
        // (because parent.children contains Child). We do NOT save the Child
        // explicitly to avoid a hash-index duplicate-save conflict. The cascade
        // handles the Child's persistence; the FK engine validates Parent's
        // existence during cascade-save of Child.
        storage.save(parent, tx);

        assertDoesNotThrow(() -> tx.commit(),
                "FK should resolve when parent saved in same TX!");

        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-1", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertTrue(children.size() >= 1);
        verifyTx.commit();
    }

    /** Delete parent with live children → FK veto at commit. */
    @Test
    @DisplayName("testDeleteParentWithLiveChild_VetoOnCommit: delete parent w/ live children → veto")
    public void testDeleteParentWithLiveChild_VetoOnCommit() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        // First commit parent+child
        Parent parent = new Parent();
        parent.setId("parent-live-1");
        Child child = new Child();
        child.setId("child-live-1");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx);
        tx.commit();

        // Now delete parent (child still references it)
        IXdStorageTransaction tx2 = storage.beginTransaction();
        Parent loadedParent = storage.load(Parent.class, "parent-live-1", tx2);

        // NOTE: DO NOT clear child's parent reference! The Child still references
        // the Parent (income link), and the FK tree still has Parent→Child.
        // Deleting the Parent with live children must be vetoed.
        // (Original test cleared references — that defeated the FK veto.)

        storage.delete(loadedParent, tx2);

        assertThrows(XdStorageRuntimeException.class, () -> tx2.commit(),
                "FK-veto failed: delete parent w/ live children should be rejected!");

        tx2.rollback();
    }

    /** Delete child with parent reference → VETO (the parent's persisted collection still references the child). */
    @Test
    @DisplayName("testDeleteChildWithLiveParentReference_VetoOnCommit: delete child w/ parent ref → VETO (dangling reference)")
    public void testDeleteChildWithLiveParentReference_VetoOnCommit() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        // First commit parent+child
        Parent parent = new Parent();
        parent.setId("parent-del-1");
        Child child = new Child();
        child.setId("child-del-1");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx);
        tx.commit();

        // Now delete child
        IXdStorageTransaction tx2 = storage.beginTransaction();
        Child childDel = storage.load(Child.class, "child-del-1", tx2);
        storage.delete(childDel, tx2);

        // CANON (2026-11-20, DeleteScenarios.testDeleteOnlyOneChildThrows): delete-child-alone
        // while the parent's persisted collection still references the child → VETO at commit
        // (the same dangling-reference end-state → the same verdict).
        assertThrows(XdStorageRuntimeException.class, () -> tx2.commit(),
                "FK-veto failed: delete child w/ parent ref should be rejected!");
        tx2.rollback();

        // Parent should still exist
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-del-1", verifyTx);
        assertNotNull(loaded);
        verifyTx.commit();
    }
}
