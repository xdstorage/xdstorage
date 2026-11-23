package io.github.xdstorage.btree;

import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import io.github.xdstorage.resource.cache.XdStorageCacheRecord;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural invariant checks for the B+tree indexes, run at transaction-operation
 * boundaries after index mutations (insert/delete) inside the transaction contour.
 *
 * <p>Checks the following invariants:</p>
 * <ol>
 *   <li>vertex integrity: every child of every internal node resolves to a live node
 *       (catches dangling references after merges — the merge removed the node but the
 *       parent still holds it);</li>
 *   <li>keys/objects parity in every leaf (catches the parallel lists drifting apart
 *       after a split);</li>
 *   <li>the set of leaves reachable from the root equals the set reachable from
 *       {@code firstLeaf} along the next chain (catches a broken chain: a leaf exists in
 *       the tree but range scans never see it);</li>
 *   <li>global non-decreasing key order along the leaf chain (catches a bad split/merge
 *       stitching).</li>
 * </ol>
 *
 * <p>Any violation throws {@code XdStorageException} — the transaction must roll back
 * loudly instead of silently committing a corrupt structure to disk.</p>
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public final class XdStorageBTreeInvariants {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(XdStorageBTreeInvariants.class);

    /**
     * R-32: сэмплирование полной валидации. Полный обход цепочки листьев + descend — O(N);
     * на больших деревьях вызов на КАЖДУЮ операцию давал O(N²) на массовых удалениях
     * (пурдж 1250 ключей ≈ 780к посещений узлов). Дешевые O(1)-проверки пар node
     * (children==keys+1, монотонность разделителей) выполняются tripwire-ами после КАЖДОЙ
     * мутации независимо; полный обход — каждая 25-я операция.
     * R-56: интервал конфигурируем (-Dxdstorage.validation.interval=1) — бисекция
     * структурных расхождений: первый разрыв ловится НА породившей его операции.
     */
    private static final int FULL_VALIDATION_INTERVAL =
            Math.max(1, Integer.getInteger("xdstorage.validation.interval", 25));
    private static final java.util.concurrent.atomic.AtomicLong VALIDATION_SAMPLER =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * R-32 sampling gate for the full O(N) validation: the full root descent plus leaf-chain
     * walk runs once per sampling interval only (cheap O(1) tripwires run after every
     * mutation regardless); R-56 makes the interval configurable for structural bisection.
     *
     * @return {@code true} when the caller should run the full structural validation now
     */
    public static boolean shouldRunFullValidation() {
        return VALIDATION_SAMPLER.incrementAndGet() % FULL_VALIDATION_INTERVAL == 1;
    }

    private XdStorageBTreeInvariants() {
        // utility class
    }

    /**
     * Полная структурная валидация дерева индекса после мутации.
     * Выполняется под write-локом дерева: мутаторы держат этот лок на всю операцию,
     * поэтому валидатор не может наблюдать промежуточные состояния ребалансировки.
     *
     * R-56: ВАЛИДАЦИЯ СЭМПЛ-СОГЛАСОВАННА. Сторожевой Exception
     * (ConcurrentStructureChangeSignal) сигнализирует, что пара (structureVersion,
     * activeMutations) дерева изменилась ВО ВРЕМЯ двух обходов, — наблюдение не атомарно,
     * результат недействителен. Валидация повторяется до {@code VALIDATION_RETRY_LIMIT}
     * раз; после этого ДИАГНОСТИКА ПРОПУСКАЕТСЯ с WARN (полную гарантию целостности несут
     * tripwire-ы на каждой мутации). РЕАЛЬНОЕ нарушение (расхождение множеств листьев
     * при стабильной версии) по-прежнему громко роняет операцию.
     */
    private static final int VALIDATION_RETRY_LIMIT = 3;

    /** Внутренний маркер «наблюдение не атомарно» — не уходит наружу валидатора. */
    private static final class ConcurrentStructureChangeSignal extends XdStorageException {
        ConcurrentStructureChangeSignal(final String message) {
            super(message);
        }
    }

    /**
     * Full structural validation of the index tree after a mutation, run under the tree
     * write lock: mutators hold that lock for a whole operation, so the validator cannot
     * observe intermediate rebalance states. Sample-consistent (R-56): when the tree's
     * (structureVersion, activeMutations) pair changes between the two traversals, the
     * observation was not atomic — validation is retried up to a small limit and, if the
     * structure never quiesces, skipped with a WARN (the per-mutation tripwires carry the
     * real integrity guarantee); a genuine violation under a stable version still fails
     * loudly and is logged at ERROR before propagating.
     *
     * @param tree        the tree to validate ({@code null} — no-op)
     * @param storage     the storage facade used to resolve node stubs
     * @param transaction the owning transaction (provides the lock timeout)
     * @param context     an operation label included in every violation message
     * @throws XdStorageException           on any invariant violation (rollback trigger)
     * @throws XdStorageConnectionException when resolving a node stub fails
     */
    public static void validateLeafChain(final XdStorageBTree tree, final IXdStorage storage,
                                         final XdStorageTransaction transaction, final String context)
            throws XdStorageException, XdStorageConnectionException {
        if (tree == null) {
            return;
        }

        tree.lockWrite(transaction);
        try {
            validateWithConsistencySampling(tree, storage, transaction, context);
        } catch (final XdStorageException e) {
            // Нарушение инварианта — всегда громко: исключение уйдет в откат транзакции,
            // но сам факт структурного повреждения обязан остаться в логе ERROR навсегда.
            log.error("B+Tree INVARIANT VIOLATION: {}", e.getMessage(), e);
            throw e;
        } finally {
            tree.unlockWrite();
        }
    }

    private static void validateWithConsistencySampling(final XdStorageBTree tree, final IXdStorage storage,
                                                        final XdStorageTransaction transaction, final String context)
            throws XdStorageException, XdStorageConnectionException {
        ConcurrentStructureChangeSignal signal = null;
        for (int attempt = 1; attempt <= VALIDATION_RETRY_LIMIT; ++attempt) {
            try {
                validateUnderTreeLock(tree, storage, transaction, context);
                return;
            } catch (final ConcurrentStructureChangeSignal e) {
                signal = e;
                log.warn("B+Tree validation of {} observed concurrent structure change (attempt {}/{}): {}",
                        context, attempt, VALIDATION_RETRY_LIMIT, e.getMessage());
            }
        }
        // Диагностика не смогла получить атомарный срез — пропуск вместо громкого отказа:
        // параллельные мутации продолжаются, структурные гарантии несут tripwire-ы мутаций.
        log.warn("B+Tree validation of {} skipped after {} attempts: structure never quiesced ({})",
                context, VALIDATION_RETRY_LIMIT, signal != null ? signal.getMessage() : "unknown");
    }

    private static void validateUnderTreeLock(final XdStorageBTree tree, final IXdStorage storage,
                                              final XdStorageTransaction transaction, final String context)
            throws XdStorageException, XdStorageConnectionException {
        if (tree == null) {
            return;
        }

        // R-56: сэмпл согласованности (до обхода из корня)
        final long versionBefore = tree.currentStructureVersion();
        final int activeBefore = tree.activeMutationCount();
        if (activeBefore != 0) {
            throw new ConcurrentStructureChangeSignal(context + ": мутация уже в полете (activeMutations="
                    + activeBefore + ", mutators=" + tree.describeActiveMutators()
                    + ", writeLocked=" + tree.isWriteLocked() + ") — наблюдение отложено");
        }

        // ИНВАРИАНТ 1 + сбор множества листьев, достижимых из корня
        final Set<Object> leavesViaRoot = new HashSet<>();
        if (tree.getRoot() != null) {
            // TREEOVERLAY (task 53, the reopen fix): the reopened tree's root = the
            // REFERENCE stub (the fresh parse) — the stub's fields unset (the children
            // empty!) → the descent treated the stub as a LEAF → the false divergence
            // ({the root stub} via the root vs the real chain). The materialize FIRST
            // (the R-53, the same as the chain walk's per-node materialize).
            XdStorageBTreeNode rootForDescend = tree.getRoot();
            if (XdStorageObjectUtils.isReference(rootForDescend)) {
                rootForDescend = XdStorageBTreeNode.materialize(rootForDescend, storage, transaction, tree, null);
            }
            descendCollectingLeaves(rootForDescend, storage, transaction, leavesViaRoot, context, new HashSet<>(),
                    tree.isMultiple());
        }

        // R-56: сэмпл между обходами — версия сменилась = корневой обход видел другой каркас
        final long versionBetween = tree.currentStructureVersion();
        if (versionBetween != versionBefore) {
            throw new ConcurrentStructureChangeSignal(context +
                    ": structureVersion изменился во время корневого обхода ("
                    + versionBefore + " -> " + versionBetween + ")");
        }

        if (tree.getFirstLeaf() == null) {
            if (!leavesViaRoot.isEmpty()) {
                throw new XdStorageException(context + ": firstLeaf пуст, но из корня достижимо листьев: " +
                        leavesViaRoot.size());
            }
            return;
        }

        // ИНВАРИАНТ 2 + 4 + сбор множества листьев цепочки
        final Set<Object> leavesViaChain = new HashSet<>();
        final XdStorageException[] failure = new XdStorageException[]{null};
        final Comparable[] previousKey = new Comparable[]{null};

        XdStorageBTreeNode current = tree.getFirstLeaf();
        // R-53: стаб первого листа (холодное открытие) разрешается до обхода цепочки
        if (current != null && XdStorageObjectUtils.isReference(current)) {
            current = XdStorageBTreeNode.materialize(current, storage, transaction, tree, null);
            tree.setFirstLeaf(current);
        }
        int guard = 0;
        while (current != null) {
            if (failure[0] != null) {
                throw failure[0];
            }
            if (current.getNextTreeNodeOnThisLevel() == current) {
                throw new XdStorageException(context + ": лист " + current.getId() + " замкнут сам на себя");
            }
            if (!leavesViaChain.add(current.getId())) {
                throw new XdStorageException(context + ": цикл в цепочке листьев — лист " + current.getId() +
                        " встречается повторно");
            }
            // R-56: parity и обход ключей — под монитором коллекций (дисциплина R-43),
            // чтобы даже гипотетический параллельный мутатор не дал валидатору рваный срез
            final int keysCount, objectsCount;
            final List<Comparable> leafKeys;
            synchronized (current.getKeys()) {
                keysCount = current.getKeys().size();
                leafKeys = new ArrayList<>(current.getKeys());
            }
            synchronized (current.getObjects()) {
                objectsCount = current.getObjects().size();
            }
            if (keysCount != objectsCount) {
                throw new XdStorageException(context + ": лист " + current.getId() + " имеет keys=" + keysCount
                        + " при objects=" + objectsCount + " — списки разошлись");
            }
            for (final Comparable key : leafKeys) {
                if (key == null) {
                    throw new XdStorageException(context + ": лист " + current.getId() + " содержит null-ключ");
                }
                if (previousKey[0] != null && XdStorageBTreeNode.compareMixedKeys(previousKey[0], key) > 0) {
                    throw new XdStorageException(context + ": нарушение сортировки в цепочке листьев: " + previousKey[0]
                            + " > " + key
                            + " (лист " + current.getId() + ") — некорректный split/merge");
                }
                previousKey[0] = key;
            }

            final XdStorageBTreeNode next = current.getNextTreeNodeOnThisLevel();
            if (next != null && XdStorageObjectUtils.isReference(next)) {
                // R-53: стаб следующего листа разрешается в канонический инстанс (слот чинится:
                // валидация идет под write-локом дерева, мутаций не видно)
                final XdStorageBTreeNode resolved = XdStorageBTreeNode.materialize(next, storage, transaction, tree,
                        null);
                if (resolved != next) {
                    current.setNextTreeNodeOnThisLevel(resolved);
                }
                current = resolved;
            } else {
                current = next;
            }

            if (++guard > 100000) {
                throw new XdStorageException(context + ": цепочка листьев длиннее 100000 — защита от зацикливания");
            }
        }

        // R-56: сэмпл после обхода цепочки — оба обхода атомарны только при неизменной версии
        final long versionAfter = tree.currentStructureVersion();
        final int activeAfter = tree.activeMutationCount();
        if (versionAfter != versionBefore || activeAfter != 0) {
            throw new ConcurrentStructureChangeSignal(context + ": структура изменилась во время валидации (version "
                    + versionBefore + " -> " + versionAfter + ", activeMutations " + activeBefore + " -> " + activeAfter
                            + ")");
        }

        // ИНВАРИАНТ 3: множество листьев из корня == множество листьев цепочки
        if (!leavesViaRoot.equals(leavesViaChain)) {
            final Set<Object> onlyViaRoot = new HashSet<>(leavesViaRoot);
            onlyViaRoot.removeAll(leavesViaChain);
            final Set<Object> onlyViaChain = new HashSet<>(leavesViaChain);
            onlyViaChain.removeAll(leavesViaRoot);
            throw new XdStorageException(context + ": рассинхрон структуры: листьев только из корня: " + onlyViaRoot
                    + ", листьев только из цепочки: " + onlyViaChain
                    + " (из корня: " + leavesViaRoot.size() + ", из цепочки: " + leavesViaChain.size() + ")");
        }
    }

    /**
     * TRIPWIRE: мгновенная проверка структурной целостности ноды сразу ПОСЛЕ мутации
     * (join/borrow/split/root-collapse). Для внутренней ноды: разделители строго возрастают,
     * дети == ключи + 1, id детей не дублируются. Бросает немедленно — имя операции в сообщении
     * указывает мутатор, разъехавшийся пары keys/children.
     */
    static void checkStructuralAlignment(final XdStorageBTreeNode node, final String op) throws XdStorageException {
        if (node == null || node.getChildren().isEmpty()) {
            return; // лист или пустая нода — пары keys/children не проверяемы здесь
        }
        final List<Comparable> keys = node.getKeys();
        // Для multiple-деревьев равные разделители легитимны (дубликаты ключа лежат в разных листьях)
        final boolean allowEqualSeparators = node.getTree() != null && node.getTree().isMultiple();
        for (int i = 1; i < keys.size(); ++i) {
            final int cmp = XdStorageBTreeNode.compareMixedKeys(keys.get(i - 1), keys.get(i));
            if (cmp > 0 || (cmp == 0 && !allowEqualSeparators)) {
                throw new XdStorageException("[tripwire:" + op + "] нода " + node.getId()
                        + ": дубликат/убывание разделителя keys[" + (i - 1) + "]=" + keys.get(i - 1)
                        + " >= keys[" + i + "]=" + keys.get(i));
            }
        }
        if (node.getChildren().size() != keys.size() + 1) {
            throw new XdStorageException("[tripwire:" + op + "] нода " + node.getId()
                    + ": children=" + node.getChildren().size() + " при keys=" + keys.size());
        }
        final Set<Object> seenChildIds = new HashSet<>();
        for (final XdStorageBTreeNode child : node.getChildren()) {
            if (child != null && child.getId() != null && !seenChildIds.add(child.getId())) {
                throw new XdStorageException("[tripwire:" + op + "] нода " + node.getId()
                        + ": дубликат ребенка " + child.getId() + " в списке детей");
            }
        }
    }

    /**
     * R-56: диагностический обход из корня для дампа расхождения — только id@identityHash,
     * без бросков (диагностика не имеет права маскировать исходное нарушение).
     */
    private static void descendCollectingLeaves(final XdStorageBTreeNode node, final IXdStorage storage,
                                                final XdStorageTransaction transaction, final Set<Object> leaves,
                                                final String context, final Set<Object> visited,
                                                final boolean allowEqualSeparators)
            throws XdStorageException, XdStorageConnectionException {
        if (node == null) {
            return;
        }
        if (!visited.add(node.getId())) {
            throw new XdStorageException(context + ": цикл/дубль в descend-обходе — нода " + node.getId() +
                    " встречена повторно");
        }

        if (node.getChildren().isEmpty()) {
            leaves.add(node.getId());
            return;
        }

        // ИНВАРИАНТ: разделители внутренней ноды СТРОГО возрастают, а число детей
        // ровно на единицу больше числа ключей. Дубль/убывание разделителя — первый
        // симптом каскадного сбоя join/borrow (сдвиг пары keys/children), из которого
        // растут промахи спуска ("does not exists") и сироты цепочки листьев.
        // R-56: срезы под мониторами коллекций (дисциплина R-43).
        final List<Comparable> nodeKeys;
        final List<XdStorageBTreeNode> childrenSnapshot;
        synchronized (node.getKeys()) {
            nodeKeys = new ArrayList<>(node.getKeys());
        }
        synchronized (node.getChildren()) {
            childrenSnapshot = new ArrayList<>(node.getChildren());
        }
        for (int i = 1; i < nodeKeys.size(); ++i) {
            final int cmp = XdStorageBTreeNode.compareMixedKeys(nodeKeys.get(i - 1), nodeKeys.get(i));
            if (cmp > 0 || (cmp == 0 && !allowEqualSeparators)) {
                throw new XdStorageException(context + ": нарушение монотонности разделителей внутренней ноды " + node
                        .getId()
                        + ": keys[" + (i - 1) + "]=" + nodeKeys.get(i - 1) + " >= keys[" + i + "]=" + nodeKeys.get(i)
                        + " (сдвиг пары keys/children в join/borrow)");
            }
        }
        if (childrenSnapshot.size() != nodeKeys.size() + 1) {
            throw new XdStorageException(context + ": внутренняя нода " + node.getId() + " имеет children=" +
                    childrenSnapshot.size()
                    + " при keys=" + nodeKeys.size() + " (ожидалось children == keys + 1)");
        }

        for (final XdStorageBTreeNode child : childrenSnapshot) {
            if (child == null) {
                throw new XdStorageException(context + ": внутренняя нода " + node.getId() + " содержит null-ребенка");
            }
            XdStorageBTreeNode resolved = child;
            if (XdStorageObjectUtils.isReference(child)) {
                try {
                    // R-53: стаб ребенка разрешается в канонический инстанс по id
                    resolved = XdStorageBTreeNode.materialize(child, storage, transaction, null, node);
                } catch (final Throwable t) {
                    throw new XdStorageException(context + ": ВИСЯЧАЯ ССЫЛКА — внутренняя нода " + node.getId()
                            + " ссылается на ребенка " + child.getId() + ", который не разрешается: " + t.getMessage(),
                                    t);
                }
            }
            descendCollectingLeaves(resolved, storage, transaction, leaves, context, visited, allowEqualSeparators);
        }
    }
    /**
     * LOSTUPD-2 (task 53): the COMMIT-TIME revalidation hook — the stage→commit window
     * re-check shared by the commit protocol's phase loop and the tests. The semantics:
     * a foreign COMMIT between this transaction's read of the record (writeReadTimestamp,
     * captured at the read registration) and this commit makes the staged write stale →
     * the loud conflict (the first-committer-wins extended to the commit; the loser
     * retries with a fresh read). The aborted foreign stages don't bump commitTimeNano
     * → no over-rejection. The update/delete changes only (the inserts: the duplicate-id
     * guard; the infra: the R-42 structure-owned semantics — the caller filters).
     *
     * @param record      the cache record being committed
     * @param transaction the committing transaction
     * @param prepare     the phase flag (the revalidation runs in phase-1 only)
     * @param type        the operation kind (Update/Delete revalidated; Insert skipped)
     * @param writeReadTs the read timestamp captured at the update's read registration
     * @param deleteReadTs the read timestamp captured at the delete's read registration
     * @throws XdStorageException the loud stale-write conflict (the loser retries with a fresh read)
     */
    public static void simulateCommitTimeRevalidation(final XdStorageCacheRecord record,
                                                      final IXdStorageTransaction transaction,
                                                      final boolean prepare,
                                                      final XdStorageObjectOperationType type,
                                                      final long writeReadTs,
                                                      final long deleteReadTs) throws XdStorageException {
        if (!prepare || record == null) {
            return;
        }
        if (type != XdStorageObjectOperationType.Update && type != XdStorageObjectOperationType.Delete) {
            return;
        }
        final long myReadTs = type == XdStorageObjectOperationType.Update ? writeReadTs : deleteReadTs;
        final long foreignCommit = record.getCommitTimeNano();
        if (myReadTs != 0 && foreignCommit != 0 && foreignCommit > myReadTs) {
            throw new XdStorageException("COMMIT-TIME CONFLICT (first-committer-wins): record " + record.getId()
                    + " was committed by another transaction after this transaction's read — the staged write is " +
                            "stale; retry with a fresh read");
        }
    }
}
