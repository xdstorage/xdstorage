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
 * FK: Canonical topology build (4 tests).
 * Проверка, что XdStorageForeignKeyRegistry правильно строит canonical relation topology:
 * - income relations (child's @XdStorageParentObject)
 * - outcome relations (parent's @XdStorageAutoSave)
 * - dedupe (one relation per parent/child/field triplet)
 * - bidirectional (income + outcome)
 */
@DisplayName("FK: Registry topology build (4 tests)")
public class FxForeignKeyRegistryTopologyTest {

    private IXdFileStorage storage;
    private String storagePath;
    private String storageName;

    @BeforeEach
    public void setUp() {
        storagePath = "./target/test_fk_registry_" + System.nanoTime();
        storageName = "fk_registry_" + System.nanoTime();
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
     * Test 1: registry build correct hierarchy.
     * Save parent + child → registry should have correct income/outcome relations.
     */
    @Test
    @DisplayName("buildCorrectHierarchy: parent has income from child, child has outcome from parent")
    public void buildCorrectHierarchy() throws Exception {
        // TX0: save parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("registry-parent-1");
        Child child = new Child();
        child.setId("registry-child-1");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // Verify: parent should have income relations from child
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "registry-parent-1", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Parent should have exactly 1 income relation from child");
        verifyTx.commit();
    }

    /**
     * Test 2: bidirectional relations.
     * Save parent + child → child should have back-reference to parent.
     */
    @Test
    @DisplayName("buildBidirectional: child has back-ref to parent")
    public void buildBidirectional() throws Exception {
        // TX0: save parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("registry-parent-2");
        Child child = new Child();
        child.setId("registry-child-2");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // Verify: child has back-reference to parent
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Child loaded = storage.load(Child.class, "registry-child-2", verifyTx);
        assertNotNull(loaded);
        Parent backRef = loaded.getParent();
        assertNotNull(backRef);
        assertEquals("registry-parent-2", String.valueOf(backRef.getId()),
                "Child should have back-reference to parent");
        verifyTx.commit();
    }

    /**
     * Test 3: dedupe (one relation per parent/child/field triplet).
     * Save parent + child twice → only 1 relation (idempotent).
     */
    @Test
    @DisplayName("buildDedupe: save parent+child twice → 1 relation")
    public void buildDedupe() throws Exception {
        // TX0: save parent + child
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("registry-parent-3");
        Child child = new Child();
        child.setId("registry-child-3");
        parent.addChild(child);
        child.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // TX1: save same parent + child again
        IXdStorageTransaction tx1 = storage.beginTransaction();
        Parent parent2 = new Parent();
        parent2.setId("registry-parent-3");
        Child child2 = new Child();
        child2.setId("registry-child-3");
        parent2.addChild(child2);
        child2.setParent(parent2);
        storage.update(parent2, tx1);
        tx1.commit();

        // Verify: exactly 1 relation (idempotent)
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "registry-parent-3", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Save twice should be idempotent: only 1 relation");
        verifyTx.commit();
    }

    /**
     * Test 4: multiple children → multiple relations.
     * Save parent with 2 children → 2 relations.
     */
    @Test
    @DisplayName("buildMultipleChildren: parent w/ 2 children → 2 relations")
    public void buildMultipleChildren() throws Exception {
        // TX0: save parent + 2 children
        IXdStorageTransaction tx0 = storage.beginTransaction();
        Parent parent = new Parent();
        parent.setId("registry-parent-4");
        Child child1 = new Child();
        child1.setId("registry-child-4a");
        Child child2 = new Child();
        child2.setId("registry-child-4b");
        parent.addChild(child1);
        parent.addChild(child2);
        child1.setParent(parent);
        child2.setParent(parent);
        storage.save(parent, tx0);
        tx0.commit();

        // Verify: parent has 2 children
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "registry-parent-4", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(2, children.size(), "Parent should have exactly 2 children");
        verifyTx.commit();
    }
}
