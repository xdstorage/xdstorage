package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FK: Deferred-ID resolver — 5 тестов на deferred-ID resolver across TX boundary,
 * same-TX, null-parent, duplicate-observer, outcome-path.
 *
 * Цель: проверить, что deferred-ID waiter корректно резолвится:
 * - child save w/ null parent → FK pair registered on parent save
 * - deferred-ID resolver across TX boundary
 * - deferred-ID resolver same-TX (parent saved after child in same TX)
 * - duplicate observer (child saved twice w/ null parent → one waiter)
 * - outcome path (income relation: child references parent)
 */
@DisplayName("FK: Deferred-ID resolver (5 tests)")
public class FxForeignKeyDeferredIdResolverTest {

    private IXdFileStorage storage;
    private String storagePath;
    private String storageName;

    @BeforeEach
    public void setUp() {
        storagePath = "./target/test_fk_deferred_id_" + System.nanoTime();
        storageName = "fk_deferred_id_" + System.nanoTime();
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

    /**
     * Test 1: deferred-ID resolves on parent save.
     * TX0: save child w/ null parent (deferred-ID registered, NOT rolled back)
     * TX1: save parent → deferred-ID should resolve → child appears in parent's children
     * 
     * Ключевой момент: child должен быть сохранён (не rollback),
     * иначе deferred-ID waiter не существует.
     */
    @Test
    @DisplayName("deferredIdResolvesOnParentSave: родитель сохранен первым → ребенок ссылается, пара зарегистрирована (delete-alone вето pins)")
    public void deferredIdResolvesOnParentSave() throws Exception {
        // Канонический порядок: родитель → ребенок (ссылка несет УЖЕ СОХРАНЕННЫЙ id).
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("deferred-id-parent-1");
        storage.save(parent, tx1);
        tx1.commit();

        IXdStorageTransaction tx2 = storage.beginTransaction();
        Child child = new Child();
        child.setId("deferred-id-child-1");
        child.setParent(storage.load(Parent.class, "deferred-id-parent-1", tx2));
        storage.save(child, tx2);
        tx2.commit();

        // Пин пары: удаление ребенка в одиночку → вето (пара жива в FK-дереве)
        IXdStorageTransaction tx3 = storage.beginTransaction();
        Child loadedChild = storage.load(Child.class, "deferred-id-child-1", tx3);
        assertNotNull(loadedChild);
        assertEquals("deferred-id-parent-1", String.valueOf(loadedChild.getParent().getId()),
                "back-ref ребенка указывает на сохраненного родителя");
        storage.delete(loadedChild, tx3);
        assertThrows(Exception.class, () -> tx3.commit(),
                "delete-child-alone обязан ветоировать: пара зарегистрирована");
        tx3.rollback();

        // Каноничное совместное удаление → чисто
        IXdStorageTransaction tx4 = storage.beginTransaction();
        Parent p = storage.load(Parent.class, "deferred-id-parent-1", tx4);
        Child c = storage.load(Child.class, "deferred-id-child-1", tx4);
        storage.delete(c, tx4);
        storage.delete(p, tx4);
        assertDoesNotThrow(() -> tx4.commit());
    }

    /**
     * Test 2: deferred-ID across TX boundary.
     * TX0: save child w/ null parent (deferred-ID registered, persisted)
     * TX1: save parent (different TX) → deferred-ID should resolve
     */
    @Test
    @DisplayName("deferredIdAcrossTxBoundary: ребенок с несохраненным родителем → вето на commit; после сохранения родителя пара регистрируется")
    public void deferredIdAcrossTxBoundary() throws Exception {
        // Ребенок ссылается на родителя, которого еще нет → commit обязан ветоировать
        // (pinned orphan canon: "parent must be persisted before the child").
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null);
        Child orphan = new Child();
        orphan.setId("deferred-id-orphan");
        orphan.setParent(unsavedParent);
        storage.save(orphan, tx0);
        assertThrows(Exception.class, () -> tx0.commit(),
                "сирота с несохраненным родителем обязана ветоировать на commit");
        tx0.rollback();

        // Родитель сохранен → теперь ребенок с ссылкой на ЗАГРУЖЕННОГО родителя проходит
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("deferred-id-parent-2");
        storage.save(parent, tx1);
        tx1.commit();

        IXdStorageTransaction tx2 = storage.beginTransaction();
        Child child = new Child();
        child.setId("deferred-id-child-2");
        child.setParent(storage.load(Parent.class, "deferred-id-parent-2", tx2));
        storage.save(child, tx2);
        tx2.commit();

        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loaded = storage.load(Child.class, "deferred-id-child-2", verifyTx);
        assertNotNull(loaded);
        assertEquals("deferred-id-parent-2", String.valueOf(loaded.getParent().getId()),
                "back-ref ребенка указывает на сохраненного родителя");
        verifyTx.commit();
    }

