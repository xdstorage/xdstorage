package io.github.xdstorage.tools;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.entities.XdStorageMigrateNote;
import io.github.xdstorage.entities.XdStorageMigrateOrder;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MIGRATE (docs/tasks/2.2.0/17): the {@code builtin:v1-to-v2} script of the migrate CLI.
 *
 * <p>No 1.x folder exists in the repository; the tests make the two shapes a 1.x folder
 * has, from a folder the current engine writes: the YAML shape — marker removed, the EOF
 * sentinel taken off every file — and the {@code JSON} shape — an XML folder whose files
 * carry the {@code .json} name 1.x gave them, marker removed. The criterion the task sets
 * is the criterion the tool is held to: the result opens with the DEFAULT configuration and
 * answers with the same records.</p>
 */
@DisplayName("MIGRATE (2.2.0/17): сценарий builtin:v1-to-v2")
class XdStorageMigrateTest {

    @TempDir
    Path tempDir;

    private Path folder;
    private String storageName;

    @BeforeEach
    void folder() throws IOException {
        folder = Files.createDirectories(tempDir.resolve("db"));
        storageName = "migrate-" + System.nanoTime();
    }

    // === the script ===

    @Test
    @DisplayName("1. Каталог 1.x (YAML без сентинелов): после migrate открывается стандартной конфигурацией")
    void migratesALegacyYamlFolder() throws Exception {
        writeYaml(folder);
        makeLegacyYaml(folder);
        assertEquals(0, countSentinels(folder), "the fixture has no sentinel to begin with");

        final Run run = run(folder.toString());

        assertEquals(0, run.exit, run.out + run.err);
        assertEquals(yamlFiles(folder).size(), countSentinels(folder), "every YAML file ends with the sentinel");
        assertRecords(folder, FilesFormat.YAML);
        assertTrue(run.out.contains("appended the EOF sentinel to " + yamlFiles(folder).size() + " file(s)"), run.out);
        assertFalse(run.out.contains("verify -> CORRUPT"), run.out);
    }

    @Test
    @DisplayName("2. Каталог 1.x JSON (XML в файлах .json во вложенных каталогах): переименованы все")
    void migratesAJsonFolderAtAnyDepth() throws Exception {
        writeXml(folder);
        makeLegacyJson(folder);
        assertEquals(0, yamlFiles(folder).size(), "an XML folder has no YAML file to complete");
        final int jsonFiles = jsonFiles(folder).size();
        assertTrue(jsonFiles >= 4, "the files sit in data/<Class>/ and indexes/ sub-directories");

        final Run run = run(folder.toString());

        assertEquals(0, run.exit, run.out + run.err);
        assertTrue(jsonFiles(folder).isEmpty(), "no .json file is left");
        assertRecords(folder, FilesFormat.XML);
        assertTrue(run.out.contains("renamed " + jsonFiles + " file(s) .json -> .xml"), run.out);
    }

    @Test
    @DisplayName("3. --dry-run печатает план и не меняет ни байта; выход 1, когда есть работа")
    void dryRunChangesNothing() throws Exception {
        writeYaml(folder);
        makeLegacyYaml(folder);
        final Map<String, byte[]> before = snapshot(folder);

        final Run run = run(folder.toString(), "--dry-run");

        assertEquals(XdStorageMigrate.WORK_FOUND, run.exit, run.out + run.err);
        assertArrayEquals(toArray(before), toArray(snapshot(folder)), "the folder is byte for byte the same");
        assertTrue(run.out.contains("YAML files without the EOF sentinel: " + yamlFiles(folder).size()), run.out);
        assertTrue(run.out.contains("nothing was changed"), run.out);
    }

    @Test
    @DisplayName("4. --dry-run на актуальном каталоге: работы нет, выход 0")
    void dryRunWithNothingToDo() throws Exception {
        writeYaml(folder);

        final Run run = run(folder.toString(), "--dry-run");

        assertEquals(0, run.exit, run.out + run.err);
        assertTrue(run.out.contains("nothing to migrate"), run.out);
    }

