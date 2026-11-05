package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R-53 / P0 (PERFORMANCE-RECOMMENDATIONS.md §6): B+Tree структуры нечитаемы после
 * переоткрытия. После shutdown() → re-open парсерные стабы root/firstLeaf/children
 * не опознавались как ссылки (isReference знал только wrapper-стабы), спуск шел по
 * пустому узлу — все находки через BTree id index и поисковые индексы (они B+Tree
 * внутри) возвращали пусто, включая full scan. Hash id index не затронут.
 *
 * Регрессионное покрытие НЕМОЖНО сузить: каждый тест обязан комбинировать
 * BTree/поисковый индекс с shutdown → re-open → read (такого покрытия не было —
 * дефект прошел все зеленые прогоны).
 */
@DisplayName("W-2 / P0: B+Tree (id index + поисковые индексы) читаемы и пишемы после переоткрытия")
public class XdStorageW2ReopenBTreeTest {

    private static final int RECORDS = 200; // t=8: 200 ключей => многоуровневое дерево

    @Test
    @DisplayName("BTree id index: после reopen каждый по-id load находит запись, full scan возвращает все")
    public void testBTreeIdIndexReadableAfterReopen() throws Exception {
        final File dir = new File("./target/w2_btree_read");
        final String name = "w2-btree-read";
        try {
            IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                for (int i = 0; i < RECORDS; i++) {
                    storage.save(new XdStorageW2BTreeRecord("r-" + i, "payload-" + i, i), tx);
                }
                tx.commit();
                final IXdStorageTransaction warmTx = storage.beginTransaction();
                assertNotNull(storage.load(XdStorageW2BTreeRecord.class, "r-0", warmTx), "warm read must work before shutdown");
                warmTx.commit();
            } finally {
                storage.shutdown();
            }

            storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                for (int i = 0; i < RECORDS; i++) {
                    final XdStorageW2BTreeRecord loaded = storage.load(XdStorageW2BTreeRecord.class, "r-" + i, tx);
                    assertNotNull(loaded, "by-id load through reopened BTree id index must find r-" + i);
                    assertEquals("payload-" + i, loaded.getPayload(), "payload must survive reopen");
                    assertEquals(i, loaded.getNumber());
                }
                final Collection<XdStorageW2BTreeRecord> scan = storage.load(XdStorageW2BTreeRecord.class, tx);
                assertEquals(RECORDS, scan.size(), "full scan through reopened BTree must return every record");
                tx.commit();
            } finally {
                storage.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("BTree id index: вставки/обновления/удаления после reopen корректны (сплиты/слияния по живому дереву)")
    public void testBTreeWritesWorkAfterReopen() throws Exception {
        final File dir = new File("./target/w2_btree_write");
        final String name = "w2-btree-write";
        try {
            XdStorageConfig config = new XdStorageConfig.Builder().journalWrites(true).build();
            IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128, FilesFormat.YAML, config);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                for (int i = 0; i < RECORDS; i++) {
                    storage.save(new XdStorageW2BTreeRecord("r-" + i, "payload-" + i, i), tx);
                }
                tx.commit();
            } finally {
                storage.shutdown();
            }

            // Записи в переоткрытое дерево: новые вставки переводят сплиты (getTree().getT()
            // обязан быть 8, а не 0 починенного бэк-референса), удаления — слияния/заимствования
            storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                for (int i = RECORDS; i < RECORDS + 50; i++) {
                    storage.save(new XdStorageW2BTreeRecord("r-" + i, "post-reopen-" + i, i), tx);
                }
                for (int i = 0; i < 10; i++) {
                    storage.update(new XdStorageW2BTreeRecord("r-" + i, "updated-" + i, i), tx);
                }
                for (int i = 100; i < 110; i++) {
                    storage.delete(new XdStorageW2BTreeRecord("r-" + i, null, 0), tx);
                }
                tx.commit();
            } finally {
                storage.shutdown();
            }

