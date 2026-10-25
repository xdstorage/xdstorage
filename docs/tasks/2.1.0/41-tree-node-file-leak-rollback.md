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

## Landed (2026-10-25): the stale-adoption vector is closed

`XdStorageBTree` now records a self-collapse in a `collapsedEmpty` flag (set at the
`setRoot(null)` choke point, cleared on any non-null root). All five
`root == null && wasInitialized` refresh branches refuse to adopt a root from storage when
the flag is set — the log-proven leak vector (the emptied `XdGalaxy-id_index` root
flipping `null → 1` mid-commit by adopting a lagging file parse, misrouting every
subsequent key operation) is dead. The btree/reliability/search estates (86-106 tests) are
green with the guard.

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

## The deterministic repro is landed (2026-10-25): `XdStorageTreeStructureRollbackTest`

Two scenarios (single-threaded, fully deterministic — no concurrency needed):
- `rollbackOfFullCollapse_RestoresTheTree` — **DISABLED: RED by design until the
  structural compensation lands.** 40 entities into a t=4 tree, all deleted in one
  transaction (the collapse empties the tree), a phantom delete fails inside the
  transaction, rollback — then EVERY entity must be loadable by id again through the
  id-index and the tree must accept growth. Red for exactly the defect: the in-place
  collapse survives the rollback and the index reads as empty.
- `rolledBackCollapse_ReopensConsistently` — **enabled and green today**: the rolled-back
  purge followed by shutdown/reopen serves the pre-transaction entities (the FILE state
  is the last-committed one — the rolled-back tx never wrote), pinning the on-disk
  half of the contract while the structural fix is pending.

### Attempt record (2026-10-25, TWO full compensation attempts, both reverted)

**Attempt 1** (structure triples at split/last-key/collapse + reorder + fresh-parse
re-inserts): fixed the "was deleted this transaction" refusals (the reorder — resource
rollback must precede the key/structure compensations), then failed on rebalance budget
exhaustion: the re-inserts descended the IN-MEMORY mutated node graph (clearing the
record cache does not unlink the linked node objects — the restored root instance's
subtree is the post-mutation graph).

**Attempt 2** (same round, incremental): registered the structure triples at ALL setRoot
sites, reordered resources→structures→keys, added fresh-parse-per-reinsert
(`storage.load(tree)` after the infra-cache invalidation), and invalidated infra record
caches + canonical registrations in `postRollback`. Result: the re-inserts still burned
the rebalance retry budgets on the last keys (1–4 in the repro) — the in-memory node
graph remains structurally inconsistent beyond pointer repair: the descent follows
IN-MEMORY node instances (parsed-stub materialization resolves through the parent's
in-memory children list and the canonical registry), so a cache clear cannot force a
true re-parse while ANY mutated instance stays linked. A registry+cache invalidation
that also unlinks the in-memory references is a structural redesign of the R-53
canonical-instance identity — W-4 territory.

**Conclusion (three repair strategies documented, each fixing one layer and exposing the
next):** the rollback correctness for in-place-mutated B+tree structures requires the
W4-DESIGN write-path semantics (read-your-own-read snapshots or per-record undo logs).
Until then, the deterministic repro stays disabled-red, and the OPERATIONAL mitigation
for the leak family is the documented one: a rolled-back purge leaves orphans; a storage
REOPEN (shutdown + open) rebuilds every tree from the pre-transaction files and clears
the symptom (the enabled green scenario in the repro test).

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
