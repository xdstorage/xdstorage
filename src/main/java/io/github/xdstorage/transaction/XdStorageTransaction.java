package io.github.xdstorage.transaction;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.IXdStorageResourceObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The engine's transaction implementation: the unit of atomicity over the storage's resources,
 * driven to completion by {@code XdStorageTransactionManager} (two-phase commit, rollback,
 * rollback-only sweeps). The application-facing contract is {@code IXdStorageTransaction}; this
 * class adds the internals the manager and the resources need — critical sections, nested
 * (internal) transaction tracking, the commit serial and the per-transaction commit queues.
 *
 * <p>Thread-safety: one transaction may be used from many threads at once. The manager counts
 * active threads per transaction, and a rollback requested from another thread is deferred until
 * every active thread has left; state that must stay consistent is guarded by the critical
 * section or by atomics. Nested transactions suspend on their parent and cascade rollback-only
 * downward-only — a nested rollback never poisons the parent. Timeouts are enforced lazily (at
 * lock waits, latches and the manager's sweeps), not by an expiry timer.
 *
 * <p>Engine-internal — not part of the public API.
 */
public class XdStorageTransaction implements IXdStorageTransaction {

    private static final Logger log = LoggerFactory.getLogger(XdStorageTransaction.class);

    private final IXdStorageTransactionManager manager;

    private final long timeout;

    private final String transactionThreadId;

    private final String transactionId;

    private final Long timestart;

    /**
     * Снапшот-номер транзакции: значение глобального счетчика коммитов на момент НАЧАЛА транзакции.
     * Граница видимости SNAPSHOT: запись видна в старой версии, пока ее commitSerial больше снапшот-номера.
     * Для моков (без конструктора) остается null — кэш откатывается к timestart-сравнению.
     */
    private final Long snapshotSerial;

    /**
     * Глобальный счетчик фактических коммитов (порядок коммитов, независимый от часов).
     * Начинается с 1: значение 0 зарезервировано как «нет номера» — Mockito-моки транзакций
     * возвращают 0L для неснаппированного Long, и кэш откатывает их к timestart-сравнению.
     */
    private static final AtomicLong COMMIT_SEQUENCE = new AtomicLong(1);

    /**
     * Allocates the next global commit serial — the engine-wide order of commits, independent of
     * wall clocks. Feeds the snapshot visibility boundary: a record written under serial N
     * becomes visible to readers whose snapshot is at least N.
     *
     * @return the next value of the global commit counter
     */
    public static long nextCommitSerial() {
        return COMMIT_SEQUENCE.incrementAndGet();
    }

    private final Set<Object> resourcesIds;

    /**
     * The resources this transaction has registered, keyed by resource id; the commit iterates
     * an isolated snapshot of it.
     */
    protected final Map<Object, IXdStorageResourceObject> resources;

    /**
     * Resources with pending changes, queued in first-phase commit order (by registration
     * priority); drained at the start of phase 1.
     */
    protected final Queue<ComparableResourceObject> forCommit;

    /**
     * Resources write-locked during the current commit; released after phase 2 or a failed-commit
     * rollback.
     */
    protected final List<IXdStorageResourceObject> lockedResources;

    // R-57: индексные ключи, успешно вставленные в живые Б+Деревья этой транзакцией, —
    // подлежат КОМПЕНСИРУЮЩЕМУ удалению при откате (деревья мутируются ин-плейс и
    // не откатываются record-механизмом; без компенсации индекс вечно указывает на
    // несуществующие записи — "cannot load by reference" у читателей сканов).
    private final java.util.List<Object[]> indexKeyDeletesForRollback =
            new java.util.ArrayList<>();

    private final java.util.List<Object[]> indexKeysForRollback =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private final AtomicReference<TransactionState> transactionState = new AtomicReference<>(TransactionState.InProcess);

    private final XdStorageTransaction globalTransaction;

    private final Set<String> internalTransactions = Collections.synchronizedSet(new HashSet<>());

    private final AtomicBoolean hasInternalTransactions = new AtomicBoolean(false);

    private final AtomicBoolean rollbackOnly = new AtomicBoolean(false);

    private final AtomicLong critical = new AtomicLong(0);

    /** R-40: запас сверх таймаута внутренней tx, после которого родитель принудительно ее sweeping-откатывает. */
    private static final long INTERNAL_SWEEP_GRACE_MILLIS = 500;

    /**
     * R-45: serial коммита ЭТОЙ транзакции (0 = не коммитится). Резервируется ОДИН на всю
     * транзакцию ДО начала фаз коммита: все ее записи получают один и тот же номер, поэтому
     * читатель со снапшотом >= serial видит ЛИБО все эффекты транзакции, ЛИБО ни одного —
     * атомарность видимости между ресурсами (раньше у каждой записи был свой serial, и
     * читатель между фазами разных ресурсов видел смесь: новую систему со ссылками на
     * еще невидимые планеты — "cannot load by reference" в GraphPhantom).
     */
    private final AtomicLong commitSerial = new AtomicLong(0);

    /**
     * R-45: reserves this transaction's commit serial, once, before the commit phases start.
     * Every record this transaction writes then carries the same serial, so a snapshot reader
     * sees either all of the transaction's effects or none of them — atomic visibility across
     * resources. A no-op when a serial is already assigned.
     */
    public void assignCommitSerial() {
        commitSerial.compareAndSet(0, XdStorageTransaction.nextCommitSerial());
    }

    /** R-45: serial коммита; 0 — транзакция не в фазе коммита.
     *
     * @return the commit serial, or {@code 0} while the transaction is not committing
     */
    public long getCommitSerial() {
        return commitSerial.get();
    }

    /**
     * Creates a transaction. Root transactions pass {@code globalTransaction == null}; a nested
     * one receives its parent and is suspended on it by the manager right after creation.
     *
     * @param manager             the owning transaction manager
     * @param transactionThreadId the id string of the thread the transaction is bound to
     * @param globalTransaction   the parent transaction of a nested transaction, {@code null}
     *                            for a root transaction
     * @param timeout             the transaction timeout in milliseconds (enforced lazily)
     * @param transactionId       the unique transaction id
     */
    protected XdStorageTransaction(final IXdStorageTransactionManager manager, final String transactionThreadId,
                                   final XdStorageTransaction globalTransaction, final long timeout, final String transactionId) {
        this.manager = manager;
        this.transactionThreadId = transactionThreadId;
        this.globalTransaction = globalTransaction;
        this.timeout = timeout;
        this.transactionId = transactionId;
        this.timestart = System.nanoTime();
        this.snapshotSerial = COMMIT_SEQUENCE.get();
        this.resourcesIds = Collections.synchronizedSet(new HashSet<>());
        this.resources = new ConcurrentHashMap<>();
        this.forCommit = new PriorityBlockingQueue<>();
        this.lockedResources = new ArrayList<>();
    }

    /** Снапшот-номер транзакции; null для мок-транзакций, созданных мимо конструктора.
     *
     * @return the commit-counter value observed when the transaction began, or {@code null} for
     *         mock transactions created without the constructor
     */
    public Long getSnapshotSerial() {
        return snapshotSerial;
    }

    @Override
    public IXdStorageTransactionManager<?> getTransactionManager() {
        return manager;
    }

    @Override
    public IXdStorage getStorage() {
        return manager.getStorage();
    }

    /**
     * Marks the transaction finished and wakes every thread waiting in
     * {@link #waitForFinish()}. Called by the manager once the commit, rollback or sweep has
     * fully released the transaction's resources.
     */
    public void markFinished() {
        synchronized (transactionState) {
            transactionState.set(TransactionState.Finished);
            transactionState.notifyAll();
        }
    }

    /**
     * Blocks until the transaction is finished (see {@link #markFinished()}). The manager uses
     * it when beginning a transaction after a rollback-only one: the new transaction must not
     * start before the old one has released its locks. An interrupt while waiting fails with
     * {@code XdStorageRuntimeException}.
     */
    public void waitForFinish() {
        synchronized (transactionState) {
            while (transactionState.get() != TransactionState.Finished) {
                try {
                    transactionState.wait();
                } catch (InterruptedException e) {
                    throw new XdStorageRuntimeException("invalid state of transaction " + transactionId, e);
                }
            }
        }
    }

    /**
     * The parent of a nested transaction.
     *
     * @return the parent transaction, or {@code null} for a root transaction
     */
    public XdStorageTransaction getGlobalTransaction() {
        return globalTransaction;
    }

    /**
     * The parent transaction's id.
     *
     * @return the parent transaction's id, or {@code null} for a root transaction
     */
    public String getGlobalTransactionId() {
        return globalTransaction != null ? globalTransaction.getTransactionId() : null;
    }

    /**
     * Registers a nested ("internal") transaction on this parent: the parent must wait for it
     * before its own commit/rollback (see {@link #skipOrWaitForInternalTransactions()}). Called
     * by the manager right after the nested transaction is created.
     *
     * @param internalTransactionId the id of the nested transaction
     */
    public final void suspend(final String internalTransactionId) {
        synchronized (hasInternalTransactions) {
            internalTransactions.add(internalTransactionId);
            hasInternalTransactions.set(true);
        }
    }

    /**
     * Waits until every registered nested transaction has resumed, force-sweeping the abandoned
     * ones first. A nested transaction that died on an exception before its own commit/rollback
     * would block the parent here forever while still holding its record locks, so the parent
     * sweeps: internal transactions still bound to the current thread (their scope escaped) and
     * internal transactions past their timeout (plus a small grace) are marked rollback-only and
     * handed to the manager's asynchronous rollback sweeper, which releases the suspension via
     * {@link #resume(String)}.
     */
    public final void skipOrWaitForInternalTransactions() {
        if (hasInternalTransactions.get()) {
            synchronized (hasInternalTransactions) {
                while (hasInternalTransactions.get()) {
                    // R-40: ЗАЧИСТКА БРОШЕННЫХ ВНУТРЕННИХ ТРАНЗАКЦИЙ. Если вложенная tx умерла
                    // по исключению, не дойдя до commit/rollback, ее suspend() на родителе
                    // никогда не снимется штатно: родитель навеки зависает здесь, оставаясь
                    // "живым" держателем своих record-локов — все чужие обновления его узлов
                    // отказывают вечно (детерминированный ноль прогрессов nested-стресса R-37).
                    // Истекшая по таймауту внутренняя tx отправляется в штатный откат
                    // (registerRollbackOnlyTransaction — асинхронный уборщик с барьером
                    // активных потоков), который и снимет suspend через resume().
                    boolean swept = false;
                    for (final String internalId : internalTransactions.toArray(new String[0])) {
                        final XdStorageTransaction internal = (XdStorageTransaction) manager.getTransaction(internalId);
                        if (internal == null) {
                            // Запись-мусор: tx уже завершена, но resume не дошел — снимаем сами
                            resume(internalId);
                            swept = true;
                            continue;
                        }
                        // R-41: внутренняя tx, привязанная к ТЕКУЩЕМУ потоку, брошена этим же
                        // потоком (исключение из nested-области до commit/rollback) — завершить
                        // ее некому; ждем только 3.5с зомби-окна, отравляющего чужие спуски.
                        if (!internal.isRollbackOnly() && ((XdStorageTransactionManager) manager).isTransactionBoundToCurrentThread(internalId)) {
                            log.warn("Parent transaction {} force-sweeps internal transaction {} abandoned on the same thread (exception escaped its scope)",
                                    transactionId, internalId);
                            internal.markSelfAndChildrenRollbackOnly();
                            swept = true;
                            continue;
                        }
                        final long ageMillis = (System.nanoTime() - internal.getTimestart()) / 1_000_000;
                        if (ageMillis > internal.getTimeout() + INTERNAL_SWEEP_GRACE_MILLIS && !internal.isRollbackOnly()) {
                            log.warn("Parent transaction {} force-sweeps expired internal transaction {} (age {} ms > timeout {} ms) — it was abandoned without commit/rollback",
                                    transactionId, internalId, ageMillis, internal.getTimeout());
                            // Отравление цепочки: первый вызов каскадно помечает parent (ACID:
                            // упавшая вложенная tx обязана откатить родителя), второй — саму
                            // внутреннюю tx (ее globalTransaction уже rollback-only).
                            internal.markRollbackOnly();
                            if (!internal.isRollbackOnly()) {
                                internal.markRollbackOnly();
                            }
                            manager.registerRollbackOnlyTransaction(internal);
                            swept = true;
                        }
                    }
                    if (!swept) {
                        try {
                            hasInternalTransactions.wait(100);
                        } catch (final InterruptedException e) {
                            throw new XdStorageRuntimeException(e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes a finished nested transaction from this parent's wait set; when the last one is
     * gone, threads waiting in {@link #skipOrWaitForInternalTransactions()} are woken.
     *
     * @param internalTransactionId the id of the finished nested transaction
     */
    public final void resume(final String internalTransactionId) {
        synchronized (hasInternalTransactions) {
            internalTransactions.remove(internalTransactionId);
            if (internalTransactions.isEmpty()) {
                hasInternalTransactions.set(false);
                hasInternalTransactions.notifyAll();
            }
        }
    }

    public long getTimeout() {
        return timeout;
    }

    @Override
    public String getTransactionThreadId() {
        return transactionThreadId;
    }

    @Override
    public String getTransactionId() {
        return transactionId;
    }

    @Override
    public long getTimestart() {
        return timestart;
    }

    /**
     * Enters a critical section, refusing when the transaction is already rollback-only: a
     * normal operation fails fast instead of doing doomed work. Convenience overload of
     * {@link #startCriticalSection(boolean)} with {@code rollback == false}.
     *
     * @throws XdStorageException declared on the entry contract; the current implementation
     *                            signals the rollback-only case with a runtime exception instead
     */
    public void startCriticalSection() throws XdStorageException {
        startCriticalSection(false);
    }

    /**
     * Enters a critical section: increments the active-operation counter so a rollback-only mark
     * requested by another thread waits (inside {@code setRollbackOnly()}) instead of yanking
     * the state away under a running operation. With {@code rollback == false} (the normal case)
     * an already rollback-only transaction fails fast with {@code XdStorageRuntimeException};
     * the rollback machinery itself passes {@code true} to enter regardless.
     *
     * @param rollback whether the caller is the rollback machinery itself (bypasses the
     *                 rollback-only fast fail)
     */
    public void startCriticalSection(boolean rollback) {
        synchronized (critical) {
            // Если транзакция УЖЕ помечена как rollback-only, и это не вызов самого отката,
            // мгновенно прерываем операцию легитимным исключением СУБД!
            if (!rollback && rollbackOnly.get())
                throw new XdStorageRuntimeException("transaction " + transactionId + " marked as rollback only and will be rolled back");
            critical.incrementAndGet();
        }
    }

    /**
     * Leaves the critical section opened by {@code startCriticalSection(...)}; when the last one
     * ends, a competing thread waiting to set the rollback-only flag is woken.
     */
    public void finishCriticalSection() {
        synchronized (critical) {
            if (critical.decrementAndGet() == 0) {
                critical.notifyAll();
            }
        }
    }

    @Override
    public boolean isRollbackOnly() {
        return rollbackOnly.get();
    }

    private void setRollbackOnly() {
        synchronized (critical) {
            // ИСПРАВЛЕНИЕ: Проверяем, выполняется ли операция в контексте текущего транзакционного потока.
            // Если поток совпадает с transactionThreadId (или текущим Thread.currentThread()),
            // мы КАТЕГОРИЧЕСКИ запрещаем вызывать блокирующий critical.wait(), который усыплял поток сам в себе!
            // Взводим флаг отката атомарно, полностью ликвидируя внутритранзакционные дедлоки!
            final String currentThreadStr = String.valueOf(Thread.currentThread().getId());
            if (transactionThreadId != null && (transactionThreadId.equals(currentThreadStr) || transactionThreadId.contains(currentThreadStr))) {
                rollbackOnly.set(true);
                return;
            }

            // Для сторонних конкурирующих потоков сохраняем дефолтное безопасное ожидание
            while (critical.get() > 0) {
                try {
                    critical.wait(50); // Добавляем жесткий таймаут для предотвращения бесконечного зависания узлов
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
            rollbackOnly.set(true);
        }
    }

    @Override
    public void commit() {
        manager.commitTransaction(this);
    }

    @Override
    public void rollback() {
        manager.rollbackTransaction(this);
    }

    /**
     * A snapshot of the resources this transaction has registered (touched).
     *
     * @return a fresh collection of the registered resources
     */
    public Collection<IXdStorageResourceObject> getResources() {
        return new ArrayList<>(resources.values());
    }

    /**
     * Whether the resource is already registered in this transaction.
     *
     * @param resource the resource to test
     * @return {@code true} when the resource was registered by {@link #registerResource}
     */
    public boolean isResourceRegistered(final IXdStorageResourceObject resource) {
        return resourcesIds.contains(resource.getResourceId());
    }

    /**
     * Registers a touched resource for this transaction: adds it to the registry and queues it
     * for the commit (ordered by {@code commitOrder}, which fixes the first-phase commit order
     * across resources). Fails on a rollback-only transaction.
     *
     * @param resource    the resource the transaction touched
     * @param commitOrder the resource's position in the two-phase commit order
     * @throws XdStorageException when the transaction is marked rollback-only
     */
    public void registerResource(final IXdStorageResourceObject resource, final int commitOrder) throws XdStorageException {
        synchronized (critical) {
            if (rollbackOnly.get())
                throw new XdStorageException("transaction " + transactionId + " marked as rollback only and will be rolled back");

            resourcesIds.add(resource.getResourceId());
            resources.put(resource.getResourceId(), resource);
            forCommit.add(new ComparableResourceObject(resource, commitOrder));  // use priority queue
        }
    }

    /**
     * Runs the two-phase commit over the registered resources, driving the state holder through
     * its phases. Phase 1 drains the commit queue, write-locks every changed resource and lets
     * each write its changes (still invisible to other transactions); phase 2 lets every
     * registered resource clean caches and confirm versions. The per-resource change records of
     * phase 1 are collected into {@code firstPhaseCommittedResources} so a later failure can be
     * rolled back. On the first error the remaining work is skipped and the error is rethrown
     * (as {@code XdStorageRuntimeException} wrapping the cause) after the record is filled.
     *
     * @param state                        the commit-phase holder advanced during the two phases
     * @param firstPhaseCommittedResources receives the change record of every resource that
     *                                     completed phase 1
     * @throws XdStorageException declared on the commit contract; failures here surface as
     *                            {@code XdStorageRuntimeException} wrapping the cause
     */
    public void commitInternal(final XdStorageCommitTransactionStateHolder state,
                               final Map<Object, XdStorageTransactionResourceChanges> firstPhaseCommittedResources) throws XdStorageException {

        final XdStorageRuntimeException[] exceptions = new XdStorageRuntimeException[]{null};
        final AtomicBoolean hasError = new AtomicBoolean();

        // Делаем изолированный снимок зарегистрированных ресурсов СУБД для второй фазы
        final Collection<IXdStorageResourceObject> resourceToCommit = new ArrayList<>(resources.values());

        // Переводим транзакцию в состояние подготовки коммита (Phase 1 Commit)
        state.setState(XdStorageCommitTransactionState.PREPARING);

        // ИСПРАВЛЕНИЕ МНОГОПОТОЧНОСТИ: Категорически убираем хрупкий forCommit.iterator() и it.remove()!
        // Итератор PriorityBlockingQueue не предназначен для удаления элементов "на лету" под нагрузкой.
        // Применяем пуленепробиваемый паттерн Queue Drain: сливаем элементы очереди через poll()
        // во временный локальный список, гарантируя 100% изоляцию от ConcurrentModificationException.
        final List<ComparableResourceObject> activeChangesResources = new ArrayList<>();
        ComparableResourceObject checkObject;

        while ((checkObject = forCommit.poll()) != null) {
            final IXdStorageResourceObject res = checkObject.resource;
            // Если ресурс не содержит изменений в текущей транзакции — просто пропускаем его
            if (!res.hasChanges(this)) {
                continue;
            }
            // Блокируем измененный дисковый ресурс на коммит и добавляем в локальную цепочку
            res.lockForCommit(this);
            lockedResources.add(res);
            activeChangesResources.add(checkObject);
        }

        // Выполняем первую фазу коммита (запись изменений на диск) строго для отфильтрованных ресурсов
        for (final ComparableResourceObject resObject : activeChangesResources) {
            if (hasError.get()) {
                break;
            }

            try {
                final IXdStorageResourceObject res = resObject.resource;
                final XdStorageTransactionResourceChanges record = new XdStorageTransactionResourceChanges(res);
                firstPhaseCommittedResources.put(res.getResourceId(), record);
                res.performFirstPhaseCommit(this, record);
            } catch (final Throwable e) {
                if (hasError.get()) {
                    break;
                }
                hasError.set(true);
                exceptions[0] = new XdStorageRuntimeException(e);
            }
        }

        if (exceptions[0] != null)
            throw exceptions[0];

        state.setState(XdStorageCommitTransactionState.PREPARED);

        // performing second phase commit (финальная очистка кэшей и подтверждение версий)
        resourceToCommit.stream().forEach(resource -> {
            if (hasError.get()) {
                return;
            }

            try {
                final XdStorageTransactionResourceChanges record = new XdStorageTransactionResourceChanges(resource);
                resource.performSecondPhaseCommit(this, record);
            } catch (final Throwable e) {
                if (hasError.get()) {
                    return;
                }
                hasError.set(true);
                exceptions[0] = new XdStorageRuntimeException(e);
            }
        });

        if (exceptions[0] != null)
            throw exceptions[0];

        state.setState(XdStorageCommitTransactionState.FINISHED);

        lockedResources.stream().forEach(resource -> {
            resource.unlockAfterCommit(XdStorageTransaction.this);
        });
    }

    @Override
    public void registerIndexKeyForRollback(final Object tree, final Comparable key) {
        if (tree != null && key != null) {
            indexKeysForRollback.add(new Object[]{tree, key});
        }
    }

    @Override
    public void registerIndexKeyDeleteForRollback(final Object tree, final Comparable key, final Object value) {
        if (tree != null && key != null) {
            synchronized (indexKeyDeletesForRollback) {
                indexKeyDeletesForRollback.add(new Object[]{tree, key, value});
            }
        }
    }

    @Override
    public void rollbackIndexKeyDeletes() {
        final java.util.List<Object[]> pending;
        synchronized (indexKeyDeletesForRollback) {
            if (indexKeyDeletesForRollback.isEmpty()) {
                return;
            }
            pending = new java.util.ArrayList<>(indexKeyDeletesForRollback);
            indexKeyDeletesForRollback.clear();
        }
        // Обратный порядок регистрации: последнее удаление восстанавливается первым.
        for (int i = pending.size() - 1; i >= 0; --i) {
            final Object[] triple = pending.get(i);
            try {
                ((io.github.xdstorage.btree.XdStorageBTree) triple[0])
                        .insert((Comparable) triple[1], triple[2], getStorage(), this);
            } catch (final Throwable t) {
                // Ключ мог вернуться в дерево параллельной транзакцией (ин-плейс мутации
                // видны всем) — тогда двойная вставка штатно отклоняется. Более серьезный
                // сбой восстановления ключа — это дыра в индексе: WARN, не тишина.
                org.slf4j.LoggerFactory.getLogger(XdStorageTransaction.class)
                        .warn("rollback index-key DELETE compensation failed for {} (index may miss the key): {}",
                                triple[1], t.getMessage());
            }
        }
    }

    @Override
    public void rollbackIndexKeyInserts() {
        final java.util.List<Object[]> pending;
        synchronized (indexKeysForRollback) {
            if (indexKeysForRollback.isEmpty()) {
                return;
            }
            pending = new java.util.ArrayList<>(indexKeysForRollback);
            indexKeysForRollback.clear();
        }
        // Обратный порядок регистрации: последний вставленный ключ удаляется первым.
        for (int i = pending.size() - 1; i >= 0; --i) {
            final Object[] pair = pending.get(i);
            try {
                ((io.github.xdstorage.btree.XdStorageBTree) pair[0])
                        .delete((Comparable) pair[1], getStorage(), this);
            } catch (final Throwable t) {
                // Ключа может уже не быть (параллельная компенсация/удаление) — штатно.
                org.slf4j.LoggerFactory.getLogger(XdStorageTransaction.class)
                        .debug("rollback index-key compensation skipped for {}: {}", pair[1], t.getMessage());
            }
        }
    }

    /**
     * Undoes a failed two-phase commit: rolls every resource that completed phase 1 back to its
     * pre-commit state and releases the commit locks. Rollback errors are collected and logged,
     * not rethrown.
     *
     * @param firstPhaseCommittedResources the phase-1 change records filled by
     *                                     {@link #commitInternal}
     * @return {@code true} when every resource rolled back cleanly, {@code false} when at least
     *         one rollback failed
     */
    public boolean rollbackFailedCommit(final Map<Object, XdStorageTransactionResourceChanges> firstPhaseCommittedResources) {
        final List<XdStorageRuntimeException> exceptions = Collections.synchronizedList(new ArrayList<>());

        firstPhaseCommittedResources.entrySet().stream().forEach(entry -> {
            try {
                resources.get(entry.getKey()).rollbackPerformingFirstPhaseCommit(this, entry.getValue().getChangesObjects());
            } catch (final Throwable e) {
                exceptions.add(new XdStorageRuntimeException(e));
            }
        });

        lockedResources.stream().forEach(resource -> {
            resource.unlockAfterCommit(XdStorageTransaction.this);
        });

        if (!exceptions.isEmpty()) {
            exceptions.stream().forEach(e -> {
                log.error("rollback failed commit error", e);
            });
        }

        return exceptions.isEmpty();
    }

    /**
     * Rolls the whole transaction back. First the R-57 compensating deletion of index keys this
     * transaction inserted into live B-trees (tree mutations happen in place and are not covered
     * by the record-state rollback), then the state rollback of every registered resource, then
     * the commit locks are released. A failing resource rollback is rethrown as
     * {@code XdStorageRuntimeException}.
     */
    public void rollbackInternal() {
        // R-57: компенсация ин-плейс индексных мутаций ДО отката record-состояний —
        // компенсирующие delete сами регистрируют свои изменения в этой транзакции
        // и убираются общим откатом ниже. Зеркало для УДАЛЕННЫХ ключей: возврат ключа
        // держит дерево в read-committed состоянии после отката.
        rollbackIndexKeyInserts();
        rollbackIndexKeyDeletes();
        for (final IXdStorageResourceObject resource : resources.values()) {
            try {
                resource.rollback(this);
            } catch (final XdStorageException e) {
                throw new XdStorageRuntimeException("invalid state of database", e);
            }
        }

        lockedResources.stream().forEach(resource -> {
            resource.unlockAfterCommit(XdStorageTransaction.this);
        });
    }

    @Override
    public void markRollbackOnly() {
        if (globalTransaction == null || globalTransaction.isRollbackOnly() || isRollbackOnly()) {
            markSelfAndChildrenRollbackOnly();
        } else {
            globalTransaction.markRollbackOnly();
        }
    }

    /**
     * R-41: ИЗОЛИРОВАННЫЙ откат области транзакции — своя отметка rollback-only плюс
     * каскад ВНИЗ по внутренним транзакциям, БЕЗ отравления родителя. Контракт вложенного
     * отката: "аварийный ОТКАТ вложенной транзакции — родитель должен выжить". Делегирование
     * вверх (markRollbackOnly) здесь ломало сценарий 2 nested-стресса: каждый второй шаг
     * (nested rollback) ронял commit родителя "marked as rollback only" — детерминированно,
     * даже в один поток.
     */
    public void markSelfAndChildrenRollbackOnly() {
        setRollbackOnly();
        for (final String internalId : internalTransactions.toArray(new String[0])) {
            final XdStorageTransaction internal = (XdStorageTransaction) manager.getTransaction(internalId);
            if (internal != null && !internal.isRollbackOnly()) {
                internal.markSelfAndChildrenRollbackOnly();
            }
        }
        manager.registerRollbackOnlyTransaction(this);
    }

    /**
     * Identity check against another transaction reference.
     *
     * @param transaction the transaction to compare with
     * @return {@code true} when it is this very transaction instance
     */
    public boolean isTransaction(final XdStorageTransaction transaction) {
        return this == transaction;
    }

    /**
     * A registered resource paired with its commit-order priority: the commit queue drains in
     * ascending priority, which fixes the first-phase commit order across resources.
     *
     * <p>Engine-internal — not part of the public API.
     */
    protected class ComparableResourceObject implements Comparable<ComparableResourceObject> {

        /**
         * The resource this entry queues for commit.
         */
        public final IXdStorageResourceObject resource;

        private final Integer priority;

        ComparableResourceObject(final IXdStorageResourceObject resource, final int priority) {
            this.resource = resource;
            this.priority = priority;
        }

        /**
         * Orders entries by their commit priority (ascending).
         *
         * @param o the entry to compare with
         * @return a negative number, zero or a positive number as this entry's priority is
         *         less than, equal to or greater than the other's
         */
        @Override
        public int compareTo(ComparableResourceObject o) {
            return this.priority.compareTo(o.priority);
        }
    }

    private enum TransactionState {
        InProcess, Finished;
    }
}
