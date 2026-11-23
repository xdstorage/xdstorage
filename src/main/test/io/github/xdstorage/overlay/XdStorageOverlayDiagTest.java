package io.github.xdstorage.overlay;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W-4 DE-ALIASING PINS (2026-11-21): the empirical closure of the two former
 * @Disabled overlay pins. THE ROOT CAUSE of their redness was the TEST-ENTITY PREMISE
 * (OvParent's children field had getChildren without setChildren — the engine's
 * documented JavaBeans contract DROPPED the field from persistence with a WARN;
 * NOTHING round-tripped: not the seed, not the commit, not the rollback). With
 * setChildren added, every flow is empirically green:
 * <ul>
 *   <li>the seed round-trip, the commit survival (both in-memory and the REOPEN
 *       file-truth),</li>
 *   <li>the foreign-tx visibility (no uncommitted leak — the aliasing premise of the
 *       old disable notes was WRONG for these flows),</li>
 *   <li>the rollback restore (the canonical returns to the exact pre-tx content).</li>
 * </ul>
 * The reopen file-truth assertion is this pin's unique contribution (the overlay
 * pins check the in-memory state only).
 */
@DisplayName("W-4 DIAG: commit data-loss + rollback restore direction")
public class XdStorageOverlayDiagTest {

    private static final String STORAGE_PATH = "./target/overlay-diag-" + System.nanoTime();
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        deleteDir(new File(STORAGE_PATH));
        final XdStorageConfig config = new XdStorageConfig.Builder()
                .journalWrites(true)
                .setEnableForeignKeys(true)
                .build();
        storage = XdStorageProvider.newOrGetFileStorage(
                "overlay_diag_" + System.nanoTime(), STORAGE_PATH, 200, FilesFormat.YAML, config);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (final Exception ignored) {
            }
        }
        deleteDir(new File(STORAGE_PATH));
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

    private void walkFiles(final File file, final int depth) {
        if (file.isFile()) {
            String content = "";
            try {
                content = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
            } catch (final Exception ignored) {
            }
            final boolean mentionsChild = content.contains("diag-child-0") || content.contains("diag-parent-1");
            System.out.println("[FILES] " + file.getPath() + " (" + file.length() + " b, mentionsSeed=" + mentionsChild
                    + ")");
            if (mentionsChild && file.length() < 4000) {
                System.out.println("[FILES] ---- content ----\n" + content + "\n---- end ----");
            }
            return;
        }
        final File[] list = file.listFiles();
        if (list != null) {
            for (final File f : list) {
                walkFiles(f, depth + 1);
            }
        }
    }

    private void dump(final OvParent parent, final String tag) {
        final int count = parent.getChildren() == null ? -1 : parent.getChildren().size();
        final StringBuilder ids = new StringBuilder();
        if (parent.getChildren() != null) {
            for (final OvChild c : parent.getChildren()) {
                ids.append(c.getId()).append(" ");
            }
        }
        System.out.println("[DIAG] " + tag + ": children=" + count + " ids=[" + ids + "]"
                + " instance=" + System.identityHashCode(parent)
                + " innerContainer=" + System.identityHashCode(parent.getChildren()));
    }

    @Test
    @DisplayName("коммит update-потока: ребёнок выживает в файле? (reopen-проба)")
    public void commitSurvival_ReopenProbe() throws Exception {
        final IXdStorageTransaction tx0 = storage.beginTransaction();
        final OvParent parent = new OvParent();
        parent.setId("diag-parent-1");
        final OvChild child0 = new OvChild();
        child0.setId("diag-child-0");
        parent.addChild(child0);
        storage.save(parent, tx0);
        tx0.commit();

        walkFiles(new File(STORAGE_PATH), 0);

        final IXdStorageTransaction tx1 = storage.beginTransaction();
        final OvParent loaded = storage.load(OvParent.class, "diag-parent-1", tx1);
        dump(loaded, "tx1 loaded");
        final OvChild child1 = new OvChild();
        child1.setId("diag-child-1");
        loaded.addChild(child1);
        dump(loaded, "tx1 after addChild");
        storage.update(loaded, tx1);
        dump(loaded, "tx1 after update");
        tx1.commit();

        final IXdStorageTransaction post = storage.beginTransaction();
        final OvParent postLoad = storage.load(OvParent.class, "diag-parent-1", post);
        dump(postLoad, "fresh tx AFTER commit (no reopen)");
        post.commit();

        // reopen: the file truth
        storage.shutdown();
        storage = XdStorageProvider.newOrGetFileStorage(
                "overlay_diag_reopen_" + System.nanoTime(), STORAGE_PATH, 200, FilesFormat.YAML, new XdStorageConfig
                        .Builder()
                        .journalWrites(true)
                        .setEnableForeignKeys(true)
                        .build());
        final IXdStorageTransaction postReopen = storage.beginTransaction();
        final OvParent reopenLoad = storage.load(OvParent.class, "diag-parent-1", postReopen);
        dump(reopenLoad, "fresh tx AFTER REOPEN (the file truth)");
        postReopen.commit();

        assertEquals(2, reopenLoad.getChildren().size(), "после reopen оба ребёнка обязаны быть (файловая правда)");
    }

    @Test
    @DisplayName("rollback update-потока: канонический возвращается к pre-tx состоянию?")
    public void rollbackRestore_DirectionProbe() throws Exception {
        final IXdStorageTransaction tx0 = storage.beginTransaction();
        final OvParent parent = new OvParent();
        parent.setId("diag-parent-rb");
        final OvChild child0 = new OvChild();
        child0.setId("diag-rb-child-0");
        parent.addChild(child0);
        storage.save(parent, tx0);
        tx0.commit();

        final IXdStorageTransaction tx1 = storage.beginTransaction();
        final OvParent loaded = storage.load(OvParent.class, "diag-parent-rb", tx1);
        final OvChild child1 = new OvChild();
        child1.setId("diag-rb-child-1");
        loaded.addChild(child1);
        dump(loaded, "rb tx1 after addChild (2 expected)");
        storage.update(loaded, tx1);
        tx1.rollback();

        final IXdStorageTransaction post = storage.beginTransaction();
        final OvParent postLoad = storage.load(OvParent.class, "diag-parent-rb", post);
        dump(postLoad, "fresh tx AFTER rollback (1 expected — pre-tx)");
        post.commit();

        assertEquals(1, postLoad.getChildren().size(), "rollback обязан вернуть pre-tx состояние (1 ребёнок)");
        assertEquals("diag-rb-child-0", postLoad.getChildren().iterator().next().getId(), "ровно исходный ребёнок");
    }
}
