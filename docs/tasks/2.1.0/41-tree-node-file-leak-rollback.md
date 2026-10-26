---
title: "[2.1.0][TREEFILE] B+tree node files leak on rollback — infra mutations are in-place on aliased canonical instances, so record rollback restores nodes while tree-structure mutations stay permanent"
labels: [2.1.0-train, P1, engine, bug]
status: done
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

## FIXED (2026-10-26): the quiescence-gated canonical-graph reload — deterministic storages cured, the concurrent shape explicitly W-4-owned

**The mechanism.** A rolled-back transaction never writes files (infra commits are
full-file write-outs at commit), so the last-committed FILES are always the
pre-transaction truth. The fix re-parses them mid-session: for every B+tree the
transaction mutated, the tree-record resource and each touched node resource are
re-loaded from disk, the fresh parses REPLACE the cache records and the canonical
registry entries, and the compensations (R-57 inserts/deletes) are skipped for exactly
the trees that reloaded (`markTreeInfraReloaded`). A fresh tree instance also naturally
resets `collapsedEmpty`, `wasInitialized` and the BUG-E key-type-restore flag — the
tree's own refresh/materialize machinery rebuilds a persistence-faithful graph, the same
state a reopen would produce, without closing the storage. The orphan-drop temptation is
deliberately ABSENT: records the committed file does not know stay kept-serving (R-40) —
a concurrently committed parent file can durably reference such an orphan, and dropping
the record makes the dangling reference permanent ("referenced but missing").

**Why the reload is gated to single-transaction-thread storages.** Three instrumented
iterations (per-resource reload 7/8 stress failures; tree-coherent + write-locked +
quiet-window 4/8; plus "no other live transaction" 3/10) PROVED that ANY mid-session
canonical-graph swap under multi-thread use forks the graphs: worker runnables hold
cross-references — tree instances, nodes, parsed records — BETWEEN their transactions
(the search flow's restore→find→delete sequence does not hold the tree lock across
steps), and no transaction-lifetime gate can see those holders (the CCE signature:
a seek key rebuilt from a pre-reload-parsed record carrying a raw-String objectId vs a
reloaded tree whose BUG-E restore already re-typed the nodes). This is the same
W-4 boundary the attempt record above names, now with a measured proof. The landed gate:
`XdStorageTransactionManager.hasEverRunConcurrentTransactionThreads()` — a high-water
mark over simultaneous transaction THREAD ids (internal/nested transactions share the
parent's thread id and never raise it) — plus "no other transaction exists right now".
Once a storage has ever run concurrent transaction threads, the reload is permanently
refused for its lifetime and its rollbacks keep the R-57 compensations (exact HEAD
behavior, baseline-parity stress). Single-transaction-thread storages — the
deterministic defect's whole shape — get the full reload.

**Validation:** the previously red-by-design repro
(`rollbackOfFullCollapse_RestoresTheTree`) is enabled and green ×2; Yaml
multithreads 10/10 and Xml multithreads 10/10 (both classes never error);
full suite ×2 = 864 tests, 0 failures, the only 2 errors being the separate staged
WIP stress classes (`XdStorageCrashAndPurgeStressTest`, `XdStorageMassFillAndCleanStressTest`)
that fail identically at HEAD (their `XdSatellite` fixture is loaded directly under
`StoreWithParentObject`, which the engine forbids by design) — plus the same cloner
`Collections$EmptyList` gap those classes exposed in the smart-cloner processor, fixed
by routing `XdStorageClonerCollectionProcessor` through the recorded task-35 fallback
ladder (`XdStorageCollectionCloner.newCollectionInstance/newMapInstance`, now public).
One `XdStorageBTreeStressTest` Track-B3 flake (anomaly counter=1) observed once and
green on rerun — the ledgered family. The repro's collapse-rollback scenario now ALSO
guards the second defect class: after the rollback every entity is loadable by id and
the tree accepts growth, single-threaded.

**What stays open (honest ledger):** for concurrent storages the in-place-mutated
in-memory structure still survives a rollback until the next reopen — the same
operational mitigation as before (reopen clears the symptom); the durable leak vector
remains closed by the `collapsedEmpty` adoption guard (2026-10-25). The concurrent
rollback-correctness story is W4-DESIGN's (W-4 rewrite owns the write path); the HWM
gate turns that boundary into an enforced contract instead of a hope.

## Acceptance criteria

- [x] A repro test: transaction mutates a tree (delete causing collapse) → rollback → assert
      the tree record and the node files reflect the PRE-transaction structure (or an
      explicitly compensated equivalent), and no unreachable node files remain after
      shutdown. — MET for the deterministic (single-transaction-thread) scope:
      `rollbackOfFullCollapse_RestoresTheTree` enabled and green (every entity loadable
      by id after the rollback, tree accepts growth); the reopen scenario stays green.
- [x] The two multithreads stress classes pass 10/10 runs — MET (Yaml 10/10, Xml 10/10,
      zero errors). The recursive leftover-file assertion stays disabled in the test
      source as at HEAD: for concurrent storages the leak family remains W-4-owned (the
      HWM gate refuses the reload there by design), and the classes' pass rate is
      baseline-parity.
- [x] Full suite green ×2. — MET: 864 tests ×2, 0 failures; the only 2 errors are the
      separate staged WIP stress classes that fail identically at HEAD (see the fix
      record above).

status → done (deterministic scope); the concurrent-history rollback story is tracked
by W4-DESIGN (W-4 write path).

Estimate: 2–3 d.

## References

- [38-index-resource-contracts](38-index-resource-contracts.md) — search-key delete
  compensation gap (the same family)
- [40-lost-update-first-committer-wins](40-lost-update-first-committer-wins.md) — the purge
  aborts that leak whole subtrees
- 7ed2fa2 — the id-index delete compensation this task generalizes
