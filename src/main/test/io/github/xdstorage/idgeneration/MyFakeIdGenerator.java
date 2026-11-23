package io.github.xdstorage.idgeneration;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MyFakeIdGenerator implements IXdStorageIdGenerator {

    private final long PART_OF_IDENTIFIERS = 10;

    private final Map<Class<?>, Lock> lockers = new ConcurrentHashMap<>();

    private Map<Class<?>, AtomicLong> counters = new ConcurrentHashMap<>();

    private Map<Class<?>, Long> lastIdentifierOfPart = new ConcurrentHashMap<>();

    @Override
    public Object generate(Class<?> cl, IXdStorage storage, IXdStorageTransaction transaction) throws XdStorageException
            {
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
                initIdentifiers(cl, storage, transaction);
                counter = counters.get(cl);
            }

            if ((identifier = counter.incrementAndGet()) == lastIdentifierOfPart.get(cl)) {
                takeNextPartOfIdentifiers(cl, storage, transaction);
            }
        } finally {
            locker.unlock();
        }
        return identifier;
    }

    private void initIdentifiers(final Class<?> cl, final IXdStorage storage, IXdStorageTransaction tx) throws
            XdStorageException {

        final IXdStorageTransaction transaction = storage.beginTransaction(tx);
        try {
            Long newLastIdentifierOfPart;

            MyFakeIdCounterRecord record = storage.load(MyFakeIdCounterRecord.class, cl);
            if (record == null) {
                newLastIdentifierOfPart = PART_OF_IDENTIFIERS;

                record = new MyFakeIdCounterRecord();
                record.setCl(cl);
                record.setCounter(newLastIdentifierOfPart);

                storage.save(record);
            } else {
                newLastIdentifierOfPart = record.getCounter() + PART_OF_IDENTIFIERS;

                record.setCounter(newLastIdentifierOfPart);

                storage.update(record);
            }

            counters.put(cl, new AtomicLong(newLastIdentifierOfPart - PART_OF_IDENTIFIERS));
            lastIdentifierOfPart.put(cl, newLastIdentifierOfPart);

            transaction.commit();
        } catch (final XdStorageConnectionException e) {
            transaction.rollback();
            throw new XdStorageException(e);
        } catch (final XdStorageException e) {
            transaction.rollback();
            throw e;
        }
    }

    private void takeNextPartOfIdentifiers(final Class<?> cl, final IXdStorage storage, IXdStorageTransaction tx) throws
            XdStorageException {

        final Long newLastIdentifierOfPart = lastIdentifierOfPart.get(cl) + PART_OF_IDENTIFIERS;

        final IXdStorageTransaction transaction = storage.beginTransaction(tx);
        try {

            final MyFakeIdCounterRecord record = storage.load(MyFakeIdCounterRecord.class, cl);
            record.setCounter(newLastIdentifierOfPart);
            storage.update(record);

            lastIdentifierOfPart.put(cl, newLastIdentifierOfPart);

            transaction.commit();
        } catch (final XdStorageConnectionException e) {
            transaction.rollback();
            throw new XdStorageException(e);
        } catch (final XdStorageException e) {
            transaction.rollback();
            throw e;
        }
    }
}
