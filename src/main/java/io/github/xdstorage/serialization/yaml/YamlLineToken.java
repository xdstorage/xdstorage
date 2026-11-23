package io.github.xdstorage.serialization.yaml;

/**
 * One token of the engine's tab-indented YAML dialect: the parsed form of a single
 * physical line. Tokens are produced only by {@link XdStorageYamlTokenizer} and are the
 * currency of the recursive-descent YAML readers.
 *
 * <p>Thread-safety: immutable value object — safely shareable across threads.
 * Engine-internal — not part of the public API.</p>
 */
public class YamlLineToken {
    /** Nesting depth: the number of leading {@code '\t'} characters of the line. */
    public final int level;        // Глубина вложенности (количество '\t')
    /** The key part before the first {@code ':'} (e.g. {@code "class"}, {@code "name"}),
     * or {@code null} for a flat scalar list item. */
    public final String key;       // Ключ поля (например, "class", "name", "id")
    /** The raw (unquoted) scalar value after the {@code ':'}, or {@code null} when the
     * line is a bare block name or the value is absent/empty. On the EOF-sentinel token it
     * carries the batch trailer written before the sentinel ({@code serial=… tx=…}), when
     * there is one (docs/tasks/2.2.0/06). */
    public final String value;     // Сырое строковое значение (null, если это имя блока)
    /** Whether the line starts with the {@code "- "} collection-item marker. */
    public final boolean isListItem; // Флаг, начинается ли строка с "- "

    /**
     * Builds a token; normally invoked only by the tokenizer.
     *
     * @param level       the nesting depth (leading tab count)
     * @param key         the key part, or {@code null} for a flat scalar list item
     * @param value       the raw scalar value, or {@code null} for a bare block name
     * @param isListItem  whether the line carries the {@code "- "} marker
     */
    public YamlLineToken(int level, String key, String value, boolean isListItem) {
        this.level = level;
        this.key = key;
        this.value = value;
        this.isListItem = isListItem;
    }

    @Override
    public String toString() {
        return "YamlLineToken{" +
                "level=" + level +
                ", key='" + key + '\'' +
                ", value='" + value + '\'' +
                ", isListItem=" + isListItem +
                '}';
    }
}
