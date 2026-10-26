package io.github.xdstorage.transaction;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.metrics.XdStorageMetrics;
import io.github.xdstorage.resource.IXdStorageResourceObject;
import io.github.xdstorage.services.XdStorageServicesLocator;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The engine's transaction manager: begins root and nested transactions bound to the calling
 * thread, commits them in two phases, rolls them back, and runs the asynchronous rollback-only
 * sweep for transactions marked by timeout or conflict. Its core protection is a thread
 * reference counter: the manager counts how many threads are currently executing inside each
 * transaction, keeps such a transaction alive, and makes a background sweep defer and
 * reschedule its rollback until every active thread has left — a parallel-stream operation is
 * never shot in the back by the sweeper. Timeouts are enforced lazily (at lock waits, latches
 * and this sweep), not by an expiry timer.
 *
 * <p>Thread-safety: the registries are concurrent maps; one transaction may be used from many
 * threads. Nested transactions suspend on their parent and roll back downward-only — a nested
 * rollback never poisons the parent, and the parent waits for open nested transactions at its
 * own commit.
 *
 * <p>Engine-internal — not part of the public API.
 */
public class XdStorageTransactionManager implements IXdStorageTransactionManager<XdStorageTransaction> {

    private static final Logger log = LoggerFactory.getLogger(XdStorageTransactionManager.class);

    /**
     * The storage's service locator (storage, metrics, triggers, executor, cloner).
     */
    protected final XdStorageServicesLocator services;

    private final Map<String, XdStorageTransaction> transactionsById;

    /**
     * TREEFILE (docs/tasks/2.1.0/41): пиковое число одновременных транзакционных ПОТОКОВ
     * за жизнь хранилища (см. точку замера в beginTransaction). Начинается с 1: монотонное
     * однопоточное хранилище никогда не поднимает пик.
     */
    private final java.util.concurrent.atomic.AtomicInteger infraThreadHighWaterMark =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private final Map<String, String> threadsByTransactionId;
    private final Map<String, XdStorageTransaction> transactionsByThreadId;

    // АТОМАРНЫЙ ТРЕКЕР ПОТОКОВ: Считает количество параллельных ForkJoin-потоков,
    // выполняющих операции внутри конкретной транзакции прямо сейчас.
    private final Map<String, AtomicLong> transactionActiveThreadsCounter = new ConcurrentHashMap<>();

    /**
     * Creates a manager with empty transaction registries.
     *
     * @param provider the storage's service locator
     */
    public XdStorageTransactionManager(final XdStorageServicesLocator provider) {
        this.services = provider;
        this.transactionsById = new ConcurrentHashMap<>();
        this.threadsByTransactionId = new ConcurrentHashMap<>();
        this.transactionsByThreadId = new ConcurrentHashMap<>();
    }

