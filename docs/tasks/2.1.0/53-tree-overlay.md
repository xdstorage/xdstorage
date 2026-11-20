---
title: "[2.1.0][TREEOVERLAY] B+tree per-tx overlay: sound rollback + per-op release of the tree change-slot (W4-DESIGN, task 41 + the concurrent-write unlock)"
labels: [2.1.0-train, P0, engine, w4-design, performance]
status: in-progress
---

## Summary

Move the B+tree mutations (the id-indexes + the FK trees) from the model
"in-place mutation + the tree record's change-slot held until commit" to the
**per-tx overlay** model:

- a TX's key mutations (`insert`/`delete`) stage into the tree's per-tx overlay;
- the physical tree is not mutated until the commit;
- reads (`find`/`has`) merge the physical snapshot with the writing tx's
  overlay (read-your-own-write);
- **rollback = discard the overlay** — sound by construction (nothing to
  reverse; the lessons of the two failed task-41 compensations are not used);
- **commit = the batch application of the overlay** in the owning resource's
  phase-1 (inside the INDEX_RESOURCE_ORDER window) through the public btree
  path with the per-op undo (undo(insert k) = delete k; undo(delete k,v) =
  insert(k,v) — single-key operations, not structural transforms).

## Why (quantified, FKBENCH Step 0 — docs/tasks/2.1.0/50)

The default tx timeout of 3s, the MassFill profile, each worker owning its
universe (the contention only on the class-level B+tree resources):

```
fk=OFF t=1: 167.4 tx/s p50=5ms  →  fk=OFF t=10: 1.2 tx/s p50=12s  (a ~140× collapse)
fk=ON  t=1: 138.8 tx/s p50=7ms  →  fk=ON  t=10: 0.8 tx/s p50=18s1
```

The failures name the class-level **id-index** btree resource files in the
FK-off arm too — the choke sits in the write core; the FK trees are the second
layer of the same mechanism. The overlay closes both layers. The expected
effect: the concurrent writes 1.2 → 100+ tx/s, p99 12-21s → ms; the removal of
the Semaphore workaround from MassFillSecond.

## The semantic shifts (recorded)

1. The cross-transaction duplicate id: the detection moves from op-time to
   commit-time (first-committer-wins on the id, like a unique index in a
   DBMS). The tx's own duplicate is still caught at op-time (has() reads the
   overlay + the physics).
2. Uncommitted pairs/keys are invisible to foreign txs until the commit
   (today they are physically visible in the tree — effectively
   READ-UNCOMMITTED at the tree level; this makes it READ-COMMITTED).
3. The overlay application — one tx at a time (the tree's slot is taken for
   the application, not for the tx duration). An application failure: the
   per-op undo in reverse order + an honest veto (the tx rolls back entirely).

## Plan

- [x] Phase 0: the study (the btree surface, INDEX_RESOURCE_ORDER, the
      find/has callers)
- [x] Phase 1: the overlay in XdStorageBTree (stageInsert/stageDelete/the
      overlay merge for find/has; the later-wins staging; isOverlayMode by the
      id name: "id_index" / "*_foreign_key")
- [x] Phase 2: the apply in commitInternal BEFORE the resource-queue drain
      (the storage captured at the staging; the application registers the
      infra resources into the tx via the mutator — only that puts them into
      acquisition/phases; the files are written by the resources' own
      phase-1). SELF-DEADLOCK lesson: applyOverlays must NOT take the tree
      write-latch (it is non-reentrant; doInsert/doDelete latch themselves).
      The rollback hook: rollbackInternal → discardAll.
- [x] Phase 2 verification: the FK estate 372/0f/0e (7 bad after the first
      enablement — 1) the Recursive update: overlays.put inside the same
      CHM's computeIfAbsent — removed; 2) the merge later-wins: stageDelete
      did not prune stagedInserts — fixed; 3) the apply ran in the phase-1 of
      the FK/index resources = AFTER the infra resource's phases ("Illegal
      state of record" — proven twice) — moved to commitInternal; 4) the
      storage.load of the tree in phase-1 = 3s invisibility stalls — removed
      (the instance registry)).
- [ ] Phase 3: revive XdStorageTreeStructureRollbackTest (RED → GREEN)
- [ ] Phase 4: the FKBENCH rerun (the target: t=10 ≥ 100 tx/s, p99 < 100ms),
      the Semaphore removal from MassFillSecond, the FK estate + the full
      gate ×3
- [ ] Phase 5: the documentation (FOREIGN_KEYS.md §9, BENCHMARKS.md, the
      W4-DESIGN bottleneck ledger)