            // Контрольная переоткрытие: финальное состояние согласовано в структуре и на диске
            storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                for (int i = 0; i < 10; i++) {
                    final XdStorageW2BTreeRecord loaded = storage.load(XdStorageW2BTreeRecord.class, "r-" + i, tx);
                    assertNotNull(loaded);
                    assertEquals("updated-" + i, loaded.getPayload(), "post-reopen update must survive");
                }
                for (int i = 100; i < 110; i++) {
                    assertNull(storage.load(XdStorageW2BTreeRecord.class, "r-" + i, tx), "post-reopen delete must remove r-" + i);
                }
                for (int i = RECORDS; i < RECORDS + 50; i++) {
                    final XdStorageW2BTreeRecord loaded = storage.load(XdStorageW2BTreeRecord.class, "r-" + i, tx);
                    assertNotNull(loaded, "post-reopen insert must survive r-" + i);
                    assertEquals("post-reopen-" + i, loaded.getPayload());
                }
                final Collection<XdStorageW2BTreeRecord> scan = storage.load(XdStorageW2BTreeRecord.class, tx);
                assertEquals(RECORDS + 50 - 10, scan.size(), "final scan count after post-reopen writes");
                tx.commit();
            } finally {
                storage.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    @Test
    @DisplayName("Поисковый индекс (B+Tree): EQUALS и full scan работают после reopen; записи после reopen видны")
    public void testSearchIndexReadableAndWritableAfterReopen() throws Exception {
        final File dir = new File("./target/w2_search");
        final String name = "w2-search";
        try {
            IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                for (long i = 0; i < 20; i++) {
                    storage.save(newShip(i), tx);
                }
                tx.commit();
            } finally {
                storage.shutdown();
            }

            storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                assertNotNull(storage.load(XdStorageW2SearchShip.class, 3L, tx), "by-id load through reopened BTree id index");
                assertEquals(1, count(storage, tx, searchByName("Ship-3")), "EQUALS through reopened search index");
                assertEquals(20, count(storage, tx, searchScan()), "full scan through reopened search index");
                tx.commit();

                // записи в переоткрытые поисковые деревья: удаление чистит crew-индекс (B+Tree), вставка строит новые узлы
                final IXdStorageTransaction writeTx = storage.beginTransaction();
                storage.delete(newShip(3), writeTx);
                storage.save(newShip(99), writeTx);
                writeTx.commit();
            } finally {
                storage.shutdown();
            }

            storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 128);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                assertNull(storage.load(XdStorageW2SearchShip.class, 3L, tx), "deleted ship must not survive");
                assertEquals(0, count(storage, tx, searchByName("Ship-3")), "deleted ship must leave the search index");
                assertEquals(1, count(storage, tx, searchByName("Ship-99")), "post-reopen insert must be searchable");
                assertEquals(20, count(storage, tx, searchScan()), "20 - 1 deleted + 1 inserted");
                tx.commit();
            } finally {
                storage.shutdown();
            }
        } finally {
            deleteQuietly(dir);
        }
    }

    private static XdStorageW2SearchShip newShip(final long i) {
        final XdStorageW2SearchShip ship = new XdStorageW2SearchShip();
        ship.setId(i);
        ship.setName("Ship-" + i);
        ship.setTonnage((int) (i % 50));
        ship.setCaptain(new XdStorageW2SearchCrewMember(i * 10, "Rank-" + (i % 5)));
        ship.addCrewMember(new XdStorageW2SearchCrewMember(i * 10 + 1, "Rank-3"));
        return ship;
    }

    private static XdStorageSearchQuery searchByName(final String name) {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, name), null);
    }

    private static XdStorageSearchQuery searchScan() {
        return new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "@@none@@"), null);
    }

    @SuppressWarnings("unchecked")
    private static int count(final IXdFileStorage storage, final IXdStorageTransaction tx, final XdStorageSearchQuery query) throws Exception {
        final Object result = storage.load(XdStorageW2SearchShip.class, "w2_ship_idx", query, tx);
        return ((Collection<Object>) result).size();
    }

    private static void deleteQuietly(final File dir) {
        try {
            Files.walk(dir.toPath()).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Exception ignored) {
        }
    }
}
