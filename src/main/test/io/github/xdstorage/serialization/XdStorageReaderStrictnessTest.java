package io.github.xdstorage.serialization;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.serialization.yaml.XdStorageYamlBlockObjectsReader;
import io.github.xdstorage.serialization.yaml.XdStorageYamlTokenizer;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YAMLREAD (docs/tasks/2.1.0/37): corruption-detection regression tests for the
 * serialization readers. The contract pinned here: a malformed or wrong-kind input fails
 * LOUDLY ({@code XdStorageIOException}) — it is never silently absorbed into empty
 * results, skipped fields, or {@code null} returns; and a stored {@code null} property
 * loads as {@code null} (honest assignment), never as the field's construction default.
 */
public class XdStorageReaderStrictnessTest {

    /** Reader-level fixture: a plain class the readers can resolve and reflect into. */
    public static class StrictnessEntity {
        @XdStorageObjectId
        private Long id;
        private String value = "CONSTRUCTION-DEFAULT";

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    private static final String YAML_OBJECT_WITH_NULL_FIELD =
            "\t\tobject:\r\n" +
            "\t\t\tclass: 'io.github.xdstorage.serialization.XdStorageReaderStrictnessTest$StrictnessEntity'\r\n" +
            "\t\t\tfields:\r\n" +
            "\t\t\t\t- field:\r\n" +
            "\t\t\t\t\tname: 'id'\r\n" +
            "\t\t\t\t\ttype: 'java.lang.Long'\r\n" +
            "\t\t\t\t\tvalue: '7'\r\n" +
            "\t\t\t\t- field:\r\n" +
            "\t\t\t\t\tname: 'value'\r\n" +
            "\t\t\t\t\ttype: 'java.lang.String'\r\n" +
            "\t\t\t\t\tvalue:\r\n";

    @Test
    public void objectElementInReferencesFile_IsRefusedLoudly() {
        // the references reader accepted the start element but only processed
        // `reference` — an `object` element silently yielded nothing (references vanished).
        final XdStorageDefaultObjectsReader reader = new XdStorageDefaultObjectsReader(new XdStorageDefaultSimpleTypeHelper());
        final Reader xml = new StringReader(
                "<objects><object class='io.github.xdstorage.serialization.XdStorageReaderStrictnessTest$StrictnessEntity' name='e1'/></objects>");
        assertThrows(XdStorageIOException.class,
                () -> reader.readReferences(xml, XdStorageObjectUtils.getClassInfo(StrictnessEntity.class).getIdField()),
                "an object element in a references file must be refused loudly");
    }

    @Test
    public void referenceElementInObjectsFile_IsRefusedLoudly() {
        // the mirror case: the data-object reader silently skipped reference elements.
        final XdStorageDefaultObjectsReader reader = new XdStorageDefaultObjectsReader(new XdStorageDefaultSimpleTypeHelper());
        final Reader xml = new StringReader(
                "<objects><reference class='io.github.xdstorage.serialization.XdStorageReaderStrictnessTest$StrictnessEntity' value='1'/></objects>");
        assertThrows(XdStorageIOException.class,
                () -> reader.read(xml),
                "a reference element in a data-object file must be refused loudly");
    }

    @Test
    public void storedNullProperty_LoadsAsNull_NotAsConstructionDefault() throws Exception {
        // the old reader silently skipped null values — the loaded field kept its
        // construction default ("CONSTRUCTION-DEFAULT") instead of the stored null.
        final XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(
                new StringReader(YAML_OBJECT_WITH_NULL_FIELD));
        tokenizer.nextToken(); // consume the 'object:' marker (mirrors the block-reader test)
        final Object restored = XdStorageYamlBlockObjectsReader.readObject(tokenizer, 2,
                new XdStorageDefaultSimpleTypeHelper());
        assertInstanceOf(StrictnessEntity.class, restored);
        assertNull(((StrictnessEntity) restored).getValue(),
                "a stored null property must load as null, not as the construction default");
    }

    @Test
    public void referenceBlockWithoutId_IsRefusedLoudly() {
        // the old readReference returned null for an id-less reference block —
        // indistinguishable from "no reference" and hiding corruption.
        final String yaml =
                "reference:\n" +
                "\tclass: 'io.github.xdstorage.serialization.XdStorageReaderStrictnessTest$StrictnessEntity'\n";
        final XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(new StringReader(yaml));
        assertThrows(XdStorageIOException.class,
                () -> XdStorageYamlBlockObjectsReader.readReference(tokenizer, -1, null,
                        new XdStorageDefaultSimpleTypeHelper()),
                "a reference block without an id must be refused loudly");
    }

    @Test
    public void nullFormat_IsRefusedWithActionableMessage() {
        final XdStorageRuntimeException x = assertThrows(XdStorageRuntimeException.class,
                () -> XdStorageIOFactory.instanceIOFactory(null, null, null));
        assertTrue(x.getMessage().contains("FilesFormat"),
                "the refusal must name the expected type: " + x.getMessage());
    }
}
