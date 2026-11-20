# XdStorage Transaction Model: commit, rollback, isolation — algorithms and failure families

**Status:** analysis verified against the working tree as of 2026-11-20
(branch `main-2.1.0-qwen3.8-foreign-key-fixes`); every claim carries a code
anchor. This document complements
[ANALYSIS-ARCHITECTURE.md](../ANALYSIS-ARCHITECTURE.md) §5 (Transaction Model) —
that section has the outline; this one has the algorithms and the failure
families.

---

## 1. Transaction lifecycle

```
beginTransaction(timeout)
  └─ XdStorageTransaction (id = UUID; globalTransactionId = null for a root tx)
       │  operations: load / save / update / delete / query
       │    └─ every mutation: mutator → cache-record change-slot (per-record, one slot)
       │    └─ btree operations: IN-PLACE mutation of the live canonical graph
       │                       + per-op undo registration (R-57 indexKey*ForRollback)
       ├─ commit()  → commitInternal(state, firstPhaseCommittedResources)
       └─ rollback() → rollbackInternal()
```

Key entities:
- `XdStorageTransaction` — the tx facade; holds `forCommit` (the queue of
  `ComparableResourceObject` = resource + priority), `internalTransactions`
  (R-41: nested/internal txs), `rollbackOnlyCause` (RBCAUSE: the first cause).
- `XdStorageResourceCache` — the per-resource MVCC contour: records with a
  SINGLE change-slot (object / newObject / prevObject / state / phase flags).
- `XdStorageCacheCommitProtocol` — the shared prepare/perform (CACHE2PC,
  task 34): the double-feed collector (the phase-2 refresh — deferred ids
  landing between the phases).

## 2. Commit (commitInternal) — the algorithm step by step

```
1. TREEOVERLAY: applyAllOverlays(this)        ← applies the staged overlay ops
                                                (task 53; see BTREE_MUTATION_OVERLAY.md)
2. skipOrWaitForInternalTransactions()        ← R-41: internal txs finish first
3. assignCommitSerial()                       ← R-45: one serial for all the tx's records
                                                (atomic visibility across resources)
4. ACQUISITION (reverse order): drain forCommit,
   lockForCommit per resource                 ← shared-first, unique-last (RECIDENT:
                                                inverted acquisition — otherwise every
                                                waiter inherits the stall)
   └─ lockForCommit: bounded retry ×2 (lock contention — a clean pre-phase failure)
5. PHASE LOOP (execution order: DATA → INDEX → ...):
   per resource: performFirstPhaseCommit(tx, record)
     ├─ user data: journal append (W-4a) + full rewrite at the checkpoint
     ├─ hash id-index: writeObjects(readUnsafe)  ← O(N) — the B1 wall (W4-DESIGN §1.3)
     └─ btree infra: per-node writes
   failure: hasError → break → exceptions[0]
6. GATE: exceptions[0] → throw (after the overlay undo+discard; task 53)
7. GATE: rollbackOnly → buildRollbackOnlyFailure (RBCOMMIT; after the overlay undo+discard)
8. state = PREPARED
9. PHASE 2: performSecondPhaseCommit (cache publish, version confirmation)
10. GATE ×2: exceptions / rollbackOnly (after the overlay undo+discard + dropAppliedUndos)
11. state = FINISHED; unlockAfterCommit for all
```

**The overlay resolution points (task 53)**: step 1 (the apply — before the
drain: the application registers the infra resources into the tx via the
mutator, and only that puts them into acquisition), steps 6/7/10 (the undo of
the applied ops on failure), the final drop after step 10.

## 3. Isolation: first-committer-wins

- **The stage-time check** (`canBeChangedByTransaction`): the version conflict
  is detected at markUpdate — the readTimestamp vs commitTimeNano (the LOSTUPD
  core, commit 7195872: "the true commit moment" in the 2-arg check).
- **The stage→commit window is NOT revalidated**: a record staged before a
  foreign commit publishes without revalidation → the **lost-update window**
  (diagnosed in MassFillSecond: "Рассинхрон 1 vs 0" — a stale staged state
  wiped a late insert). Fix = commit-time conflict revalidation in phase-1
  (the task 40/53 continuation).