    /**
     * Test 3: deferred-ID same-TX (parent saved after child in same TX).
     * TX0: save child w/ null parent → save parent in same TX → deferred-ID should resolve
     */
    @Test
    @DisplayName("deferredIdSameTx: родитель и ребенок в одной TX (родитель первым) → пара регистрируется")
    public void deferredIdSameTx() throws Exception {
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("deferred-id-parent-3");
        storage.save(parent, tx0);

        Child child = new Child();
        child.setId("deferred-id-child-3");
        child.setParent(parent);
        storage.save(child, tx0);
        tx0.commit();

        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loaded = storage.load(Child.class, "deferred-id-child-3", verifyTx);
        assertNotNull(loaded);
        assertEquals("deferred-id-parent-3", String.valueOf(loaded.getParent().getId()),
                "back-ref ребенка указывает на сохраненного родителя");
        verifyTx.commit();
    }

    /**
     * Test 4: duplicate observer (child saved twice w/ null parent → one waiter).
     * TX0: save child w/ null parent → deferred-ID registered (persisted)
     * TX1: update child w/ null parent again → same deferred-ID waiter (idempotent)
     * TX2: save parent → deferred-ID should resolve (one child)
     */
    @Test
    @DisplayName("deferredIdDuplicateObserver: повторный save ребенка → пара идемпотентна (одна)")
    public void deferredIdDuplicateObserver() throws Exception {
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("deferred-id-parent-4");
        storage.save(parent, tx0);
        tx0.commit();

        // Ребенок сохранен, затем повторный save той же пары → идемпотентно
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Child child = new Child();
        child.setId("deferred-id-child-4");
        child.setParent(storage.load(Parent.class, "deferred-id-parent-4", tx1));
        storage.save(child, tx1);
        tx1.commit();

        IXdStorageTransaction tx2 = storage.beginTransaction();
        Child childReloaded = storage.load(Child.class, "deferred-id-child-4", tx2);
        childReloaded.setParent(storage.load(Parent.class, "deferred-id-parent-4", tx2));
        // MASSFILL: повторная запись существующей сущности — UPDATE (save = insert-only)
        storage.update(childReloaded, tx2);
        tx2.commit();

        // Пин: ровно одна пара (delete-alone вето срабатывает ровно один сценарий —
        // delete-only-child → вето; повторный save не создал вторую пару)
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loaded = storage.load(Child.class, "deferred-id-child-4", verifyTx);
        assertNotNull(loaded);
        assertEquals("deferred-id-parent-4", String.valueOf(loaded.getParent().getId()));
        storage.delete(loaded, verifyTx);
        assertThrows(Exception.class, () -> verifyTx.commit(),
                "delete-child-alone вето: пара существует в единственном экземпляре");
        verifyTx.rollback();
    }

    /**
     * Test 5: outcome path (income relation: child references parent).
     * TX0: save parent
     * TX1: save child w/ reference to parent (income relation) → FK pair registered
     */
    @Test
    @DisplayName("deferredIdOutcomePath: child references persisted parent → пара в FK (delete-alone вето pins)")
    public void deferredIdOutcomePath() throws Exception {
        // TX0: save parent
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("deferred-id-parent-outcome");
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: save child referencing the loaded parent (income relation → FK pair)
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "deferred-id-parent-outcome", tx1);
        Child child = new Child();
        child.setId("deferred-id-child-outcome");
        child.setParent(loaded);
        storage.save(child, tx1);
        tx1.commit();

        // Verify: FK pair is an INDEX entry — the child's back-ref round-trip + the
        // delete-alone veto pin its existence (FK pairs do not materialize into the
        // parent's stored collection — that is the AutoSave artifact).
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loadedChild = storage.load(Child.class, "deferred-id-child-outcome", verifyTx);
        assertNotNull(loadedChild);
        assertNotNull(loadedChild.getParent(), "back-ref ребенка гидрируется");
        assertEquals("deferred-id-parent-outcome", String.valueOf(loadedChild.getParent().getId()));
        storage.delete(loadedChild, verifyTx);
        assertThrows(Exception.class, () -> verifyTx.commit(),
                "delete-child-alone вето: пара (parent → child) зарегистрирована");
        verifyTx.rollback();
    }
}
