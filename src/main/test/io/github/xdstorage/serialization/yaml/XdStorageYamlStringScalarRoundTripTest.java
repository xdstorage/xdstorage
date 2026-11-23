package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.services.XdStorageServicesLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * YAMLSTR (docs/tasks/2.2.0/40) — a string survives a round trip through the YAML format in
 * every position the format has, whatever it contains. Two cases used to lose data silently:
 * a line break inside a string split the record (the fields after it were lost or shifted),
 * and the four characters {@code null} read back as {@code null} — as a field value, as a
 * list element, as a map value, and as a map KEY (the whole entry was dropped).
 */
@DisplayName("YAMLSTR (2.2.0/40): строка переживает round-trip в любой позиции формата")
public class XdStorageYamlStringScalarRoundTripTest {

    /** Every string-bearing position of the format in one object. */
    public static class TextHost {
        @XdStorageObjectId
        private Long id;
        private String text;
        private char letter;
        private List<String> tags;
        private Map<String, String> meta;
        private String[] labels;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public char getLetter() { return letter; }
        public void setLetter(char letter) { this.letter = letter; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public Map<String, String> getMeta() { return meta; }
        public void setMeta(Map<String, String> meta) { this.meta = meta; }
        public String[] getLabels() { return labels; }
        public void setLabels(String[] labels) { this.labels = labels; }
    }

    /**
     * The corpus: what real text contains. The line-break rows are the defect of this task;
     * the rest are the neighbours a fix must not break (quotes, backslashes, the null
     * markers, the tokenizer's own sigils, whitespace, non-ASCII).
     */
    static Stream<String> strings() {
        return Stream.of(
                "", " ", "   ", "\t", "  padded  ",
                "null", "~", "NULL", "nUll", " null ",
                "'", "''", "'quoted'", "it's", "O'Brien", "\"", "\"quoted\"", "'\"both\"'", "\"\\n\"",
                "\\", "a\\b", "C:\\new\\table", "\\n", "\\r\\n", "\\\\",
                "\n", "\r", "\r\n", "line one\nline two", "line one\r\nline two", "a\n\n\nb", "end\n",
                "\nstart", "tab\there", "tab\tend\t",
                "#hash", "# xdstorage-eof", "- item:", "- dash", ": colon", "key: value", "value: 'x'",
                "class: 'java.lang.String'", "fields:", "\t\tleading tabs",
                "Украина, ЁЖЖёФЫв", "\uD83D\uDE00 emoji",
                "it's \"quoted\" \\backslashed\\ #hash: value",
                "line1\nline2\twith 'quotes' and \"doubles\" and \\ backslashes\r\nand a null\nEND");
    }

    private XdStorageYamlObjectsWriter writer;
    private XdStorageYamlObjectsReader reader;
    private XdStorageServicesLocator services;

    @BeforeEach
    public void setUp() {
        final IXdStorageSimpleTypeHelper helper = new XdStorageDefaultSimpleTypeHelper();
        services = mock(XdStorageServicesLocator.class);
        final IXdStorageIdGenerator idGenerator = mock(IXdStorageIdGenerator.class);
        writer = new XdStorageYamlObjectsWriter(services, helper, idGenerator);
        reader = new XdStorageYamlObjectsReader(helper);
    }

    private static TextHost host() {
        final TextHost host = new TextHost();
        host.setId(7L);
        host.setLetter('x');
        return host;
    }

    private String yamlOf(final TextHost host) throws Exception {
        final StringWriter out = new StringWriter();
        writer.writeObjects(out, Collections.singletonList(host));
        return out.toString();
    }

    private TextHost roundTrip(final TextHost host) throws Exception {
        return (TextHost) new ArrayList<>(reader.read(new StringReader(yamlOf(host)))).get(0);
    }

