package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TREEFILE (docs/tasks/2.1.0/41) — the full-collapse contract of a B+tree id index.
 *
 * <p>Pins the observable end state the leak investigation established: when every record
 * of a B+tree-indexed class is deleted, the collapse must empty the tree (no node files
 * survive) and the emptied tree must stay empty — a later operation against it must not
 * resurrect node files or a stale root, and the storage must reopen cleanly and accept
 * new inserts into the same (re-grown) tree.
 */
public class XdStorageBTreeFullCollapseTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    @XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
    public static class CollapseEntity {
        @XdStorageObjectId
        private Long id;
        private String value;

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    private static final String STORAGE_NAME = "collapse-test-" + System.nanoTime();
    private static final String STORAGE_DIR = "./target/collapse-db-" + System.nanoTime();

    private IXdFileStorage storage;

    @BeforeEach
    public void openStorage() {
        deleteDir(new File(STORAGE_DIR));
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 200);
    }

    @AfterEach
    public void closeStorage() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    private static File nodeDir() {
        return new File(STORAGE_DIR, "data/XdStorageBTreeNode");
    }

    private static List<File> nodeFiles() {
        final File dir = nodeDir();
        final File[] files = dir.listFiles((d, n) -> n.endsWith(".yaml") && n.startsWith("XdStorageBTreeNode-"));
        if (files == null) {
            return new ArrayList<>();
        }
        final List<File> result = new ArrayList<>();
        for (final File f : files) {
            result.add(f);
        }
        return result;
    }

    private static int entityFileCount() throws IOException {
        final File classDir = new File(STORAGE_DIR, "data/XdStorageBTreeFullCollapseTest$CollapseEntity");
        if (!classDir.isDirectory()) {
            return 0;
        }
        final File[] files = classDir.listFiles((d, n) -> n.endsWith(".yaml") && n.contains("CollapseEntity-"));
        return files == null ? 0 : files.length;
    }

    private List<Long> insertBatch(final int from, final int count) throws Exception {
        final List<Long> ids = new ArrayList<>();
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            for (int i = from; i < from + count; ++i) {
                final CollapseEntity e = new CollapseEntity();
                e.setValue("v" + i);
                storage.save(e, tx);
                ids.add(e.getId());
            }
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
        return ids;
    }

    private void deleteAll(final List<Long> ids) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            for (final Long id : ids) {
                final CollapseEntity ref = new CollapseEntity();
                ref.setId(id);
                storage.delete(ref, tx);
            }
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    @Test
    public void fullCollapseRemovesNodeFilesAndStaysEmpty() throws Exception {
        // Grow the tree past one node (t=4): 40 records force splits and multiple node files.
        final List<Long> ids = insertBatch(0, 40);
        assertTrue(nodeFiles().size() >= 1, "the grown tree must have node files");

        // Delete everything in one transaction: the collapse must empty the tree and
        // remove the node records.
        deleteAll(ids);
        assertEquals(0, entityFileCount(), "no entity record files may remain");
        assertEquals(0, nodeFiles().size(),
                "the fully collapsed tree must not keep node files (TREEFILE leak)");

        // The emptied tree refuses phantom deletes loudly and does not resurrect anything.
        final CollapseEntity phantom = new CollapseEntity();
        phantom.setId(987654321L);
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        assertThrows(XdStorageException.class, () -> storage.delete(phantom, tx),
                "a delete against the emptied index must fail loudly");
        tx.rollback();
        assertEquals(0, nodeFiles().size(), "the failed phantom delete must not resurrect node files");

        // The class still loads (empty) through a fresh transaction.
        final IXdStorageTransaction check = storage.beginTransaction(15000L);
        assertTrue(storage.load(CollapseEntity.class, check).isEmpty());
        check.commit();
    }

    @Test
    public void collapsedTreeReopensAndRegrows() throws Exception {
        // Fill + fully delete + shut down: the on-disk tree record carries root=null.
        final List<Long> ids = insertBatch(0, 30);
        deleteAll(ids);
        storage.shutdown();
        storage = null;

        // Reopen the same folder: the collapsed tree must parse cleanly and accept growth.
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 200);
        final List<Long> second = insertBatch(1000, 10);
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        final java.util.Collection<?> loaded = storage.load(CollapseEntity.class, tx);
        tx.commit();
        assertEquals(10, loaded.size(), "the re-grown tree must serve its records");
        deleteAll(second);
        assertEquals(0, entityFileCount());
        assertEquals(0, nodeFiles().size());
    }
}
