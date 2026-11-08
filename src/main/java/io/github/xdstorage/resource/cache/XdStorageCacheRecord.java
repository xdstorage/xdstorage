package io.github.xdstorage.resource.cache;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageRollbackOnlyCause;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Запись кэша ресурсов: MVCC-состояние одного объекта (id -> версии) — государственная
 * машина read/insert/update/delete x undefined/locked/committed, record-level latch,
 * протокол двухфазного коммита и вычисление ВИДИМОЙ версии объекта для транзакции.
 * Вынесена из XdStorageResourceCache при разделении класса (ранее — приватный
 * внутренний класс CacheRecord). Все мутации состояния выполняются под монитором
 * самой записи (synchronized (record) на стороне вызывающих).
 */
public class XdStorageCacheRecord {

    private static final Logger log = LoggerFactory.getLogger(XdStorageCacheRecord.class);

    /** Режим вычисления видимой версии: различия сайтов чтения сосредоточены здесь. */
    public enum View {
        /** Сканы (предикат/полный/watch/readByReference): базовый MVCC-переключатель. */
        SCAN,
        /** Чтение по id: + барьер чужого коммита (committingSerial) и prevObject-фолбэк (R-45). */
        BY_ID,
        /** Выписка на диск (readUnsafe): последнее закоммиченное состояние для чужих записей. */
        WRITE_OUT
    }

    private enum State {
        undefined, locked, committed, rolledback
    }

    private final XdStorageCacheContext cache;

    private Object id;

    private Object prevObject;

    private Object object;

    private Object newObject;

    private XdStorageTransaction transaction;

    private long timestamp = Long.MIN_VALUE;

    private long prevTimestamp = Long.MIN_VALUE;

    /**
     * LOSTUPD (docs/tasks/2.1.0/40): the wall-nano moment this version was COMMITTED
     * ({@code 0} when the record never went through a real two-phase commit — mock
     * fixtures, reopened pre-existing state). The write-conflict check consults this
     * instead of {@link #timestamp} (which carries the WRITER's timestart by pinned
     * contract) — comparing my read moment against the true commit moment is what makes
     * the check first-committer-wins.
     */
    private volatile long commitTimeNano;

    private long prevCommitTimeNano;

    private XdStorageCacheChange change;

    private XdStorageCacheChange previousChange;

    private State state;

    private State previousState;

    private boolean firstPhaseCommit;

    private boolean secondPhaseCommit;

    private AtomicBoolean locked = new AtomicBoolean(false);

    /** Версия записи: растет при каждой мутации состояния (для RC-освежения чтений). */
    private volatile long version;

    /**
     * Номер фактического коммита текущей версии (граница видимости SNAPSHOT-читателей).
     * 0 = версия пришла с диска/еще не коммитилась в памяти — видна всем читателям.
     */
    private volatile long commitSerial;

    private Map<String, Long> blockingTime = new HashMap<>();

    XdStorageCacheRecord(final XdStorageCacheContext cache) {
        this.cache = cache;
    }

    /**
     * Запись чистого чтения (объект пришел с диска / из живой структуры).
     *
     * @param cache  the cache context this record belongs to
     * @param field  the id field accessor used to derive the record's id from the object
     * @param object the materialized committed object the record will hold
     * @return a new cache record in the clean-read state
     */
    public static XdStorageCacheRecord newReadRecord(final XdStorageCacheContext cache, final XdStorageObjectIdField field, final Object object) {
        final XdStorageCacheRecord record = new XdStorageCacheRecord(cache);
        record.id = field.get(object);
        record.object = object;
        record.newObject = null;
        record.transaction = null;
        record.change = XdStorageCacheChange.read;
        record.state = State.undefined;
        return record;
    }

    /**
     * Запись незакоммиченной вставки: контент в newObject, object == null до коммита.
     *
     * @param cache       the cache context this record belongs to
     * @param field       the id field accessor used to derive the record's id from the object
     * @param newObject   the object being inserted (held as the pending content until commit)
     * @param transaction the inserting transaction, which becomes the record's owner
     * @return a new cache record in the uncommitted-insert state
     */
    public static XdStorageCacheRecord newInsertRecord(final XdStorageCacheContext cache, final XdStorageObjectIdField field, final Object newObject, final XdStorageTransaction transaction) {
        final XdStorageCacheRecord record = new XdStorageCacheRecord(cache);
        record.id = field.get(newObject);
        record.object = null;
        record.newObject = newObject;
        record.change = XdStorageCacheChange.insert;
        record.timestamp = transaction.getTimestart();
        record.state = State.locked;
        return record;
    }

    /**
     * Returns the id this record is registered under.
     *
     * @return the object id this record is keyed by in the cache registry (may be {@code null}
     *         for a not-yet-identified insert)
     */
    public Object getId() {
        return this.id;
    }

    /**
     * Returns the record's monotonic state version.
     *
     * @return the record's state version, incremented on every state mutation (used for
     *         READ_COMMITTED refresh detection)
     */
    public long getVersion() {
        return version;
    }

