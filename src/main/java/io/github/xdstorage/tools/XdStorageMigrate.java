package io.github.xdstorage.tools;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.serialization.yaml.XdStorageYamlTokenizer;
import io.github.xdstorage.utils.XdStorageFolderLock;
import io.github.xdstorage.utils.XdStorageFormatMeta;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The migration CLI: brings a storage folder written by an earlier line of the library to
 * the form the current one opens with its default configuration (docs/tasks/2.2.0/17).
 *
 * <p>Usage:
 * <pre>
 * java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageMigrate &lt;folder&gt; --dry-run
 * java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageMigrate &lt;folder&gt; [--script builtin:v1-to-v2]
 * </pre>
 *
 * <p>The one script, {@code builtin:v1-to-v2}, does two things to the folder's files and
 * then proves the result:
 * <ol>
 *   <li>renames every {@code *.json} file — at any depth — to {@code *.xml}: the 1.x
 *       {@code JSON} constant wrote XML content under that name (EMBEDDING § Migrating a
 *       1.x folder);</li>
 *   <li>appends the end-of-file sentinel ({@code # xdstorage-eof}) to every YAML file that
 *       lacks it. Every file the engine writes ends with that line; a 2.x engine refuses a
 *       file without it as truncated unless it is opened with
 *       {@code acceptMissingEofSentinel(true)}. The step takes each file as complete — the
 *       same reading the lenient open gives it — so it cannot tell a truncated 1.x file from
 *       a whole one: keep a copy of the folder until the result is verified;</li>
 *   <li>opens the folder with the default configuration — for an XML folder, without
 *       journaling, since that format does not journal — (which also runs the 2.0.0 layout
 *       migration when the marker says {@code formatVersion=2}), closes it, and runs
 *       {@code XdStorageProvider.verify}.</li>
 * </ol>
 *
 * <p>No entity class is needed on the classpath: the script works on the files. It does not
 * transform record content and does not run structure updates — for those, the application's
 * own {@code executeStructureUpdate} is the tool. {@code --dry-run} prints the plan and
 * leaves the folder byte for byte.
 *
 * <p>Exit codes: 0 = migrated, or nothing to migrate (also for {@code --dry-run} with
 * nothing to do); 1 = {@code --dry-run} found work; 2 = usage error; 3 = the folder is in
 * use, a step failed, or the result does not verify.
 */
public final class XdStorageMigrate {

    /** The only script there is. */
    public static final String BUILTIN = "builtin:v1-to-v2";

    /** Exit code of a {@code --dry-run} that found work. */
    public static final int WORK_FOUND = 1;
    /** Exit code of a usage error. */
    public static final int USAGE = 2;
    /** Exit code of a failed migration. */
    public static final int FAILED = 3;

    private XdStorageMigrate() {
    }

    /** What the tool found in a folder, and so what it would do to it. */
    public static final class Plan {
        /** The folder. */
        public final Path folder;
        /** The marker's {@code formatVersion}, or {@code null} when there is no marker (a 1.x folder). */
        public final String markerVersion;
        /** The {@code *.json} files to rename, at any depth, sorted. */
        public final List<Path> jsonFiles;
        /** The YAML files that do not end with the sentinel and are not empty, sorted. */
        public final List<Path> filesWithoutSentinel;
        /** Whether the folder holds {@code *.xml} files already. */
        public final boolean hasXml;

        Plan(final Path folder, final String markerVersion, final List<Path> jsonFiles,
             final List<Path> filesWithoutSentinel, final boolean hasXml) {
            this.folder = folder;
            this.markerVersion = markerVersion;
            this.jsonFiles = jsonFiles;
            this.filesWithoutSentinel = filesWithoutSentinel;
            this.hasXml = hasXml;
        }

        /** Whether the script would change anything. */
        public boolean hasWork() {
            return !jsonFiles.isEmpty() || !filesWithoutSentinel.isEmpty();
        }

        /** The format the folder is opened with after the script: XML when it holds XML content. */
        public FilesFormat format() {
            return hasXml || !jsonFiles.isEmpty() ? FilesFormat.XML : FilesFormat.YAML;
        }
    }

    /**
     * CLI entry point.
     *
     * @param args {@code <folder> [--dry-run] [--script builtin:v1-to-v2]}
     */
    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The whole command, without {@code System.exit} — for tests and build tools.
     *
     * @param args   {@code <folder> [--dry-run] [--script builtin:v1-to-v2]}
     * @param out    where the plan and the result go
     * @param err    where usage and failures go
     * @return the exit code
     */
    public static int run(final String[] args, final PrintStream out, final PrintStream err) {
        Path folder = null;
        boolean dryRun = false;
        String script = BUILTIN;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--script":
                    if (i + 1 >= args.length) {
                        err.println("xdstorage-migrate: --script is missing its value");
                        usage(err);
                        return USAGE;
                    }
                    script = args[++i];
                    break;
                case "--help":
                    usage(out);
                    return 0;
                default:
                    if (args[i].startsWith("--")) {
                        err.println("xdstorage-migrate: unknown option " + args[i]);
                        usage(err);
                        return USAGE;
                    }
                    if (folder != null) {
                        err.println("xdstorage-migrate: one folder, not two (" + folder + " and " + args[i] + ")");
                        usage(err);
                        return USAGE;
                    }
                    folder = Paths.get(args[i]);
            }
        }
        if (folder == null) {
            usage(err);
            return USAGE;
        }
        if (!BUILTIN.equals(script)) {
            err.println("xdstorage-migrate: unknown script '" + script + "' — the only script is " + BUILTIN);
            usage(err);
            return USAGE;
        }
        if (!Files.isDirectory(folder)) {
            err.println("xdstorage-migrate: folder does not exist or is not a directory: " + folder);
            return FAILED;
        }
        try {
            final Plan plan = plan(folder);
            describe(plan, out);
            if (!plan.hasWork()) {
                out.println("xdstorage-migrate: nothing to migrate");
                return 0;
            }
            if (dryRun) {
                out.println("xdstorage-migrate: dry run — nothing was changed");
                return WORK_FOUND;
            }
            return apply(plan, out, err);
        } catch (final IOException | RuntimeException failure) {
            err.println("xdstorage-migrate: " + firstLine(failure.toString()));
            return FAILED;
        }
    }

    /** Reads the folder and says what the script would do; changes nothing. */
    public static Plan plan(final Path folder) throws IOException {
        final List<Path> json = new ArrayList<>();
        final List<Path> withoutSentinel = new ArrayList<>();
        boolean hasXml = false;
        try (Stream<Path> files = Files.walk(folder)) {
            for (final Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".json")) {
                    json.add(file);
                } else if (name.endsWith(".xml")) {
                    hasXml = true;
                } else if (name.endsWith(".yaml") && Files.size(file) > 0 && !endsWithSentinel(file)) {
                    withoutSentinel.add(file);
                }
            }
        }
        Collections.sort(json);
        Collections.sort(withoutSentinel);
        final Properties marker = XdStorageFormatMeta.read(folder.toFile());
        return new Plan(folder, marker == null ? null : marker.getProperty("formatVersion"), json, withoutSentinel,
                hasXml);
    }

    /**
     * Runs the script on a planned folder.
     *
     * @param plan the plan, made by {@link #plan}
     * @param out  where the result goes
     * @param err  where failures go
     * @return 0 when the result opened with the default configuration and verified, else {@link #FAILED}
     */
    public static int apply(final Plan plan, final PrintStream out, final PrintStream err) throws IOException {
        final Path folder = plan.folder;
        if (plan.hasXml && !plan.jsonFiles.isEmpty()) {
            err.println("xdstorage-migrate: the folder holds both .xml and .json files — not a shape the engine"
                    + " writes; nothing was renamed; sort the .json files out by hand");
            return FAILED;
        }
        // no other process (or JVM) may have the folder open while its files are renamed
        try (XdStorageFolderLock ignored = XdStorageFolderLock.acquire(folder.toFile())) {
            for (final Path json : plan.jsonFiles) {
                if (Files.exists(asXml(json))) {
                    err.println("xdstorage-migrate: both " + json.getFileName() + " and " + asXml(json).getFileName()
                            + " exist in " + json.getParent() + " — nothing was renamed");
                    return FAILED;
                }
            }
            for (final Path json : plan.jsonFiles) {
                Files.move(json, asXml(json));
            }
            for (final Path file : plan.filesWithoutSentinel) {
                final byte[] content = Files.readAllBytes(file);
                // the shape the engine's writer leaves: the last record, a blank line, the sentinel
                final byte[] ending = (content[content.length - 1] == '\n' ? "\n" : "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                final byte[] sentinel = (XdStorageYamlTokenizer.EOF_SENTINEL_LINE + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                final byte[] complete = new byte[content.length + ending.length + sentinel.length];
                System.arraycopy(content, 0, complete, 0, content.length);
                System.arraycopy(ending, 0, complete, content.length, ending.length);
                System.arraycopy(sentinel, 0, complete, content.length + ending.length, sentinel.length);
                Files.write(file, complete);
            }
        }
        out.println("xdstorage-migrate: renamed " + plan.jsonFiles.size() + " file(s) .json -> .xml; appended the"
                + " EOF sentinel to " + plan.filesWithoutSentinel.size() + " file(s)");

        // the default configuration; for XML that is without journaling — the XML format does not journal
        final XdStorageConfig config = plan.format() == FilesFormat.XML
                ? XdStorageConfig.builder().journalWrites(false).build() : XdStorageConfig.defaults();
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("xdstorage-migrate",
                folder.toAbsolutePath().toString(), 64, plan.format(), config);
        storage.shutdown();
        out.println("xdstorage-migrate: the folder opens with the default configuration (" + plan.format() + ")");

        final XdStorageVerifyReport report = XdStorageProvider.verify(folder);
        out.println("xdstorage-migrate: verify -> " + report.verdict);
        if (report.verdict == XdStorageVerifyReport.Verdict.WARNINGS && plan.markerVersion == null) {
            out.println("xdstorage-migrate: a 1.x folder keeps no format marker — that alone is a warning to"
                    + " verify");
        }
        if (report.verdict == XdStorageVerifyReport.Verdict.CORRUPT) {
            for (final String finding : report.findings) {
                err.println("xdstorage-migrate: " + finding);
            }
            return FAILED;
        }
        return 0;
    }

    private static Path asXml(final Path json) {
        final String name = json.getFileName().toString();
        return json.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".xml");
    }

    private static boolean endsWithSentinel(final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0; i--) {
            final String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                return line.equals(XdStorageYamlTokenizer.EOF_SENTINEL_LINE);
            }
        }
        return false;
    }

    private static void describe(final Plan plan, final PrintStream out) {
        out.println("xdstorage-migrate: " + plan.folder);
        out.println("  format marker: "
                + (plan.markerVersion == null ? "none (a 1.x folder)" : "v" + plan.markerVersion));
        out.println("  .json files to rename: " + plan.jsonFiles.size());
        out.println("  YAML files without the EOF sentinel: " + plan.filesWithoutSentinel.size());
        out.println("  opens as: " + plan.format());
    }

    private static void usage(final PrintStream out) {
        out.println("Usage: java -cp <xdstorage.jar> io.github.xdstorage.tools.XdStorageMigrate <folder>"
                + " [--dry-run] [--script builtin:v1-to-v2]");
        out.println("  --dry-run              print the plan, change nothing (exit 1 when there is work)");
        out.println("  --script builtin:...   the only script; the default");
        out.println("Exit codes: 0 migrated or nothing to do; 2 usage; 3 in use, failed, or the result"
                + " does not verify");
    }

    private static String firstLine(final String text) {
        final int cut = text.indexOf('\n');
        return cut < 0 ? text : text.substring(0, cut);
    }
}