- **READ COMMITTED for infrastructure**: the infra caches always read the last
  COMMITTED state (including the tx's own uncommitted records — "stayed
  invisible past the wait budget, serving absent", the 3s budget).

## 4. Rollback — three mechanisms (by mutation type)

### 4.1 Record rollback (domain records)
`XdStorageCacheRecord.rollback(tx)`: `object = prevObject; newObject = null;
state = previousState`. **The "prevObject aliasing" failure family (task 40)**:
prevObject is the raw reference to the canonical instance the caller already
mutated in place (`u.addGalaxy(g)` before `update()`) → the rollback restores
the same mutated collection → "any later committer makes the orphan reference
durable".
**The verified prototype fix (0 orphans ×5 runs, reverted with the overlay)**:
`prevSnapshot` — a clean clone captured at `newReadRecord` (BEFORE handing the
instance to the caller). Limitation: the clone via `unwrapAndClone` fails on
null-id nested references (the deferred-ID flow) — a plain copier
(`XdStorageObjectUtils.cloneObject`) or a lazy first-mutation snapshot is
required.

### 4.2 Compensations (R-57: the index keys)
`rollbackIndexKeyInserts/Deletes` — the reverse replay of the key operations
(an inserted key → delete; a deleted key → restore). The lessons:
- **The ordering**: the compensations AFTER the general resource rollback —
  the compensations' own staged changes are wiped by an earlier infra-cache
  rollback (R-57: "the compensations after the general rollback are final").
- **The HASH order of resources in rollbackInternal** — nondeterminism: an
  index-key compensation does a load-by-reference of the restored record; with
  INDEX-before-DATA ordering it fails with "reference cannot be null"
  (reproduced 2026-11-20 on the deferred-ID test). The fix prototype: sort the
  rollback loop (DATA before INFRA via `isInfrastructureResource()`).
- With TREEOVERLAY: the compensations for the overlay trees are redundant
  (the stage is keyed under the overlay key; the discard removes it) — see
  BTREE_MUTATION_OVERLAY.md §5.

### 4.3 Infra fresh-parse (TREEFILE, task 41)
The infrastructure records (trees/nodes) are aliased live canonical instances;
their in-place structural mutations survive the record rollback (prevObject
aliases the live instance). Two compensation attempts (2026-10-25) proved the
mutated in-memory graph cannot be repaired in place. The solution:
`recoverInfraFromCommittedFiles()` — the per-tree-group reload from the
committed files (the file = the pre-tx truth: the full write-out happens only
at commit). **The gate**: only a storage that has been single-transaction-
thread for its whole life (`infraThreadHighWaterMark ≤ 1`) — under
concurrency the canonical-graph swap forks the graphs (the W-4 boundary).
With TREEOVERLAY: the overlay trees are excluded from the recovery (their
rollback is the in-memory discard+undo, concurrency-safe; see
BTREE_MUTATION_OVERLAY.md §6).

## 5. The failure-family registry (signatures)

| Family | Signature | Mechanism | Status |
|---|---|---|---|
| **Lost-update window** | "Рассинхрон! expected N was M" — a stage without commit-time revalidation | stage→commit without a re-check | open (the task 40/53 continuation) |
| **prevObject aliasing** | survivors after the rollback ("a galaxy with ZERO commits") | the canonical alias + the in-place mutation before update | the prevSnapshot prototype verified (0 orphans ×5), reverted with the overlay |
| **Task-41 structural** | "B+Tree node N is referenced but missing", collapse-rollback | the in-place tree mutation + the unsound compensation | task 41 open; the overlay prototype solves it (rollback=discard) |
| **Infra visibility** | "stayed invisible past the wait budget (3000 ms) — serving absent" | infra READ COMMITTED: own uncommitted writes invisible for 3s | by design; never wait inside the phases |
| **Rollback-only early-return** | the applied-undo never runs | the manager: isRollbackOnly → return before rollbackInternal | closed in the overlay prototype (the undo in commitInternal's error paths) |
| **Commit-lock convoy** | lockForCommit "Timeout Ns expired" on the shared trees | each commit holds the shared trees through both phases | open; the fix = the commit-lock granularity |

## 6. Interconnections with the other subsystems

- **The FK engine** (see FOREIGN_KEYS.md): the veto = an exception from the
  FK resource's phase-1; with the overlay engine the apply runs before the
  phases → the veto path must perform the overlay undo (closed in the
  prototype; see the "Rollback-only early-return" family).
- **The R-45 commit serial**: one serial — the atomic visibility; the recovery
  replays only the COMMITTED serials (an uncommitted apply is discarded — the
  disk converges to the committed state).
- **The sweeper**: the incremental rollback-only marker; "a late/racing
  rollback inverted a committed insert" (R-58: the rollback of a COMMITTED
  record is ignored).
- **TREEOVERLAY** (task 53): see BTREE_MUTATION_OVERLAY.md — the full overlay
  engine design and the remaining integration layers.
