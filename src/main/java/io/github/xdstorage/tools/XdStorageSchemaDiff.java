package io.github.xdstorage.tools;

import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.search.XdStorageSearchIndex;
import io.github.xdstorage.serialization.yaml.XdStorageYamlObjectsReader;
import io.github.xdstorage.structure.XdStorageClassIndexStructure;
import io.github.xdstorage.structure.XdStorageClassIndexStructureId;
import io.github.xdstorage.structure.XdStorageClassStructure;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectFieldInfo;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The schema-diff CLI: compares the structure two storage folders store for their classes
 * — or one folder's against the live Java classes — and reports the per-class field and
 * index differences (docs/tasks/2.2.0/17).
 *
 * <p>Usage:
 * <pre>
 * java -cp &lt;xdstorage.jar&gt; io.github.xdstorage.tools.XdStorageSchemaDiff &lt;folderA&gt; &lt;folderB&gt; [--json]
 * java -cp &lt;xdstorage.jar&gt; io.github.xdstorage.tools.XdStorageSchemaDiff &lt;folderA&gt;
 *      --against-classes &lt;ClassFQCN&gt;... [--json]
 * </pre>
 *
 * <p><b>What a folder stores.</b> The engine keeps no schema apart from its records: a
 * class's structure is written only when the application ran
 * {@code executeStructureUpdate(Class)} for it, and then as one record of
 * {@code XdStorageClassStructure} in the file {@code structure/XdStorageClassStructure.yaml},
 * keyed by the class name. This tool reads that file. A side that stores no structure at
 * all has nothing to compare: the tool says so (a note on stderr, "nothing to compare" on
 * stdout) and exits 0 rather than listing classes as added or removed; a class whose data
 * is in the folder but which has no record is named in the note and not compared. YAML
 * folders only.
 *
 * <p>Output: one line per change, {@code <class> <kind> <action> <details>}, where kind is
 * CLASS / FIELD / IDX / POLICY and action ADDED / REMOVED / CHANGED; {@code --json} prints
 * the same as a JSON array.
 *
 * <p>Exit codes: 0 = no differences; 1 = differences found; 2 = usage error; 3 = a folder
 * cannot be read (missing, unreadable, XML-format).
 */
public final class XdStorageSchemaDiff {

    /** Exit code when the two sides differ. */
    public static final int DIFFERENCES_FOUND = 1;
    /** Exit code of a usage error. */
    public static final int USAGE = 2;
    /** Exit code when a folder cannot be read. */
    public static final int CANNOT_READ = 3;

    /** The one file a folder keeps its class structures in. */
    public static final String STRUCTURE_FILE = "structure/XdStorageClassStructure.yaml";

    private XdStorageSchemaDiff() {
    }

    /** What a folder stores about its classes. */
    public static final class Stored {
        /** The stored structures by class name. */
        public final Map<String, XdStorageClassStructure> structures = new TreeMap<>();
        /** Classes whose data is in the folder and that have no stored structure. */
        public final List<String> classesWithoutStructure = new ArrayList<>();
        /** Whether the structure file exists at all. */
        public boolean structureFile;
    }

    /** One reported change. */
    public static final class Diff {
        final String className;
        final String kind;
        final String action;
        final String details;

        Diff(final String className, final String kind, final String action, final String details) {
            this.className = className;
            this.kind = kind;
            this.action = action;
            this.details = details;
        }

        @Override
        public String toString() {
            return className + " " + kind + " " + action + (details != null ? " " + details : "");
        }

        String toJson() {
            return "{\"class\":\"" + escape(className) + "\",\"kind\":\"" + kind + "\",\"action\":\"" + action
                    + "\",\"details\":\"" + (details != null ? escape(details) : "") + "\"}";
        }

        private static String escape(final String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }

    /**
     * CLI entry point.
     *
     * @param args see the class comment
     */
    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The whole command, without {@code System.exit} — for tests and build tools.
     *
     * @param args see the class comment
     * @param out where the report goes
     * @param err where usage and failures go
     * @return the exit code
     */
    public static int run(final String[] args, final PrintStream out, final PrintStream err) {
        final List<String> folders = new ArrayList<>();
        final List<String> classNames = new ArrayList<>();
        boolean json = false;
        boolean againstClasses = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--json":
                    json = true;
                    break;
                case "--against-classes":
                    againstClasses = true;
                    break;
                case "--help":
                    usage(out);
                    return 0;
                default:
                    if (args[i].startsWith("--")) {
                        err.println("schema-diff: unknown option " + args[i]);
                        usage(err);
                        return USAGE;
                    }
                    if (againstClasses) {
                        classNames.add(args[i]);
                    } else {
                        folders.add(args[i]);
                    }
            }
        }
        if (againstClasses) {
            if (folders.size() != 1 || classNames.isEmpty()) {
                usage(err);
                return USAGE;
            }
        } else if (folders.size() != 2) {
            usage(err);
            return USAGE;
        }