    /** R-58 диагностика: serial закоммиченной версии записи.
     *
     * @return the commit serial of the record's current version, or {@code 0} if it has not
     *         been committed in memory yet
     */
    public long getCommitSerial() {
        return commitSerial;
    }

    /** R-58 диагностика: timestamp текущей версии записи.
     *
     * @return the timestamp of the record's current version (the owning transaction's start
     *         time, or {@link java.lang.Long#MIN_VALUE} if never mutated)
     */
    public long getTimestamp() {
        return timestamp;
    }

    /** R-40 диагностика отказов: состояние записи на момент отказа обновления.
     * Дубликат-диагностика alongside {@link #getRecordTimestamp()} — intentional
     * (R-40's refusal dumps print both the state and the version stamp); the pair
     * stays, see also {@link #getRecordTimestamp()}.
     *
     * @return the record's current internal state as text (one of {@code undefined},
     *         {@code locked}, {@code committed}, {@code rolledback})
     */
    public String getState() {
        return String.valueOf(state);
    }

    /** R-40 диагностика отказов: id транзакции-владельца записи (или null).
     *
     * @return the id of the transaction currently owning this record, or {@code null} if unowned
     */
    public String getOwnerTransactionId() {
        return transaction != null ? transaction.getTransactionId() : null;
    }

    /** Владелец записи (транзакция, захватившая ее последней) или null.
     *
     * @return the transaction that captured this record last, or {@code null} if unowned
     */
    public XdStorageTransaction getOwnerTransaction() {
        return transaction;
    }

    /** R-40 диагностика отказов: timestamp записи на момент отказа обновления.
     * Дубликат-диагностика alongside {@link #getState()} — intentional (R-40's refusal
     * dumps print both); see also {@link #getState()}.
     *
     * @return the timestamp of the record's current version (see {@link #getTimestamp()})
     */
    public long getRecordTimestamp() {
        return timestamp;
    }

    /**
     * Whether this transaction may write the record: the state is fresh, the owner is dead
     * (the record is adoptable), the transaction already owns this very mutation (directly or
     * through the nested-transaction chain, R-40), or the record is committed and the
     * transaction started after the version's timestamp.
     *
     * @param transaction the transaction attempting the change
     * @return {@code true} if the change would not conflict with the record's current state
     */
    public boolean canBeChangedByTransaction(final XdStorageTransaction transaction) {
        boolean isOwnerTxAlive = this.transaction != null &&
                cache.isTransactionAlive(this.transaction);

        // =========================================================================
        // R-40: РЕЛАКСАЦИЯ ЦЕПОЧКИ ВЛОЖЕННОСТИ. Nested-транзакция — тот же логический
        // писатель, что и ее родитель: спуск parent-tx по индексу обновляет родительские
        // указатели узлов (record уходит в locked до коммита parent), после чего спуск
        // nested-tx по ТОМУ ЖЕ дереву получал отказ "can not be updated" на СВОЮ ЖЕ
        // блокировку — детерминированный ноль прогрессов под nested-стрессом (R-37).
        // Идентичность цепочки строгая (по ссылкам транзакций), чужие воркеры не
        // пересекаются никогда.
        // =========================================================================
        boolean result = this.state == State.undefined
                || !isOwnerTxAlive
                || (this.state == State.locked && this.transaction == transaction && transaction.getTimestart() == timestamp)
                || (this.state == State.locked && isOwnedByTransactionChain(transaction))
                || (this.state == State.committed && transaction.getTimestart() > timestamp);

        if (log.isDebugEnabled()) {
            log.debug("canBeChangedByTransaction state: " + this.state
                    + " \r\n this.transaction: " + this.transaction + "(" + (this.transaction != null ? this.transaction.getTransactionId() : null)
                    + ")\r\n transaction: " + transaction + "(" + transaction.getTransactionId()
                    + ")\r\n this.timestamp: " + timestamp + "\r\n tran.timestart: " + transaction.getTimestart());
        }

        return result;
    }

