package io.github.xdstorage.resource.cache;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Транзакционные контуры кэша ресурсов — все состояния, живущие ПОКА жива транзакция,
 * сгруппированы в одном месте (вынесены из XdStorageResourceCache при разделении класса):
 * <ul>
 *   <li>changes — write-set транзакции (id -> запись кэша); источник hasChanges/2PC;</li>
 *   <li>undoLogs — ЖУРНАЛ ОТКАТА (undo-change-log): записи кэша в ПОРЯДКЕ первой мутации
 *       транзакцией. rollback() проигрывает журнал в ОБРАТНОМ порядке — это дает полный
 *       контроль над откатом (каждая мутация имеет ровно одну точку отмены) и
 *       диагностическую картину "что именно делала транзакция" при сбое отката.
 *       Очищается при commit (изменения стали постоянными), rollback и clear;</li>
 *   <li>unidentified — неидентифицированные observable-обертки (объект еще без DB-id);</li>
 *   <li>readObjects — материализованные (прочитанные/транзитные) объекты транзакции.</li>
 * </ul>
 * Все карты — ConcurrentHashMap, ключ — id транзакции; сам объект не добавляет
 * синхронизации сверх той, что была у карт в XdStorageResourceCache.
 */
public class XdStorageTransactionWorkspace {

    private static final Logger log = LoggerFactory.getLogger(XdStorageTransactionWorkspace.class);

    /** Creates an empty workspace: all per-transaction contours are created lazily on demand. */
    public XdStorageTransactionWorkspace() {
    }

    private final Map<String, Map<Object, XdStorageCacheRecord>> changes = new ConcurrentHashMap<>();

    private final Map<String, Deque<XdStorageCacheRecord>> undoLogs = new ConcurrentHashMap<>();

    private final Map<String, Map<Object, IXdStorageIdObservableWrapper>> unidentified = new ConcurrentHashMap<>();

    private final Map<String, Map<Object, XdStorageCacheObjectChange>> readObjects = new ConcurrentHashMap<>();

    /**
     * Write-set транзакции (без создания): null, если транзакция ничего не меняла.
     *
     * @param transactionId id of the transaction whose write-set is requested
     * @return the transaction's write-set (id to cache record), or {@code null} if the
     *         transaction has not mutated anything
     */
    public Map<Object, XdStorageCacheRecord> changes(final String transactionId) {
        return changes.get(transactionId);
    }

    /** Write-set транзакции (с ленивым созданием). */
    private Map<Object, XdStorageCacheRecord> changesOf(final String transactionId) {
        Map<Object, XdStorageCacheRecord> map = changes.get(transactionId);
        if (map == null) {
            changes.putIfAbsent(transactionId, new ConcurrentHashMap<>());
            map = changes.get(transactionId);
        }
        return map;
    }

    /** Журнал отката транзакции (создается при первой регистрации мутации). */
    Deque<XdStorageCacheRecord> undoLog(final String transactionId) {
        return undoLogs.computeIfAbsent(transactionId, k -> new ArrayDeque<>());
    }

    /** Журнал отката (без создания): null, если транзакция ничего не мутировала. */
    Deque<XdStorageCacheRecord> peekUndoLog(final String transactionId) {
        return undoLogs.get(transactionId);
    }

    /** Неидентифицированные observable-обертки (без создания): null, если нет.
     *
     * @param transactionId id of the transaction whose unidentified wrappers are requested
     * @return the map of not-yet-identified observable wrappers, or {@code null} if the
     *         transaction has none
     */
    public Map<Object, IXdStorageIdObservableWrapper> unidentified(final String transactionId) {
        return unidentified.get(transactionId);
    }

    /** Неидентифицированные observable-обертки (с ленивым созданием).
     *
     * @param transactionId id of the transaction whose unidentified wrapper map is requested
     * @return the transaction's map of not-yet-identified observable wrappers, created empty
     *         if it did not exist yet
     */
    public Map<Object, IXdStorageIdObservableWrapper> unidentifiedOf(final String transactionId) {
        Map<Object, IXdStorageIdObservableWrapper> records = unidentified.get(transactionId);
        if (records == null) {
            unidentified.putIfAbsent(transactionId, new ConcurrentHashMap<>());
            records = unidentified.get(transactionId);
        }
        return records;
    }