        try {
            final Stored a = readFolder(Paths.get(folders.get(0)));
            note(a, folders.get(0), err);
            final Map<String, XdStorageClassStructure> other;
            if (againstClasses) {
                other = buildLiveMap(classNames);
            } else {
                final Stored b = readFolder(Paths.get(folders.get(1)));
                note(b, folders.get(1), err);
                if (!b.structureFile) {
                    other = null;
                } else {
                    other = b.structures;
                }
            }
            // a side that stores no structure at all has nothing to compare: saying so is
            // the report, not a list of classes "added" or "removed"
            if (!a.structureFile || other == null) {
                out.println("nothing to compare: a side stores no class structure (see the notes)");
                return 0;
            }
            final List<Diff> diffs = computeDiffs(a.structures, other);
            if (json) {
                out.println(toJson(diffs));
            } else if (diffs.isEmpty()) {
                out.println("OK: no differences found.");
            } else {
                for (final Diff diff : diffs) {
                    out.println(diff);
                }
            }
            return diffs.isEmpty() ? 0 : DIFFERENCES_FOUND;
        } catch (final IOException | RuntimeException failure) {
            err.println("schema-diff: " + failure.getMessage());
            return CANNOT_READ;
        }
    }

    private static void note(final Stored stored, final String folder, final PrintStream out) {
        if (!stored.structureFile) {
            out.println("note: " + folder + " stores no class structure (the application never ran"
                    + " executeStructureUpdate) — nothing from it takes part in the comparison");
        } else if (!stored.classesWithoutStructure.isEmpty()) {
            out.println("note: " + folder + " has data of classes without a stored structure (not compared): "
                    + stored.classesWithoutStructure);
        }
    }

    /**
     * Reads what a folder stores about its classes.
     *
     * @param folder a storage folder, closed
     * @return the stored structures, the classes without one, and whether the file exists
     * @throws IOException when the folder or the file cannot be read
     */
    public static Stored readFolder(final Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IOException("folder does not exist or is not a directory: " + folder);
        }
        final Stored stored = new Stored();
        final Path file = folder.resolve(STRUCTURE_FILE);
        stored.structureFile = Files.isRegularFile(file);
        if (stored.structureFile) {
            try (InputStream in = Files.newInputStream(file)) {
                for (final XdStorageClassStructure structure : readStructures(in)) {
                    stored.structures.put(structure.getType(), structure);
                }
            }
        }
        // data/ holds the application's classes only — the engine's own records live under
        // structure/ and indexes/
        final Path data = folder.resolve("data");
        if (Files.isDirectory(data)) {
            try (Stream<Path> classes = Files.list(data)) {
                for (final Path dir : (Iterable<Path>) classes.filter(Files::isDirectory)::iterator) {
                    final String simpleName = dir.getFileName().toString();
                    final boolean known = stored.structures.keySet().stream()
                            .anyMatch(name -> name.endsWith("." + simpleName));
                    if (!known) {
                        stored.classesWithoutStructure.add(simpleName);
                    }
                }
            }
        }
        java.util.Collections.sort(stored.classesWithoutStructure);
        return stored;
    }

    /**
     * Parses the structure file with the engine's own reader, leniently.
     *
     * @param in the content of {@value #STRUCTURE_FILE}
     * @return every structure record in it
     * @throws IOException when the content cannot be parsed
     */
    public static List<XdStorageClassStructure> readStructures(final InputStream in) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            final Collection<Object> records = new XdStorageYamlObjectsReader(
                    new XdStorageDefaultSimpleTypeHelper(), false).read(reader);
            final List<XdStorageClassStructure> structures = new ArrayList<>();
            for (final Object record : records) {
                if (record instanceof XdStorageClassStructure) {
                    structures.add((XdStorageClassStructure) record);
                }
            }
            return structures;
        } catch (final IOException failure) {
            throw failure;
        } catch (final Exception failure) {
            throw new IOException(STRUCTURE_FILE + " cannot be parsed: " + failure.getMessage(), failure);
        }
    }

    /**
     * Builds the structures of live classes, the way the engine does before it stores them.
     *
     * @param classNames fully qualified class names
     * @return the structures by class name; a class that cannot be loaded or is not an
     *         entity is left out
     */
    public static Map<String, XdStorageClassStructure> buildLiveMap(final List<String> classNames) {
        final Map<String, XdStorageClassStructure> result = new TreeMap<>();
        for (final String name : classNames) {
            final Class<?> cl;
            try {
                cl = Class.forName(name);
            } catch (final ClassNotFoundException notFound) {
                continue;
            }
            final XdStorageClassInfo info = XdStorageObjectUtils.getClassInfo(cl);
            if (info != null) {
                result.put(name, buildStructureFromClass(cl, info));
            }
        }
        return result;
    }

    static XdStorageClassStructure buildStructureFromClass(final Class<?> cl, final XdStorageClassInfo info) {
        final XdStorageClassStructure structure = new XdStorageClassStructure();
        structure.setType(cl.getName());
        structure.setPolicy(info.getPolicy());
        final Map<String, XdStorageObjectField> fields = info.getFields();
        if (fields != null && !fields.isEmpty()) {
            final Map<String, Class<?>> fieldTypes = new TreeMap<>();
            for (final Map.Entry<String, XdStorageObjectField> e : fields.entrySet()) {
                final Class<?> type = typeOf(e.getValue());
                if (type != null) {
                    fieldTypes.put(e.getKey(), type);
                }
            }
            structure.setFields(fieldTypes.isEmpty() ? null : fieldTypes);
        }
        final Map<String, XdStorageSearchIndex> indexes = info.getIndexes();
        if (indexes != null && !indexes.isEmpty()) {
            final Map<String, XdStorageClassIndexStructure> structures = new TreeMap<>();
            for (final Map.Entry<String, XdStorageSearchIndex> e : indexes.entrySet()) {
                structures.put(e.getValue().getName(), buildIndexStructure(e.getValue(), cl));
            }
            structure.setIndexes(structures);
        }
        return structure;
    }

    private static Class<?> typeOf(final XdStorageObjectField field) {
        final XdStorageObjectFieldInfo info = field.getFieldInfo();
        return info == null ? null : info.getClazz();
    }

    static XdStorageClassIndexStructure buildIndexStructure(final XdStorageSearchIndex index,
                                                            final Class<?> owner) {
        final XdStorageClassIndexStructure structure = new XdStorageClassIndexStructure();
        structure.setId(new XdStorageClassIndexStructureId());
        structure.getId().setName(index.getName());
        structure.getId().setType(owner.getName());
        final Map<String, XdStorageObjectField> accessors = index.getFieldAccesors();
        if (accessors != null) {
            for (final Map.Entry<String, XdStorageObjectField> e : accessors.entrySet()) {
                final Class<?> type = typeOf(e.getValue());
                if (type != null) {
                    structure.addField(e.getKey(), type);
                }
            }
        }
        final XdStorageObjectField primary = index.getPrimaryField();
        final Class<?> primaryType = primary == null ? null : typeOf(primary);
        if (primaryType != null && (structure.getFields() == null || !structure.getFields().containsKey(
                primary.getName()))) {
            structure.addField(primary.getName(), primaryType);
        }
        return structure;
    }

    /**
     * Compares two structure maps. Everything in {@code b} that is not in {@code a} is
     * ADDED, the reverse is REMOVED.
     *
     * @param a the old structures
     * @param b the new structures
     * @return the differences, in class order
     */
    public static List<Diff> computeDiffs(final Map<String, XdStorageClassStructure> a,
                                           final Map<String, XdStorageClassStructure> b) {
        final List<Diff> diffs = new ArrayList<>();
        for (final Map.Entry<String, XdStorageClassStructure> entry : b.entrySet()) {
            final XdStorageClassStructure old = a.get(entry.getKey());
            if (old == null) {
                diffs.add(new Diff(entry.getKey(), "CLASS", "ADDED", null));
            } else {
                diffClass(entry.getKey(), old, entry.getValue(), diffs);
            }
        }
        for (final String name : a.keySet()) {
            if (!b.containsKey(name)) {
                diffs.add(new Diff(name, "CLASS", "REMOVED", null));
            }
        }
        return diffs;
    }

    static void diffClass(final String className, final XdStorageClassStructure oldStruct,
                          final XdStorageClassStructure newStruct, final List<Diff> diffs) {
        if (oldStruct.getPolicy() != newStruct.getPolicy()) {
            diffs.add(new Diff(className, "POLICY", "CHANGED", oldStruct.getPolicy() + "->" + newStruct.getPolicy()));
        }
        final Map<String, Class<?>> oldFields = oldStruct.getFields();
        final Map<String, Class<?>> newFields = newStruct.getFields();
        if (newFields != null) {
            for (final Map.Entry<String, Class<?>> e : newFields.entrySet()) {
                if (oldFields == null || !oldFields.containsKey(e.getKey())) {
                    diffs.add(new Diff(className, "FIELD", "ADDED", e.getKey() + " " + e.getValue().getSimpleName()));
                } else if (!oldFields.get(e.getKey()).equals(e.getValue())) {
                    diffs.add(new Diff(className, "FIELD", "CHANGED", e.getKey() + " "
                            + oldFields.get(e.getKey()).getSimpleName() + "->" + e.getValue().getSimpleName()));
                }
            }
        }
        if (oldFields != null) {
            for (final Map.Entry<String, Class<?>> e : oldFields.entrySet()) {
                if (newFields == null || !newFields.containsKey(e.getKey())) {
                    diffs.add(new Diff(className, "FIELD", "REMOVED", e.getKey() + " " + e.getValue().getSimpleName()));
                }
            }
        }

        final Map<String, XdStorageClassIndexStructure> oldIndexes = oldStruct.getIndexes();
        final Map<String, XdStorageClassIndexStructure> newIndexes = newStruct.getIndexes();
        if (newIndexes != null) {
            for (final Map.Entry<String, XdStorageClassIndexStructure> e : newIndexes.entrySet()) {
                final XdStorageClassIndexStructure oldIndex = oldIndexes == null ? null : oldIndexes.get(e.getKey());
                if (oldIndex == null) {
                    diffs.add(new Diff(className, "IDX", "ADDED", e.getKey()));
                } else {
                    diffFields(className, "IDX", e.getKey() + "/", oldIndex.getFields(), e.getValue().getFields(),
                            diffs);
                }
            }
        }
        if (oldIndexes != null) {
            for (final String name : oldIndexes.keySet()) {
                if (newIndexes == null || !newIndexes.containsKey(name)) {
                    diffs.add(new Diff(className, "IDX", "REMOVED", name));
                }
            }
        }
    }

    /** The same field comparison for the class's fields and for one index's fields. */
    private static void diffFields(final String className, final String kind, final String prefix,
                                   final Map<String, Class<?>> oldFields, final Map<String, Class<?>> newFields,
                                   final List<Diff> diffs) {
        if (newFields != null) {
            for (final Map.Entry<String, Class<?>> e : newFields.entrySet()) {
                if (oldFields == null || !oldFields.containsKey(e.getKey())) {
                    diffs.add(new Diff(className, kind, "ADDED", prefix + e.getKey() + " " + e.getValue()
                            .getSimpleName()));
                } else if (!oldFields.get(e.getKey()).equals(e.getValue())) {
                    diffs.add(new Diff(className, kind, "CHANGED", prefix + e.getKey() + " " + oldFields.get(e.getKey())
                            .getSimpleName() + "->" + e.getValue().getSimpleName()));
                }
            }
        }
        if (oldFields != null) {
            for (final Map.Entry<String, Class<?>> e : oldFields.entrySet()) {
                if (newFields == null || !newFields.containsKey(e.getKey())) {
                    diffs.add(new Diff(className, kind, "REMOVED", prefix + e.getKey() + " " + e.getValue()
                            .getSimpleName()));
                }
            }
        }
    }

    /** The differences as one JSON array. */
    public static String toJson(final List<Diff> diffs) {
        final StringBuilder json = new StringBuilder("[");
        for (final Diff diff : diffs) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(diff.toJson());
        }
        return json.append(']').toString();
    }

    private static void usage(final PrintStream out) {
        out.println("Usage: java -cp <xdstorage.jar> io.github.xdstorage.tools.XdStorageSchemaDiff <folderA>"
                + " <folderB> [--json]");
        out.println("       java -cp <xdstorage.jar> io.github.xdstorage.tools.XdStorageSchemaDiff <folderA>"
                + " --against-classes <ClassFQCN>... [--json]");
        out.println("Exit codes: 0 no differences; 1 differences found; 2 usage; 3 a folder cannot be read");
    }
}
