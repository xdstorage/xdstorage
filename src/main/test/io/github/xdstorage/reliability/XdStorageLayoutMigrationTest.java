package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.index.XdStorageIndexType;

/**
 * W-4e (docs/tasks/2.2.0/07-indexes-subdirectory): the indexes/ layout migration contract.
 *
 * <p>Migration moves legacy B+Tree/node files (data/XdStorageBTree/, data/XdStorageBTreeNode/)
 * and legacy id-index files (index/) into a dedicated indexes/ subdirectory at storage open.
 * Journals are NOT moved — they remain under indexes/&lt;class&gt;/journal/ (the JOURNAL_DIR
 * is a nested subdirectory of the index dir).
 *
 * <p>Scope of this class:
 * <ul>
 *   <li>One-time migration of each legacy layout on open</li>
 *   <li>Idempotency: re-opening after migration is a no-op</li>
 *   <li>Combined legacy (BTree + BTreeNode + index/ present together)</li>
 *   <li>Journals stay in place (indexes/&lt;class&gt;/journal/)</li>
 *   <li>New storage: indexes/ created from scratch</li>
 * </ul>
 */
@DisplayName("W-4e: indexes/ layout migration (IDXLAYOUT)")
public class XdStorageLayoutMigrationTest {

    @TempDir
    Path tempDir;

