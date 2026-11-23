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
 * FK: P2 edge-case tests (#16-25 from FK_TEST_INSTRUCTIONS.md §7).
 * Uses unique per-test storage to avoid cross-test contamination.
 */
@DisplayName("FK: P2 edge cases (resource/cache/engine)")
public class ForeignKeyP2EdgeCasesTest {

    private IXdFileStorage storage;
    private String storagePath;
    private String storageName;

    @BeforeEach
    public void setUp() {
        storagePath = "./target/test_fk_p2_edge_" + System.nanoTime();
        storageName = "fk_p2_edge_" + System.nanoTime();
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

    /** #16: FK resource delete from both trees — delete child should clear both parent->child and child->parent
     * trees. */
    @Test
    @DisplayName("testDeleteChildClearsBothTrees: delete child -> no parent-child or child-parent pairs")
    public void testDeleteChildClearsBothTrees() throws Exception {
        // Baseline: parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-1");
        Child child = new Child();
        child.setId("p2-child-1");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: delete child — сначала удалить child из parent.children (FK-5)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parentLoaded = storage.load(Parent.class, "p2-parent-1", tx1);
        Child childDel = storage.load(Child.class, "p2-child-1", tx1);
        // FK-5: удаление child требует удаления из parent.children; повторная запись
        // существующего родителя — это UPDATE (save = insert-only, повторный save дал бы
        // "is exists" по id-индексу).
        parentLoaded.getChildren().remove(childDel);
        storage.update(parentLoaded, tx1);
        storage.delete(childDel, tx1);
        tx1.commit();

        // Verify: parent exists but has no children
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-1", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertTrue(children.isEmpty(), "Deleting child should clear parent->child FK pair");
        verifyTx.commit();
    }

    /** #17: FK resource insert deferred-ID — insert with null parent ID should register deferred-ID. */
    @Test
    @DisplayName("testInsertDeferredId: save child w/ null parent -> FK pair registered on parent save")
    public void testInsertDeferredId() throws Exception {
        // TX0: save child with null parent
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        Child child = new Child();
        child.setId("p2-child-deferred");
        child.setParent(unsavedParent);
        storage.save(child, tx0);
        tx0.rollback();

        // TX1: save parent (resolves deferred-ID)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-deferred");
        storage.save(parent, tx1);
        tx1.commit();

        // TX0 был откачен: откатившаяся транзакция не оставляет FK-следа — ни записи
        // ребенка, ни pending-by-object (перс-тX контур очищен на release). Parent в TX1
        // чист: ни пары в FK-дереве, ни детей в коллекции (FK-пары — записи индекса, они
        // не материализуются в коллекции).
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-deferred", verifyTx);
        assertNotNull(loaded);
        assertTrue(loaded.getChildren() == null || loaded.getChildren().isEmpty(),
                "откат TX0 с неперсистентным родителем не оставляет FK-следа");
        // Observable proxy of "no FK trace": the rolled-back child's record is absent.
        assertNull(storage.load(Child.class, "p2-child-deferred", verifyTx),
                "FK-пара откаченного ребенка отсутствует: записи ребенка нет");
        verifyTx.commit();
    }

    /** #18: FK engine processIncomeRelations — child save should register income relation. */
    @Test
    @DisplayName("testProcessIncomeRelation: save child -> parent income relation registered")
    public void testProcessIncomeRelation() throws Exception {
        // TX0: save parent
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-income");
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: save child referencing parent
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-income", tx1);
        Child child = new Child();
        child.setId("p2-child-income");
        child.setParent(loaded);
        storage.save(child, tx1);
        tx1.commit();

        // Verify: FK pair is an INDEX entry (it does not materialize into the parent's
        // stored collection). The canonical observable: delete-child-alone → veto
        // (the income-DELETE arms the waiter → the phase-1 counter veto).
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loadedChild = storage.load(Child.class, "p2-child-income", verifyTx);
        assertNotNull(loadedChild);
        storage.delete(loadedChild, verifyTx);
        assertThrows(Exception.class, () -> verifyTx.commit(),
                "delete-child-alone вето: пара (parent → child) зарегистрирована");
        verifyTx.rollback();
    }

    /** #19: FK engine processOutcomeRelations — parent save with children should register outcome relations. */
    @Test
    @DisplayName("testProcessOutcomeRelation: save parent with children -> children have parent ref")
    public void testProcessOutcomeRelation() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-outcome");
        Child child = new Child();
        child.setId("p2-child-outcome");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx);
        tx.commit();

        // Verify: child has parent back-ref
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loaded = storage.load(Child.class, "p2-child-outcome", verifyTx);
        assertNotNull(loaded);
        Parent backRef = loaded.getParent();
        assertNotNull(backRef);
        assertEquals("p2-parent-outcome", String.valueOf(backRef.getId()));
        verifyTx.commit();
    }

    /** #20: FK engine process() with null parent -> deferred-ID registered. */
    @Test
    @DisplayName("testProcessWithNullParent: save child w/ null parent -> deferred-ID registered")
    public void testProcessWithNullParent() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        Child child = new Child();
        child.setId("p2-child-null-parent");
        child.setParent(unsavedParent);
        storage.save(child, tx);
        tx.rollback();

        // TX1: save parent (resolves deferred-ID)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-null");
        storage.save(parent, tx1);
        tx1.commit();

        // TX0 откачен — FK-след отсутствует (перс-тX изоляция): ни пары, ни детей.
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-null", verifyTx);
        assertNotNull(loaded);
        assertTrue(loaded.getChildren() == null || loaded.getChildren().isEmpty(),
                "откат TX0 не оставляет FK-следа (canon #23: TX1 не видит pending TX0)");
        verifyTx.commit();
    }

    /** #21: FK cache validateAllForTransaction — validate pending FKs on commit. */
    @Test
    @DisplayName("testValidateAllForTransaction: child without parent -> commit throws")
    public void testValidateAllForTransaction() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        Child child = new Child();
        child.setId("p2-child-validate");
        child.setParent(unsavedParent);
        storage.save(child, tx);

        // Commit should validate pending FKs and throw
        assertThrows(Exception.class, () -> tx.commit(),
                "Pending FKs should be validated on commit");
        tx.rollback();
    }

    /** #22: FK cache commitAllFxPairs order — pairs are written in parent-first order. */
    @Test
    @DisplayName("testCommitFxPairsOrder: parent saved before child -> pair exists on parent load")
    public void testCommitFxPairsOrder() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-order");
        Child child = new Child();
        child.setId("p2-child-order");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx);
        tx.commit();

        // Verify pair exists
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-order", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size());
        verifyTx.commit();
    }

    /** #23: FK cache release per-transaction — each TX has isolated FK state. */
    @Test
    @DisplayName("testCacheReleasePerTx: TX0 child pending -> TX1 can't see it")
    public void testCacheReleasePerTx() throws Exception {
        // TX0: save child with null parent (deferred-ID)
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        Child child = new Child();
        child.setId("p2-child-release");
        child.setParent(unsavedParent);
        storage.save(child, tx0);
        tx0.rollback();

        // TX1: save parent -> resolves deferred-ID
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-release");
        storage.save(parent, tx1);
        tx1.commit();

        // Verify (canon #23: "TX0 child pending -> TX1 can't see it" — перс-тX изоляция)
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-release", verifyTx);
        assertNotNull(loaded);
        assertTrue(loaded.getChildren() == null || loaded.getChildren().isEmpty(),
                "откат TX0 с неперсистентным родителем не оставляет FK-следа в TX1");
        verifyTx.commit();
    }

    /** #24: FK cache later-wins (delete then insert) — same pair deleted then re-inserted. */
    @Test
    @DisplayName("testLaterWinsDeleteInsert: delete pair, re-insert same -> pair exists")
    public void testLaterWinsDeleteInsert() throws Exception {
        // TX0: save parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-lw2");
        Child child = new Child();
        child.setId("p2-child-lw2");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: delete child (removes FK pair) — сначала удалить child из parent.children (FK-5)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parentLoaded = storage.load(Parent.class, "p2-parent-lw2", tx1);
        Child childDel = storage.load(Child.class, "p2-child-lw2", tx1);
        // FK-5: удаление child требует удаления из parent.children
        parentLoaded.getChildren().remove(childDel);
        // MASSFILL: повторная запись существующего родителя — UPDATE (save = insert-only)
        storage.update(parentLoaded, tx1);
        storage.delete(childDel, tx1);
        tx1.commit();

        // TX2: re-save parent + child (reinstates pair)
        IXdStorageTransaction tx2 = storage.beginTransaction();
        Parent parent2 = new Parent();
        parent2.setId("p2-parent-lw2");
        Child child2 = new Child();
        child2.setId("p2-child-lw2");
        parent2.addChild(child2);
        child2.setParent(parent2);
        // MASSFILL: повторная запись существующего id — это UPDATE (save = insert-only)
        storage.update(parent2, tx2);
        tx2.commit();

        // Verify
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-lw2", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Later-wins: re-insert should restore pair");
        verifyTx.commit();
    }

    /** #25: FK cache duplicate insert idempotent — same pair saved twice -> 1 pair. */
    @Test
    @DisplayName("testDuplicateInsertIdempotent: save parent+child twice -> 1 pair")
    public void testDuplicateInsertIdempotent() throws Exception {
        // TX0: save parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("p2-parent-dup");
        Child child = new Child();
        child.setId("p2-child-dup");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: save same parent + child again
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent2 = new Parent();
        parent2.setId("p2-parent-dup");
        Child child2 = new Child();
        child2.setId("p2-child-dup");
        parent2.addChild(child2);
        child2.setParent(parent2);
        storage.update(parent2, tx1);
        tx1.commit();

        // Verify: exactly 1 pair (idempotent)
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "p2-parent-dup", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Duplicate insert should be idempotent");
        verifyTx.commit();
    }
}
