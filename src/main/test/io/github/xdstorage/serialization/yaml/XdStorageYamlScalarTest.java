package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.exceptions.XdStorageIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.StringReader;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YAMLSTR (docs/tasks/2.2.0/40) — the one quoting rule itself: which form a string takes, and
 * how the tokenizer reads each form back. The byte form of everything a single line can hold
 * is pinned on purpose — it is what every file written before 2.2.0 already contains.
 */
@DisplayName("YAMLSTR (2.2.0/40): единое правило записи строк и чтение обеих форм")
public class XdStorageYamlScalarTest {

    /** (the string, the exact bytes on disk) */
    static Stream<Arguments> forms() {
        return Stream.of(
                Arguments.of("plain", "'plain'"),
                Arguments.of("", "''"),
                Arguments.of("it's", "'it''s'"),
                Arguments.of("'", "''''"),
                Arguments.of("null", "'null'"),
                Arguments.of("~", "'~'"),
                Arguments.of("a\\b", "'a\\b'"),
                Arguments.of("C:\\new\\table", "'C:\\new\\table'"),
                Arguments.of("\\n", "'\\n'"),
                Arguments.of("\"quoted\"", "'\"quoted\"'"),
                Arguments.of("a\tb", "'a\tb'"),
                Arguments.of("  padded  ", "'  padded  '"),
                Arguments.of("\n", "\"\\n\""),
                Arguments.of("\r", "\"\\r\""),
                Arguments.of("a\nb", "\"a\\nb\""),
                Arguments.of("a\r\nb", "\"a\\r\\nb\""),
                Arguments.of("a\"b\nc\\d\r\ne\tf'g", "\"a\\\"b\\nc\\\\d\\r\\ne\tf'g\""));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("forms")
    @DisplayName("Форма на диске: без разрыва строки — как раньше, с разрывом — двойные кавычки")
    public void onDiskForm(final String value, final String expectedToken) {
        assertEquals(expectedToken, XdStorageYamlScalar.token(value));
    }

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("forms")
    @DisplayName("Токенайзер читает обе формы обратно в исходную строку")
    public void tokenizerReadsBothFormsBack(final String value, final String token) throws Exception {
        final String line = "\t\tvalue: " + token + "\r\n";
        final YamlLineToken parsed = new XdStorageYamlTokenizer(new StringReader(line)).nextToken();
        assertEquals("value", parsed.key, "the key");
        assertEquals(value, parsed.value, "the decoded value");
    }

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("roundTripStrings")
    @DisplayName("Любая строка: токен — одна физическая строка, и она читается обратно")
    public void everyStringTakesOneLineAndComesBack(final String value) throws Exception {
        final String token = XdStorageYamlScalar.token(value);
        assertFalse(token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0, "the token must be one physical line");
        final YamlLineToken parsed = new XdStorageYamlTokenizer(new StringReader("value: " + token)).nextToken();
        assertEquals(value, parsed.value);
    }

    static Stream<String> roundTripStrings() {
        return Stream.of("", " ", "\t", "null", "~", "'", "\"", "\\", "\n", "\r", "\r\n", "\n\r", "\\\\\"\"",
                "line one\nline two\r\nline three", "# not a comment", "- not an item", "key: value",
                "'''", "\"\"\"", "\\\n", "\\\"\r\n\\\\", "    ", "\t\t\t", "emoji \uD83D\uDE00\n第二行");
    }

    @Test
    @DisplayName("Строка в одинарных кавычках, начинающаяся с двойной кавычки, — это по-прежнему одинарная форма")
    public void aDoubleQuoteInsideSingleQuotesIsTheSingleQuotedForm() throws Exception {
        final YamlLineToken parsed = new XdStorageYamlTokenizer(new StringReader("value: '\"x\"'")).nextToken();
        assertEquals("\"x\"", parsed.value);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("brokenForms")
    @DisplayName("Битая форма — громкий отказ, а не данные")
    public void aBrokenDoubleQuotedScalarIsRefused(final String line, final String expectedWords) {
        final XdStorageIOException refusal = assertThrows(XdStorageIOException.class,
                () -> new XdStorageYamlTokenizer(new StringReader(line)).nextToken(), "line: " + line);
        assertTrue(refusal.getMessage().contains(expectedWords),
                "the refusal must say '" + expectedWords + "', got: " + refusal.getMessage());
    }

    static Stream<Arguments> brokenForms() {
        return Stream.of(
                Arguments.of("value: \"unterminated", "unterminated"),
                Arguments.of("value: \"", "unterminated"),
                Arguments.of("value: \"unknown \\q escape\"", "unknown escape"),
                Arguments.of("value: \"a lone backslash at the end\\\"", "lone backslash"));
    }

    @Test
    @DisplayName("Табуляция в форме с двойными кавычками читается (для файлов, правленных руками)")
    public void theTabEscapeIsAcceptedOnRead() throws Exception {
        final YamlLineToken parsed = new XdStorageYamlTokenizer(new StringReader("value: \"a\\tb\"")).nextToken();
        assertEquals("a\tb", parsed.value);
    }

    @Test
    @DisplayName("В одинарной форме обратный слеш — литерал: он не escape-символ")
    public void aBackslashIsLiteralInTheSingleQuotedForm() throws Exception {
        final YamlLineToken parsed = new XdStorageYamlTokenizer(new StringReader("value: '\\n\\t\\\\'")).nextToken();
        assertEquals("\\n\\t\\\\", parsed.value);
    }
}