    @Test
    @DisplayName("5. Повторный запуск: ничего не делает и не меняет ни байта")
    void secondRunChangesNothing() throws Exception {
        writeYaml(folder);
        makeLegacyYaml(folder);
        assertEquals(0, run(folder.toString()).exit);
        final Map<String, byte[]> after = snapshot(folder);

        final Run again = run(folder.toString());

        assertEquals(0, again.exit, again.out + again.err);
        assertTrue(again.out.contains("nothing to migrate"), again.out);
        assertArrayEquals(toArray(after), toArray(snapshot(folder)));
    }

    @Test
    @DisplayName("6. .json и .xml с одним именем: отказ до переименования, выход 3")
    void refusesWhenJsonAndXmlCollide() throws Exception {
        writeXml(folder);
        makeLegacyJson(folder);
        final Path first = jsonFiles(folder).get(0);
        Files.write(first.resolveSibling(first.getFileName().toString().replace(".json", ".xml")), "<x/>".getBytes(
                StandardCharsets.UTF_8));
        final int jsonBefore = jsonFiles(folder).size();

        final Run run = run(folder.toString());

        assertEquals(XdStorageMigrate.FAILED, run.exit, run.out + run.err);
        assertEquals(jsonBefore, jsonFiles(folder).size(), "nothing was renamed");
        assertTrue(run.err.contains("nothing was renamed"), run.err);
    }

    @Test
    @DisplayName("7. Каталог открыт другим процессом: отказ, выход 3")
    void refusesAFolderInUse() throws Exception {
        writeYaml(folder);
        makeLegacyYaml(folder);
        final IXdFileStorage holder = XdStorageProvider.newOrGetFileStorage(storageName + "-holder",
                folder.toAbsolutePath().toString(), 64);
        try {
            final Run run = run(folder.toString());
            assertEquals(XdStorageMigrate.FAILED, run.exit, run.out + run.err);
        } finally {
            holder.shutdown();
        }
    }

    @Test
    @DisplayName("8. Ошибки использования: выход 2")
    void usageErrors() {
        assertEquals(XdStorageMigrate.USAGE, run().exit);
        assertEquals(XdStorageMigrate.USAGE, run(folder.toString(), "--no-such-option").exit);
        assertEquals(XdStorageMigrate.USAGE, run(folder.toString(), "--script").exit);
        assertEquals(XdStorageMigrate.USAGE, run(folder.toString(), "--script", "builtin:nothing").exit);
        assertEquals(XdStorageMigrate.USAGE, run(folder.toString(), folder.toString()).exit);
    }

    @Test
    @DisplayName("9. Каталога нет: выход 3")
    void missingFolder() {
        assertEquals(XdStorageMigrate.FAILED, run(tempDir.resolve("no-such-folder").toString()).exit);
    }

    // === fixtures: a current folder, and the two 1.x shapes made from it ===

