---
title: "[2.1.0][TREEFILE] B+tree node files leak on rollback — infra mutations are in-place on aliased canonical instances, so record rollback restores nodes while tree-structure mutations stay permanent"
labels: [2.1.0-train, P1, engine, bug]
status: open
---

## Summary

A disk-space leak proven by the 2026-10-25 file-leak run
(`YamlDataStorageMultithreadsTest`, the recursive leftover-file assertion — 7 of 10 runs fail
with 5–15 leaked files). After a test whose transactions rolled back, the storage holds
**orphaned B+tree node files**: `data/XdStorageBTreeNode/XdStorageBTreeNode-N.yaml` records
that are detached from every tree (the tree records say `root=null` — fully collapsed), still
carry stale search keys (`XdStorageSearchIndexKey{value: 'Planet_3', objectId: 214}` for
planets whose records are long gone), and whose `parent` pointers reference nodes that no
longer exist.

## The mechanism (the infra rollback asymmetry)

The engine's record rollback restores the PRE-mutation version of a record — but only for
records whose versions are CLONED on write. Infrastructure records (everything in
`io.github.xdstorage.btree` — the `XdStorageBTree` and `XdStorageBTreeNode` classes) are
deliberately NOT cloned (`materializeForCache` returns the live canonical instance — the
"живой канонический граф" design, R-40/R-53). Consequence, when a transaction that mutated a
tree ROLLS BACK:

- the tree record's `root`/`firstLeaf` fields were mutated IN PLACE on the aliased canonical
  instance (`setRoot`/`setFirstLeaf` during collapse/join), and `prevObject` IS the same
  instance — the rollback "restores" the post-mutation state. The on-disk tree record ends up
  `root=null` even when the rollback should have restored the pre-collapse root;
- node records deleted by a collapse/join roll back through the record mechanism — but their
  restored content is likewise the aliased live state, and the structural detachment is not
  undone: the node is back on disk while unreachable from the (reset) tree;
- search-key deletions (`planet_idx`/`star_system_idx` trees) have NO rollback compensation at
  all (the task-38 gap) and id-index key deletions only got theirs in 7ed2fa2.

Every rolled-back transaction that touched a tree (workers roll back 4+ times per run —
timeout sweeps, rollback-only marks, task-40 aborts) can therefore orphan node files. The
leak composes with task 40 (a purge aborted by a dangling reference leaks whole subtrees) and
task 38 (uncompensated search-key deletes keep trees non-empty).

## ⚠️ A naive shutdown sweep is UNSAFE — proven, do not re-attempt as written

An implementation attempt (2026-10-25, reverted) walked the trees' IN-MEMORY children from
the canonical instances and deleted registered-but-unreachable node files at shutdown. The
reliability estate caught it immediately: `XdStorageSearchRangeReopenTest`,
`XdStorageW2ReopenBTreeTest`, `XdStorageBackupRestoreWalkthroughTest` —
"B+Tree node N is referenced but missing in storage" on the next open. The in-memory
children graph is NOT a sound reachability oracle: node children materialize lazily
(`loadChildOfThisNode(storage, tx)` needs a transaction — none exists at shutdown), so the
walk misses subtrees of parsed nodes and garbage-collects LIVE nodes. Any sweep must first
build a transaction-free, persistence-faithful reachability view (persisted child ids, not
in-memory instances) — that is precisely the structural work below.

## The fix direction

The infra rollback asymmetry itself needs the same treatment R-57 gave the id-index keys —
the tree mutations must either become compensable or become rollback-safe:

1. **Structural compensation**: register (tree, oldRoot, oldFirstLeaf) before every
   `setRoot`/`setFirstLeaf` and restore them on rollback (the tree-record mirror of
   `registerIndexKeyForRollback`); joins/collapses register the detached node records for
   re-adoption or delete them compensably.
2. **Orphan sweep** (defense in depth, not the primary fix): at a clean `shutdown()` — after
   the W-4c checkpoint — walk every known tree from its root, collect reachable node ids, and
   delete unreachable `XdStorageBTreeNode` records of that storage. The purge scenario ends
   with empty trees (root=null), so the sweep removes every orphan. The node records carry no
   tree id, so reachability must come from walking the trees (all of them), not from file
   names.
3. Decide and document the READ-your-own-write story for infra: today a rollback can also
   leave a tree structurally mutated while its KEYS were compensated back — the two layers
   (key compensation vs structure compensation) must be reconciled in one place
   (W4-DESIGN's W-4 rewrite owns the write path; this task owns the rollback correctness).

## Cautions

- The btree suites (split/join invariants, chain walks, reopen restore) and the power-loss
  matrix must stay green; the sweep must never run concurrently with live trees (shutdown-only,
  under the folder lock).
- The R-55 retention (resources survive release) must not be broken by the sweep.

## Acceptance criteria

- [ ] A repro test: transaction mutates a tree (delete causing collapse) → rollback → assert
      the tree record and the node files reflect the PRE-transaction structure (or an
      explicitly compensated equivalent), and no unreachable node files remain after
      shutdown.
- [ ] The two multithreads stress classes pass 10/10 runs with the recursive leftover-file
      assertion green.
- [ ] Full suite green ×2.

Estimate: 2–3 d.

## References

- [38-index-resource-contracts](38-index-resource-contracts.md) — search-key delete
  compensation gap (the same family)
- [40-lost-update-first-committer-wins](40-lost-update-first-committer-wins.md) — the purge
  aborts that leak whole subtrees
- 7ed2fa2 — the id-index delete compensation this task generalizes