    // ───────────────────────────────────────────────────────────────────────────────
    // 1. Legacy index/ → indexes/ (id-index layout)
    // ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Legacy index/ → indexes/ (id-index layout): files move into indexes/")
    public void testLegacyIndexDirectoryMigratedToIndexes() throws Exception {
        final Path dir = tempDir.resolve("legacy-idindex-db");
        Files.createDirectories(dir);

        // Simulate legacy id-index layout: index/ dir with a class file
        final Path indexDir = Files.createDirectories(dir.resolve("index"));
        final Path indexFile = indexDir.resolve("BenchmarkEntity.yaml");
        Files.write(indexFile, "# legacy index file\n".getBytes());

        // Simulate a format marker so the storage recognises the folder
        writeFormatMarker(dir);

        // Open storage — the migration should move index/ → indexes/
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("migrate-idindex", dir.toString(), 64);
        try {
            // Verify migration: index/ is gone, indexes/ exists
            assertFalse(Files.exists(dir.resolve("index")),
                    "legacy index/ must be moved (not left behind)");
            assertTrue(Files.exists(dir.resolve("indexes")),
                    "indexes/ directory must exist after migration");
            assertTrue(Files.exists(dir.resolve("indexes/BenchmarkEntity.yaml")),
                    "index file moved to indexes/");
        } finally {
            storage.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // 2. Legacy data/XdStorageBTree/ → indexes/XdStorageBTree/
    // ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Legacy data/XdStorageBTree/ → indexes/XdStorageBTree/")
    public void testLegacyBTreeDirectoryMigrated() throws Exception {
        final Path dir = tempDir.resolve("legacy-btree-db");
        Files.createDirectories(dir);

        final Path dataDir = Files.createDirectories(dir.resolve("data"));
        final Path btreeDir = Files.createDirectories(dataDir.resolve("XdStorageBTree"));
        Files.write(btreeDir.resolve("BenchmarkEntity.yaml"), "btree data\n".getBytes());

        // Also create a node dir (to be moved)
        final Path bnodeDir = Files.createDirectories(dataDir.resolve("XdStorageBTreeNode"));
        Files.write(bnodeDir.resolve("BenchmarkEntity.yaml"), "node data\n".getBytes());

        writeFormatMarker(dir);

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("migrate-btree", dir.toString(), 64);
        try {
            assertTrue(Files.exists(dir.resolve("indexes")), "indexes/ must exist");
            assertTrue(Files.exists(dir.resolve("indexes/XdStorageBTree")), "XdStorageBTree moved");
            assertTrue(Files.exists(dir.resolve("indexes/XdStorageBTreeNode")), "XdStorageBTreeNode moved");
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTree")), "legacy BTree must be gone");
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTreeNode")), "legacy BTreeNode must be gone");
        } finally {
            storage.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // 3. Idempotency: re-opening after migration is a no-op
    // ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Re-opening after migration is idempotent (no re-migration)")
    public void testMigrationIdempotentOnReopen() throws Exception {
        final Path dir = tempDir.resolve("idempotent-migrate-db");
        Files.createDirectories(dir);

        // First open: create migration
        final IXdFileStorage storage1 = XdStorageProvider.newOrGetFileStorage("idem-migrate", dir.toString(), 64);
        storage1.shutdown();

        // Verify once: indexes/ exists, legacy dirs gone
        assertTrue(Files.exists(dir.resolve("indexes")));
        assertFalse(Files.exists(dir.resolve("data/XdStorageBTree")));
        assertFalse(Files.exists(dir.resolve("data/XdStorageBTreeNode")));

        // Second open: should not error, should not re-migrate (no legacy dirs to move)
        final IXdFileStorage storage2 = XdStorageProvider.newOrGetFileStorage("idem-migrate", dir.toString(), 64);
        try {
            // Storage works fine — no re-migration errors
            final IXdStorageTransaction tx = storage2.beginTransaction();
            storage2.save(new BenchmarkEntity("idem-1", "ok"), tx);
            tx.commit();

            // After second open, everything still correct
            assertTrue(Files.exists(dir.resolve("indexes")));
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTree")));
        } finally {
            storage2.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // 4. Combined legacy: all legacy dirs present at once
    // ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Combined legacy: index/ + data/BTree + data/BTreeNode all migrated together")
    public void testCombinedLegacyMigration() throws Exception {
        final Path dir = tempDir.resolve("combined-legacy-db");
        Files.createDirectories(dir);

        // All legacy directories
        Files.createDirectories(dir.resolve("index"));
        Files.write(dir.resolve("index").resolve("CombinedEntity.yaml"), "idx\n".getBytes());

        Files.createDirectories(dir.resolve("data/XdStorageBTree"));
        Files.write(dir.resolve("data/XdStorageBTree").resolve("CombinedEntity.yaml"), "btree\n".getBytes());

        Files.createDirectories(dir.resolve("data/XdStorageBTreeNode"));
        Files.write(dir.resolve("data/XdStorageBTreeNode").resolve("CombinedEntity.yaml"), "bnode\n".getBytes());

        writeFormatMarker(dir);

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("combined-migrate", dir.toString(), 64);
        try {
            assertFalse(Files.exists(dir.resolve("index")), "legacy index/ must be gone");
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTree")), "legacy BTree must be gone");
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTreeNode")), "legacy BTreeNode must be gone");
            assertTrue(Files.exists(dir.resolve("indexes/XdStorageBTree")), "BTree moved to indexes/");
            assertTrue(Files.exists(dir.resolve("indexes/XdStorageBTreeNode")), "BTreeNode moved to indexes/");
            assertTrue(Files.exists(dir.resolve("indexes/CombinedEntity.yaml")), "id-index file moved to indexes/");
        } finally {
            storage.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // 5. Journals stay under indexes/<class>/journal/ (NOT moved to top-level)
    // ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Journals are NOT moved to top-level; they stay under indexes/<class>/journal/")
    public void testJournalsStayUnderIndexesClassJournal() throws Exception {
        final Path dir = tempDir.resolve("journals-stay-db");
        Files.createDirectories(dir);

        // Create a new storage — it will have indexes/XdStorageBTreeNode/ and
        // indexes/<Class>/journal/
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("journals-stay", dir.toString(), 64);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(new BenchmarkEntity("j-1", "alpha"), tx);
            tx.commit();

            // Verify journal lives under indexes/<class>/journal/ (the JOURNAL_DIR)
            final Path classIndexDir = dir.resolve("indexes").resolve("BenchmarkEntity");
            final Path journalDir = classIndexDir.resolve(XdStorageYamlCommitJournal.JOURNAL_DIR);
            assertTrue(Files.exists(journalDir),
                    "journals must stay under indexes/<class>/journal/ (JOURNAL_DIR)");

            // Verify NO top-level journals/ directory was created
            assertFalse(Files.exists(dir.resolve("journals")),
                    "NO top-level journals/ directory should be created");
            assertFalse(Files.exists(dir.resolve("journals")),
                    "journals must NOT be at storage root level");
        } finally {
            storage.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // 6. New storage: indexes/ created from scratch (no legacy dirs)
    // ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("New storage: indexes/ created from scratch, no legacy dirs present")
    public void testNewStorageLayout() throws Exception {
        final Path dir = tempDir.resolve("new-storage-db");
        Files.createDirectories(dir);

        // No legacy dirs at all — a brand new storage
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("new-layout", dir.toString(), 64);
        try {
            // Use BTree-indexed entity to ensure indexes/ is created
            final IXdStorageTransaction tx = storage.beginTransaction();
            final CollapseForTest e1 = new CollapseForTest("n-1", "new");
            storage.save(e1, tx);
            final CollapseForTest e2 = new CollapseForTest("n-2", "new2");
            storage.save(e2, tx);
            tx.commit();

            // After write: indexes/ now exists (created lazily by atomic-file writes)
            assertTrue(Files.exists(dir.resolve("indexes")),
                    "indexes/ must exist after first BTree write");
            assertTrue(Files.exists(dir.resolve("indexes/XdStorageBTree")),
                    "indexes/XdStorageBTree must exist");
            assertTrue(Files.exists(dir.resolve("indexes/XdStorageBTreeNode")),
                    "indexes/XdStorageBTreeNode must exist");
            // data/ and structure/ exist (standard layout)
            assertTrue(Files.exists(dir.resolve("data")));
            assertTrue(Files.exists(dir.resolve("structure")));
            // No legacy dirs
            assertFalse(Files.exists(dir.resolve("index")));
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTree")));
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTreeNode")));

            // Verify data round-trips correctly
            storage.shutdown();
            final IXdFileStorage storage2 = XdStorageProvider.newOrGetFileStorage("new-layout", dir.toString(), 64);
            try {
                final IXdStorageTransaction tx2 = storage2.beginTransaction();
                final List<CollapseForTest> loaded = new java.util.ArrayList<>(storage2.load(CollapseForTest.class, tx2));
                assertEquals(2, loaded.size(), "both entities must load after reopen");
                tx2.commit();
            } finally {
                storage2.shutdown();
            }
        } finally {
            storage.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // 7. Migration does NOT touch non-migration directories
    // ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Migration does not touch data/ (non-BTree) or structure/ directories")
    public void testNonMigrationDirsUntouched() throws Exception {
        final Path dir = tempDir.resolve("untouched-dirs-db");
        Files.createDirectories(dir);

        // Create data/ with a regular entity dir (non-BTree)
        final Path entityDataDir = Files.createDirectories(dir.resolve("data").resolve("BenchmarkEntity"));
        Files.write(entityDataDir.resolve("BenchmarkEntity.yaml"), "entity data\n".getBytes());

        // Create structure/ dir
        final Path structureDir = Files.createDirectories(dir.resolve("structure"));
        Files.write(structureDir.resolve("BenchmarkEntity.yaml"), "struct data\n".getBytes());

        // Create a legacy BTree dir (should be moved)
        Files.createDirectories(dir.resolve("data/XdStorageBTree"));
        Files.write(dir.resolve("data/XdStorageBTree").resolve("BenchmarkEntity.yaml"), "btree\n".getBytes());

        writeFormatMarker(dir);

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("untouched-db", dir.toString(), 64);
        try {
            // BTree moved
            assertTrue(Files.exists(dir.resolve("indexes/XdStorageBTree")));
            assertFalse(Files.exists(dir.resolve("data/XdStorageBTree")));
            // Regular entity data stays in data/
            assertTrue(Files.exists(dir.resolve("data/BenchmarkEntity/BenchmarkEntity.yaml")));
            // structure stays
            assertTrue(Files.exists(dir.resolve("structure/BenchmarkEntity.yaml")));
        } finally {
            storage.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // Test entity: BTree-indexed for observable indexes/ layout
    // ───────────────────────────────────────────────────────────────────────────────

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    @XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
    public static class CollapseForTest {
        @XdStorageObjectId
        private String id;
        private String value;
        public CollapseForTest() {}
        public CollapseForTest(final String id, final String value) {
            this.id = id; this.value = value;
        }
        public String getId() { return id; }
        public void setId(final String id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────────────

    private void writeFormatMarker(final Path dir) throws IOException {
        final Path meta = dir.resolve(".xdstorage.meta");
        // Properties format: key=value (no quotes around values)
        final String metaContent = "formatVersion=2\n";
        Files.write(meta, metaContent.getBytes());
    }

    private void deleteQuietly(final Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
