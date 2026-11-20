# Architecture map — subsystems, layers, interconnections

The condensed engine map for the working developer. The full specification:
[docs/ANALYSIS-ARCHITECTURE.md](../../../docs/ANALYSIS-ARCHITECTURE.md) (19 sections).
The transaction algorithms: [TX_COMMIT_ROLLBACK_MODEL.md](../../../docs/analysis/TX_COMMIT_ROLLBACK_MODEL.md).

## 1. The call graph (write path)

```
XdStorage facade (save/update/delete/load/commit/rollback)
  → XdStorageTransactionManager (per-thread tx registry; internal txs for R-41)
    → XdStorageTransaction (forCommit queue of ComparableResourceObject;
       commitInternal: overlay-apply → drain → acquisition (reverse: shared
       first) → phase loop (DATA → INDEX → FK) → RBCOMMIT gates → phase-2 →
       FINISHED; rollbackInternal: discard-overlays → resources rollback
       (DATA→INFRA order) → R-57 key compensations → recoverInfra → overlay-undo)
      → Resources (XdStorageResource: DATA/INDEX bands; infra = btree package)
        → XdStorageResourceCache (records: object/newObject/prevObject/prevSnapshot,
           one change-slot per record, first-committer-wins at stage)
          → XdStorageCacheCommitProtocol (prepare/perform collapsed; double-feed
             phase-2 refresh for deferred ids)
      → B+trees (id_index per class; *_foreign_key per relation direction;
           planet_idx search trees) — IN-PLACE mutation on the canonical graph
        → (task 53) per-tx overlays: staged ops, applied at commitInternal
```

## 2. The persistence layers

| What | Where | Written when |
|---|---|---|
| User entity records | per-entity files (StoreAsClassObjects) / per-class (SingleObject) | phase-1: journal append (W-4a) + full rewrite at checkpoint |
| Hash id-index | one file per class — **full rewrite per touched commit** (B1 wall) | phase-1 writeObjects |
| B+tree id-index / search trees | per-node files + tree record | phase-1 per touched node |
| FK trees | per-relation-direction B+trees (`*_foreign_key`) | op-time (pre-overlay) / commit-apply (overlay) |
| Journal | per-resource append (W-4a); recovery replays COMMITTED serials only (R-45) | phase-1 append |
| Structure metadata | structure/ dir | on structure updates |

## 3. The isolation model

- **Domain records**: first-committer-wins at stage time
  (`canBeChangedByTransaction` with commitTimeNano — the true commit moment);
  reads = READ COMMITTED on the record state (own staged writes visible
  through the tx cache).
- **Infra records (btree package)**: READ COMMITTED ALWAYS — own uncommitted
  writes INVISIBLE (3s wait budget, then "serving absent"); updates version-
  relaxed (R-42); structural node cache fully relaxed.
- **B+tree in-memory instance**: shared canonical graph, mutated IN-PLACE —
  the cross-tx visibility of tree ops = READ UNCOMMITTED (the estate lives
  with it; the FK read overlay adds the tx's own view).
- **Rollback-only mark**: the veto/mark lands → the manager's rollback()
  EARLY-RETURNS (the commit flow is assumed to compensate) — any post-mark
  cleanup hooks must live in the commit failure paths, not only in
  rollbackInternal (the overlay lesson).

## 4. The overlay engine (task 53) — the current proposal

Stage (op-time, lock-free) → apply (commitInternal start, batch physical
replay, per-op undo) → success = drop; failure/rollback = undo-replay +
discard. Keying: overlayResolutionOwnerId() = getGlobalTransactionId() ??
getTransactionId() — the R-41 internal txs stage under their own ids; the
resolution iterates the outer tx's id set (own + internalTransactions).

The full design, the contract pitfalls, the battle scars:
[BTREE_MUTATION_OVERLAY prototype doc](../../../docs/analysis/BTREE_MUTATION_OVERLAY.md)
and [task 53](../../../docs/tasks/2.1.0/53-tree-overlay.md).

## 5. The subsystem interconnection table

| From | To | Coupling | Breakage symptom |
|---|---|---|---|
| FK engine | id-index resources (read/has) | reads by id (merged overlay) | has=false → orphan-veto misfires |
| FK engine | FK trees (B+tree pairs) | stage/apply/dissolve | dangling pairs → invisible-orphan leaks |
| Cascade (AutoSave) | entity saves + id-index | upsert per child; stub-skip guard | duplicate-wipe → unreachable subtrees |
| Commit protocol | journal | appendRedo per resource (phase-1) | recovery skips uncommitted serials |
| Recovery | journal | replay committed serials only | uncommitted apply entries correctly discarded |
| Sweeper | records/tx | mark rollback-only; kills waiter at tx timeout | 8-20s stalls under the commit convoy |
