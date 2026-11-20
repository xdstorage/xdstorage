# Transaction model — the condensed reference

The full algorithms and the failure families:
[docs/analysis/TX_COMMIT_ROLLBACK_MODEL.md](../../../docs/analysis/TX_COMMIT_ROLLBACK_MODEL.md).

## The commit flow (commitInternal)

1. overlay-apply (task 53) — BEFORE the resource drain (the mutator registers
   the infra resources; only that puts them into acquisition).
2. skipOrWaitForInternalTransactions (R-41).
3. assignCommitSerial (R-45 — the atomic visibility across resources).
4. Acquisition (REVERSED: shared resources first, unique last — the convoy
   containment; RECIDENT bounded lock retries ×2). **THE TOTAL ORDER (the
   2026-11-21 convoy fix): the commit-order values TIE across the whole INDEX
   band — the tie MUST break by the resource id (globally unique), else the
   arbitrary per-transaction tie order deadlocks the opposing holds on the
   shared nodes (the FKBENCH: 0.8-1.2 → 121-158 tx/s after the one-comparator
   fix). Also: the READ-ONLY resources (no write changes) skip the locks and
   the phases; the FK resource always stays (the veto validations).**
5. Phase-1 loop (execution order: DATA → INDEX → FK): journal/write per
   resource; failure → hasError → break → the failure path: overlay-undo +
   discard → rethrow.
6. RBCOMMIT gate ×1: rollback-only mid-commit → overlay-undo + discard +
   buildRollbackOnlyFailure.
7. Phase-2 loop: publish the caches.
8. Failure path ×2: overlay-undo + discard.
9. Final RBCOMMIT gate → dropAppliedUndos → FINISHED → unlockAfterCommit.

## The rollback flow (rollbackInternal)

1. discardAllOverlays (the unconsumed stages dropped).
2. The resources' rollback — **DATA before INFRA** (the ordering fix: the R-57
   key compensations load-by-reference the restored records; the HASH order
   was nondeterministic and hit not-yet-restored records).
3. rollbackIndexKeyInserts/Deletes (the R-57 key compensations; skipped for
   the infraReloadedTrees — the fresh-parse restored them).
4. recoverInfraFromCommittedFiles — **overlay trees EXCLUDED** (isOverlayMode):
   their rollback = discard+applied-undo (in-memory, concurrency-safe); the
   reload would fork the canonical graphs (W-4 boundary).
5. undoAllAppliedOverlays — the applied batch undone LAST (its cache writes
   are final after the resource rollback — the R-57 ordering lesson).

## rollbackFailedCommitInternal (the mirror)

recoverInfraFromCommittedFiles → undoAllAppliedOverlays → unlock. Invoked by
the manager's catch on ANY commitInternal failure (the veto, the sweeper, the
IO) — the overlay resolution must ALSO be in the explicit rollback() path
(the manager's rollback-only early-return skips rollbackInternal, but calls
the compensation first ✓ — verify on any change).

## The isolation rules

- Stage-time first-committer-wins (commitTimeNano); the stage→commit window is
  NOT revalidated (the lost-update window — the open layer).
- Infra caches: READ COMMITTED ALWAYS (own uncommitted writes invisible;
  3s visibility budget → "serving absent").
- The B+tree in-memory instance: READ UNCOMMITTED across txs (the canonical
  graph is shared and mutated in place — the W-4 domain).
- R-45 commit serial: one serial per tx — the readers see all or none.
- R-58: the rollback of a COMMITTED record is ignored (the late/racing
  rollback suppression — no zombie records).

## The overlay resolution points (task 53)

| Where | What |
|---|---|
| commitInternal start | applyAllOverlays(this) — before the drain |
| phase-1 failure | undoAllAppliedOverlays + discardAllOverlays before rethrow |
| RBCOMMIT gates ×2 | undo + discard before buildRollbackOnlyFailure |
| post-phase-2 failure | undo + discard before rethrow |
| the final gate passed | dropAppliedUndos |
| rollbackInternal | discard at top; undoAllAppliedOverlays AFTER the resources' rollback (the R-57 lesson: the compensations after the general rollback are final) |
| rollbackFailedCommitInternal | the undo after recoverInfra |
| the manager's rollback-only early-return | undo + discard (the veto path skips rollbackInternal!) |
