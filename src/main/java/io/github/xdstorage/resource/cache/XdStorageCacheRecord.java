package io.github.xdstorage.resource.cache;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.transaction.IXdStorageTransaction;
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
public final class XdStorageCacheRecord {

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
        record.transaction = transaction;
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

        boolean result = this.state == State.undefined
                || !isOwnerTxAlive
                || (this.state == State.locked && this.transaction == transaction && transaction.getTimestart() == timestamp)
                || (this.state == State.locked && isOwnedByTransactionChain(transaction))
                || (this.state == State.committed && readObjectByTransactionTimestamp > timestamp);

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
        return this.change != XdStorageCacheChange.read && this.transaction != null && this.transaction == transaction
                && transaction.getTimestart() == timestamp;
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
    public boolean isCommitedState() {
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
            if (isCommitedState()) {
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
            if (isCommitedState()) {
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
            if (!isCommitedState()) {
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
     * @throws XdStorageException if the wait exceeds the transaction's lock timeout or is
     *                            interrupted
     */
    public void lock(final XdStorageTransaction transaction) throws XdStorageException {
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
        boolean isOwnerAlive = this.transaction != null &&
                cache.isTransactionAlive(this.transaction);

        if (this.transaction != null && !isOwnerAlive && this.transaction != transaction) {
            log.debug("record {} adopted from dead transaction {} by {}",
                    id, this.transaction.getTransactionId(), transaction.getTransactionId());
            this.transaction = null;
            synchronized (locked) {
                locked.set(false);
                locked.notify();
            }
        }

        if (this.transaction == null || this.transaction != transaction) {
            synchronized (locked) {
                while (locked.get()) {
                    try {
                        final String transactionId = transaction.getTransactionId();

                        boolean currentOwnerAlive = this.transaction != null &&
                                cache.isTransactionAlive(this.transaction);
                        if (this.transaction != null && !currentOwnerAlive) {
                            log.debug("record {} owner {} died while waiting — adopted by {}",
                                    id, this.transaction.getTransactionId(), transactionId);
                            this.transaction = null;
                            locked.set(false);
                            break;
                        }

                        final Long startTime = blockingTime.remove(transactionId);
                        final Long currentTime = System.nanoTime();
                        if (startTime == null) {
                            blockingTime.put(transactionId, currentTime);
                        } else if ((currentTime - startTime) >= transaction.getTimeout() * 1_000_000) {
                            log.warn("record {} locked by transaction {} (change={}) — waiter {} timed out after {} ms",
                                    id, this.transaction != null ? this.transaction.getTransactionId() : null,
                                    change, transactionId, transaction.getTimeout());
                            throw new XdStorageException("transaction " + transactionId + " should be rolled back by timeout. Object with id " + id + " is locked for " + change);
                        } else {
                            blockingTime.put(transactionId, startTime);
                        }

                        locked.wait(transaction.getTimeout() / 5);
                    } catch (final InterruptedException e) {
                        throw new XdStorageException("locking object interrupted", e);
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
    public void prepareCommit() {
        firstPhaseCommit = true;
    }

    /**
     * Phase 2 of the record's two-phase commit protocol: arms the perform flag, completing the
     * precondition pair checked by {@code commit}.
     */
    public void performCommit() {
        secondPhaseCommit = true;
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
        if (!firstPhaseCommit || !secondPhaseCommit) {
            log.error("commit of record {} rejected: firstPhaseCommit={}, secondPhaseCommit={}, state={}, change={}, owner={}",
                    id, firstPhaseCommit, secondPhaseCommit, state, change,
                    transaction != null ? transaction.getTransactionId() : null);
            throw new XdStorageRuntimeException("Illegal state of record to commit");
        }

        this.prevObject = this.object;
        this.object = this.newObject;
        this.newObject = null;
        this.state = State.committed;
        this.previousState = State.undefined;

        firstPhaseCommit = false;
        secondPhaseCommit = false;

        this.prevTimestamp = this.timestamp;
        this.timestamp = tx.getTimestart();

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
    public void rollback() {
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
    public void rollbackInsertKeepServing() {
        final Object liveInstance = this.newObject;
        rollback();
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
}
