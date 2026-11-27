package io.github.xdstorage.tools;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.structure.XdStorageClassStructure;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-3b (docs/tasks/2.2.0/17): schema-diff CLI correctness + v1-to-v2 migration.
 */
@DisplayName("T-3b: schema-diff CLI + v1-to-v2 migration")
public class XdStorageSchemaDiffTest {

    @TempDir
    Path tempDir;

    private XdStorageClassStructure mkClass(String type, String policy, String... fields) {
        final XdStorageClassStructure s = new XdStorageClassStructure();
        s.setType(type);
        s.setPolicy(XdStoragePolicy.valueOf(policy));
        if (fields.length > 0) {
            final Map<String, Class<?>> f = new LinkedHashMap<>();
            for (int i = 0; i < fields.length; i += 2) {
                f.put(fields[i], fieldClass(fields[i + 1]));
            }
            s.setFields(f);
        }
        return s;
    }

    private static Class<?> fieldClass(String name) {
        if ("String".equals(name)) return String.class;
        if ("Integer".equals(name)) return Integer.class;
        if ("Long".equals(name)) return Long.class;
        throw new IllegalArgumentException("unknown type: " + name);
    }

    private static Map<String, XdStorageClassStructure> map(String k, XdStorageClassStructure v) {
        final Map<String, XdStorageClassStructure> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    @Test
    @DisplayName("schema-diff: detects added field")
    public void testAddedField() {
        final XdStorageClassStructure a = mkClass("test.Foo", "StoreAsClassObjects", "id", "String", "name", "String");
        final XdStorageClassStructure b = mkClass("test.Foo", "StoreAsClassObjects", "id", "String", "name", "String", "extra", "String");
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map("Foo", a), map("Foo", b));
        assertTrue(diffs.stream().anyMatch(d -> "FIELD".equals(d.kind) && "ADDED".equals(d.action) && d.details.contains("extra")),
            "should report ADDED field 'extra': " + diffs);
    }

    @Test
    @DisplayName("schema-diff: detects removed field")
    public void testRemovedField() {
        final XdStorageClassStructure a = mkClass("test.Bar", "StoreAsClassObjects", "id", "String", "value", "Integer", "deprecated", "String");
        final XdStorageClassStructure b = mkClass("test.Bar", "StoreAsClassObjects", "id", "String", "value", "Integer");
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map("Bar", a), map("Bar", b));
        assertTrue(diffs.stream().anyMatch(d -> "FIELD".equals(d.kind) && "REMOVED".equals(d.action) && d.details.contains("deprecated")),
            "should report REMOVED field 'deprecated': " + diffs);
    }

    @Test
    @DisplayName("schema-diff: detects type change")
    public void testTypeChange() {
        final XdStorageClassStructure a = mkClass("test.Baz", "StoreAsClassObjects", "id", "String", "count", "Long");
        final XdStorageClassStructure b = mkClass("test.Baz", "StoreAsClassObjects", "id", "String", "count", "Integer");
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map("Baz", a), map("Baz", b));
        assertTrue(diffs.stream().anyMatch(d -> "FIELD".equals(d.kind) && "CHANGED".equals(d.action) && d.details.contains("count")),
            "should report CHANGED field 'count': " + diffs);
    }

    @Test
    @DisplayName("schema-diff: no differences for identical structures")
    public void testNoDifferences() {
        final XdStorageClassStructure a = mkClass("test.Same", "StoreAsClassObjects", "id", "String", "name", "String");
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map("Same", a), map("Same", a));
        assertTrue(diffs.isEmpty(), "should find no differences: " + diffs);
    }

    @Test
    @DisplayName("schema-diff: class ADDED when in B but not A")
    public void testClassAdded() {
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(new HashMap<>(), map("New", mkClass("test.New", "StoreAsClassObjects")));
        assertTrue(diffs.stream().anyMatch(d -> "CLASS".equals(d.kind) && "ADDED".equals(d.action)),
            "should report ADDED class 'New': " + diffs);
    }

    @Test
    @DisplayName("schema-diff: class REMOVED when in A but not B")
    public void testClassRemoved() {
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map("Old", mkClass("test.Old", "StoreAsClassObjects")), new HashMap<>());
        assertTrue(diffs.stream().anyMatch(d -> "CLASS".equals(d.kind) && "REMOVED".equals(d.action)),
            "should report REMOVED class 'Old': " + diffs);
    }

    @Test
    @DisplayName("migrate --dry-run: prints plan, folder byte-identical")
    public void testMigrateDryRun() throws Exception {
        final Path folder = tempDir.resolve("migrate-dryrun");
        Files.createDirectories(folder);
        final File folderFile = folder.toFile();

        final Properties props = new Properties();
        props.setProperty("formatVersion", "1");
        props.setProperty("format", "YAML");
        props.setProperty("storage", "test");
        props.setProperty("libraryVersion", "2.1.0");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folderFile, ".xdstorage.meta"))) {
            props.store(out, "test");
        }

        final File jsonFile = new File(folderFile, "Entity.yaml.json");
        Files.write(jsonFile.toPath(), "old json".getBytes());
        final Set<String> before = fileNames(folder);

        XdStorageMigrate.printMigrationPlan(folder);

        assertEquals(before, fileNames(folder), "folder unchanged after dry-run");
        assertTrue(jsonFile.exists(), ".json not renamed in dry-run");
    }

    @Test
    @DisplayName("migrate v1-to-v2: folder opens clean under 2.2.0")
    public void testMigrateV1toV2() throws Exception {
        final Path folder = tempDir.resolve("migrate-v1");
        Files.createDirectories(folder);
        final File folderFile = folder.toFile();

        final File dataDir = new File(folderFile, "data");
        Files.createDirectories(dataDir.toPath());
        final File dataFile = new File(dataDir, "Foo.yaml");
        Files.write(dataFile.toPath(), "objects:\n- object:\n    class: test.Foo\n    id: \"1\"\n".getBytes());

        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
            "migrate-test", folder.toString(),
            XdStorageConfig.builder().acceptMissingEofSentinel(true).build());

        final IXdStorageTransaction tx = storage.beginTransaction();
        try {
            storage.checkpoint();
            tx.commit();
        } finally {
            storage.shutdown();
        }

        final IXdFileStorage storage2 = XdStorageProvider.newOrGetFileStorage(
            "migrate-test-verify", folder.toString(),
            XdStorageConfig.defaults());
        storage2.shutdown();
    }

    private Set<String> fileNames(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> p.toString().substring(dir.toString().length() + 1)).collect(java.util.stream.Collectors.toSet());
        }
    }
}