    private static String show(final String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    // === every string-bearing position ===

    @ParameterizedTest(name = "[{index}] поле")
    @MethodSource("strings")
    @DisplayName("Поле объекта")
    public void fieldRoundTrips(final String s) throws Exception {
        final TextHost host = host();
        host.setText(s);
        assertEquals(s, roundTrip(host).getText(), "field value '" + show(s) + "'");
    }

    @ParameterizedTest(name = "[{index}] элемент списка")
    @MethodSource("strings")
    @DisplayName("Элемент списка (между двумя null)")
    public void listElementRoundTrips(final String s) throws Exception {
        final TextHost host = host();
        host.setTags(Arrays.asList(s, null, s));
        assertEquals(Arrays.asList(s, null, s), roundTrip(host).getTags(), "list element '" + show(s) + "'");
    }

    @ParameterizedTest(name = "[{index}] ключ и значение карты")
    @MethodSource("strings")
    @DisplayName("Ключ и значение карты")
    public void mapKeyAndValueRoundTrip(final String s) throws Exception {
        final TextHost host = host();
        final Map<String, String> expected = new HashMap<>();
        expected.put(s, s);
        expected.put("fixed-key", s);
        host.setMeta(new HashMap<>(expected));
        assertEquals(expected, roundTrip(host).getMeta(), "map key/value '" + show(s) + "'");
    }

    @ParameterizedTest(name = "[{index}] элемент массива")
    @MethodSource("strings")
    @DisplayName("Элемент массива")
    public void arrayElementRoundTrips(final String s) throws Exception {
        final TextHost host = host();
        host.setLabels(new String[]{s, s});
        assertArrayEquals(new String[]{s, s}, roundTrip(host).getLabels(), "array element '" + show(s) + "'");
    }

    @ParameterizedTest(name = "'{0}'")
    @ValueSource(chars = {'\n', '\r', '"', '\'', '\\', '\t', ' ', 'A', '0', '\u0410'})
    @DisplayName("Символьное поле")
    public void charFieldRoundTrips(final char c) throws Exception {
        final TextHost host = host();
        host.setLetter(c);
        assertEquals(c, roundTrip(host).getLetter(), "char field");
    }

    // === the shape of the file: one physical line per scalar, whatever the content ===

    @ParameterizedTest(name = "[{index}] одна строка на скаляр")
    @MethodSource("strings")
    @DisplayName("Скаляр всегда занимает ровно одну физическую строку")
    public void onePhysicalLinePerScalar(final String s) throws Exception {
        final TextHost reference = host();
        reference.setText("plain reference value");
        reference.setTags(Arrays.asList(s, null, s));
        reference.setLabels(new String[]{s});
        final long expected = yamlOf(reference).split("\n", -1).length;
        final TextHost host = host();
        host.setText(s);
        host.setTags(Arrays.asList(s, null, s));
        host.setLabels(new String[]{s});
        assertEquals(expected, yamlOf(host).split("\n", -1).length,
                "a scalar must never add or remove a physical line: '" + show(s) + "'");
    }

    // === the neighbours: a stored null is still a null ===

    @Test
    @DisplayName("Явный null остаётся null: отсутствующее поле, null-элемент, null-значение карты")
    public void storedNullStaysNull() throws Exception {
        final TextHost host = host();
        host.setTags(Arrays.asList(null, "x", null));
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("a", null);
        meta.put(null, "dropped-by-contract");
        host.setMeta(meta);

        final TextHost restored = roundTrip(host);

        assertNull(restored.getText(), "an absent field is null");
        assertEquals(Arrays.asList(null, "x", null), restored.getTags(), "null list elements stay null");
        assertEquals(Collections.singletonMap("a", null), restored.getMeta(), "a null value stays, a null key drops");
    }

    // === the journal: the crash-equivalent path ===

    @TempDir
    Path folder;

    @Test
    @DisplayName("Журнал коммитов: запись с многострочным текстом и словом null реплеится без потерь")
    public void commitJournalRecordReplayIntact() throws Exception {
        final TextHost host = host();
        host.setText("first line\nsecond line\r\nthird: null, it's \"quoted\"");
        host.setTags(Arrays.asList("null", "~", "a\nb"));
        final Map<String, String> meta = new HashMap<>();
        meta.put("null", "line one\nline two");
        host.setMeta(meta);
        host.setLabels(new String[]{"\\n is two chars\n and this one is a break"});

        final Path journal = folder.resolve("TextHost-journal.yaml");
        final XdStorageYamlIOFactory factory = new XdStorageYamlIOFactory(services,
                new XdStorageDefaultSimpleTypeHelper(), mock(IXdStorageIdGenerator.class));
        XdStorageYamlCommitJournal.appendRedo(journal, 17L,
                Collections.singletonList(new XdStorageObjectChange(XdStorageObjectOperationType.Insert, "note-1",
                        null, host)), services, factory, false);

        final String written = new String(Files.readAllBytes(journal), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, countOf(written, "# xdstorage-eof"), "one append, one sentinel");

        final XdStorageYamlCommitJournal.Replay replay = XdStorageYamlCommitJournal.readForReplay(journal,
                String.class, factory);
        assertEquals(0, replay.discardedTailRecords, "a cleanly terminated append has no torn tail");
        assertEquals(1, replay.upserts.size(), "the record must replay — nothing is discarded");
        final TextHost replayed = (TextHost) replay.upserts.get(0);
        assertEquals(host.getText(), replayed.getText(), "journal field value");
        assertEquals(host.getTags(), replayed.getTags(), "journal list");
        assertEquals(host.getMeta(), replayed.getMeta(), "journal map");
        assertArrayEquals(host.getLabels(), replayed.getLabels(), "journal array");
    }

    private static int countOf(final String text, final String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + fragment.length())) {
            ++count;
        }
        return count;
    }

