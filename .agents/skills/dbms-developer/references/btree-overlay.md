# B+tree overlay (task 53) — the condensed design reference

The full design, the session log, and the battle scars:
[docs/analysis/BTREE_MUTATION_OVERLAY.md](../../../docs/analysis/BTREE_MUTATION_OVERLAY.md)
and [task 53](../../../docs/tasks/2.1.0/53-tree-overlay.md). The prototype:
[53-tree-overlay-prototype.patch](../../../docs/tasks/2.1.0/53-tree-overlay-prototype.patch).

## The status (2026-11-21): LANDED AND GREEN

The overlay is IN THE TREE: the FK estate 372/0/0 ×3, the collapse-rollback
test GREEN (was DISABLED-RED at HEAD), the invariants 39/0/0, **the convoy
destroyed by the total acquisition order** (the FKBENCH 121–158 tx/s at t=10 —
the ≥100 target met; the deadlock root = the arbitrary intra-band tie order),
MassFillSecond ×3 WITHOUT the semaphore (the survivors = 0, the throughput
40×). The remaining: the lost-update window (the layer 2), the BTreeStress
phantom family (the R-56/multi-scan race — PRE-EXISTING at HEAD, the stash
baseline 24810), the read-snapshot cost.

## The model

- Overlay-mode trees: `id_index` (per class) + `*_foreign_key` (per relation
  direction) — `isOverlayMode()` derives from the tree id name (survives
  reloads).
- Op-time: `tree.insert/delete` → stageInsert/stageDelete(tx) — the per-tx
  overlay (ops list + stagedInserts/stagedDeletes maps). NO locks, NO cache
  slots, NO physical mutation. Later-wins: a staged delete prunes the staged
  insert (and vice versa) — the merged read matches the op-replay.
- Reads: find/has merge the OWNING tx's overlay over the physical snapshot
  (read-your-own-write); foreign overlays invisible (READ-COMMITTED).
- Commit: applyAllOverlays(this) at commitInternal BEFORE the resource drain —
  per-id applyOverlaysById: the batch physical replay (per-op undo), the push
  into APPLIED_UNDOS, then the normal phases (the node writes journal through
  the owning infra resource).
- Failure: any commitInternal failure path → undoAllAppliedOverlays(this) +
  discardAllOverlays(this) BEFORE rethrow (the RBCOMMIT gates ×2, the
  exceptions[0] paths ×2). The manager's rollback-only early-return → the
  resolution in rollbackTransaction's early-return branch.
- Plain rollback: discardAllOverlays (the stages never physically applied).

## The keying contract

Everything keys on overlayResolutionOwnerId() =
getGlobalTransactionId() ?? getTransactionId():
- the stage (the btree ops run under the INTERNAL tx — the owner bridges to
  the outer);
- the resolution (the commit/rollback of the same owner);
- APPLIED_UNDOS (per overlay id — the resolution loop iterates the ids).

## The recovery exclusion

recoverInfraFromCommittedFiles: the overlay trees are EXCLUDED from the
grouping (isOverlayMode() public) — their rollback is discard+applied-undo
(in-memory, concurrency-safe); the canonical-graph swap would fork the graphs
under concurrency (the W-4 boundary; the single-thread gate
infraThreadHighWaterMark ≤ 1 counts even the storage's own daemon threads —
proven: the recovery skipped in a single-threaded test).

## The pitfalls (each = a debugging cycle)

1. The stage keys by the INTERNAL tx id → the resolution must iterate
   overlayResolutionIds(), not just the outer id.
2. applyOverlaysById must NOT take the tree write-latch (non-reentrant;
   doInsert/doDelete latch themselves) — the outer latch = the self-deadlock,
   the sweeper killed the creator tx.
3. Never storage.load(tree) inside phases (the infra READ COMMITTED
   invisibility → 3s stalls); resolve via the instance registry.
4. The APPLIED_UNDOS lookup must use the SAME id the push used (the overlay
   id, not the outer).
5. The undo replays with the REAL transaction (the cache contour), never null.
6. The mass-fill cascade upsert (AUTOSAVE): the stub children of a one-level
   graph must be SKIPPED by the cascade (isReference check) — the naive upsert
   wiped the durable collections (the MassFill leak).
7. The R-57 key compensations are no-ops for overlay trees (their tree ops
   stage into the dying overlay and are discarded) — correct by design; the
   recovery skips their journal entries (uncommitted serial).
