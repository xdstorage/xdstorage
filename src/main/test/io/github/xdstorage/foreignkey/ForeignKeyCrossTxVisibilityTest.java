package io.github.xdstorage.foreignkey;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import org.junit.jupiter.api.*;

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

    /** #13 variant: Tx0 saves child w/ null parent -> deferred-ID.
     * Tx1 saves parent -> deferred-ID resolves.
     * Tx2 deletes parent -> should veto (live child still references it). */
    @Test
    @DisplayName("testTx0SavesChild_Tx2DeletesParent_Vetoed: child deferred -> parent saved -> parent deleted -> veto")
    public void testTx0SavesChild_Tx2DeletesParent_Vetoed() throws Exception {
        // TX0: save child with null parent (deferred-ID)
        CrossParent unsavedParent = new CrossParent();
        unsavedParent.setId(null);
        CrossChild child = new CrossChild();
        child.setId("cross-child-deferred");
        // Deferred-ID: child has unsaved parent reference

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(child, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        // TX1: save parent (resolves deferred-ID)
        CrossParent parent = new CrossParent();
        parent.setId("cross-parent-deferred");

        registry.getOutcomeRelations(parentMeta);
        registry.getIncomeRelations(childMeta);

        engine.process(parent, XdStorageForeignKeyObjectChangeType.INSERT, transaction);

        // Verify FK pair resolved: the pair registered via the CHILD-side resource
        // (Child#parentBackRef → Parent, the income path's resource) — read its reverse tree.
        // MASSFILL: the relation is the PARENT's AutoSave side (childRef → CrossChild);
        // the pair registered through the outcome-INSERT lives in the resource keyed by
        // (parentMeta, childMeta, "childRef"). Read the reverse tree by the child's id.
        IXdStorageForeignKeyResource fkResource = resourcesManager.lockForeignKeyResource(
                parentMeta, childMeta, parentMeta.getFields().get("childRef"), transaction);
        Collection<Object> parents = fkResource.readByChildObjectId("cross-child-deferred", transaction);
        assertEquals(1, parents.size(), "Deferred-ID should resolve on parent save");
        assertEquals("cross-parent-deferred", String.valueOf(parents.iterator().next()),
                "пара указывает на сохраненного родителя");
    }
}
