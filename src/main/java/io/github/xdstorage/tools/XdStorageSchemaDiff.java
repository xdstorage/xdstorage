package io.github.xdstorage.tools;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.search.XdStorageSearchIndex;
import io.github.xdstorage.structure.XdStorageClassIndexStructure;
import io.github.xdstorage.structure.XdStorageClassIndexStructureId;
import io.github.xdstorage.structure.XdStorageClassStructure;
import io.github.xdstorage.utils.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Schema diff CLI: compares two storage folders' structure metadata (or one folder
 * against the live Java classes) and reports per-class field/index diffs plus
 * stored format generations.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff <folderA> <folderB>}</li>
 *   <li>{@code java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff <folderA> --against-classes <ClassFQCNs...>}</li>
 * </ul>
 * <p>Output: one line per change — {@code <class> <kind> <action> <details>}
 * where kind is CLASS/FIELD/IDX and action is ADDED/REMOVED/CHANGED.
 * {@code --json} switches to JSON array output for pipeline consumption.
 * <p>
 * Exit codes: 0 = no differences; 1 = differences found; 2 = usage error;
 * 3 = I/O error (folder missing/unreadable).
 *
 * @see <a href="docs/tasks/2.2.0/17-migration-schema-diff.md">task 17</a>
 */
public class XdStorageSchemaDiff {

