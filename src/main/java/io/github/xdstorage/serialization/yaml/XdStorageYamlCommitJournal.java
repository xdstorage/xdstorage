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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>WAL (docs/tasks/2.2.0/06) — the batch trailer and the two failure cases. Every batch is
 * followed by a trailer comment naming its commit serial and its transaction
 * ({@code # xdstorage-meta serial=17 tx=<id>}) and then by the plain sentinel; the tokenizer
 * hands the trailer to the reader with the sentinel, and a reader that predates it skips the
 * line as a comment. The reader tells two failures apart that used to share one silent
 * answer:</p>
 * <ul>
 *   <li><b>A torn tail</b> — a physical truncation: the last batch has no sentinel after it,
 *       whether the file simply ends or a value inside it fails to parse. Discarded, as
 *       before — now reporting the exact commit serials lost and their count.</li>
 *   <li><b>A corruption in the middle</b> — a record fails to parse while further complete
 *       batches follow it in the file. This is damage, not an interrupted commit: the replay
 *       REFUSES, naming what was read and what was not. (It used to be swallowed as a torn
 *       tail, dropping every later commit in the journal with one WARN.)</li>
 * </ul>
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
        appendRedo(file, commitSerial, null, changes, services, factory, durable);
    }

    /**
     * Appends REDO records for a successfully prepared commit, naming the transaction the
     * batch belongs to in its trailer (WAL, task 06).
     *
     * @param file         the journal file to append to (created with its parent directory)
     * @param commitSerial the serial of the commit being journaled
     * @param transactionId the id of the committing transaction, written to the batch trailer
     *                      ({@code null} for a batch without a transaction identity)
     * @param changes      the commit's object changes (entries without an id are skipped)
     * @param services     the storage services locator (used by the object body encoder)
     * @param factory      the YAML I/O factory supplying the simple-type helper and id generator
     * @param durable      {@code true} to fsync the append ({@code FileDescriptor.sync}) — the
     *                     journal is the durability point while it holds the commit
     * @throws IOException        when the file cannot be appended to
     * @throws io.github.xdstorage.exceptions.XdStorageException when id generation fails while
     *         encoding an object body
     */
    public static void appendRedo(final Path file, final long commitSerial, final String transactionId,
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
        append(file, records, transactionId, services, factory, durable);
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
        append(file, records, null, services, factory, durable);
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

    /** One locked append: records, the batch trailer and the trailing sentinel — O(delta) bytes. */
    private static void append(final Path file, final List<Record> records, final String transactionId,
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
        long maxSerial = 0L;
        for (final Record record : records) {
            emitter.openBlock("- record");
            emitter.writeKey("serial");
            emitter.writeScalar(String.valueOf(record.serial));
            emitter.writeKey("op");
            emitter.writeScalar(record.delete ? "DELETE" : "UPSERT");
            emitter.writeKey("id");
            emitter.writeScalar(XdStorageYamlScalar.token(helper.simpleTypeToString(record.id)));
            if (!record.delete && record.object != null) {
                emitter.openBlock("object");
                XdStorageYamlBlockObjectsWriter.writeObjectData(record.object, emitter, services, helper, idGenerator);
                emitter.closeBlock();
            }
            emitter.closeBlock();
            maxSerial = Math.max(maxSerial, record.serial);
        }
        emitter.writeEofSentinel(trailer(maxSerial, transactionId));

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
        /** WAL (task 06): the exact commit serials lost with the torn tail — the lowest serial
         * among the discarded redo records. {@code 0} when nothing was discarded or only
         * compensation records were. */
        public long discardedSerialFrom = 0L;
        /** WAL (task 06): the highest serial among the discarded redo records — the other end
         * of {@link #discardedSerialFrom}. */
        public long discardedSerialTo = 0L;
        /** WAL (task 06): how many of the discarded records were compensation records (serial
         * {@code 0} — the undo of a failed phase 1, never a reported commit). */
        public int discardedCompensationRecords = 0;
        /** WAL (task 06): how many of the discarded records were cut before their serial line
         * could be read — their commit cannot be named. */
        public int discardedUnnumberedRecords = 0;
        /** WAL (task 06): the complete batches (sentinels) the journal held. */
        public int replayedBatches = 0;
        /** WAL (task 06): the highest commit serial of a replayed batch — the journal's durable
         * high-water mark; {@code 0} when no redo batch was replayed. */
        public long lastReplayedSerial = 0L;
        /** WAL (task 06): the transactions the replayed redo batches belong to, in file order. */
        public final List<String> replayedTransactions = new ArrayList<>();
    }

    /** One parsed record of the batch being read, before its sentinel says it is complete. */
    private static final class Pending {
        final boolean delete;
        /** The record's commit serial ({@code 0} for compensation), or {@code null} when the
         * record was cut before its serial line could be read. */
        final Long serial;
        /** The materialized DELETE id, or the UPSERT body. */
        final Object idOrObject;

        Pending(final boolean delete, final Long serial, final Object idOrObject) {
            this.delete = delete;
            this.serial = serial;
            this.idOrObject = idOrObject;
        }
    }

    /** A record whose body could not be read — it still belongs to the count of what was lost. */
    private static final class UnreadableRecord extends Exception {
        private static final long serialVersionUID = 1L;
        /** The record's serial when its line was read, else {@code null}. */
        final Long serial;

        UnreadableRecord(final Long serial, final Throwable cause) {
            super(cause);
            this.serial = serial;
        }
    }

    /** A damage this reader diagnosed itself — never mistaken for a plain parse failure. */
    private static final class Corruption extends XdStorageIOException {
        private static final long serialVersionUID = 1L;

        Corruption(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Reads the journal for recovery replay. Complete batches (a sentinel after them) are
     * returned in file (commit) order. A torn tail — the last batch has no sentinel after it —
     * is DISCARDED with a loud WARN, now naming the exact commit serials lost: the interrupted
     * commit was never reported durable. A failure with further complete batches after it is
     * damage in the middle of the file, not an interrupted append: the read throws
     * {@code XdStorageIOException} and nothing of the journal is applied. Replay itself is
     * idempotent: last write per id wins at the call site.
     *
     * @param file    the journal file to read (a missing file yields an empty replay)
     * @param idType  the id class of the resource, used to materialize DELETE record ids
     * @param factory the YAML I/O factory supplying the simple-type helper and the object
     *                body reader
     * @return the parsed replay: in-order upsert bodies, delete ids, the torn-tail record
     *         count and the exact serials lost
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
            final List<Pending> pending = new ArrayList<>();   // the batch since the last sentinel
            try {
                YamlLineToken token;
                while ((token = tokenizer.nextToken()) != null) {
                    if (XdStorageYamlTokenizer.EOF_SENTINEL_KEY.equals(token.key)) {
                        commitBatch(replay, pending, token.value, file);
                        continue;
                    }
                    if (token.level == 0 && token.isListItem && "record".equals(token.key)) {
                        pending.add(readRecordBody(tokenizer, idType, helper));
                    }
                }
            } catch (final Corruption diagnosed) {
                throw diagnosed;   // already named — a batch disagreed with its own trailer
            } catch (final Exception parseFailure) {
                // WAL (docs/tasks/2.2.0/06): the old code answered every failure with "torn
                // tail" and dropped the rest of the file unseen. A sentinel further on means
                // later complete batches exist: this is damage in the middle, not an
                // interrupted append.
                final XdStorageYamlTokenizer.Remainder rest = scanRest(tokenizer, file, parseFailure);
                if (rest.sentinels > 0) {
                    throw new Corruption(corruptionMessage(file, replay, pending, rest, parseFailure), parseFailure);
                }
                discardTail(replay, pending, file, parseFailure,
                        parseFailure instanceof UnreadableRecord ? ((UnreadableRecord) parseFailure).serial : null,
                        parseFailure instanceof UnreadableRecord);
                return replay;
            }
            discardTail(replay, pending, file, null, null, false);
        }
        return replay;
    }

    /** Moves the batch before a sentinel into the replay, after checking it against its trailer. */
    private static void commitBatch(final Replay replay, final List<Pending> pending, final String trailer,
                                    final Path file) {
        long maxSerial = 0L;
        for (final Pending record : pending) {
            if (record.serial != null) {
                maxSerial = Math.max(maxSerial, record.serial);
            }
        }
        final Trailer parsed = Trailer.parse(trailer);
        if (parsed != null && parsed.serial != null && parsed.serial != maxSerial) {
            throw new Corruption("commit journal " + file + " is corrupt in batch "
                    + (replay.replayedBatches + 1) + ": its trailer declares serial=" + parsed.serial
                    + " but the batch's records carry up to serial " + maxSerial + " (a trailer and its records "
                    + "are written by one append — they cannot disagree unless the file was altered)", null);
        }
        for (final Pending record : pending) {
            if (record.delete) {
                replay.deletes.add(record.idOrObject);
            } else {
                replay.upserts.add(record.idOrObject);
            }
        }
        replay.replayedBatches++;
        replay.lastReplayedSerial = Math.max(replay.lastReplayedSerial, maxSerial);
        if (parsed != null && parsed.transaction != null && maxSerial > 0L) {
            replay.replayedTransactions.add(parsed.transaction);
        }
        pending.clear();
    }

    /** Counts what is left of the file after a failure; an unreadable rest is itself damage. */
    private static XdStorageYamlTokenizer.Remainder scanRest(final XdStorageYamlTokenizer tokenizer, final Path file,
                                                              final Exception parseFailure) throws IOException {
        try {
            return tokenizer.scanRemainder();
        } catch (final IOException unreadable) {
            throw new Corruption("commit journal " + file + " cannot be classified after a parse failure ("
                    + parseFailure + "): the rest of the file is unreadable (" + unreadable + ")", unreadable);
        }
    }

    private static String corruptionMessage(final Path file, final Replay replay, final List<Pending> pending,
                                            final XdStorageYamlTokenizer.Remainder rest, final Exception cause) {
        final String batch = rest.trailer == null ? "" : " [" + rest.trailer + "]";
        final boolean cutRecord = cause instanceof UnreadableRecord;
        return "commit journal " + file + " is CORRUPT in batch " + (replay.replayedBatches + 1) + batch + " (after "
                + replay.replayedBatches + " complete batch(es)): " + cause + ". This is not a torn tail — the "
                + "damaged batch is closed by its own sentinel and " + (rest.sentinels - 1) + " later batch(es) with "
                + rest.records + " further record(s) follow. Refusing to replay the journal — the "
                + (pending.size() + (cutRecord ? 1 : 0)) + " record(s) of the damaged batch and the "
                + replay.upserts.size() + " upsert(s) / " + replay.deletes.size() + " delete(s) read before it "
                + "are not applied. Restore the folder from a backup, or rebuild this resource's journal from "
                + "its base file knowingly";
    }

    /**
     * The torn tail: nothing after the failure (or the file simply ended) — discard, and say
     * exactly what was lost, including a record cut before it could be read.
     *
     * @param unreadable       the serial of the record whose body failed to parse, when one did
     * @param recordWasCut     whether such a record exists
     */
    private static void discardTail(final Replay replay, final List<Pending> pending, final Path file,
                                    final Exception cause, final Long unreadable, final boolean recordWasCut) {
        if (pending.isEmpty() && !recordWasCut) {
            return;
        }
        final List<Long> lost = new ArrayList<>();
        int compensation = 0;
        int unnumbered = 0;
        for (final Pending record : pending) {
            if (record.serial == null) {
                unnumbered++;
            } else if (record.serial == 0L) {
                compensation++;
            } else {
                lost.add(record.serial);
            }
        }
        if (recordWasCut) {
            if (unreadable == null) {
                unnumbered++;
            } else if (unreadable == 0L) {
                compensation++;
            } else {
                lost.add(unreadable);
            }
        }
        replay.discardedTailRecords = pending.size() + (recordWasCut ? 1 : 0);
        replay.discardedCompensationRecords = compensation;
        replay.discardedUnnumberedRecords = unnumbered;
        for (final long serial : lost) {
            if (serial < replay.discardedSerialFrom || replay.discardedSerialFrom == 0L) {
                replay.discardedSerialFrom = serial;
            }
            replay.discardedSerialTo = Math.max(replay.discardedSerialTo, serial);
        }
        log.warn("commit journal {} has a TORN TAIL (crash mid-append): discarding {} record(s) after the last "
                + "EOF sentinel — lost: {} — the interrupted commit was never reported durable{}", file,
                replay.discardedTailRecords, lostText(replay), cause == null ? ""
                        : " (a record inside the uncompleted tail does not parse: " + cause + ")");
    }

    private static String lostText(final Replay replay) {
        final List<String> parts = new ArrayList<>();
        if (replay.discardedSerialFrom == 0L) {
            parts.add("no redo record");
        } else {
            parts.add(replay.discardedSerialFrom == replay.discardedSerialTo
                    ? "commit serial " + replay.discardedSerialFrom
                    : "commit serials " + replay.discardedSerialFrom + ".." + replay.discardedSerialTo);
        }
        if (replay.discardedCompensationRecords > 0) {
            parts.add(replay.discardedCompensationRecords + " compensation record(s)");
        }
        if (replay.discardedUnnumberedRecords > 0) {
            parts.add(replay.discardedUnnumberedRecords + " record(s) cut before their serial line");
        }
        return String.join(" + ", parts);
    }

    /** The batch trailer's two fields, either of which may be absent on an older journal. */
    private static final class Trailer {
        final Long serial;
        final String transaction;

        private Trailer(final Long serial, final String transaction) {
            this.serial = serial;
            this.transaction = transaction;
        }

        /** Parses {@code serial=<n> tx=<id>}; unknown fields are ignored, a missing trailer is {@code null}. */
        static Trailer parse(final String trailer) {
            if (trailer == null) {
                return null;
            }
            Long serial = null;
            String transaction = null;
            for (final String field : trailer.split("\\s+")) {
                final int equals = field.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                final String value = field.substring(equals + 1);
                if (field.startsWith("serial=") && !value.isEmpty()) {
                    try {
                        serial = Long.valueOf(value);
                    } catch (final NumberFormatException notANumber) {
                        // an unreadable serial is the same as none: the batch's own records decide
                    }
                } else if (field.startsWith("tx=") && !value.isEmpty()) {
                    transaction = value.startsWith("'") && value.endsWith("'") && value.length() >= 2
                            ? value.substring(1, value.length() - 1).replace("''", "'") : value;
                }
            }
            return new Trailer(serial, transaction);
        }
    }

    /** The trailer of one appended batch: its highest serial, and its transaction when it has one. */
    private static String trailer(final long maxSerial, final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return "serial=" + maxSerial;
        }
        final boolean plain = transactionId.matches("[A-Za-z0-9._-]+");
        return "serial=" + maxSerial + " tx=" + (plain ? transactionId
                : XdStorageYamlScalar.singleQuoted(transactionId));
    }

    /** Reads one `- record` body; the record token itself is already consumed. */
    private static Pending readRecordBody(final XdStorageYamlTokenizer tokenizer,
                                          final Class<?> idType,
                                          final IXdStorageSimpleTypeHelper helper) throws UnreadableRecord {
        Long serial = null;
        try {
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
                    serial = token.value != null ? Long.parseLong(token.value) : 0L;
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
                return new Pending(true, serial, helper.simpleTypeFromString(idType, idScalar));
            }
            if (payload == null) {
                throw new XdStorageIOException("UPSERT record without object body");
            }
            return new Pending(false, serial, payload);
        } catch (final Exception failure) {
            // WAL (task 06): the caller must still count this record as lost, and name its
            // commit when the serial line was read before the failure
            throw new UnreadableRecord(serial, failure);
        }
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
