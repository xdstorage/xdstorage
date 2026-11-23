package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.exceptions.XdStorageIOException;

/**
 * The ONE quoting rule for every string the engine's YAML dialect stores (YAMLSTR,
 * docs/tasks/2.2.0/40). One physical line is one token, so a scalar must never carry a line
 * break in its raw form — a raw one used to split the record and lose every field after it.
 * Two forms, chosen by the content:
 * <ul>
 *   <li>WITHOUT a line break — the original form: single quotes, each inner {@code '} doubled
 *       ({@code 'it''s'}). Every file written before 2.2.0 is byte-identical to what this
 *       version writes for the same data.</li>
 *   <li>WITH a line break — double quotes with backslash escapes ({@code "line one\nline
 *       two"}): {@code \\}, {@code \"}, {@code \n} and {@code \r}.</li>
 * </ul>
 * The reader's choice between them is unambiguous because a value starts with {@code "} on
 * disk only in the second form: no earlier version wrote one (strings were always
 * single-quoted; unquoted scalars are numbers, class names and the {@code null}/{@code ~}
 * markers), and a single-quoted string starts with {@code '} whatever it contains.
 *
 * <p>Thread-safety: stateless static methods — thread-safe. Engine-internal — not part of
 * the public API.</p>
 */
public final class XdStorageYamlScalar {

    /**
     * Utility class — no instances.
     */
    private XdStorageYamlScalar() {
    }

    /**
     * The on-disk form of one string: single-quoted unless it carries a line break.
     *
     * @param value the string to store (never {@code null} — a null is the unquoted
     *              {@code null} marker, written by the caller)
     * @return the complete scalar token, quotes included
     */
    public static String token(final String value) {
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return singleQuoted(value);
        }
        return doubleQuoted(value);
    }

    /**
     * The original form: single quotes with each inner {@code '} doubled.
     *
     * @param value the string to wrap
     * @return the single-quoted token
     */
    public static String singleQuoted(final String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * The line-safe form: double quotes with backslash escapes for the backslash, the double
     * quote and the two line-break characters. Everything else is written as is.
     *
     * @param value the string to wrap (it carries a line break)
     * @return the double-quoted token, one physical line
     */
    private static String doubleQuoted(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '"') {
                out.append("\\\"");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * Restores the string behind a double-quoted token's content. The escapes are the four
     * the writer emits plus {@code \t} (accepted for hand-edited fixtures); anything else is
     * refused — the engine never writes it, so the value was corrupted or mistyped.
     *
     * @param content the text between the quotes
     * @return the restored string
     * @throws XdStorageIOException on an unknown escape or a lone trailing backslash
     */
    public static String unescapeDoubleQuoted(final String content) {
        if (content.indexOf('\\') < 0) {
            return content;
        }
        final StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= content.length()) {
                throw new XdStorageIOException("a double-quoted scalar ends with a lone backslash (corrupt or "
                        + "hand-edited value): \"" + content + "\"");
            }
            c = content.charAt(i);
            if (c == '\\') {
                out.append('\\');
            } else if (c == '"') {
                out.append('"');
            } else if (c == 'n') {
                out.append('\n');
            } else if (c == 'r') {
                out.append('\r');
            } else if (c == 't') {
                out.append('\t');
            } else {
                throw new XdStorageIOException("unknown escape '\\" + c + "' in a double-quoted scalar (the engine "
                        + "writes \\\\, \\\", \\n and \\r only) — corrupt or hand-edited value: \"" + content + "\"");
            }
        }
        return out.toString();
    }
}
