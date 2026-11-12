package io.github.xdstorage.utils;

import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.XdStorageVerifyReport.FileReport;
import io.github.xdstorage.XdStorageVerifyReport.State;
import io.github.xdstorage.XdStorageVerifyReport.Verdict;
import io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal;
import io.github.xdstorage.serialization.yaml.XdStorageYamlTokenizer;
import io.github.xdstorage.serialization.yaml.YamlLineToken;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * VERIFY (docs/tasks/2.1.0/26): the read-only integrity walk behind
 * {@code XdStorageProvider.verify(folder)} — the mechanized after-a-power-loss recipe.
 *
 * <p>Everything is STRUCTURAL: files are tokenized (never materialized into objects), so no
 * entity classes are needed and nothing is written. Per content file: the S-1 EOF sentinel
 * state and the entry count (level-1 list items — objects, index records, references, or
 * journal records, whatever the file carries). On top of the per-file rows: the format
 * marker, orphaned journals (no base), stale {@code .tmp} litter, the per-class data-vs-id-index
 * count consistency (enforced only on clean folders — with journals present the counts
 * reconcile at replay), and the folder-lock probe (a live storage makes the results
 * unreliable; reported, never refused).
 *
 * <p>Boundaries, loudly drawn: verify never repairs; it does not open a storage and never
 * takes the folder lock; B+tree key-set reconciliation stays FP-5's scope (this walk checks
 * the tree files' sentinels and reports their entry counts).
 */
public final class XdStorageFolderVerifier {

    private static final Logger log = LoggerFactory.getLogger(XdStorageFolderVerifier.class);

    /** Engine-infra directories under data/ (B+tree heads and nodes) — no per-class consistency anchor. */
    private static final String BTREE_DIR = "XdStorageBTree";
    private static final String BTREE_NODE_DIR = "XdStorageBTreeNode";

    private static final String DATA_DIR = "data";
    private static final String INDEX_DIR = "indexes";
    private static final String STRUCTURE_DIR = "structure";

    private XdStorageFolderVerifier() {
    }

