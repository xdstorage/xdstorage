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
 * P3 Deferred-ID waiter tests.
 *
 * Deferred-ID waiter should:
 *   1. Register when a child is saved with null parent
 *   2. Fire when the parent's ID is generated (storage.save assigns ID)
 *   3. Resolve the FK pair at commit time
 */
@DisplayName("P3: Deferred-ID waiter")
public class XdStorageForeignKeyDeferredIdTest {

    private static final String STORAGE_PATH = "./target/test_fk_deferred_id_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        if (storage != null) storage.shutdown();
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_deferred_id_test_1", STORAGE_PATH, 128, FilesFormat.YAML);
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

    /** P3-1: Save child with null parent, DON'T save parent → FK veto.
     * This is CORRECT behavior: if parent is never saved, FK vetoes. */
    @Test
    @DisplayName("P3-1: Save child w/ null parent w/o saving parent → FK veto")
    public void testSaveChildWithNullParent_VetoAtCommit() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Child child = new Child();
        child.setId("deferred-child-1");

        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        child.setParent(unsavedParent);

        storage.save(child, tx);

        // FK MUST veto: parent never saved
        assertThrows(XdStorageRuntimeException.class, () -> {
            tx.commit();
        });

        tx.rollback();
    }

    /** P3-2: Save child (deferred) + save parent in same TX → FK resolves.
     * EXPECTED: deferred-ID waiter fires when parent's ID is set,
     * FK pair resolves, commit succeeds. */
    @Test
    @DisplayName("P3-2: Save child (deferred) + save parent same TX → FK resolves")
    public void testSaveChildThenParentInSameTx_Resolves() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        // Step 1: Save child referencing unsaved parent (deferred-ID waiter registers)
        Child child = new Child();
        child.setId("deferred-child-2");
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        child.setParent(unsavedParent);
        storage.save(child, tx);

        // Step 2: Save parent (deferred-ID waiter SHOULD fire here)
        unsavedParent.setId("deferred-parent-2");
        storage.save(unsavedParent, tx);

        // Step 3: FK should resolve → commit succeeds
        assertDoesNotThrow(() -> tx.commit(),
                "FK should resolve: deferred-ID waiter fires when parent saved in same TX!");

        // Verify FK pair persisted
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "deferred-parent-2", verifyTx);
        assertNotNull(loaded, "Parent should be persisted");
        Child loadedChild = storage.load(Child.class, "deferred-child-2", verifyTx);
        assertNotNull(loadedChild, "Child should be persisted");
        assertEquals(loaded, loadedChild.getParent(),
                "Child's parent ref should point to the persisted parent after deferred-ID resolution");
        verifyTx.commit();
    }

    /** P3-3: Save child with null parent, DON'T save parent → FK veto.
     * Same as P3-1, negative case. */
    @Test
    @DisplayName("P3-3: Save child (unsaved parent) w/o saving parent → FK veto")
    public void testSaveChildWithUnsavedParent_NoResolve_Veto() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Child child = new Child();
        child.setId("deferred-child-3");
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        child.setParent(unsavedParent);
        storage.save(child, tx);

        assertThrows(XdStorageRuntimeException.class, () -> {
            tx.commit();
        });

        tx.rollback();
    }

    /** P3-4: Canonical ordering — save parent FIRST, then child → FK succeeds. */
    @Test
    @DisplayName("P3-4: Parent+child in ONE TX → FK succeeds (working canonical ordering)")
    public void testSaveParentAndChildInSameTx_Success() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Parent parent = new Parent();
        parent.setId("deferred-parent-4");
        Child child = new Child();
        child.setId("deferred-child-4");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx);

        assertDoesNotThrow(() -> tx.commit());

        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "deferred-parent-4", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertTrue(children.size() >= 1);
        verifyTx.commit();
    }
}
