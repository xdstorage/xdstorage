# FK Integrity Model — the canons, the veto families, the test estate

**Status:** the canons arbitrated by the architect on 2026-11-20; the estate
is 372 tests (372/0f/0e on the HEAD engine; see the performance limitation —
[W4_WRITE_PATH_STATUS.md](W4_WRITE_PATH_STATUS.md)). The subsystem overview:
[FOREIGN_KEYS.md](FOREIGN_KEYS.md); the veto mechanics:
[TX_COMMIT_ROLLBACK_MODEL.md](TX_COMMIT_ROLLBACK_MODEL.md).

---

## 1. The integrity canons (arbitrated, pinned by tests)

| # | Canon | Pinning test |
|---|---|---|
| C1 | **delete-child-alone → VETO**: deleting a child while the parent still references it is forbidden | `DeleteScenarios.testDeleteOnlyOneChildThrows` (btree+hash) |
| C2 | **dangling-ref end-state → VETO**: the identical end state = the identical verdict (the operation order is irrelevant) | `UpdateScenarios` #6 (flipped from success to veto, 2026-11-20) |
| C3 | **remove-from-collection ≠ delete-object** (Rule 4): dropping the reference is legal; the object survives | `UpdateScenarios` #4 (remove all + update → success) |
| C4 | **remove + delete + update → success**: the legitimate flow — delete the child AND drop the reference, the commit is clean | `DeleteScenarios.testDeleteOneChildAndUpdateParentSuccess`, UpdateScenarios 4a |
| C5 | **delete-parent-alone → VETO**: deleting a parent with live children is forbidden | `DeleteScenarios.testDeleteParentAloneThrows` |
| C6 | **remove + delete ANOTHER child with a live reference → VETO** | `UpdateScenarios` #6 (btree+hash, flipped 2026-11-20) |
| C7 | **deferred-ID**: a child with an unsaved parent → VETO at commit; parent-first / a manual setId in the same tx → resolves | `FxForeignKeyDeferredIdResolverTest` 5/5 |
| C8 | **rollback leaves no trace**: no records, no id-index entries, no overlay pairs | the MassFill tests, `XdStorageBTreeFullCollapseTest` |

**The arbitration key** (resolves "two pins conflict"): compare the **end
state** (who references the deleted record at commit), not the operation
order.

## 2. The veto families (who fires from where)

| Family | Trigger | Failure signature |
|---|---|---|
| DeleteChildAlone (counter) | delete(child), the parent not updated | "Not all foreign keys are **deleted**" (the del-counter > 0 at phase-1) |
| DeleteParentAlone | delete(parent), the children alive | "Not all foreign keys are **deleted** for relation parent#field → child" |
| OrphanInsert | save(child) with an unsaved parent | "Cannot persist FK (...) parent record does not exist — parent must be persisted before the child" |
| Deferred unresolved | a pair with a null id at phase-1 | "the deferred pair ... has unresolved ids at commit" |

**The mechanics**: the `insertWaitingIdentifiers`/`deleteWaitingIdentifiers`
per-tx counters; the FK resource's phase-1 throws at ≠ 0. The counter
resolution — the dissolution (unregisterForeignKey: the id-index entry + the
FK pairs both directions + cacheDelete) via the update(parent)-diff or
delete(parent).

## 3. The operation flows and their mandatory steps

### 3.1 insert(parent) with AutoSave children (the cascade)
1. save(parent) → the cascade: the children upsert (has → update, else save —
   the save-as-upsert trap: a repeated save of the same id = "is exists").
2. FK: registerForeignKey(parent, child) per pair → STAGE (overlay) /
   physically (pre-overlay).
3. A duplicate pair: idempotent (the merged-find contains → no-op).

