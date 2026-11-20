# B+tree Mutation Overlay — design, prototype, remaining layers (task 53)

**Status:** the prototype built and debugged to **365/372** estate tests;
reverted to HEAD (the night of 2026-11-20) — three remaining integration
layers need a dedicated session. The full prototype:
[53-tree-overlay-prototype.patch](../tasks/2.1.0/53-tree-overlay-prototype.patch).
The working journal and all the traces:
[53-tree-overlay.md](../tasks/2.1.0/53-tree-overlay.md).

---

## 1. The problem: why the tree is the write choke

B+tree mutations run **in place on the live canonical graph** at operation
time (the contour rule: infrastructure mutations happen while the phase window
is open). The consequences:

1. **The tree record's change-slot is held until commit** → all writers of a
   class serialize on the tx duration. Measured (FKBENCH Step 0, task 50):
   the concurrent block-write **167 → 1.2 tx/s** (a ~140× collapse),
   FK-independent (the id-index trees); the FK trees add a second layer
   (+50% stall time).
2. **The rollback of in-place mutations is unsound** (task 41): two
   compensation attempts (2026-10-25) proved the mutated in-memory graph
   cannot be repaired in place (rebalance budgets, the "in-memory mutated
   node graph").
3. **The veto path**: the apply physically mutates the tree BEFORE the phases;
   a veto after the apply leaves the applied mutations without undo (§4.3).

## 2. The solution: the per-tx overlay

The mutations of the overlay trees (id_index / *_foreign_key) are **staged**
into a per-tx overlay; the physical tree is not mutated until the commit-time
application.

```
Op-time:   tree.insert/delete → stageInsert/stageDelete(tx)   [no locks, no slots]
Reads:     find/has → the physical snapshot + the writing tx's overlay merge
            [read-your-own-write; foreign overlays invisible — READ-COMMITTED]
Commit:    commitInternal → applyAllOverlays(this) BEFORE the drain  [the batch
            replay; registers the infra resources into the tx via the mutator]
Failure:   the applied-batch undo-replay in reverse order          [per-op undo]
Rollback:  discardAllOverlays — the stage discarded, the tree clean [sound by construction]
```

**The soundness property**: the rollback = discard the overlay — nothing to
reverse (the physics was never mutated). The two failed task-41 compensation
attempts (reversing the structural transforms) are not used, by design.

## 3. The keying contract (the internal-TX split lesson)

The btree operations run under an **internal transaction** (R-41), while the
resolution (apply/undo/discard) runs under the **outer** one. The key is the
single owner-id:

```java
// IXdStorageTransaction
default String overlayResolutionOwnerId() {
    final String globalId = getGlobalTransactionId();
    return globalId != null ? globalId : getTransactionId();
}
default java.util.Set<String> overlayResolutionIds() {  // own + internal ids
    ... getTransactionId() + internalTransactions ...
}
```

All the registries (overlays / TX_OVERLAY_TREES / TX_OVERLAY_STORAGE /
APPLIED_UNDOS) key on `overlayResolutionOwnerId()`. **The trap**: deriving the
key from `getTransactionId()` loses the entire stage — the apply finds empty,
the undo never fires (the 2026-11-20 trace: stage tx=04a1e40d, apply
tx=ec9a0152, undo tx=12d3046a — three different ids in one veto flow).

`overlayResolutionIds()` is a **default method** on the interface (the
default: the own id) — the test mocks do not break; `XdStorageTransaction`
overrides it with the union of `internalTransactions`.

## 4. The batch application: placement and failures

**The placement is critical (three failures at this exact spot):**

| Placement | Result |
|---|---|
| the phase-1 of the FK/index resources | AFTER the infra resource's phases → the staged changes never published ("Illegal state of record to commit", proven twice) |
| rollbackFailedCommitInternal before recoverInfra | the undo wiped by the infra-cache rollback |
| **commitInternal, BEFORE the resource-queue drain** | ✅ the application registers the infra resources into the TX via the mutator — only that puts them into acquisition/phases; the files are written by the resources' own phase-1 |

**Mid-batch failure**: the per-op undo in reverse order
(undo(insert)=delete, undo(delete)=re-insert with the captured value);
"is exists"/"does not exists" are absorbed idempotently. After the batch:
push the undo entries into APPLIED_UNDOS — if the TX rolls back AFTER the
application (the sweeper, the RBCOMMIT gate), the undo-replay restores the
trees. The commit success → dropAppliedUndos.

**Every commitInternal failure path is covered** (see
TX_COMMIT_ROLLBACK_MODEL.md §2): exceptions[0] → undo+discard; the RBCOMMIT
gates ×2 → undo+discard; the final gate → dropAppliedUndos. **The manager's
rollback-only early-return** (a rollback-only TX: rollbackTransaction returns
without calling rollbackInternal) — also undo+discard (without it the applied
tree deletions persist forever — the veto path).

## 5. The later-wins staging (the merge invariant)

`stageDelete` must prune the value from `stagedInserts[key]` (and vice versa) —
otherwise the merged read shows a pair the op-replay correctly removes
(the symptom: OutcomeRelations "expected 1 but was 2").

## 6. Recovery: the overlay trees are excluded

`recoverInfraFromCommittedFiles()` reloads the canonical tree graphs from the
files — **not required and unsafe** for the overlay trees (swapping the
canonical graph forks it under concurrency — the W-4 boundary; the gate
`infraThreadHighWaterMark ≤ 1` counts even the storage's own daemon threads —
proven: the recovery skipped in a single-threaded test). The overlay trees are
excluded from the recovery grouping (`isOverlayMode()` public): their rollback
is the in-memory discard+applied-undo, concurrency-safe.

## 7. The prototype results

| Run | Result |
|---|---|
| The FK estate | **365/372** (all the "mechanical" integrations green) |
| The battle scars (all closed) | the CHM Recursive update; the merge later-wins; the apply placement ×3; the self-deadlock latch; the storage.load invisibility; the owner-id keying |
| The open layers | see §8 |

## 8. The remaining layers (with traces — everything in task 53)

1. **The internal-TX bridge in the veto path**: the stage delete-flow keys
   under the internal id (the btree view), the resolution under the outer.
   The owner-id keying is implemented, but the resolver test stayed red —
   the interaction with the rollback compensations
   (rollbackFailedCommitInternal / rollbackIndexKey*) needs the full trace
   (the probes: STAGE-D / APPLY / UNDO-OP — in the code, removable).
2. **The lost-update window** stage→commit: no commit-time version
   revalidation (a separate write-path core layer; see
   TX_COMMIT_ROLLBACK_MODEL.md §3).
3. **The commit-lock convoy**: each commit holds the shared trees through both
   phases → the queues to the timeouts. The fix = the granularity (holding
   only during own apply).

## 9. The tests pinning the behavior after the completion

| Test | What it pins |
|---|---|
| The FK estate 372 (all) | the veto semantics, the dissolved/registered pairs, the reopen |
| `XdStorageTreeStructureRollbackTest` (RED-disabled) | the collapse-rollback: with the overlay it must turn GREEN (rollback=discard) |
| `XdStorageForeignKeyReopenKeyTypeTest` ×3 | the reopen key types |
| MassFillFirst/Second | the purge integrity (without the Semaphore after the completion) |
| NEW: the concurrent FK stress | 10 threads: inserts+deletes of one relation → 0 orphans, 0 lost updates |
| NEW: the veto-path undo pin | delete-child-alone → veto → rollback → the records restored (the id-index + the FK pairs + the data) |
| FKBENCH (task 50) | t=10 ≥ 100 tx/s, p99 < 100ms |

## 10. Related documents

- [TX_COMMIT_ROLLBACK_MODEL.md](TX_COMMIT_ROLLBACK_MODEL.md) — commit/rollback/isolation
- [W4_WRITE_PATH_STATUS.md](W4_WRITE_PATH_STATUS.md) — the W-4 program: statuses, plan, tests
- [FK_INTEGRITY_MODEL.md](FK_INTEGRITY_MODEL.md) — the FK integrity canons
- [task 53](../tasks/2.1.0/53-tree-overlay.md) — the working journal
- [FOREIGN_KEYS.md](FOREIGN_KEYS.md) — the FK subsystem (consolidated)
