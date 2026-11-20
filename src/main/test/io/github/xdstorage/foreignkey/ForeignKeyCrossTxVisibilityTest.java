package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FK: Cross-transaction visibility (P1 test #12).
 * Tx0 saves parent+child, Tx1 loads parent -> should see Tx0's child in FK tree.
 * Extends AbstractForeignKeyEngineIntegrationTest for resourcesManager access.
 */
@DisplayName("FK: Cross-tx visibility (Tx0 saves, Tx1 sees)")
public class ForeignKeyCrossTxVisibilityTest extends AbstractForeignKeyEngineIntegrationTest {

    // --- Test entities with real DB annotations ---
    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    public static class CrossParent {
        @XdStorageObjectId public String id;
        @XdStorageAutoSave public CrossChild childRef;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public CrossChild getChildRef() { return childRef; }
        public void setChildRef(CrossChild c) { this.childRef = c; }
    }

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    public static class CrossChild {
        @XdStorageObjectId public String id;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }

    private XdStorageClassInfo parentMeta;
    private XdStorageClassInfo childMeta;

    @BeforeEach
    void initMetadata() {
        parentMeta = XdStorageClassMetadataRegistry.getClassInfo(CrossParent.class);
        childMeta = XdStorageClassMetadataRegistry.getClassInfo(CrossChild.class);
    }

    /** #12: Tx0 saves parent+child, Tx1 loads parent -> sees child in FK tree. */
    @Test
    @DisplayName("testTx0SavesParentChild_Tx1SeesChild: Tx0 saves -> Tx1 loads -> child visible")
    public void testTx0SavesParentChild_Tx1SeesChild() throws Exception {
        // TX0: save parent + child
        CrossParent parent = new CrossParent();
        parent.setId("parent-cross-vis-1");
        CrossChild child = new CrossChild();
        child.setId("child-cross-vis-1");
        parent.setChildRef(child);

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);
        engine.process(child, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        // Verify FK tree has the pair
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
        Collection<Object> children = fkResource.readByParentObjectId("parent-cross-vis-1", transaction);
        assertEquals(1, children.size(), "Tx0 saved FK pair should be visible in tree");
        assertTrue(children.stream().anyMatch(c -> "child-cross-vis-1".equals(String.valueOf(c))));
    }

    /** #13 variant — GROUP B REAL-STORAGE MIGRATION (2026-11-20): the old body asserted a
     * cross-tx DEFERRED-ID resolution on the mock harness, but its premise was unwireable:
     * the entities were never wired together (CrossChild has no parent field at all) and a
     * tx-scoped pending leaves no trace across txs (TX isolation canon — the deferred-ID
     * resolution shapes are pinned on real storage by FxForeignKeyDeferredIdResolverTest).
     * The name's intent is pinned here instead: TX0 commits a wired parent+child pair,
     * TX1 sees it, TX2 deletes the parent alone → VETO (the committed child still
     * references it — the DeleteParentAlone canon). */
    @Test
    @DisplayName("testTx0CommitsPair_Tx2DeletesParent_Vetoed: TX0 commit -> TX1 sees -> TX2 parent delete -> veto")
    public void testTx0SavesChild_Tx2DeletesParent_Vetoed() throws Exception {
        final String unique = String.valueOf(System.nanoTime());
        final String path = "./target/fk_cross_tx_vis_" + unique;
        IXdFileStorage realStorage = XdStorageProvider.newOrGetFileStorage("fk_cross_vis_" + unique, path, 128, FilesFormat.YAML);
        try {
            // TX0: save parent + child (wired via @XdStorageAutoSave childRef) -> commit
            IXdStorageTransaction tx0 = realStorage.beginTransaction();
            CrossParent parent = new CrossParent();
            parent.setId("cross-parent-deferred");
            CrossChild child = new CrossChild();
            child.setId("cross-child-deferred");
            parent.setChildRef(child);
            realStorage.save(parent, tx0);
            tx0.commit();

            // TX1: the committed pair is visible (the child loads through the parent)
            IXdStorageTransaction tx1 = realStorage.beginTransaction();
            CrossParent loaded = realStorage.load(CrossParent.class, "cross-parent-deferred", tx1);
            assertNotNull(loaded, "parent persisted in TX0");
            assertNotNull(loaded.getChildRef(), "the committed parent still references the child");
            assertEquals("cross-child-deferred", loaded.getChildRef().getId(), "the pair survived the commit");
            tx1.commit();

            // TX2: delete the parent alone -> VETO (the live child still references it)
            IXdStorageTransaction tx2 = realStorage.beginTransaction();
            CrossParent toDelete = realStorage.load(CrossParent.class, "cross-parent-deferred", tx2);
            assertNotNull(toDelete);
            realStorage.delete(toDelete, tx2);
            assertThrows(XdStorageRuntimeException.class, () -> tx2.commit(),
                    "СУБД позволила удалить родителя, на которого ещё ссылается живой ребёнок!");
            tx2.rollback();
        } finally {
            realStorage.shutdown();
            deleteDir(new File(path));
        }
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }
}
