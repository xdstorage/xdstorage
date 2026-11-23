package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexChildManualId;
import io.github.xdstorage.entities.foreignkeys.index.btree.manual.BTreeIndexParentManualId;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FNKEY A2.3 (docs/analysis/FOREIGN_KEYS_ISSUES.md §P1): the REOPEN half of the FK tree
 * key-type hazard. The FK B+trees round-trip through YAML: after a storage reopen their
 * keys/values come back as RAW STRINGS while every in-transaction writer inserts TYPED
 * ids — the first typed read on the reloaded tree used to crash with
 * {@code Long cannot be cast to String} (or the reverse, on the in-memory side).
 *
 * <p>The fix: the FK resource runs the btree's BUG-E restoration (task 18) once per tree
 * instance on load — the reloaded Strings are converted back to the relation's typed ids
 * — so typed seeks work in both worlds, and the returned values are normalized to the
 * declared id type. These tests pin the reopen flow: fill → shutdown → reopen → the
 * delete-alone veto still fires (typed read), the mixed diff still resolves, and the
 * loaded entity ids stay typed.</p>
 */
@DisplayName("A2.3 reopen: FK tree key types are restored after a storage reopen")
public class XdStorageForeignKeyReopenKeyTypeTest {

    private static final String STORAGE_PATH = "./target/test_fk_reopen_keytype_db";
    private IXdFileStorage storage;
    private String storageName;
    private String uniquePath;

    private final Long manualParentId = 77777L;
    private final Long manualChildId = 88888L;

    private void clearStorageRegistry(String name) {
        try {
            Field storagesField = XdStorageProvider.class.getDeclaredField("storages");
            storagesField.setAccessible(true);
            Map<String, IXdFileStorage> map = (Map<String, IXdFileStorage>) storagesField.get(null);
            map.remove(name);
        } catch (Throwable ignored) {
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        storageName = "fk_reopen_keytype_" + UUID.randomUUID().toString().replace("-", "");
        uniquePath = STORAGE_PATH + "_" + UUID.randomUUID().toString().replace("-", "");

        clearStorageRegistry(storageName);
        deleteDir(new File(uniquePath));
        storage = XdStorageProvider.newOrGetFileStorage(storageName, uniquePath, 128, FilesFormat.YAML);

        IXdStorageTransaction tx = storage.beginTransaction();
        BTreeIndexParentManualId p = new BTreeIndexParentManualId();
        p.setId(manualParentId);
        BTreeIndexChildManualId c = new BTreeIndexChildManualId();
        c.setId(manualChildId);
        p.addChild(c);
        c.setParent(p);
        storage.save(p, tx);
        tx.commit();
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
        clearStorageRegistry(storageName);
        deleteDir(new File(uniquePath));
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    /** shutdown → reopen the SAME path: the FK trees reload with raw String keys. */
    private void reopenStorage() throws Exception {
        storage.shutdown();
        clearStorageRegistry(storageName);
        storage = XdStorageProvider.newOrGetFileStorage(storageName, uniquePath, 128, FilesFormat.YAML);
    }

    @Test
    @DisplayName("Инвариант после реопена: удаление родителя при живом ребенке по-прежнему ветоируется " +
            "(типизированное чтение FK-дерева)")
    public void testReopenDeleteParentAloneStillThrows() throws Exception {
        reopenStorage();

        IXdStorageTransaction tx = storage.beginTransaction();
        BTreeIndexParentManualId parentInDb = storage.load(BTreeIndexParentManualId.class, manualParentId, tx);
        assertNotNull(parentInDb, "the parent survives the reopen");

        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.delete(parentInDb, tx);
            tx.commit();
        }, "the delete-alone veto must survive the reopen (typed FK-tree read, no CCE)");

        tx.rollback();
    }

    @Test
    @DisplayName("Сценарий после реопена: совместное каскадное удаление по ручным ID проходит (смешанный diff на " +
            "восстановленном дереве)")
    public void testReopenDeleteBothSucceeds() throws Exception {
        reopenStorage();

        IXdStorageTransaction tx = storage.beginTransaction();
        BTreeIndexParentManualId p = storage.load(BTreeIndexParentManualId.class, manualParentId, tx);
        BTreeIndexChildManualId c = storage.load(BTreeIndexChildManualId.class, manualChildId, tx);
        assertNotNull(p);
        assertNotNull(c);

        storage.delete(c, tx);
        storage.delete(p, tx);
        assertDoesNotThrow(() -> tx.commit(), "the canonical delete resolves on the restored tree");
    }

    @Test
    @DisplayName("Реопен: загруженные сущности несут типизированные ID (Long, не String) после чтения через FK-граф")
    public void testReopenLoadedEntitiesKeepTypedIds() throws Exception {
        reopenStorage();

        IXdStorageTransaction tx = storage.beginTransaction();
        BTreeIndexParentManualId p = storage.load(BTreeIndexParentManualId.class, manualParentId, tx);
        assertNotNull(p);
        assertEquals(manualParentId, p.getId(), "the parent id is typed after the reopen");
        if (p.getChildren() != null && !p.getChildren().isEmpty()) {
            final BTreeIndexChildManualId child = p.getChildren().iterator().next();
            assertEquals(manualChildId, child.getId(), "the child id is typed after the reopen");
        }
        tx.rollback();
    }
}