    private XdStorageSchemaDiff() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }

        final Path folderA = Paths.get(args[0]);
        final boolean toJson = Arrays.asList(args).contains("--json");
        final int argIdx = "--against-classes".equals(args[1]) ? 2 : 1;
        final boolean againstClasses = "--against-classes".equals(args[1]);

        if (againstClasses) {
            if (args.length < 3) {
                System.err.println("Error: --against-classes requires at least one class name");
                System.err.println("Usage: java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff <folder> --against-classes <ClassFQCNs...> [--json]");
                System.exit(2);
            }
            final List<String> classNames = Arrays.asList(args).subList(2, args.length);
            doCompareAgainstLiveClasses(folderA, classNames, toJson);
        } else {
            final Path folderB = Paths.get(args[argIdx]);
            doCompareTwoFolders(folderA, folderB, toJson);
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff <folderA> <folderB> [--json]");
        System.err.println("  java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff <folderA> --against-classes <ClassFQCNs...> [--json]");
    }

    private static void doCompareTwoFolders(Path folderA, Path folderB, boolean toJson) {
        try {
            final Map<String, XdStorageClassStructure> a = readFolder(folderA);
            final Map<String, XdStorageClassStructure> b = readFolder(folderB);
            final List<Diff> diffs = computeDiffs(a, b);
            if (toJson) {
                printJsonDiffs(diffs);
            } else {
                printTextDiffs(diffs);
            }
            System.exit(diffs.isEmpty() ? 0 : 1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(3);
        }
    }

    private static void doCompareAgainstLiveClasses(Path folder, List<String> classNames, boolean toJson) {
        try {
            final Map<String, XdStorageClassStructure> stored = readFolder(folder);
            final Map<String, XdStorageClassStructure> live = buildLiveMap(classNames);

            for (String name : stored.keySet()) {
                if (!live.containsKey(name)) {
                    final List<Diff> diffs = new ArrayList<>();
                    diffs.add(new Diff(name, "CLASS", "REMOVED", null));
                    if (toJson) printJsonDiffs(diffs);
                    else System.out.println("CLASS REMOVED " + name);
                    System.exit(1);
                }
            }

            final List<Diff> diffs = computeDiffs(stored, live);
            if (toJson) {
                printJsonDiffs(diffs);
            } else {
                printTextDiffs(diffs);
            }
            System.exit(diffs.isEmpty() ? 0 : 1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(3);
        }
    }

    /**
     * Read structure metadata from a storage folder.
     */
    static Map<String, XdStorageClassStructure> readFolder(Path folder) throws IOException {
        final Map<String, XdStorageClassStructure> result = new LinkedHashMap<>();

        final File metaFile = folder.resolve(".xdstorage.meta").toFile();
        int formatVersion = 1;
        if (metaFile.isFile()) {
            final java.util.Properties meta = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(metaFile)) {
                meta.load(in);
            }
            final String raw = meta.getProperty("formatVersion");
            if (raw != null) {
                formatVersion = Integer.parseInt(raw.trim());
            }
        }

        final File structureDir = folder.resolve("structure").toFile();
        if (!structureDir.exists() || !structureDir.isDirectory()) {
            return result;
        }

        for (File f : structureDir.listFiles()) {
            if (!f.isFile()) continue;
            final String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            final String className = name.substring(0, dot);

            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                final XdStorageClassStructure structure = loadStructure(in);
                if (structure != null) {
                    result.put(className, structure);
                }
            }
        }
        return result;
    }

    /**
     * Load a structure record from a YAML stream using the engine's reader.
     */
    static XdStorageClassStructure loadStructure(java.io.InputStream in) {
        try {
            // XdStorageClassStructure is a POJO with @XdStorageObjectId on `type`.
            // XdStorageYamlObjectsReader reads it via class: <FQCN> resolution.
            final Reader reader = new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8);
            // Cast the SimpleTypeHelper parameter explicitly to avoid import ambiguity
            final io.github.xdstorage.serialization.yaml.XdStorageYamlObjectsReader yamlReader =
                new io.github.xdstorage.serialization.yaml.XdStorageYamlObjectsReader(
                    (io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper)
                        java.lang.Class.forName("io.github.xdstorage.utils.XdStorageObjectUtils").newInstance(), false);
            @SuppressWarnings("unchecked")
            final java.util.List<XdStorageClassStructure> objects =
                (java.util.List<XdStorageClassStructure>) (java.util.List<?>) yamlReader.read(reader);
            if (objects != null && !objects.isEmpty()) {
                return objects.get(0);
            }
        } catch (Exception e) {
            // Corrupted or not-yaml structure file — skip silently
        }
        return null;
    }

    /**
     * Build a live class structure map from FQCN class names.
     */
    static Map<String, XdStorageClassStructure> buildLiveMap(List<String> classNames) {
        final Map<String, XdStorageClassStructure> result = new LinkedHashMap<>();

        for (String fqcn : classNames) {
            try {
                final Class<?> cl = Class.forName(fqcn);
                final XdStorageClassInfo info = XdStorageObjectUtils.getClassInfo(cl);
                if (info == null) {
                    System.err.println("WARN: " + fqcn + " not registered as stored object (no @XdStorageObjectId)");
                    continue;
                }
                result.put(fqcn, buildStructureFromClass(cl, info));
            } catch (ClassNotFoundException e) {
                System.err.println("WARN: class not found: " + fqcn);
            }
        }
        return result;
    }

    /**
     * Build a XdStorageClassStructure snapshot from a live class — same logic
     * as the engine's XdStorageStructureManager.buildClassStructureSnapshot().
     */
    static XdStorageClassStructure buildStructureFromClass(Class<?> cl, XdStorageClassInfo info) {
        final XdStorageClassStructure structure = new XdStorageClassStructure();
        structure.setType(cl.getName());
        structure.setPolicy(info.getPolicy());

        // Fields: Map<String, XdStorageObjectField> -> Map<String, Class<?>>
        final Map<String, XdStorageObjectField> fields = info.getFields();
        if (fields != null && !fields.isEmpty()) {
            final Map<String, Class<?>> fieldTypes = new LinkedHashMap<>();
            for (Map.Entry<String, XdStorageObjectField> e : fields.entrySet()) {
                final XdStorageObjectFieldInfo fi = e.getValue().getFieldInfo();
                if (fi != null) {
                    final Class<?> t = fi.getClazz();
                    if (t != null) {
                        fieldTypes.put(e.getKey(), t);
                    }
                }
            }
            structure.setFields(fieldTypes.isEmpty() ? null : fieldTypes);
        }

        // Search indexes: Map<String, XdStorageSearchIndex> -> Map<String, XdStorageClassIndexStructure>
        final Map<String, XdStorageSearchIndex> indexes = info.getIndexes();
        if (indexes != null && !indexes.isEmpty()) {
            final Map<String, XdStorageClassIndexStructure> idxStructs = new LinkedHashMap<>();
            for (Map.Entry<String, XdStorageSearchIndex> e : indexes.entrySet()) {
                final XdStorageSearchIndex si = e.getValue();
                final XdStorageClassIndexStructure idx = buildIndexStructure(si, cl);
                idxStructs.put(si.getName(), idx);
            }
            structure.setIndexes(idxStructs);
        }

        return structure;
    }

    /**
     * Build a XdStorageClassIndexStructure from a declared search index.
     */
    static XdStorageClassIndexStructure buildIndexStructure(XdStorageSearchIndex index, Class<?> owner) {
        final XdStorageClassIndexStructure idx = new XdStorageClassIndexStructure();
        idx.setId(new XdStorageClassIndexStructureId());
        idx.getId().setName(index.getName());
        idx.getId().setType(owner.getName());

        // Indexed fields (primary + accessor fields)
        final Map<String, XdStorageObjectField> fieldAccesors = index.getFieldAccesors();
        if (fieldAccesors != null) {
            for (Map.Entry<String, XdStorageObjectField> e : fieldAccesors.entrySet()) {
                final XdStorageObjectFieldInfo fi = e.getValue().getFieldInfo();
                if (fi != null) {
                    final Class<?> t = fi.getClazz();
                    if (t != null) {
                        idx.addField(e.getKey(), t);
                    }
                }
            }
        }

        // Primary field
        final XdStorageObjectField primary = index.getPrimaryField();
        if (primary != null) {
            final XdStorageObjectFieldInfo fi = primary.getFieldInfo();
            if (fi != null) {
                final Class<?> t = fi.getClazz();
                if (t != null && (idx.getFields() == null || !idx.getFields().containsKey(primary.getName()))) {
                    idx.addField(primary.getName(), t);
                }
            }
        }

        return idx;
    }

    static List<Diff> computeDiffs(Map<String, XdStorageClassStructure> a, Map<String, XdStorageClassStructure> b) {
        final List<Diff> diffs = new ArrayList<>();

        for (String name : b.keySet()) {
            if (!a.containsKey(name)) {
                diffs.add(new Diff(name, "CLASS", "ADDED", null));
                continue;
            }
            diffClass(name, a.get(name), b.get(name), diffs);
        }
        for (String name : a.keySet()) {
            if (!b.containsKey(name)) {
                diffs.add(new Diff(name, "CLASS", "REMOVED", null));
            }
        }
        return diffs;
    }

    static void diffClass(String className, XdStorageClassStructure oldStruct,
                           XdStorageClassStructure newStruct, List<Diff> diffs) {
        final Map<String, Class<?>> oldFields = oldStruct.getFields();
        final Map<String, Class<?>> newFields = newStruct.getFields();

        if (newFields != null) {
            for (Map.Entry<String, Class<?>> e : newFields.entrySet()) {
                if (oldFields == null || !oldFields.containsKey(e.getKey())) {
                    diffs.add(new Diff(className, "FIELD", "ADDED", e.getKey() + " " + e.getValue().getSimpleName()));
                } else if (!oldFields.get(e.getKey()).equals(e.getValue())) {
                    diffs.add(new Diff(className, "FIELD", "CHANGED",
                            e.getKey() + " " + oldFields.get(e.getKey()).getSimpleName() + "->" + e.getValue().getSimpleName()));
                }
            }
        }
        if (oldFields != null) {
            for (String name : oldFields.keySet()) {
                if (newFields == null || !newFields.containsKey(name)) {
                    diffs.add(new Diff(className, "FIELD", "REMOVED", name + " " + oldFields.get(name).getSimpleName()));
                }
            }
        }

        final Map<String, XdStorageClassIndexStructure> oldIndexes = oldStruct.getIndexes();
        final Map<String, XdStorageClassIndexStructure> newIndexes = newStruct.getIndexes();

        final Map<String, XdStorageClassIndexStructure> oldReal = (oldIndexes != null)
                ? new LinkedHashMap<>(oldIndexes)
                : null;
        final Map<String, XdStorageClassIndexStructure> newReal = (newIndexes != null)
                ? new LinkedHashMap<>(newIndexes)
                : null;

        // ADDED indexes
        if (newReal != null) {
            for (String name : newReal.keySet()) {
                if (oldReal == null || !oldReal.containsKey(name)) {
                    diffs.add(new Diff(className, "IDX", "ADDED", name));
                }
            }
        }
        // REMOVED indexes
        if (oldReal != null) {
            for (String name : oldReal.keySet()) {
                if (newReal == null || !newReal.containsKey(name)) {
                    diffs.add(new Diff(className, "IDX", "REMOVED", name));
                }
            }
        }
        // CHANGED indexes (same name, different fields)
        if (oldReal != null && newReal != null) {
            for (Map.Entry<String, XdStorageClassIndexStructure> e : newReal.entrySet()) {
                final String name = e.getKey();
                final XdStorageClassIndexStructure newIdx = e.getValue();
                final XdStorageClassIndexStructure oldIdx = oldReal.get(name);
                if (oldIdx == null) continue;

                final Map<String, Class<?>> oFields = oldIdx.getFields();
                final Map<String, Class<?>> nFields = newIdx.getFields();

                if (nFields != null) {
                    for (Map.Entry<String, Class<?>> f : nFields.entrySet()) {
                        if (oFields == null || !oFields.containsKey(f.getKey())) {
                            diffs.add(new Diff(className, "IDX", "ADDED", name + "/" + f.getKey() + " " + f.getValue().getSimpleName()));
                        } else if (!oFields.get(f.getKey()).equals(f.getValue())) {
                            diffs.add(new Diff(className, "IDX", "CHANGED", name + "/" + f.getKey() + " "
                                    + oFields.get(f.getKey()).getSimpleName() + "->" + f.getValue().getSimpleName()));
                        }
                    }
                }
                if (oFields != null) {
                    for (String fn : oFields.keySet()) {
                        if (nFields == null || !nFields.containsKey(fn)) {
                            diffs.add(new Diff(className, "IDX", "REMOVED", name + "/" + fn + " " + oFields.get(fn).getSimpleName()));
                        }
                    }
                }
            }
        }
    }

    static void printTextDiffs(List<Diff> diffs) {
        if (diffs.isEmpty()) {
            System.out.println("OK: no differences found.");
            return;
        }
        for (Diff d : diffs) {
            System.out.println(d.toString());
        }
    }

    static void printJsonDiffs(List<Diff> diffs) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Diff d : diffs) {
            if (!first) sb.append(",");
            first = false;
            sb.append(d.toJson());
        }
        sb.append("]");
        System.out.println(sb.toString());
    }

    static class Diff {
        final String className, kind, action, details;

        Diff(String className, String kind, String action, String details) {
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
            return "{" +
                    "\"class\":\"" + esc(className) + "\"," +
                    "\"kind\":\"" + kind + "\"," +
                    "\"action\":\"" + action + "\"," +
                    "\"details\":\"" + (details != null ? esc(details) : "") + "\"" +
                    "}";
        }

        private String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }
}
