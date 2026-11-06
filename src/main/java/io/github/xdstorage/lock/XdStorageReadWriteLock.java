package io.github.xdstorage.lock;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The engine's reentrant read-write latch guarding record resources: per-thread counted readers,
 * a single reentrant writer, and every state transition made under one short internal lock.
 * Blocking acquisition ({@code lockRead}/{@code lockWrite}) loops over the non-blocking
 * try-operations, sleeping in short steps, until the transaction's timeout elapses — then it
 * fails loudly and feeds the JVM-wide anomaly metrics: a lock-acquisition timeout increments the
 * engine's lock-timeout counter, and any acquisition that waited one second or more is logged
 * and counted as a contention anomaly (possible leaked write lock or reader starvation).
 *
 * <p>Thread-safety: counters are atomics and concurrent maps, transitions are atomic under the
 * internal lock. Readers pass while no other thread holds the write lock; a writer passes only
 * with no readers and no other writer. A thread holding the write lock can also take read locks
 * (downgrade), and a lone reader can take the write lock (upgrade). There is no FIFO arbitration:
 * reader-priority, waiting-writer-barrier and fair variants were tried and rejected by
 * experiments (R-26/R-28/R-29); starvation treatment remains a known open point (R-29).
 *
 * <p>Engine-internal — not part of the public API.
 */
public class XdStorageReadWriteLock {

    /** Creates an unlocked latch: no writer, no readers, all counters at zero. */
    public XdStorageReadWriteLock() {
    }

    private static final Logger log = LoggerFactory.getLogger(XdStorageReadWriteLock.class);

    // Легковесный лок для защиты атомарности каста состояний
    private final ReentrantLock mainLock = new ReentrantLock();

    private volatile Thread writeLockThread = null;
    private final AtomicLong writeLocksCounter = new AtomicLong(0);

    // Потокобезопасный учет реентерабельных читателей
    private final Map<Thread, AtomicLong> readLocksCounters = new ConcurrentHashMap<>();

    // R-26/R-28/R-29 (эксперименты отклонены, полная история в docs/OPTIMIZATION-PLAN.md):
    // читательский приоритет голодал писателей; waiting-writers барьер голодал читателей;
    // condition-wait не убрал голодание (просыпание != победа в mainLock-гонке);
    // fair-RRWL ЗАВИСАЛ под 25 воркерами (queue-handoff при deep-lock цепях не дренируется).
    // Остается простая схема: читатели проходят при свободном локе, писатели — при отсутствии
    // читателей и чужого writer-лока. Полноценное лечение голодания (R-29, open) — переезд на
    // java.util.concurrent RW-лок только для ТЕКОВЫХ latch-ов с FIFO, либо per-node striping,
    // либо устранение самого contention (снижение глубины deep-lock цепей).

    /**
     * Whether the CURRENT thread holds the write lock.
     *
     * @return {@code true} when the write lock is held (reentrancy counted) by the calling thread
     */
    public boolean isWriteLocked() {
        return writeLocksCounter.get() > 0 && writeLockThread == Thread.currentThread();
    }

    /**
     * Whether the CURRENT thread holds at least one read lock.
     *
     * @return {@code true} when the calling thread's reader count is above zero
     */
    public boolean isReadLocked() {
        AtomicLong counter = readLocksCounters.get(Thread.currentThread());
        return counter != null && counter.get() > 0;
    }

