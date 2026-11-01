package io.github.xdstorage.structure;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.search.XdStorageSearchIndex;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.structure.update.IXdStorageStructureUpdater;
import io.github.xdstorage.structure.update.XdStorageSearchIndexStructureUpdater;
import io.github.xdstorage.structure.update.XdStorageUpdateStructureRecord;
import io.github.xdstorage.structure.update.XdStorageUpdateStructureResult;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionWrapper;
import io.github.xdstorage.utils.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The check-and-update flow for stored class structures: on the first use of a class (and
 * on every subsequent structure check) it builds a fresh {@code XdStorageClassStructure}
 * snapshot from the class metadata and compares it with the persisted record. A missing
 * record is simply persisted; a changed one first triggers the registered
 * {@code IXdStorageStructureUpdater} migration scripts (their executions are journalled in
 * {@code XdStorageUpdateStructureRecord} records) and then the refreshed snapshot.
 *
 * <p>To avoid cross-cycles between transaction monitors and the per-class structure locks,
 * the disk verification and the migration run inside an autonomous transaction on the
 * engine executor, strictly before this manager returns to the caller. Each class is
 * guarded by its own {@code ReentrantLock} and its readiness is remembered, so repeated
 * checks take a lock-free fast path; an already-updated class is never migrated twice.
 * Updater registration is not synchronized — register scripts before the store serves
 * concurrent load. Engine-internal — not part of the public API (applications reach it via
 * {@code XdStorage#registerStructureUpdater} and {@code XdStorage#executeStructureUpdate}).
 * </p>
 */
public class XdStorageStructureManager {

    private static final Logger log = LoggerFactory.getLogger(XdStorageStructureManager.class);

    private final XdStorageServicesLocator services;

    private final Map<Class<?>, AtomicBoolean> states = new ConcurrentHashMap<>();

    private final Map<Class<?>, Lock> locks = new ConcurrentHashMap<>();

    private final Map<Class<?>, List<IXdStorageStructureUpdater>> updaters = new HashMap<>();

    /**
     * Creates the manager bound to the given engine services.
     *
     * @param provider the engine services locator supplying the resources manager,
     *                 transactions manager and executor the flow runs on
     */
    public XdStorageStructureManager(final XdStorageServicesLocator provider) {
        this.services = provider;
    }

    /**
     * Registers a structure update (migration) script for the class it targets
     * ({@code getDataClass()}). Scripts of a class are executed in registration order when
     * the class's persisted structure is found changed. Not synchronized — call it during
     * setup, before concurrent structure checks start.
     *
     * @param updater the migration script to register
     */
    public void registerUpdater(final IXdStorageStructureUpdater updater) {
        final Class<?> cl = updater.getDataClass();
        List<IXdStorageStructureUpdater> tmp = updaters.get(cl);
        if (tmp == null) {
            updaters.put(cl, tmp = new ArrayList<>());
        }
        tmp.add(updater);
    }

    /**
     * Verifies that the persisted structure record of the class matches its current Java
     * schema and, when it does not, persists the new snapshot or executes the registered
     * migration scripts against the stored data. The work runs under the class structure
     * lock and inside an autonomous transaction on the engine executor, and this method
     * returns only after the migration transaction has settled — ready classes short-circuit
     * on a lock-free fast path.
     *
     * @param cl the stored class to verify and, if needed, migrate
     * @param tx the caller's transaction context (the verification and migration themselves
     *           run in an internally managed autonomous transaction)
     * @return the migration scripts executed by this call in execution order, empty when
     *         the structure was already up to date
     * @throws XdStorageException           when the structure synchronization or a migration
     *                                       script failed
     * @throws XdStorageConnectionException when the storage connection failed during the
     *                                       migration
     */
    /**
     * STRUCTFIX (docs/tasks/2.1.0/36) — TRANSACTION CONTRACT, decided and explicit:
     * the migration does NOT run inside the caller's transaction. The {@code tx}
     * parameter is accepted for API compatibility ({@code IXdFileStorage#executeStructureUpdate}
     * requires the caller to be inside a transaction) and is deliberately NOT used:
     * <ul>
     *   <li>the schema migration is DDL-like: it must commit atomically on its own and
     *       must NOT be undone by a business transaction's later rollback — a rolled-back
     *       business tx must not resurrect the old schema over migrated data;</li>
     *   <li>the verification/migration runs in a dedicated AUTONOMOUS 3 s transaction on
     *       the engine executor — the deadlock fix (see the in-method comment): running it
     *       in the caller's tx crossed the caller's record locks with the structure
     *       resource locks and deadlocked parallel first-touches;</li>
     *   <li>the call is still SYNCHRONOUS ({@code .get()}): when this method returns, the
     *       migration has committed and the caller's subsequent operations run against the
     *       migrated schema.</li>
     * </ul>
     */
    public List<IXdStorageStructureUpdater> checkAndUpdateStructureIfNeed(final Class<?> cl, final XdStorageTransaction tx) throws XdStorageException, XdStorageConnectionException {
        AtomicBoolean state = states.get(cl);
        if (state != null && state.get()) {
            return Collections.emptyList();
        }

        // Локальный контейнер для безопасного выноса результатов из асинхронного таска
        final List<IXdStorageStructureUpdater> result = new ArrayList<>();
        final XdStorageException[] exceptionContainer = new XdStorageException[]{null};
        final XdStorageConnectionException[] connectionExceptionContainer = new XdStorageConnectionException[]{null};

        try {
            lockStructure(cl);

            state = states.get(cl);
            if (state != null && state.get()) {
                return Collections.emptyList();
            }

            // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ СУБД (Финальное уничтожение дедлоков ядра!):
            // Переносим дисковую верификацию и эволюцию схем данных XdStorageClassStructure в выделенный
            // независимый поток системного исполнителя services.getExecutor(). Это полностью исключает
            // перекрестное заклинивание мониторов транзакций и ReentrantLock замков структуры классов,
            // гарантируя абсолютную пропускную способность под пиковой многопоточной нагрузкой!
            services.getExecutor().submit(() -> {
                // Создаем абсолютно чистую, автономную транзакцию верхнего уровня для апдейта метаданных
                final XdStorageTransaction autonomousTx = (XdStorageTransaction) services.getTransactionsManager().beginTransaction(3 * 1000);
                try {
                    final XdStorageClassStructure currentStructure = buildStructure(cl);
                    if (!hasStructure(cl, autonomousTx)) {
                        insertStructure(cl, currentStructure, autonomousTx);
                        autonomousTx.commit();
                    } else if (isStructureChanged(cl, currentStructure, autonomousTx)) {
                        generateAndRegisterSearchIndexUpdaters(cl, currentStructure, autonomousTx);
                        log.debug("Update structure for " + cl + " started");

                        // Выполняем миграцию схемы
                        List<IXdStorageStructureUpdater> updatersList = updateStructure(cl, currentStructure, autonomousTx);
                        if (updatersList != null) {
                            result.addAll(updatersList);
                        }

                        autonomousTx.commit();
                        log.debug("Update structure for " + cl + " finished");
                    } else {
                        autonomousTx.commit(); // Структура не изменилась, чисто закрываем транзакцию
                    }

                    // Атомарно взводим стейт готовности класса в Lock-Free мапе
                    states.put(cl, new AtomicBoolean(true));

                } catch (final XdStorageConnectionException e) {
                    autonomousTx.rollback();
                    connectionExceptionContainer[0] = e;
                } catch (final XdStorageException e) {
                    autonomousTx.rollback();
                    exceptionContainer[0] = e;
                } catch (final Throwable t) {
                    autonomousTx.rollback();
                    exceptionContainer[0] = new XdStorageException("Критический сбой автономной миграции структуры класса " + cl.getName(), t);
                }
            }).get(); // Жестко дожидаемся завершения транзакции миграции схемы

        } catch (final Exception e) {
            throw new XdStorageException("Сбой синхронизации менеджера структур для класса " + cl.getName(), e);
        } finally {
            try {
                unlockStructure(cl);
            } catch (Exception ignored) {}
        }

        // Пробрасываем накопленные исключения миграции вверх по стеку в исходных типах
        if (connectionExceptionContainer[0] != null) throw connectionExceptionContainer[0];
        if (exceptionContainer[0] != null) throw exceptionContainer[0];

        return result;
    }

    private void lockStructure(final Class<?> cl) {
        Lock lock = locks.get(cl);
        if (lock == null) {
            locks.putIfAbsent(cl, new ReentrantLock());
            lock = locks.get(cl);
        }

        lock.lock();
    }

    private void unlockStructure(final Class<?> cl) throws XdStorageException {
        Lock lock = locks.get(cl);
        if (lock == null) {
            throw new XdStorageException("cannot unlock structure of " + cl);
        }

        lock.unlock();
    }

    /**
     * Tells whether a structure record is persisted for the class.
     *
     * @param cl the stored class to look up
     * @param tx the transaction to read the structure resource in
     * @return {@code true} when the class already has a persisted structure record
     * @throws XdStorageException           when the structure resource cannot be accessed
     * @throws XdStorageConnectionException when the storage connection fails
     */
    public boolean hasStructure(final Class<?> cl, final XdStorageTransaction tx) throws XdStorageException, XdStorageConnectionException {
        final Class<?> structureCl = XdStorageClassStructure.class;
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(structureCl);

        final IXdStorageDaoResource resource = services.getResourcesManager().lockStructureResource(clInfo, tx);
        return resource.read(cl.getName(), tx) != null; // BUG-B: the record identity is the FQCN
    }

    private void insertStructure(final Class<?> cl, final XdStorageClassStructure currentStructure,
                                 final XdStorageTransaction tx) throws XdStorageException, XdStorageConnectionException {
        final Class<?> structureCl = XdStorageClassStructure.class;
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(structureCl);

        final IXdStorageDaoResource resource = services.getResourcesManager().lockStructureResource(clInfo, tx);
        resource.insert(currentStructure, tx);
    }

    private boolean isStructureChanged(final Class<?> cl, final XdStorageClassStructure currentStructure,
                                       final XdStorageTransaction tx) throws XdStorageException, XdStorageConnectionException {
        final Class<?> structureCl = XdStorageClassStructure.class;
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(structureCl);

        final IXdStorageDaoResource resource = services.getResourcesManager().lockStructureResource(clInfo, tx);
        final XdStorageClassStructure structure = (XdStorageClassStructure) resource.read(cl.getName(), tx); // BUG-B: FQCN identity

        return !Objects.equals(structure, currentStructure);
    }

    private void generateAndRegisterSearchIndexUpdaters(final Class<?> cl, final XdStorageClassStructure currentStructure,
                                                        final XdStorageTransaction tx) throws XdStorageException, XdStorageConnectionException {
        final Class<?> structureCl = XdStorageClassStructure.class;
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(structureCl);

        final IXdStorageDaoResource resource = services.getResourcesManager().lockStructureResource(clInfo, tx);
        final XdStorageClassStructure structure = (XdStorageClassStructure) resource.read(cl.getName(), tx); // BUG-B: FQCN identity

        final Map<String, XdStorageClassIndexStructure> oldIndexes = structure.getIndexes();
        if (oldIndexes != null) {
            final Map<String, XdStorageClassIndexStructure> newIndexes = currentStructure.getIndexes();
            for (final Map.Entry<String, XdStorageClassIndexStructure> indexStructure : oldIndexes.entrySet()) {
                final String indexName = indexStructure.getKey();
                if (newIndexes.containsKey(indexName)
                        && !Objects.equals(indexStructure.getValue(), newIndexes.get(indexName))) {
                    final XdStorageSearchIndex index = XdStorageObjectUtils.getClassInfo(cl).getIndexes().get(indexName);
                    registerUpdater(new XdStorageSearchIndexStructureUpdater(services.getSearchManager(), cl, index));
                }
            }
        }
    }

    private XdStorageClassStructure buildStructure(final Class<?> cl) {
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        final XdStorageClassStructure structure = new XdStorageClassStructure();
        structure.setType(cl.getName()); // BUG-B: the record identity is the FQCN, not the Class object
        structure.setPolicy(clInfo.getPolicy());

        final Collection<XdStorageObjectField> fields = clInfo.getFields().values();
        for (final XdStorageObjectField field : fields) {
            structure.addField(field.getName(), field.getFieldInfo().getClazz());
        }

        final Map<String, XdStorageSearchIndex> indexes = clInfo.getIndexes();
        for (final XdStorageSearchIndex index : indexes.values()) {
            structure.addIndex(index.getName(), buildIndexStructure(cl, index));
        }

        return structure;
    }

    private XdStorageClassIndexStructure buildIndexStructure(final Class<?> cl, final XdStorageSearchIndex index) {
        final XdStorageClassIndexStructureId id = new XdStorageClassIndexStructureId();
        id.setName(index.getName());
        id.setType(cl.getName()); // BUG-B: the identity is the FQCN, not the Class object

        final XdStorageClassIndexStructure indexStructure = new XdStorageClassIndexStructure();
        indexStructure.setId(id);

        final Map<String, XdStorageObjectField> fields = index.getFieldAccesors();
        for (final Map.Entry<String, XdStorageObjectField> accessor : fields.entrySet()) {
            indexStructure.addField(accessor.getKey(), accessor.getValue().getFieldInfo().getClazz());
        }

        final Collection<List<XdStoragePair<String, XdStorageObjectField>>> childAccessors = index.getChildAccessors();
        for(final List<XdStoragePair<String, XdStorageObjectField>> childFields : childAccessors) {
            for (final XdStoragePair<String, XdStorageObjectField> childAccessor : childFields) {
                final XdStorageObjectField childField = childAccessor.b;
                final XdStorageObjectFieldInfo fieldInfo = childField.getFieldInfo();

                final Class<?> propertyClass = fieldInfo.getValueClass();
                final Map<String, XdStorageObjectField> childFieldAccessors = index.getChildFieldAccesors(propertyClass);

                if (childFieldAccessors != null) {
                    for (final Map.Entry<String, XdStorageObjectField> accessor : childFieldAccessors.entrySet()) {
                        indexStructure.addChildField(propertyClass, accessor.getKey(), accessor.getValue().getFieldInfo().getClazz());
                    }
                }
            }
        }

        return indexStructure;
    }

    private List<IXdStorageStructureUpdater> updateStructure(final Class<?> cl, final XdStorageClassStructure currentStructure,
                                                             final XdStorageTransaction tx) throws XdStorageException, XdStorageConnectionException {
        final List<IXdStorageStructureUpdater> result = new ArrayList<>();

        // STRUCTFIX (docs/tasks/2.1.0/36): res is the result OF THE LAST EXECUTED SCRIPT,
        // not of the whole updater — the "executed earlier" branch below used to report it
        // (initially FAILURE) for a script that had actually succeeded.
        XdStorageUpdateStructureResult res = XdStorageUpdateStructureResult.FAILURE;
        final XdStorageTransaction safeTransaction = new XdStorageTransactionWrapper(tx);

        final Class<?> updatesCl = XdStorageUpdateStructureRecord.class;
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(updatesCl);

        final List<IXdStorageStructureUpdater> toExecute = updaters.get(cl);
        boolean fullResult = true;
        if (toExecute != null) {
            for (IXdStorageStructureUpdater updater : toExecute) {
                try {
                    final IXdStorageDaoResource resource = services.getResourcesManager().lockStructureResource(clInfo, tx);
                    XdStorageUpdateStructureRecord record = (XdStorageUpdateStructureRecord) resource.read(updater.getUpdateName(), tx);
                    if (record == null || record.getResult() == XdStorageUpdateStructureResult.FAILURE) {
                        log.debug("Execution update script '" + updater.getUpdateName() + "' started");
                        res = updater.execute(services.getResourcesManager(), safeTransaction);
                        if (res == XdStorageUpdateStructureResult.FAILURE) {
                            log.debug("Execution update script '" + updater.getUpdateName() + "' failed");
                            updater.setResult(res, "update " + updater.getUpdateName() + " has been failed", null);
                            fullResult = false;
                            break;
                        }
                        log.debug("Execution update script '" + updater.getUpdateName() + "' finished successfully");
                        updater.setResult(res, "update " + updater.getUpdateName() + " has been executed successfully", null);

                        record = new XdStorageUpdateStructureRecord();
                        record.setName(updater.getUpdateName());
                        record.setResult(res);
                        record.setTime(new Date());

                        resource.insert(record, tx);
                    } else {
                        // STRUCTFIX: the prior run SUCCEEDED (record.getResult() != FAILURE) —
                        // reporting the stale `res` (initially FAILURE) mislabeled a success
                        // as FAILURE for any caller branching on the result.
                        updater.setResult(XdStorageUpdateStructureResult.SUCCESSFULLY,
                                "update with name " + updater.getUpdateName() + " has been executed earlier", null);
                    }
                } catch (final Throwable e) {
                    updater.setResult(res, "execute update exception", e);
                    fullResult = false;
                    break;
                } finally {
                    result.add(updater);
                }
            }
            if (fullResult) {
                final Class<?> structureCl = XdStorageClassStructure.class;
                final XdStorageClassInfo strClInfo = XdStorageObjectUtils.getClassInfo(structureCl);

                final IXdStorageDaoResource resource = services.getResourcesManager().lockStructureResource(strClInfo, tx);
                resource.update(currentStructure, tx);
            }
        }
        return result;
    }
}
