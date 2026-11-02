package io.github.xdstorage.btree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Test-side verifier of the COMPLETE set of structural B+tree invariants (both modes). Unlike
 * the engine's sampled validator ({@code XdStorageBTreeInvariants}) it checks occupancy,
 * balance, separator bounds, parent pointers, the leaf chain against the in-order leaves,
 * storage leaks and lock leaks. Every violation fails with the invariant's number:
 * <ol>
 *   <li>INV-1 empty tree: {@code root == null} exactly when {@code firstLeaf == null};</li>
 *   <li>INV-2 the root has no parent;</li>
 *   <li>INV-3 node shape: a leaf has no children and keys/records of equal size; an internal
 *       node has no records and {@code children == keys + 1}; no null key/child; no node is
 *       reachable twice;</li>
 *   <li>INV-4 occupancy: at most {@code 2t-1} keys per node, at least {@code t-1} per non-root
 *       node, at least one in the root;</li>
 *   <li>INV-5 balance: every leaf at the same depth;</li>
 *   <li>INV-6 in-node order: keys non-decreasing (a MULTI tree) / strictly increasing (UNIQUE);</li>
 *   <li>INV-7 separator bounds: every key of child {@code i} lies within
 *       {@code [keys[i-1], keys[i]]} (MULTI: duplicates on both sides of an equal separator) /
 *       {@code [keys[i-1], keys[i])} (UNIQUE);</li>
 *   <li>INV-8 parent pointers: every child points to the node that holds it;</li>
 *   <li>INV-9 every node points to its tree;</li>
 *   <li>INV-10 leaf chain: starts at the leftmost leaf, visits exactly the in-order leaves and
 *       ends with {@code null};</li>
 *   <li>INV-11 global key order along the chain (non-decreasing / strictly increasing);</li>
 *   <li>INV-12 node ids are unique;</li>
 *   <li>INV-13 storage: the stored nodes are exactly the reachable ones (no orphan, no missing
 *       node) — when a {@link BTreeMemoryNodeStorage} is given;</li>
 *   <li>INV-14 quiescence: no mutation or walk in flight, no lock left held on the tree or a
 *       node.</li>
 * </ol>
 * The content invariant (the entries equal the expected model, duplicates in insertion
 * order) is checked by the tests on {@link Shape#entries()}.
 */
final class BTreeStructureChecker {

    private BTreeStructureChecker() {
    }

    /** A verified tree: its entries in chain order plus the structural metrics. */
    static final class Shape {
        private final List<Comparable> keys;
        private final List<Object> records;
        private final int height;
        private final int nodeCount;

        private Shape(final List<Comparable> keys, final List<Object> records, final int height, final int nodeCount) {
            this.keys = keys;
            this.records = records;
            this.height = height;
            this.nodeCount = nodeCount;
        }

        /** @return the entries in chain order as {@code key=record} strings */
        List<String> entries() {
            final List<String> entries = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); ++i) {
                entries.add(keys.get(i) + "=" + records.get(i));
            }
            return entries;
        }

        int size() {
            return keys.size();
        }

        /** @return the number of levels (0 for an empty tree, 1 for a root leaf) */
        int height() {
            return height;
        }

        int nodeCount() {
            return nodeCount;
        }
    }

    static Shape verify(final XdStorageBTree tree) {
        return verify(tree, null);
    }

    static Shape verify(final XdStorageBTree tree, final BTreeMemoryNodeStorage storage) {
        check(tree.activeMutationCount() == 0, "INV-14", "mutation in flight: " + tree.describeActiveMutators());
        check(tree.activeWalkCount() == 0, "INV-14", tree.activeWalkCount() + " leaf-chain walk(s) in flight");
        assertLockFree(tree, "the tree");

        final XdStorageBTreeNode root = tree.getRoot();
        if (root == null) {
            check(tree.getFirstLeaf() == null, "INV-1", "root is null but firstLeaf is node " + idOf(tree.getFirstLeaf()));
            if (storage != null) {
                check(storage.storedNodeIds().isEmpty(), "INV-13", "empty tree but the storage still holds nodes "
                        + storage.storedNodeIds());
            }
            return new Shape(Collections.<Comparable>emptyList(), Collections.emptyList(), 0, 0);
        }
        check(tree.getFirstLeaf() != null, "INV-1", "root " + idOf(root) + " exists but firstLeaf is null");
        check(root.getParent() == null, "INV-2", "root " + idOf(root) + " has parent " + idOf(root.getParent()));

        final Walk walk = new Walk(tree);
        walk.visit(root, 1, null, null, true);

        // INV-10: the chain is exactly the in-order sequence of leaves
        XdStorageBTreeNode current = tree.getFirstLeaf();
        for (int i = 0; i < walk.leaves.size(); ++i) {
            final XdStorageBTreeNode expected = walk.leaves.get(i);
            check(current == expected, "INV-10", (i == 0 ? "firstLeaf " : "chain position " + i + " is node ")
                    + idOf(current) + ", the in-order leaf is " + idOf(expected));
            current = current.getNextTreeNodeOnThisLevel();
        }
        check(current == null, "INV-10", "the chain continues past the last leaf to node " + idOf(current));

        // INV-11: global order along the chain
        for (int i = 1; i < walk.keys.size(); ++i) {
            final int cmp = walk.keys.get(i - 1).compareTo(walk.keys.get(i));
            check(cmp < 0 || (cmp == 0 && tree.isMultiple()), "INV-11", "chain order broken at entry " + i + ": "
                    + walk.keys.get(i - 1) + " then " + walk.keys.get(i));
        }

        if (storage != null) {
            final Set<Long> stored = storage.storedNodeIds();
            final Set<Long> orphans = new HashSet<>(stored);
            orphans.removeAll(walk.ids);
            final Set<Long> missing = new HashSet<>(walk.ids);
            missing.removeAll(stored);
            check(orphans.isEmpty() && missing.isEmpty(), "INV-13", "stored but unreachable (orphans): " + orphans
                    + ", reachable but not stored: " + missing);
        }
        return new Shape(walk.keys, walk.records, walk.leafDepth, walk.visited.size());
    }

    private static final class Walk {
        private final XdStorageBTree tree;
        private final int t;
        private final boolean multiple;
        private final Set<XdStorageBTreeNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Long> ids = new HashSet<>();
        private final List<XdStorageBTreeNode> leaves = new ArrayList<>();
        private final List<Comparable> keys = new ArrayList<>();
        private final List<Object> records = new ArrayList<>();
        private int leafDepth = -1;

        private Walk(final XdStorageBTree tree) {
            this.tree = tree;
            this.t = tree.getT();
            this.multiple = tree.isMultiple();
        }

        private void visit(final XdStorageBTreeNode node, final int depth, final Comparable lower, final Comparable upper,
                           final boolean isRoot) {
            final String at = "node " + idOf(node) + " (depth " + depth + ")";
            check(visited.add(node), "INV-3", at + " is reachable twice");
            check(node.getTree() == tree, "INV-9", at + " points to another tree");
            if (node.getId() != null) {
                check(ids.add(node.getId()), "INV-12", at + ": duplicate node id");
            }
            assertLockFree(node, at);

            final List<Comparable> nodeKeys = new ArrayList<>(node.getKeys());
            final List<XdStorageBTreeNode> children = new ArrayList<>(node.getChildren());
            final List<Object> nodeRecords = new ArrayList<>(node.getObjects());
            final int n = nodeKeys.size();

            check(n <= 2 * t - 1, "INV-4", at + " holds " + n + " keys, the maximum is " + (2 * t - 1));
            if (isRoot) {
                check(n >= 1, "INV-4", "the root " + at + " holds no key");
            } else {
                check(n >= t - 1, "INV-4", at + " holds " + n + " keys, the minimum is " + (t - 1));
            }
            for (int i = 0; i < n; ++i) {
                final Comparable key = nodeKeys.get(i);
                check(key != null, "INV-3", at + " holds a null key at " + i);
                if (i > 0) {
                    final int cmp = nodeKeys.get(i - 1).compareTo(key);
                    check(cmp < 0 || (cmp == 0 && multiple), "INV-6", at + ": keys[" + (i - 1) + "]=" + nodeKeys.get(i - 1)
                            + (cmp == 0 ? " duplicates " : " > ") + "keys[" + i + "]=" + key);
                }
                check(lower == null || key.compareTo(lower) >= 0, "INV-7", at + ": key " + key
                        + " below the separator bound " + lower);
                if (upper != null) {
                    final int cmp = key.compareTo(upper);
                    check(cmp < 0 || (cmp == 0 && multiple), "INV-7", at + ": key " + key + " above the separator bound " + upper);
                }
            }

            if (children.isEmpty()) {
                check(nodeRecords.size() == n, "INV-3", "leaf " + at + " holds " + n + " keys but " + nodeRecords.size() + " records");
                if (leafDepth < 0) {
                    leafDepth = depth;
                }
                check(depth == leafDepth, "INV-5", "leaf " + at + " lies at depth " + depth + ", the first leaf at " + leafDepth);
                leaves.add(node);
                keys.addAll(nodeKeys);
                records.addAll(nodeRecords);
                return;
            }

            check(nodeRecords.isEmpty(), "INV-3", "internal " + at + " holds " + nodeRecords.size() + " records");
            check(children.size() == n + 1, "INV-3", "internal " + at + " holds " + children.size()
                    + " children for " + n + " keys");
            for (int i = 0; i < children.size(); ++i) {
                final XdStorageBTreeNode child = children.get(i);
                check(child != null, "INV-3", "internal " + at + " holds a null child at " + i);
                check(child.getParent() == node || (child.getParent() != null && child.getParent().getId() != null
                                && child.getParent().getId().equals(node.getId())),
                        "INV-8", "child " + idOf(child) + " of " + at + " points to parent " + idOf(child.getParent()));
                visit(child, depth + 1, i == 0 ? lower : nodeKeys.get(i - 1), i == n ? upper : nodeKeys.get(i), false);
            }
        }
    }

    private static void assertLockFree(final IXdStorageBTreeNode lockable, final String what) {
        check(!lockable.isWriteLocked() && !lockable.isReadLocked(), "INV-14", what + " is still locked by the checking thread");
        check(lockable.tryLockWrite(null), "INV-14", what + " is still locked by another thread (leaked lock)");
        lockable.unlockWrite();
    }

    private static String idOf(final XdStorageBTreeNode node) {
        if (node == null) {
            return "null";
        }
        return node.getId() != null ? String.valueOf(node.getId()) : "@" + System.identityHashCode(node);
    }

    private static void check(final boolean condition, final String invariant, final String message) {
        if (!condition) {
            throw new AssertionError(invariant + " violated: " + message);
        }
    }
}