    /**
     * Non-blocking write acquisition: succeeds when no other thread holds the write lock and the
     * reader map is empty or contains only the calling thread (lone-reader upgrade). Reentrant:
     * every success increments the calling thread's writer count.
     *
     * @return {@code true} when the write lock was acquired, {@code false} when another thread
     *         holds the write lock or other readers exist
     */
    public boolean tryLockWrite() {
        final Thread currentThread = Thread.currentThread();

        // =========================================================================
        // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ (Ликвидация ложных отказов блокировок):
        // Заменяем "mainLock.tryLock()" на честный блокирующий "mainLock.lock()".
        // Это гарантирует, что потоки не будут ложно отваливаться с отказом false,
        // если столкнулись лбами на обновлении внутренних счетчиков локера в куче Java!
        // =========================================================================
        mainLock.lock();
        try {
            if (writeLockThread != null && writeLockThread != currentThread) {
                return false;
            }

            // РЕЕНТЕРАБЕЛЬНЫЙ UPGRADE ЗАМКА: Если текущий поток уже держит замки на чтение,
            // и он единственный читатель в СУБД, мы беспрепятственно разрешаем ему взять Write-Lock!
            if (!readLocksCounters.isEmpty()) {
                if (readLocksCounters.size() != 1 || !readLocksCounters.containsKey(currentThread)) {
                    return false;
                }
            }
            writeLockThread = currentThread;
            writeLocksCounter.incrementAndGet();
            return true;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Non-blocking read acquisition: succeeds while no other thread holds the write lock; the
     * calling thread's own write lock does not block it (downgrade). Reentrant: every success
     * increments the calling thread's reader count.
     *
     * @return {@code true} when the read lock was acquired, {@code false} when another thread
     *         holds the write lock
     */
    public boolean tryLockRead() {
        final Thread currentThread = Thread.currentThread();

        // =========================================================================
        // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ (Освобождение Shared Read параллельности):
        // Заменяем "mainLock.tryLock()" на монолитный "mainLock.lock()".
        // Параллельные читатели больше не будут отбриваться локером, а выстроятся
        // в наносекундную очередь, атомарно пропишут счетчики и будут читать данные ОДНОВРЕМЕННО,
        // что полностью уничтожает ошибку Expected 10, Actual 4!
        // =========================================================================
        mainLock.lock();
        try {
            // ИСПРАВЛЕНИЕ СУБД (РЕЕНТЕРАБЕЛЬНЫЙ DOWNGRADE): Если монопольный Write-Lock уже удерживается
            // ТЕКУЩИМ ПОТОКОМ, мы обязаны мгновенно разрешить ему операцию чтения!
            if (writeLockThread != null && writeLockThread == currentThread) {
                AtomicLong counter = readLocksCounters.get(currentThread);
                if (counter == null) {
                    readLocksCounters.put(currentThread, counter = new AtomicLong(0));
                }
                counter.incrementAndGet();
                return true;
            }

            if (writeLockThread != null && writeLockThread != currentThread) {
                return false;
            }

            AtomicLong counter = readLocksCounters.get(currentThread);
            if (counter == null) {
                readLocksCounters.put(currentThread, counter = new AtomicLong(0));
            }
            counter.incrementAndGet();
            return true;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Blocking write acquisition: polls {@link #tryLockWrite()} with short sleeps until it
     * succeeds or the transaction's timeout elapses. A timeout increments the engine's
     * lock-acquisition-timeout metric, logs the full lock state (holder and readers) and fails
     * with {@code XdStorageRuntimeException}; a wait of one second or more is logged and counted
     * as a contention anomaly. Without a transaction a default 5 s timeout applies.
     *
     * @param transaction the transaction whose timeout bounds the wait ({@code null} uses the
     *                    default timeout)
     * @throws InterruptedException when the polling sleep is interrupted
     */
    public void lockWrite(final IXdStorageTransaction transaction) throws InterruptedException {
        long timeout = transaction != null ? transaction.getTimeout() : 100L;
        timeout *= 1_000_000;
        long startTime = System.nanoTime();
        long sleepStep = 15;

        while (!tryLockWrite()) {
            long elapsed = System.nanoTime() - startTime;
            if (elapsed >= timeout) {
                io.github.xdstorage.metrics.XdStorageMetrics.engineEvents()
                        .increment(io.github.xdstorage.metrics.XdStorageMetrics.LOCK_ACQUISITION_TIMEOUTS);
                log.error("Write-lock acquisition failed after {} ms (holder thread: {}, tx: {}){} — timeout or leaked write-lock",
                        elapsed / 1_000_000, writeLockThread, transaction != null ? transaction.getTransactionId() : null,
                        describeLockState());
                throw new XdStorageRuntimeException("Конфликт блокировок MVCC: Ресурс монопольно занят на запись другим потоком. Превышен таймаут ожидания: " + timeout / 1_000_000 + " мс");
            }
            Thread.sleep(Math.min(sleepStep, timeout - elapsed));
        }
        logLockContention(startTime, "write", transaction);
    }

    /**
     * Blocking read acquisition: polls {@link #tryLockRead()} with short sleeps until it
     * succeeds or the transaction's timeout elapses. A timeout increments the engine's
     * lock-acquisition-timeout metric, logs the full lock state (holder and readers) and fails
     * with {@code XdStorageRuntimeException}; a wait of one second or more is logged and counted
     * as a contention anomaly. Without a transaction a default 5 s timeout applies.
     *
     * @param transaction the transaction whose timeout bounds the wait ({@code null} uses the
     *                    default timeout)
     * @throws InterruptedException when the polling sleep is interrupted
     */
    public void lockRead(final IXdStorageTransaction transaction) throws InterruptedException {
        long timeout = transaction != null ? transaction.getTimeout() : 100L;
        timeout *= 1_000_000;
        long startTime = System.nanoTime();
        long sleepStep = 15;

        while (!tryLockRead()) {
            long elapsed = System.nanoTime() - startTime;
            if (elapsed >= timeout) {
                io.github.xdstorage.metrics.XdStorageMetrics.engineEvents()
                        .increment(io.github.xdstorage.metrics.XdStorageMetrics.LOCK_ACQUISITION_TIMEOUTS);
                log.error("Read-lock acquisition failed after {} ms (holder thread: {}, tx: {}){} — timeout or leaked write-lock",
                        elapsed / 1_000_000, writeLockThread, transaction != null ? transaction.getTransactionId() : null,
                        describeLockState());
                throw new XdStorageRuntimeException("Конфликт блокировок MVCC: Ресурс заблокирован чужой WRITE-блокировкой (держатель: "
                        + (writeLockThread != null ? writeLockThread.getName() : "null") + "). Превышен таймаут ожидания: " + timeout / 1_000_000 + " мс");
            }
            Thread.sleep(Math.min(sleepStep, timeout - elapsed));
        }
        logLockContention(startTime, "read", transaction);
    }

    /**
     * R-37 диагностика: полный снимок состояния лока в момент таймаута — держатель write-лока
     * и ВСЕ счетчики читателей. Утечка читательского счетчика так же смертельна, как утечка
     * write-лока (tryLockWrite требует пустую карту читателей), но раньше была невидима в логе.
     */
    private String describeLockState() {
        final StringBuilder sb = new StringBuilder(" state=[writeHolder=");
        sb.append(writeLockThread == null ? "null" : writeLockThread.getName());
        sb.append(", writeCount=").append(writeLocksCounter.get());
        sb.append(", readers={");
        boolean first = true;
        for (final Map.Entry<Thread, AtomicLong> e : readLocksCounters.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey().getName()).append('=').append(e.getValue().get());
        }
        sb.append("}]");
        return sb.toString();
    }

    /**
     * Захват, занявший больше секунды, — симптом утечки write-лока (писатель умер, не отпустив)
     * или голодания читателей: такие события обязаны быть видны в логе на уровне WARN.
     */
    private void logLockContention(final long startTimeNanos, final String kind, final IXdStorageTransaction transaction) {
        final long waitedMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
        if (waitedMs >= 1000) {
            io.github.xdstorage.metrics.XdStorageMetrics.engineEvents()
                    .increment(io.github.xdstorage.metrics.XdStorageMetrics.LOCK_WAITS_OVER_1S);
            log.warn("{}-lock acquired after {} ms by tx {} — possible write-lock leak (holder: {}) or reader starvation",
                    kind, waitedMs, transaction != null ? transaction.getTransactionId() : null, writeLockThread);
        }
    }

    /**
     * Releases one write-lock acquisition of the CURRENT thread; the lock frees when the last
     * reentrant acquisition is released. A thread that does not hold the write lock gets
     * {@code IllegalMonitorStateException} — a leaked or unbalanced lock is also logged with the
     * real holder.
     */
    public void unlockWrite() {
        final Thread currentThread = Thread.currentThread();
        // ИСПРАВЛЕНИЕ: Используем ГАРАНТИРОВАННЫЙ блокирующий вызов lock() вместо tryLock(),
        // полностью ликвидируя ложные выбросы IllegalMonitorStateException при разгрузке потоков!
        mainLock.lock();
        try {
            if (writeLockThread == null || writeLockThread != currentThread) {
                log.warn("unlockWrite by thread {} rejected: write lock held by {} (leaked lock or unbalanced lock/unlock)",
                        currentThread, writeLockThread);
                throw new IllegalMonitorStateException("Блокировка записи не удерживается текущим потоком");
            }
            if (writeLocksCounter.decrementAndGet() == 0) {
                writeLockThread = null;
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Releases one read-lock acquisition of the CURRENT thread; the reader is forgotten when its
     * count drops to zero. A thread that holds no read lock gets
     * {@code IllegalMonitorStateException}.
     */
    public void unlockRead() {
        final Thread currentThread = Thread.currentThread();
        // ИСПРАВЛЕНИЕ: Используем ГАРАНТИРОВАННЫЙ блокирующий вызов lock() вместо tryLock(),
        // полностью ликвидируя ложные выбросы IllegalMonitorStateException при разгрузке потоков!
        mainLock.lock();
        try {
            final AtomicLong counter = readLocksCounters.get(currentThread);
            if (counter == null || counter.get() <= 0) {
                throw new IllegalMonitorStateException("Блокировка чтения не удерживается текущим потоком");
            }
            if (counter.decrementAndGet() == 0) {
                readLocksCounters.remove(currentThread);
            }
        } finally {
            mainLock.unlock();
        }
    }
}
