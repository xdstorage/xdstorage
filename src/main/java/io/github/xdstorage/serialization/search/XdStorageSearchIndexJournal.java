package io.github.xdstorage.serialization.search;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.xdstorage.serialization.yaml.XdStorageYamlEmitter;

/**
 * W-4b (docs/tasks/2.1.0/49): reader/writer for the search-index logical-op journal.
 * The journal lives in {@code <resource>/indexes/<base>-journal.yaml} and contains
 * append-only ADDKEY/DELKEY records replayed into the live canonical B+Tree at open.
 *
 * <p>Writer uses {@link XdStorageYamlEmitter} — the same emitter that produces
 * {@code XdStorageYamlCommitJournal} output (tab-indented YAML via {@code openBlock}/
 * {@code writeKey}/{@code writeScalar}/{@code closeBlock} calls), terminated by
 * the S-1 EOF sentinel ({@code # xdstorage-eof}).
 *
 * <p>Reader parses with a private {@code Tokenizer} inner class (same structure as the
 * {@code XdStorageYamlTokenizer} — tab nesting, list-item detection, quoted-string
 * unquoting). Records after the last sentinel (a torn tail from a crash mid-append)
 * are discarded with a WARN.
 *
 * @see io.github.xdstorage.search.XdStorageSearchIndexResource
 * @see io.github.xdstorage.serialization.XdStorageSearchIndexJournalRecord
 */
public final class XdStorageSearchIndexJournal {

    private static final Logger log = LoggerFactory.getLogger(XdStorageSearchIndexJournal.class);

    /** Holder of static journal I/O only — constructing it carries no state. */
    public XdStorageSearchIndexJournal() {
    }

    /** The S-1 EOF sentinel line (same as {@code XdStorageYamlTokenizer}). */
    private static final String EOF_SENTINEL_LINE = "# xdstorage-eof";

    // --- writer (XdStorageYamlEmitter style — same as XdStorageYamlCommitJournal.append) ---