    /**
     * Runs the read-only structural integrity walk over a storage folder: checks the format
     * marker, tokenizes every content file for its EOF-sentinel state and level-1 entry count
     * (never materializing objects), flags journals without a base, stale {@code .tmp} litter
     * and unexpected files, and compares per-class data counts against their id-index file
     * (only on journal-free folders). A live folder lock is reported but not refused.
     *
     * @param folder the storage folder to inspect read-only (never modified, never locked)
     * @return the structured report: per-file rows, findings, format version and the overall
     *         verdict ({@code OK} / {@code WARNINGS} / {@code CORRUPT})
     */
    public static XdStorageVerifyReport verify(final File folder) {
        System.err.println("[VERIFY] START folder=" + folder);
        final List<FileReport> files = new ArrayList<>();
        final List<String> findings = new ArrayList<>();
        final String folderPath = folder.toString();

        // ---- the folder itself -------------------------------------------------------
        if (!folder.isDirectory()) {
            findings.add("NO SUCH FOLDER: " + folderPath + " does not exist or is not a directory");
            return new XdStorageVerifyReport(folderPath, Verdict.CORRUPT, null, false, files, findings);
        }

        // ---- the format marker -------------------------------------------------------
        String formatVersion = null;
        boolean corrupt = false;
        boolean warnings = false;
        Properties metaRead = null;
        try {
            metaRead = XdStorageFormatMeta.read(folder);
        } catch (final RuntimeException e) {
            findings.add("UNREADABLE MARKER: " + XdStorageFormatMeta.META_FILE_NAME + " cannot be read (" + e.getMessage() + ")");
            corrupt = true;
        }
        final Properties meta = metaRead;
        if (meta == null) {
            if (!corrupt) {
                findings.add("NO FORMAT MARKER: " + XdStorageFormatMeta.META_FILE_NAME + " absent — "
                        + "a legacy 1.x folder or not a storage folder");
                warnings = true;
            }
        } else {
            formatVersion = meta.getProperty("formatVersion");
            try {
                final int version = Integer.parseInt(formatVersion.trim());
                if (version > XdStorageFormatMeta.FORMAT_VERSION) {
                    findings.add("NEWER FORMAT: formatVersion=" + version + ", this library writes up to v"
                            + XdStorageFormatMeta.FORMAT_VERSION + " — upgrade xdstorage before opening");
                    corrupt = true;
                }
            } catch (final RuntimeException e) {
                findings.add("UNREADABLE MARKER: formatVersion='" + formatVersion + "' is not a number");
                corrupt = true;
            }
        }
        files.add(new FileReport(XdStorageFormatMeta.META_FILE_NAME, "marker",
                meta == null ? State.MISSING_SENTINEL : State.OK, 0,
                new File(folder, XdStorageFormatMeta.META_FILE_NAME).length(), false,
                meta == null ? Collections.singletonList("marker absent") : null));

        // ---- the folder lock: report a live storage, never refuse --------------------
        final boolean inUse = probeFolderLock(new File(folder, XdStorageFolderLock.LOCK_FILE_NAME));
        if (inUse) {
            findings.add("IN USE: a live storage holds the folder lock — the results are unreliable "
                    + "(files were read while being written)");
            warnings = true;
        }

        // ---- the walk ----------------------------------------------------------------
        final List<Path> contentFiles = new ArrayList<>();
        try (final Stream<Path> walk = Files.walk(folder.toPath())) {
            for (final Path path : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                final String name = path.getFileName().toString();
                if (name.equals(XdStorageFormatMeta.META_FILE_NAME) || name.equals(XdStorageFolderLock.LOCK_FILE_NAME)) {
                    continue;
                }
                if (name.endsWith(".tmp")) {
                    findings.add("STALE TMP: " + relative(folder, path) + " (S-5 sweeps it at the next open)");
                    warnings = true;
                    continue;
                }
                contentFiles.add(path);
            }
        } catch (final IOException e) {
            findings.add("WALK FAILED: the folder tree cannot be walked (" + e.getMessage() + ")");
            corrupt = true;
        }
        Collections.sort(contentFiles);

        final boolean legacyFolder = meta == null
                || (formatVersion != null && formatVersion.trim().startsWith("1"));
        boolean sawContentFile = false;

        for (final Path path : contentFiles) {
            final String relative = relative(folder, path);
            final String name = path.getFileName().toString();
            sawContentFile = true;

            if (!name.endsWith(".yaml")) {
                findings.add("UNEXPECTED FILE: " + relative + " is not engine-owned content");
                warnings = true;
                continue;
            }

            final boolean journal = name.endsWith(XdStorageYamlCommitJournal.JOURNAL_SUFFIX)
                    || name.endsWith(XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX);
            final boolean legacyNaming = name.endsWith(XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX);

            final Parsed parsed = parseStructurally(path, journal);
            final State state = parsed.state;
            final String kind = kindOf(relative, name, journal);

            final List<String> notes = new ArrayList<>();
            if (legacyNaming) {
                notes.add("2.0.0-era journal naming — migrates automatically at the file's replay");
            }
            if (state == State.MISSING_SENTINEL && !journal && legacyFolder) {
                notes.add("legacy v1 file without an EOF sentinel (opens with acceptMissingEofSentinel)");
            }
            files.add(new FileReport(relative, kind, state, parsed.entries, parsed.bytes, legacyNaming, notes));

            switch (state) {
                case MISSING_SENTINEL:
                    if (journal) {
                        findings.add("JOURNAL TORN: " + relative + " has no EOF sentinel (mid-append crash) — "
                                + parsed.entries + " record(s) before the cut replay, the tail is discarded");
                        warnings = true;   // designed lost-tail semantics (task 23)
                    } else if (legacyFolder) {
                        // legacy note only — already on the FileReport
                    } else {
                        findings.add("TRUNCATED: " + relative + " has no EOF sentinel (v2 file) — "
                                + "the open refuses it loudly, never a silent partial base");
                        corrupt = true;
                    }
                    break;
                case TORN_TAIL:
                    findings.add("JOURNAL TORN: " + relative + " carries " + parsed.entries
                            + " record(s) AFTER its last sentinel — that tail is discarded at replay");
                    warnings = true;
                    break;
                case PARSE_FAILED:
                    findings.add("UNREADABLE: " + relative + " cannot be tokenized (" + parsed.error + ")");
                    corrupt = true;
                    break;
                default:
                    break;
            }

            if (journal) {
                final String baseName = baseOfJournal(name);
                // the journal directory spelling: the base is the PARENT of the journal dir
                final Path journalParent = path.getParent();
                final File baseDir = journalParent == null || journalParent.getFileName() == null
                        ? folder
                        : XdStorageYamlCommitJournal.JOURNAL_DIR.equals(journalParent.getFileName().toString())
                        && journalParent.getParent() != null ? journalParent.getParent().toFile() : journalParent.toFile();
                final File base = new File(baseDir, baseName);
                if (!base.isFile()) {
                    findings.add("ORPHANED JOURNAL: " + relative + " has no base file ("
                            + baseName + ") — un-checkpointed commits or crash litter; the open replays it");
                    warnings = true;
                }
            }
        }

        if (!sawContentFile && !new File(folder, DATA_DIR).isDirectory() && !new File(folder, STRUCTURE_DIR).isDirectory()) {
            findings.add("EMPTY FOLDER: no storage content (a fresh folder, or the wrong path?)");
            warnings = true;
        }

        // ---- per-class data vs id-index count consistency ----------------------------
        final File dataDir = new File(folder, DATA_DIR);
        final File[] classDirs = dataDir.listFiles();
        if (classDirs != null) {
            for (final File classDir : classDirs) {
                if (!classDir.isDirectory()) {
                    continue;
                }
                final String dirName = classDir.getName();
                final boolean infra = dirName.startsWith("XdStorage");
                final File indexFile = new File(classDir, "index.yaml");
                if (infra || !indexFile.isFile()) {
                    continue;
                }
                long baseEntries = 0;
                boolean journalsPresent = false;
                final String classDirPath = DATA_DIR + "/" + dirName;
                final String classJournalDirPath = classDirPath + "/" + XdStorageYamlCommitJournal.JOURNAL_DIR;
                for (final FileReport row : files) {
                    final String parent = row.path.contains("/") ? row.path.substring(0, row.path.lastIndexOf('/')) : "";
                    if (!parent.equals(classDirPath) && !parent.equals(classJournalDirPath)) {
                        continue;
                    }
                    if ("journal".equals(row.kind) && row.state != State.EMPTY) {
                        journalsPresent = true;
                    } else if ("base".equals(row.kind)) {
                        baseEntries += row.entries;
                    }
                }
                if (journalsPresent) {
                    continue;   // un-checkpointed commits — the counts reconcile at replay
                }
                final Parsed indexParsed = parseStructurally(indexFile.toPath(), false);
                if (indexParsed.state == State.OK && indexParsed.entries != baseEntries) {
                    findings.add("COUNT MISMATCH: " + DATA_DIR + "/" + dirName + "/ holds " + baseEntries
                            + " data object(s) vs " + indexParsed.entries + " id-index entries — "
                            + "hand-edited or partially lost content");
                    corrupt = true;
                }
            }
        }

        System.err.println("[VERIFY] after data/index.yaml check, moving to id-index check");

        // ---- StoreAsClassObjects: data dir vs id-index (indexes/<Class>/) count check ----
        final File indexDir = new File(folder, INDEX_DIR);
        System.err.println("[VERIFY-IDX] folder=" + folder + " INDEX_DIR=" + INDEX_DIR + " indexDir=" + indexDir + " exists=" + indexDir.isDirectory() + " dataDir=" + dataDir + " dataDirExists=" + dataDir.isDirectory() + " dataDirFiles=" + java.util.Arrays.toString(dataDir.listFiles()));
        if (indexDir.isDirectory()) {
            final File[] indexClassDirs = indexDir.listFiles();
            if (indexClassDirs != null) {
                for (final File indexClassDir : indexClassDirs) {
                    if (!indexClassDir.isDirectory()) {
                        continue;
                    }
                    final String idxDirName = indexClassDir.getName();
                    System.err.println("[VERIFY-IDX] class=" + idxDirName + " indexesDir=" + indexClassDir + " dataDir=" + new File(dataDir, idxDirName));
                    // match against a data dir by the same name
                    final File dataClassDir = new File(dataDir, idxDirName);
                    if (!dataClassDir.isDirectory()) {
                        continue;
                    }
                    // sum id-index entries from index/<Class>/ (may be fragmented)
                    long idxEntries = 0;
                    final File[] idxFiles = indexClassDir.listFiles();
                    if (idxFiles != null) {
                        for (final File idxFile : idxFiles) {
                            if (!idxFile.isFile() || !idxFile.getName().endsWith(".yaml")) {
                                continue;
                            }
                            final Parsed p = parseStructurally(idxFile.toPath(), false);
                            if (p.state == State.OK) {
                                idxEntries += p.entries;
                                System.err.println("[VERIFY-IDX]   idxFile=" + idxFile.getName() + " entries=" + p.entries);
                            }
                        }
                    }
                    // sum data entries from data/<Class>/ (fragmented)
                    long dbEntries = 0;
                    final File[] dbFiles = dataClassDir.listFiles();
                    if (dbFiles != null) {
                        for (final File dbFile : dbFiles) {
                            if (!dbFile.isFile() || !dbFile.getName().endsWith(".yaml")) {
                                continue;
                            }
                            final Parsed p = parseStructurally(dbFile.toPath(), false);
                            if (p.state == State.OK) {
                                dbEntries += p.entries;
                                System.err.println("[VERIFY-IDX]   dbFile=" + dbFile.getName() + " entries=" + p.entries);
                            }
                        }
                    }
                    System.err.println("[VERIFY-IDX]   " + idxDirName + " data=" + dbEntries + " index=" + idxEntries);
                    if (idxEntries != dbEntries) {
                        findings.add("COUNT MISMATCH: " + DATA_DIR + "/" + idxDirName + "/ holds " + dbEntries
                                + " data object(s) vs " + idxEntries + " id-index entries — "
                                + "hand-edited or partially lost content");
                        corrupt = true;
                    }
                }
            }
        }

        final Verdict verdict = corrupt ? Verdict.CORRUPT : warnings ? Verdict.WARNINGS : Verdict.OK;
        log.info("verify {}: {} ({} file(s), {} finding(s))", folderPath, verdict, files.size(), findings.size());
        return new XdStorageVerifyReport(folderPath, verdict, formatVersion, inUse, files, findings);
    }

