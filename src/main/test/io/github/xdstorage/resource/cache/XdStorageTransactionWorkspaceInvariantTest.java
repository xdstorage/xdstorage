package io.github.xdstorage.resource.cache;

import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.Test;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;

/**
 * Инварианты транзакционных контуров {@link XdStorageTransactionWorkspace}:
 * write-set, журнал отката (ровно одна точка отмены на запись), неидентифицированные
 * обертки и материализованное — изоляция по транзакциям и контракты очистки.
 */
class XdStorageTransactionWorkspaceInvariantTest extends CacheInvariantTestSupport {

    @Test
    void undoJournalRecordsFirstMutationOfEachRecordExactlyOnce() {
        XdStorageTransactionWorkspace ws = new XdStorageTransactionWorkspace();
        XdStorageCacheRecord first = XdStorageCacheRecord.newReadRecord(context(), context().idField(), entity(1L,
                "v1"));
        XdStorageCacheRecord second = XdStorageCacheRecord.newReadRecord(context(), context().idField(), entity(1L,
                "v1-rebound"));

        ws.registerMutation("t1", 1L, first);
        ws.registerMutation("t1", 1L, second); // повторная мутация той же записи

        Deque<XdStorageCacheRecord> undoLog = ws.peekUndoLog("t1");
        assertEquals(1, undoLog.size(), "у записи ровно ОДНА точка отмены — первая мутация");
        assertSame(first, undoLog.getFirst(), "в журнал попадает запись на момент ПЕРВОЙ мутации");
        assertSame(second, ws.changes("t1").get(1L), "write-set всегда хранит актуальную запись");
    }

    @Test
    void undoJournalPreservesMutationChronology() {
        XdStorageTransactionWorkspace ws = new XdStorageTransactionWorkspace();
        XdStorageCacheRecord r1 = recordOf(1L);
        XdStorageCacheRecord r2 = recordOf(2L);
        XdStorageCacheRecord r3 = recordOf(3L);

        ws.registerMutation("t1", 1L, r1);
        ws.registerMutation("t1", 2L, r2);
        ws.registerMutation("t1", 3L, r3);

        Iterator<XdStorageCacheRecord> it = ws.peekUndoLog("t1").iterator();
        assertSame(r1, it.next());
        assertSame(r2, it.next());
        assertSame(r3, it.next());
        assertFalse(it.hasNext(), "журнал хранит хронологию мутаций — rollback пойдет по ней в обратном порядке");
    }

    @Test
    void contoursAreIsolatedPerTransactionAndLazilyCreated() {
        XdStorageTransactionWorkspace ws = new XdStorageTransactionWorkspace();

        assertNull(ws.readObjects("tx-a"), "до первого чтения контур не создается");
        Map<Object, XdStorageCacheObjectChange> readA = ws.readObjectsOf(transaction("tx-a"));
        Map<Object, XdStorageCacheObjectChange> readB = ws.readObjectsOf(transaction("tx-b"));

        assertNotSame(readA, readB, "у транзакций раздельные контуры");
        assertSame(readA, ws.readObjectsOf(transaction("tx-a")), "контур ленивый, но единственный на транзакцию");
        assertNull(ws.readObjects("tx-c"), "чужой контур по-прежнему отсутствует");

        Map<Object, IXdStorageIdObservableWrapper> unidentified = ws.unidentifiedOf("tx-a");
        assertSame(unidentified, ws.unidentified("tx-a"));
    }

    @Test
    void hasChangesReflectsBothWriteSetAndUnidentifiedContours() {
        XdStorageTransactionWorkspace ws = new XdStorageTransactionWorkspace();
        assertFalse(ws.hasChanges("t1"));

        ws.unidentifiedOf("t1").put(new Object(), mock(IXdStorageIdObservableWrapper.class));
        assertTrue(ws.hasChanges("t1"), "неидентифицированные обертки тоже являются мутациями транзакции");

        XdStorageTransactionWorkspace ws2 = new XdStorageTransactionWorkspace();
        ws2.registerMutation("t1", 1L, recordOf(1L));
        assertTrue(ws2.hasChanges("t1"));
    }

    @Test
    void finishTransactionKeepsWriteSetUntilExplicitRemoval() {
        XdStorageTransactionWorkspace ws = new XdStorageTransactionWorkspace();
        ws.registerMutation("t1", 1L, recordOf(1L));
        ws.readObjectsOf(transaction("t1")).put(1L, new XdStorageCacheObjectChange(new Object(), XdStorageCacheChange
                .read, 1L));
        ws.unidentifiedOf("t1");

        ws.finishTransaction("t1");

        assertNull(ws.readObjects("t1"), "транзитные объекты чистятся при завершении");
        assertNull(ws.unidentified("t1"));
        assertEquals(1, ws.changes("t1").size(),
                "write-set изымает ТОЛЬКО протокол (commit/rollback вызывают removeChanges явно)");
        ws.removeChanges("t1");
        assertFalse(ws.hasChanges("t1"));
    }

    @Test
    void clearRemovesAllContours() {
        XdStorageTransactionWorkspace ws = new XdStorageTransactionWorkspace();
        ws.registerMutation("t1", 1L, recordOf(1L));
        ws.readObjectsOf(transaction("t1"));
        ws.unidentifiedOf("t1");

        ws.clear("t1");

        assertNull(ws.changes("t1"));
        assertNull(ws.peekUndoLog("t1"));
        assertNull(ws.readObjects("t1"));
        assertNull(ws.unidentified("t1"));
        assertFalse(ws.hasChanges("t1"));
    }

    @Test
    void removeChangesReturnsAndDetachesWriteSet() {
        XdStorageTransactionWorkspace ws = new XdStorageTransactionWorkspace();
        ws.registerMutation("t1", 1L, recordOf(1L));

        Map<Object, XdStorageCacheRecord> removed = ws.removeChanges("t1");
        assertEquals(1, removed.size());
        assertNull(ws.changes("t1"));
        assertFalse(ws.hasChanges("t1"));
    }

    // =========================================================================
    // ПОМОЩНИКИ
    // =========================================================================

    private StubCacheContext cachedContext;

    private StubCacheContext context() {
        if (cachedContext == null) {
            cachedContext = new StubCacheContext();
        }
        return cachedContext;
    }

    private XdStorageCacheRecord recordOf(final long id) {
        return XdStorageCacheRecord.newReadRecord(context(), context().idField(), entity(id, "v" + id));
    }

    private XdStorageTransaction transaction(final String txId) {
        return mkTx(txId, 100L);
    }
}
