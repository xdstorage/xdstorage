package io.github.xdstorage.resource.cache;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Протокол завершения транзакции кэша ресурсов: двухфазный коммит (prepare/perform),
 * финализация, откат по журналу мутаций и откат упавшего коммита (вынесен из
 * XdStorageResourceCache при разделении класса).
 *
 * <p>CACHE2PC (docs/tasks/2.1.0/34) — решение по аудиту двух фаз: тела prepare/perform
 * НАМЕРЕННО идентичны (один общий {@link #runCommitPhase}); фазы отличаются ТОЛЬКО
 * флагом записи ({@code prepareCommit()} / {@code performCommit()}). Двойная подача
 * изменений в коллектор — не дублирование, а механизм обновления: вторая фаза кладет
 * в коллектор состояние, ДОГНАВШЕЕ фазу prepare (отложенные идентификаторы, долетевшие
 * до необнаруженных оберток, и продвинутые состояния записей), поэтому каждая фаза
 * обязана кормить коллектор сама.
 *
 * <p>Откат: живой контур (все транзакции движка) играет журнал мутаций в обратном
 * порядке. Контур БЕЗ журнала (карта write-set без журнала) достижим только из
 * сторонних/моковых транзакций, у которых нет хронологии — в нем откат идет в порядке
 * итерации карты (см. {@link #rollback}).
 */
public final class XdStorageCacheCommitProtocol {

    private static final Logger log = LoggerFactory.getLogger(XdStorageCacheCommitProtocol.class);

    private final XdStorageCacheContext cache;

    /**
     * Binds the protocol to a cache context.
     *
     * @param cache the cache context (records registry, workspace, id field) this protocol operates on
     */
    public XdStorageCacheCommitProtocol(final XdStorageCacheContext cache) {
        this.cache = cache;
    }

    /**
     * Phase 1 of the two-phase commit: snapshots the transaction's write-set into the collector.
     * For every changed record the pending operation type and the old/new object pair (cloned
     * where required, and preferring the transaction's materialized instance for inserts and
     * updates) are added to {@code collector}, after which the record is marked prepare-committed;
     * not-yet-identified observable wrappers are contributed as inserts without an id. After this
     * phase the collector holds everything the resource needs to write the changes out to disk.
     *
     * @param transaction the committing transaction whose write-set is snapshotted
     * @param collector   the per-resource change buffer that receives the prepared change objects
     */
    public void prepareCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges collector) {
        runCommitPhase(transaction, collector, true);
    }

    /**
     * Phase 2 of the two-phase commit: see {@link #prepareCommit} — the bodies are
     * deliberately identical (CACHE2PC); only the record flag differs. The second feed
     * refreshes the collector with state that advanced after the prepare phase.
     *
     * @param transaction the committing transaction whose write-set is contributed
     * @param collector   the per-resource change buffer receiving the final change objects
     */
    public void performCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges collector) {
        runCommitPhase(transaction, collector, false);
    }

    /**
     * The single implementation of both commit phases (CACHE2PC: the duplication collapse —
     * the bodies were byte-identical except the flag and the debug label). For every
     * write-set record: compute the operation type and the old/new pair (preferring the
     * transaction's materialized instance for inserts and updates), feed the collector,
     * and set the record's phase flag. Unidentified observable wrappers are contributed as
     * inserts without an id — in BOTH phases, because the deferred id may land between
     * them and the second feed must carry the wrapper state forward.
     *
     * @param transaction the committing transaction
     * @param collector   the per-resource change buffer
     * @param prepare     {@code true} for phase 1 (marks records prepare-committed),
     *                    {@code false} for phase 2 (marks them perform-committed)
     */
    private void runCommitPhase(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges collector,
                                final boolean prepare) {
        final String transactionId = transaction.getTransactionId();

        final Map<Object, XdStorageCacheObjectChange> readObjects = cache.workspace().readObjectsOf(transaction);

        final Map<Object, XdStorageCacheRecord> map = cache.workspace().changes(transactionId);
        if (map != null) {
            final Iterator<Entry<Object, XdStorageCacheRecord>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                final XdStorageCacheRecord record = it.next().getValue();
                Object oldObject = null, newObject = null;
                XdStorageObjectOperationType type = null;
                synchronized (record) {
                    if (record.isInsertChange()) {
                        newObject = record.getNewObject();
                        type = XdStorageObjectOperationType.Insert;

                        if (newObject != null) {
                            final Object objectId = cache.idField().get(newObject);
                            if (objectId != null && readObjects != null && readObjects.containsKey(objectId)) {
                                newObject = readObjects.get(objectId).object;
                            }
                        }
                    } else if (record.isUpdateChange()) {
                        oldObject = record.getObject();
                        type = XdStorageObjectOperationType.Update;

                        if (oldObject != null) {
                            final Object objectId = cache.idField().get(oldObject);
                            if (objectId != null && readObjects != null && readObjects.containsKey(objectId)) {
                                newObject = readObjects.get(objectId).object;
                            }
                        }
                    } else if (record.isDeleteChange()) {
                        oldObject = record.getObject();
                        type = XdStorageObjectOperationType.Delete;
                    }

                    if (log.isDebugEnabled()) {
                        log.debug((prepare ? "PREPARE COMMIT " : "PERFORM COMMIT ") + type + " by transaction "
                                + transaction.getTransactionId() + "\r\n OLD OBJECT: " + oldObject + "\r\n NEW OBJECT: " + newObject);
                    }

                    collector.addChangeObject(type, record.getId(),
                            oldObject != null ? XdStorageObjectUtils.cloneObject(oldObject) : null,
                            newObject != null ? newObject : null);

                    if (prepare) {
                        record.prepareCommit();
                    } else {
                        record.performCommit();
                    }
                }
            }
        }

        final Map<Object, IXdStorageIdObservableWrapper> wrappers = cache.workspace().unidentified(transactionId);
        if (wrappers != null) {
            for (final IXdStorageIdObservableWrapper wrapper : wrappers.values()) {
                collector.addChangeObject(XdStorageObjectOperationType.Insert, null, null, wrapper);
            }
        }
    }

    /**
     * Finalizes a successful commit: discards the transaction's undo journal (the mutations
     * are permanent now), commits every write-set record, releases each record's lock and
     * closes the transaction's workspace contour. A record that reports an already-committed
     * state (nested/double commit path) is skipped with a warning instead of failing, since
     * its data is already permanent; records in any other illegal state fail loudly.
     *
     * @param transaction the transaction whose cached changes become permanent
     * @throws XdStorageException if a record's lock cannot be released
     */
    public void commit(final XdStorageTransaction transaction) throws XdStorageException {
        final String transactionId = transaction.getTransactionId();
        cache.workspace().removeUndoLog(transactionId); // изменения стали постоянными — журнал отката не нужен
        final Map<Object, XdStorageCacheRecord> map = cache.workspace().removeChanges(transactionId);
        if (map != null) {
            log.debug("commit of tx {}: {} record(s) became permanent", transactionId, map.size());
            final Iterator<Entry<Object, XdStorageCacheRecord>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                final XdStorageCacheRecord record = it.next().getValue();

                if (log.isDebugEnabled()) {
                    log.debug("COMMIT by transaction " + transaction.getTransactionId() + "\r\n OLD OBJECT: " + record.getObject() + "\r\n NEW OBJECT: " + record.getNewObject());
                }

                boolean committedNow = true;
                synchronized (record) {
                    try {
                        record.commit(transaction);
                    } catch (final XdStorageRuntimeException illegalState) {
                        // R-58: запись УЖЕ ЗАКОММИЧЕНА (повторный commit той же записи —
                        // вложенный/двойной контур коммита: флаги фаз потреблены первым
                        // проходом, state=committed). Пропускаем с WARN: данные уже
                        // постоянны, повторная фиксация ничего не меняет (и не захватывает
                        // latch повторно — unlock не нужен). Для записей в иных состояниях
                        // ошибка остается громкой.
                        if (record.isCommitedState()) {
                            log.warn("commit of tx {}: record {} already committed (double-commit path) — skipped",
                                    transactionId, record.getId());
                            committedNow = false;
                        } else {
                            throw illegalState;
                        }
                    }
                }

                if (committedNow) {
                    record.unlock(transaction);
                }
            }
        }

        cache.workspace().finishTransaction(transactionId);
    }

    /**
     * Reverts every cached change of the transaction. The undo journal — populated at the
     * first mutation of each record — is replayed in reverse order, so rollback strictly
     * inverts the transaction's chronology with exactly one undo point per record: inserts
     * by this transaction are undone (infrastructure-cache inserts keep serving their live
     * instance), other records are restored to their previous version, and every processed
     * record is unlocked. If no journal exists (legacy contour), the write-set map is rolled
     * back in iteration order instead. Finally the transaction's workspace contour is closed.
     *
     * @param transaction the transaction whose cached changes are reverted
     * @throws XdStorageException if a record's lock cannot be released
     */
    public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
        final String transactionId = transaction.getTransactionId();
        final Map<Object, XdStorageCacheRecord> map = cache.workspace().removeChanges(transactionId);
        final java.util.Deque<XdStorageCacheRecord> undoLog = cache.workspace().removeUndoLog(transactionId);

        // UNDO-CHANGE-LOG: проигрываем журнал мутаций В ОБРАТНОМ порядке — каждая мутация
        // имеет ровно одну точку отмены, и откат строго инвертирует хронологию транзакции.
        if (undoLog != null) {
            final java.util.Iterator<XdStorageCacheRecord> it = undoLog.descendingIterator();
            while (it.hasNext()) {
                final XdStorageCacheRecord record = it.next();

                if (log.isDebugEnabled()) {
                    log.debug("ROLLBACK by transaction " + transaction.getTransactionId() + "\r\n OLD OBJECT: " + record.getObject() + "\r\n NEW OBJECT: " + record.getNewObject());
                }

                // Фиксируем признак вставки строго по транзакционному контракту СУБД
                boolean isInsertOp = record.isInsertChange() && record.isChangedByTransaction(transaction);
                Object recordId = record.getId();

                try {
                    synchronized (record) {
                        if (isInsertOp && cache.isInfrastructureCache()) {
                            record.rollbackInsertKeepServing();
                        } else {
                            record.rollback();
                        }
                    }

                    if (isInsertOp && !cache.isInfrastructureCache() && recordId != null) {
                        cache.records().remove(recordId);
                    }
                    record.unlock(transaction);
                } catch (final RuntimeException e) {
                    // Откат не завершился: оставшиеся записи журнала содержат незакрытые мутации.
                    // Без этого ERROR половина отката была бы НЕЗаметной.
                    log.error("ROLLBACK FAILED mid-journal for transaction {} on record {} — {} undo entries left unprocessed",
                            transactionId, recordId, countRemaining(it), e);
                    throw e;
                }
            }
        } else if (map != null) {
            // Журнал отсутствует (контур старого кода/мока: все транзакции движка пишут
            // журнал при первой мутации записи — CACHE2PC). Хронологии в этом контуре нет,
            // поэтому откат идет в порядке итерации карты: детерминизм недостижим и не
            // требуется — сторонний контур по определению не несет движковых мутаций.
            final Iterator<Entry<Object, XdStorageCacheRecord>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                final XdStorageCacheRecord record = it.next().getValue();
                boolean isInsertOp = record.isInsertChange() && record.isChangedByTransaction(transaction);
                Object recordId = record.getId();
                synchronized (record) {
                    if (isInsertOp && cache.isInfrastructureCache()) {
                        record.rollbackInsertKeepServing();
                    } else {
                        record.rollback();
                    }
                }
                if (isInsertOp && !cache.isInfrastructureCache() && recordId != null) {
                    cache.records().remove(recordId);
                }
                record.unlock(transaction);
            }
        }

        if (map != null || undoLog != null) {
            log.debug("rollback of tx {}: {} record(s) reverted in reverse mutation order",
                    transactionId, map != null ? map.size() : 0);
        }

        cache.workspace().finishTransaction(transactionId);
    }

    /**
     * Restores the cache after the disk write-out of a commit failed. The change objects the
     * collector already committed are replayed in reverse effect: objects this transaction
     * deleted are put back into the records registry (reusing the write-set record when it
     * still holds one), objects it inserted are removed again, and updated objects get their
     * pre-transaction record back; each affected record is rolled back and unlocked. The
     * transaction's workspace contour is then closed.
     *
     * @param transaction the transaction whose commit failed on disk
     * @param committed   the change objects the collector reported as applied before the failure
     * @throws XdStorageException if a record's lock cannot be released
     */
    public void rollbackFailedCommit(final XdStorageTransaction transaction, final Collection<XdStorageObjectChange> committed) throws XdStorageException {
        final String transactionId = transaction.getTransactionId();
        cache.workspace().removeUndoLog(transactionId);
        final Map<Object, XdStorageCacheRecord> map = cache.workspace().removeChanges(transactionId);
        if (map != null) {
            for (final XdStorageObjectChange change : committed) {
                if (change.oldObject == null && change.newObject == null) {
                    continue;
                }
                final Object object = change.oldObject;
                final Object id = cache.idField().get(object != null ? object : change.newObject);
                XdStorageCacheRecord record = cache.records().get(id), oldRecord = map.get(id);
                if (record == null && object != null) { // was deleted by this transaction
                    cache.records().put(id, record = oldRecord != null ? oldRecord : cache.createReadRecord(object));
                } else if (object == null) { // was inserted by this transaction
                    record = cache.records().remove(id);
                } else { // was updated by this transaction
                    cache.records().put(id, record = oldRecord != null ? oldRecord : cache.createReadRecord(object));
                }
                if (record != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("ROLLBACK FAILED COMMIT by transaction " + transaction.getTransactionId() + "\r\n OLD OBJECT: " + record.getObject() + "\r\n NEW OBJECT: " + record.getNewObject());
                    }

                    synchronized (record) {
                        record.rollback();
                    }
                    record.unlock(transaction);
                }
            }
        }

        if (map != null) {
            log.debug("rollback-failed-commit of tx {}: {} record(s) restored from the collector's committed changes", transactionId, map.size());
        }

        cache.workspace().finishTransaction(transactionId);
    }

    private static int countRemaining(final java.util.Iterator<?> it) {
        int n = 0;
        while (it.hasNext()) {
            it.next();
            ++n;
        }
        return n;
    }
}