    // ------------------------------------------------------------------ helpers

    /** One structural parse: entry count + sentinel state, never materializing objects. */
    private static Parsed parseStructurally(final Path file, final boolean journal) {
        final long bytes = file.toFile().length();
        if (bytes == 0) {
            return new Parsed(State.EMPTY, 0, bytes, null);
        }
        long entries = 0;
        boolean sentinelSeen = false;
        try (final BufferedReader reader = new BufferedReader(new FileReader(file.toFile()), 1 << 16)) {
            final XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(reader);
            YamlLineToken token;
            while ((token = tokenizer.nextToken()) != null) {
                if (XdStorageYamlTokenizer.EOF_SENTINEL_KEY.equals(token.key)) {
                    sentinelSeen = true;
                    continue;
                }
                if (token.level == 1 && token.isListItem) {
                    if (sentinelSeen) {
                        // journal-only: record blocks after the last sentinel = the torn tail
                        return new Parsed(State.TORN_TAIL, entries + 1, bytes, null);
                    }
                    entries++;
                }
            }
        } catch (final Throwable e) {
            return new Parsed(State.PARSE_FAILED, entries, bytes, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (!sentinelSeen) {
            return new Parsed(State.MISSING_SENTINEL, entries, bytes, null);
        }
        return new Parsed(State.OK, entries, bytes, null);
    }

    /** A live holder keeps the OS lock; a stale lock file locks nothing. Read-only probe: open, try, release. */
    private static boolean probeFolderLock(final File lockFile) {
        if (!lockFile.isFile()) {
            return false;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(lockFile, "rw");
            try {
                return raf.getChannel().tryLock() == null;
            } catch (final OverlappingFileLockException e) {
                return true;   // held by THIS jvm
            }
        } catch (final IOException e) {
            return false;   // cannot even open — do not guess
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (final IOException ignored) {
                }
            }
        }
    }

    private static String kindOf(final String relative, final String name, final boolean journal) {
        if (journal) {
            return "journal";
        }
        if (relative.startsWith(STRUCTURE_DIR + "/")) {
            return "structure";
        }
        if (relative.contains("/" + BTREE_DIR + "/") || relative.startsWith(BTREE_DIR + "/")) {
            return "btree";
        }
        if (relative.contains("/" + BTREE_NODE_DIR + "/") || relative.startsWith(BTREE_NODE_DIR + "/")) {
            return "btree-node";
        }
        if (name.equals("index.yaml")) {
            return "index";
        }
        if (name.equals("references.yaml")) {
            return "references";
        }
        return "base";
    }

    /** The base file a journal belongs to: the suffix spelling strips to the extension-less base + ".yaml". */
    private static String baseOfJournal(final String journalName) {
        final String name = journalName;
        if (name.endsWith(XdStorageYamlCommitJournal.JOURNAL_SUFFIX)) {
            return name.substring(0, name.length() - XdStorageYamlCommitJournal.JOURNAL_SUFFIX.length()) + ".yaml";
        }
        return name.substring(0, name.length() - XdStorageYamlCommitJournal.LEGACY_JOURNAL_SUFFIX.length());
    }

    private static String relative(final File folder, final Path path) {
        return folder.toPath().relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static final class Parsed {
        final State state;
        final long entries;
        final long bytes;
        final String error;

        Parsed(final State state, final long entries, final long bytes, final String error) {
            this.state = state;
            this.entries = entries;
            this.bytes = bytes;
            this.error = error;
        }
    }
}