### 3.2 delete(child) alone (without the parent)
1. The child's id-index entry: a staged delete (overlay).
2. FK: the income-DELETE → readByChildObjectId → the pair found →
   waitForParentDelete (armed on the parent's record, del-counter++).
3. commit: the FK phase-1: counter > 0 (the parent never updated its
   collection) → **VETO**.
4. The legitimate removal: delete the child + update(parent) (drop the
   reference) → the update-diff dissolve → counter-- → the commit is clean.

### 3.3 delete(parent) with a subtree
1. Per child of the subtree: delete (bottom-up).
2. delete(parent) → dissolve the pairs → the commit is clean.
3. **The violated order** (the parent without its subtree) → the
   DeleteParentAlone VETO.

### 3.4 update(parent) with a composition change
1. removed = the tree children − the new collection → dissolve
   (unregisterForeignKey).
2. added = the new collection − the tree children → registerForeignKey.
3. A removal without deleting the object → **VETO** (the Rule-4 exception:
   the pair must be dissolved explicitly via the child's deletion).
4. **The lost-update window**: the update stages from a snapshot; a foreign
   commit between the stage and the commit is not revalidated → see
   TX_COMMIT_ROLLBACK_MODEL.md §3.

## 4. The test estate (the pinning)

```
foreignkey/
├── policies/{btree,hash}/           8 classes × 14 = 112  (the FNKEY-6 canon: the full lifecycle)
├── index/{btree,hash}/
│   ├── ForeignKeySaveScenariosTest       (8)   — the insert scenarios
│   ├── ForeignKeyDeleteScenariosTest     (8)   — the delete scenarios (C1, C5)
│   ├── ForeignKeyUpdateScenariosTest     (12)  — the update scenarios (C2, C3, C6)
│   ├── ForeignKeyDifferentCollectionsTest(48)  — Set/Map/Array × the policies
│   ├── ForeignKeyManualId*               (16)  — the manual-ID flows
│   ├── ForeignKeyChildFirstScenariosTest (4)   — the child-first flows
│   └── ForeignKeyReferenceScenariosTest  (2)   — the back-refs
├── XdStorageForeignKeyDeletionTest       (3)   — the real storage, C1/C5
├── FxForeignKeyDeferredIdResolverTest    (5)   — C7 (parent-first, the veto, idempotent)
├── FxForeignKeyVetoSemanticsTest         (6)   — the TX isolation, the veto canons
├── OutcomeRelationsForeignKeyTest        (5)   — the outcome flows
├── XdStorageForeignKeyReopenKeyTypeTest  (3)   — the reopen key types (A2.3)
└── ... (the full registry: 372 tests, 56 classes)
```

**The gate**: `mvn test -Dtest="io.github.xdstorage.foreignkey.**.*Test"
-Pquick-gate` → 372/0f/0e. A failure = a release blocker.

## 5. The performance limitation (honest)

The concurrent FK-heavy write profile: **167 → 1.2 tx/s** (the ~140× collapse,
FKBENCH Step 0, task 50) — the choke = the tree record's change-slot held
until commit (the id-indexes — FK-independent; the FK trees — the second
layer). The solution — the overlay engine (task 53, the prototype 365/372;
see [BTREE_MUTATION_OVERLAY.md](BTREE_MUTATION_OVERLAY.md)) — **is not
release-ready** (three integration layers; the plan:
[W4_WRITE_PATH_STATUS.md](W4_WRITE_PATH_STATUS.md)).

## 6. Related documents

- [FOREIGN_KEYS.md](FOREIGN_KEYS.md) — the subsystem (consolidated), §9 — the limits
- [TX_COMMIT_ROLLBACK_MODEL.md](TX_COMMIT_ROLLBACK_MODEL.md) — the commit/rollback
- [BTREE_MUTATION_OVERLAY.md](BTREE_MUTATION_OVERLAY.md) — the overlay (task 53)
- [W4_WRITE_PATH_STATUS.md](W4_WRITE_PATH_STATUS.md) — the W-4 program
- task 42 (the FK feature), task 50 (FKBENCH), tasks 40/41 (the W-4 core)
