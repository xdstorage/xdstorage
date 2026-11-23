package io.github.xdstorage.serialization.yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * High-performance line-oriented, lock-free tokenizer for the engine's tab-indented
 * YAML dialect. Each physical line becomes one {@link YamlLineToken} carrying its tab
 * nesting level, key, raw value and list-item flag; blank lines and {@code #} comment
 * lines are skipped — except the S-1 EOF sentinel, which is returned as a level-0 token
 * so the high-level reader can distinguish a clean end of file from a truncation. A
 * one-token look-ahead buffer ({@code peekToken}) backs the recursive-descent readers.
 *
 * <p>Thread-safety: an instance is confined to the reader that created it and is not
 * safe for concurrent use. Engine-internal — not part of the public API.</p>
 */
public class XdStorageYamlTokenizer {

    /**
     * S-1: последний маркер файла, который райтер ставит после корневого блока.
     * Его отсутствие = файл обрезан (сбой/внешнее вмешательство) — ридер обязан
     * отказать в загрузке, а не молча отдать частичную базу.
     * Формат — строка комментария: старые версии движка (и любые YAML-читатели)
     * пропускают ее безвредно.
     */
    public static final String EOF_SENTINEL_LINE = "# xdstorage-eof";

    /** The sentinel's token key: the level-0 pseudo-token returned by this tokenizer
     * when {@link #EOF_SENTINEL_LINE} is read, letting the high-level reader detect a
     * clean EOF explicitly. */
    public static final String EOF_SENTINEL_KEY = "xdstorage-eof";

    /**
     * WAL (docs/tasks/2.2.0/06): the prefix of the BATCH TRAILER — a comment line a commit
     * journal writes immediately before its sentinel, naming the commit serial and the
     * transaction the batch belongs to ({@code # xdstorage-meta serial=17 tx=<id>}). It is a
     * comment on purpose: a reader that predates it skips the line and still finds the plain
     * sentinel, so a journal written by this version replays under an older one unchanged.
     * The tokenizer attaches it to the sentinel token's {@code value}.
     */
    public static final String TRAILER_PREFIX = "# xdstorage-meta ";

    private final BufferedReader reader;

    // Внутренний буфер для реализации заглядывания вперёд (Look-Ahead Barrier)
    private YamlLineToken peekedToken = null;

    /** The trailer line read last, when nothing but blank and comment lines followed it. */
    private String pendingTrailer = null;

    /**
     * Creates the tokenizer over the given stream, wrapping a non-buffered reader into a
     * {@code BufferedReader} for line reads.
     *
     * @param reader the character stream of the resource file to tokenize
     */
    public XdStorageYamlTokenizer(final Reader reader) {
        this.reader = (reader instanceof BufferedReader) ?
                (BufferedReader) reader : new BufferedReader(reader);
    }

    /**
     * Позволяет высокоуровневому демаршаллеру заглянуть на один токен вперёд,
     * не извлекая его физически из конвейера чтения. Ключевой элемент рекурсивного спуска!
     *
     * @return the next token without consuming it ({@code null} at end of file); repeated
     *         calls return the same buffered token until {@link #nextToken()} is used
     * @throws IOException when the underlying stream cannot be read
     */
    public YamlLineToken peekToken() throws IOException {
        if (peekedToken == null) {
            peekedToken = readNextTokenFromServerStream();
        }
        return peekedToken;
    }

    /**
     * Потребляет и возвращает следующий валидный токен из YAML-файла.
     * Если перед этим вызывался peekToken(), бесшовно отдаёт объект из буфера.
     *
     * @return the consumed next token ({@code null} at end of file)
     * @throws IOException when the underlying stream cannot be read
     */
    public YamlLineToken nextToken() throws IOException {
        if (peekedToken != null) {
            YamlLineToken res = peekedToken;
            peekedToken = null; // Очищаем буфер заглядывания
            return res;
        }
        return readNextTokenFromServerStream();
    }

    /**
     * Внутренний прямолинейный алгоритм посимвольного подсчёта табуляций и сплита строк.
     */
    private YamlLineToken readNextTokenFromServerStream() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            // 1. Мгновенно отсекаем пустые строки и комментарии СУБД
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                // S-1: сентинел конца файла НЕ пропускается — возвращается как токен уровня 0,
                // чтобы высокий ридер мог отличить честный конец файла от обрыва
                if (EOF_SENTINEL_LINE.equals(trimmed)) {
                    return sentinelToken(pendingTrailer);
                }
                // WAL (task 06): the enriched spelling on the sentinel line itself — read, never
                // written by this version (the trailer line is the forward-safe form)
                if (trimmed.startsWith(EOF_SENTINEL_LINE + " ")) {
                    return sentinelToken(trimmed.substring(EOF_SENTINEL_LINE.length()).trim());
                }
                if (trimmed.startsWith(TRAILER_PREFIX)) {
                    pendingTrailer = trimmed.substring(TRAILER_PREFIX.length()).trim();
                }
                continue;
            }
            pendingTrailer = null;   // a content line ends the trailer's reach

            // 2. МOЛНИЕНОСНЫЙ ПОДСЧЁТ ВЛОЖЕННОСТИ:
            // Считаем количество префиксных '\t' в начале физической строки
            int level = 0;
            while (level < line.length() && line.charAt(level) == '\t') {
                level++;
            }

            // 3. Срезаем отступы за один проход
            String cleanLine = line.substring(level).trim();

            // 4. Детектируем и вырезаем маркер элемента коллекции "- "
            boolean isListItem = false;
            if (cleanLine.startsWith("- ")) {
                isListItem = true;
                cleanLine = cleanLine.substring(2).trim();
            }

            // 5. Разрезаем чистую строку по первому символу двоеточия ':'
            int colonIdx = cleanLine.indexOf(':');
            String key = cleanLine;
            String value = null;

            if (colonIdx != -1) {
                key = cleanLine.substring(0, colonIdx).trim();
                String rawValue = cleanLine.substring(colonIdx + 1).trim();
                if (!rawValue.isEmpty()) {
                    value = parseRawValue(rawValue);
                }
            } else {
                // Если двоеточия нет, это может быть плоский скалярный элемент коллекции (например, "- null")
                if (isListItem) {
                    value = parseRawValue(cleanLine);
                    key = value == null ? cleanLine : null;
                }
            }

            return new YamlLineToken(level, key, value, isListItem);
        }
        return null; // Конец файла (EOF)
    }

    /** The sentinel pseudo-token; {@code trailer} is the batch trailer before it, when there is one. */
    private static YamlLineToken sentinelToken(final String trailer) {
        return new YamlLineToken(0, EOF_SENTINEL_KEY, trailer == null || trailer.isEmpty() ? null : trailer, false);
    }

    /**
     * After a parse failure: what the rest of the stream still holds, counted on the RAW lines
     * (nothing is tokenized, so a second bad value cannot mask the first). The buffered
     * look-ahead token counts first. Used to tell a torn tail from a corruption in the middle
     * of a journal: a sentinel further on means later batches exist.
     *
     * @return the record starts ({@code - record} lines), the EOF sentinels — the first of
     *         them closes the batch the failure sits in — and the first batch trailer further on
     * @throws IOException when the underlying stream cannot be read
     */
    public Remainder scanRemainder() throws IOException {
        int records = 0;
        int sentinels = 0;
        String trailer = null;
        final YamlLineToken buffered = peekedToken;
        peekedToken = null;
        if (buffered != null) {
            if (EOF_SENTINEL_KEY.equals(buffered.key)) {
                sentinels++;
            } else if (buffered.level == 0 && buffered.isListItem) {
                records++;
            }
        }
        String line;
        while ((line = reader.readLine()) != null) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final boolean sentinel = trimmed.equals(EOF_SENTINEL_LINE)
                    || trimmed.startsWith(EOF_SENTINEL_LINE + " ");
            if (sentinel) {
                sentinels++;
            } else if (trimmed.startsWith(TRAILER_PREFIX)) {
                if (trailer == null) {
                    trailer = trimmed.substring(TRAILER_PREFIX.length()).trim();
                }
            } else if (trimmed.startsWith("#")) {
                continue;
            } else if (line.startsWith("- ")) {
                records++;   // a record start sits at level 0
            }
        }
        return new Remainder(records, sentinels, trailer);
    }

    /** What a {@link #scanRemainder()} found after a parse failure. */
    public static final class Remainder {
        /** Record starts ({@code - record} lines) left in the stream. */
        public final int records;
        /** EOF sentinels left in the stream; at least one means the damaged batch is closed. */
        public final int sentinels;
        /** The first batch trailer further on, or {@code null} when none precedes a sentinel. */
        public final String trailer;

        Remainder(final int records, final int sentinels, final String trailer) {
            this.records = records;
            this.sentinels = sentinels;
            this.trailer = trailer;
        }
    }

    /**
     * По канону нашего соглашения: распаковывает одинарные кавычки '' -> '
     * и распознает явные маркеры пустых значений.
     */
    private String parseRawValue(final String rawValue) {
        if ("null".equals(rawValue) || "~".equals(rawValue)) {
            return null;
        }
        if (rawValue.charAt(0) == '"') {
            // YAMLSTR (docs/tasks/2.2.0/40): the line-safe scalar form — only a string WITH a
            // line break is written double-quoted; no earlier version wrote a value that
            // starts with a double quote, so the form is recognized by its first character.
            if (rawValue.length() < 2 || rawValue.charAt(rawValue.length() - 1) != '"') {
                throw new io.github.xdstorage.exceptions.XdStorageIOException("an unterminated double-quoted scalar "
                        + "(corrupt or hand-edited value): " + rawValue);
            }
            return XdStorageYamlScalar.unescapeDoubleQuoted(rawValue.substring(1, rawValue.length() - 1));
        }
        // Распаковка одинарных кавычек по твоему алгоритму без прикрас
        if (rawValue.startsWith("'") && rawValue.endsWith("'") && rawValue.length() >= 2) {
            String content = rawValue.substring(1, rawValue.length() - 1);
            return content.replaceAll("''", "'");
        }
        return rawValue;
    }
}
