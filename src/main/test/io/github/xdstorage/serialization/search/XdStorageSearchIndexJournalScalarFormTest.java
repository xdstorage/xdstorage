package io.github.xdstorage.serialization.search;

import io.github.xdstorage.serialization.XdStorageSearchIndexJournalRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YAMLSTR (docs/tasks/2.2.0/40) — the search-index journal stores its keys through the same
 * quoting rule as every other YAML file: an indexed value with a line break used to split the
 * record in the journal exactly as it split a data record.
 */
@DisplayName("YAMLSTR (2.2.0/40): журнал поискового индекса пишет ключи по общему правилу")
public class XdStorageSearchIndexJournalScalarFormTest {

    @TempDir
    Path folder;

    private static List<XdStorageSearchIndexJournalRecord> record(final String key, final String objectId) {
        return Collections.singletonList(new XdStorageSearchIndexJournalRecord(1L, "idx", "ADDKEY", key, objectId));
    }

    private String appendAndRead(final String key, final String objectId) throws Exception {
        final Path journal = folder.resolve("name_idx-journal.yaml");
        XdStorageSearchIndexJournal.append(journal, 1L, record(key, objectId));
        return new String(Files.readAllBytes(journal), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Ключ и идентификатор с разрывом строки: запись журнала остаётся одной строкой на скаляр")
    public void aKeyWithALineBreakStaysOnOneLine() throws Exception {
        final String written = appendAndRead("first line\nsecond line \"quoted\" \\ backslash", "id\n1");
        assertFalse(written.contains("key: 'first line"), "a raw line break must not split the key: " + written);
        assertTrue(written.contains("key: \"first line\\nsecond line \\\"quoted\\\" \\\\ backslash\""),
                "the line-safe form: " + written);
        assertTrue(written.contains("objectId: \"id\\n1\""), "the line-safe form: " + written);
        assertEquals(8, written.split("\r\n", -1).length - 1,
                "one physical line per scalar: six record lines + the blank line + the sentinel");
    }

    @Test
    @DisplayName("Ключ без разрыва строки пишется как раньше: одинарные кавычки, апостроф удваивается")
    public void aSingleLineKeyKeepsTheOriginalForm() throws Exception {
        final String written = appendAndRead("it's null", "7");
        assertTrue(written.contains("key: 'it''s null'"), written);
        assertTrue(written.contains("objectId: '7'"), written);
    }

    @Test
    @DisplayName("Собственный токенайзер журнала читает обе формы")
    public void theJournalTokenizerReadsBothForms() throws Exception {
        final String written = appendAndRead("first line\nsecond line", "it's");
        final XdStorageSearchIndexJournal.Tokenizer tokenizer =
                new XdStorageSearchIndexJournal.Tokenizer(new BufferedReader(new StringReader(written)));
        final List<String> values = Arrays.asList("idx", "ADDKEY", "first line\nsecond line", "it's");
        int seen = 0;
        for (XdStorageSearchIndexJournal.Tokenizer.Token token = tokenizer.nextToken(); token != null;
             token = tokenizer.nextToken()) {
            if (token.level != 1 || token.value == null || "serial".equals(token.key)) {
                continue;
            }
            assertTrue(seen < values.size(), "an unexpected value token: " + token.value);
            assertEquals(values.get(seen), token.value, "value token #" + seen);
            seen++;
        }
        assertEquals(values.size(), seen, "every scalar of the record was read");
    }
}
