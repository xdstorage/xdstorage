package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.XdStorageIndexType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-57: компенсирующее удаление индексных ключей при откате. Мутации Б+Дерева
 * выполняются ин-плейс в живом каноническом графе и не откатываются record-механизмом:
 * без компенсации ключ id-индекса переживал откат своей транзакции и навсегда указывал
 * читателям сканов на несуществующую запись — скан падал "cannot load by reference"
 * (канал GraphPhantom-семейства).
 *
 * Регрессионный пин: save+rollback обязан оставлять индекс чистым — последующий
 * полный скан обязан вернуть пусто БЕЗ исключений, и индекс обязан оставаться
 * работоспособным для последующих вставок.
 */
public class XdStorageIndexRollbackCompensationTest {

    private static final String STORAGE_NAME = "rollback-comp-" + System.nanoTime();
    private static final String STORAGE_DIR = "./target/rollback-comp-db-" + System.nanoTime();

    private static IXdFileStorage storage;

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    @XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
    public static class CompensatedEntity {
        @XdStorageObjectId
        private Long id;
        private String value;

        public CompensatedEntity() {}

        public CompensatedEntity(final String value) {
            this.value = value;
        }

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    @BeforeAll
    static void openStorage() {
        deleteDir(new File(STORAGE_DIR));
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 250);
    }

    @AfterAll
    static void closeStorage() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_DIR));
    }

    @Test
    @DisplayName("save+rollback оставляет id-индекс чистым: скан пуст и не падает, индекс работоспособен")
    public void rollbackCompensatesIndexKeys() throws Exception {
        final IXdStorage txStorage = (IXdStorage) storage;

        // Вставка + ОТКАТ: индексные ключи обязаны быть компенсированы
        io.github.xdstorage.transaction.IXdStorageTransaction tx =
                txStorage.beginTransaction(100L);
        try {
            txStorage.save(new CompensatedEntity("rolled-1"), tx);
            txStorage.save(new CompensatedEntity("rolled-2"), tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
        tx = txStorage.beginTransaction(100L);
        try {
            txStorage.save(new CompensatedEntity("doomed-1"), tx);
            txStorage.save(new CompensatedEntity("doomed-2"), tx);
        } finally {
            tx.rollback();
        }

        // ГЛАВНЫЙ ПИН: скан по индексу НЕ находит откатенных и НЕ падает на висячих ключах.
        // До R-57 ключи doomed-* оставались в живом дереве, и этот скан завершался
        // XdStorageException ("cannot load by reference").
        io.github.xdstorage.transaction.IXdStorageTransaction scanTx = txStorage.beginTransaction(100L);
        final Collection<CompensatedEntity> afterRollback;
        try {
            afterRollback = txStorage.load(CompensatedEntity.class, scanTx);
            scanTx.commit();
        } catch (final Throwable t) {
            scanTx.rollback();
            throw t;
        }
        assertEquals(2, afterRollback.size(),
                "Скан обязан видеть ЗАКОММИЧЕННЫЕ вставки и не видеть откатенных: " + valuesOf(afterRollback));
        final java.util.Set<String> values = valuesOf(afterRollback);
        assertTrue(values.contains("rolled-1") && values.contains("rolled-2"),
                "Закоммиченные записи обязаны остаться видимыми");
        assertTrue(!values.contains("doomed-1") && !values.contains("doomed-2"),
                "R-57: откатенные записи обязаны исчезнуть ИЗ ИНДЕКСА (компенсация ключей), "
                        + "иначе сканы падают на висячих ключах");

        // Индекс работоспособен: новая вставка видна и единственна
        io.github.xdstorage.transaction.IXdStorageTransaction liveTx = txStorage.beginTransaction(100L);
        try {
            txStorage.save(new CompensatedEntity("live-1"), liveTx);
            liveTx.commit();
        } catch (final Throwable t) {
            liveTx.rollback();
            throw t;
        }

        io.github.xdstorage.transaction.IXdStorageTransaction verifyTx = txStorage.beginTransaction(100L);
        final Collection<CompensatedEntity> live;
        try {
            live = txStorage.load(CompensatedEntity.class, verifyTx);
            verifyTx.commit();
        } catch (final Throwable t) {
            verifyTx.rollback();
            throw t;
        }
        assertEquals(3, live.size(), "После компенсации индекс обязан содержать только живые записи");
        final java.util.Set<String> liveValues = valuesOf(live);
        assertTrue(liveValues.contains("live-1") && liveValues.contains("rolled-1") && liveValues.contains("rolled-2"),
                "Живые записи: " + liveValues);
    }

    private static java.util.Set<String> valuesOf(final Collection<CompensatedEntity> entities) {
        final java.util.Set<String> values = new java.util.HashSet<>();
        for (final CompensatedEntity e : entities) {
            values.add(e.getValue());
        }
        return values;
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
}
