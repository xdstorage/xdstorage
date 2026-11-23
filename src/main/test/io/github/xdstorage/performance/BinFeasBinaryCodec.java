package io.github.xdstorage.performance;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BINFEAS experiment (task 33 follow-up): the compact binary-ish codec over the SPI's
 * CHARACTER stream. The format (all structural tokens are ASCII chars; string values are
 * length-framed RAW chars — any unicode, no escaping):
 *
 * <pre>
 * BF1\n                          — the magic
 * &lt;blockCount&gt;\n
 * per block:
 *   &lt;className&gt;\n
 *   &lt;fieldCount&gt;\n &lt;fieldName&gt;\n × fieldCount   (the schema ONCE per block —
 *                                                 the YAML repeats names per object)
 *   &lt;recordCount&gt;\n
 *   per record, per field (positional):
 *     N                          — null
 *     S&lt;charCount&gt;\n&lt;raw chars&gt;  — string, length-framed
 *     I&lt;decimal&gt;\n               — Long/Integer
 *     D&lt;rawDoubleBits decimal&gt;\n — double, exact bit round-trip
 *     B0|1\n                     — boolean
 *     L&lt;size&gt;\n (size × tag)     — list of scalars/nulls
 *     O&lt;className&gt;\n (a nested block: fields + 1 record) — a nested object
 * </pre>
 *
 * <p>The experiment covers scalars, strings, nulls, lists of scalars and one nesting
 * level — the BinFeasEntity + the hash id-index record shapes. Dates/enums/maps/arrays/
 * references fail LOUD (an unsupported-type exception beats a silent loss). The journal
 * replay path is deliberately left YAML: the factory extends XdStorageYamlIOFactory, so
 * the engine's {@code instanceof} check keeps the journal machinery intact while the
 * base files go compact.
 */
final class BinFeasBinaryCodec {

    static final String MAGIC = "BF1";

    private BinFeasBinaryCodec() {
    }

    // ───────────────────────── the write side ─────────────────────────

    /** Serializes a same-class object collection into the char writer. */
    static void writeBlock(final Writer out, final Collection<?> objects) throws IOException {
        if (objects.isEmpty()) {
            out.write(MAGIC + "\n0\n");
            return;
        }
        Object first = null;
        for (final Object o : objects) {
            if (o != null) {
                first = o;
                break;
            }
        }
        if (first == null) {
            out.write(MAGIC + "\n0\n");
            return;
        }
        final Class<?> cl = first.getClass();
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final Map<String, XdStorageObjectField> fields = orderedFields(clInfo);

        out.write(MAGIC + "\n");
        out.write("1\n"); // one class block per file (the engine writes one class per resource)
        out.write(cl.getName() + "\n");
        out.write(fields.size() + "\n");
        for (final String name : fields.keySet()) {
            out.write(name + "\n");
        }
        out.write(objects.size() + "\n");
        for (final Object o : objects) {
            for (final XdStorageObjectField field : fields.values()) {
                writeValue(out, field.get(o));
            }
        }
    }

    /** Serializes engine-neutral records (the readData form) — the inverse of readAsRecords. */
    static void writeRecordsBlock(final Writer out, final Collection<XdStorageIdentifiableObject> records) throws
            IOException {
        out.write(MAGIC + "\n");
        if (records.isEmpty()) {
            out.write("0\n");
            return;
        }
        final Class<?> cl = records.iterator().next().getType();
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final Map<String, XdStorageObjectField> fields = orderedFields(clInfo);
        out.write("1\n");
        out.write(cl.getName() + "\n");
        out.write(fields.size() + "\n");
        for (final String name : fields.keySet()) {
            out.write(name + "\n");
        }
        out.write(records.size() + "\n");
        final String idName = idFieldName(clInfo);
        for (final XdStorageIdentifiableObject record : records) {
            for (final String name : fields.keySet()) {
                // the YAML readObjectData convention: the id lands in setId(), NOT in the
                // properties — mirror it (a getProperty on the id field would write null)
                final Object value = name.equals(idName) ? record.getId() : record.getProperty(name);
                writeValue(out, value);
            }
        }
    }

    /** The id field's name (the wrapper exposes the reflective field publicly). */
    private static String idFieldName(final XdStorageClassInfo clInfo) {
        final XdStorageObjectIdField idField = clInfo.getIdField();
        return idField == null ? null : idField.field.getName();
    }

