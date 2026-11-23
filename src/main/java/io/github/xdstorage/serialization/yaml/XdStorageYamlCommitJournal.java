package io.github.xdstorage.serialization.yaml;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.services.XdStorageServicesLocator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * W-1: append-only commit journal for one resource (WRITE-PATH-DESIGN.md).
 *
 * <p>Layout (JRNLNAME, docs/tasks/2.1.0/24): journals live in the {@link #JOURNAL_DIR journal}
 * SUBDIRECTORY of their resource's directory — {@code Order-1.yaml → <dir>/journal/Order-1-journal.yaml}
 * — so a class folder holds only bases. The journal content is YAML for every storage format,
 * so the name always ends in .yaml. Read-compatibility, resolved in this priority: the
 * 2.1.0-dev sibling spelling ({@code <dir>/Order-1-journal.yaml}, pre-subdirectory) and the
 * 2.0.0-era stacked name ({@code <dir>/Order-1.yaml.journal.yaml}) are both replay sources —
 * after a clean replay the file is migrated into the journal subdirectory, and any additional
 * older-named twin is removed (WARN). A sequence of record blocks, each append
 * terminated by the S-1 EOF sentinel:</p>
 * <pre>
 * - record:
 *     serial: 17
 *     op: UPSERT
 *     id: 'ship-1'
 *     object:
 *         class: 'com.acme.Ship'
 *         ...full object body via the standard YAML object writer...
 * </pre>
 *
 * <p>Crash semantics: an append interrupted mid-write leaves the tail without a trailing
 * sentinel; the reader discards everything after the LAST sentinel (loud WARN) instead of
 * serving a torn record. Recovery replay is idempotent: UPSERT/DELETE by id, last record
 * in file order wins (appends are serialized by the resource commit lock, so file order is
 * commit order).</p>
 *
 * <p>Checkpoint (size threshold): full base rewrite via the existing writer + journal file
 * deletion. No checkpoint marker: replay over an already-checkpointed base is idempotent,
 * so every crash window between "base rewritten" and "journal deleted" is safe.</p>
 *
 * <p>W-1 scope notes: journaling covers data/reference resources only (id-index/search
 * B+tree infra keeps the full-file write-out — node graphs are not journalable records);
 * YAML format only.</p>
 */
public final class XdStorageYamlCommitJournal {

    private static final Logger log = LoggerFactory.getLogger(XdStorageYamlCommitJournal.class);

    // JRNLNAME (docs/tasks/2.1.0/24): journals live in the journal SUBDIRECTORY of their
    // resource's directory, named with a SUFFIX on the extension-less base — never a stacked
    // extension. The journal content is YAML for every storage format, so the name ends in .yaml.
    /** Name of the journal subdirectory inside a resource's directory: {@code <dir>/journal/}. */
    public static final String JOURNAL_DIR = "journal";

    /** Canonical journal file suffix appended to the extension-less base name:
     * {@code Order-1.yaml → Order-1-journal.yaml}. */
    public static final String JOURNAL_SUFFIX = "-journal.yaml";

    /** The 2.0.0-era naming (baseFileName + ".journal.yaml", a stacked extension, base's dir) —
     * read-compatible: a legacy file is replayed and migrated into the journal directory. */
    public static final String LEGACY_JOURNAL_SUFFIX = ".journal.yaml";

    private XdStorageYamlCommitJournal() {
    }

    /** The journal path for one base file: {@code <dir-of-base>/journal/<base-without-ext>-journal.yaml}.
     *
     * @param baseFileName the path of the resource's base file
     * @return the canonical journal path (which may not exist yet)
     */
    public static Path journalFileFor(final String baseFileName) {
        final File base = new File(baseFileName);
        final File parent = base.getParentFile();
        final String journalName = stripFormatExtension(base.getName()) + JOURNAL_SUFFIX;
        return (parent == null
                ? new File(JOURNAL_DIR, journalName)
                : new File(new File(parent, JOURNAL_DIR), journalName)).toPath();
    }

    /** The 2.1.0-dev sibling spelling (the journal NEXT to the base, no subdirectory) —
     * read-compatible within the unreleased train: replayed and migrated into {@link #JOURNAL_DIR}.
     *
     * @param baseFileName the path of the resource's base file
     * @return the sibling journal path (which may not exist yet)
     */
    public static Path siblingJournalFileFor(final String baseFileName) {
        return new File(stripFormatExtension(baseFileName) + JOURNAL_SUFFIX).toPath();
    }

    /** The 2.0.0-era path (the stacked-extension name in the base's dir) — resolution + migration only.
     *
     * @param baseFileName the path of the resource's base file
     * @return the legacy stacked-name journal path (which may not exist yet)
     */
    public static Path legacyJournalFileFor(final String baseFileName) {
        return new File(baseFileName + LEGACY_JOURNAL_SUFFIX).toPath();
    }

    /** Strips a known format extension (yaml/xml/json) — class-derived base names carry no other dots.
     *
     * @param baseFileName the base file name, with or without a format extension
     * @return the extension-less base name (the input itself when no known format extension ends it)
     */
    public static String stripFormatExtension(final String baseFileName) {
        if (baseFileName.endsWith(".yaml")) return baseFileName.substring(0, baseFileName.length() - 5);
        if (baseFileName.endsWith(".json")) return baseFileName.substring(0, baseFileName.length() - 5);
        if (baseFileName.endsWith(".xml")) return baseFileName.substring(0, baseFileName.length() - 4);
        return baseFileName;
    }

    /** The resolved journal location for one base name (existence-aware). */
    public static final class Resolution {
        /** The path ALL journal I/O must use after resolution (migrating storage included). */
        public final Path file;
        /** Existing older-named journals (lower-priority namings) — migrated/removed after the clean replay. */
        public final java.util.List<Path> superseded;

        Resolution(final Path file, final java.util.List<Path> superseded) {
            this.file = file;
            this.superseded = java.util.Collections.unmodifiableList(superseded);
        }
    }

    /**
     * JRNLNAME resolution, priority order: the journal-directory file wins; a sibling-named
     * (pre-subdirectory) or legacy stacked-named journal without the newer ones is the replay
     * source (migrate after a successful replay); the fresh case creates in the journal directory.
     *
     * @param baseFileName the path of the resource's base file
     * @return the resolution: the journal file all future I/O must use, plus any
     *         older-named twins found on disk
     */
    public static Resolution resolveFor(final String baseFileName) {
        Path chosen = null;
        final java.util.List<Path> superseded = new java.util.ArrayList<>();
        for (final Path candidate : new Path[]{
                journalFileFor(baseFileName), siblingJournalFileFor(baseFileName), legacyJournalFileFor(baseFileName)})
                        {
            if (Files.exists(candidate)) {
                if (chosen == null) {
                    chosen = candidate;
                } else {
                    superseded.add(candidate);
                }
            }
        }
        return new Resolution(chosen == null ? journalFileFor(baseFileName) : chosen, superseded);
    }

    /** Task 54 (JRNLDATA): the index-base journal's PRIMARY location — the class's data
     *  journal directory ({@code data/<Class>/journal/}). The index journals are DATA
     *  CARRIERS (the sole durable location of the un-checkpointed id→fragment
     *  addressability between checkpoints) and live with the class's data, not with the
     *  index layout.
     *
     * @param baseFileName the path of the index base file (indexes/<Class>/<name>.yaml)
     * @param dataClassJournalDir the class's data journal dir (the naming service's
     *                            getDataClassJournalDir)
     * @return the journal path inside data/<Class>/journal/ (which may not exist yet)
     */
    public static Path dataJournalFileFor(final String baseFileName, final Object dataClassJournalDir) {
        if (dataClassJournalDir == null) {
            return null; // the stub-services tests / the pre-wiring states: the legacy chain applies
        }
        final String stem = stripFormatExtension(new File(baseFileName).getName());
        return new File(dataClassJournalDir.toString(), stem + JOURNAL_SUFFIX).toPath();
    }

    /**
     * Task 54 (JRNLDATA): the INDEX-base journal resolution — the data-carrier location
     * first, then the older spellings: the dev-train journal dir next to the index base
     * (indexes/&lt;Class&gt;/journal/), the sibling, the 2.0.0 stacked. The first existing
     * wins; the rest are the superseded twins. The fresh case creates at the data location.
     *
     * @param baseFileName the path of the index base file
     * @param dataClassJournalDir the class's data journal dir
     * @return the resolution: the journal file all future I/O must use, plus the older twins
     */
    public static Resolution resolveForIndexBase(final String baseFileName, final Object dataClassJournalDir) {
        Path chosen = null;
        final java.util.List<Path> superseded = new java.util.ArrayList<>();
        // the 5th level: the STACKED spelling anchored at the class's DATA dir — the journal
        // that lived next to the base BEFORE the indexes/ move (the dev-train folders) or
        // before the JRNLDATA relocation of the primary (data/<Class>/index.yaml.journal.yaml)
        if (dataClassJournalDir == null) {
            return resolveFor(baseFileName); // the stub-services fallback: the pre-JRNLDATA chain
        }
        final File dataClassDir = new File(dataClassJournalDir.toString()).getParentFile();
        final Path dataAnchoredStacked = dataClassDir == null ? null
                : legacyJournalFileFor(new File(dataClassDir, new File(baseFileName).getName()).getPath());
        final Path dataAnchoredSibling = dataClassDir == null ? null
                : new File(dataClassDir, stripFormatExtension(new File(baseFileName).getName()) + JOURNAL_SUFFIX)
                        .toPath();
        for (final Path candidate : new Path[]{
                dataJournalFileFor(baseFileName, dataClassJournalDir),
                journalFileFor(baseFileName), siblingJournalFileFor(baseFileName), legacyJournalFileFor(baseFileName),
                dataAnchoredSibling, dataAnchoredStacked}) {
            if (candidate != null && Files.exists(candidate)) {
                if (chosen == null) {
                    chosen = candidate;
                } else {
                    superseded.add(candidate);
                }
            }
        }
        return new Resolution(
                chosen == null ? dataJournalFileFor(baseFileName, dataClassJournalDir) : chosen, superseded);
    }

    /**
     * JRNLNAME: called after a successful journal replay — migrates a replayed older-named
     * file into the journal directory, and drops any additional superseded twin (the
     * both-present anomaly, WARN). Returns the path all future journal I/O must use.
     *
     * @param replayedFile the journal actually replayed (may already be the canonical one)
     * @param superseded   the lower-priority journals returned by {@link #resolveFor}
     * @param baseFileName the path of the resource's base file
     * @return the canonical journal path inside {@link #JOURNAL_DIR} for all future I/O
     * @throws java.io.IOException when the move or a stale-file deletion fails
     */
    public static Path settleAfterReplay(final Path replayedFile, final java.util.List<Path> superseded,
                                         final String baseFileName) throws java.io.IOException {
        final Path target = journalFileFor(baseFileName);
        if (!replayedFile.equals(target)) {
            // task 51 (LAYOUTMIG): the migrated stacked journals settle BEFORE any append
            // ever created the journal/ directory — create it (a 2.0.0 folder opened by
            // 2.1.0 hits exactly this path; without it the move fails NoSuchFile)
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.move(replayedFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("journal migrated into the journal directory: {} -> {}", replayedFile, target);
        }
        for (final Path stale : superseded) {
            if (stale.equals(replayedFile)) {
                continue;   // the replay source itself — already moved above
            }
            java.nio.file.Files.delete(stale);
            log.warn("superseded journal naming removed for {}: {} ({} wins)",
                    baseFileName, stale, replayedFile);
        }
        return target;
    }

    /**
     * Task 54 (JRNLDATA): the settle variant with the EXPLICIT target — the index-base
     * journals settle into the class's data journal dir (data/&lt;Class&gt;/journal/),
     * not next to the index base. The same create-parent + move + drop-twins contract.
     *
     * @param replayedFile the replayed journal file (any older spelling)
     * @param superseded the additional older-named twins to remove
     * @param target the journal path all future I/O must use
     * @return the target path
     * @throws java.io.IOException when creating the target's parent or moving/deleting a
     *         journal file fails
     */
    public static Path settleAfterReplay(final Path replayedFile, final java.util.List<Path> superseded,
                                         final Path target) throws java.io.IOException {
        if (target == null) {
            return replayedFile; // the stub-services fallback: keep the resolved file in place
        }
        if (!replayedFile.equals(target)) {
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.move(replayedFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("journal migrated to the data journal dir: {} -> {}", replayedFile, target);
        }
        for (final Path stale : superseded) {
            if (stale.equals(replayedFile)) {
                continue;
            }
            java.nio.file.Files.delete(stale);
            log.warn("superseded journal naming removed for {}: {} ({} wins)", target, stale, target);
        }
        return target;
    }

    /**
     * JRNLNAME for search-index journals: resolution priority is
     * (1) same-dir sibling (the canonical placement for search-indexes),
     * (2) legacy stacked name in the base's dir, (3) sibling 2.1.0-dev spelling.
     * Search-indexes keep journals in the {@code indexes/} subdirectory, not in {@link #JOURNAL_DIR}.
     *
     * @param baseFileName the path of the search-index's base file
     * @return the resolution: the journal file all future I/O must use, plus any
     *         older-named twins found on disk
     */
    public static Resolution searchIndexJournalResolveFor(final String baseFileName) {
        final String baseName = stripFormatExtension(baseFileName);
        final String journalName = baseName + JOURNAL_SUFFIX;
        Path chosen = null;
        final java.util.List<Path> superseded = new java.util.ArrayList<>();
        // Priority: 1) same-dir sibling (canonical), 2) legacy stacked, 3) sibling 2.1.0-dev
        for (final Path candidate : new Path[]{
                new File(new File(baseFileName).getParentFile(), journalName).toPath(),
                new File(baseFileName + LEGACY_JOURNAL_SUFFIX).toPath(),
                siblingJournalFileFor(baseFileName)}) {
            if (Files.exists(candidate)) {
                if (chosen == null) {
                    chosen = candidate;
                } else {
                    superseded.add(candidate);
                }
            }
        }
        return new Resolution(chosen == null
                ? new File(new File(baseFileName).getParentFile(), journalName).toPath()
                : chosen, superseded);
    }

    /**
     * Search-index journal settlement: moves the replayed file into the same-dir
     * sibling position, drops any superseded twin.
     *
     * @param replayedFile the journal actually replayed
     * @param superseded   the lower-priority journals
     * @param baseFileName the path of the search-index's base file
     * @return the canonical same-dir journal path for all future I/O
     * @throws java.io.IOException when creating the target's parent or moving/deleting a
     *         journal file fails
     */
    public static Path searchIndexJournalSettleAfterReplay(final Path replayedFile,
                                                            final java.util.List<Path> superseded,
                                                            final String baseFileName)
            throws java.io.IOException {
        final File base = new File(baseFileName);
        final Path target = new File(base.getParentFile(),
                stripFormatExtension(base.getName()) + JOURNAL_SUFFIX).toPath();
        if (!replayedFile.equals(target)) {
            // task 51: same parent-creation pin as settleAfterReplay (the same-dir variant)
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.move(replayedFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("search-index journal migrated to same dir: {} -> {}", replayedFile, target);
        }
        for (final Path stale : superseded) {
            if (stale.equals(replayedFile)) {
                continue;
            }
            java.nio.file.Files.delete(stale);
            log.warn("superseded search-index journal naming removed for {}: {} ({} wins)",
                    baseFileName, stale, replayedFile);
        }
        return target;
    }

    /** One durable redo/compensation record waiting to be appended. */
    public static final class Record {
        /** The commit serial of the owning commit (0 for compensation records). */
        public final long serial;
        /** {@code true} for a DELETE record (only {@link #id} is meaningful), {@code false}
         * for an UPSERT carrying a full {@link #object} body. */
        public final boolean delete;
        /** The id of the affected object, already materialized to its id type. */
        public final Object id;
        /** The full new object body for an UPSERT, or {@code null} for a DELETE. */
        public final Object object;

        Record(final long serial, final boolean delete, final Object id, final Object object) {
            this.serial = serial;
            this.delete = delete;
            this.id = id;
            this.object = object;
        }
    }

    /**
     * Appends REDO records for a successfully prepared commit:
     * Insert/Update → UPSERT(newObject), Delete → DELETE(id).
     *
     * @param file         the journal file to append to (created with its parent directory)
     * @param commitSerial the serial of the commit being journaled
     * @param changes      the commit's object changes (entries without an id are skipped)
     * @param services     the storage services locator (used by the object body encoder)
     * @param factory      the YAML I/O factory supplying the simple-type helper and id generator
     * @param durable      {@code true} to fsync the append ({@code FileDescriptor.sync}) — the
     *                     journal is the durability point while it holds the commit
     * @throws IOException        when the file cannot be appended to
     * @throws io.github.xdstorage.exceptions.XdStorageException when id generation fails while
     *         encoding an object body
     */
    public static void appendRedo(final Path file, final long commitSerial,
                                  final Collection<XdStorageObjectChange> changes,
                                  final XdStorageServicesLocator services,
                                  final XdStorageYamlIOFactory factory,
                                  final boolean durable) throws IOException, io.github.xdstorage.exceptions
                                          .XdStorageException {
        final List<Record> records = new ArrayList<>();
        for (final XdStorageObjectChange change : changes) {
            if (change.id == null) {
                continue;   // deferred-id wrapper notification path — not a data record
            }
            if (change.type == XdStorageObjectOperationType.Delete) {
                records.add(new Record(commitSerial, true, change.id, null));
            } else {
                records.add(new Record(commitSerial, false, change.id, change.newObject));
            }
        }
        append(file, records, services, factory, durable);
    }

    /**
     * Appends COMPENSATION records for a rolled-back first phase: the restored (pre-tx
     * committed) state per changed id — oldObject → UPSERT, insert-without-old → DELETE.
     *
     * @param file         the journal file to append to (created with its parent directory)
     * @param changes      the aborted commit's object changes (entries without an id are skipped)
     * @param services     the storage services locator (used by the object body encoder)
     * @param factory      the YAML I/O factory supplying the simple-type helper and id generator
     * @param durable      {@code true} to fsync the append ({@code FileDescriptor.sync})
     * @throws IOException        when the file cannot be appended to
     * @throws io.github.xdstorage.exceptions.XdStorageException when id generation fails while
     *         encoding an object body
     */
    public static void appendCompensation(final Path file,
                                          final Collection<XdStorageObjectChange> changes,
                                          final XdStorageServicesLocator services,
                                          final XdStorageYamlIOFactory factory,
                                          final boolean durable) throws IOException, io.github.xdstorage.exceptions
                                                  .XdStorageException {
        final List<Record> records = new ArrayList<>();
        for (final XdStorageObjectChange change : changes) {
            if (change.id == null) {
                continue;
            }
            if (change.oldObject != null) {
                records.add(new Record(0, false, change.id, change.oldObject));
            } else {
                records.add(new Record(0, true, change.id, null));
            }
        }
        append(file, records, services, factory, durable);
    }

    /**
     * Per-journal-file append lock: appends to DIFFERENT resources must not serialize
     * JVM-wide (W-3 made journalizing the default — 25-worker storms commit in parallel).
     * Same-folder cross-process exclusion is the folder lock's job (S-3); inside one JVM
     * the canonical-path interned string is the lock identity.
     */
    /** W-4a/c: the per-file append lock exposed for atomic checkpointing — a checkpoint
     * (base rewrite + journal reset) holds the same lock as appends, so a concurrent
     * commit's redo can never be lost between the base write and the reset.
     *
     * @param file the journal file whose appends are to be excluded
     * @return the lock identity ({@code synchronized} monitor) shared by all appends and
     *         checkpoints of that file
     */
    public static Object lockFor(final Path file) {
        return appendLockFor(file);
    }

    private static Object appendLockFor(final Path file) {
        try {
            return file.toFile().getCanonicalFile().getPath().intern();
        } catch (final IOException e) {
            return file.toAbsolutePath().toString().intern();
        }
    }

    /** One locked append: records + trailing sentinel, O(delta) bytes. */
    private static void append(final Path file, final List<Record> records,
                                            final XdStorageServicesLocator services,
                                            final XdStorageYamlIOFactory factory,
                                            final boolean durable) throws IOException, io.github.xdstorage.exceptions
                                                    .XdStorageException {
        if (records.isEmpty()) {
            return;
        }
        final StringWriter out = new StringWriter();
        final XdStorageYamlEmitter emitter = new XdStorageYamlEmitter(out);
        final IXdStorageSimpleTypeHelper helper = factory.simpleTypeHelper();
        final IXdStorageIdGenerator idGenerator = factory.idGenerator();
        for (final Record record : records) {
            emitter.openBlock("- record");
            emitter.writeKey("serial");
            emitter.writeScalar(String.valueOf(record.serial));
            emitter.writeKey("op");
            emitter.writeScalar(record.delete ? "DELETE" : "UPSERT");
            emitter.writeKey("id");
            emitter.writeScalar("'" + XdStorageYamlBlockObjectsWriter.encode(helper.simpleTypeToString(record.id)) +
                    "'");
            if (!record.delete && record.object != null) {
                emitter.openBlock("object");
                XdStorageYamlBlockObjectsWriter.writeObjectData(record.object, emitter, services, helper, idGenerator);
                emitter.closeBlock();
            }
            emitter.closeBlock();
        }
        emitter.writeEofSentinel();

        // task 51: pinned cross-OS
        final byte[] bytes = out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final Object appendLock = appendLockFor(file);
        // сериализация выше — вне лока; под локом только системный аппенд
        synchronized (appendLock) {
            final File parent = file.getParent() != null ? file.getParent().toFile() : null;
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file.toFile(), true)) {   // true = append
                fos.write(bytes);
                if (durable) {
                    fos.getFD().sync();   // S-2 parity: the journal is the durability point when it holds the commit
                }
            }
        }
    }

    /** One resource journal checkpoint entry: result of {@link #readForReplay}. */
    public static final class Replay {

        /**
         * Creates an empty replay accumulator.
         */
        public Replay() {
        }

        /** UPSERT bodies in file (commit) order: full object instances restored from the
         * journal's {@code object} blocks. */
        public final List<Object> upserts = new ArrayList<>();
        /** DELETE ids in file (commit) order, materialized to the resource's id type. */
        public final List<Object> deletes = new ArrayList<>();
        /** How many trailing records were discarded as a torn tail (crash mid-append);
         * {@code 0} for a clean journal. */
        public int discardedTailRecords = 0;
    }

    /**
     * Reads the journal for recovery replay. Records after the last sentinel (a torn tail
     * from a crash mid-append) are DISCARDED with a loud WARN — unlike a truncated base
     * file (S-1: loud failure), the journal tail is by-design discardable because the
     * interrupted commit was never reported durable. Replay itself is idempotent: records
     * are returned in file order (commit order), last write per id wins at the call site.
     *
     * @param file    the journal file to read (a missing file yields an empty replay)
     * @param idType  the id class of the resource, used to materialize DELETE record ids
     * @param factory the YAML I/O factory supplying the simple-type helper and the object
     *                body reader
     * @return the parsed replay: in-order upsert bodies, delete ids, and the count of
     *         discarded torn-tail records
     * @throws IOException when the file exists but cannot be read
     */
    public static Replay readForReplay(final Path file, final Class<?> idType,
                                       final XdStorageYamlIOFactory factory) throws IOException {
        return readForReplay(file, idType, factory.simpleTypeHelper());
    }

    private static Replay readForReplay(final Path file, final Class<?> idType,
                                        final IXdStorageSimpleTypeHelper helper) throws IOException {
        final Replay replay = new Replay();
        if (!Files.exists(file)) {
            return replay;
        }
        // task 51: pinned cross-OS
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file
                .toFile()), java.nio.charset.StandardCharsets.UTF_8), 1 << 16)) {
            final XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(reader);
            final List<Object[]> pending = new ArrayList<>();   // [Boolean delete, Object idOrObject]
            boolean tornTail = false;
            try {
                YamlLineToken token;
                while ((token = tokenizer.nextToken()) != null) {
                    if (XdStorageYamlTokenizer.EOF_SENTINEL_KEY.equals(token.key)) {
                        for (final Object[] entry : pending) {
                            if ((Boolean) entry[0]) {
                                replay.deletes.add(entry[1]);
                            } else {
                                replay.upserts.add(entry[1]);
                            }
                        }
                        pending.clear();
                        continue;
                    }
                    if (token.level == 0 && token.isListItem && "record".equals(token.key)) {
                        pending.add(readRecordBody(tokenizer, idType, helper));
                    }
                }
                tornTail = !pending.isEmpty();
            } catch (final Exception parseFailure) {
                // обрыв внутри тела записи (обрезанный блок) — остаток после последнего сентинела отбрасываем
                tornTail = true;
            }
            if (tornTail) {
                replay.discardedTailRecords = pending.size();
                log.warn("commit journal {} has a TORN TAIL (crash mid-append): discarding {} record(s) after the last "
                        + "EOF sentinel — the interrupted commit was never reported durable", file, pending.size());
            }
        }
        return replay;
    }

    /** Reads one `- record` body; the record token itself is already consumed. */
    private static Object[] readRecordBody(final XdStorageYamlTokenizer tokenizer,
                                           final Class<?> idType,
                                           final IXdStorageSimpleTypeHelper helper) throws Exception {
        long serial = 0;
        boolean delete = false;
        String idScalar = null;
        Object payload = null;
        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null && token.level >= 1) {
            token = tokenizer.nextToken();
            if (token.level != 1) {
                continue;   // глубже уровня записи (не должен встречаться вне object-блока)
            }
            if (XdStorageYamlTokenizer.EOF_SENTINEL_KEY.equals(token.key)) {
                throw new XdStorageIOException("record block cut before its body");
            }
            if ("serial".equals(token.key)) {
                serial = token.value != null ? Long.parseLong(token.value) : 0;
            } else if ("op".equals(token.key)) {
                delete = "DELETE".equals(token.value);
            } else if ("id".equals(token.key)) {
                idScalar = token.value;
            } else if ("object".equals(token.key) && token.value == null) {
                payload = XdStorageYamlBlockObjectsReader.readObject(tokenizer, token.level, helper);
            }
        }
        if (idScalar == null) {
            throw new XdStorageIOException("journal record without id");
        }
        if (delete) {
            return new Object[]{true, helper.simpleTypeFromString(idType, idScalar)};
        }
        if (payload == null) {
            throw new XdStorageIOException("UPSERT record without object body");
        }
        return new Object[]{false, payload};
    }

    /** Checkpoint: the journal is dropped after the base file rewrite has been written atomically.
     *
     * @param file the journal file to reset
     * @throws IOException when the deletion fails
     */
    public static void reset(final Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    /**
     * Current size of the journal file in bytes — the checkpoint threshold metric.
     *
     * @param file the journal file to measure
     * @return the file size in bytes, or {@code 0} when the journal does not exist
     */
    public static long sizeOf(final Path file) {
        return Files.exists(file) ? file.toFile().length() : 0L;
    }
}
