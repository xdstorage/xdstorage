package io.github.xdstorage.transaction;

import io.github.xdstorage.IXdStorage;

public class MockTransaction implements IXdStorageTransaction {

    private String id;

    public MockTransaction(String id) {
        this.id = id;
    }

    @Override
    public IXdStorageTransactionManager<?> getTransactionManager() {
        return null;
    }

    @Override
    public long getTimestart() {
        return 0;
    }

    @Override
    public IXdStorage getStorage() {
        return null;
    }

    @Override
    public String getTransactionId() {
        return id;
    }

    @Override
    public String getTransactionThreadId() {
        return null;
    }

    @Override
    public long getTimeout() {
        return 0;
    }

    @Override
    public void commit() {

    }

    @Override
    public void rollback() {

    }

    @Override
    public void markRollbackOnly() {

    }

    @Override
    public void markRollbackOnly(final String site, final Throwable cause) {
        // mock: no live capture
    }

    @Override
    public XdStorageRollbackOnlyCause getRollbackOnlyCause() {
        return null;
    }

    @Override
    public boolean isRollbackOnly() {
        return false;
    }

    @Override
    public void registerIndexKeyForRollback(final Object tree, final Comparable key) {
        // no-op in mock
    }

    @Override
    public void rollbackIndexKeyInserts() {
        // no-op in mock
    }

    @Override
    public void registerIndexKeyDeleteForRollback(final Object tree, final Comparable key, final Object value) {
        // mock: no live trees to compensate
    }

    @Override
    public void rollbackIndexKeyDeletes() {
        // mock: no live trees to compensate
    }

    @Override
    public void markTreeInfraReloaded(final Object tree) {
        // mock: no live trees to reload
    }
}
