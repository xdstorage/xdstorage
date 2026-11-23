package io.github.xdstorage.idgeneration;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** The engine's Long Hi-Lo id generator — the {@code long} twin of
 * {@link XdStorageIntegerIdGenerator} (persisted batches, prefetch, per-class locking). */
public class XdStorageLongIdGenerator implements IXdStorageIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(XdStorageLongIdGenerator.class);

    private final long PART_OF_IDENTIFIERS = 100;
    private final XdStorageServicesLocator services;
    private final Map<Class<?>, Lock> lockers = new ConcurrentHashMap<>();
    private final Map<Class<?>, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<Class<?>, Long> lastIdentifierOfPart = new ConcurrentHashMap<>();

    /** Builds the generator.
     *
     * @param provider the storage services locator (transaction/bookkeeping access) */
    public XdStorageLongIdGenerator(final XdStorageServicesLocator provider) {
        this.services = provider;
    }

    @Override
    public Object generate(final Class<?> cl, final IXdStorage storage, final IXdStorageTransaction transaction) throws
            XdStorageException {
        Lock locker = lockers.get(cl);
        if (locker == null) {
            lockers.putIfAbsent(cl, new ReentrantLock());
            locker = lockers.get(cl);
        }

        long identifier;
        try {
            locker.lock();

            AtomicLong counter = counters.get(cl);
            if (counter == null) {
                initIdentifiers(cl, transaction);
                counter = counters.get(cl);
            }

            // ИСПРАВЛЕНИЕ ПО ПОИНТУ Г (Prefetch Policy):
            long currentVal = counter.get();
            long limit = lastIdentifierOfPart.get(cl);

            if (currentVal + 1 >= limit) {
                takeNextPartOfIdentifiers(cl, transaction);
            }

            identifier = counter.incrementAndGet();

            if (identifier > lastIdentifierOfPart.get(cl)) {
                log.warn("Hi-Lo buffer exhausted for {}: generated id {} escaped its batch — refetch race suspected", cl
                        .getName(), identifier);
                throw new XdStorageException("Критический сбой Hi-Lo буфера Long: сгенерированный ID вышел за " +
                        "пределы пачки!");
            }

        } finally {
            locker.unlock();
        }
        return identifier;
    }

    private void initIdentifiers(final Class<?> cl, final IXdStorageTransaction tx) throws XdStorageException {
        final Class<?> clRecord = XdStorageLongIdCounterRecord.class;
        final long[] newLastIdentifierOfPart = new long[1];

        java.util.concurrent.Callable<Void> task = () -> {
            final IXdStorageTransactionManager transactionsManager = services.getTransactionsManager();
            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageTransaction transaction = transactionsManager.beginTransaction(3 * 1000);
            try {
                final XdStorageClassInfo clRecordInfo = XdStorageObjectUtils.getClassInfo(clRecord);
                final IXdStorageDaoResource resource = resourcesManager.lockStructureResource(clRecordInfo,
                        (XdStorageTransaction) transaction);

                XdStorageLongIdCounterRecord record = (XdStorageLongIdCounterRecord) resource.read(cl,
                        (XdStorageTransaction) transaction);
                if (record == null) {
                    newLastIdentifierOfPart[0] = PART_OF_IDENTIFIERS;
                    record = new XdStorageLongIdCounterRecord();
                    record.setCl(cl);
                    record.setCounter(newLastIdentifierOfPart[0]);
                    resource.insert(record, (XdStorageTransaction) transaction);
                } else {
                    newLastIdentifierOfPart[0] = record.getCounter() + PART_OF_IDENTIFIERS;
                    record.setCounter(newLastIdentifierOfPart[0]);
                    resource.update(record, (XdStorageTransaction) transaction);
                }

                transaction.commit();
            } catch (Throwable t) {
                transaction.rollback();
                throw t; // Пробрасываем исходное исключение СУБД без искажения типа
            }
            return null;
        };

        try {
            if (services != null && services.getExecutor() != null) {
                services.getExecutor().submit(task).get();
            } else {
                task.call();
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            // ИСПРАВЛЕНИЕ КОНТРАКТА ТИПОВ: Если исходной причиной является легитимный Exception СУБД,
            // мы КАТЕГОРИЧЕСКИ запрещаем оборачивать его, пробрасывая в оригинальном виде!
            if (cause instanceof XdStorageException) throw (XdStorageException) cause;
            if (cause instanceof io.github.xdstorage.exceptions.XdStorageRuntimeException) throw (io.github.xdstorage
                    .exceptions.XdStorageRuntimeException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new XdStorageException("Критический сбой инициализации Hi-Lo буфера Long", cause);
        }

        counters.put(cl, new AtomicLong(newLastIdentifierOfPart[0] - PART_OF_IDENTIFIERS));
        lastIdentifierOfPart.put(cl, newLastIdentifierOfPart[0]);
    }

    private void takeNextPartOfIdentifiers(final Class<?> cl, final IXdStorageTransaction tx) throws XdStorageException
            {
        final Class<?> clRecord = XdStorageLongIdCounterRecord.class;
        final long newLastIdentifierOfPart = lastIdentifierOfPart.get(cl) + PART_OF_IDENTIFIERS;

        java.util.concurrent.Callable<Void> task = () -> {
            final IXdStorageTransactionManager transactionsManager = services.getTransactionsManager();
            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageTransaction transaction = transactionsManager.beginTransaction(3 * 1000);
            try {
                final XdStorageClassInfo clRecordInfo = XdStorageObjectUtils.getClassInfo(clRecord);
                final IXdStorageDaoResource resource = resourcesManager.lockStructureResource(clRecordInfo,
                        (XdStorageTransaction) transaction);

                final XdStorageLongIdCounterRecord record = (XdStorageLongIdCounterRecord) resource.read(cl,
                        (XdStorageTransaction) transaction);
                if (record != null) {
                    record.setCounter(newLastIdentifierOfPart);
                    resource.update(record, (XdStorageTransaction) transaction);
                }
                transaction.commit();
            } catch (Throwable t) {
                transaction.rollback();
                throw t;
            }
            return null;
        };

        try {
            if (services != null && services.getExecutor() != null) {
                services.getExecutor().submit(task).get();
            } else {
                task.call();
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof XdStorageException) throw (XdStorageException) cause;
            if (cause instanceof io.github.xdstorage.exceptions.XdStorageRuntimeException) throw (io.github.xdstorage
                    .exceptions.XdStorageRuntimeException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new XdStorageException("Критический сбой расширения пачки Hi-Lo Long", cause);
        }

        lastIdentifierOfPart.put(cl, newLastIdentifierOfPart);
    }
}