    /** The engine's field map preserves registration order — pin it deterministic. */
    private static Map<String, XdStorageObjectField> orderedFields(final XdStorageClassInfo clInfo) {
        final Map<String, XdStorageObjectField> fields = new LinkedHashMap<>();
        for (final Map.Entry<String, XdStorageObjectField> e : clInfo.getFields().entrySet()) {
            fields.put(e.getKey(), e.getValue());
        }
        return fields;
    }

    private static void writeValue(final Writer out, final Object value) throws IOException {
        if (value == null) {
            out.write("N\n");
        } else if (value instanceof String) {
            final String s = (String) value;
            out.write("S" + s.length() + "\n");
            out.write(s);
            out.write("\n");
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
                {
            out.write("I" + ((Number) value).longValue() + "\n");
        } else if (value instanceof Double || value instanceof Float) {
            out.write("D" + Double.doubleToRawLongBits(((Number) value).doubleValue()) + "\n");
        } else if (value instanceof Boolean) {
            out.write("B" + ((Boolean) value ? 1 : 0) + "\n");
        } else if (value instanceof List) {
            final List<?> list = (List<?>) value;
            out.write("L" + list.size() + "\n");
            for (final Object element : list) {
                if (element instanceof String || element instanceof Number || element instanceof Boolean || element ==
                        null) {
                    writeValue(out, element);
                } else {
                    throw loud("list element type " + element.getClass().getName());
                }
            }
        } else {
            throw loud("value type " + value.getClass().getName());
        }
    }

    private static XdStorageIOException loud(final String what) {
        return new XdStorageIOException("BINFEAS codec: the experiment does not cover " + what
                + " — refusing loudly rather than silently losing data (extend the codec or keep the field YAML)");
    }

    // ───────────────────────── the read side ─────────────────────────

    /** A tiny char-stream cursor over the fully-buffered payload. */
    static final class Cursor {
        private final char[] buf;
        private int pos;

        Cursor(final Reader reader) throws IOException {
            final StringWriter sw = new StringWriter(1 << 16);
            final char[] chunk = new char[1 << 16];
            int n;
            while ((n = reader.read(chunk)) != -1) {
                sw.write(chunk, 0, n);
            }
            this.buf = sw.toString().toCharArray();
        }

        String line() {
            final int start = pos;
            while (pos < buf.length && buf[pos] != '\n') {
                pos++;
            }
            final String s = new String(buf, start, pos - start);
            if (pos < buf.length) {
                pos++; // the newline
            }
            return s;
        }

        String readChars(final int count) {
            final String s = new String(buf, pos, count);
            pos += count;
            if (pos < buf.length && buf[pos] == '\n') {
                pos++; // the framing newline after a raw string
            }
            return s;
        }

        boolean done() {
            return pos >= buf.length;
        }

        boolean atMagic() {
            return pos == 0;
        }
    }

    /** Reads the blocks into live instances (the {@code read} family). */
    static List<Object> readInstances(final Reader reader, final IXdStorageTransaction transaction) throws IOException,
            XdStorageException {
        final Cursor cur = new Cursor(reader);
        final List<Object> result = new ArrayList<>();
        readMagic(cur);
        final int blocks = Integer.parseInt(cur.line());
        for (int b = 0; b < blocks; b++) {
            final Block block = readSchema(cur);
            final int records = Integer.parseInt(cur.line());
            for (int r = 0; r < records; r++) {
                final Object instance = block.newInstance();
                for (final FieldSlot slot : block.slots) {
                    slot.field.set(instance, readValue(cur, slot.name));
                }
                result.add(instance);
            }
        }
        return result;
    }

    /** Reads the blocks into the engine-neutral record form (the {@code readData} family). */
    static List<XdStorageIdentifiableObject> readAsRecords(final Reader reader, final XdStorageObjectIdField idField)
            throws IOException, XdStorageException {
        final Cursor cur = new Cursor(reader);
        final List<XdStorageIdentifiableObject> result = new ArrayList<>();
        readMagic(cur);
        final int blocks = Integer.parseInt(cur.line());
        for (int b = 0; b < blocks; b++) {
            final Block block = readSchema(cur);
            final int records = Integer.parseInt(cur.line());
            for (int r = 0; r < records; r++) {
                final Map<String, Object> properties = new LinkedHashMap<>();
                Object id = null;
                final String idName = idField.field.getName();
                for (final FieldSlot slot : block.slots) {
                    final Object value = readValue(cur, slot.name);
                    properties.put(slot.name, value);
                    if (slot.name.equals(idName)) {
                        id = value;
                    }
                }
                final XdStorageIdentifiableObject record = new XdStorageIdentifiableObject();
                record.setType(block.type);
                record.setId(id);
                record.setProperties(properties);
                result.add(record);
            }
        }
        return result;
    }

    /** Reads the blocks restoring id-only stubs (the {@code readReferences} family). */
    static List<Object> readReferenceStubs(final Reader reader, final XdStorageObjectIdField idField)
            throws IOException, XdStorageException {
        final Cursor cur = new Cursor(reader);
        final List<Object> result = new ArrayList<>();
        readMagic(cur);
        final int blocks = Integer.parseInt(cur.line());
        for (int b = 0; b < blocks; b++) {
            final Block block = readSchema(cur);
            final int records = Integer.parseInt(cur.line());
            for (int r = 0; r < records; r++) {
                final Object stub = block.newInstance();
                final String idName = idField.field.getName();
                for (final FieldSlot slot : block.slots) {
                    final Object value = readValue(cur, slot.name);
                    if (slot.name.equals(idName)) {
                        slot.field.set(stub, value);
                    }
                }
                result.add(stub);
            }
        }
        return result;
    }

    private static void readMagic(final Cursor cur) throws XdStorageIOException {
        final String magic = cur.line();
        if (!MAGIC.equals(magic)) {
            throw new XdStorageIOException("BINFEAS codec: the stream is not " + MAGIC + " (got '" + magic
                    + "') — a YAML base file read by the binary reader?");
        }
    }

    private static final class Block {
        final Class<?> type;
        final List<FieldSlot> slots;

        Block(final Class<?> type, final List<FieldSlot> slots) {
            this.type = type;
            this.slots = slots;
        }

        Object newInstance() throws XdStorageException {
            try {
                return type.newInstance();
            } catch (final Exception e) {
                throw new XdStorageException("BINFEAS codec: cannot instantiate " + type.getName(), e);
            }
        }
    }

    private static final class FieldSlot {
        final String name;
        final XdStorageObjectField field;

        FieldSlot(final String name, final XdStorageObjectField field) {
            this.name = name;
            this.field = field;
        }
    }

    private static Block readSchema(final Cursor cur) throws IOException, XdStorageException {
        final Class<?> type;
        try {
            type = XdStorageClassMetadataRegistry.resolveOrThrow(cur.line());
        } catch (final ClassNotFoundException unknownClass) {
            throw new XdStorageIOException("BINFEAS codec: the block class is not on the classpath: " + unknownClass
                    .getMessage(), unknownClass);
        }
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(type);
        final Map<String, XdStorageObjectField> fields = clInfo.getFields();
        final int fieldCount = Integer.parseInt(cur.line());
        final List<FieldSlot> slots = new ArrayList<>();
        for (int i = 0; i < fieldCount; i++) {
            final String name = cur.line();
            final XdStorageObjectField field = fields.get(name);
            if (field == null) {
                throw new XdStorageIOException("BINFEAS codec: the block schema field '" + name
                        + "' is absent from " + type.getName() + " (a schema drift)");
            }
            slots.add(new FieldSlot(name, field));
        }
        return new Block(type, slots);
    }

    private static Object readValue(final Cursor cur, final String fieldName) throws IOException {
        final String tagLine = cur.line();
        if (tagLine.isEmpty()) {
            throw new XdStorageIOException("BINFEAS codec: an empty value tag at field '" + fieldName + "'");
        }
        final char tag = tagLine.charAt(0);
        switch (tag) {
            case 'N':
                return null;
            case 'S': {
                final int len = Integer.parseInt(tagLine.substring(1));
                return cur.readChars(len);
            }
            case 'I':
                return Long.parseLong(tagLine.substring(1));
            case 'D':
                return Double.longBitsToDouble(Long.parseLong(tagLine.substring(1)));
            case 'B':
                return tagLine.charAt(1) == '1';
            case 'L': {
                final int size = Integer.parseInt(tagLine.substring(1));
                final List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(readValue(cur, fieldName + "[]"));
                }
                return list;
            }
            default:
                throw new XdStorageIOException("BINFEAS codec: an unsupported value tag '" + tag
                        + "' at field '" + fieldName + "' — the experiment codec refuses loudly");
        }
    }
}
