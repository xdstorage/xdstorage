package io.github.xdstorage.structure;

import io.github.xdstorage.XdStoragePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STRUCTFIX (docs/tasks/2.1.0/36): the persisted structure records override {@code equals}
 * and land in hash-based collections — both must honor the equals/hashCode contract.
 * Also pins the canonical usage shape: the structure manager stores these records in
 * hash-keyed caches keyed by their logical identity (the class / the class+index name).
 */
public class XdStorageStructureRecordsContractTest {

    private XdStorageClassStructure structure(final String typeName, final XdStoragePolicy policy,
                                              final String fieldName, final Class<?> fieldType) {
        final XdStorageClassStructure s = new XdStorageClassStructure();
        s.setType(typeName);
        s.setPolicy(policy);
        final Map<String, Class<?>> fields = new HashMap<>();
        fields.put(fieldName, fieldType);
        s.setFields(fields);
        return s;
    }

    @Test
    @DisplayName("XdStorageClassStructure: equal records have equal hash codes and behave in hash collections")
    public void classStructureHashCodeContract() {
        final XdStorageClassStructure a = structure("evolution.EvoEntity", XdStoragePolicy.StoreAsClassObjects,
                "score", Long.class);
        final XdStorageClassStructure b = structure("evolution.EvoEntity", XdStoragePolicy.StoreAsClassObjects,
                "score", Long.class);
        final XdStorageClassStructure differentField = structure("evolution.EvoEntity", XdStoragePolicy.StoreAsClassObjects,
                "extra", String.class);
        final XdStorageClassStructure differentPolicy = structure("evolution.EvoEntity", XdStoragePolicy.StoreAsSingleObject,
                "score", Long.class);

        assertEquals(a, b, "equal records");
        assertEquals(a.hashCode(), b.hashCode(), "equal records MUST have equal hash codes");
        assertNotEquals(a, differentField);
        assertNotEquals(a, differentPolicy);
        assertNotEquals(a.hashCode(), differentField.hashCode(), "different fields should (sanity) hash differently");

        // the hash-based collection shape the structure manager relies on
        final Set<XdStorageClassStructure> set = new HashSet<>();
        assertTrue(set.add(a));
        assertFalse(set.add(b), "an equal record must not re-enter the set");
        assertEquals(1, set.size());
        assertTrue(set.contains(structure("evolution.EvoEntity", XdStoragePolicy.StoreAsClassObjects, "score", Long.class)),
                "an equal fresh instance must be FOUND in the set (the hashCode contract)");
    }

    @Test
    @DisplayName("XdStorageClassIndexStructure: equal records have equal hash codes and behave in hash collections")
    public void indexStructureHashCodeContract() {
        final XdStorageClassIndexStructure a = indexStructure("evolution.EvoEntity", "evo_idx", "score", Long.class);
        final XdStorageClassIndexStructure b = indexStructure("evolution.EvoEntity", "evo_idx", "score", Long.class);
        final XdStorageClassIndexStructure widened = indexStructure("evolution.EvoEntity", "evo_idx", "score", Long.class);
        widened.addField("extra", String.class);

        assertEquals(a, b, "equal index records");
        assertEquals(a.hashCode(), b.hashCode(), "equal index records MUST have equal hash codes");
        assertNotEquals(a, widened, "a widened index differs");

        final Set<XdStorageClassIndexStructure> set = new HashSet<>();
        assertTrue(set.add(a));
        assertFalse(set.add(b), "an equal index record must not re-enter the set");
        assertTrue(set.contains(indexStructure("evolution.EvoEntity", "evo_idx", "score", Long.class)),
                "an equal fresh instance must be FOUND in the set (the hashCode contract)");
    }

    private XdStorageClassIndexStructure indexStructure(final String owner, final String name,
                                                        final String fieldName, final Class<?> fieldType) {
        final XdStorageClassIndexStructureId id = new XdStorageClassIndexStructureId();
        id.setName(name);
        id.setType(owner);

        final XdStorageClassIndexStructure s = new XdStorageClassIndexStructure();
        s.setId(id);
        s.addField(fieldName, fieldType);
        return s;
    }
}
