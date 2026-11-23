package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.performance.entities.FkBenchChild;
import io.github.xdstorage.performance.entities.FkBenchParent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FKBENCH (task 50) finding diagnostics: the benchmark's childDelete trials failed with
 * "Not all foreign keys are deleted" (the FK change registry's phase-1 check) on the
 * delete + REINSERT cycle. This test isolates the trigger: (a) a plain delete, (b) a
 * delete + the same-id reinsert with a PARENT STUB, (c) the delete + the reinsert with a
 * LOADED parent, (d) the second delete after the reinsert. Whatever fails pins the
 * engine edge the benchmark walked into.
 */
@DisplayName("FKBENCH diag: the delete + reinsert cycle of a FK-bearing child")
public class XdStorageForeignKeyDeleteReinsertDiagTest {

    private static final String STORAGE_PATH = "./target/fk-diag-" + System.nanoTime();
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteRecursively(new File(STORAGE_PATH));
        // (g) the GREEN-open shape: the 3-arg overload, the engine-default config, the
        // fragment 128 — the ONLY difference from the green FK estate
        storage = XdStorageProvider.newOrGetFileStorage(
                "fk-diag-" + System.nanoTime(), STORAGE_PATH, 128, FilesFormat.YAML);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (final Exception ignored) {
            }
        }
        deleteRecursively(new File(STORAGE_PATH));
    }

    private static void deleteRecursively(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteRecursively(f);
            }
        }
        file.delete();
    }

    private void seed() throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction();
        final FkBenchParent parent = new FkBenchParent();
        parent.setId("p0");
        parent.setLabel("parent-0");
        final FkBenchChild child = new FkBenchChild();
        child.setId("p0-c0");
        child.setLabel("child-0");
        child.setParent(parent);
        storage.save(child, tx);
        parent.addChild(child);
        storage.save(parent, tx);
        storage.commitTransaction(tx);
    }

    /**
     * The delete-child-ALONE op — the shape the CANON 2026-11-20 veto refuses when the
     * parent's collection still references the child. The refusal surfaces at the commit
     * (the FK change registry's phase-1 check: "Not all foreign keys are deleted").
     */
    private void deleteChildExpectingVeto(final String childId) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction();
        final FkBenchChild child = storage.load(FkBenchChild.class, childId, tx);
        assertNotNull(child, "the child exists before the delete: " + childId);
        storage.delete(child, tx);
        assertThrows(io.github.xdstorage.exceptions.XdStorageRuntimeException.class,
                () -> storage.commitTransaction(tx),
                "the delete-child-alone while the parent's collection still references the child is a designed veto");
        storage.rollbackTransaction(tx);
    }

    @Test
    @DisplayName("(h) КОНТРАКТНЫЙ цикл: sync коллекции родителя → delete ребёнка → restore")
    public void contractCycleDeleteRestore() throws Exception {
        seed(); // the AutoSave cascade (the parent first)
        // the CONTRACT-correct removal: sync the parent's collection, then delete the child
        final IXdStorageTransaction tx = storage.beginTransaction();
        final FkBenchParent parent = storage.load(FkBenchParent.class, "p0", tx);
        final FkBenchChild child = storage.load(FkBenchChild.class, "p0-c0", tx);
        assertNotNull(child);
        final java.util.Iterator<FkBenchChild> it = parent.getChildren().iterator();
        while (it.hasNext()) {
            if ("p0-c0".equals(it.next().getId())) {
                it.remove();
            }
        }
        storage.update(parent, tx);
        storage.delete(child, tx);
        storage.commitTransaction(tx);

        // the restore cycle: re-add to the collection + update (the AutoSave cascade)
        final IXdStorageTransaction tx2 = storage.beginTransaction();
        final FkBenchParent parent2 = storage.load(FkBenchParent.class, "p0", tx2);
        final FkBenchChild restored = new FkBenchChild();
        restored.setId("p0-c0");
        restored.setLabel("child-restored");
        restored.setParent(parent2);
        parent2.getChildren().add(restored);
        storage.update(parent2, tx2);
        storage.commitTransaction(tx2);

        // the SECOND contract cycle — the benchmark's repeated-op shape
        final IXdStorageTransaction tx3 = storage.beginTransaction();
        final FkBenchParent parent3 = storage.load(FkBenchParent.class, "p0", tx3);
        final FkBenchChild child3 = storage.load(FkBenchChild.class, "p0-c0", tx3);
        assertNotNull(child3, "the restored child loads");
        final java.util.Iterator<FkBenchChild> it3 = parent3.getChildren().iterator();
        while (it3.hasNext()) {
            if ("p0-c0".equals(it3.next().getId())) {
                it3.remove();
            }
        }
        storage.update(parent3, tx3);
        storage.delete(child3, tx3);
        storage.commitTransaction(tx3);
    }

    @Test
    @DisplayName("(f) КОНТРАКТ-ПИН: veto на зелёной паре эстейта (не свойство моих сущностей)")
    public void estateEntityPairDelete() throws Exception {
        final io.github.xdstorage.entities.foreignkeys.Parent parent = new io.github.xdstorage.entities.foreignkeys
                .Parent();
        parent.setId("ep0");
        final io.github.xdstorage.entities.foreignkeys.Child child = new io.github.xdstorage.entities.foreignkeys
                .Child();
        child.setId("ep0-c0");
        child.setParent(parent);
        final IXdStorageTransaction tx = storage.beginTransaction();
        parent.addChild(child);
        storage.save(parent, tx); // the AutoSave cascade persists the child
        storage.commitTransaction(tx);

        final IXdStorageTransaction tx2 = storage.beginTransaction();
        final io.github.xdstorage.entities.foreignkeys.Child loaded =
                storage.load(io.github.xdstorage.entities.foreignkeys.Child.class, "ep0-c0", tx2);
        assertNotNull(loaded, "the estate child loaded");
        storage.delete(loaded, tx2);
        assertThrows(io.github.xdstorage.exceptions.XdStorageRuntimeException.class,
                () -> storage.commitTransaction(tx2),
                "the designed veto holds for the estate entity pair too");
        storage.rollbackTransaction(tx2);
    }

    @Test
    @DisplayName("(e) КОНТРАКТ-ПИН: veto при parent-first seed (порядок сохранения не влияет)")
    public void parentFirstSeedPlainDelete() throws Exception {
        // the seed WITH the parent saved before the child: the FK relation registers on
        // an EXISTING parent — if this delete passes while (a) fails, the root is the
        // child-before-parent half-registration
        final IXdStorageTransaction tx = storage.beginTransaction();
        final FkBenchParent parent = new FkBenchParent();
        parent.setId("p0");
        parent.setLabel("parent-0");
        final FkBenchChild child = new FkBenchChild();
        child.setId("p0-c0");
        child.setLabel("child-0");
        child.setParent(parent);
        parent.addChild(child);
        storage.save(parent, tx); // the parent FIRST (the AutoSave cascade persists the child)
        storage.commitTransaction(tx);
        deleteChildExpectingVeto("p0-c0");
    }

    @Test
    @DisplayName("(a) КОНТРАКТ-ПИН: delete-child-alone при живой ссылке родителя = designed veto (CANON 2026-11-20)")
    public void plainDelete() throws Exception {
        seed();
        deleteChildExpectingVeto("p0-c0");
    }

    @Test
    @DisplayName("(d) КОНТРАКТ-ПИН: veto на новом id (не same-id)")
    public void deleteReinsertNewId() throws Exception {
        seed();
        deleteChildExpectingVeto("p0-c0");
        final IXdStorageTransaction tx = storage.beginTransaction();
        final FkBenchParent stub = new FkBenchParent();
        stub.setId("p0");
        final FkBenchChild fresh = new FkBenchChild();
        fresh.setId("p0-c1");
        fresh.setLabel("child-1");
        fresh.setParent(stub);
        storage.save(fresh, tx);
        storage.commitTransaction(tx);
        deleteChildExpectingVeto("p0-c1");
    }
}