    /**
     * Appends records to the journal file (creates parent directory). Each record
     * block is terminated by the S-1 EOF sentinel. Appended in file order.
     * Uses {@link XdStorageYamlEmitter} for structured tab-indented YAML output.
     *
     * @param file         the journal file to append to (created with parent dir)
     * @param commitSerial the serial of the commit being journaled
     * @param records      the records to append (in order)
     * @throws IOException when the file cannot be appended to
     */
    public static void append(final Path file, final long commitSerial,
                               final List<io.github.xdstorage.serialization.XdStorageSearchIndexJournalRecord> records)
            throws IOException {
        if (records.isEmpty()) {
            return;
        }
        final StringWriter out = new StringWriter();
        final XdStorageYamlEmitter emitter = new XdStorageYamlEmitter(out);
        for (final io.github.xdstorage.serialization.XdStorageSearchIndexJournalRecord rec : records) {
            emitter.openBlock("- record");
            emitter.writeKey("serial");
            emitter.writeScalar(String.valueOf(rec.serial));
            emitter.writeKey("treeId");
            emitter.writeScalar("'" + escapeScalar(rec.treeId) + "'");
            emitter.writeKey("op");
            emitter.writeScalar(rec.op);
            emitter.writeKey("key");
            emitter.writeScalar("'" + escapeScalar(rec.key) + "'");
            emitter.writeKey("objectId");
            emitter.writeScalar("'" + escapeScalar(rec.objectId) + "'");
            emitter.closeBlock();
        }
        emitter.writeEofSentinel();

        // Per-file append lock — same pattern as XdStorageYamlCommitJournal
        final Object appendLock = lockFor(file);
        synchronized (appendLock) {
            final java.io.File parent = file.getParent() != null ? file.getParent().toFile() : null;
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file.toFile(), true)) {   // true = append
                fos.write(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); // task 51: pinned cross-OS
            }
        }
    }

    /** One-append-per-resource lock (static, keyed by canonical file path).
     * Same pattern as {@link XdStorageYamlCommitJournal#lockFor(Path)}. */
    static Object lockFor(final Path file) {
        return LOCKS.computeIfAbsent(file, k -> new Object());
    }

    private static final java.util.concurrent.ConcurrentHashMap<Path, Object> LOCKS
            = new java.util.concurrent.ConcurrentHashMap<>();

    // --- scalar escaping (same convention as XdStorageYamlCommitJournal) ---

    /** Escapes a YAML scalar value (wraps in single quotes; inner single quotes doubled).
     * Same convention as {@code XdStorageYamlCommitJournal}. */
    static String escapeScalar(final String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    // --- reader ---

    /** Reads the journal for recovery replay. Records after the last sentinel are
     * DISCARDED with a WARN (crash mid-append). Returns records in file order.
     *
     * @param file the journal file to read (a missing file yields an empty list)
     * @return parsed records in file (commit) order
     * @throws IOException when the file exists but cannot be read
     */
    public static List<io.github.xdstorage.serialization.XdStorageSearchIndexJournalRecord> readForReplay(final Path
            file)
            throws IOException {
        final List<io.github.xdstorage.serialization.XdStorageSearchIndexJournalRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        // task 51: pinned cross-OS
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file
                .toFile()), java.nio.charset.StandardCharsets.UTF_8), 1 << 16)) {
            final Tokenizer tokenizer = new Tokenizer(reader);
            final List<JournalRecord> pending = new ArrayList<>();
            boolean tornTail = false;
            try {
                Tokenizer.Token token;
                while ((token = tokenizer.nextToken()) != null) {
                    if (token.isSentinel) {
                        // EOF sentinel
                        for (final JournalRecord jr : pending) {
                            records.add(new io.github.xdstorage.serialization.XdStorageSearchIndexJournalRecord(
                                    jr.serial, jr.treeId, jr.op, jr.key, jr.objectId));
                        }
                        pending.clear();
                        continue;
                    }
                    if (token.level == 0 && token.isListItem && "record".equals(token.key)) {
                        pending.add(readRecordBody(tokenizer));
                    }
                }
                tornTail = !pending.isEmpty();
            } catch (final Exception parseFailure) {
                tornTail = true;
            }
            if (tornTail) {
                log.warn("search-index commit journal {} has a TORN TAIL (crash mid-append): discarding {} record(s) " +
                        "after the last EOF sentinel",
                        file, pending.size());
            }
        }
        return records;
    }

    /** Reads one `- record` body (token already consumed). */
    private static JournalRecord readRecordBody(final Tokenizer tokenizer) throws Exception {
        long serial = 0;
        String treeId = null;
        String op = null;
        String key = null;
        String objectId = null;

        Tokenizer.Token token;
        while ((token = tokenizer.peekToken()) != null && token.level >= 1) {
            token = tokenizer.nextToken();
            if (token.level != 1) {
                continue;
            }
            if (token.level == 1 && "serial".equals(token.key)) {
                serial = token.value != null ? Long.parseLong(token.value) : 0;
            } else if ("treeId".equals(token.key)) {
                treeId = token.value != null ? unquote(token.value) : null;
            } else if ("op".equals(token.key)) {
                op = token.value;
            } else if ("key".equals(token.key)) {
                key = token.value != null ? unquote(token.value) : null;
            } else if ("objectId".equals(token.key)) {
                objectId = token.value != null ? unquote(token.value) : null;
            }
        }
        return new JournalRecord(serial, treeId, op, key, objectId);
    }

    /** Removes surrounding single quotes from a scalar token value (handles '' -> '). */
    private static String unquote(final String v) {
        if (v == null || v.length() < 2 || v.charAt(0) != '\'' || v.charAt(v.length() - 1) != '\'') {
            return v;
        }
        return v.substring(1, v.length() - 1).replace("''", "'");
    }

    // --- internal record ---

    /** A single record block in the journal (parsed from the file). */
    private static final class JournalRecord {
        final long serial;
        final String treeId;
        final String op;
        final String key;
        final String objectId;

        JournalRecord(final long serial, final String treeId, final String op,
                      final String key, final String objectId) {
            this.serial = serial;
            this.treeId = treeId;
            this.op = op;
            this.key = key;
            this.objectId = objectId;
        }
    }

    // --- tokenizer (same structure as XdStorageYamlTokenizer, local copy) ---

    /** Tokenizer for the search-index journal reader — a lightweight YAML tokenizer
     * that understands the block-level structure needed for record blocks.
     * Same logic as {@code XdStorageYamlTokenizer}. */
    static final class Tokenizer {

        /** A single YAML token produced by this tokenizer. */
        static final class Token {
            final int level;
            final String key;
            final String value;
            final boolean isListItem;
            boolean isSentinel;

            Token(final int level, final String key, final String value, final boolean isListItem) {
                this.level = level; this.key = key; this.value = value; this.isListItem = isListItem;
            }

            Token(final int level, final String key, final String value) {
                this(level, key, value, false); this.isSentinel = true;
            }
        }

        private final BufferedReader reader;
        private Token peeked = null;

        Tokenizer(final BufferedReader reader) {
            this.reader = reader;
        }

        Token peekToken() throws IOException {
            if (peeked == null) {
                peeked = nextTokenFromStream();
            }
            return peeked;
        }

        Token nextToken() throws IOException {
            if (peeked != null) {
                Token res = peeked;
                peeked = null;
                return res;
            }
            return nextTokenFromStream();
        }

        private Token nextTokenFromStream() throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // EOF sentinel
                if (EOF_SENTINEL_LINE.equals(trimmed)) {
                    return new Token(0, "xdstorage-eof", null, false);
                }
                // Skip comment lines
                if (trimmed.startsWith("#")) continue;

                // Count tab nesting
                int level = 0;
                while (level < line.length() && line.charAt(level) == '\t') level++;
                String cleanLine = line.substring(level).trim();

                // List item detection
                boolean isListItem = false;
                if (cleanLine.startsWith("- ")) {
                    isListItem = true;
                    cleanLine = cleanLine.substring(2).trim();
                }

                // Split by first ':'
                int colonIdx = cleanLine.indexOf(':');
                String key = cleanLine;
                String value = null;
                if (colonIdx != -1) {
                    key = cleanLine.substring(0, colonIdx).trim();
                    String raw = cleanLine.substring(colonIdx + 1).trim();
                    if (!raw.isEmpty()) {
                        value = parseValue(raw);
                    }
                } else if (isListItem) {
                    value = parseValue(cleanLine);
                    key = value == null ? cleanLine : null;
                }
                return new Token(level, key, value, isListItem);
            }
            return null;
        }

        private String parseValue(final String raw) {
            if ("null".equals(raw) || "~".equals(raw)) return null;
            if (raw.startsWith("'") && raw.endsWith("'") && raw.length() >= 2) {
                return raw.substring(1, raw.length() - 1).replace("''", "'");
            }
            return raw;
        }
    }
}
