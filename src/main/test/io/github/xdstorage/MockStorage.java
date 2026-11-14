package io.github.xdstorage;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.trigger.IXdStorageTrigger;

import java.util.Collection;

public class MockStorage implements IXdStorage {

    @Override
    public String getName() {
        return null;
    }

    @Override
    public io.github.xdstorage.metrics.XdStorageMetrics getMetrics() {
        return new io.github.xdstorage.metrics.XdStorageMetrics();
    }

    @Override
    public <T> void registerTrigger(IXdStorageTrigger<T> trigger) {

    }

    @Override
    public IXdStorageTransaction beginTransaction(long timeout) {
        return null;
    }

    @Override
    public IXdStorageTransaction beginTransaction() {
        return null;
    }

    @Override
    public IXdStorageTransaction beginTransaction(IXdStorageTransaction transaction) {
        return null;
    }

    @Override
    public IXdStorageTransaction beginTransaction(IXdStorageTransaction transaction, long timeout) {
        return null;
    }

    @Override
    public void commitTransaction(IXdStorageTransaction transaction) throws XdStorageException {

    }

    @Override
    public void rollbackTransaction(IXdStorageTransaction transaction) {

    }

    @Override
    public void save(Object object) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void save(Object object, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void save(Collection<?> objects) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void save(Collection<?> objects, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public boolean has(Class<?> cl, Object id) throws XdStorageException, XdStorageConnectionException {
        return true;
    }

    @Override
    public boolean has(Class<?> cl, Object id, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return true;
    }

    @Override
    public void load(Object reference) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void load(Object reference, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public <T> T load(Class<T> cl, Object id) throws XdStorageException, XdStorageConnectionException {
        return null;
    }

    @Override
    public <T> T load(Class<T> cl, Object id, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return null;
    }

    @Override
    public void load(Collection<?> references) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void load(Collection<?> references, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public <T> Collection<T> load(Class<T> cl) throws XdStorageException, XdStorageConnectionException {
        return null;
    }

    @Override
    public <T> Collection<T> load(Class<T> cl, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        return null;
    }

    @Override
    public void update(Object object) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void update(Object object, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void update(Collection<?> objects) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void update(Collection<?> objects, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void delete(Object reference) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void delete(Object reference, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void delete(Collection<?> references) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public void delete(Collection<?> references, IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {

    }

    @Override
    public IXdStorageTransactionManager getTransactionManager() {
        return null;
    }

    @Override
    public void shutdown() {

    }

    @Override
    public void checkpoint() {

    }
}
