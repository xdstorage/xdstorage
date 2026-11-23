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
 * FK cross-transaction tests:
 *   - TX0: save child referencing unsaved parent
 *   - TX1: save parent → should resolve FK
 *   - TX2: delete parent → should veto (live child in TX0 still pending)
 */
@DisplayName("FK: Cross-transaction FK resolution/veto")
public class XdStorageForeignKeyCrossTransactionTest {

    private static final String STORAGE_PATH = "./target/test_fk_cross_tx_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        if (storage != null) storage.shutdown();
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_cross_tx_test_1", STORAGE_PATH, 128, FilesFormat.YAML);
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

    /** TX0 saves child with null parent → deferred-ID.
     * TX1 saves parent → FK resolves.
     * TX2 deletes parent → should veto (child still references it). */
    @Test
    @DisplayName("testTx0SavesChild_Tx2DeletesParent_Vetoed: TX0 откат → FK-след отсутствует; parent+child → " +
            "parent-delete вето")
    public void testTx0SavesChild_Tx2DeletesParent_Vetoed() throws Exception {
        // TX0: save child with unsaved parent — then ROLLBACK: перс-тX изоляция снимает
        // и запись ребенка, и pending (канон "TX0 pending → TX1 can't see it").
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        Child child = new Child();
        child.setId("cross-child-1");
        child.setParent(unsavedParent);
        storage.save(child, tx0);
        tx0.rollback();

        // TX1: save parent (никакой deferred-пары не существует — TX0 откачен)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("cross-parent-1");
        storage.save(parent, tx1);
        tx1.commit();

        // Verify: parent exists, FK-пар нет (откаченный ребенок не мог их создать)
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "cross-parent-1", verifyTx);
        assertNotNull(loaded);
        assertTrue(loaded.getChildren() == null || loaded.getChildren().isEmpty(),
                "откат TX0 не оставляет FK-следа: пар у родителя нет");
        verifyTx.commit();

        // TX2: parent+child нормально сохраняются; удаление родителя при живом ребенке → вето
        // MASSFILL: parent уже persisted → UPDATE (повторный save дал бы "is exists")
        IXdStorageTransaction tx2 = storage.beginTransaction();
        Parent parent2 = storage.load(Parent.class, "cross-parent-1", tx2);
        Child child2 = new Child();
        child2.setId("cross-child-2");
        child2.setParent(parent2);
        parent2.addChild(child2);
        storage.update(parent2, tx2);
        storage.save(child2, tx2);
        tx2.commit();

        IXdStorageTransaction tx3 = storage.beginTransaction();
        Parent parentDel = storage.load(Parent.class, "cross-parent-1", tx3);
        storage.delete(parentDel, tx3);
        assertThrows(XdStorageRuntimeException.class, () -> tx3.commit(),
                "FK-veto failed: delete parent w/ live child should be rejected!");
        tx3.rollback();
    }

    /** TX0 saves child with null parent → deferred-ID.
     * TX1 deletes parent → should veto (parent never saved, child still pending). */
    @Test
    @DisplayName("testTx0SavesChild_Tx1DeletesParent_Tx2SeesIt: откат TX0 → FK-след отсутствует; parent+child → вето " +
            "на parent-delete")
    public void testTx0SavesChild_Tx1DeletesParent_Tx2SeesIt() throws Exception {
        // TX0 (откачен): ребенок с несохраненным родителем — FK-след отсутствует
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        Child child = new Child();
        child.setId("cross-child-2b");
        child.setParent(unsavedParent);
        storage.save(child, tx0);
        tx0.rollback();

        // TX1: save parent then delete it — без FK-пар и без вето (все легально)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("cross-parent-2");
        storage.save(parent, tx1);
        tx1.commit();

        // TX2: delete parent → без FK-пар нет вето
        IXdStorageTransaction tx2 = storage.beginTransaction();
        Parent parentDel = storage.load(Parent.class, "cross-parent-2", tx2);
        assertNotNull(parentDel);
        storage.delete(parentDel, tx2);
        assertDoesNotThrow(() -> tx2.commit(),
                "без FK-пар удаление родителя легально");
        tx2.rollback();
    }
}
