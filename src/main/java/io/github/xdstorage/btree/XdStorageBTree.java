package io.github.xdstorage.btree;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.lock.XdStorageReadWriteLock;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The B+tree index structure of the engine: an ordered key-to-record map persisted as one
 * file per node ({@code XdStorageBTreeNode}), with the tree object itself holding the root
 * and the first leaf. Leaves form an ordered chain over next-level pointers — the backbone
 * of the horizontal scans {@code read}/{@code readRange}. A tree is either UNIQUE
 * ({@code multiple == false}, one record per key — the primary id-address index) or MULTI
 * ({@code multiple == true}, duplicates allowed — a search index); for MULTI trees the
 * engine deliberately forbids vertical descents on reads (R-46: splits may reorder duplicate
 * keys across left/right branches), so scans traverse the whole leaf chain from
 * {@code firstLeaf}, and {@code readRange} (FP-1a) recovers sub-linear behavior through
 * early termination once the ordered chain passes the upper bound.
 *
 * <p>Thread safety: every operation serializes on the tree-level read/write lock; mutations
 * additionally hold write locks along the rebalanced node path under a bounded
 * retry-with-backoff budget (livelock containment). On a search the tree read lock is
 * released by the node descent itself at the lock-coupling point — callers must not wrap
 * {@code find}/{@code read} in an external unlock. The structure-mutation counters
 * ({@code currentStructureVersion}, {@code activeMutationCount}) let the invariant
 * validator reject non-atomic observations.</p>
 *
 * <p>After a reopen the generic deserializer hands back raw-String keys/records for search
 * indexes; the types are restored once per tree instance by
 * {@code restoreSearchKeyTypes} (task 18 / BUG-E).</p>
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class XdStorageBTree implements IXdStorageBTreeNode, io.github.xdstorage.serialization
        .IXdStorageSerializationGuard {

    private static final Logger log = LoggerFactory.getLogger(XdStorageBTree.class);

    /** R-36→R-54: бюджет ретраев спуска поиска — по ВРЕМЕНИ (см. find), не по попыткам.
     *  Верхняя граница дедлайна: даже при большом таймауте транзакции читатель не hangs дольше 10с. */
    private static final long FIND_RETRY_BUDGET_CAP_MS = 10000;
    /** R-54: потолок экспоненциального backoff между ретраями поиска. */
    private static final long FIND_RETRY_BACKOFF_MAX_MILLIS = 1000;

    /** R-38: страховка предпрогрева цепи листьев от зацикливания (штатная цепь конечна). */
    private static final int PREWARM_STEP_BUDGET = 100;

    @XdStorageObjectId
    private XdStorageBTreeId id;

    private boolean isReference = true;

    private boolean multiple;

    private int t;

    private XdStorageBTreeNode root;

    private XdStorageBTreeNode firstLeaf;

    // R56SCAN (task 23): детерминированный-repro шов — тест гейтит prewarm между
    // materialize стаба и записью слота цепи (choreography: split в окне чинки).
    // Производство никогда не выставляет; см. XdStorageBTreeWalkSplitRaceTest.
    volatile Runnable prewarmChinkGate;

    // R56SCAN (task 23): the capture→prewarm window gate — the deterministic-repro
    // seam for the multi-scan race (the split during the prewarm). Production never sets.
    volatile Runnable prewarmGate;

    // R56SCAN (task 23): the STRUCTURE EPOCH — bumped by every structural primitive
    // (setRoot/setFirstLeaf/setNextTreeNodeOnThisLevel). The walk captures the epoch
    // before the traversal and validates after: a change means the walk traversed a
    // mixed pre/post-structure view (the moved keys double-emitted or missed) — the
    // walk is RETRIED with a resettable viewer. Volatile: the writers bump under the
    // write lock, the readers read it unlocked (the racy read only over-triggers a retry).
    private volatile long structureEpoch;

    /** R56SCAN: bumps the structure epoch (called by the structural primitives only). */
    void bumpStructureEpoch() {
        structureEpoch++;
    }

    /** Package-private test seam: the gate invoked before the prewarm chain-slot write. */
    void setPrewarmChinkGate(final Runnable gate) {
        this.prewarmChinkGate = gate;
    }

    /** Package-private test seam accessor. */
    Runnable getPrewarmChinkGate() {
        return prewarmChinkGate;
    }

    /** Package-private test seam: the capture→prewarm window gate. */
    void setPrewarmGate(final Runnable gate) {
        this.prewarmGate = gate;
    }

    // Маркер жизненного цикла: фиксирует, что дерево хотя бы раз инициализировало структуру узлов.
    // Позволяет надежно отличать первичную пустую базу от базы, опустошенной в ходе транзакции очистки!
    private boolean wasInitialized = false;
    /** TREEFILE: set when a collapse legitimately emptied the tree (root=null); see setRoot. */
    private volatile boolean collapsedEmpty;

    /**
     * Default constructor for the persistence layer — an empty, unresolved tree whose
     * fields the deserializer fills in.
     */
    public XdStorageBTree() {
        // do nothing
    }

    /**
     * Creates a live tree with the given identity and fill factor.
     *
     * @param id       the tree's persistent identity (indexed class + index name)
     * @param multiple {@code true} for a duplicate-bearing (search) index,
     *                 {@code false} for a unique index
     * @param t        the fill factor (minimum degree): a settled node holds between
     *                 {@code t-1} and {@code 2t-1} keys and splits at {@code 2t}
     * @throws XdStorageRuntimeException when {@code t} is less than or equal to 1
     */
    public XdStorageBTree(final XdStorageBTreeId id, final boolean multiple, final int t) {
        this.id = id;
        this.isReference = false;
        this.multiple = multiple;
        this.t = t;

        if (t <= 1) {
            throw new XdStorageRuntimeException("Parameter t of BTree cannot be less than or equal to 1");
        }
    }

    private final XdStorageReadWriteLock lock = new XdStorageReadWriteLock();

    // BUG-E (docs/tasks/2.1.0/18): the search-index keys live in the node YAML files and
    // come back from a reopen as raw Strings (the key field is declared Object) — the first
    // typed seek crashes (Long bound vs String key). The restore walks the leaf chain ONCE
    // per tree instance; this flag is the once-guard (the canonical instance lives for the
    // storage's lifetime, so the flag does too).
    private final java.util.concurrent.atomic.AtomicBoolean searchKeysTypeRestored =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // R-56: детектор структурных мутаций. structureVersion инкрементируется на входе в
    // каждую мутацию (insert/delete/update), activeMutations считает мутации «в полете».
    // Валидатор сэмплирует пару (version, active) до/между/после двух своих обходов:
    // изменение версии или активные мутации означают, что наблюдение не атомарно
    // (канал параллельной мутации), и результат валидации обязан быть отброшен/повторен,
    // а не превращен в громкий отказ («рассинхрон структуры»).
    private final AtomicLong structureVersion = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger activeMutations = new java.util.concurrent.atomic
            .AtomicInteger();
    /** R-56 диагностика: кто именно внутри мутационной секции (поток → описание операции). */
    private final java.util.concurrent.ConcurrentHashMap<Thread, String> activeMutatorThreads =
            new java.util.concurrent.ConcurrentHashMap<>();

    // =========================================================================
    // TREEOVERLAY (docs/tasks/2.1.0/53-tree-overlay.md, W4-DESIGN task 41):
    // мутации overlay-деревьев (id_index / *_foreign_key) стейджатся в per-tx overlay
    // и применяются пакетом в phase-1 ресурса-владельца. Физическое дерево НЕ мутируется
    // до применения: rollback = discard overlay (звук по построению), change-слот записи
    // дерева свободен на всё время TX (штопор конкурентной записи исчезает).
    // =========================================================================
    /** CHM null-key sentinel: the deferred pairs stage with the null front/reverse. */
    private static final Comparable NULL_KEY = new Comparable<Comparable>() {
        @Override
        public int compareTo(final Comparable o) {
            return o == NULL_KEY ? 0 : -1;
        }
        @Override
        public String toString() {
            return "null";
        }
    };

    private static Comparable nkey(final Comparable key) {
        return key == null ? NULL_KEY : key;
    }

    private static final class TreeOverlay {
        /**
         * TREEOVERLAY (task 53): КОНКУРЕНТНЫЕ структуры. Один tx может обслуживать
         * несколько потоков (моки стресс-тестов; R-41 internal-tx) — стейджинг
         * конкурентен: ops — FIFO-очередь (порядок реплея), maps — CHM, значения —
         * CopyOnWriteArrayList (мало записей, много чтений merge'ем).
         */
        final java.util.Queue<Object[]> ops = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final Map<Comparable, List<Object>> stagedInserts = new ConcurrentHashMap<>();
        final Map<Comparable, Set<Object>> stagedDeletes = new ConcurrentHashMap<>();
    }

    /** txId → overlay (txId — глобально-уникальный UUID, пересечений между сторами нет). */
    private final Map<String, TreeOverlay> overlays = new ConcurrentHashMap<>();

    /** Глобальный реестр «какие деревья трогала TX» — для apply/discard из ресурсов-владельцев. */
    private static final java.util.concurrent.ConcurrentMap<String, Set<XdStorageBTree>> TX_OVERLAY_TREES =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** txId → storage, зафиксированный при стейджинге (apply на коммите идёт без параметра). */
    private static final java.util.concurrent.ConcurrentMap<String, IXdStorage> TX_OVERLAY_STORAGE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * txId → undo-журнал ПРИМЕНЕННЫХ (на коммите) операций: {tree, "I"|"D", key, value}.
     * Apply физически мутирует дерево ДО успешного завершения фаз — если TX после apply
     * откатывается (sweeper, veto чужого ресурса), undo-replay возвращает деревья в
     * до-примененное состояние. Коммит-успех → dropAppliedUndos.
     */
    private static final java.util.concurrent.ConcurrentMap<String, java.util.Deque<Object[]>> APPLIED_UNDOS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Overlay-режим определяется РОЛЬЮ дерева по имени id: идексные деревья ("id_index")
     * и FK-деревья ("*_foreign_key") стейджат; прочие (поисковые и т.п.) живут по-старому.
     * Производится от имени, а не хранится флагом: инстанс дерева пересоздаётся при
     * реопене из файла, и флаг пережил бы reload только в имени.
     */
    private boolean isOverlayMode() {
        final XdStorageBTreeId treeId = this.id;
        if (treeId == null || treeId.getName() == null) {
            return false;
        }
        final String name = treeId.getName();
        return "id_index".equals(name) || name.endsWith("_foreign_key");
    }

    private TreeOverlay newOverlay(final IXdStorageTransaction transaction) {
        // ВАЖНО: НЕ делать overlays.put здесь — метод вызывается ИЗ
        // overlays.computeIfAbsent: рекурсивная модификация той же CHM = "Recursive update"
        final TreeOverlay overlay = new TreeOverlay();
        TX_OVERLAY_TREES.computeIfAbsent(transaction.getTransactionId(), k -> ConcurrentHashMap.newKeySet()).add(this);
        return overlay;
    }

    private void stageInsert(final IXdStorageTransaction transaction, final Comparable key, final Object value, final
            IXdStorage storage) {
        TX_OVERLAY_STORAGE.putIfAbsent(transaction.getTransactionId(), storage);
        final TreeOverlay overlay = overlays.computeIfAbsent(transaction.getTransactionId(), k ->
                newOverlay(transaction));
        overlay.ops.add(new Object[]{"I", key, value});
        // Later-wins: стейдженная вставка отменяет предыдущее стейдженное удаление
        // того же (key, value) — иначе merged-чтение показывает пару, которую apply
        // (реплей в порядке ops) корректно удаляет
        final Set<Object> pendingDeletes = overlay.stagedDeletes.get(nkey(key));
        if (pendingDeletes != null) {
            pendingDeletes.remove(value);
        }
        overlay.stagedInserts.computeIfAbsent(nkey(key), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(value);
    }

    private void stageDelete(final IXdStorageTransaction transaction, final Comparable key, final Object value, final
            IXdStorage storage) {
        TX_OVERLAY_STORAGE.putIfAbsent(transaction.getTransactionId(), storage);
        final TreeOverlay overlay = overlays.computeIfAbsent(transaction.getTransactionId(), k ->
                newOverlay(transaction));
        overlay.ops.add(new Object[]{"D", key, value});
        // Later-wins: стейдженное удаление отменяет предыдущую стейдженную вставку
        final List<Object> pendingInserts = overlay.stagedInserts.get(nkey(key));
        if (pendingInserts != null) {
            pendingInserts.remove(value);
        }
        overlay.stagedDeletes.computeIfAbsent(nkey(key), k -> ConcurrentHashMap.newKeySet()).add(value);
    }

    /**
     * Merge стейджа TX в физический результат find: стейдженные вставки добавляются,
     * стейдженные удаления исключаются. Чужие overlay невидимы (READ-COMMITTED).
     */
    private List<Object> mergeOverlay(final List<Object> physical, final Comparable key, final IXdStorageTransaction
            transaction) {
        if (transaction == null || !isOverlayMode()) {
            return physical;
        }
        final TreeOverlay overlay = overlays.get(transaction.getTransactionId());
        if (overlay == null) {
            return physical;
        }
        final List<Object> merged = new ArrayList<>(physical);
        final Set<Object> deleted = overlay.stagedDeletes.get(nkey(key));
        if (deleted != null && !deleted.isEmpty()) {
            merged.removeIf(value -> deleted.contains(value));
        }
        final List<Object> inserted = overlay.stagedInserts.get(nkey(key));
        if (inserted != null) {
            for (final Object value : inserted) {
                if (!merged.contains(value)) {
                    merged.add(value);
                }
            }
        }
        return merged;
    }

    /**
     * Применяет стейдженные операции TX к физическому дереву (вызывается из phase-1
     * ресурса-владельца, ВНУТРИ INDEX_RESOURCE_ORDER-окна). Пакет берет tree write-latch
     * целиком (конкурентные применения сериализуются); каждая операция идет через
     * физический путь, undo-log однооперационный: undo(insert)=delete, undo(delete)=insert.
     * Отказ середины пакета: undo в обратном порядке + rethrow (TX откатывается целиком).
     * "is exists"/"does not exists" поглощаются идемпотентно.
     *
     * @param transaction транзакция-владелец overlay-пакета
     * @param storage     хранилище, в которое пишут infra-ресурсы при применении
     * @throws XdStorageException           отказ пакета (после undo-повтора; TX откатывается)
     * @throws XdStorageConnectionException отказ на уровне соединения
     */
    public void applyOverlays(final IXdStorageTransaction transaction, final IXdStorage storage) throws
            XdStorageException, XdStorageConnectionException {
        if (transaction == null) {
            return;
        }
        final TreeOverlay overlay = overlays.remove(transaction.getTransactionId());
        if (overlay != null && !overlay.ops.isEmpty()) {
            final StringBuilder ks = new StringBuilder();
            for (final Object[] op : overlay.ops) {
                ks.append(op[0]).append(':').append(op[1]).append(';');
            }
        }
        if (overlay == null || overlay.ops.isEmpty()) {
            return;
        }
        final List<Object[]> undo = new ArrayList<>();
        // ВАЖНО: БЕЗ внешнего tree write-latch — каждая физическая операция внутри
        // берет latch сама (latch НЕ реентерантен: внешний захват = самодедлок phase-1,
        // sweeper убивал TX создателя, записи деревьев оставались invisible-forever).
        // Пакетная атомарность обеспечивается undo-логом, а не latch'ем.
        try {
            for (final Object[] op : overlay.ops) {
                final String type = (String) op[0];
                final Comparable key = (Comparable) op[1];
                final Comparable value = (Comparable) op[2];
                try {
                    if ("I".equals(type)) {
                        doInsert(key, value, storage, transaction);
                        undo.add(new Object[]{"D", key, value});
                    } else {
                        doDeleteTyped(key, value, storage, transaction);
                        undo.add(new Object[]{"I", key, value});
                    }
                } catch (final XdStorageException absorbable) {
                    final String m = String.valueOf(absorbable.getMessage());
                    if (m.contains("is exists") || m.contains("already exists") || m.contains("does not exists")) {
                        continue;
                    }
                    try {
                        undoBatch(undo, storage, transaction);
                    } catch (final Exception suppressed) {
                        absorbable.addSuppressed(suppressed);
                    }
                    throw absorbable;
                }
            }
            // Батч применен полностью: undo-журнал переезжает в ожидание разрешения TX
            // (коммит-успех → drop; rollback → undo-replay). До этого момента физические
            // изменения дерева обратимы.
            final java.util.Deque<Object[]> applied = APPLIED_UNDOS.computeIfAbsent(
                    transaction.getTransactionId(), k -> new java.util.ArrayDeque<>());
            for (int i = undo.size() - 1; i >= 0; i--) {
                final Object[] u = undo.get(i);
                applied.addFirst(new Object[]{this, u[0], u[1], u[2]});
            }

            // W2REOPEN FIX (task 53): стейджим ЗАГОЛОВОК дерева (root/firstLeaf) — apply
            // мутировал структуру (сплиты), но tree-запись стейджилась ТОЛЬКО при создании
            // (storage.save(tree) — единственный сайт!) → tree-файл = STALE-заголовок,
            // node-файлы = новая структура → рассинхрон на reopen (root=19 vs 26-листовая
            // цепь). update (не save — запись уже существует, save-as-upsert trap). Откат
            // транзакции откатывает и этот стейдж через общий cache.rollback ✓.
            try {
                storage.update(this, transaction);
            } catch (final Exception headerStageFailure) {
                final String hm = String.valueOf(headerStageFailure.getMessage());
                if (!hm.contains("does not exists") && !hm.contains("is exists")) {
                    throw headerStageFailure;
                }
            }
        } catch (final XdStorageConnectionException connectionFailure) {
            try {
                undoBatch(undo, storage, transaction);
            } catch (final Exception suppressed) {
                connectionFailure.addSuppressed(suppressed);
            }
            throw connectionFailure;
        }
    }

    private void undoBatch(final List<Object[]> undo, final IXdStorage storage, final IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        for (int i = undo.size() - 1; i >= 0; i--) {
            final Object[] u = undo.get(i);
            try {
                if ("D".equals(u[0])) {
                    doDeleteTyped((Comparable) u[1], (Comparable) u[2], storage, transaction);
                } else {
                    doInsert((Comparable) u[1], u[2], storage, transaction);
                }
            } catch (final XdStorageException ignored) {
                // undo идемпотентен: "is exists"/"does not exists" — целевое состояние уже достигнуто
            }
        }
    }

    /**
     * Применяет overlay ВСЕХ деревьев, тронутых TX (вызов из phase-1 владельца).
     * Работает по реестру инстансов — БЕЗ storage.load: инфра-кэш READ COMMITTED,
     * и load записи дерева в фазе-1 ждёт видимости собственного применения (3s-столлы).
     *
     * @param transaction транзакция-владелец overlay-пакетов
     * @param storage     хранилище, фиксируемое для последующего apply по реестру
     * @throws XdStorageException           отказ применения (после undo-повтора)
     * @throws XdStorageConnectionException отказ на уровне соединения
     */
    public static void applyAllOverlays(final IXdStorageTransaction transaction, final IXdStorage storage) throws
            XdStorageException, XdStorageConnectionException {
        TX_OVERLAY_STORAGE.putIfAbsent(transaction.getTransactionId(), storage);
        applyAllOverlays(transaction);
    }

    /**
     * Применение на коммите (вызов из XdStorageTransaction.commitInternal ДО drain'а
     * очереди ресурсов): применение регистрирует infra-ресурсы в TX через мутатор,
     * поэтому обязано случиться ДО acquisition-цикла — иначе зарегистрированные
     * посреди коммита ресурсы не войдут в фазы и их файлы не запишутся.
     *
     * @param transaction транзакция-владелец overlay-пакетов
     * @throws XdStorageException           отказ применения (после undo-повтора)
     * @throws XdStorageConnectionException отказ на уровне соединения
     */
    public static void applyAllOverlays(final IXdStorageTransaction transaction) throws XdStorageException,
            XdStorageConnectionException {
        if (transaction == null) {
            return;
        }
        final Set<XdStorageBTree> trees = TX_OVERLAY_TREES.remove(transaction.getTransactionId());
        final IXdStorage storage = TX_OVERLAY_STORAGE.remove(transaction.getTransactionId());
        if (trees == null || storage == null) {
            return;
        }
        for (final XdStorageBTree tree : trees) {
            tree.applyOverlays(transaction, storage);
        }
    }

    /**
     * Откат TX: overlay всех тронутых деревьев отбрасывается (без storage.load).
     *
     * @param transaction транзакция, чьи overlay-пакеты отбрасываются
     */
    public static void discardAllOverlays(final IXdStorageTransaction transaction) {
        if (transaction == null) {
            return;
        }
        final Set<XdStorageBTree> trees = TX_OVERLAY_TREES.remove(transaction.getTransactionId());
        TX_OVERLAY_STORAGE.remove(transaction.getTransactionId());
        if (trees == null) {
            return;
        }
        for (final XdStorageBTree tree : trees) {
            tree.discardOverlays(transaction);
        }
    }

    /**
     * Откат TX после УСПЕШНОГО apply: undo-replay примененных операций в обратном
     * порядке (возврат удаленных записей id-индексов/FK-деревьев, снятие вставленных).
     * Идемпотентен по "is exists"/"does not exists"; закрывает дыру атомарности
     * apply→rollback (sweeper-убийство TX после применения пакета).
     *
     * @param transaction транзакция, чьи применённые операции откатываются
     */
    public static void undoAllAppliedOverlays(final IXdStorageTransaction transaction) {
        if (transaction == null) {
            return;
        }
        final java.util.Deque<Object[]> applied = APPLIED_UNDOS.remove(transaction.getTransactionId());
        if (applied == null) {
            return;
        }
        final IXdStorage storage = TX_OVERLAY_STORAGE.get(transaction.getTransactionId());
        if (storage == null) {
            return;
        }
        final java.util.Iterator<Object[]> desc = applied.descendingIterator();
        while (desc.hasNext()) {
            final Object[] entry = desc.next();
            final XdStorageBTree tree = (XdStorageBTree) entry[0];
            final String type = (String) entry[1];
            final Comparable key = (Comparable) entry[2];
            final Comparable value = (Comparable) entry[3];
            try {
                if ("D".equals(type)) {
                    tree.doInsert(key, value, storage, transaction);
                } else {
                    tree.doDeleteTyped(key, value, storage, transaction);
                }
            } catch (final Exception ignored) {
                // undo идемпотентен: целевое состояние уже достигнуто
            }
        }
    }

    /**
     * Коммит-успех: применённые изменения стали коммитом — undo-журнал снят.
     *
     * @param transaction транзакция, чей undo-журнал снимается
     */
    public static void dropAppliedUndos(final IXdStorageTransaction transaction) {
        if (transaction != null) {
            APPLIED_UNDOS.remove(transaction.getTransactionId());
        }
    }

    /**
     * Откат TX: overlay отбрасывается целиком (физическое дерево не мутировалось).
     *
     * @param transaction транзакция, чей overlay отбрасывается
     */
    public void discardOverlays(final IXdStorageTransaction transaction) {
        if (transaction == null) {
            return;
        }
        overlays.remove(transaction.getTransactionId());
    }

    /**
     * R-56 support for the invariant validator: the structure-mutation version grows on
     * every structural mutation, so an unchanged version across two traversals certifies
     * that the observation was atomic with respect to structure changes.
     *
     * @return the current structure-mutation version counter
     */
    public long currentStructureVersion() {
        return structureVersion.get();
    }

    /**
     * R-56 support for the invariant validator: a nonzero count means a mutation is in
     * flight and no structural observation may be trusted.
     *
     * @return the number of structural mutations currently in flight
     */
    public int activeMutationCount() {
        return activeMutations.get();
    }

    /** R-56: снимок активных мутаторов для диагностики сигналов валидатора.
     *
     * @return a diagnostic dump of the in-flight mutators (thread to operation description)
     */
    public String describeActiveMutators() {
        return activeMutatorThreads.toString();
    }

    // БЮДЖЕТ ПОПЫТОК РЕБАЛАНСИРОВКИ: retry-циклы операций обязаны быть ограничены —
    // иначе устойчивая конкуренция за deep-локи превращает их в вечный livelock,
    // вешающий потоки (дефект зависания cleanShutdown в merge-стрессе).
    private static final int REBALANCE_RETRY_BUDGET = 100;

    /**
     * Reports the calling thread's hold of the tree-level write lock.
     *
     * @return {@code true} when the calling thread holds the tree write lock
     */
    @Override
    public boolean isWriteLocked() {
        return lock.isWriteLocked();
    }

    // R-43: сериализация дерева (root/firstLeaf) — tryLock-версия, см. ноду.

    /**
     * R-43 serialization guard: takes the tree write lock non-blockingly so a disk
     * serializer snapshots the tree header (root/firstLeaf) consistently. Best-effort —
     * when the lock is busy the serializer proceeds unlocked and relies on the
     * collection-monitor snapshots. Paired with {@link #endConsistentSerialization()}.
     */
    @Override
    public void beginConsistentSerialization() {
        tryLockWrite(null);
    }

    /**
     * R-43 serialization guard: releases the tree write lock taken by
     * {@link #beginConsistentSerialization()} (no-op when it was not captured).
     */
    @Override
    public void endConsistentSerialization() {
        if (isWriteLocked()) {
            unlockWrite();
        }
    }

    /**
     * Reports the calling thread's hold of the tree-level read lock.
     *
     * @return {@code true} when the calling thread holds the tree read lock
     */
    @Override
    public boolean isReadLocked() {
        return lock.isReadLocked();
    }

    /**
     * Acquires the tree-level write lock, blocking (with polling) until success or the
     * transaction's lock timeout expires. Mutators hold it around root/firstLeaf
     * materialization; the rebalance descent re-acquires it per attempt.
     *
     * @param transaction the owning transaction providing the lock timeout ({@code null} — engine default)
     * @throws XdStorageException when the acquiring thread is interrupted while waiting
     */
    /**
     * RECIDENT/R-36 (docs/tasks/2.1.0/47): the tree-level ENTRY write lock with the
     * bounded contention retry. The entry lock is the only clean retry boundary (no
     * in-place mutation exists before it); the MVCC write-lock contention bursts of the
     * parallel workers are absorbed with a short backoff. Failures after the entry (the
     * descent) mutate in place and are never retried.
     */
    private void lockWriteWithContentionRetry(final IXdStorageTransaction transaction) throws XdStorageException {
        for (int attempt = 1; ; ++attempt) {
            try {
                lockWrite(transaction);
                return;
            } catch (final XdStorageException | XdStorageRuntimeException contention) {
                final String message = contention.getMessage() != null ? contention.getMessage()
                        : (contention.getCause() != null && contention.getCause().getMessage() != null
                           ? contention.getCause().getMessage() : "");
                final boolean transientContention = message.contains("Превышен таймаут ожидания")
                        || message.contains("Ресурс монопольно занят на запись");
                if (attempt >= 3 || !transientContention) {
                    throw contention;
                }
                try {
                    Thread.sleep(20L * attempt);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw contention;
                }
            }
        }
    }

    public void lockWrite(final IXdStorageTransaction transaction) throws XdStorageException {
        try {
            lock.lockWrite(transaction);
        } catch (final InterruptedException e) {
            throw new XdStorageException(e);
        }
    }

    /**
     * Acquires the tree-level read lock, blocking (with polling) until success or the
     * transaction's lock timeout expires. On a search/scan the descent releases it at the
     * lock-coupling point — do not release it externally afterwards.
     *
     * @param transaction the owning transaction providing the lock timeout ({@code null} — engine default)
     * @throws XdStorageException when the acquiring thread is interrupted while waiting
     */
    @Override
    public void lockRead(final IXdStorageTransaction transaction) throws XdStorageException {
        try {
            lock.lockRead(transaction);
        } catch (final InterruptedException e) {
            throw new XdStorageException(e);
        }
    }

    /**
     * Attempts to capture the tree write lock without waiting for other holders.
     *
     * @param transaction the owning transaction (part of the interface contract; not used by the try-variant)
     * @return {@code true} when the lock was captured
     */
    @Override
    public boolean tryLockWrite(final IXdStorageTransaction transaction) {
        return lock.tryLockWrite();
    }

    /**
     * Attempts to capture the tree read lock without waiting for other holders.
     *
     * @param transaction the owning transaction (part of the interface contract; not used by the try-variant)
     * @return {@code true} when the lock was captured
     */
    @Override
    public boolean tryLockRead(final IXdStorageTransaction transaction) {
        return lock.tryLockRead();
    }

    /**
     * Releases one write-lock hold of the calling thread on the tree lock.
     */
    @Override
    public void unlockWrite() {
        lock.unlockWrite();
    }

    /**
     * Releases one read-lock hold of the calling thread on the tree lock.
     */
    @Override
    public void unlockRead() {
        lock.unlockRead();
    }

    /**
     * Returns the tree's persistent identity — the indexed class plus the index name.
     *
     * @return the tree's persistent identity (indexed class + index name)
     */
    public XdStorageBTreeId getId() {
        return id;
    }

    /**
     * Sets the tree's persistent identity (used by the parser on materialization).
     *
     * @param id the persistent identity to set
     */
    public void setId(final XdStorageBTreeId id) {
        this.id = id;
    }

    /**
     * Reports whether this tree is still an unloaded parser stub.
     *
     * @return {@code true} while this instance is an unloaded parser stub that must be
     *         materialized from storage before use
     */
    public boolean isReference() {
        return isReference;
    }

    /**
     * Sets the parser-stub marker (cleared by the parser when the tree is materialized).
     *
     * @param reference the parser-stub marker to set ({@code false} for a live instance)
     */
    public void setReference(boolean reference) {
        isReference = reference;
    }

    /**
     * Reports the tree's uniqueness mode.
     *
     * @return {@code true} for a duplicate-bearing (search) index, {@code false} for a unique index
     */
    public boolean isMultiple() {
        return multiple;
    }

    /**
     * Sets the tree's uniqueness mode.
     *
     * @param multiple the uniqueness mode to set ({@code true} — duplicates allowed)
     */
    public void setMultiple(boolean multiple) {
        this.multiple = multiple;
    }

    /**
     * Returns the tree's fill factor (minimum degree).
     *
     * @return the fill factor (minimum degree) of the tree
     */
    public int getT() {
        return t;
    }

    /**
     * Sets the tree's fill factor (minimum degree).
     *
     * @param t the fill factor to set; a settled node holds between {@code t-1} and {@code 2t-1} keys
     * @throws XdStorageRuntimeException when {@code t} is less than or equal to 1
     */
    public void setT(final int t) {
        if (t <= 1) {
            throw new XdStorageRuntimeException("Parameter t of BTree cannot be less than or equal to 1");
        }
        this.t = t;
    }

    /**
     * Returns the current root node of the tree.
     *
     * @return the current root node ({@code null} for an empty, never-initialized tree)
     */
    public XdStorageBTreeNode getRoot() {
        return root;
    }

    /**
     * Sets the root node of the tree.
     *
     * @param root the root node to set
     */
    public void setRoot(final XdStorageBTreeNode root) {
        // TREEFILE (docs/tasks/2.1.0/41): track "this tree was emptied by its own
        // collapse" — the one case where a root==null refresh must NEVER adopt a root
        // from storage (the file lags the collapse; adopting resurrects pre-collapse
        // keys and commits a stale structure — the log-proven root=null→1 leak vector).
        this.collapsedEmpty = root == null;
        this.root = root;
    }

    /**
     * Returns the head of the ordered leaf chain — the entry point of range walks.
     *
     * @return the first (leftmost) leaf of the ordered leaf chain, {@code null} when the tree is empty
     */
    public XdStorageBTreeNode getFirstLeaf() {
        return firstLeaf;
    }

    /**
     * Sets the head of the ordered leaf chain.
     *
     * @param firstLeaf the first-leaf pointer to set
     */
    public void setFirstLeaf(final XdStorageBTreeNode firstLeaf) {
        this.firstLeaf = firstLeaf;
        bumpStructureEpoch();
    }

    /**
     * Inserts the record under the key, creating the root on the first insert. Parser stubs
     * of root/firstLeaf are materialized under the tree write lock first; the descent itself
     * runs under lock coupling. On sustained rebalance-lock contention the descent is retried
     * within a bounded attempt budget (with backoff) and fails loudly when the budget is
     * exhausted. A duplicate key fails loudly on a unique ({@code multiple == false}) tree.
     *
     * @param key         the insertion key (Comparable contract of the indexed field's type)
     * @param object      the record to store under the key
     * @param storage     the storage facade used to persist the tree and its nodes
     * @param transaction the owning transaction
     * @throws XdStorageException           when the thread is interrupted, the rebalance retry
     *                                      budget is exhausted, or a unique tree already
     *                                      contains the key
     * @throws XdStorageConnectionException when persisting a node or the tree fails
     */
    public void insert(final Comparable key, final Object object,
                       final IXdStorage storage, final IXdStorageTransaction transaction) throws XdStorageException,
                               XdStorageConnectionException {
        // ИСПРАВЛЕНИЕ: Fail-fast проверка прерывания потока на входе в СУБД
        if (Thread.currentThread().isInterrupted()) {
            throw new XdStorageException("interrupted", new
                    InterruptedException("Поток прерван перед вставкой в B+ Дерево"));
        }
        // TREEOVERLAY: overlay-деревья стейджат операцию (физическое дерево не мутируется
        // до applyOverlays в phase-1 владельца); ноль лока и ноль change-слотов на op-time.
        if (isOverlayMode() && transaction != null) {
            stageInsert(transaction, key, object, storage);
            return;
        }
        doInsert(key, object, storage, transaction);
    }

    private void doInsert(final Comparable key, final Object object,
                          final IXdStorage storage, final IXdStorageTransaction transaction) throws XdStorageException,
                                  XdStorageConnectionException {
        // ИСПРАВЛЕНИЕ: Fail-fast проверка прерывания потока на входе в СУБД
        if (Thread.currentThread().isInterrupted()) {
            throw new XdStorageException("interrupted", new
                    InterruptedException("Поток прерван перед вставкой в B+ Дерево"));
        }

        // RECIDENT/R-36 (docs/tasks/2.1.0/47): the tree-level ENTRY lock is the only clean
        // retry boundary — before it no in-place mutation exists, so a lock-timeout burst
        // under parallel workers can be absorbed with a short bounded retry. Failures
        // AFTER the entry (the descent) mutate in place and are never retried here.
        lockWriteWithContentionRetry(transaction);
        try {
            if (root == null) {
                createRoot(storage, transaction);
            }

            // R-53: парсерный стаб корня (холодное открытие) заменяется каноническим
            // инстансом — иначе спуск идет по пустому узлу и вставки после реопена
            // портят структуру (PERFORMANCE-RECOMMENDATIONS.md §6);
            // firstLeaf — тоже: цепочка листьев нужна валидатору и сканам
            root = this.root = XdStorageBTreeNode.materialize(root, storage, transaction, this, null);
            if (firstLeaf != null) {
                firstLeaf = this.firstLeaf = XdStorageBTreeNode.materialize(firstLeaf, storage, transaction, this,
                        null);
            }
        } finally {
            unlockWrite();
        }

        final AtomicBoolean retryInsert = new AtomicBoolean();
        // OVERFLOW (docs/tasks/2.2.0/33): корректные вставки дубликатов теперь СПЛЯТ
        // терминальные листья chain-диапазона, и параллельный шторм на горячих ключах
        // выжигал ПОПЫТОЧНЫЙ бюджет тем же способом, каким R-54 выжигал его у поисков:
        // каждый retry заново захватывает write-лок дерева и проигрывает следующему
        // держателю глубокого лока. Паттерн R-54 портирован сюда: ДЕДЛАЙН (3/4 таймаута
        // транзакции, минимум 500 мс, потолок 10 с) + экспоненциальный backoff — ожидание
        // ограничено ВРЕМЕНЕМ: шторму — терпение, вечному клину — громкий отказ.
        final long txTimeoutMs = transaction != null ? transaction.getTimeout() : 100L;
        final long insertBudgetMs = Math.min(Math.max(txTimeoutMs * 3 / 4, 500L), FIND_RETRY_BUDGET_CAP_MS);
        final long insertDeadlineNanos = System.nanoTime() + insertBudgetMs * 1_000_000L;
        long backoffMillis = 1L;
        do {
            retryInsert.set(false);
            lockWrite(transaction);
            try {
                if (root == null) {
                    createRoot(storage, transaction);
                }
                // R-56: счетчики ТОЛЬКО внутри lockWrite-секции — иначе они флагают
                // ждущих мутаторов (версия растет у заблокированного на входе воркера),
                // а не структурные изменения.
                structureVersion.incrementAndGet();
                activeMutations.incrementAndGet();
                activeMutatorThreads.put(Thread.currentThread(), "insert");
                try {
                    root.insert(this, key, object, storage, transaction, retryInsert);
                } finally {
                    activeMutatorThreads.remove(Thread.currentThread());
                    activeMutations.decrementAndGet();
                }
            } finally {
                // R-33: страховка утечки write-лока при throw из write-секции. deepUnlock
                // внутри node-операций уже снял глубокую цепь (включая tree); разблокируем
                // только если loop-уровневый захват еще держится текущим потоком.
                if (isWriteLocked()) {
                    unlockWrite();
                }
            }
            if (retryInsert.get()) {
                if (System.nanoTime() - insertDeadlineNanos >= 0) {
                    log.warn("B+Tree insert retry budget exhausted for key {} in tree {} — sustained rebalance lock " +
                            "contention (operation failed loudly)", key,
                            id);
                    throw new XdStorageException("retry budget exhausted by insert of idgeneration " + key
                            + " into " + id + ": устойчивая конкуренция за блокировки ребалансировки");
                }

                // R-42/R-54: экспоненциальный backoff — без паузы ретраящий молотит
                // tryLock, мешая сплитам/джойнам завершиться; растущая пауза дает
                // мутаторам окно и создает safepoint для диагностики.
                try {
                    Thread.sleep(backoffMillis);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new XdStorageException("interrupted during insert retry backoff", e);
                }
                backoffMillis = Math.min(backoffMillis * 2, FIND_RETRY_BACKOFF_MAX_MILLIS);
            }
        } while (retryInsert.get());
    }

    private void createRoot(final IXdStorage storage, final IXdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException {
        log.debug("B+Tree {} created root (first key stored)", id);
        firstLeaf = root = new XdStorageBTreeNode(this, null);
        this.wasInitialized = true; // Фиксируем факт рождения структуры Б+ Дерева
        storage.save(root, transaction);
        storage.update(this, transaction);
    }

    /**
     * Removes the record stored under the key and rebalances the tree on underflow
     * (borrow from a sibling, merge, parent recursion, root collapse; an emptied root also
     * clears the root and first-leaf pointers). Deleting from a never-initialized
     * non-reference tree fails loudly; a no-op otherwise when the tree is empty. Sustained
     * rebalance contention is retried within a bounded budget, then fails loudly.
     *
     * @param key         the key to remove
     * @param storage     the storage facade used to persist the tree and its nodes
     * @param transaction the owning transaction
     * @throws XdStorageException           when the thread is interrupted, the key is not
     *                                      present, or the rebalance retry budget is exhausted
     * @throws XdStorageConnectionException when persisting a node or the tree fails
     */
    public void delete(final Comparable key, final IXdStorage storage, final IXdStorageTransaction transaction) throws
            XdStorageException, XdStorageConnectionException {
        if (Thread.currentThread().isInterrupted()) {
            throw new XdStorageException("interrupted", new
                    InterruptedException("Поток прерван перед удалением из B+ Дерева"));
        }
        // TREEOVERLAY: стейджим удаление; значение фиксируем на этапе стейджа — оно нужно
        // для undo(delete) при откате применения пакета. Чужие overlay при чтении невидимы.
        if (isOverlayMode() && transaction != null) {
            final List<Object> current = findPhysical(key, storage, transaction);
            final Object knownValue = current.isEmpty() ? null : current.get(0);
            stageDelete(transaction, key, knownValue, storage);
            return;
        }
        doDelete3(key, storage, transaction);
    }

    /** TREEOVERLAY: физический путь применения/undo — маршрутизация по типу дерева. */
    private void doDeleteTyped(final Comparable key, final Comparable value, final IXdStorage storage, final
            IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        if (this.multiple) {
            doDeleteMulti(key, value, storage, transaction);
        } else {
            doDelete3(key, storage, transaction);
        }
    }

    private void doDelete3(final Comparable key, final IXdStorage storage, final IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        if (Thread.currentThread().isInterrupted()) {
            throw new XdStorageException("interrupted", new
                    InterruptedException("Поток прерван перед удалением из B+ Дерева"));
        }

        lockWrite(transaction);
        try {
            // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ СУБД (Корень дефекта 65 / 88):
            // Если поле root вернуло null, но маркер wasInitialized равен true, это означает,
            // что старый родитель был удален каскадным сжатием ярусов join, а новый корень
            // находится в транзакционном кэше изменений сессии.
            // Перегружаем состояние bTree из storage, чтобы восстановить живую ссылку на root!
            if (root == null && wasInitialized) {
                XdStorageBTree freshTree = storage.load(XdStorageBTree.class, this.id, transaction);
                // TREEFILE: after a self-collapse the tree is genuinely empty — the file
                // (and any parse of it) lags the collapse; adoption would resurrect keys.
                if (collapsedEmpty) {
                    freshTree = null;
                }
                if (freshTree != null && freshTree.getRoot() != null) {
                    this.root = freshTree.getRoot();
                    this.firstLeaf = freshTree.getFirstLeaf();
                }
            }

            if (root != null) {
                // R-53: парсерный стаб корня (холодное открытие) заменяется каноническим
                // инстансом — удаления после реопена обязаны идти по живому графу;
                // firstLeaf — тоже (цепочка листьев: валидатор, слияния листьев)
                root = this.root = XdStorageBTreeNode.materialize(root, storage, transaction, this, null);
                if (firstLeaf != null) {
                    firstLeaf = this.firstLeaf = XdStorageBTreeNode.materialize(firstLeaf, storage, transaction, this,
                            null);
                }
            } else {
                if (!wasInitialized && !isReference) {
                    throw new XdStorageException("object of " + (id != null ? id.getCl().getName() : "Unknown") +
                            " with id " + key + " does not exists");
                }
                return;
            }
        } finally {
            unlockWrite();
        }

        final AtomicLong counter = new AtomicLong(0);
        final AtomicBoolean retryDelete = new AtomicBoolean();
        int attempts = 0;
        do {
            synchronized (counter) {
                if (counter.incrementAndGet() % 5 == 0) {
                    try {
                        counter.wait(50);
                    } catch (InterruptedException e) {
                        throw new XdStorageException("interrupted", e);
                    }
                }
            }
            if (++attempts > REBALANCE_RETRY_BUDGET) {
                log.warn("B+Tree rebalance retry budget exhausted for delete of key {} in tree {} — sustained " +
                        "rebalance lock contention (operation failed loudly)", key,
                        id);
                throw new XdStorageException("retry budget exhausted by delete of idgeneration " + key
                        + " from " + id + ": устойчивая конкуренция за блокировки ребалансировки");
            }

            retryDelete.set(false);
            lockWrite(transaction);
            try {
                // Защита от фантомного зануления внутриdo-while цикла
                if (root == null && wasInitialized) {
                    XdStorageBTree freshTree = storage.load(XdStorageBTree.class, this.id, transaction);
                    // TREEFILE: see the delete-path refresh — a self-collapsed empty tree
                    // must not adopt a root from a lagging file parse.
                    if (collapsedEmpty) {
                        freshTree = null;
                    }
                    if (freshTree != null && freshTree.getRoot() != null) {
                        this.root = freshTree.getRoot();
                        this.firstLeaf = freshTree.getFirstLeaf();
                    }
                }

                if (root == null) {
                    log.debug("B+Tree {} is empty after delete of key {} (root purged)", id, key);
                    return;
                }
                structureVersion.incrementAndGet();
                activeMutations.incrementAndGet();
                activeMutatorThreads.put(Thread.currentThread(), "delete");
                try {
                    root.delete(this, key, storage, transaction, retryDelete);
                } finally {
                    activeMutatorThreads.remove(Thread.currentThread());
                    activeMutations.decrementAndGet();
                }
            } finally {
                // R-33: см. комментарий в insert
                if (isWriteLocked()) {
                    unlockWrite();
                }
            }
        } while (retryDelete.get());
    }

    /**
     * Прецизионное удаление конкретной пары Ключ + Значение из B+ Дерева (для MULTI-индексов).
     *
     * @param key         ключ связи (Comparable)
     * @param value       конкретное значение/ссылка (Comparable)
     * @param storage     фасад хранилища для ленивой загрузки нод
     * @param transaction текущая транзакция
     * @throws XdStorageException           при превышении бюджета ретраев или отсутствии ключа
     * @throws XdStorageConnectionException при ошибках ввода-вывода
     */
    public void delete(final Comparable key, final Comparable value, final IXdStorage storage, final
            IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        if (Thread.currentThread().isInterrupted()) {
            throw new XdStorageException("interrupted", new
                    InterruptedException("Поток прерван перед удалением пары из B+ Дерева"));
        }

        // TREEOVERLAY: стейджим удаление пары (значение известно из вызова)
        if (isOverlayMode() && transaction != null) {
            stageDelete(transaction, key, value, storage);
            return;
        }
        doDeleteMulti(key, value, storage, transaction);
    }

    private void doDeleteMulti(final Comparable key, final Comparable value, final IXdStorage storage, final
            IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        if (Thread.currentThread().isInterrupted()) {
            throw new XdStorageException("interrupted", new
                    InterruptedException("Поток прерван перед удалением пары из B+ Дерева"));
        }

        lockWrite(transaction);
        try {
            if (root == null && wasInitialized) {
                XdStorageBTree freshTree = storage.load(XdStorageBTree.class, this.id, transaction);
                if (collapsedEmpty) {
                    freshTree = null;
                }
                if (freshTree != null && freshTree.getRoot() != null) {
                    this.root = freshTree.getRoot();
                    this.firstLeaf = freshTree.getFirstLeaf();
                }
            }

            if (root != null) {
                root = this.root = XdStorageBTreeNode.materialize(root, storage, transaction, this, null);
                if (firstLeaf != null) {
                    firstLeaf = this.firstLeaf = XdStorageBTreeNode.materialize(firstLeaf, storage, transaction, this,
                            null);
                }
            } else {
                if (!wasInitialized && !isReference) {
                    throw new XdStorageException("object with key " + key + " and value " + value +
                            " does not exists in " + id);
                }
                return;
            }
        } finally {
            unlockWrite();
        }

        final AtomicLong counter = new AtomicLong(0);
        final AtomicBoolean retryDelete = new AtomicBoolean();
        int attempts = 0;
        do {
            synchronized (counter) {
                if (counter.incrementAndGet() % 5 == 0) {
                    try {
                        counter.wait(50);
                    } catch (InterruptedException e) {
                        throw new XdStorageException("interrupted", e);
                    }
                }
            }
            if (++attempts > REBALANCE_RETRY_BUDGET) {
                log.warn("B+Tree rebalance retry budget exhausted for targeted delete of key {} in tree {} " +
                        "(operation failed loudly)", key, id);
                throw new XdStorageException("retry budget exhausted by targeted delete of key " + key + " from " + id);
            }

            retryDelete.set(false);
            lockWrite(transaction);
            try {
                if (root == null && wasInitialized) {
                    XdStorageBTree freshTree = storage.load(XdStorageBTree.class, this.id, transaction);
                    if (collapsedEmpty) {
                        freshTree = null;
                    }
                    if (freshTree != null && freshTree.getRoot() != null) {
                        this.root = freshTree.getRoot();
                        this.firstLeaf = freshTree.getFirstLeaf();
                    }
                }

                if (root == null) {
                    return;
                }
                structureVersion.incrementAndGet();
                activeMutations.incrementAndGet();
                activeMutatorThreads.put(Thread.currentThread(), "deletePair");
                try {
                    // R-46 ПРИМЕНЁН К ТОЧЕЧНОМУ УДАЛЕНИЮ: в MULTI-дереве сплиты раскидывают
                    // дубликаты ключа между левой и правой ветвями, поэтому вертикальный
                    // спуск по разделителям этажей не может адресовать конкретную пару
                    // Ключ+Значение: на равенстве ключа с разделителем он обязан выбрать
                    // одну сторону (в findChildAndDeletePair — строго правую), а целевая
                    // пара может лежать в другой. Отсюда фантомные "does not exists" на
                    // сериях дубликатов длиннее 2t-1. Лист с парой ищется каноническим
                    // горизонтальным обходом упорядоченной цепи листьев — зеркально
                    // find()/read(); правый (уникальный) контекст сохраняет спуск.
                    if (multiple) {
                        final XdStorageBTreeNode leafWithPair = locateLeafWithPair(key, value, storage, transaction);
                        if (leafWithPair == null) {
                            throw new XdStorageException("object of " + id + " with key " + key + " and value " + value
                                    + " does not exists");
                        }
                        leafWithPair.delete(this, key, value, storage, transaction, retryDelete);
                    } else {
                        // Вызываем перегруженный целевой метод корня
                        root.delete(this, key, value, storage, transaction, retryDelete);
                    }
                } finally {
                    activeMutatorThreads.remove(Thread.currentThread());
                    activeMutations.decrementAndGet();
                }
            } finally {
                if (isWriteLocked()) {
                    unlockWrite();
                }
            }
        } while (retryDelete.get());
    }

    /**
     * R-46 для точечного удаления (MULTI): находит лист, содержащий конкретную пару
     * Ключ+Значение, каноническим горизонтальным обходом упорядоченной цепи листьев от
     * {@code firstLeaf} — тем же путём, которым дубликаты находит {@code find()}/{@code read()}.
     * Вызывается под write-локом дерева из мутационного цикла {@code delete(key, value, ...)}:
     * структура цепи стабильна (все мутаторы сериализуются на локе дерева), стабы цепи
     * разрешаются в канонические инстансы по ходу, снимки списков берутся под их
     * мониторами (R-43, дисциплина сериализатора). Цепь упорядочена по ключу, поэтому
     * обход завершается досрочно, как только ключ листа ушёл за целевой: дубликаты
     * одного ключа занимают сплошной отрезок цепи.
     *
     * @param key         the key of the pair to locate (route only — duplicates span leaves)
     * @param value       the concrete value/reference of the pair (the discriminator)
     * @param storage     the storage facade used to resolve leaf-chain stubs
     * @param transaction the owning transaction
     * @return the leaf holding the pair, or {@code null} when no leaf contains it
     * @throws XdStorageException           when a chain stub cannot be resolved
     * @throws XdStorageConnectionException when lazily loading a chain stub fails
     */
    private XdStorageBTreeNode locateLeafWithPair(final Comparable key, final Comparable value,
                                                  final IXdStorage storage, final IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        XdStorageBTreeNode previous = null;
        XdStorageBTreeNode current = firstLeaf;
        int steps = 0;
        while (current != null && steps++ < PREWARM_STEP_BUDGET) {
            if (XdStorageObjectUtils.isReference(current)) {
                final XdStorageBTreeNode canonical = XdStorageBTreeNode.materialize(current, storage, transaction, this,
                        null);
                if (previous == null) {
                    this.firstLeaf = canonical;
                } else {
                    previous.setNextTreeNodeOnThisLevel(canonical);
                }
                current = canonical;
            }
            final List<Comparable> keySnapshot;
            final List<Object> objectSnapshot;
            synchronized (current.getKeys()) {
                keySnapshot = new ArrayList<>(current.getKeys());
            }
            synchronized (current.getObjects()) {
                objectSnapshot = new ArrayList<>(current.getObjects());
            }
            boolean passedBeyondKey = false;
            for (int i = 0; i < keySnapshot.size(); ++i) {
                final int cmp = XdStorageBTreeNode.compareMixedKeys(keySnapshot.get(i), key);
                if (cmp == 0) {
                    final Object candidate = objectSnapshot.get(i);
                    if (candidate instanceof Comparable && ((Comparable) candidate).compareTo(value) == 0) {
                        return current;
                    }
                } else if (cmp > 0) {
                    // цепь упорядочена: за целевым ключом этой и последующих пар быть не может
                    passedBeyondKey = true;
                    break;
                }
            }
            if (passedBeyondKey) {
                return null;
            }
            previous = current;
            current = current.getNextTreeNodeOnThisLevel();
        }
        return null;
    }

    /**
     * Replaces the record stored under the key with the new value in place (a leaf-list
     * write, no structural change); a missing key fails loudly. Sustained rebalance
     * contention is retried within a bounded budget, then fails loudly.
     *
     * @param key         the key whose record is replaced
     * @param value       the replacement record
     * @param storage     the storage facade used to persist the affected node
     * @param transaction the owning transaction
     * @throws XdStorageException           when the thread is interrupted, the key is not
     *                                      present, or the rebalance retry budget is exhausted
     * @throws XdStorageConnectionException when persisting the node fails
     */
    public void update(final Comparable key, final Object value, final IXdStorage storage, final IXdStorageTransaction
            transaction) throws XdStorageException, XdStorageConnectionException {
        if (Thread.currentThread().isInterrupted()) {
            throw new XdStorageException("interrupted", new
                    InterruptedException("Поток прерван перед обновлением в B+ Дереве"));
        }

        lockWrite(transaction);
        try {
            // Восстановление живого корня для операции обновления структуры
            if (root == null && wasInitialized) {
                XdStorageBTree freshTree = storage.load(XdStorageBTree.class, this.id, transaction);
                // TREEFILE: after a self-collapse the tree is genuinely empty — the file
                // (and any parse of it) lags the collapse; adoption would resurrect keys.
                if (collapsedEmpty) {
                    freshTree = null;
                }
                if (freshTree != null && freshTree.getRoot() != null) {
                    this.root = freshTree.getRoot();
                    this.firstLeaf = freshTree.getFirstLeaf();
                }
            }

            if (root == null) {
                throw new XdStorageException("object of " + id + " with id " + key + " does not exists");
            }

            // R-53: парсерный стаб корня (холодное открытие) заменяется каноническим инстансом;
            // firstLeaf — тоже (цепочка листьев: валидатор, сканы)
            root = this.root = XdStorageBTreeNode.materialize(root, storage, transaction, this, null);
            if (firstLeaf != null) {
                firstLeaf = this.firstLeaf = XdStorageBTreeNode.materialize(firstLeaf, storage, transaction, this,
                        null);
            }
        } finally {
            unlockWrite();
        }

        final AtomicLong counter = new AtomicLong(0);
        final AtomicBoolean retryUpdate = new AtomicBoolean();
        int attempts = 0;
        do {
            synchronized (counter) {
                if (counter.incrementAndGet() % 5 == 0) {
                    try {
                        counter.wait(50);
                    } catch (InterruptedException e) {
                        throw new XdStorageException("interrupted", e);
                    }
                }
            }
            if (++attempts > REBALANCE_RETRY_BUDGET) {
                log.warn("B+Tree rebalance retry budget exhausted for update of key {} in tree {} — sustained " +
                        "rebalance lock contention (operation failed loudly)", key,
                        id);
                throw new XdStorageException("retry budget exhausted by update of idgeneration " + key
                        + " in " + id + ": устойчивая конкуренция за блокировки ребалансировки");
            }

            retryUpdate.set(false);
            lockWrite(transaction);
            try {
                if (root == null && wasInitialized) {
                    XdStorageBTree freshTree = storage.load(XdStorageBTree.class, this.id, transaction);
                    // TREEFILE: see the delete-path refresh — a self-collapsed empty tree
                    // must not adopt a root from a lagging file parse.
                    if (collapsedEmpty) {
                        freshTree = null;
                    }
                    if (freshTree != null && freshTree.getRoot() != null) {
                        this.root = freshTree.getRoot();
                        this.firstLeaf = freshTree.getFirstLeaf();
                    }
                }

                if (root == null) {
                    throw new XdStorageException("object of " + id + " with id " + key + " does not exists");
                }
                structureVersion.incrementAndGet();
                activeMutations.incrementAndGet();
                activeMutatorThreads.put(Thread.currentThread(), "update");
                try {
                    root.update(this, key, value, storage, transaction, retryUpdate);
                } finally {
                    activeMutatorThreads.remove(Thread.currentThread());
                    activeMutations.decrementAndGet();
                }
            } finally {
                // R-33: см. комментарий в insert
                if (isWriteLocked()) {
                    unlockWrite();
                }
            }
        } while (retryUpdate.get());
    }

    /**
     * Searches the records stored under the key. On a unique tree this is a single vertical
     * descent; on a MULTI tree the whole leaf chain is scanned (no vertical descents — R-46).
     * The search retries under a time budget derived from the transaction timeout with
     * exponential backoff and fails loudly when the budget expires under sustained write
     * pressure. The tree read lock is released by the descent itself (lock coupling).
     *
     * @param key         the key to look up
     * @param storage     the storage facade used to lazily load stub nodes
     * @param transaction the owning transaction
     * @return the records stored under the key — several for a MULTI tree; empty when the key is absent
     * @throws XdStorageException           when the thread is interrupted or the search retry
     *                                      budget expires under sustained contention
     * @throws XdStorageConnectionException when lazily loading a node fails
     */
    public List<Object> find(final Comparable key, final IXdStorage storage, final IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        // TREEOVERLAY: физический результат сливается со стейджем TX (свои вставки видимы,
        // свои удаления исключены; чужие overlay невидимы — READ-COMMITTED)
        return mergeOverlay(findPhysical(key, storage, transaction), key, transaction);
    }

    private List<Object> findPhysical(final Comparable key, final IXdStorage storage, final IXdStorageTransaction
            transaction) throws XdStorageException, XdStorageConnectionException {
        if (root == null && wasInitialized) {
            lockWrite(transaction);
            try {
                if (root == null) {
                    XdStorageBTree freshTree = storage.load(XdStorageBTree.class, this.id, transaction);
                    // TREEFILE: see the delete-path refresh — a self-collapsed empty tree
                    // must not adopt a root from a lagging file parse.
                    if (collapsedEmpty) {
                        freshTree = null;
                    }
                    if (freshTree != null && freshTree.getRoot() != null) {
                        this.root = freshTree.getRoot();
                        this.firstLeaf = freshTree.getFirstLeaf();
                    }
                }
            } finally {
                unlockWrite();
            }
        }

        // =========================================================================
        // FKWRITE (docs/tasks/2.2.0/21, 2026-11-23): прежняя ветка multi-деревьев
        // («КАТЕГОРИЧЕСКИ запрещаем вертикальный спуск — сплиты разрывают дубликаты
        // между ветками») запускала сквозной горизонтальный обход ВСЕЙ цепи листьев
        // от firstLeaf с фильтром == key — O(все пары) НА КАЖДЫЙ поиск. Профиль
        // fk-write-100k (docs/perf/profile-2026-11-23): 46% cascadeSave — эти обходы
        // (registerForeignKey/readByChildObjectId → find → BTreeNode.read +
        // LinkedList.toArray) — источник измеренных 23–160× деградации FK.
        //
        // Звуковой канон точечного поиска по ключу (спуск + цепь):
        // 1) спуск «первый разделитель СТРОГО больше ключа → левый ребенок» приземляется
        //    в САМОЕ ЛЕВОЕ поддерево, которое вообще может держать ключ: у всех левых
        //    поддеревьев разделители < ключа, их ключи ≤ разделителям → < ключа;
        //    дубликаты ЛЕВЕЕ приземления невозможны независимо от того, на какой ветке
        //    оказались дубликаты при сплитах/заимствованиях;
        // 2) цепь листьев отсортирована (инвариант, на котором стоит ранняя остановка
        //    readRange) → все ==key пары идут подряд; проход от приземления с остановкой
        //    на первом ключе > key собирает их все — O(log + d);
        // 3) multi-щит в XdStorageBTreeNode.find (лист с совпадением продолжает проход)
        //    держит переходные формы между мутациями.
        // Итог: multi-поиск идет через тот же спуск, что и уникальный (ниже), O(log + d).
        // =========================================================================

        if (root != null && XdStorageObjectUtils.isReference(root)) {
            // R-38: СТАБ-УЗЕЛ корня грузится ТОЛЬКО под write-локом самого узла.
            // Раньше дисковый IO выполнялся под write-локом ВСЕГО ДЕРЕВА — все читатели и
            // писатели дерева простаивали весь XML-парс, и на холодном кэше первый поиск
            // вымораживал дерево на секунды (таймауты "монопольно занят на запись" у всех воркеров).
            // R-53: стаб корня заменяется каноническим инстансом по id (ФАЗА 3B идентичность) —
            // без этого спуск шел по пустому узлу и все поиски после реопена возвращали пусто.
            final XdStorageBTreeNode stub = root;
            stub.lockWrite(transaction);
            try {
                if (XdStorageObjectUtils.isReference(stub)) {
                    root = this.root = XdStorageBTreeNode.materialize(stub, storage, transaction, this, null);
                }
            } finally {
                stub.unlockWrite();
            }
        }

        List<Object> result;
        final AtomicBoolean retryFind = new AtomicBoolean();
        // R-54: бюджет поисковых ретраев — ПО ВРЕМЕНИ, не по числу попыток. Попытечный
        // бюджет (R-36, 200 попыток) выгорал за десятки миллисекунд под лавиной из 25
        // писателей: каждый retry заново захватывает read-лок дерева (ожидая чужой мутации),
        // спускается и проигрывает гонку следующему deep-lock — читатель-ЧЕСТНЕЦ погибал
        // громким отказом под легитимной нагрузкой. Теперь ретраи ограничены дедлайном
        // (производная от таймаута транзакции) с экспоненциальным backoff: аллокационный
        // шторм R-36 невозможен (паузы ограничивают темп), а окно ожидания перекрывает
        // штормы; вечный клин отсечен дедлайном + штатным таймаутом lockRead.
        final long txTimeoutMs = transaction != null ? transaction.getTimeout() : 100L;
        final long findBudgetMs = Math.min(Math.max(txTimeoutMs * 3 / 4, 500L), FIND_RETRY_BUDGET_CAP_MS);
        final long findDeadlineNanos = System.nanoTime() + findBudgetMs * 1_000_000L;
        long backoffMillis = 1L;
        do {
            retryFind.set(false);
            lockRead(transaction);
            // ВНИМАНИЕ (R-35): read-лок релизится самим node-спуском (lock-coupling —
            // XdStorageBTreeNode.find вызывает tree.unlockRead на точке сцепления).
            // Внешний try/finally здесь дал бы ДВОЙНОЙ unlockRead. Известная дыра:
            // если спуски бросают ДО точки сцепления — read-лок течет (R-35 open).
            if (root == null) {
                unlockRead();
                return Collections.emptyList();
            }
            result = root.find(this, key, storage, transaction, retryFind);

            // R-36/R-54: громкий отказ — ТОЛЬКО когда спуск снова не пробился (retryFind)
            // И дедлайн истек. Успешный спуск возвращает результат независимо от числа
            // ретраев (попытечный бюджет умел ронять УСПЕШНУЮ попытку №201).
            if (retryFind.get()) {
                if (System.nanoTime() - findDeadlineNanos >= 0) {
                    io.github.xdstorage.metrics.XdStorageMetrics.engineEvents().increment(io.github.xdstorage.metrics
                            .XdStorageMetrics.FIND_RETRY_BUDGET_EXHAUSTIONS);
                    log.warn("B+Tree find retry budget exhausted after {} ms for key {} in tree {} — sustained write " +
                            "pressure (operation failed loudly)",
                            findBudgetMs, key, id);
                    throw new XdStorageException("find retry budget exhausted by search of idgeneration " + key
                            + " in " + id + ": устойчивая конкуренция за блокировки ребалансировки");
                }

                // R-42/R-54: экспоненциальный backoff — без паузы читатель молотит
                // tryLockRead в цикле, мешая сплитам/джойнам завершиться; растущая пауза
                // дает мутаторам окно и создает safepoint для диагностики.
                try {
                    Thread.sleep(backoffMillis);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new XdStorageException("interrupted during find retry backoff", e);
                }
                backoffMillis = Math.min(backoffMillis * 2, FIND_RETRY_BACKOFF_MAX_MILLIS);
            }
        } while (retryFind.get());
        return result == null ? Collections.emptyList() : result;
    }

    /**
     * BUG-E (docs/tasks/2.1.0/18): restores every search-index key's {@code value} to the
     * indexed class's PRIMARY index field type and its {@code objectId} to the entity's
     * id-field type — after a reopen the generic YAML reader has them as raw Strings (the
     * key fields are declared Object), and the first typed seek crashes with a CCE. Runs
     * ONCE per tree instance (CAS-guarded; O(N) one-time per open), delegating the walk to
     * the first leaf (same lock discipline as {@link #read}).
     * @param keyRestorer maps each raw key/record to its typed form (identity when already typed)
     * @param storage     the storage facade used to resolve leaf-chain stubs
     * @param transaction the owning transaction
     * @throws XdStorageException           when a lock acquisition is interrupted or the walk fails
     * @throws XdStorageConnectionException when lazily loading a leaf-chain stub fails
     */
    public void restoreSearchKeyTypes(final java.util.function.UnaryOperator<Object> keyRestorer,
                                      final IXdStorage storage, final IXdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {
        if (searchKeysTypeRestored.getAndSet(true)) {
            return;
        }
        lockWrite(transaction);
        XdStorageBTreeNode chainStart;
        try {
            chainStart = firstLeaf;
        } finally {
            unlockWrite();
        }
        if (chainStart != null) {
            chainStart = prewarmLeafChain(chainStart, storage, transaction);
        }
        // the walk's lock-coupling contract (mirror of read()): the TREE read lock is held
        // on entry and released by the walk itself after the first node lock is taken
        lockRead(transaction);
        if (chainStart == null) {
            unlockRead();
            return;
        }
        chainStart.restoreSearchKeyTypes(this, storage, transaction, keyRestorer);
    }

    /**
     * Feeds every entry of the tree to the viewer in ascending key order by walking the
     * ordered leaf chain from the first leaf. The chain's parser stubs are prewarmed first
     * (disk IO under per-node write locks — never under the tree lock, R-38); the walk then
     * proceeds under per-node read locks with lock coupling and releases the tree read lock
     * at the first node lock. Each leaf's keys/records are snapshotted under the list
     * monitors before the viewer sees them.
     *
     * @param storage     the storage facade used to resolve leaf-chain stubs
     * @param transaction the owning transaction
     * @param viewer      the entry sink; may end the walk early by raising the
     *                    {@code walkTerminated()} signal (see {@link #readRange})
     * @throws XdStorageException           when a lock acquisition is interrupted or the walk fails
     * @throws XdStorageConnectionException when lazily loading a leaf-chain stub fails
     */
    public void read(final IXdStorage storage, final IXdStorageTransaction transaction, final IXdStorageBTreeViewer
            viewer) throws XdStorageException, XdStorageConnectionException {
        // R-38: ПРЕДПРОГРЕВ ЦЕПИ ЛИСТЬЕВ. На холодном кэше связный обход под read-локами
        // делал синхронный storage.load каждого стаба-узла (XML-парс с диска) ВНУТРИ
        // удерживаемых блокировок — писатели вымораживались на таймаутах (R-37: детерминированный
        // ноль прогрессов в nested-стрессе). Поэтому сначала разрешаем все стабы цепи,
        // держа на время дискового IO только write-лок самого загружаемого узла.
        lockWrite(transaction);
        XdStorageBTreeNode chainStart;
        try {
            chainStart = firstLeaf;
        } finally {
            unlockWrite();
        }
        if (chainStart != null) {
            // R-53: предпрогрев разрешает стабы цепи (включая первый лист) в канонические
            // инстансы и возвращает разрешенное начало цепи для обхода
            final Runnable prewarmGateHook = prewarmGate;
            if (prewarmGateHook != null) {
                prewarmGateHook.run(); // R56SCAN: the split-in-prewarm-window repro seam
            }
            chainStart = prewarmLeafChain(chainStart, storage, transaction);
        }

        // R56SCAN (task 23): the walk is latch-coupled per leaf, but NOT chain-atomic —
        // a concurrent split/merge during the walk moves keys across the chain and the
        // walk returns a mixed pre/post-structure view (the double-emitted or missed
        // keys). The STRUCTURE EPOCH validated after the walk detects it; the RETRY
        // re-walks the fresh chain (the prewarm is warm now — the re-walk is cheap).
        // The resettable viewer contract: a stale walk's output is DISCARDED and the
        // viewer is reset before the re-walk; the callers' collectors implement it.
        // The bounded attempts (3) + the final attempt under the tree read lock (the
        // structure cannot change under it; the chain is prewarmed — no disk IO in the
        // lock, the R-37 writer-starvation does not apply to the no-IO hold).
        for (int attempt = 1; ; ++attempt) {
            final long capturedEpoch = structureEpoch;
            // R56SCAN: lockRead + walk (hand-over-hand releases the tree read latch
            // at the first leaf — NO explicit unlockRead here)
            lockRead(transaction);
            if (chainStart == null) {
                unlockRead();
                return;
            }
            chainStart.read(this, storage, transaction, viewer);
            if (structureEpoch == capturedEpoch) {
                return; // the walk traversed a stable structure — the result is valid
            }
            if (!(viewer instanceof IXdStorageResetableViewer)) {
                return; // the legacy viewer: the streamed result stands
            }
            ((IXdStorageResetableViewer) viewer).resetForReplay();
            if (attempt >= 3) {
                return; // the bounded fallback: accept the last attempt's output
            }
            chainStart = firstLeaf;
            if (chainStart != null) {
                chainStart = prewarmLeafChain(chainStart, storage, transaction);
            }
        }
    }

    /**
     * FP-1a (docs/tasks/2.1.0/09): ORDERED BOUNDED walk of the leaf chain — the range-operator
     * fast path. Reuses read()'s prewarm/lock machinery (R-38/R-53) and feeds the viewer only
     * the entries inside [fromKey, toKey]; the walk TERMINATES as soon as the chain passes the
     * upper bound (the chain is ordered, nothing further can re-enter the range). A null bound
     * is open on that side. No vertical descent: for duplicate-bearing trees (search indexes,
     * multiple=true) the engine deliberately forbids descents (splits reorder duplicates across
     * branches — see the invariant comment in find), so positioning starts at the first leaf;
     * early termination is what makes selective upper-bounded ranges sub-linear.
     *
     * @param fromKey       the lower bound of the range ({@code null} — open lower bound)
     * @param fromInclusive whether {@code fromKey} itself belongs to the range
     * @param toKey         the upper bound of the range ({@code null} — open upper bound)
     * @param toInclusive   whether {@code toKey} itself belongs to the range
     * @param storage       the storage facade used to resolve leaf-chain stubs
     * @param transaction   the owning transaction
     * @param viewer        the entry sink for the entries inside the range; may end the walk
     *                      early by raising {@link #walkTerminated()} (LIMIT pushdown)
     * @throws XdStorageException           when a lock acquisition is interrupted or the walk fails
     * @throws XdStorageConnectionException when lazily loading a leaf-chain stub fails
     */
    public void readRange(final Comparable fromKey, final boolean fromInclusive,
                          final Comparable toKey, final boolean toInclusive,
                          final IXdStorage storage, final IXdStorageTransaction transaction,
                          final IXdStorageBTreeViewer viewer) throws XdStorageException, XdStorageConnectionException {
        try {
            read(storage, transaction, new IXdStorageBTreeViewer() {
                @Override
                public void look(final Comparable key, final Object value) throws XdStorageException,
                        XdStorageConnectionException {
                    if (fromKey != null) {
                        final int cmp = XdStorageBTreeNode.compareMixedKeys(key, fromKey);
                        if (cmp < 0 || (cmp == 0 && !fromInclusive)) {
                            return;
                        }
                    }
                    if (toKey != null) {
                        final int cmp = XdStorageBTreeNode.compareMixedKeys(key, toKey);
                        if (cmp > 0 || (cmp == 0 && !toInclusive)) {
                            throw new RangeWalkAborted();
                        }
                    }
                    viewer.look(key, value);
                }
            });
        } catch (final RangeWalkAborted | WalkTerminated walkFinished) {
            // normal completion of a bounded walk — the node walk releases all its locks on the way out
        }
    }

    /**
     * FP-1b (docs/tasks/2.1.0/10): control-flow signal raised by a viewer that has collected
     * everything it needs — the LIMIT pushdown on the ordered walk (readRange). Treated exactly
     * like {@link RangeWalkAborted}: normal completion, the node walk releases all its locks on
     * the way out; callers of readRange never see it.
     */
    public static final class WalkTerminated extends XdStorageRuntimeException {
        private WalkTerminated() {
            super(null, null, false, false);
        }
    }

    /** FP-1b: a fresh walk-termination signal for a viewer that has collected its page.
     *
     * @return a fresh, stack-less termination signal to raise from a viewer's {@code look}
     */
    public static WalkTerminated walkTerminated() {
        return new WalkTerminated();
    }

    /** Control-flow signal of readRange: the ordered chain walked past the upper bound. */
    private static final class RangeWalkAborted extends XdStorageRuntimeException {
        private RangeWalkAborted() {
            super(null, null, false, false);
        }
    }

    /**
     * R-38: разрешает (догружает с диска) стабы-узлы вдоль цепи листьев ДО захвата read-локов
     * обхода. Идемпотентно: загруженный канонический инстанс больше не является стабом.
     * НЕБЛОКИРУЮЩЕЕ (R-42): стаб, чей write-лок занят чужим читателем/писателем, ПРОПУСКАЕТСЯ —
     * его догрузит штатный locked-обход. Блокирующий lockWrite здесь скрещивался с coupled
     * читателями встречным порядком и давал взаимные 3-секундные зависания (PhantomRead).
     * R-53: стаб разрешается в канонический инстанс по id; слоты firstLeaf/next чинятся
     * на живой канон. Возвращает разрешенное начало цепи (или null, если startLeaf == null).
     */
    private XdStorageBTreeNode prewarmLeafChain(final XdStorageBTreeNode startLeaf, final IXdStorage storage, final
            IXdStorageTransaction transaction) throws XdStorageException, XdStorageConnectionException {
        if (startLeaf == null) {
            return null;
        }
        XdStorageBTreeNode current = startLeaf;
        XdStorageBTreeNode previous = null;
        XdStorageBTreeNode resolvedStart = startLeaf;
        int steps = 0;
        while (current != null && steps++ < PREWARM_STEP_BUDGET) {
            if (XdStorageObjectUtils.isReference(current) && current.tryLockWrite(transaction)) {
                // R-53: лок держим на СТАБЕ (current ниже может переприсвоиться каноном)
                final XdStorageBTreeNode stub = current;
                try {
                    if (XdStorageObjectUtils.isReference(stub)) {
                        final XdStorageBTreeNode materialized =
                                XdStorageBTreeNode.materialize(stub, storage, transaction, this, null);
                        if (materialized != stub) {
                            // R56SCAN (task 23): the chain-slot chink runs OUTSIDE the stub's
                            // write lock (a gated hold must not block the writers through the
                            // held node lock) and as a LOCK-VERIFY-WRITE: the unlocked chink
                            // raced the split's rewire and could ORPHAN the split-out leaf
                            // (the intermittent wrong results, the CI gate 2026-11-23). If the
                            // predecessor's next moved on (a split/collapse rewired it) — the
                            // chink is SKIPPED and the walk follows the LIVE chain.
                            if (previous == null) {
                                if (tryLockWrite(transaction)) {
                                    try {
                                        if (firstLeaf == stub) {
                                            this.firstLeaf = materialized;
                                            resolvedStart = materialized;
                                            current = materialized;
                                        } else {
                                            current = firstLeaf; // the collapse/split handled the slot
                                        }
                                    } finally {
                                        unlockWrite();
                                    }
                                }
                                // the tree lock busy: skip the chink — the locked walk resolves the slot
                            } else {
                                final Runnable chinkGate = prewarmChinkGate;
                                if (chinkGate != null) {
                                    chinkGate.run(); // R56SCAN: the deterministic-repro seam (no locks held)
                                }
                                if (previous.tryLockWrite(transaction)) {
                                    try {
                                        if (previous.getNextTreeNodeOnThisLevel() == stub) {
                                            previous.setNextTreeNodeOnThisLevel(materialized);
                                            current = materialized;
                                        } else {
                                            // the split rewired — follow the live
                                            current = previous.getNextTreeNodeOnThisLevel();
                                        }
                                    } finally {
                                        previous.unlockWrite();
                                    }
                                }
                                // the previous lock busy: skip the chink — the locked walk resolves the slot
                            }
                        }
                    }
                } finally {
                    stub.unlockWrite();
                }
            }
            // Указатель читаем без нодового лока сознательно: гонка со сплитом здесь неопасна —
            // пропущенный стаб догрузит locked-обход; Reference читается атомарно (volatile).
            previous = current;
            current = current.getNextTreeNodeOnThisLevel();
        }
        if (steps >= PREWARM_STEP_BUDGET) {
            log.warn("B+Tree leaf chain prewarm hit the step budget {} in tree {} — possible chain cycle",
                    PREWARM_STEP_BUDGET, id);
        }
        return resolvedStart;
    }
}