    // УПРАВЛЕНИЕ СЧЕТЧИКАМИ ССЫЛОК ПОТОКОВ СУБД
    /**
     * Counts one more thread as executing inside the transaction (thread reference counting).
     * While the count is above zero, {@code isTransactionAlive} reports the transaction alive
     * and the rollback-only sweep defers and reschedules its rollback. A no-op for a
     * {@code null} id.
     *
     * @param transactionId the id of the transaction being entered
     */
    public void incrementActiveThreads(final String transactionId) {
        if (transactionId != null) {
            transactionActiveThreadsCounter.computeIfAbsent(transactionId, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * Counts one thread out of the transaction; a count dropping to zero unregisters the
     * counter, so a pending sweep stops deferring. A no-op for a {@code null} id or a
     * transaction without a counter.
     *
     * @param transactionId the id of the transaction being left
     */
    public void decrementActiveThreads(final String transactionId) {
        if (transactionId != null) {
            AtomicLong counter = transactionActiveThreadsCounter.get(transactionId);
            if (counter != null) {
                if (counter.decrementAndGet() <= 0) {
                    transactionActiveThreadsCounter.remove(transactionId);
                }
            }
        }
    }

    private XdStorageMetrics metrics() {
        // mock-локаторы в юнит-тестах возвращают null — счетчики опциональны, работа нет
        final XdStorageMetrics m = services.getMetrics();
        return m != null ? m : new XdStorageMetrics();
    }

    @Override
    public IXdStorage getStorage() {
        return services.getStorage();
    }

    @Override
    public boolean isTransactionAlive(final XdStorageTransaction transaction) {
        if (transaction == null) {
            return false;
        }

        // 1. Проверяем физическое наличие транзакции в глобальном реестре СУБД
        boolean exists = this.transactionsById.containsKey(transaction.getTransactionId());
        if (!exists) {
            return false;
        }

        // 2. ИСПРАВЛЕНИЕ АКТИВНОСТИ: Проверяем, обрабатывается ли транзакция параллельными потоками прямо сейчас.
        // Если счетчик активных потоков > 0 (идет parallelStream), мы удерживаем статус alive = true,
        // предотвращая "выстрел в спину" активным геттерам. Если 0 (одиночный поток или модульный тест) —
        // строго возвращаем каноническое правило СУБД: транзакция мертва, если выставлен rollbackOnly!
        AtomicLong activeThreads = transactionActiveThreadsCounter.get(transaction.getTransactionId());
        if (activeThreads != null && activeThreads.get() > 0) {
            return true;
        }

        // Идеальное сохранение оригинального контракта для модульных тестов в один поток!
        return !transaction.isRollbackOnly();
    }

    /**
     * TREEFILE (docs/tasks/2.1.0/41): whether any transaction OTHER than {@code self}
     * currently EXISTS in the storage. The rollback's infra recovery
     * (recoverInfraFromCommittedFiles) swaps canonical B+tree graphs — the one operation
     * a concurrent transaction can never tolerate (it holds cross-references — tree
     * instances, nodes, parsed records — between its own steps, even between its own
     * transactions a worker thread races the swap through the search flow's
     * restore/find/delete sequences). Only a storage with NO other transaction at all —
     * the deterministic single-writer case, the shape of the task-41 repro — is provably
     * safe; the swap is refused otherwise (the documented W-4 boundary: the write-path
     * rewrite owns concurrent-history rollback).
     *
     * @param self the calling (rolling-back) transaction, excluded from the check
     * @return {@code true} when at least one other transaction exists
     */
    public boolean anyOtherTransactionExists(final XdStorageTransaction self) {
        return transactionsById.size() > 1;
    }

    /**
     * TREEFILE (docs/tasks/2.1.0/41): whether this storage has EVER run transactions on
     * more than one thread concurrently. A multi-thread storage can never take the
     * rollback's canonical-graph reload: worker runnables hold cross-references into the
     * graphs BETWEEN their transactions (the search flow's restore/find/delete sequence
     * alone defeats every transaction-lifetime gate), so only a storage that has been
     * single-transaction-thread for its whole life is provably holder-free. Internal
     * (nested) transactions share the parent's thread id and never raise the mark.
     *
     * @return {@code true} once concurrent transaction threads have been observed
     */
    public boolean hasEverRunConcurrentTransactionThreads() {
        return infraThreadHighWaterMark.get() > 1;
    }

    @Override
    public XdStorageTransaction beginTransaction(final long timeout) {
        final String currentThreadId = getCurrentThreadId();
        final XdStorageTransaction transaction = transactionsByThreadId.get(currentThreadId);
        return beginTransaction(transaction, timeout);
    }

    @Override
    public XdStorageTransaction beginTransaction(final XdStorageTransaction transaction, long timeout) {
        final String currentThreadId = getCurrentThreadId();
        final XdStorageTransaction newTransaction;
        final String newTransactionId = generateTransactionId();

        if (transaction == null) {
            newTransaction = createTransaction(this, currentThreadId, timeout, newTransactionId);
        } else if (transaction.isRollbackOnly()) {
            transaction.waitForFinish();
            newTransaction = createTransaction(this, currentThreadId, timeout, newTransactionId);
        } else {
            newTransaction = createTransaction(this, currentThreadId, transaction, timeout, newTransactionId);
            transaction.suspend(newTransaction.getTransactionId());
        }

        threadsByTransactionId.put(newTransactionId, currentThreadId);
        transactionsByThreadId.put(currentThreadId, newTransaction);
        transactionsById.put(newTransactionId, newTransaction);
        transactionActiveThreadsCounter.put(newTransactionId, new AtomicLong(0));
        // TREEFILE (docs/tasks/2.1.0/41): пиковая одновременность транзакционных ПОТОКОВ
        // за жизнь хранилища. Внутренние (nested) транзакции разделяют thread-id родителя
        // и пик не поднимают; отдельные воркер-потоки — поднимают. Признак «хранилище
        // когда-либо работало в несколько транзакционных потоков» навсегда запрещает
        // перезагрузку канонических графов при откате (recoverInfraFromCommittedFiles):
        // воркер-раннабли держат ссылки на граф между своими транзакциями.
        infraThreadHighWaterMark.accumulateAndGet(transactionsByThreadId.size(), Math::max);
        metrics().increment(XdStorageMetrics.TRANSACTIONS_BEGUN);
        metrics().increment(XdStorageMetrics.ACTIVE_TRANSACTIONS);

        log.debug(String.format("Started transaction %s (timeout %d ms%s)", newTransactionId, timeout,
                transaction != null ? ", nested in " + transaction.getTransactionId() : ""));
        return newTransaction;
    }

    private XdStorageTransaction createTransaction(final XdStorageTransactionManager manager, final String threadId,
                                                   final long timeout, final String transactionId) {
        return createTransaction(manager, threadId, null, timeout, transactionId);
    }

    /**
     * Creates a transaction instance: {@code globalTransaction == null} for a root transaction,
     * the parent for a nested one (the manager suspends a nested transaction on its parent right
     * afterwards). Extension point for subclasses and tests that need a custom transaction type.
     *
     * @param manager           the manager the new transaction belongs to
     * @param threadId          the id string of the thread the transaction is bound to
     * @param globalTransaction the parent transaction of a nested transaction, {@code null} for
     *                          a root transaction
     * @param timeout           the transaction timeout in milliseconds
     * @param transactionId     the unique id for the new transaction
     * @return the created transaction
     */
    protected XdStorageTransaction createTransaction(final XdStorageTransactionManager manager, final String threadId,
                                                     final XdStorageTransaction globalTransaction, final long timeout, final String transactionId) {
        return new XdStorageTransaction(manager, threadId, globalTransaction, timeout, transactionId);
    }

    public void commitTransaction(final XdStorageTransaction transaction) {
        if (transaction.isRollbackOnly()) {
            throw new XdStorageRuntimeException("transaction will be rolled back because it is marked as rollback only");
        }

        final XdStorageTransaction globalTransactionToCheck = transaction.getGlobalTransaction();
        if (globalTransactionToCheck != null && globalTransactionToCheck.isRollbackOnly()) {
            transaction.markRollbackOnly();
            throw new XdStorageRuntimeException("transaction should be rolled back because global transaction is marked as rollback only");
        }

        final Collection<IXdStorageResourceObject> resources = transaction.getResources();
        final XdStorageCommitTransactionStateHolder state = new XdStorageCommitTransactionStateHolder();
        final Map<Object, XdStorageTransactionResourceChanges> firstPhaseCommittedResources = new ConcurrentHashMap<>();

        transaction.skipOrWaitForInternalTransactions();

        try {
            // R-45: резервируем ЕДИНЫЙ commit-serial всей транзакции ДО фаз коммита —
            // атомарная граница видимости для всех ее записей.
            transaction.assignCommitSerial();
            transaction.startCriticalSection();
            transaction.commitInternal(state, firstPhaseCommittedResources);
            if (state.getState() == XdStorageCommitTransactionState.FINISHED) {
                fireChanges(firstPhaseCommittedResources, transaction);
            }
        } catch (final Throwable e) {
            log.warn("commit internal error", e);
            // R-57: сначала компенсация ин-плейс индексных мутаций — вставок И удалений
            // (деревья не откатываются record-механизмом; возврат удаленных ключей держит
            // дерево read-committed после отката), затем штатный откат зафиксированных ресурсов.
            // TREEFILE: rollbackFailedCommit ПЕРЕЗАГРУЖАЕТ деревья с закоммиченных файлов
            // (recoverInfraFromCommittedFiles) — их ключевые компенсации пропускаются
            // точечно, по факту перезагрузки (infraReloadedTrees).
            transaction.rollbackFailedCommit(firstPhaseCommittedResources);
            transaction.rollbackIndexKeyInserts();
            transaction.rollbackIndexKeyDeletes();
        } finally {
            transaction.finishCriticalSection();
        }

        threadsByTransactionId.remove(transaction.getTransactionId());
        transactionsById.remove(transaction.getTransactionId());
        transactionActiveThreadsCounter.remove(transaction.getTransactionId());

        final String currentThreadId = transaction.getTransactionThreadId();
        transactionsByThreadId.remove(currentThreadId);

        resources.stream().forEach(resource -> {
            resource.release(transaction);
        });

        services.getCloner().release(transaction);

        final String globalTransactionId = transaction.getGlobalTransactionId();
        if (globalTransactionId != null) {
            final XdStorageTransaction globalTransaction = transactionsById.get(globalTransactionId);
            if (globalTransaction != null && globalTransaction.getTransactionThreadId().equalsIgnoreCase(currentThreadId)) {
                threadsByTransactionId.put(globalTransactionId, currentThreadId);
                transactionsByThreadId.put(currentThreadId, globalTransaction);
            }
            if (globalTransaction != null) {
                globalTransaction.resume(transaction.getTransactionId());
            }
        }

        transaction.markFinished();
        metrics().increment(XdStorageMetrics.TRANSACTIONS_COMMITTED);
        metrics().decrement(XdStorageMetrics.ACTIVE_TRANSACTIONS);
        log.debug(String.format("Committed transaction %s", transaction.getTransactionId()));
    }

    /**
     * Fires the persistence triggers for the committed changes, on the storage's executor and
     * awaited inline — the commit does not count as done before the triggers ran. Trigger
     * failures are logged, not rethrown: by then the commit itself is already durable.
     *
     * @param firstPhaseCommittedResources the per-resource change records of the finished commit
     * @param transaction                  the committed transaction, handed to every trigger
     */
    protected void fireChanges(final Map<Object, XdStorageTransactionResourceChanges> firstPhaseCommittedResources, final XdStorageTransaction transaction) {
        final Callable<Boolean> task = () -> {
            firstPhaseCommittedResources.entrySet().stream().forEach(entry -> {
                entry.getValue().performTriggers(transaction, services.getTriggersManager());
            });
            return true;
        };

        try {
            services.getExecutor().submit(task).get();
        } catch (final InterruptedException | ExecutionException e) {
            log.warn("performing triggers error", e);
        }
    }

    public void rollbackTransaction(final XdStorageTransaction transaction) {
        if (transaction.isRollbackOnly()) {
            log.warn("transaction " + transaction.getTransactionId() + " will be rolled back automatically by mark rollback only");
            return;
        }

        final String globalTransactionId = transaction.getGlobalTransactionId();
        if (globalTransactionId != null) {
            // R-41: ИЗОЛИРОВАННЫЙ откат вложенной транзакции — родитель выживает.
            // Прежний transaction.markRollbackOnly() делегировал наверх и ядовил parent:
            // сценарий "nested rollback" ронял следующий commit родителя даже в один поток.
            transaction.markSelfAndChildrenRollbackOnly();
            return;
        }

        final Collection<IXdStorageResourceObject> resources = transaction.getResources();
        transaction.skipOrWaitForInternalTransactions();

        transaction.startCriticalSection(true);
        try {
            transaction.rollbackInternal();
        } finally {
            transaction.finishCriticalSection();
        }
        metrics().increment(XdStorageMetrics.TRANSACTIONS_ROLLED_BACK);
        metrics().decrement(XdStorageMetrics.ACTIVE_TRANSACTIONS);

        resources.stream().forEach(resource -> {
            resource.release(transaction);
        });

        services.getCloner().release(transaction);

        threadsByTransactionId.remove(transaction.getTransactionId());
        transactionsById.remove(transaction.getTransactionId());

        transactionActiveThreadsCounter.remove(transaction.getTransactionId());

        final String currentThreadId = transaction.getTransactionThreadId();
        transactionsByThreadId.remove(currentThreadId);

        transaction.markFinished();
        log.debug(String.format("Rolled back transaction %s", transaction.getTransactionId()));
    }

    @Override
    public XdStorageTransaction getCurrentTransaction() {
        return transactionsByThreadId.get(getCurrentThreadId());
    }

    /**
     * R-41: true, если транзакция зарегистрирована на ТЕКУЩЕМ потоке. Такой внутренней tx,
     * ждущей в skipOrWaitForInternalTransactions своего родителя, никто никогда не завершит:
     * ее поток — сам родительский, он поймал исключение из nested-области и пытается
     * завершить родителя. Брошенная tx подлежит немедленному откату.
     *
     * @param transactionId the id of the transaction to test
     * @return {@code true} when the transaction is registered on the current thread
     */
    public boolean isTransactionBoundToCurrentThread(final String transactionId) {
        final String threadId = threadsByTransactionId.get(transactionId);
        return threadId != null && threadId.equals(getCurrentThreadId());
    }

    @Override
    public XdStorageTransaction getTransaction(final String transactionId) {
        return transactionsById.get(transactionId);
    }

    @Override
    public void registerRollbackOnlyTransaction(final XdStorageTransaction transaction) {
        services.getExecutor().submit(() -> {
            synchronized (transaction) {
                if (transactionsById.containsKey(transaction.getTransactionId()) && transaction.isRollbackOnly()) {
                    // Аномалия, которую нельзя терять в логах: транзакция не смогла завершиться
                    // сама (таймаут/конфликт) и откатывается уборщиком — все ее изменения теряются.
                    metrics().increment(XdStorageMetrics.ROLLBACK_ONLY_MARKS);
                    metrics().increment(XdStorageMetrics.TIMEOUT_SWEPT_ROLLBACKS);
                    log.warn("Rollback-only sweep: transaction {} is being rolled back by the timeout sweeper (its changes are discarded)",
                            transaction.getTransactionId());
                    // БАРЬЕР ЗАЩИТЫ ПОТОКОВ: Фоновый уборщик выполнит откат ресурсов, но жестко ждет,
// пока активные ForkJoin-потоки параллельного стрима полностью не выйдут из методов СУБД!
                    AtomicLong activeThreads = transactionActiveThreadsCounter.get(transaction.getTransactionId());
                    if (activeThreads != null && activeThreads.get() > 0) {
                        log.debug("Фоновый откат отложен: транзакция удерживается активными параллельными потоками.");
                        // R-31: отложенный откат обязан ПЕРЕЗАПЛАНИРОВАТЬСЯ, а не теряться.
                        // Раньше полагались на "повторный вызов при финальном анлоке" — но если
                        // последний вызов свалился через lazy-getter (исключение проглочено
                        // вызывающим кодом), повторного вызова НЕТ: транзакция навсегда
                        // остается rollback-only с защелкнутыми записями и живыми картами —
                        // утечка памяти и мертвые блокировки для всех последующих транзакций.
                        services.getExecutor().submit(() -> {
                            try {
                                Thread.sleep(200); // дать активным потокам шанс выйти
                            } catch (final InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            registerRollbackOnlyTransaction(transaction);
                        });
                        return;
                    }

                    Collection<IXdStorageResourceObject> resources = transaction.getResources();
                    transaction.skipOrWaitForInternalTransactions();
                    transaction.rollbackInternal();

                    resources.stream().forEach(resource -> {
                        resource.release(transaction);
                    });

                    services.getCloner().release(transaction);

                    threadsByTransactionId.remove(transaction.getTransactionId());
                    transactionsById.remove(transaction.getTransactionId());
                    transactionActiveThreadsCounter.remove(transaction.getTransactionId());

                    final String currentThreadId = transaction.getTransactionThreadId();
                    transactionsByThreadId.remove(currentThreadId);

                    final String globalTransactionId = transaction.getGlobalTransactionId();
                    if (globalTransactionId != null) {
                        final XdStorageTransaction globalTransaction = transactionsById.get(globalTransactionId);
                        if (globalTransaction != null && globalTransaction.getTransactionThreadId().equalsIgnoreCase(currentThreadId)) {
                            threadsByTransactionId.put(globalTransactionId, currentThreadId);
                            transactionsByThreadId.put(currentThreadId, globalTransaction);
                        }
                        if (globalTransaction != null) {
                            globalTransaction.resume(transaction.getTransactionId());
                        }
                    }

                    transaction.markFinished();
                    log.debug("Rolled back transaction " + transaction.getTransactionId() + " by mark rollback only");
                }
            }
        });
    }

    private static String generateTransactionId() {
        return UUID.randomUUID().toString();
    }

    private static String getCurrentThreadId() {
        final Thread thread = Thread.currentThread();
        return thread.getName() + thread.getId();
    }
}