    // === the legacy document: what a 2.1.x reader returned for a file of that era ===

    /** Built by hand on purpose: the single-quoted spellings every version before 2.2.0 wrote. */
    private static String legacyDocument(final String singleQuotedValue) {
        return "objects:\r\n"
                + "\t- object:\r\n"
                + "\t\tclass: '" + TextHost.class.getName() + "'\r\n"
                + "\t\tfields:\r\n"
                + "\t\t\t- field:\r\n"
                + "\t\t\t\tname: 'id'\r\n"
                + "\t\t\t\ttype: 'java.lang.Long'\r\n"
                + "\t\t\t\tvalue: '7'\r\n"
                + "\t\t\t- field:\r\n"
                + "\t\t\t\tname: 'text'\r\n"
                + "\t\t\t\ttype: 'java.lang.String'\r\n"
                + "\t\t\t\tvalue: " + singleQuotedValue + "\r\n"
                + "\r\n"
                + "# xdstorage-eof\r\n";
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("legacyScalars")
    @DisplayName("Файл, записанный до 2.2.0, читается так же, как раньше")
    public void legacySingleQuotedScalarsReadAsBefore(final String description, final String expected,
            final String onDisk) throws Exception {
        final Object restored = new ArrayList<>(reader.read(new StringReader(legacyDocument(onDisk)))).get(0);
        assertEquals(expected, ((TextHost) restored).getText(), description);
    }

    /** (what the row shows, the string a 2.1.x reader returned, the exact bytes on disk) */
    static Stream<Arguments> legacyScalars() {
        return Stream.of(
                Arguments.of("a backslash is literal", "C:\\new\\table", "'C:\\new\\table'"),
                Arguments.of("a backslash-n is two characters", "\\n", "'\\n'"),
                Arguments.of("a string that starts and ends with a double quote", "\"quoted\"", "'\"quoted\"'"),
                Arguments.of("an apostrophe pair", "it's", "'it''s'"),
                Arguments.of("a string that is only apostrophes", "''", "''''''"),
                Arguments.of("an empty string", "", "''"),
                Arguments.of("a tilde", "~", "'~'"),
                Arguments.of("leading and trailing spaces inside the quotes", "  padded  ", "'  padded  '"),
                Arguments.of("a hash and a colon", "# not a comment: value", "'# not a comment: value'"),
                Arguments.of("upper-case NULL", "NULL", "'NULL'"));
    }
}