## The remainder (2026-11-20, the end of the session): MassFillSecond — the task-40 family

The FK estate 372/0/0 on the overlay engine; MassFillFirst green; MassFillSecond —
the intermittent surviving systems (1-2 per run, worker-origin): their galaxies
were deleted by the worker delete-ops, the walk saw/deleted its own, the systems
of foreign galaxies survived = **the task-40 canonical-mutation contamination**
("a rolled-back tx's list mutation survives + a later committer makes the
orphan durable", "a galaxy with ZERO commits" — the documented W-4-class
domain-record remainder). The overlay cured the TREE half of W-4; the
domain-record half (task 40: the read-side cloning / the prevObject aliasing)
is the next block. The SURVIVING probe left in the test (the next session's
diagnostics).

## The lessons the design does NOT use (all from task 41)

- we do not reverse the structural transforms (split/move/collapse) — their
  reverse undo is unsound (the rebalance budgets, the "in-memory mutated node
  graph");
- we do not clean with the in-memory children sweeps (they deleted live nodes);
- we do not guess by instances (the registry is first-instance-wins;
  canonicalTree = stale);
- we do not apply the overlay after the phases (the POINT-6 failure "Illegal
  state of record to commit") — only inside the owner's phase-1.

## Verification

- The FK estate 372 ×3; the full gate ×3; FKBENCH: fk=ON t=10 ≥ 100 tx/s;
  XdStorageTreeStructureRollbackTest collapse-rollback GREEN (the @Disabled
  removed); MassFillFirst/Second green WITHOUT the Semaphore.

Estimate: 2–4 sessions (the core + the integration + the verification).

## SESSION OUTCOME 2026-11-20 (evening) — the integrity closed, the performance is the next layer

**Closed (all verified):**
- task 40 READ-SNAPSHOT: `XdStorageCacheRecord.prevSnapshot` — a clean
  pre-tx clone captured at newReadRecord (BEFORE handing the instance to the
  caller); markUpdate/markDelete set prevObject = prevSnapshot. The canonical
  instance contamination ("prevObject aliasing", the surviving orphans)
  eliminated: MassFillSecond — 0 survivors ×3 (was 1-4 intermittent).
- task 53 apply→rollback atomicity: APPLIED_UNDOS — the undo journal of the
  commit-applied overlay ops; rollbackInternal: the undo-replay AFTER the
  resource rollback (the R-57 lesson: the compensations after the general
  rollback are final; an earlier undo was wiped by the infra-cache rollback);
  the commit success → dropAppliedUndos.
- The FK estate 372/0f/0e; MassFillFirst green; MassFillSecond: the integrity ✓
  (0 survivors, all the integrity asserts pass).

**Open (the next layers, with traces):**
1. **The lost-update window stage→commit**: a delete-TX stages
   update(universe) (a snapshot without the shard-mate's late insert), no
   commit-time version revalidation → the stale staged state wins, the wiped
   insert = "Рассинхрон! expected 1 but was 0". The fix = the commit-time
   conflict revalidation in phase-1 (the record version re-check before the
   file writes) — the write core, task 40/31-adjacent.
2. **The commit-lock convoy**: each commit holds the shared trees through both
   phases → the queues to the timeouts; lockForCommit 20s is marginal. The
   fix = the commit-lock granularity (holding only during own apply) — W-4.
3. **The read-side snapshot cost**: every load clones the pre-tx state
   (unwrapAndClone) — ~2× the read cost; the iterations slowed, the delete
   phase reached only at a window ≥15s. The optimization = the lazy snapshot
   on the first mutation (the wrapper interception) — the generated code.
The MassFillSecond state: the integrity ✓ (0 survivors), the liveness ✗ (the
deletes/audit depend on the convoy) — the test is red, honestly. The config:
the timeout 20s, the window 10s, the backoff 2s×attempt between the delete
attempts (the R-54 protocol).

## THE SESSION DECISION (the night of 2026-11-20): the prototype preserved, the core reverted to HEAD

**The prototype reached 365/372** (all the "mechanical" integrations closed),
but the final three layers exposed the next W-4 depth, not safely closable in
one session:
1. The internal-TX split: the btree ops stage under the internal tx id (R-41),
   the resolution (apply/undo) under the outer's; the veto path leaves the
   applied deletions without undo (the deferred-id test's child vanished).
   The fix: the owner-id keying (overlayResolutionOwnerId = globalTransactionId
   ?? transactionId) + a default method on IXdStorageTransaction + the bridge
   in the manager — implemented, compiles, BUT the resolver stayed red (a
   deeper interaction: the rollback compensations
   rollbackFailedCommitInternal/rollbackIndexKey* and their load-by-reference).
2. The lost-update window stage→commit (no commit-time version revalidation) —
   "Рассинхрон".
3. The commit-lock convoy (each commit holds the shared trees through both
   phases) — the 8-20s timeouts.
4. The read-snapshot cost: ~2× the load (a clone per read-record), the delete
   phase starves at a window <15s. The optimization: the lazy snapshot on the
   first mutation (the wrapper interception).

**The prototype preserved WHOLE**:
docs/tasks/2.1.0/53-tree-overlay-prototype.patch (336 lines: the overlay core
+ the applied-undo registry + the later-wins + the commitInternal placement +
the owner-id keying skeleton). All the battle scars and fixes — above in this
file.

**Reverted to HEAD**: the btree, the cache record, the transaction (+the
interface), the manager, XdStorage, MassFillSecond — the last verified
green baseline: the FK estate 372/0/0, MassFillFirst green, MassFillSecond —
the task-40 survivors (pre-existing, documented; NOT overlay-dependent —
confirmed by a HEAD run: the same 2 survivors).

**The next session (a fresh context):** in order — (1) the internal-TX bridge
(the resolution over the id set: the outer + the internals from
internalTransactions); (2) the rollback compensations under the overlay (the
R-57 key compensations vs the overlay discard); (3) the veto-path apply→undo;
(4) the FKBENCH (the target: t=10 ≥ 100 tx/s); (5) the Semaphore removal.


## SESSION 2026-11-21: THE OVERLAY IS GREEN — the FK estate 372/0/0, the collapse-rollback GREEN

**The re-apply + two fixes closed everything:**

1. **The systemic prevSnapshot lifecycle bug (the resolver/reopen cluster):**
   the snapshot was captured only at newReadRecord, but a record's lifecycle
   continues: insert (newInsertRecord — prevSnapshot null) → the commit
   publish → a foreign markDelete → rollback: `object = prevObject = NULL` →
   THE RECORD DESTROYED (the deferred-id child load → null at TX4).
   **The fix — the role split:** `prevObject` keeps the HEAD semantics (the
   canonical aliased reference — the identity/serving contracts intact: the
   invariant tests pin assertSame, the MVCC serving hands it to the readers);
   `prevSnapshot` = the clean clone (captured at newReadRecord, or lazily at
   markUpdate/markDelete when absent — never null for a live object) used ONLY
   by the rollback's content restore: `fillObject(prevSnapshot, object)` — the
   IN-PLACE field-wise copy into the canonical instance (the identity
   preserved, the mutations reverted). The publish and the rollback clear
   prevSnapshot (the staleness). fillObject already existed
   (XdStorageObjectUtils — the same-type graph fill).
2. **The probe length-safety:** `getTransactionId().substring(0, 8)` threw on
   the mock txs' short ids (4 chars) → 43 phantom errors in the mock-harness
   classes. All the probes go through shortTx() (length/null-safe).

**Verified (2026-11-21):** the resolver 5/5; the reopen trio 3/3;
`XdStorageTreeStructureRollbackTest` 2/2 GREEN (the collapse-rollback —
DISABLED-RED at HEAD — green naturally on the overlay: the soundness claim
proven); the cache/protocol/MVCC invariant suites 37/0/0; **the FK estate
372/0/0**. The full quick-gate: running at the update time.

**Still open (honest):** the commit-lock convoy (the MassFillSecond Semaphore
stays — its removal waits for the convoy work); the lost-update window
stage→commit; the read-snapshot cost (~2× the load); the aliasing residue for
markUpdate on a committed record whose read registered no snapshot (the lazy
capture at the mark takes the already-mutated state — the full fix = the
per-tx read-time capture in the cache read path; the MassFill survivors are
covered by the read-registration capture — verify on the next MassFill run).


## VICTORY 2026-11-21 (продолжение сессии): конвой уничтожен — цель достигнута

**THE CONVOY DESTROYED — the root was an ACQUISITION DEADLOCK, not the lock hold time:**
the commit-order values tie across the whole INDEX band (all the tree/node resources
share one order); `Collections.reverseOrder()` left the tie order ARBITRARY per
transaction → A held node-3 waiting node-12 while B held node-12 waiting node-3 —
a deadlock broken only by the lock-timeout cycles (the 12s waits = 4 × 3s; the
sweeper cascades). **The fix: the TOTAL acquisition order** — the tie-break by the
resource id (globally unique) makes the order identical in every transaction: the
convoy became a queue, never a cycle.

**The FKBENCH result (the same conditions as the Step-0 collapse measurement):**

```
              HEAD (Step 0)            NOW (overlay + total order)
fk=OFF t=1:   167.4 tx/s p50=5ms   →  170.2 tx/s p50=5ms    (no regression)
fk=OFF t=5:     2.2 tx/s p95=12s   →  160.2 tx/s p95=38ms   (73×)
fk=OFF t=10:    1.2 tx/s p50=12s   →  158.4 tx/s p50=63ms   (132×)
fk=ON  t=1:   138.8 tx/s p50=7ms   →  141.4 tx/s p50=7ms    (no regression)
fk=ON  t=5:     2.2 tx/s p95=15s   →  115.6 tx/s p95=60ms   (53×)
fk=ON  t=10:    0.8 tx/s p50=18s   →  121.2 tx/s p50=82ms   (151×)
```

**The ≥100 tx/s target: MET (121–158 tx/s at t=10). p99: 12–21s → 84–113ms.
Zero failures (no sweeper kills, no lock timeouts).**

**MassFillSecond WITHOUT the semaphore ×3: PASS — inserts 392–423 (was 7–10, 40×),
deletes 144–416, the task-40 survivors = 0.** The semaphore permits 1 → 64 (10
threads physically cannot exhaust — the serialization constraint removed).

**Also landed this session:** the read-only-resource commit filter (the reads skip
the commit locks/phases; the FK resource always stays for the veto validations);
the live-canonical-instance serving in the infra invisibility check
(`record.getObject() != null` → serve immediately — the tree-level
READ-UNCOMMITTED, the pre-existing in-place semantics; the 3001ms phase-1
visibility wait eliminated); the infra identity pass-through in newReadRecord
(the node clones on the materializations = the CPU explosion).

**The BTreeStress phantom family — PRE-EXISTING, NOT an overlay regression:**
the HEAD baseline measured by the stash experiment: **24810 phantoms** (the
overlay: 27.7k — the same race's variance; the 334 in the early run was the
lucky draw). The family = the R-56/multi-scan race: the hand-over-hand chain
walk releases the tree read latch at the first leaf; a split in the
prewarm window (the firstLeaf capture → the prewarm → the walk) moves the keys
to the new leaves while the walk traverses the STALE chain → the intermittent
0-results. The latch-held-walk experiment FAILED (27k phantoms — the RW-latch
writer starvation + the node latch semantics) and was REVERTED. The proper fix =
the version-validated retry with the resettable viewer or the snapshot-leaf
chain — THE NEXT SESSION's dedicated work item (the test now fails loudly at
its assertion instead of hanging: the bounded future.get() landed).


## FINAL GATE 2026-11-21 (night): 1319/1f/1e — the overlay CLEARED 2 pre-existing failures, zero regressions

**MassFillSecond ✓ CLEARED** (was red — the survivors/audit), **Multithreads ✓
CLEARED** (was ERROR — the lazy-load) — the convoy destruction + the role-split
fixed them. **Remaining (the pre-existing families, the next sessions):**
- the BTreeStress phantoms (the R-56/multi-scan race — the stash-verified HEAD
  baseline 24810 phantoms; the latch-held experiment failed and reverted; the
  proper fix = the version-validated retry with the resettable viewer);
- the W2Reopen reopen-desync (the task-41 family at HEAD — the structure
  validator "листьев только из корня/из цепочки"; investigate why HEAD has it
  red now).

**The new test coverage:** `XdStorageBTreeOverlayTest`
(`io.github.xdstorage.overlay` — THE PACKAGE MATTERS: the entities in the
`btree` package are treated as INFRA by the package check!) + the TOP-LEVEL
entities `OvParent`/`OvChild` (the nested can't be runtime-compiled — the
house rule re-confirmed): the deadlock pin ACTIVE GREEN (the disjoint parent
records + the shared trees) + 2 honest @Disabled pins (the visibility contract
+ the rollback restore — the WRAPPER WRITE-THROUGH ALIASING channel, the next
W-4 layer: the SmartCloner/reference-provider de-aliasing).

**Also landed:** the read-time snapshot capture (`captureSnapshotIfNeeded` at
the cache read registration — the rollback half of the task-40 channel closed);
the live-canonical serving (the 3001ms visibility-wait eliminated); the
read-only-resource commit filter; the infra identity in newReadRecord (the
node clones on the materializations = the CPU explosion); the bounded
future.get (the test hygiene — the maintainer's timeout directive).
