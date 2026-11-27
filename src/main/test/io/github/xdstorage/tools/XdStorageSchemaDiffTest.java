package io.github.xdstorage.tools;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.entities.XdStorageIndexedInstantEntity;
import io.github.xdstorage.entities.XdStorageMigrateOrder;
import io.github.xdstorage.structure.XdStorageClassStructure;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MIGRATE (docs/tasks/2.2.0/17): the schema-diff CLI — the comparison itself, and the half
 * the first version never exercised: reading what a folder really stores (one file of
 * {@code XdStorageClassStructure} records, written by {@code executeStructureUpdate}),
 * comparing it with the live classes, the JSON output, and the exit codes.
 */
@DisplayName("MIGRATE (2.2.0/17): schema-diff")
class XdStorageSchemaDiffTest {

    private static final String ORDER = XdStorageMigrateOrder.class.getName();
    private static final String EVENT = XdStorageIndexedInstantEntity.class.getName();

    @TempDir
    Path tempDir;

    // === the comparison ===

    @Test
    @DisplayName("1. Поле добавлено")
    void testAddedField() {
        final XdStorageClassStructure a = structure("test.Foo", "id", String.class, "name", String.class);
        final XdStorageClassStructure b = structure("test.Foo", "id", String.class, "name", String.class,
                "extra", String.class);
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map(a), map(b));
        assertTrue(diffs.stream().anyMatch(d -> "FIELD".equals(d.kind) && "ADDED".equals(d.action)
                && d.details.contains("extra")), diffs.toString());
    }

    @Test
    @DisplayName("2. Поле удалено")
    void testRemovedField() {
        final XdStorageClassStructure a = structure("test.Bar", "id", String.class, "value", Integer.class,
                "deprecated", String.class);
        final XdStorageClassStructure b = structure("test.Bar", "id", String.class, "value", Integer.class);
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map(a), map(b));
        assertTrue(diffs.stream().anyMatch(d -> "FIELD".equals(d.kind) && "REMOVED".equals(d.action)
                && d.details.contains("deprecated")), diffs.toString());
    }

    @Test
    @DisplayName("3. Тип поля изменился")
    void testTypeChange() {
        final XdStorageClassStructure a = structure("test.Baz", "id", String.class, "count", Long.class);
        final XdStorageClassStructure b = structure("test.Baz", "id", String.class, "count", Integer.class);
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map(a), map(b));
        assertTrue(diffs.stream().anyMatch(d -> "FIELD".equals(d.kind) && "CHANGED".equals(d.action)
                && d.details.contains("count")), diffs.toString());
    }

    @Test
    @DisplayName("4. Различий нет")
    void testNoDifferences() {
        final XdStorageClassStructure a = structure("test.Same", "id", String.class, "name", String.class);
        assertTrue(XdStorageSchemaDiff.computeDiffs(map(a), map(a)).isEmpty());
    }

    @Test
    @DisplayName("5. Класс добавлен / удалён")
    void testClassAddedAndRemoved() {
        final XdStorageClassStructure only = structure("test.Only", "id", String.class);
        assertTrue(XdStorageSchemaDiff.computeDiffs(new HashMap<>(), map(only)).stream()
                .anyMatch(d -> "CLASS".equals(d.kind) && "ADDED".equals(d.action)));
        assertTrue(XdStorageSchemaDiff.computeDiffs(map(only), new HashMap<>()).stream()
                .anyMatch(d -> "CLASS".equals(d.kind) && "REMOVED".equals(d.action)));
    }

    @Test
    @DisplayName("6. Политика хранения изменилась")
    void testPolicyChange() {
        final XdStorageClassStructure a = structure("test.P", "id", String.class);
        a.setPolicy(XdStoragePolicy.StoreAsSingleObject);
        final XdStorageClassStructure b = structure("test.P", "id", String.class);
        final List<XdStorageSchemaDiff.Diff> diffs = XdStorageSchemaDiff.computeDiffs(map(a), map(b));
        assertTrue(diffs.stream().anyMatch(d -> "POLICY".equals(d.kind) && "CHANGED".equals(d.action)),
                diffs.toString());
    }

    // === what a folder stores ===

    @Test
    @DisplayName("7. Читает то, что движок действительно хранит: один файл записей XdStorageClassStructure")
    void readsWhatTheEngineStores() throws Exception {
        final Path folder = folderWithStoredStructures("stored");

        final XdStorageSchemaDiff.Stored stored = XdStorageSchemaDiff.readFolder(folder);

        assertTrue(stored.structureFile);
        assertTrue(stored.classesWithoutStructure.isEmpty(), stored.classesWithoutStructure.toString());
        final XdStorageClassStructure order = stored.structures.get(ORDER);
        assertEquals(String.class, order.getFields().get("id"));
        assertEquals(String.class, order.getFields().get("name"));
        assertEquals(Long.class, order.getFields().get("amount"));
        assertEquals(XdStoragePolicy.StoreAsClassObjects, order.getPolicy());
        assertEquals(Instant.class, stored.structures.get(EVENT).getIndexes().get("by_at").getFields().get("at"));
    }

    @Test
    @DisplayName("8. Сохранённая структура совпадает с живым классом — поля и индекс")
    void storedStructureEqualsTheLiveClass() throws Exception {
        final Path folder = folderWithStoredStructures("stored-vs-live");

        final Run run = run(folder.toString(), "--against-classes", ORDER, EVENT);

        assertEquals(0, run.exit, run.out + run.err);
        assertTrue(run.out.contains("OK: no differences found."), run.out);
    }

    @Test
    @DisplayName("9. Каталог без сохранённых структур: сказано в заметке, классы не названы добавленными")
    void folderWithoutStructuresIsNotedNotDiffed() throws Exception {
        final Path fresh = folderWithRecordsOnly("fresh");

        final XdStorageSchemaDiff.Stored stored = XdStorageSchemaDiff.readFolder(fresh);
        assertFalse(stored.structureFile);
        assertTrue(stored.structures.isEmpty());
        assertTrue(stored.classesWithoutStructure.contains("XdStorageMigrateOrder"), stored.toString());

        final Run againstClasses = run(fresh.toString(), "--against-classes", ORDER);
        assertEquals(0, againstClasses.exit, againstClasses.out + againstClasses.err);
        assertTrue(againstClasses.err.contains("stores no class structure"), againstClasses.err);
        assertTrue(againstClasses.out.contains("nothing to compare"), againstClasses.out);
        assertFalse(againstClasses.out.contains("CLASS ADDED"), againstClasses.out);

        final Run againstFolder = run(fresh.toString(), folderWithStoredStructures("fresh-vs-stored").toString());
        assertEquals(0, againstFolder.exit, againstFolder.out + againstFolder.err);
        assertFalse(againstFolder.out.contains("CLASS ADDED"), againstFolder.out);
    }

    @Test
    @DisplayName("10. Два каталога с одинаковой структурой: выход 0")
    void twoFoldersAgree() throws Exception {
        final Run run = run(folderWithStoredStructures("p1").toString(),
                folderWithStoredStructures("p2").toString());
        assertEquals(0, run.exit, run.out + run.err);
        assertTrue(run.out.contains("OK: no differences found."), run.out);
    }

    @Test
    @DisplayName("11. В старом каталоге поля ещё нет: FIELD ADDED (старый → новый), выход 1")
    void fieldRemovedInOneFolder() throws Exception {
        final Path newer = folderWithStoredStructures("newer");
        final Path older = folderWithStoredStructures("older");
        removeFieldEntry(older, "amount");

        final Run run = run(older.toString(), newer.toString());

        assertEquals(XdStorageSchemaDiff.DIFFERENCES_FOUND, run.exit, run.out + run.err);
        assertTrue(run.out.contains(ORDER + " FIELD ADDED amount Long"), run.out);
    }

    @Test
    @DisplayName("12. --json: один JSON-массив на stdout")
    void jsonOutput() throws Exception {
        final Path newer = folderWithStoredStructures("json-newer");
        final Path older = folderWithStoredStructures("json-older");
        removeFieldEntry(older, "amount");

        final Run run = run(older.toString(), newer.toString(), "--json");
        final String json = run.out.trim();

        assertEquals(XdStorageSchemaDiff.DIFFERENCES_FOUND, run.exit, run.out + run.err);
        assertTrue(json.startsWith("[") && json.endsWith("]"), json);
        assertTrue(json.contains("\"kind\":\"FIELD\"") && json.contains("\"action\":\"ADDED\"")
                && json.contains("\"details\":\"amount Long\""), json);
        assertEquals("[]", run(newer.toString(), newer.toString(), "--json").out.trim());
    }

    @Test
    @DisplayName("13. Ошибки использования — выход 2; каталог не читается — выход 3")
    void exitCodes() throws Exception {
        final Path folder = folderWithStoredStructures("codes");
        assertEquals(XdStorageSchemaDiff.USAGE, run().exit);
        assertEquals(XdStorageSchemaDiff.USAGE, run(folder.toString()).exit);
        assertEquals(XdStorageSchemaDiff.USAGE, run(folder.toString(), "--against-classes").exit);
        assertEquals(XdStorageSchemaDiff.USAGE, run(folder.toString(), "--no-such-option").exit);
        assertEquals(XdStorageSchemaDiff.CANNOT_READ,
                run(tempDir.resolve("no-such-folder").toString(), folder.toString()).exit);
    }

    // === fixtures ===

    private static XdStorageClassStructure structure(final String type, final Object... namesAndTypes) {
        final XdStorageClassStructure s = new XdStorageClassStructure();
        s.setType(type);
        s.setPolicy(XdStoragePolicy.StoreAsClassObjects);
        final Map<String, Class<?>> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            fields.put((String) namesAndTypes[i], (Class<?>) namesAndTypes[i + 1]);
        }
        s.setFields(fields);
        return s;
    }

    private static Map<String, XdStorageClassStructure> map(final XdStorageClassStructure... structures) {
        final Map<String, XdStorageClassStructure> map = new LinkedHashMap<>();
        for (final XdStorageClassStructure structure : structures) {
            map.put(structure.getType(), structure);
        }
        return map;
    }

    /** Records of both classes; with {@code storeStructures}, their structures stored the way the engine does. */
    private Path folderWithStoredStructures(final String name) throws Exception {
        final Path folder = folderWithRecordsOnly(name);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("diff-structure-" + System.nanoTime(),
                folder.toAbsolutePath().toString(), 64);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            storage.executeStructureUpdate(XdStorageMigrateOrder.class);
            storage.executeStructureUpdate(XdStorageIndexedInstantEntity.class);
            storage.commitTransaction(tx);
        } finally {
            storage.shutdown();
        }
        return folder;
    }

    private Path folderWithRecordsOnly(final String name) throws Exception {
        final Path folder = Files.createDirectories(tempDir.resolve(name));
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("diff-" + System.nanoTime(),
                folder.toAbsolutePath().toString(), 64);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            storage.save(new XdStorageMigrateOrder("o-1", "one", 10L), tx);
            storage.save(new XdStorageIndexedInstantEntity("i-1", Instant.EPOCH), tx);
            storage.commitTransaction(tx);
        } finally {
            storage.shutdown();
        }
        return folder;
    }

    /** Takes one field out of a folder's stored structure — an older schema, made by hand. */
    private static void removeFieldEntry(final Path folder, final String field) throws IOException {
        final Path file = folder.resolve(XdStorageSchemaDiff.STRUCTURE_FILE);
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int value = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("value: '" + field + "'")) {
                value = i;
                break;
            }
        }
        assertTrue(value > 0, "the stored structure has a field " + field);
        int start = value;
        while (!lines.get(start).contains("- entry:")) {
            start--;
        }
        int end = value + 1;
        while (end < lines.size() && !lines.get(end).contains("- entry:")
                && lines.get(end).length() - lines.get(end).trim().length() >= lines.get(start).length()
                - lines.get(start).trim().length()) {
            end++;
        }
        final List<String> kept = new ArrayList<>(lines.subList(0, start));
        kept.addAll(lines.subList(end, lines.size()));
        Files.write(file, (String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static Run run(final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit = XdStorageSchemaDiff.run(args, utf8(out), utf8(err));
        return new Run(exit, new String(out.toByteArray(), StandardCharsets.UTF_8),
                new String(err.toByteArray(), StandardCharsets.UTF_8));
    }

    private static PrintStream utf8(final ByteArrayOutputStream bytes) {
        try {
            return new PrintStream(bytes, true, "UTF-8");
        } catch (final java.io.UnsupportedEncodingException never) {
            throw new AssertionError(never);    // UTF-8 is in every charset decoder
        }
    }

    private static final class Run {
        final int exit;
        final String out;
        final String err;

        Run(final int exit, final String out, final String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }
}