    /** Материализованные объекты транзакции (без создания): null, если нет.
     *
     * @param transactionId id of the transaction whose materialized objects are requested
     * @return the map of the transaction's materialized (read/transit) objects, or {@code null}
     *         if it has none
     */
    public Map<Object, XdStorageCacheObjectChange> readObjects(final String transactionId) {
        return readObjects.get(transactionId);
    }

    /** Материализованные объекты транзакции (с ленивым созданием).
     *
     * @param transaction the transaction whose materialized-object map is requested
     * @return the transaction's map of materialized (read/transit) objects, created empty if
     *         it did not exist yet
     */
    public Map<Object, XdStorageCacheObjectChange> readObjectsOf(final XdStorageTransaction transaction) {
        final String transactionId = transaction.getTransactionId();
        Map<Object, XdStorageCacheObjectChange> transactionReadObjects = readObjects.get(transactionId);
        if (transactionReadObjects == null) {
            readObjects.putIfAbsent(transactionId, new ConcurrentHashMap<>());
            transactionReadObjects = readObjects.get(transactionId);
        }
        return transactionReadObjects;
    }

    /**
     * Регистрация мутации записи в write-set транзакции. Первая мутация записи
     * попадает в журнал отката (каждая запись — ровно один вход в журнал).
     *
     * @param transactionId id of the mutating transaction
     * @param objectId      the id of the mutated object
     * @param record        the cache record holding the pending mutation
     */
    public void registerMutation(final String transactionId, final Object objectId, final XdStorageCacheRecord record) {
        final Map<Object, XdStorageCacheRecord> map = changesOf(transactionId);
        if (map.put(objectId, record) == null) {
            undoLog(transactionId).addLast(record); // первая мутация записи — в журнал отката
        }
    }

    /** Есть ли у транзакции незакоммиченные мутации (write-set или неидентифицированные).
     *
     * @param transactionId id of the transaction to test
     * @return {@code true} if the transaction has a non-empty write-set or at least one
     *         unidentified observable wrapper
     */
    public boolean hasChanges(final String transactionId) {
        final Map<Object, XdStorageCacheRecord> transactionChanges = changes.get(transactionId);
        final Map<Object, IXdStorageIdObservableWrapper> transactionUnidentifiedObjects = unidentified.get(transactionId);
        return (transactionChanges != null && !transactionChanges.isEmpty()) ||
                (transactionUnidentifiedObjects != null && !transactionUnidentifiedObjects.isEmpty());
    }

    /** Изъять write-set (при commit/rollback/rollbackFailedCommit).
     *
     * @param transactionId id of the finishing transaction
     * @return the removed write-set (id to cache record), or {@code null} if there was none
     */
    public Map<Object, XdStorageCacheRecord> removeChanges(final String transactionId) {
        return changes.remove(transactionId);
    }

    /** Изъять журнал отката (при commit/rollback/rollbackFailedCommit).
     *
     * @param transactionId id of the finishing transaction
     * @return the removed undo journal (records in first-mutation order), or {@code null} if
     *         there was none
     */
    public Deque<XdStorageCacheRecord> removeUndoLog(final String transactionId) {
        return undoLogs.remove(transactionId);
    }

    /** Полная очистка транзакционного контура (cache.clear).
     *
     * @param transactionId id of the transaction whose whole contour is discarded
     */
    public void clear(final String transactionId) {
        readObjects.remove(transactionId);
        unidentified.remove(transactionId);
        changes.remove(transactionId);
        undoLogs.remove(transactionId);
        log.debug("transaction contour of {} cleared (write-set, undo journal, unidentified wrappers, read objects)", transactionId);
    }

    /** Завершение коммита/отката: транзитные и неидентифицированные объекты больше не нужны.
     *
     * @param transactionId id of the finished transaction
     */
    public void finishTransaction(final String transactionId) {
        unidentified.remove(transactionId);
        readObjects.remove(transactionId);
        log.debug("transient contours of {} closed (read objects, unidentified wrappers)", transactionId);
    }
}
