package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2.3 (task 53): the mixed-key tree pins — the id_index tree can hold the keys of
 * different types (the auto-Long ids vs the manual UUID Strings landed in one tree:
 * the DIAG-CCE traces). The raw compareTo threw CCE on EVERY descent/scan — the whole
 * tree became unusable. The tolerant compare (compareMixedKeys) keeps the tree alive:
 * the finds work for BOTH key types, no CCE.
 *
 * <p>The tree: the direct XdStorageBTree ops (no entities — the node-level unit);
 * multiple=true (the id_index semantics); the keys: the Longs + the Strings mixed.
 */
@DisplayName("A2.3: смешанные типы ключей в одном B+дереве — находки работают без CCE")
public class XdStorageMixedKeyFindTest {

    private static IXdStorage mockStorage() {
        return mock(IXdStorage.class);
    }

    private static IXdStorageTransaction mockTx() {
        final IXdStorageTransaction tx = mock(IXdStorageTransaction.class);
        when(tx.getTimestart()).thenReturn(System.nanoTime());
        return tx;
    }

    @Test
    @DisplayName("вставка смешанных ключей (Long + String) → находки обоих типов работают, CCE нет")
    public void mixedKeys_InsertAndFind_BothTypes() throws Exception {
        final IXdStorage storage = mockStorage();
        final IXdStorageTransaction tx = mockTx();
        final XdStorageBTree tree = new XdStorageBTree(new XdStorageBTreeId(String.class, "mixed_test"), true, 3);

        assertDoesNotThrow(() -> {
            tree.insert("uuid-entry-1", "value-str-1", storage, tx);
            tree.insert(2L, "value-long-2", storage, tx);
            tree.insert("uuid-entry-3", "value-str-3", storage, tx);
            tree.insert(4L, "value-long-4", storage, tx);
            tree.insert("uuid-entry-5", "value-str-5", storage, tx);
        }, "вставка смешанных ключей не должна падать CCE");

        assertEquals(Collections.singletonList("value-str-1"), tree.find("uuid-entry-1", storage, tx),
                "String-ключ находит своё значение");
        assertEquals(Collections.singletonList("value-long-2"), tree.find(2L, storage, tx),
                "Long-ключ находит своё значение");
        assertEquals(Collections.singletonList("value-str-5"), tree.find("uuid-entry-5", storage, tx),
                "последний String-ключ");
        assertEquals(Collections.singletonList("value-long-4"), tree.find(4L, storage, tx), "последний Long-ключ");
    }

    @Test
    @DisplayName("дубликаты смешанных ключей (multiple=true): find возвращает все значения ключа")
    public void mixedKeys_Duplicates_MultiFind() throws Exception {
        final IXdStorage storage = mockStorage();
        final IXdStorageTransaction tx = mockTx();
        final XdStorageBTree tree = new XdStorageBTree(new XdStorageBTreeId(String.class, "mixed_multi"), true, 3);

        assertDoesNotThrow(() -> {
            tree.insert("dup-key", "val-str-1", storage, tx);
            tree.insert("dup-key", "val-str-2", storage, tx);
            tree.insert(7L, "val-long-1", storage, tx);
            tree.insert("dup-key", "val-str-3", storage, tx);
        });

        final List<Object> found = tree.find("dup-key", storage, tx);
        assertEquals(3, found.size(), "multi-дерево: все 3 значения дубликата найдены");
        assertTrue(found.contains("val-str-1") && found.contains("val-str-2") && found.contains("val-str-3"),
                "все значения дубликата на месте");
        assertEquals(Collections.singletonList("val-long-1"), tree.find(7L, storage, tx),
                "Long-ключ соседний не задет");
    }

    @Test
    @DisplayName("прогрев-морф: много вставок смешанных ключей подряд — ни одного CCE (стабильно)")
    public void mixedKeys_MassInserts_NoCCE() throws Exception {
        final IXdStorage storage = mockStorage();
        final IXdStorageTransaction tx = mockTx();
        final XdStorageBTree tree = new XdStorageBTree(new XdStorageBTreeId(String.class, "mixed_mass"), true, 3);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100; i++) {
                tree.insert("str-" + i, "sv-" + i, storage, tx);
                tree.insert((long) (1000 + i), "lv-" + i, storage, tx);
            }
        }, "массовые вставки смешанных ключей: ни одного CCE");

        int found = 0;
        try {
            for (int i = 0; i < 100; i += 25) {
                if (!tree.find("str-" + i, storage, tx).isEmpty()) found++;
                if (!tree.find((long) (1000 + i), storage, tx).isEmpty()) found++;
            }
        } catch (final Exception findFailure) {
            fail("массовые вставки: находка ключей обоих типов упала: " + findFailure);
        }
        assertEquals(8, found, "все проверенные ключи обоих типов находятся после массовых вставок");
    }
}
