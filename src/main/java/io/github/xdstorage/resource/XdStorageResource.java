package io.github.xdstorage.resource;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.serialization.IXdStorageIOFactory;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.resource.cache.XdStorageCacheRecord;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import io.github.xdstorage.utils.XdStorageAtomicFile;
import io.github.xdstorage.utils.XdStorageClassInfo;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One file-backed DAO resource — the physical home of an entity's records (a class-shared
 * fragment, a per-object file or a references file). On top of the abstract base it adds the
 * disk I/O (read/write through the configured serialization factory, atomic write-out) and
 * the real transactional machinery: lazy prepare-from-disk with commit-journal replay, the
 * two commit phases (full-file write-out, or an O(delta) journal append when journaling is
 * on), compensation of a failed phase 1, and the shutdown journal checkpoint.
 *
 * <p>Thread-safety: one instance serves all threads; commits are serialized per resource by
 * a {@code ReentrantLock} acquired with the committing transaction's timeout
 * ({@link #lockForCommit}), while {@code prepare} is double-checked under its own lock.
 * Reads and staged writes resolve through the MVCC cache.</p>
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public class XdStorageResource extends XdStorageAbstractResource implements IXdStorageJournalCheckpoint {

    private static final Logger log = LoggerFactory.getLogger(XdStorageResource.class);

    private final IXdStorageObjectsReader reader;

    private final IXdStorageObjectsWriter writer;

    /** W-1: append-only commit journal; null = journaling off (full-file rewrite per commit, прежнее поведение). */
    private java.nio.file.Path journalFile;

    // JRNLNAME: older-named journals present at resolution (replay sources / superseded twins)
    private java.util.List<java.nio.file.Path> supersededJournals = java.util.Collections.emptyList();

    private final io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory yamlJournalFactory;

    private Lock lock = new ReentrantLock();

    private AtomicBoolean prepared = new AtomicBoolean(false);

    /**
     * Builds a resource with in-process id generation; journaling follows the storage
     * configuration and is applied only to user-data resources.
     *
     * @param services   engine services shared with collaborators
     * @param manager    the owning resources manager
     * @param resourceId the storage-wide unique resource id (the file path)
     * @param clInfo     metadata of the entity class
     * @param factory    the serialization factory creating the record reader and writer
     */
    public XdStorageResource(final XdStorageServicesLocator services,
                             final XdStorageAbstractResourcesManager manager,
                             final Object resourceId,
                             final XdStorageClassInfo clInfo,
                             final IXdStorageIOFactory factory) {
        this(services, manager, resourceId, clInfo, null, false, factory);
    }

    /**
     * Builds a resource with full configuration. Journaling is enabled only when the storage
     * configuration turns it on AND the resource stores user data — B+-tree infrastructure
     * caches keep the full-rewrite strategy because journal replays of their UPSERTs would
     * fork the canonical in-memory graphs.
     *
     * @param services     engine services shared with collaborators
     * @param manager      the owning resources manager
     * @param resourceId   the storage-wide unique resource id (the file path)
     * @param clInfo       metadata of the entity class
     * @param idGenerator  external id generator, or {@code null} for in-process ids
     * @param isReferences whether this is a references resource
     * @param factory      the serialization factory creating the record reader and writer
     */
    public XdStorageResource(final XdStorageServicesLocator services,
                             final XdStorageAbstractResourcesManager manager,
                             final Object resourceId, final XdStorageClassInfo clInfo,
                             final IXdStorageIdGenerator idGenerator,
                             final boolean isReferences,
                             final IXdStorageIOFactory factory) {
        super(services, manager, resourceId, clInfo, idGenerator, isReferences);
        this.reader = factory.newInstanceReader();
        this.writer = factory.newInstanceWriter();
        // W-1 scope (WRITE-PATH-DESIGN.md): журнал ведут ТОЛЬКО ресурсы пользовательских данных.
        // Инфраструктура B+Дерева (деревья/ноды/references) хранит канонические живые графы —
        // реплей их UPSERT-ов при переоткрытии плодит конфликтные копии канонических инстансов
        // (реестр R-20) и рвет дерево: инфраструктура сохраняется полной перезаписью, как раньше.
        this.journalWrites = services.getStorageConfig() != null && services.getStorageConfig().isJournalWrites()
                && !cache.isInfrastructureCache() && !cache.isStructuralNodeCache();
        if (journalWrites) {
            final io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.Resolution resolution =
                    io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.resolveFor(getFileName());
            this.journalFile = resolution.file;
            this.supersededJournals = resolution.superseded;
        } else {
            this.journalFile = null;
            this.supersededJournals = java.util.Collections.emptyList();
        }
        this.yamlJournalFactory = journalWrites && factory instanceof io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory
                ? (io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory) factory
                : null;
        // W-2: бюджет материализации считают ТОЛЬКО ресурсы данных (id-индексные кэши — служебные)
        this.cache.setEnforceMaterializationBudget(true);
    }

    private final boolean journalWrites;

    private Map<String, Long> blockingTime = new HashMap<>();

    private AtomicBoolean locked = new AtomicBoolean(false);

    @Override
    public void lockForCommit(final XdStorageTransaction transaction) {
        super.lockForCommit(transaction);

        // ИСПРАВЛЕНИЕ ДЕДЛОКА СУБД: Полностью ликвидируем опасный synchronized(locked) и locked.wait()!
        // Вместо кустарного монитора используем промышленный ReentrantLock 'lock' с поддержкой
        // tryLock() по таймауту транзакции. Если ресурс занят другим коммитом, поток чисто
        // дождется его или выбросит контролируемый таймаут, не утягивая ядро в циклический Deadlock!
        final long timeout = transaction != null ? transaction.getTimeout() : 3000L;
        try {
            boolean acquired = lock.tryLock(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new XdStorageRuntimeException("resource " + getFileName() + " cannot be locked for commit by transaction "
                        + transaction.getTransactionId() + " (Timeout " + timeout + " ms expired)");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XdStorageRuntimeException("waiting for lock resource " + getFileName() + " has been interrupted", e);
        }
    }

    @Override
    public void unlockAfterCommit(final XdStorageTransaction transaction) {
        // ИСПРАВЛЕНИЕ ДЕДЛОКА СУБД: Освобождаем ReentrantLock вместо synchronized-уведомлений
        if (((ReentrantLock) lock).isHeldByCurrentThread()) {
            lock.unlock();
        }

        super.unlockAfterCommit(transaction);
    }

    /**
     * The path of this resource's backing file (the resource id rendered as a string).
     *
     * @return the backing file path
     */
    public String getFileName() {
        return resourceId.toString();
    }

    private Collection<Object> readAsObjects() throws XdStorageException {
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
            throw new XdStorageException("invalid state of database: error by reading resource " + resourceId, e);
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


        // R-43: guard-ы берутся ДО атомарной записи и отпускаются гарантированно в finally
        final List<Object> guarded = new ArrayList<>();
        final long writeStartNanos = System.nanoTime();
        try {
            for (final Object object : objects) {
                if (object instanceof io.github.xdstorage.serialization.IXdStorageSerializationGuard) {
                    ((io.github.xdstorage.serialization.IXdStorageSerializationGuard) object).beginConsistentSerialization();
                    guarded.add(object);
                }
            }
            // crash-safe: пишем во временный файл-соседа и атомарно подменяем ресурс,
            // чтобы сбой посреди коммита не мог обрезать существующий файл ресурса
            XdStorageAtomicFile.writeReplacing(getFileName(), services.getStorageConfig().isDurableWrites(), out -> {
                if (isReferences) {
                    writer.writeReferences(out, idField, objects);
                } else {
                    // R-43: живые инстансы (канонический реестр B+Tree) сериализуются под
                    // guard-ом самого объекта — параллельный сплит/джойн больше не рвет
                    // LinkedList под итератором писателя (CME) и не оставляет рваный XML.
                    writer.writeObjects(out, objects);
                }
            });
            services.getMetrics().increment(io.github.xdstorage.metrics.XdStorageMetrics.RESOURCE_WRITE_OUTS);
            services.getMetrics().add(io.github.xdstorage.metrics.XdStorageMetrics.RESOURCE_WRITE_OUT_NANOS,
                    System.nanoTime() - writeStartNanos);
        } catch (final Throwable e) {
            throw new XdStorageRuntimeException("invalid state of database: error by writing resource " + resourceId, e);
        } finally {
            for (final Object object : guarded) {
                ((io.github.xdstorage.serialization.IXdStorageSerializationGuard) object).endConsistentSerialization();
            }
        }
    }

    public Collection<XdStorageIdentifiableObject> readAsData(final XdStorageTransaction transaction) throws XdStorageException {
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
            throw new XdStorageException("invalid state of database: error by reading resource " + resourceId, e);
        }
        return objects;
    }

    /**
     * Loads the resource state from disk into the cache on the first call: parses the base
     * file, replays the commit journal over it when journaling is on, runs the subclass
     * hook and syncs the manager's object counter. Later calls (double-checked under the
     * prepare lock) are no-ops.
     *
     * @param transaction the transaction the resource is prepared for
     * @throws XdStorageException when the base file or the journal cannot be read or parsed
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
            log.debug("resource {} prepared from disk: {} objects", getFileName(), objects != null ? objects.size() : 0);
            if (objects != null)
                cache.fillCache(objects);
            if (journalWrites) {
                replayCommitJournal();
            }
            postPrepare(transaction);
            manager.updateCounter(getObjectsClass(), this);

            prepared.set(true);
        } finally {
            lock.unlock();
        }
    }

    /** W-1: восстановление закоммиченного состояния из журнала поверх базы (идемпотентно, last writer wins). */
    private void replayCommitJournal() throws XdStorageException {
        try {
            final io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.Replay replay =
                    io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.readForReplay(
                            journalFile, idField.field.getType(), yamlJournalFactory);
            for (final Object upsert : replay.upserts) {
                // BUG-A (docs/tasks/2.1.0/15) recovery semantics: a record whose id cannot be
                // restored (its identity class was unresolvable at replay time) is SKIPPED
                // loudly instead of poisoning the cache with a null key (the old behavior —
                // a ConcurrentHashMap null-key NPE that stranded the whole folder at open).
                // The identity-bearing data is re-validated by the next structure pass.
                if (idField.get(upsert) == null) {
                    log.error("resource {} journal replay skips an upsert with an unrestorable identity "
                            + "(class {}): the record is dropped from the read contour — "
                            + "run the structure update to re-establish it", getFileName(),
                            upsert != null ? upsert.getClass().getName() : "null");
                    continue;
                }
                cache.applyCommittedJournalUpsert(upsert);
            }
            for (final Object id : replay.deletes) {
                cache.applyCommittedJournalDelete(id);
            }
            if (!replay.upserts.isEmpty() || !replay.deletes.isEmpty()) {
                log.debug("resource {} replayed commit journal: {} upsert(s), {} delete(s), {} discarded tail record(s)",
                        getFileName(), replay.upserts.size(), replay.deletes.size(), replay.discardedTailRecords);
            }
            // JRNLNAME: a replayed older-named journal migrates into the journal directory;
            // superseded twins (both-present anomaly) are removed — after a clean replay
            journalFile = io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.settleAfterReplay(
                    journalFile, supersededJournals, getFileName());
            supersededJournals = java.util.Collections.emptyList();
        } catch (final Throwable e) {
            throw new XdStorageException("invalid state of database: commit journal of resource " + resourceId + " cannot be replayed", e);
        }
    }

    /**
     * Phase 1: persists the transaction's changes durably without publishing them. With
     * journaling on, fills the collector and appends an O(delta) redo record to the commit
     * journal (checkpointing the journal first when it grew past its size threshold);
     * otherwise falls back to the atomic full-file write-out of the transaction's view.
     * A resource without changes in the transaction is skipped.
     *
     * @param transaction the committing transaction
     * @param record      the collector filled with the staged changes for phase 2 and
     *                    compensation
     * @throws XdStorageException on a storage-level failure
     */
    public void performFirstPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        if (cache.hasChanges(transaction)) {
            if (journalWrites) {
                // W-1: журнал — точка записи фазы 1: O(delta) аппенд вместо полной перезаписи файла.
                // Изменения берутся ПОСЛЕ prepareCommit — коллектор (type, id, old, new) заполнен им.
                cache.prepareCommit(transaction, record);
                try {
                    io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.appendRedo(journalFile,
                            transaction.getCommitSerial(), record.getChangesObjects(),
                            services, yamlJournalFactory, services.getStorageConfig().isDurableWrites());
                } catch (final Throwable e) {
                    throw new XdStorageRuntimeException("invalid state of database: error by appending commit journal of resource " + resourceId, e);
                }
                checkpointIfJournalTooBig(transaction);
            } else {
                final Collection<Object> objects = cache.readUnsafe(transaction);
                cache.prepareCommit(transaction, record);
                writeObjects(objects);
            }
        }
    }

    /** W-1: порог размера журнала — чекпоинт (полная перезапись базы существующим writer-ом + сброс журнала). */
    private void checkpointIfJournalTooBig(final XdStorageTransaction transaction) throws XdStorageException {
        final long threshold = services.getStorageConfig().getJournalCheckpointBytes();
        if (io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.sizeOf(journalFile) <= threshold) {
            return;
        }
        log.info("resource {} commit journal exceeded {} bytes — checkpointing (full base rewrite + journal reset)",
                getFileName(), threshold);
        writeObjects(cache.readUnsafe(transaction));
        try {
            io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.reset(journalFile);
        } catch (final Throwable e) {
            // безвредно: база уже содержит все закоммиченное; повтор реплея поверх нее идемпотентен
            log.warn("commit journal {} could not be reset after checkpoint (safe: replay is idempotent): {}",
                    journalFile, e.getMessage());
        }
    }

    /**
     * Phase 2: publishes the phase-1 persisted changes into the cache (visible state), then
     * runs the subclass hook. In-memory only — durability was established in phase 1.
     *
     * @param transaction the committing transaction
     * @param record      the collector filled by phase 1
     * @throws XdStorageException on a storage-level failure
     */
    public void performSecondPhaseCommit(final XdStorageTransaction transaction, final XdStorageTransactionResourceChanges record) throws XdStorageException {
        cache.performCommit(transaction, record);
        cache.commit(transaction);
        postCommit(transaction);
    }

    /**
     * W-4c: shutdown checkpoint — full committed-state rewrite + journal reset, no
     * transaction (the cache's committed view; see
     * {@link XdStorageResourceCache#readCommittedForCheckpoint()}). Guarded by
     * {@code prepared}: a resource never prepared in THIS run has an empty cache and a
     * journal that belongs to the NEXT open's replay — checkpointing it would destroy
     * the base. Atomic vs appends: the whole checkpoint holds the journal's append lock.
     */
    @Override
    public boolean checkpointJournalOnShutdown() {
        if (!journalWrites || journalFile == null || !prepared.get()) {
            return false;
        }
        synchronized (io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.lockFor(journalFile)) {
            try {
                if (io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.sizeOf(journalFile) == 0) {
                    return false;
                }
                log.info("resource {} shutdown checkpoint: full committed-state rewrite + journal reset", getFileName());
                writeObjects(cache.readCommittedForCheckpoint());
                io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.reset(journalFile);
                return true;
            } catch (final Throwable e) {
                // безвредно: журнал остается — реплей при следующем открытии вернет состояние
                log.warn("resource {} shutdown checkpoint failed (safe: the journal replays at the next open): {}",
                        getFileName(), e.getMessage());
                return false;
            }
        }
    }

    /**
     * Compensation after a failed phase 1: undoes the prepared changes in the cache and
     * persists the restored pre-commit state — a compensation append to the journal when
     * journaling is on (mandatory: without it a crash right after the redo append would
     * resurrect the rejected transaction at replay), otherwise a full-file write-out.
     *
     * @param transaction the failed transaction
     * @param changes     the staged changes phase 1 persisted
     * @throws XdStorageException on a storage-level failure
     */
    public void rollbackPerformingFirstPhaseCommit(final XdStorageTransaction transaction, final Collection<XdStorageObjectChange> changes) throws XdStorageException {
        cache.rollbackFailedCommit(transaction, changes);
        if (journalWrites) {
            // W-1: компенсация — восстановленное (до-транзакционное) состояние каждого измененного id.
            // Писать в журнал обязательно: без этого обрыв после аппенда redo вернул бы отклоненную транзакцию при реплее.
            try {
                io.github.xdstorage.serialization.yaml.XdStorageYamlCommitJournal.appendCompensation(journalFile,
                        changes, services, yamlJournalFactory, services.getStorageConfig().isDurableWrites());
            } catch (final Throwable e) {
                throw new XdStorageRuntimeException("invalid state of database: error by appending commit journal compensation of resource " + resourceId, e);
            }
        } else {
            writeObjects(cache.readUnsafe(transaction));
        }
        postRollback(transaction);
    }

    /**
     * Discards the transaction's staged changes in the cache (pre-commit rollback) and runs
     * the subclass hook; as a fail-safe it also releases the commit lock if this thread
     * still holds it. The persistent state is untouched.
     *
     * <p>TREEFILE (docs/tasks/2.1.0/41): the infrastructure reload after a rollback does
     * NOT happen here — it runs ONCE per transaction, tree-coherently, from
     * {@code XdStorageTransaction.recoverInfraFromCommittedFiles} (a per-resource reload
     * races concurrent transactions' staged state and forks canonical graphs mid-flight;
     * only a whole-tree, quiet-window, tree-write-locked reload is safe).</p>
     *
     * @param transaction the transaction being rolled back
     * @throws XdStorageException on a storage-level failure
     */
    @Override
    public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
        // Гарантируем fail-safe отпуск ReentrantLock замка при откате изменений страницы
        if (((ReentrantLock) lock).isHeldByCurrentThread()) {
            lock.unlock();
        }
        cache.rollback(transaction);
        postRollback(transaction);
    }

    /**
     * TREEFILE (docs/tasks/2.1.0/41): whether this resource hosts B+tree infrastructure
     * records (the tree objects and the nodes) — the aliased live-canonical-graph caches.
     *
     * @return {@code true} when the entity class belongs to the btree infrastructure package
     */
    public boolean isInfrastructureResource() {
        return cache.isInfrastructureCache();
    }

    /**
     * TREEFILE: whether every record of this infrastructure resource is free of staged
     * mutations by OTHER live transactions (the transaction's own staged records were
     * already reverted by its rollback). The quiet check is what makes a reload safe:
     * records staged by a live foreign transaction reference workspace state that a
     * canonical swap would orphan.
     *
     * @param transaction the transaction being rolled back (its own records don't count)
     * @return {@code true} when no other live transaction holds staged state here
     */
    public boolean isInfraQuietForRollbackOf(final XdStorageTransaction transaction) {
        for (final XdStorageCacheRecord record : cache.records().values()) {
            final XdStorageTransaction owner = record.getOwnerTransaction();
            if (owner != null && owner != transaction && !record.isCommitedState()
                    && services.getTransactionsManager().isTransactionAlive(owner)) {
                return false;
            }
        }
        return true;
    }

    /**
     * TREEFILE: the tree instance this resource's canonical records belong to — the
     * coordination point for the coherent tree-group reload. For a tree-record resource
     * this is the recorded {@code XdStorageBTree}; for a node resource it is the live
     * node's owning tree (fixed up by {@code materialize} at first touch). Returns
     * {@code null} when the records carry no live tree reference (stubs/inert records —
     * nothing coordinates through them).
     *
     * @return the owning tree instance, or {@code null} when it cannot be resolved
     */
    public Object resolveInfrastructureTree() {
        for (final XdStorageCacheRecord record : cache.records().values()) {
            final Object body = record.getObject();
            if (body instanceof io.github.xdstorage.btree.XdStorageBTree) {
                return body;
            }
            if (body instanceof io.github.xdstorage.btree.XdStorageBTreeNode) {
                return ((io.github.xdstorage.btree.XdStorageBTreeNode) body).getTree();
            }
        }
        return null;
    }

    /**
     * TREEFILE (docs/tasks/2.1.0/41): reloads this infrastructure resource from its
     * last-committed file, replacing cache records and canonical registrations with the
     * fresh instances. Infrastructure records are deliberately NOT cloned on write
     * (живой канонический граф, R-40/R-53): a rolled-back transaction's structural
     * mutations (root collapse, node emptying, splits) live directly on the aliased
     * canonical instances, and record rollback "restores" them to the post-mutation
     * state. Two compensation attempts (2026-10-25) proved the mutated in-memory node
     * graph cannot be repaired in place. The rolled-back transaction never wrote files
     * (full-file write-out happens at commit), so the file IS the pre-transaction truth:
     * the fresh parse restores it. Fresh tree instances also naturally reset
     * {@code collapsedEmpty}/{@code wasInitialized}/key-type-restore flags, so the tree's
     * own refresh machinery rebuilds a persistence-faithful graph — the same state a
     * reopen would produce, without closing the storage.
     *
     * <p>Safety rules: records staged as uncommitted mutations by OTHER live transactions
     * are kept as-is (their canonical instances and workspace records belong to a tx that
     * is not rolling back — a swap would orphan their staged inserts); parse stubs never
     * clobber existing full instances (R-53). Records the committed file does not know are
     * deliberately KEPT (the R-40 {@code rollbackInsertKeepServing} contract): a
     * concurrently committed transaction can have durably written a parent file that
     * references such an orphan node (the aliased in-place-write hazard family), and the
     * kept-serving record is what later flushes heal the file — dropping it makes the
     * dangling reference permanent ("referenced but missing"). The resource commit lock is
     * taken for the swap so a concurrent two-phase commit (full-file write + cache
     * publish) is not interleaved.</p>
     *
     * <p>Called by {@code XdStorageTransaction.recoverInfraFromCommittedFiles} per
     * TREE GROUP, under the group tree's write lock — never per-resource from
     * {@link #rollback}, which would race concurrent transactions and fork canonical
     * graphs mid-flight.</p>
     *
     * @param transaction the transaction being rolled back
     * @throws XdStorageException when the committed file exists but cannot be parsed
     */
    public void reloadInfrastructureFromCommittedFiles(final XdStorageTransaction transaction) throws XdStorageException {
        final Class<?> infraClass = cache.entityClass();
        final io.github.xdstorage.resource.XdStorageCanonicalInstanceRegistry registry = services.getCanonicalInstances();
        final Map<Object, XdStorageCacheRecord> records = cache.records();

        // Сериализуемся с идущими коммитами этого ресурса (фаза-1 полная перезапись +
        // фаза-2 публикация в кэш); коммит короткий, откат важнее — по таймауту идем без лока.
        // Вызов происходит под write-локом дерева группы: структурные мутации чужих tx
        // исключены, остаются только их коммиты — их ждет ресурсный ReentrantLock.
        boolean lockedHere = false;
        try {
            lockedHere = lock.tryLock(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!lockedHere) {
            log.warn("TREEFILE: infra reload of resource {} proceeds WITHOUT the commit lock (10 s budget expired) — racing-commit window accepted", resourceId);
        }
        try {
            final Collection<Object> parsed = readAsObjects(); // null = файла нет

            if (parsed != null) {
                // Подменяем записи и каноны свежим парсом (упор: свежий парс ВЫИГРЫВАЕТ).
                // Записи, которых в файле нет, НЕ выбрасываем (R-40 rollbackInsertKeepServing):
                // см. контракт в javadoc. Чужие живые незакоммиченные записи пропускать
                // небезопасно, но к этому моменту группа проверена на тихость под локом дерева —
                // повторная проверка здесь была бы гонкой в обратную сторону.
                for (final Object fresh : parsed) {
                    final Object id = idField.get(fresh);
                    if (id == null) {
                        continue;
                    }
                    final boolean stub = fresh instanceof io.github.xdstorage.code.IXdStorageReferenceMarked
                            && ((io.github.xdstorage.code.IXdStorageReferenceMarked) fresh).isReference();
                    if (stub && records.containsKey(id)) {
                        continue; // R-53: стаб не затирает полный инстанс
                    }
                    records.put(id, cache.createReadRecord(fresh));
                    if (!stub && registry != null) {
                        registry.register(infraClass, id, fresh);
                    }
                }
            }
            log.debug("TREEFILE: infra resource {} reloaded from committed file after rollback: {} record(s) parsed",
                    resourceId, parsed != null ? parsed.size() : 0);
        } finally {
            if (lockedHere) {
                lock.unlock();
            }
        }
    }
}