    /**
     * R-40: true, если владелец записи — звено цепочки вложенности вызывающей
     * транзакции (сам вызывающий, его parent, grandparent, ...).
     */
    private boolean isOwnedByTransactionChain(final XdStorageTransaction caller) {
        if (this.transaction == null) {
            return false;
        }
        for (XdStorageTransaction t = caller; t != null; t = t.getGlobalTransaction()) {
            if (t == this.transaction) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this transaction may write the record, judged against the timestamp at which the
     * transaction read the object: identical to {@link #canBeChangedByTransaction(XdStorageTransaction)}
     * except that a committed record requires the read timestamp (rather than the transaction's
     * start time) to be newer than the version's timestamp.
     *
     * @param transaction                        the transaction attempting the change
     * @param readObjectByTransactionTimestamp   the timestamp of the version the transaction read
     * @return {@code true} if the change would not conflict with the version the transaction saw
     */
    public boolean canBeChangedByTransaction(final XdStorageTransaction transaction, final long readObjectByTransactionTimestamp) {
        boolean isOwnerTxAlive = this.transaction != null &&
                cache.isTransactionAlive(this.transaction);

        // LOSTUPD: the committed-version comparison uses the TRUE commit moment when the
        // record has one (a real two-phase commit) — readTs older than the foreign commit
        // = a stale write, rejected. Records without a real commit (mock fixtures,
        // reopened state) fall back to the historical timestamp comparison.
        final long committedComparator = commitTimeNano != 0 ? commitTimeNano : timestamp;

        boolean result = this.state == State.undefined
                || !isOwnerTxAlive
                || (this.state == State.locked && this.transaction == transaction && transaction.getTimestart() == timestamp)
                || (this.state == State.locked && isOwnedByTransactionChain(transaction))
                || (this.state == State.committed && readObjectByTransactionTimestamp > committedComparator);

        if (log.isDebugEnabled()) {
            log.debug("canBeChangedByTransaction state: " + this.state
                    + " \r\n this.transaction: " + this.transaction + "(" + (this.transaction != null ? this.transaction.getTransactionId() : null)
                    + ")\r\n transaction: " + transaction + "(" + transaction.getTransactionId()
                    + ")\r\n this.timestamp: " + timestamp + "\r\n tran.timestart: " + readObjectByTransactionTimestamp);
        }

        return result;
    }

    /**
     * Whether the record currently holds an uncommitted mutation made by this transaction.
     *
     * @param transaction the transaction to test ownership against
     * @return {@code true} if the record's pending change is a non-read mutation stamped by
     *         exactly this transaction instance
     */
    public boolean isChangedByTransaction(final XdStorageTransaction transaction) {
        return this.change != XdStorageCacheChange.read && this.transaction != null && this.transaction == transaction;
    }

    /**
     * Returns the record's committed object.
     *
     * @return the committed (or last write-out) object held by the record; {@code null} for a
     *         record whose insert has not been committed yet
     */
    public Object getObject() {
        return this.object;
    }

    /**
     * Returns the record's pending (uncommitted) content.
     *
     * @return the pending (uncommitted) content of the record — the inserted or updated
     *         object, or {@code null} for a pending delete or when nothing is pending
     */
    public Object getNewObject() {
        return this.newObject;
    }

    /**
     * Replaces the record's pending (uncommitted) content.
     *
     * @param newObject the new pending content ({@code null} marks a pending delete)
     */
    public void setNewObject(final Object newObject) {
        this.newObject = newObject;
    }

    /**
     * Whether the record's current version is committed.
     *
     * @return {@code true} if the record's current version has been committed
     */
    public boolean isCommittedState() {
        return this.state == State.committed;
    }

    /**
     * Whether the record is a clean read.
     *
     * @return {@code true} if the record holds a clean read with no pending mutation
     */
    public boolean isReadChange() {
        return this.change == XdStorageCacheChange.read;
    }

    /**
     * Whether the record has a pending insert.
     *
     * @return {@code true} if the record's pending change is an insert
     */
    public boolean isInsertChange() {
        // Было: return this.change == Change.insert; (но из-за опечаток в CHM проверялся Change.change)
        // Стало: Прямое, строгое и кристальное вычисление типа транзакционной вставки!
        return this.change == XdStorageCacheChange.insert;
    }

    /**
     * Whether the record has a pending update.
     *
     * @return {@code true} if the record's pending change is an update
     */
    public boolean isUpdateChange() {
        return this.change == XdStorageCacheChange.update;
    }

    /**
     * Whether the record has a pending delete.
     *
     * @return {@code true} if the record's pending change is a delete
     */
    public boolean isDeleteChange() {
        return this.change == XdStorageCacheChange.delete;
    }

    /**
     * ВИДИМАЯ ВЕРСИЯ ОБЪЕКТА для транзакции — консолидированный MVCC-переключатель
     * шести сайтов чтения кэша (скан по предикату, watch, полный скан, unsafe-скан,
     * чтение по id, чтение по ссылке). Различия сайтов сведены к параметрам view
     * и committingSerial; сама выборка обязана выполняться под монитором записи.
     *
     * @param transaction      the reading transaction the visible version is resolved for
     * @param view             the read site requesting the version (scan, by-id or write-out)
     * @param committingSerial the single commit serial of another transaction currently in its
     *                         commit phase and visible to the reader's snapshot, or {@code 0}
     * @return the object version visible to the transaction ({@code null} means "not visible",
     *         e.g. an insert by another transaction or a delete the reader must observe)
     */
    public Object resolveVisible(final XdStorageTransaction transaction, final View view, final long committingSerial) {
        if (isUpdateChange()) {
            if (isCommittedState()) {
                return object;
            }
            if (isChangedByTransaction(transaction)) {
                return newObject;
            }
            if (view == View.BY_ID) {
                // R-45: чужая транзакция В ФАЗЕ КОММИТА с serial внутри снапшота читателя —
                // новое состояние обязано быть видно атомарно (все ресурсы коммита разом);
                // иначе чужой читатель видит последнюю ЗАКОММИЧЕННУЮ версию (prevObject):
                // живой record.object был бы состоянием чужой НЕЗАКОММИЧЕННОЙ транзакции.
                if (committingSerial != 0) {
                    return newObject != null ? newObject : object;
                }
                if (canBeChangedByTransaction(transaction)) {
                    return object;
                }
                return prevObject != null ? prevObject : object;
            }
            return object;
        }
        if (isInsertChange()) {
            if (isCommittedState()) {
                return object;
            }
            if (isChangedByTransaction(transaction)) {
                return newObject;
            }
            if (view == View.BY_ID && committingSerial != 0) {
                return newObject;
            }
            return null;
        }
        if (isDeleteChange()) {
            if (!isCommittedState()) {
                return !isChangedByTransaction(transaction) ? object : null;
            }
            return servesSnapshotVersion(transaction) ? prevObject : null;
        }
        // WRITE-OUT (коммит/откат ресурса): для чужих закоммиченных записей берем
        // ПОСЛЕДНЕЕ закоммиченное состояние, а не снапшотную route'ировку — иначе
        // коммиттер, начавший раньше соседа, перезаписал бы файл его старой версией
        return view == View.WRITE_OUT ? object : getObjectForTransaction(transaction);
    }

    /**
     * Marks the record as pending update by the transaction: archives the current version,
     * stamps the record with the transaction's start time and switches the change/state
     * markers to update/locked. Must run under the record's monitor.
     *
     * @param transaction the transaction making the update
     */
    public void markUpdate(IXdStorageTransaction transaction) {
        ++version;
        this.prevObject = this.object;
        // LOSTUPD diagnostics: the accept path of every write — what version stamp the
        // conflict checks will see for this record from now on.
        if (io.github.xdstorage.utils.XdStorageDiag.ENABLED) {
            log.info("markUpdate id={} tx={} version={} prevTs={} -> ts={} (writer timestart)", id,
                    transaction.getTransactionId(), version, prevTimestamp, timestamp);
        }

        // =========================================================================
        // СУБД ТАЙМСТAМП-БAРЬEР: Защищаем историческую сетку времени при холодном старте!
        // Перезаписываем prevTimestamp только если у ноды есть реальное время коммита.
        // Это полностью предотвращает превращение timestamp в Long.MIN_VALUE при параллельных
        // конфликтах сессий, возвращая выборку к исходным 1000 элементам!
        // =========================================================================
        if (this.timestamp != Long.MIN_VALUE) {
            this.prevTimestamp = this.timestamp;
        }
        this.timestamp = transaction.getTimestart();

        this.previousChange = this.change;
        this.change = XdStorageCacheChange.update;
        this.previousState = this.state;
        this.state = State.locked;
    }

    /**
     * Marks the record as pending delete by the transaction: archives the current version,
     * stamps the record with the transaction's start time and switches the change/state
     * markers to delete/locked. Must run under the record's monitor.
     *
     * @param transaction the transaction making the delete
     */
    public void markDelete(IXdStorageTransaction transaction) {
        ++version;
        this.prevObject = this.object;

        // Симметрично защищаем временную сетку для операций удаления
        if (this.timestamp != Long.MIN_VALUE) {
            this.prevTimestamp = this.timestamp;
        }
        this.timestamp = transaction.getTimestart();

        this.previousChange = this.change;
        this.change = XdStorageCacheChange.delete;
        this.previousState = this.state;
        this.state = State.locked;
    }

    /**
     * Captures the record for the transaction, blocking until any previous holder releases it
     * or is dead (its record is then adopted). Infrastructure-cache records skip the latch
     * entirely (R-27) — structural contention is serialized by the tree's own locks — and just
     * record ownership. Waits are chunked so the owner's death is noticed and the
     * transaction's lock timeout can abort the wait.
     *
     * @param transaction the transaction capturing the record
     * @throws XdStorageRuntimeException if the wait exceeds the transaction's lock timeout or is
     *                            interrupted
     */
    public void lock(final XdStorageTransaction transaction) {
        // R-27: инфраструктурные записи (деревья/узлы) НЕ захватывают latch. Структурная
        // конкуренция уже сериализована write-локом самого дерева; удержание latch-а до
        // конца транзакции-держателя на горячих узлах давало "locked for update" таймауты
        // при 25 воркерах. Своя-мутация определяется по record.transaction, проставленному
        // в createInsertRecord/markUpdate.
        if (cache.isInfrastructureCache()) {
            if (this.transaction == null) {
                this.transaction = transaction;
            }
            return;
        }

        if (this.transaction == null || this.transaction != transaction) {
            synchronized (locked) {
                while (locked.get()) {
                    try {
                        final String transactionId = transaction.getTransactionId();

                        final Long startTime = blockingTime.remove(transactionId);
                        final Long currentTime = System.nanoTime();
                        if (startTime == null) {
                            blockingTime.put(transactionId, currentTime);
                        } else if ((currentTime - startTime) >= transaction.getTimeout() * 1_000_000) {
                            log.warn("record {} locked by transaction {} (change={}) — waiter {} timed out after {} ms",
                                    id, this.transaction != null ? this.transaction.getTransactionId() : null,
                                    change, transactionId, transaction.getTimeout());
                            throw new XdStorageRuntimeException("transaction " + transactionId + " should be rolled back by timeout. Object with id " + id + " is locked for " + change);
                        } else {
                            blockingTime.put(transactionId, startTime);
                        }

                        locked.wait(transaction.getTimeout() / 5);
                    } catch (final InterruptedException e) {
                        throw new XdStorageRuntimeException("locking object interrupted", e);
                    }
                }
                locked.set(true);
                // Phase D: запись о времени ожидания больше не нужна после захвата —
                // иначе на записи течет по одному entry на каждую ждавшую транзакцию
                blockingTime.remove(transaction.getTransactionId());
            }
            this.transaction = transaction;
        }
    }

    /**
     * Releases the record previously captured by this transaction and wakes the next waiter;
     * for infrastructure-cache records it only clears ownership (no latch is held, R-27).
     *
     * @param transaction the transaction releasing the record
     * @throws XdStorageException if the transaction does not own the record's lock
     */
    public void unlock(final XdStorageTransaction transaction) throws XdStorageException {
        // R-27: инфраструктурные записи не держат latch — просто разъединяем владельца
        if (cache.isInfrastructureCache()) {
            if (this.transaction == transaction) {
                this.transaction = null;
            }
            return;
        }
        if (this.transaction == transaction) {
            this.transaction = null;
            synchronized (locked) {
                locked.set(false);
                locked.notify();
            }
        } else {
            log.warn("unlock of record {} rejected: caller {} does not own it (owner={})",
                    id, transaction.getTransactionId(), this.transaction != null ? this.transaction.getTransactionId() : null);
            throw new XdStorageException("trying to release locking object by the transaction is not locked this object");
        }
    }

    /**
     * Phase 1 of the record's two-phase commit protocol: arms the prepare flag so that the
     * subsequent {@code commit} accepts the record (both phase flags must be set by then).
     */
    /**
     * RBCOMMIT/RECIDENT (docs/tasks/2.1.0/47): the two-phase arming is TRANSACTION-SCOPED.
     * The historical single boolean pair was global to the record: any co-staged
     * transaction's rollback (the adoption handoff — an expired owner's record adopted by
     * a live one — or the nested chain) disarmed the flags of whoever's commit was in
     * flight, producing "Illegal state of record to commit" and the committed-with-null
     * zombies. The markers are keyed by the arming transaction; a rollback clears only
     * its own markers.
     */
    private static final Object LEGACY_ARMING_KEY = new Object();

    private final java.util.Set<Object> phaseOneArmedFor = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Set<Object> phaseTwoArmedFor = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static Object markerKey(final IXdStorageTransaction tx) {
        return tx != null ? tx : LEGACY_ARMING_KEY;
    }

    /**
     * RECIDENT (docs/tasks/2.1.0/47): whether the rolling transaction owns (or is a chain
     * ancestor of the owner of) the record's pending state — the full content reset is
     * allowed only then. {@code true} when nothing pending is owned (the legacy contours).
     */
    private boolean pendingStateOwnedByRollingChain(final IXdStorageTransaction rolling) {
        if (this.transaction == null || rolling == null) {
            return true;
        }
        if (this.transaction == rolling) {
            return true;
        }
        // the R-40 nested chain: the rolling tx is an ancestor of the pending owner
        Object link = this.transaction;
        while (link instanceof XdStorageTransaction) {
            final XdStorageTransaction chainTx = (XdStorageTransaction) link;
            if (chainTx == rolling) {
                return true;
            }
            link = chainTx.getGlobalTransaction();
        }
        return false;
    }

    public void prepareCommit() {
        prepareCommit(null);
    }

    public void prepareCommit(final IXdStorageTransaction transaction) {
        firstPhaseCommit = true;
        phaseOneArmedFor.add(markerKey(transaction));
    }

    /**
     * Phase 2 of the record's two-phase commit protocol: arms the perform flag, completing the
     * precondition pair checked by {@code commit}.
     */
    public void performCommit() {
        performCommit(null);
    }

    public void performCommit(final IXdStorageTransaction transaction) {
        secondPhaseCommit = true;
        phaseTwoArmedFor.add(markerKey(transaction));
    }

    /**
     * Makes the record's pending change permanent: the pending content becomes the committed
     * object, the previous object is archived for snapshot readers, and the version is stamped
     * with the transaction's single commit serial — the atomic-visibility boundary for
     * SNAPSHOT readers (R-45). Both two-phase flags must have been armed by
     * {@code prepareCommit} and {@code performCommit} first.
     *
     * @param tx the committing transaction supplying the commit serial and timestamp
     * @throws XdStorageRuntimeException if either two-phase commit flag was not armed (the
     *                                   record is in an illegal state for commit)
     */
    public void commit(IXdStorageTransaction tx) {
        ++version;
        boolean firstPhaseArmed = phaseOneArmedFor.remove(markerKey(tx));
        boolean secondPhaseArmed = phaseTwoArmedFor.remove(markerKey(tx));
        // the legacy contour compatibility: markers armed without a transaction (the
        // no-arg overloads, third-party/mocked contours) satisfy any commit
        if (!firstPhaseArmed) {
            firstPhaseArmed = phaseOneArmedFor.remove(LEGACY_ARMING_KEY);
        }
        if (!secondPhaseArmed) {
            secondPhaseArmed = phaseTwoArmedFor.remove(LEGACY_ARMING_KEY);
        }
        firstPhaseCommit = firstPhaseArmed;
        secondPhaseCommit = secondPhaseArmed;
        if (!firstPhaseArmed || !secondPhaseArmed) {
            final String detail = "record " + id
                    + " (tx " + (tx != null ? tx.getTransactionId() : null)
                    + ", firstPhaseCommit=" + firstPhaseArmed
                    + ", secondPhaseCommit=" + secondPhaseArmed
                    + ", state=" + state + ", change=" + change
                    + ", owner=" + (transaction != null ? transaction.getTransactionId() : null) + ")";
            log.error("commit of {} rejected: the record's two-phase flags were disarmed while its commit was in flight", detail);
            // RBCOMMIT (docs/tasks/2.1.0/45): the refusal carries the record id, the tx id,
            // the actual flag/state and — when the committing transaction carries a
            // rollback-only cause (the nested-expiry cascade, the timeout sweeper) — that
            // cause, in the same (by <site> at tx-age <n> ms) discipline as the rollback
            // marks.
            if (tx instanceof XdStorageTransaction && ((XdStorageTransaction) tx).getRollbackOnlyCause() != null) {
                throw XdStorageRollbackOnlyCause.buildFailure(
                        "Illegal state of record to commit: " + detail,
                        ((XdStorageTransaction) tx).getRollbackOnlyCause());
            }
            throw new XdStorageRuntimeException("Illegal state of record to commit: " + detail);
        }
        // RECIDENT (docs/tasks/2.1.0/47): the chain-shared single-slot record. When the
        // pending content is gone (nulled) while this transaction's phases were armed, a
        // chain member or a co-staged transaction has ALREADY finalized (or rolled back)
        // the shared record between this transaction's arming and its finalize. Publishing
        // the nulled content created the committed-with-null zombie; refusing it failed
        // workers whose data the chain had already published. The record's CURRENT state
        // stands (the chain's committed content, or the pre-transaction state after a
        // rollback) and this finalize becomes a no-op: the first finalize of a shared
        // record wins, loudly visible in the log. The historical null publish is dead.
        if (newObject == null && change != XdStorageCacheChange.delete && id != null) {
            log.warn("commit of record {} by tx {} skipped: the pending content was already finalized/reset by a chain or co-staged transaction (change={}, state={}, owner={}) — the record's current state stands, the shared record's first finalize wins",
                    id, tx != null ? tx.getTransactionId() : null, change, state,
                    transaction != null ? transaction.getTransactionId() : null);
            return;
        }

        this.prevObject = this.object;
        this.object = this.newObject;
        this.newObject = null;
        this.state = State.committed;
        this.previousState = State.undefined;

        firstPhaseCommit = false;
        secondPhaseCommit = false;
        phaseOneArmedFor.clear();
        phaseTwoArmedFor.clear();

        this.prevTimestamp = this.timestamp;
        this.timestamp = tx.getTimestart();
        // LOSTUPD: the TRUE commit moment — the version-stamp the write-conflict check
        // compares against (first-committer-wins). timestamp stays the writer's
        // timestart (pinned contract, see the field javadoc).
        this.prevCommitTimeNano = this.commitTimeNano;
        this.commitTimeNano = System.nanoTime();

        // LOSTUPD diagnostics: the durable version stamp (the conflict-check comparator).
        if (io.github.xdstorage.utils.XdStorageDiag.ENABLED) {
            log.info("commit id={} tx={} stampTs={} (writer timestart) change={} state={}", id,
                    tx.getTransactionId(), timestamp, change, state);
        }

        // НОМЕР ФАКТИЧЕСКОГО КОММИТА: граница видимости снапшотных читателей.
        // timestart владельца (метится при НАЧАЛЕ его транзакции) лжет читателям,
        // начавшим в окне [начало владельца, коммит]: версия еще не была видна,
        // когда они стартовали. R-45: ВСЕ записи транзакции получают ОДИН serial,
        // зарезервированный в начале коммита — атомарная видимость между ресурсами.
        final long txCommitSerial = tx instanceof XdStorageTransaction
                ? ((XdStorageTransaction) tx).getCommitSerial() : 0;
        this.commitSerial = txCommitSerial != 0 ? txCommitSerial : XdStorageTransaction.nextCommitSerial();

        // =========================================================================
        // ФИНАЛЬНЫЙ АРХИТЕКТУРНЫЙ ШТРИХ: Свобождаем тип изменения ноды!
        // Мы обязаны сохранить историю в previousChange для старых транзакций,
        // но текущий маркер change переводим в Change.undefined, чтобы транзакции
        // из будущего могли повторно использовать этот ID для вставок (insert)!
        // =========================================================================
        this.previousChange = this.change;
        this.change = XdStorageCacheChange.undefined;
    }

    /**
     * Reverts the record's pending change: the archived previous object and state become
     * current again, the pending content is dropped, both two-phase flags are cleared and the
     * timestamp is rolled back — the exact inverse of the mutation that created the record's
     * undo point. A late or racing rollback of an already-committed record is suppressed with
     * a warning instead of inverting committed data (R-58).
     */
    public void rollback(IXdStorageTransaction tx) {
        // RBCOMMIT/RECIDENT (docs/tasks/2.1.0/47): the arming markers of the rolling
        // transaction are dropped unconditionally — a foreign rollback must never disarm
        // another transaction's in-flight phases.
        phaseOneArmedFor.remove(markerKey(tx));
        phaseTwoArmedFor.remove(markerKey(tx));
        // R-58: откат ЗАКОММИЧЕННОЙ записи запрещен. Поздний/гонный откат (mark-rollback-only
        // «догнавший» уже идущий или завершенный коммит; commit-флаги к этому моменту уже
        // сброшены commit'ом) инвертировал закоммиченную вставку/обновление:
        // object = prevObject (null для вставок) при state=committed — «зомби-запись»,
        // которую чтение by-reference считало видимой и падало на пустом содержимом
        // ("cannot load by reference"). Легитимные откаты работают на state=locked/undefined
        // и не затрагиваются; rollbackFailedCommit подменяет записи старыми инстансами
        // до вызова rollback — тоже не затронут.
        if (this.state == State.committed) {
            log.warn("rollback ignored for record {} — record already committed (change={}, owner={}); late/racing rollback suppressed",
                    id, change, transaction != null ? transaction.getTransactionId() : null);
            return;
        }
        // RECIDENT: the CONTENT reset is ownership-guarded. When the record's pending
        // state has been handed to another transaction (the adoption handoff: an expired
        // owner's record adopted by a live co-stager, whose commit may be in flight
        // between its phase arming and its finalize), the rolling transaction's rollback
        // must not null the pending content nor reset the state under the new owner —
        // the historical behavior disarmed the new owner's phases and produced the
        // committed-with-null zombies. The owner itself and its chain ancestors (R-40)
        // keep the full reset.
        if (!pendingStateOwnedByRollingChain(tx)) {
            log.warn("rollback of record {} by tx {} skipped the content reset — the pending state is owned by another live transaction (adoption handoff)",
                    id, tx != null ? tx.getTransactionId() : null);
            return;
        }
        this.object = this.prevObject;
        this.prevObject = null;
        this.newObject = null;
        this.state = this.previousState;
        this.previousState = State.undefined;
        this.change = previousChange;
        this.previousChange = XdStorageCacheChange.undefined;

        firstPhaseCommit = false;
        secondPhaseCommit = false;

        this.timestamp = this.prevTimestamp;
        this.prevTimestamp = Long.MIN_VALUE;
    }

    /**
     * R-40: откат INSERT-записи инфраструктурного (B+Tree) объекта. Мутации дерева
     * выполняются НА МЕСТЕ на живом каноническом инстансе: откаченный сплит физически
     * остается в структуре (parent.children), поэтому удалять запись из кэша нельзя —
     * каждый спуск через узел-сироту падал "does not exists". Запись возвращается в
     * состояние чистого чтения текущего живого инстанса: узел разрешим и обновляем
     * любой транзакцией (state=undefined), его содержание совпадает с той версией
     * структуры, на которую и ссылается память дерева.
     */
    public void rollbackInsertKeepServing(IXdStorageTransaction tx) {
        final Object liveInstance = this.newObject;
        rollback(tx);
        this.object = liveInstance;
        this.state = State.undefined;
        this.change = XdStorageCacheChange.read;
        this.transaction = null;
        this.timestamp = Long.MIN_VALUE;
        this.prevTimestamp = Long.MIN_VALUE;
        log.debug("infra insert rollback: record {} stays serving the live instance (R-40)", id);
    }

    /**
     * R-45: если запись захвачена чужой транзакцией, УЖЕ ВСТУПИВШЕЙ в фазу коммита, и
     * снапшот читателя включает ее единый commit-serial — возвращает этот serial
     * (новое состояние записи обязано быть видно читателю атомарно). Иначе 0.
     *
     * @param reader the snapshot-reading transaction testing visibility
     * @return the owner's commit serial if it is inside the reader's snapshot (so the record's
     *         new state must be resolved as visible), otherwise {@code 0}
     */
    public long committingSerialVisibleTo(final XdStorageTransaction reader) {
        final XdStorageTransaction owner = this.transaction;
        if (owner == null || owner == reader || !firstPhaseCommit) {
            return 0;
        }
        final long ownerSerial = owner instanceof XdStorageTransaction
                ? ((XdStorageTransaction) owner).getCommitSerial() : 0;
        if (ownerSerial == 0) {
            return 0;
        }
        final Long readerSnapshot = reader.getSnapshotSerial();
        return readerSnapshot != null && readerSnapshot >= ownerSerial ? ownerSerial : 0;
    }

    private Object getObjectForTransaction(XdStorageTransaction transaction) {
        Object object;
        if (isChangedByTransaction(transaction)) {
            // СОБСТВЕННЫЕ незакоммиченные мутации: newObject (null после своего delete = скрыто)
            object = this.newObject;
        } else if (servesCommittedVersion(transaction)) {
            // читатель зафиксировал снапшот ДО фактического коммита текущей версии — видит предыдущую
            object = this.prevObject;
        } else if (canBeChangedByTransaction(transaction)) {
            object = this.object;
        } else {
            object = this.prevObject;
        }
        return object;
    }

    /**
     * SNAPSHOT-гейт видимости версии: читатель, начавший транзакцию раньше фактического
     * коммита версии (порядок по счетчику коммитов, а не по часам), видит предыдущую версию.
     * Для мок-транзакций (без снапшот-номера) и незакоммиченных (locked) записей границей
     * остается timestart владельца. READ_COMMITTED: гейт выключен — всегда последнее
     * закоммиченное состояние.
     *
     * <p>ИНФРАСТРУКТУРА (граф B+дерева: записи XdStorageBTree/XdStorageBTreeNode) —
     * READ COMMITTED ВСЕГДА, независимо от уровня изоляции хранилища: индекс обязан
     * разрешать ключи по последней ЗАКОММИЧЕННОЙ структуре. Снапшот-замороженное
     * дерево скрывает ключи, закоммиченные после начала читателя, и превращает
     * поиск по индексу в ложное «does not exist» / «cannot load by reference»
     * (класс отказов planet-3, прогон мульпоточных стрессов 2026-10-24). Данные
     * сущности сохраняют свое снапшотное представление — гейт выключен только
     * для инфраструктурных кэшей.
     */
    private boolean servesCommittedVersion(XdStorageTransaction transaction) {
        if (cache.isInfrastructureCache() || cache.getIsolationLevel() == XdStorageIsolationLevel.READ_COMMITTED) {
            return false;
        }
        if (this.state == State.committed) {
            // 0 = «нет снапшот-номера» (мок-транзакции Mockito) — откат к timestart-сравнению
            final Long readerSerial = transaction.getSnapshotSerial();
            return readerSerial != null && readerSerial > 0
                    ? readerSerial < this.commitSerial
                    : transaction.getTimestart() < this.timestamp;
        }
        return transaction.getTimestart() < this.timestamp;
    }

    /**
     * SNAPSHOT: читатель с timestart < версии видит предыдущую закоммиченную версию.
     * READ_COMMITTED: читатель всегда видит последнее закоммиченное состояние (object).
     * Инфраструктура (граф B+дерева): READ COMMITTED всегда — см. {@link #servesCommittedVersion}.
     */
    private boolean servesSnapshotVersion(final XdStorageTransaction transaction) {
        return !cache.isInfrastructureCache()
                && cache.getIsolationLevel() != XdStorageIsolationLevel.READ_COMMITTED
                && transaction.getTimestart() < this.timestamp;
    }

    public XdStorageCacheRecord clone() {
        XdStorageCacheRecord result = new XdStorageCacheRecord(cache);
        result.fill(this);
        return result;
    }

    public void fill(XdStorageCacheRecord src) {
        this.id = src.id;
        this.prevObject = src.prevObject;
        this.object = src.object;
        this.newObject = src.newObject;
        this.transaction = src.transaction;
        this.timestamp = src.timestamp;
        this.prevTimestamp = src.prevTimestamp;
        this.commitTimeNano = src.commitTimeNano;
        this.prevCommitTimeNano = src.prevCommitTimeNano;
        this.change = src.change;
        this.previousChange = src.previousChange;
        this.state = src.state;
        this.previousState = src.previousState;
        this.firstPhaseCommit = src.firstPhaseCommit;
        this.secondPhaseCommit = src.secondPhaseCommit;
        this.locked = new AtomicBoolean(src.locked.get());
        this.version = src.version;
        this.commitSerial = src.commitSerial;
    }
}
