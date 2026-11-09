package io.github.xdstorage.resource.cache;

import io.github.xdstorage.entities.foreignkeys.index.hash.HashIndexChildAutoId;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * WRAPLEAK (docs/tasks/2.1.0/46): неидентифицированные observable-обертки транзакции
 * обязаны покидать workspace по ОБОИМ контрактам завершения — коммит (в том числе без
 * назначения id: unidentified-обертки вносятся в обе фазы и больше не нужны) и откат.
 * Брошенные транзакции (task 36 sweeps) проходят через тот же rollback-путь
 * (rollbackInternal → resource.rollback → protocol.rollback), поэтому очистка наследуется.
 */
class XdStorageWorkspaceUnidentifiedCleanupTest extends CacheInvariantTestSupport {

    private StubCacheContext context;
    private XdStorageCacheMutator mutator;
    private XdStorageCacheCommitProtocol protocol;

    @BeforeEach
    void setUp() {
        context = new StubCacheContext();
        mutator = new XdStorageCacheMutator(context);
        protocol = new XdStorageCacheCommitProtocol(context);
    }

    @Test
    @DisplayName("Откат транзакции очищает unidentified-обертки workspace")
    void rolledBackTransactionClearsUnidentifiedWrappers() throws Exception {
        XdStorageTransaction tx = mkTx("tx-unid-1", 100L);
        final Object entity = new HashIndexChildAutoId();
        final IXdStorageIdObservableWrapper wrapper = XdStorageObserverService.getObservableWrapper(entity);
        mutator.insertInternal(wrapper, tx);
        assertTrue(context.workspace.hasChanges(tx.getTransactionId()), "неидентифицированная обертка — изменение транзакции");

        protocol.rollback(tx);

        assertNull(context.workspace.unidentified(tx.getTransactionId()),
                "откат обязан вычистить unidentified-обертки (брошенные/откаченные транзакции не текут)");
        assertFalse(context.workspace.hasChanges(tx.getTransactionId()));
    }

    @Test
    @DisplayName("Коммит без назначения id очищает unidentified-обертки workspace")
    void committedWithoutIdClearsUnidentifiedWrappers() throws Exception {
        XdStorageTransaction tx = mkTx("tx-unid-2", 100L);
        final Object entity = new HashIndexChildAutoId();
        final IXdStorageIdObservableWrapper wrapper = XdStorageObserverService.getObservableWrapper(entity);
        mutator.insertInternal(wrapper, tx);
        assertNotNull(context.workspace.unidentified(tx.getTransactionId()));

        protocol.prepareCommit(tx, newCollector());
        protocol.performCommit(tx, newCollector());
        protocol.commit(tx);

        assertNull(context.workspace.unidentified(tx.getTransactionId()),
                "коммит (id не назначен — unidentified внесены как вставки обеих фаз) вычищает карту");
        assertFalse(context.workspace.hasChanges(tx.getTransactionId()));
    }

    private XdStorageTransactionResourceChanges newCollector() {
        return new XdStorageTransactionResourceChanges(mock(io.github.xdstorage.resource.IXdStorageResourceObject.class));
    }
}
