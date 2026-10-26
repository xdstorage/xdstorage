package io.github.xdstorage.index.hash;

import java.nio.file.Path;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.resource.IXdStorageJournalCheckpoint;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.serialization.IXdStorageIOFactory;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageAtomicFile;
import io.github.xdstorage.utils.XdStorageClassInfo;

import java.io.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The persistent hash id-index resource for one entity class: a journal-checkpointable id
 * {@code ->} data-fragment index persisted as {@code XdStorageHashIndexRecord} entries.
 * With {@code indexJournalWrites} enabled (default) and a YAML content factory, the first
 * commit phase appends the delta to the resource's commit journal ({@code
 * <base>-journal.yaml}) instead of rewriting the whole record file (O(delta) instead
 * of O(N)); on reopen the base file is parsed once, every record key is restored to the
 * ENTITY id-field type (the YAML round-trip does not know it — without the restore, by-id
 * lookups would silently miss), and the journal replays on top. Commit phases are
 * serialized by a {@code ReentrantLock} bounded by the transaction timeout.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public class XdStorageHashIndexResource extends XdStorageAbstractHashIndexResource implements IXdStorageJournalCheckpoint {

    private static final Logger log = LoggerFactory.getLogger(XdStorageHashIndexResource.class);

    private final IXdStorageObjectsReader reader;

    private final IXdStorageObjectsWriter writer;

    private final AtomicBoolean prepared = new AtomicBoolean(false);

    private final Lock lock = new ReentrantLock();

    /** W-4a (docs/tasks/2.1.0/32, W4-DESIGN.md): journal the hash id-index — phase-1 becomes
     * an O(delta) append instead of the O(N) full-file rewrite of every cached record. */
    private final boolean indexJournal;

    private java.nio.file.Path indexJournalFile;

    // JRNLNAME: older-named index journals present at resolution (replay sources / superseded twins)
    private java.util.List<java.nio.file.Path> supersededIndexJournals = java.util.Collections.emptyList();

    private final io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory yamlJournalFactory;

    /**
     * Creates the resource without an id generator (ids must be set by the caller).
     * Resolves the commit-journal file (and any superseded older-named journals) when index
     * journaling is enabled by the storage config.
     *
     * @param services     the engine services locator
     * @param manager      the resources manager owning this resource
     * @param resourceId   the storage-wide unique resource id (the backing file path)
     * @param indexName    the logical index name
     * @param clInfo       metadata of the ENTITY class being indexed
     * @param factory      the IO factory for the record file's content format
     * @param fragmentSize the record capacity of one data fragment
     */
    public XdStorageHashIndexResource(final XdStorageServicesLocator services,
                                      final XdStorageAbstractResourcesManager manager,
                                      final Object resourceId, final String indexName, final XdStorageClassInfo clInfo,
                                      final IXdStorageIOFactory factory, final int fragmentSize) {
        super(services, manager, resourceId, indexName, clInfo, fragmentSize);
        this.reader = factory.newInstanceReader();
        this.writer = factory.newInstanceWriter();
        // W-4a: the journal covers the id-index resource itself; YAML content only (the
        // yamlJournalFactory gate — non-YAML formats keep the full-file write-out), and the
        // references variant keeps the legacy full-file write in this phase.
        final io.github.xdstorage.XdStorageConfig storageConfig = services.getStorageConfig();
        this.indexJournal = storageConfig != null && storageConfig.isIndexJournalWrites() && !isReferences;
        if (indexJournal) {
            final io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.Resolution resolution =
                    io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.resolveFor(getFileName());
            this.indexJournalFile = resolution.file;
            this.supersededIndexJournals = resolution.superseded;
        } else {
            this.indexJournalFile = null;
            this.supersededIndexJournals = java.util.Collections.emptyList();
        }
        this.yamlJournalFactory = factory instanceof io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory
                ? (io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory) factory
                : null;
    }

    /**
     * Creates the resource with an id generator used when an inserted object has no id yet.
     * Resolves the commit-journal file (and any superseded older-named journals) when index
     * journaling is enabled by the storage config.
     *
     * @param services     the engine services locator
     * @param manager      the resources manager owning this resource
     * @param resourceId   the storage-wide unique resource id (the backing file path)
     * @param indexName    the logical index name
     * @param clInfo       metadata of the ENTITY class being indexed
     * @param factory      the IO factory for the record file's content format
     * @param idGenerator  the id generator applied when an inserted object has no id yet
     * @param fragmentSize the record capacity of one data fragment
     */
    public XdStorageHashIndexResource(final XdStorageServicesLocator services,
                                      final XdStorageAbstractResourcesManager manager,
                                      final Object resourceId, final String indexName, final XdStorageClassInfo clInfo,
                                      final IXdStorageIOFactory factory, final IXdStorageIdGenerator idGenerator, final int fragmentSize) {
        super(services, manager, resourceId, indexName, clInfo, idGenerator, fragmentSize);
        this.reader = factory.newInstanceReader();
        this.writer = factory.newInstanceWriter();
        // W-4a: the journal covers the id-index resource itself; YAML content only (the
        // yamlJournalFactory gate — non-YAML formats keep the full-file write-out), and the
        // references variant keeps the legacy full-file write in this phase.
        final io.github.xdstorage.XdStorageConfig storageConfig = services.getStorageConfig();
        this.indexJournal = storageConfig != null && storageConfig.isIndexJournalWrites() && !isReferences;
        if (indexJournal) {
            final io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.Resolution resolution =
                    io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.resolveFor(getFileName());
            this.indexJournalFile = resolution.file;
            this.supersededIndexJournals = resolution.superseded;
        } else {
            this.indexJournalFile = null;
            this.supersededIndexJournals = java.util.Collections.emptyList();
        }
        this.yamlJournalFactory = factory instanceof io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory
                ? (io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory) factory
                : null;
    }

    private Map<String, Long> blockingTime = new HashMap<>();

    private AtomicBoolean locked = new AtomicBoolean(false);

    /**
     * Acquires the hash-index commit lock, waiting up to the transaction's timeout. Fails
     * loudly with {@code XdStorageRuntimeException} when the lock cannot be acquired in
     * time — deliberately breaking any commit-cycle deadlock — and re-asserts the thread's
     * interrupt flag when interrupted while waiting.
     *
     * @param transaction the transaction acquiring the lock (its timeout bounds the wait;
     *                    {@code null} means the default 3-second bound)
     */
    @Override
    public void lockForCommit(final XdStorageTransaction transaction) {
        super.lockForCommit(transaction);

        // ИСПРАВЛЕНИЕ ДЕДЛОКА СУБД: Ликвидируем synchronized(locked) и locked.wait() в Хеш-индексах!
        // Задействуем ReentrantLock 'lock' с поддержкой tryLock() по таймауту транзакции.
        final long timeout = transaction != null ? transaction.getTimeout() : 3000L;
        try {
            boolean acquired = lock.tryLock(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new XdStorageRuntimeException("Hash Index Resource " + getFileName() + " cannot be locked for commit by transaction "
                        + transaction.getTransactionId() + " (Timeout " + timeout + " ms expired)");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XdStorageRuntimeException("waiting for lock hash index resource " + getFileName() + " has been interrupted", e);
        }
    }

    /**
     * Releases the commit lock acquired by {@code #lockForCommit} (no-op when the current
     * thread does not hold it), then runs the base release.
     *
     * @param transaction the transaction releasing the lock
     */
    @Override
    public void unlockAfterCommit(final XdStorageTransaction transaction) {
        // ИСПРАВЛЕНИЕ ДЕДЛОКА СУБД: Освобождаем ReentrantLock хеш-индекса
        if (((ReentrantLock) lock).isHeldByCurrentThread()) {
            lock.unlock();
        }
        super.unlockAfterCommit(transaction);
    }

    /**
     * Returns the path of this resource's backing record file.
     *
     * @return the path of the backing record file of this resource (the resource id as a
     *         string)
     */
    public String getFileName() {
        return resourceId.toString();
    }

    private Collection<Object> readAsObjects() {
        Collection<Object> objects = null;
        try {
            final File file = new File(getFileName());
            if (file.exists()) {
                Reader xmlReader = new java.io.BufferedReader(new FileReader(file), 1 << 16);
                try {
                    if (isReferences) {
                        objects = reader.readReferences(xmlReader, idField);
                    } else {
                        objects = reader.read(xmlReader);
                    }
                } finally {
                    xmlReader.close();
                }
            }
        } catch (final Throwable e) {
            throw new XdStorageRuntimeException("invalid state of database: error by reading resource " + resourceId, e);
        }
        return objects;
    }

    private void writeObjects(final Collection<Object> objects) {
        if (objects.size() == 0) {
            // пустой ресурс = файла нет (прежняя семантика)
            final File file = new File(getFileName());
            if (file.exists())
                file.delete();
            return;
        }

        try {
            // crash-safe: пишем во временный файл-соседа и атомарно подменяем ресурс,
            // чтобы сбой посреди коммита не мог обрезать существующий файл ресурса
            XdStorageAtomicFile.writeReplacing(getFileName(), services.getStorageConfig().isDurableWrites(), out -> {
                if (isReferences) {
                    writer.writeReferences(out, idField, objects);
                } else {
                    writer.writeObjects(out, objects);
                }
            });
        } catch (final Throwable e) {
            throw new XdStorageRuntimeException("invalid state of database: error by writing resource " + resourceId, e);
        }
    }

    /**
     * Reads the persisted id-index records as raw identifiable data (id plus serialized
     * payload), without materializing them through the entity machinery. Absent file
     * yields {@code null}.
     *
     * @param transaction the transaction context of the read (unused by the file read itself)
     * @return the persisted identifiable objects, or {@code null} when the backing file
     *         does not exist
     * @throws XdStorageRuntimeException when the file exists but cannot be parsed — treated
     *         as an invalid database state
     */
    public Collection<XdStorageIdentifiableObject> readAsData(XdStorageTransaction transaction) {
        Collection<XdStorageIdentifiableObject> objects = null;
        try {
            final File file = new File(getFileName());
            if (file.exists()) {
                Reader xmlReader = new java.io.BufferedReader(new FileReader(file), 1 << 16);
                try {
                    objects = reader.readData(xmlReader, idField);
                } finally {
                    xmlReader.close();
                }
            }
        } catch (final Throwable e) {
            throw new XdStorageRuntimeException("invalid state of database: error by reading resource " + resourceId, e);
        }
        return objects;
    }

    /**
     * Prepares the resource for use: on the first call (double-checked under the resource
     * lock) parses the base record file, restores every record key to the ENTITY id-field
     * type, fills the record cache, replays the commit journal over it and builds the id
     * {@code ->} fragment map; later calls are no-ops.
     *
     * @param transaction the transaction the resource is prepared for
     * @throws XdStorageException when the base file cannot be read or the commit journal
     *         cannot be replayed — treated as an invalid database state
     */
    public void prepare(final XdStorageTransaction transaction) throws XdStorageException {
        if (prepared.get()) {
            return;
        }

        lock.lock();
        try {
            if (prepared.get()) {
                return;
            }

            Collection<Object> objects = readAsObjects();

            if (objects != null) {
                // BUG-C (docs/tasks/2.2.0/03): the YAML round-trip does not know the ENTITY's
                // id-field type, so reopened record keys arrive as raw strings/numbers and a
                // lookup by the application's real id (Long 9000 vs stored "9000") misses —
                // every id lookup silently returns null after a reopen for numeric id types.
                // Restore each record key to the id-field type before filling the cache.
                for (final Object object : objects) {
                    if (object instanceof XdStorageHashIndexRecord) {
                        ((XdStorageHashIndexRecord) object).setObjectId(
                                restoreKeyValueType(((XdStorageHashIndexRecord) object).getObjectId()));
                    }
                }
                cache.fillCache(objects);
            }
            replayIndexJournal();
            postPrepare(transaction);

            prepared.set(true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * BUG-C: restores a record key to the entity id-field type after the YAML round-trip.
     * Values that are already of the target type pass through; strings/numbers are parsed
     * via the engine's simple-type conversion; anything unparseable is returned as-is (the
     * lookup then misses loudly instead of corrupting the cache with a wrong-typed key).
     */
    private Object restoreKeyValueType(final Object key) {
        // BUG-C: the authority is the ENTITY's id-field type — this resource's own
        // `clInfo`/`idField` describe the RECORD class (XdStorageHashIndexRecord.objectId,
        // declared Object), which would silently restore nothing
        final Object restored = io.github.xdstorage.utils.XdStorageKeyTypeRestore.restore(
                key, objectClInfo.getIdField().field.getType());
        if (restored != key) {
            log.debug("restored id-index key type: {} -> {}", key, restored);
        }
        return restored;
    }

    /**
     * Phase 1 of the two-phase commit: when the transaction has staged changes, durably
     * persists them — as an O(delta) append to the commit journal (when index journaling
     * is on, followed by a size-threshold checkpoint), or otherwise as a crash-safe atomic
     * full-file rewrite — without making them visible to other transactions.
     *
     * @param transaction the committing transaction
     * @param record      the collector the phase fills with the staged changes for phase 2
     *                    and compensation
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageRuntimeException when the journal append fails — treated as an
     *         invalid database state
     */
    public void performFirstPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        if (cache.hasChanges(transaction)) {
            if (indexJournal && yamlJournalFactory != null) {
                // W-4a: O(delta) journal append instead of the O(N) full-file rewrite — the
                // commit's durability point moves to the journal (W-1 semantics verbatim).
                cache.prepareCommit(transaction, record);
                try {
                    io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.appendRedo(indexJournalFile,
                            transaction.getCommitSerial(), record.getChangesObjects(),
                            services, yamlJournalFactory, services.getStorageConfig().isDurableWrites());
                } catch (final Throwable e) {
                    throw new XdStorageRuntimeException("invalid state of database: error by appending commit journal of index resource " + resourceId, e);
                }
                checkpointIndexJournalIfTooBig(transaction);
            } else {
                final Collection<Object> objects = cache.readUnsafe(transaction);
                cache.prepareCommit(transaction, record);
                writeObjects(objects);
            }
        }
    }

    /**
     * Phase 2 of the two-phase commit: publishes the phase-1 persisted changes into the
     * record cache, commits the transaction's contour and discards the rollback stack.
     *
     * @param transaction the committing transaction
     * @param record      the collector filled by phase 1
     * @throws XdStorageException on a storage-level failure
     */
    public void performSecondPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        if (cache.hasChanges(transaction)) {
            cache.performCommit(transaction, record);
            cache.commit(transaction);
            postCommit(transaction);
        }
    }

    /**
     * Compensates an already-persisted phase 1 after the overall commit failed: restores
     * the pre-transaction committed state per changed id — as a journal compensation
     * append when index journaling is on (so a torn redo cannot resurrect the rolled-back
     * transaction), or otherwise as a full-file rewrite — then applies the id-map rollback
     * compensations.
     *
     * @param transaction the failed transaction
     * @param changes     the staged changes the first phase persisted
     * @throws XdStorageException           on a storage-level failure
     * @throws XdStorageRuntimeException when the journal compensation append fails —
     *         treated as an invalid database state
     */
    public void rollbackPerformingFirstPhaseCommit(final XdStorageTransaction transaction, final Collection<XdStorageObjectChange> changes) throws XdStorageException {
        cache.rollbackFailedCommit(transaction, changes);
        if (indexJournal && yamlJournalFactory != null) {
            // W-4a: compensation — the restored (pre-tx committed) state per changed id, so a
            // torn redo from the failed phase-1 cannot resurrect a rolled-back transaction.
            try {
                io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.appendCompensation(indexJournalFile,
                        changes, services, yamlJournalFactory, services.getStorageConfig().isDurableWrites());
            } catch (final Throwable e) {
                throw new XdStorageRuntimeException("invalid state of database: error by appending index journal compensation of resource " + resourceId, e);
            }
        } else {
            writeObjects(cache.readUnsafe(transaction));
        }
        postRollback(transaction);
    }

    /** W-4a: recovery replay of the id-index journal over the parsed base (W-1 semantics:
     * idempotent by id, last record wins; torn tail discarded by the reader). The id type
     * authority is the ENTITY's id field — the BUG-C lesson applied to the replay path. */
    private void replayIndexJournal() throws XdStorageException {
        if (indexJournalFile == null || yamlJournalFactory == null) {
            return;
        }
        try {
            final io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.Replay replay =
                    io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.readForReplay(
                            indexJournalFile, objectClInfo.getIdField().field.getType(), yamlJournalFactory);
            for (final Object upsert : replay.upserts) {
                if (upsert instanceof XdStorageHashIndexRecord) {
                    ((XdStorageHashIndexRecord) upsert).setObjectId(
                            restoreKeyValueType(((XdStorageHashIndexRecord) upsert).getObjectId()));
                }
                if (idField.get(upsert) == null) {
                    log.error("index resource {} journal replay skips an upsert with an unrestorable identity "
                            + "(class {}): the record is dropped from the read contour", getFileName(),
                            upsert != null ? upsert.getClass().getName() : "null");
                    continue;
                }
                cache.applyCommittedJournalUpsert(upsert);
            }
            for (final Object id : replay.deletes) {
                cache.applyCommittedJournalDelete(id);
            }
            if (!replay.upserts.isEmpty() || !replay.deletes.isEmpty()) {
                log.debug("index resource {} replayed commit journal: {} upsert(s), {} delete(s), {} discarded tail record(s)",
                        getFileName(), replay.upserts.size(), replay.deletes.size(), replay.discardedTailRecords);
            }
            // JRNLNAME: a replayed older-named index journal migrates into the journal directory;
            // superseded twins (both-present anomaly) are removed — after a clean replay
            indexJournalFile = io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.settleAfterReplay(
                    indexJournalFile, supersededIndexJournals, getFileName());
            supersededIndexJournals = java.util.Collections.emptyList();
        } catch (final Throwable e) {
            throw new XdStorageException("invalid state of database: commit journal of index resource " + resourceId + " cannot be replayed", e);
        }
    }

    /** W-4a: the size-threshold checkpoint — full index rewrite + journal reset. */
    private void checkpointIndexJournalIfTooBig(final XdStorageTransaction transaction) throws XdStorageException {
        final long threshold = services.getStorageConfig().getJournalCheckpointBytes();
        if (io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.sizeOf(indexJournalFile) <= threshold) {
            return;
        }
        log.info("index resource {} commit journal exceeded {} bytes — checkpointing (full index rewrite + journal reset)",
                getFileName(), threshold);
        writeObjects(cache.readUnsafe(transaction));
        try {
            io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.reset(indexJournalFile);
        } catch (final Throwable e) {
            log.warn("index journal {} could not be reset after checkpoint (safe: replay is idempotent): {}",
                    indexJournalFile, e.getMessage());
        }
    }

    /**
     * Discards the transaction's staged changes (pre-commit rollback): fail-safe releases
     * the commit lock if the current thread still holds it (emergency rollback path),
     * rolls the record cache back and applies the id-map rollback compensations.
     *
     * @param transaction the transaction being rolled back
     * @throws XdStorageException on a storage-level failure
     */
    public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
        // Гарантируем fail-safe отпуск ReentrantLock замка хеш-индекса при аварийном откате
        if (((ReentrantLock) lock).isHeldByCurrentThread()) {
            lock.unlock();
        }
        cache.rollback(transaction);
        postRollback(transaction);
    }

    /**
     * W-4c: shutdown checkpoint — the full committed index rewrite + journal reset, no
     * transaction. Guarded by {@code prepared} (an unprepared resource's journal belongs to
     * the NEXT open's replay) and by the resource lock with a short timeout (a live commit
     * keeps its journal — replay handles it). Atomic vs appends: the checkpoint holds the
     * journal's append lock across the base write + reset.
     */
    @Override
    public boolean checkpointJournalOnShutdown() {
        if (!indexJournal || indexJournalFile == null || !prepared.get()) {
            return false;
        }
        boolean locked = false;
        try {
            locked = lock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!locked) {
            log.warn("index resource {} is busy at shutdown — journal left for replay at the next open", getFileName());
            return false;
        }
        try {
            synchronized (io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.lockFor(indexJournalFile)) {
                if (io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.sizeOf(indexJournalFile) == 0) {
                    return false;
                }
                log.info("index resource {} shutdown checkpoint: full index rewrite + journal reset", getFileName());
                writeObjects(cache.readCommittedForCheckpoint());
                io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.reset(indexJournalFile);
                return true;
            }
        } catch (final Throwable e) {
            log.warn("index resource {} shutdown checkpoint failed (safe: the journal replays at the next open): {}",
                    getFileName(), e.getMessage());
            return false;
        } finally {
            if (((ReentrantLock) lock).isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Indexes a new object, guarding the base routing insert with a duplicate check and
     * id generation: generates the id when the entity's id generator requires it and the
     * id is still unset, and fails loudly when the id is already indexed.
     *
     * @param object      the object to insert
     * @param transaction the transaction the insert is staged into
     * @throws XdStorageException           when the object's id is already indexed, or on a
     *         storage-level failure
     * @throws XdStorageConnectionException on a connection-level failure (a retry may help)
     */
    @Override
    public void insert(final Object object, final XdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        Object objectId = objectIdField.get(object);
        // BUG-D (docs/tasks/2.1.0/14): UUID_GENERATOR generates here too — the mux
        // dispatches by id type and validates UUID_GENERATOR to String id fields.
        if (objectId == null &&
                (objectIdField.getIdGeneretorType() == XdStorageIdGeneratorType.DATABASE_GENERATOR
                        || objectIdField.getIdGeneretorType() == XdStorageIdGeneratorType.UUID_GENERATOR
                        || objectIdField.getIdGeneretorType() == XdStorageIdGeneratorType.CUSTOM_GENERATOR)
                && idGenerator != null) {
            objectIdField.set(XdStorageObserverService.getObservableWrapper(object), objectId = idGenerator.generate(object.getClass(), services.getStorage(), transaction));
        }
        final Object resourceId = index.getResourceId(objectId);
        if (resourceId != null) {
            throw new XdStorageException("object of class " + object.getClass() + " with idgeneration " + objectId + " is exists");
        }
        super.insert(object, transaction);
    }
}
