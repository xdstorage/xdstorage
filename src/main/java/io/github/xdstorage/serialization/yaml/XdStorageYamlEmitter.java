package io.github.xdstorage.serialization.yaml;

import java.io.IOException;
import java.io.Writer;

/**
 * The write-side counterpart of {@link XdStorageYamlTokenizer}: a tiny state machine
 * over a character stream that emits the engine's tab-indented YAML. It tracks the
 * current nesting level and prints exactly one tab per level after every line break, so
 * callers can interleave {@code writeKey}/{@code writeScalar}/{@code openBlock}/
 * {@code closeBlock} without ever formatting indentation themselves. Every resource
 * file and journal append in the YAML path is produced through this emitter, always
 * terminated by the S-1 EOF sentinel ({@link #writeEofSentinel()}).
 *
 * <p>Thread-safety: an instance is confined to the writer that created it and is not
 * safe for concurrent use. Engine-internal — not part of the public API.</p>
 */
public class XdStorageYamlEmitter {
    private final Writer writer;
    private int currentLevel = 0;
    private boolean isNewLine = true;

    /**
     * Creates an emitter over the given character stream.
     *
     * @param writer the target stream of the resource file being written
     */
    public XdStorageYamlEmitter(Writer writer) {
        this.writer = writer;
    }

    /**
     * Пишет системный перенос строки \r\n и выставляет флаг ожидания отступов.
     *
     * @throws IOException when the underlying writer fails
     */
    public void newLine() throws IOException {
        writer.append("\r\n");
        isNewLine = true;
    }

    /**
     * Внутренний fail-safe метод. Если взведен флаг новой строки,
     * нарезает ровно currentLevel символов '\t' и сбрасывает флаг.
     */
    private void indent() throws IOException {
        if (isNewLine) {
            for (int i = 0; i < currentLevel; i++) {
                writer.append('\t');
            }
            isNewLine = false;
        }
    }

    /**
     * Печатает имя свойства (ключ) с учетом текущего отступа яруса.
     *
     * @param key the property name to print, followed by {@code ": "}
     * @throws IOException when the underlying writer fails
     */
    public void writeKey(String key) throws IOException {
        indent();
        writer.append(key).append(": ");
    }

    /**
     * Печатает атомарное значение (число, строку в кавычках) и сразу завершает строку.
     *
     * @param value the already encoded scalar to print verbatim
     * @throws IOException when the underlying writer fails
     */
    public void writeScalar(String value) throws IOException {
        indent();
        writer.append(value);
        newLine();
    }

    /**
     * Открывает новый структурный блок (object:, collection: и т.д.),
     * печатает его имя и увеличивает внутренний уровень табуляции на 1.
     *
     * @param blockName the block key to print (without the trailing colon)
     * @throws IOException when the underlying writer fails
     */
    public void openBlock(String blockName) throws IOException {
        indent();
        writer.append(blockName).append(":");
        newLine();
        currentLevel++;
    }

    /**
     * Закрывает структурный блок, fail-safe уменьшая уровень табуляции на 1.
     */
    public void closeBlock() {
        if (currentLevel > 0) {
            currentLevel--;
        }
    }

    /**
     * S-1: ставит сентинел конца файла (после закрытия корневого блока).
     * Строка-комментарий: старые ридеры пропускают ее, новый ридер требует.
     *
     * @throws IOException when the underlying writer fails
     */
    public void writeEofSentinel() throws IOException {
        writeEofSentinel(null);
    }

    /**
     * S-1 sentinel preceded by the BATCH TRAILER (WAL, docs/tasks/2.2.0/06): a comment line
     * naming the commit serial and the transaction of the batch just written. The sentinel
     * line itself stays the plain one, so a reader that predates the trailer skips the extra
     * line and still sees the sentinel.
     *
     * @param trailer the trailer text after {@link XdStorageYamlTokenizer#TRAILER_PREFIX}
     *                ({@code serial=17 tx=<id>}), or {@code null} for a bare sentinel
     * @throws IOException when the underlying writer fails
     */
    public void writeEofSentinel(final String trailer) throws IOException {
        writer.append("\r\n");
        if (trailer != null && !trailer.isEmpty()) {
            writer.append(XdStorageYamlTokenizer.TRAILER_PREFIX).append(trailer).append("\r\n");
        }
        writer.append(XdStorageYamlTokenizer.EOF_SENTINEL_LINE).append("\r\n");
    }
}