    private void writeYaml(final Path folder) throws Exception {
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(storageName,
                folder.toAbsolutePath().toString(), 64);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            for (int i = 1; i <= 3; i++) {
                storage.save(new XdStorageMigrateOrder("o-" + i, "order-" + i, 100L * i), tx);
                storage.save(new XdStorageMigrateNote("n-" + i, "note-" + i), tx);
            }
            storage.commitTransaction(tx);
        } finally {
            storage.shutdown();
        }
    }

    private void writeXml(final Path folder) throws Exception {
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(storageName,
                folder.toAbsolutePath().toString(), 64, FilesFormat.XML, xmlDefaults());
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            for (int i = 1; i <= 3; i++) {
                storage.save(new XdStorageMigrateOrder("o-" + i, "order-" + i, 100L * i), tx);
                storage.save(new XdStorageMigrateNote("n-" + i, "note-" + i), tx);
            }
            storage.commitTransaction(tx);
        } finally {
            storage.shutdown();
        }
    }

    /** No marker, no sentinel on any file: what a 1.x YAML folder looks like to the current engine. */
    private static void makeLegacyYaml(final Path folder) throws IOException {
        Files.delete(folder.resolve(".xdstorage.meta"));
        for (final Path file : yamlFiles(folder)) {
            final List<String> kept = new ArrayList<>();
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.startsWith("# xdstorage-eof") && !line.startsWith("# xdstorage-meta ")) {
                    kept.add(line);
                }
            }
            Files.write(file, (String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** No marker, every file renamed to the name 1.x's JSON constant gave XML content. */
    private static void makeLegacyJson(final Path folder) throws IOException {
        Files.delete(folder.resolve(".xdstorage.meta"));
        for (final Path file : xmlFiles(folder)) {
            Files.move(file, file.resolveSibling(file.getFileName().toString().replace(".xml", ".json")));
        }
    }

    // === what the tests look at ===

    /** Every record is back, under the default (strict) configuration. */
    private void assertRecords(final Path folder, final FilesFormat format) throws Exception {
        final XdStorageVerifyReport report = XdStorageProvider.verify(folder);
        assertFalse(report.verdict == XdStorageVerifyReport.Verdict.CORRUPT, report.toString());
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(storageName + "-after",
                folder.toAbsolutePath().toString(), 64, format,
                format == FilesFormat.XML ? xmlDefaults() : XdStorageConfig.defaults());
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            final List<XdStorageMigrateOrder> orders = new ArrayList<>(storage.load(XdStorageMigrateOrder.class,
                    everything(), tx));
            final List<XdStorageMigrateNote> notes = new ArrayList<>(storage.load(XdStorageMigrateNote.class,
                    everything(), tx));
            storage.commitTransaction(tx);
            orders.sort((one, another) -> one.getId().compareTo(another.getId()));
            notes.sort((one, another) -> one.getId().compareTo(another.getId()));
            assertEquals(3, orders.size());
            assertEquals(3, notes.size());
            for (int i = 1; i <= 3; i++) {
                assertEquals("o-" + i, orders.get(i - 1).getId());
                assertEquals("order-" + i, orders.get(i - 1).getName());
                assertEquals(100L * i, orders.get(i - 1).getAmount());
                assertEquals("n-" + i, notes.get(i - 1).getId());
                assertEquals("note-" + i, notes.get(i - 1).getText());
            }
        } finally {
            storage.shutdown();
        }
    }

    /** The defaults for an XML folder: the XML format does not journal. */
    private static XdStorageConfig xmlDefaults() {
        return XdStorageConfig.builder().journalWrites(false).build();
    }

    private static <T> IXdStoragePredicate<T> everything() {
        return new IXdStoragePredicate<T>() {
            @Override
            public boolean passed(final T candidate) {
                return true;
            }
        };
    }

    private static Run run(final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit = XdStorageMigrate.run(args, utf8(out), utf8(err));
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

    // === the folder, as the tests read it ===

    private static List<Path> yamlFiles(final Path folder) throws IOException {
        return files(folder, ".yaml");
    }

    private static List<Path> xmlFiles(final Path folder) throws IOException {
        return files(folder, ".xml");
    }

    private static List<Path> jsonFiles(final Path folder) throws IOException {
        return files(folder, ".json");
    }

    private static List<Path> files(final Path folder, final String extension) throws IOException {
        final List<Path> found = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(extension))
                    .forEach(found::add);
        }
        java.util.Collections.sort(found);
        return found;
    }

    /** How many YAML files end with the sentinel line. */
    private static int countSentinels(final Path folder) throws IOException {
        int count = 0;
        for (final Path file : yamlFiles(folder)) {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (!lines.get(i).trim().isEmpty()) {
                    if (lines.get(i).trim().equals("# xdstorage-eof")) {
                        count++;
                    }
                    break;
                }
            }
        }
        return count;
    }

    /** Every file with its content, by relative path, in a stable order. */
    private static Map<String, byte[]> snapshot(final Path folder) throws IOException {
        final Map<String, byte[]> snapshot = new TreeMap<>();
        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                snapshot.put(folder.relativize(file).toString(), Files.readAllBytes(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return new LinkedHashMap<>(snapshot);
    }

    private static byte[][] toArray(final Map<String, byte[]> snapshot) {
        final List<byte[]> contents = new ArrayList<>();
        for (final Map.Entry<String, byte[]> entry : snapshot.entrySet()) {
            contents.add(entry.getKey().getBytes(StandardCharsets.UTF_8));
            contents.add(entry.getValue());
        }
        return contents.toArray(new byte[0][]);
    }
}
