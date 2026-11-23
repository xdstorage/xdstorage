package io.github.xdstorage.idgeneration;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.IXdStorage;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** The engine's Integer Hi-Lo id generator: allocates per-class batches
 * ({@code PART_OF_IDENTIFIERS} ids at a time) from the persisted counter record
 * ({@link XdStorageIntegerIdCounterRecord}) inside their own transactions, then serves
 * ids from memory; the next batch is prefetched before the buffer runs dry. Per-class
 * locking makes concurrent generation safe. */
public class XdStorageIntegerIdGenerator implements IXdStorageIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(XdStorageIntegerIdGenerator.class);

    private final int PART_OF_IDENTIFIERS = 100;
    private final XdStorageServicesLocator services;
    private final Map<Class<?>, Lock> lockers = new ConcurrentHashMap<>();
    private final Map<Class<?>, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<Class<?>, Integer> lastIdentifierOfPart = new ConcurrentHashMap<>();

    /** Builds the generator.
     *
     * @param provider the storage services locator (transaction/bookkeeping access) */
    public XdStorageIntegerIdGenerator(final XdStorageServicesLocator provider) {
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

        int identifier;
        try {
            locker.lock();

            AtomicInteger counter = counters.get(cl);
            if (counter == null) {
                initIdentifiers(cl, transaction);
                counter = counters.get(cl);
            }

            // ОПТИМИЗАЦИЯ ПО ПОИНТУ Г (Prefetch Policy):
            // Проверяем текущее значение ДО инкремента. Если в буфере остался всего 1 элемент -
            // упреждающе расширяем верхний лимит в БД ДО того, как счетчик вылетит за границу!
            int currentVal = counter.get();
            int limit = lastIdentifierOfPart.get(cl);

            if (currentVal + 1 >= limit) {
                takeNextPartOfIdentifiers(cl, transaction);
            }

            identifier = counter.incrementAndGet();

            // Жесткий Fail-Safe инвариант безопасности:
            if (identifier > lastIdentifierOfPart.get(cl)) {
                log.warn("Hi-Lo buffer exhausted for {}: generated id {} escaped its batch — refetch race suspected", cl
                        .getName(), identifier);
                throw new XdStorageException("Критический сбой Hi-Lo буфера: сгенерированный ID вышел за пределы " +
                        "пачки!");
            }

        } finally {
            locker.unlock();
        }
        return identifier;
    }

    private void initIdentifiers(final Class<?> cl, final IXdStorageTransaction tx) throws XdStorageException {
        final Class<?> clRecord = XdStorageIntegerIdCounterRecord.class;
        final int[] newLastIdentifierOfPart = new int[1];

        java.util.concurrent.Callable<Void> task = () -> {
            final IXdStorageTransactionManager transactionsManager = services.getTransactionsManager();
            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageTransaction transaction = transactionsManager.beginTransaction(3 * 1000);
            try {
                final XdStorageClassInfo clRecordInfo = XdStorageObjectUtils.getClassInfo(clRecord);
                final IXdStorageDaoResource resource = resourcesManager.lockStructureResource(clRecordInfo,
                        (XdStorageTransaction) transaction);

                XdStorageIntegerIdCounterRecord record = (XdStorageIntegerIdCounterRecord) resource.read(cl,
                        (XdStorageTransaction) transaction);
                if (record == null) {
                    newLastIdentifierOfPart[0] = PART_OF_IDENTIFIERS;
                    record = new XdStorageIntegerIdCounterRecord();
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
            throw new XdStorageException("Критический сбой инициализации Hi-Lo буфера Integer", cause);
        }

        counters.put(cl, new AtomicInteger(newLastIdentifierOfPart[0] - PART_OF_IDENTIFIERS));
        lastIdentifierOfPart.put(cl, newLastIdentifierOfPart[0]);
    }

    private void takeNextPartOfIdentifiers(final Class<?> cl, final IXdStorageTransaction tx) throws XdStorageException
            {
        final Class<?> clRecord = XdStorageIntegerIdCounterRecord.class;
        final int newLastIdentifierOfPart = lastIdentifierOfPart.get(cl) + PART_OF_IDENTIFIERS;

        java.util.concurrent.Callable<Void> task = () -> {
            final IXdStorageTransactionManager transactionsManager = services.getTransactionsManager();
            final XdStorageAbstractResourcesManager resourcesManager = services.getResourcesManager();
            final IXdStorageTransaction transaction = transactionsManager.beginTransaction(3 * 1000);
            try {
                final XdStorageClassInfo clRecordInfo = XdStorageObjectUtils.getClassInfo(clRecord);
                final IXdStorageDaoResource resource = resourcesManager.lockStructureResource(clRecordInfo,
                        (XdStorageTransaction) transaction);

                final XdStorageIntegerIdCounterRecord record = (XdStorageIntegerIdCounterRecord) resource.read(cl,
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
            throw new XdStorageException("Критический сбой расширения пачки Hi-Lo Integer", cause);
        }

        lastIdentifierOfPart.put(cl, newLastIdentifierOfPart);
    }
}
