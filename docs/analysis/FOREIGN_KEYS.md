# Foreign Keys — the subsystem document

**Consolidated 2026-11-16** — this single document replaces and supersedes the six
scattered `FOREIGN_KEY_*.md` analysis documents (SITUATION_ANALYSIS, FIX_ANALYSIS,
TASKS, REDESIGN, REDESIGN_INTEGRATION, LISTENERS_WAITERS — all deleted). Every claim
below is verified against the working tree at this date; the historical design docs
assumed an integration that since landed differently in one important way (§5, the
contour rule) — this document describes what IS, not what was planned.

**The feature spec and acceptance record** is [2.1.0 task 42](../tasks/2.1.0/42-foreign-keys.md)
(FNKEY, status: done). The write-set integration record is commit `9964901`
("FNKEY: FK pair write-set integrated into the 2PC"). The unmeasured FK cost is
[task 50](../tasks/2.1.0/50-fk-cost-benchmark.md) (FKBENCH).

---

## 1. What the subsystem is

Foreign keys enforce referential integrity between parent/child entity pairs across
the save/update/delete lifecycle. A relation exists when:

- a field in the **parent** class is annotated `@XdStorageAutoSave` (the *outcome*
  relation — the parent owns its children), or
- a field in the **child** class is annotated `@XdStorageParentObject` (the *income*
  relation — the child points at its parent).

Each relation is persisted as a **dual B+tree** (`front` = parent→child keys,
`reverse` = child→parent keys); the FK resource id is derived from
(parent class, child class, parent field name) via the naming service. The veto
semantics (all three rejections are hard failures at commit):

- saving the same (parent, child) pair twice in one relation → **rejected**;
- deleting a child whose parent still references it → **rejected**;
- deleting a parent still referenced by a child → **rejected**.

**Configuration:** `enableForeignKeys` — **default ON**;
`XdStorageConfig.builder().setEnableForeignKeys(false)` disables the engine
(the annotations keep parsing; the FK engine is not registered).

Embedded value objects (`StoreWithParentObject`) bypass FK validation **by design**
(documented limitation — §9).

---

## 2. Components (`src/main/java/io/github/xdstorage/foreignkey/`, 14 classes, ~2,766 lines)

| Component | Lines | Responsibility |
|---|---|---|
| `XdStorageForeignKeyEngine` | ~400 | Lifecycle dispatcher — routes INSERT/UPDATE/DELETE events to the FK resource (income + outcome relations); unwraps runtime proxies at entry; exposes `commitAllFxPairs`/`releaseFxPairs` to the 2PC |
| `XdStorageForeignKeyResource` | 1,100 | FK pair persistence: dual B+tree (front + reverse), deferred-ID waiters, `pendingFkRegistrations`, orphaned-children registration, write-set staging, the read-through overlay |
| `XdStorageForeignKeyCache` | 180 | **The shared FK write-set** — per-transaction keys, INSERT/DELETE intents (later-wins), phase-gated lifecycle, structural validation |
| `XdStorageForeignKeyCacheRecord` | 107 | Per-pair phase state machine (PREPARE_COMMIT → PERFORM_COMMIT → COMMIT / discard) |
| `XdStorageForeignKeyPair` | 120 | One FK intent: `(frontId, backId, relation, type)`; `key()`, `writeToTree()` (front + reverse). Argument semantics per the outcome/income path (§7, A2.1/A2.2) |
| `XdStorageForeignKeyRegistry` | ~150 | Canonical relation topology — one relation per (parent, child, field) triplet; builds outcome + income relation sets; `registerOrphanedChildren` |
| `XdStorageForeignKeyResourcesManager` | 160 | Resource factory + lifecycle (lock/create/register); **owns the cache** (`getForeignKeyCache()`); orphaned-children fan-out |
| `XdStorageForeignKeyChangeRegistry` | ~380 | Deferred-ID waiters: per-transaction `AtomicLong` counters (`insertWaitingIdentifiers` / `deleteWaitingIdentifiers`) + the update/delete listeners registered on data/index resources |
| `XdStorageForeignKeyRelation` | 82 | Relation descriptor: `classFrom`, `classTo`, `reverseRelation`, `parentAnchored`, `field` |
| `XdStorageForeignKeyViolationException` | 48 | The veto exception (transaction + relation + offending ids) |
| `XdStorageForeignKeyObjectChangeType` | ~30 | Enum: INSERT / UPDATE / DELETE |
| `IXdStorageForeignKeyEngine` | ~40 | SPI — includes `commitAllFxPairs(tx)` / `releaseFxPairs(tx)` (the 2PC hooks, §4) |
| `IXdStorageForeignKeyRegistry` | ~40 | SPI — parentClassInfo / childClassInfo / parentField / orphaned children |
| `IXdStorageForeignKeyResource` | ~90 | SPI — insert/update/delete/read + the staging/validate/promote contract |

Note: earlier documents and some git history use the old `FxStorage…` prefix; the
package was fully renamed to `XdStorage…` (no `FxStorage` classes remain).

---

## 3. Lifecycle — how FK pairs flow

```
storage.save/delete(object, tx)
  └── commit → XdStorageCacheCommitProtocol (§4)
        └── runCommitPhase marks each changed entity record and fires the listener
              └── XdStorageTriggerManager → XdStorageForeignKeyEngine.process(object, type, tx)
                    ├── incomeRelations (child's @XdStorageParentObject)
                    │     └── resource.insert(parentId, childId, tx)
                    ├── outcomeRelations (parent's @XdStorageAutoSave)
                    │     └── resource.insert(childId, parentId, tx)   ← arguments swapped (§7 A2.1/A2.2)
                    └── DELETE paths stage DELETE intents + arm the delete-waiter listeners
```

`resource.insert` does two things atomically (the CONTOUR-CORRECT FORM, §5): stages the
pair intent into the write-set (`XdStorageForeignKeyCache.cacheInsert`/`cacheDelete`)
AND performs the pair's B+tree write at operation time (invisible-until-commit through
the node caches' MVCC; rolled back with the transaction through the W-4a journals).

---

## 4. The 2PC integration — the write-set and the 8 POINTS

The FK pair lifecycle is phase-gated through the same 2PC pipeline as entity records.
**All 8 integration points are implemented** (commit `9964901`); the POINT numbering
below is cited by name in the code comments — keep it stable.

| POINT | Where | What happens | State |
|---|---|---|---|
| 1 | Engine → resource (income/outcome insert) | The resource stages an INSERT intent into the write-set (`cacheInsert`) + performs the in-contour tree write | ✅ |
| 2 | Resource `performFirstPhaseCommit` | The **full validation trio**: `validatePendingFks(tx)` (manual-ID parents) + `cache.validateAllForTransaction(tx)` (write-set structural validation) + `changeRegistry.checkAllNotificationsReceived(tx)` (waiter counters) | ✅ |
| 3 | Engine PERFORM (update path) | Pair bookkeeping for updates rides the parent-update diff; child updates stage no pairs on their own | ✅ |
| 4 | Resource `performSecondPhaseCommit` | `cache.promoteAllForTransaction(tx)` — all staged intents promoted | ✅ |
| 5 | DELETE paths (dissolution) | Dissolution stages DELETE intents (later-wins over earlier INSERT intents of the same pair) | ✅ |
| 6 | `XdStorageCacheCommitProtocol.commit()` | `foreignKeyEngine.commitAllFxPairs(tx)` — promote + write-set close, **after all entity records committed** | ✅ |
| 7 | `XdStorageCacheCommitProtocol.rollback()` | `foreignKeyEngine.releaseFxPairs(tx)` — the write-set is discarded | ✅ |
| 8 | Resource `release(tx)` | Per-transaction write-set + waiter-counter + listener cleanup | ✅ |

**Design decision — waiters and `pendingFkRegistrations` stay in the resource/ChangeRegistry:**
they validate UNRESOLVED pairs (deferred ids, manual-id parents) that have nothing to
stage yet; the write-set covers the RESOLVED pairs. Merging them into the cache was
considered and rejected (the REDESIGN doc's migration proposal is superseded by this).

**The read-through overlay:** `readByParentObjectId` / `readByChildObjectId` read the
B+tree AND overlay the transaction's staged intents, so a transaction sees its own
uncommitted pairs (read-your-own-read) without seeing other transactions'.

**The Cloner fix (same commit):** `XdStorageSmartCloner.fillAndWrap` now flips the
`reference__` marker on the hydrated reference wrapper. Before the fix, a later
`cloneAndWrap` re-packed the STORED state over the caller's mutations — the
update-window read resurrected `setParent(null)` and produced the ClearLink-family
vetoes.

---

## 5. The contour rule (why tree writes happen at operation time)

The original REDESIGN proposal assumed FK trees are dumb storage that can be written
once at COMMIT (after the 2PC validates). **That is wrong for this engine** — proven in
the first integration run: the FK trees are *infrastructure caches with their own 2PC
contour*. Mutating them after their resources' phases ran orphans the tree-object
records ("Illegal state of record to commit"; the orphaned record poisoned every later
committer).

The implemented rule:

- the pair's tree write happens at **registration time** (inside the contour window);
- it is invisible-until-commit through the node caches' MVCC;
- it rolls back with the transaction through the W-4a journals;
- it is made permanent by the trees' own commit — which **is** the commit phase of the
  pair's lifecycle;
- the write-set adds what the trees do not provide: the read-through overlay, the
  phase-gated intent state, the structural phase-1 validation, and the explicit
  rollback/release hygiene.

---

## 6. Deferred-ID waiters and the listener estate

`XdStorageForeignKeyChangeRegistry` carries the per-transaction observer machinery:

- **`insertWaitingIdentifiers` / `deleteWaitingIdentifiers`** — per-tx `AtomicLong`
  counters. Phase-1 (`checkAllNotificationsReceived`) vetoes the commit while a counter
  is positive ("Not all foreign keys are registered/deleted").
- **`registerChildIdWaiter(parentId, childObject, tx)`** — the deferred-ID waiter: the
  child has no id yet (auto-generated); when the id lands
  (`IXdStorageIdObservableWrapper` → `onNewIdIsSet`) the pair is registered and the
  counter decremented.
- **Update/delete listeners on data and index resources** (`registerUpdateListener`,
  `registerDeleteUpdateListener`, `registerParentDeleteUpdateListener`) — fired at
  PREPARE_COMMIT of the *owning* resource; they dissolve the pair when the parent's
  update/remove actually removes the child reference, and decrement the matching
  counter. These are what make "parent saved first / child saved first / both in one
  tx" all resolve cleanly.

Lock/commit-order context: FK resources sit at `FOREIGN_KEY_RESOURCE_ORDER = 7500` —
they commit **last** (everything they point at is already durable in-phase) and their
locks are acquired **first** in the reverse acquisition order (the RECIDENT mutual
ordering, 2.1.0 task 47).

---

## 7. The manual-ID parent mechanism (the FIX_ANALYSIS record, condensed)

Saving a child whose parent has a **manual id** (set on the object but never persisted)
must veto at commit unless the parent is saved in the same transaction. The mechanism:

- `pendingFkRegistrations` (per-tx `Map<String, Set<FkRelation>>`) + a second map
  `pendingFkRegistrationsByObject` track the unresolved pairs;
- `validatePendingFks(tx)` runs at phase-1 (POINT 2 trio) and throws if a pending
  pair's parent is still absent from the parent's id-index (committed or staged);
- saving the parent in the same tx clears the pending entries.

The anomalies that shaped this mechanism (A-numbering is cited in
`XdStorageForeignKeyPair` javadoc — keep it stable):

| # | Anomaly | Resolution |
|---|---|---|
| A2.1 | In the outcome-relation path `insert(childId, parentId)` — the first arg is the CHILD's id | The pair carries positional `(frontId, backId)`; the discriminator below decides |
| A2.2 | In the same path the second arg carries the real PARENT's id | `isChildInParentIndex(childId, tx)` — "2nd arg visible in the parent's index" — distinguishes the paths |
| A2.3 | FK tree keys restore as raw Strings; typed comparisons (`Long` vs `String`) throw CCE | **Worked around**: validation paths check the id-indexes first; the FK-tree read paths still need key restoration (§9) |
| A2.4 | `isChildPersistedInIndex` (child's own index) is always-true for saved children — useless as discriminator | `isChildInParentIndex` is the real discriminator |
| A2.5 | `isParentPersistedInIndex` is false for BOTH the unsaved-parent and the outcome paths | Both maps + the discriminator disambiguate |

---

## 8. Verified state (what works)

**Test estate** (at commit `9964901`): FK estate **91 tests across 27 classes, green ×3**;
`XdStorageForeignKeyCacheTest` (10 pinned tests) new; quick-gate **1033 ×3 green**.
BTree and Hash index variants are mirrored for every scenario suite:

- `ForeignKeySaveScenariosTest` / `ForeignKeyDeleteScenariosTest` /
  `ForeignKeyUpdateScenariosTest` / `ForeignKeyReferenceScenariosTest` /
  `ForeignKeyManualIdSpecificationTest` / `XdStorageForeignKeyAutoIdInsertionTest` /
  `XdStorageForeignKeyAutoIdDeletionTest` / `XdStorageForeignKeyManualIdDeletionTest`
  (each in `foreignkey/index/btree/` and `foreignkey/index/hash/`);
- `XdStorageForeignKeyRegistryTest` (canonical topology pins),
  `XdStorageNestedTransactionsTest`, `XdStorageAutoSaveTest` (the cascade boundary),
  `XdStorageParentObjectTest`, `XdStorageForeignKeyDeletionTest` /
  `XdStorageForeignKeyInsertionTest` (partially disabled, §9).

**What the subsystem achieves:** referential-integrity vetoes (all three §1 rules
pinned by tests); dual-tree bidirectional indexing; deferred-ID waiters for
auto-generated ids; manual-ID parent tracking + commit veto; canonical registry (no
duplicate relations); transaction-scoped everything; proxy unwrapping at engine entry;
`enableForeignKeys` configuration; per-tree locking; the write-set overlay.

---

### 8.1 The 2026-11-20 session canons (verified, pinned)

- **delete-child-alone → VETO** (C1) and the **dangling-ref end-state → VETO**
  (C2: the end-state arbitration — the operation order is irrelevant) — the
  full canon table and the pinning tests:
  [FK_INTEGRITY_MODEL.md](FK_INTEGRITY_MODEL.md).
- **MassFillSecond redesign**: subtree-first delete (update(universe) BEFORE
  delete(galaxy) — the reverse order = the deterministic veto), the warmup
  phase, per-shard mutation semaphore, the FK-era disk filter
  (*_foreign_key.yaml + XdStorageBTreeNode/ excluded).
- **The overlay engine (task 53)**: the prototype 365/372 — the design and
  the remaining layers: [BTREE_MUTATION_OVERLAY.md](BTREE_MUTATION_OVERLAY.md).
- **The owner-id keying lesson**: the btree ops stage under the INTERNAL tx id
  (R-41); the resolution must key by
  getGlobalTransactionId() ?? getTransactionId() — the raw stage id loses the
  whole stage (the deferred-id child vanishing, diagnosed 2026-11-20).

## 9. Known limitations and open work (honest ledger)

| Item | Priority | Where it's tracked |
|---|---|---|
| FK tree key-type restoration (A2.3) — `readByParentObjectId`/`readByChildObjectId` still `find()` raw keys; CCE risk for numeric ids is avoided, not fixed | P1 | this ledger + 2.2.0 consistency core (task 01) |
| FK-cost A/B — **Step 0 MEASURED 2026-11-20** (task 50): concurrent writes collapse 167→1.2 tx/s (~140×, FK-independent — the id-index change-slot choke); FK overhead single-thread ≈17% (within W-4a band). The write-path fix = the tree-overlay program ([task 53](../tasks/2.1.0/53-tree-overlay.md), prototype preserved) | P0 | task 50 + [BTREE_MUTATION_OVERLAY.md](BTREE_MUTATION_OVERLAY.md) |
| Rename `isChildInParentIndex` → `isChildIdInParentIndex` (naming clarity) | P2 | this ledger |
| `validatePendingFks` lives in the resource — arguably belongs in the engine (single source of truth) | P2 | this ledger |
| `canRegisterForeignKey(parent, child, tx)` pre-check API — registration is reactive only | P2 | this ledger |
| **Concurrent-write collapse ~140×** — the id-index/FK-tree change-slot held per-tx until commit; the overlay program (task 53) prototype built (365/372), three integration layers remain | P0 | task 53 + [BTREE_MUTATION_OVERLAY.md](BTREE_MUTATION_OVERLAY.md) |
| The 8 disabled tests — **re-enabled/migrated 2026-11-20**: the estate is 372 tests, 56 classes, 372/0f/0e (the disabled list below is historical) | — | resolved |
| FK + AutoSave cycle safety (A→B→A) — no explicit test (AutoSave's visited-set prevents recursion, unpinned for FK) | P2 | this ledger |
| `validatePendingFks` batching — O(pending) individual index lookups per commit | P3 | this ledger |
| Concurrent FK-registration stress test | P3 | this ledger |
| Embedded (`StoreWithParentObject`) FK bypass — documented, no pinning test | P3 | this ledger |

**The 8 disabled tests** (as of 2026-11-16):

| File | Test(s) | Disabled reason |
|---|---|---|
| `SingleEntityForeignKeySpecificationTest` | whole class | "needs testing on real storage" |
| `XdStorageForeignKeyInsertionTest` | whole class | "needs testing on real storage" |
| `OutcomeRelationsForeignKeyTest` | 2 tests | "broken test" — outcome update/delete paths, mock-infrastructure NPEs; move to the real-storage base spec |
| `IncomeAndObservabilityForeignKeyTest` | 2 tests | "broken test" + "wrong test" — income-delete cleanup; waiter test needs a rewrite on real storage |
| `XdStorageForeignKeyResourceTest` | 1 test | "broken test" — the delete-from-both-trees mock |
| `StructuralGroupsForeignKeySpecificationTest` | 1 test | "needs testing on real storage" |

The recurring root cause is mock-based infrastructure (null resources manager / null
factory) — the fix pattern is to move the scenarios onto
`AbstractForeignKeyBaseSpecificationTest` (real storage), as the scenario suites did.

---

## 10. Relationships to other subsystems

| Subsystem | FK interaction |
|---|---|
| `@XdStorageAutoSave` cascade | FK is the complement — AutoSave saves children, FK tracks/vetoes them; cascade-saved children get their pairs via the same engine path |
| Trigger system | The FK engine IS a trigger (`IXdStorageTrigger`); fires on every save/update/delete of a related class |
| Code generation | Generated wrappers are unwrapped at engine entry; deferred-ID waiters hang off the observable-wrapper registry (`XdStorageObserverService`) |
| MVCC cache / 2PC | The write-set is phase-gated by the same protocol; FK validation sees committed + staged state; FK resources commit last (order 7500) |
| W-4a journals | FK tree writes roll back with the transaction through the data-class commit journals |
| Search indexes | Independent trees; the same key-type restoration gap (A2.3) exists in both |
| Cloner | The `fillAndWrap`/`cloneAndWrap` repack hazard — fixed by the `reference__` flip (§4) |
| RECIDENT (task 47) | The reverse lock acquisition / forward execution ordering includes FK resources |

---

---

## 12. Comparison with JPA/ORM relationship management rules (foreign-keys-object-model-rules-v2.md)

Below is the mapping of our 5 FK rules against the 10 rules in `foreign-keys-object-model-rules-v2.md`.
Rules are numbered by our (1-5) and by the ORM doc (1-10).

### Rule-by-rule mapping

| Our rule | Meaning | ORM rule | Match | Comment |
|---|---|---|---|---|
| **FK-1** | parent ref = null → commit OK | **ORM 3** (unsaved parent → TransientObjectException) | ✅ | Full match: unsaved parent = veto. Our `validatePendingFks` implements this. |
| **FK-2** | parent ref ≠ null → parent in DB or same TX | **ORM 3** (unsaved parent → TransientObjectException) | ✅ | Full match: unsaved parent = veto. Our `validatePendingFks` implements this. |
| **FK-3** | AutoSave: referenced child → in DB or auto-save (re-save = error) | **ORM 1** (ObjectDeletedException on cascade re-save) + **ORM 7** (parent delete without cascade → remove children) | ✅ | Our engine: if child is referenced in AutoSave, it must be in DB or auto-saved. Matches ORM 1+7. |
| **FK-4** | **remove from collection ≠ delete object** (child survives independently) | **ORM 1** (reverse direction: delete child → remove from collection) | ✅ compatible | Different sides of the same rule: ORM 1 says "on child delete, remove from collection" (to prevent cascade re-save). We say "on collection remove, no need to delete child" (child is independent). Compatible. |
| **FK-5** | Delete child → required remove from `parent.children` | **ORM 1** (delete without remove = ObjectDeletedException) + **ORM 7** | ✅ | Full match: delete child without remove from collection = veto. Our `delete` blocks this. |

### Architectural difference: FK-tracking vs dirty-checking

> ⚠️ **Critical difference from JPA/ORM.**
>
> In JPA/Hibernate FK-integrity is checked via **dirty-checking** of Java collections
> (PersistentBag, PersistentSet, dirty flag). If you removed a child from the Java collection,
> Hibernate sees it and allows the delete. But if you **deleted a child without removing from collection**
> — Hibernate via `CascadeType.ALL-delete-orphan` tries to re-save the deleted child
> (ObjectDeletedException).
>
> Our engine tracks FK pairs **directly in the B+tree** and validates by index —
> independent of Java collections. This means:
>
> 1. **FK-4 works more correctly:** in JPA with `orphanRemoval = true`, removing from collection
>    triggers cascade delete (unpredictable with `@OneToMany` without `orphanRemoval`).
>    Our engine simply allows the child to exist without a parent — the child is independent.
> 2. **FK-5 works at the data level, not memory:** we check that the child was removed from
>    the FK-tree, not from the Java collection. This is more robust, but requires `storage.delete(child)`
>    to always clean the FK-tree.
> 3. **Bidirectional sync (ORM 2) is implemented at the engine level:**
>    `@XdStorageParentObject` (income) and `@XdStorageAutoSave` (outcome) register
>    **two** sides of the FK-pair. The engine syncs front/reverse itself —
>    you don't need to write `child.setParent(this)` manually. But: if the child is removed from the
>    Java object's collection but the FK-pair is not updated (bug in `delete`), FK-integrity breaks.
>
> **Conclusion:** our rules cover ORM rules 1-8, but with a different validation model:
> data-layer (B+tree) vs memory-layer (dirty-checking). This makes us stronger in
> FK-5 (data-level check) and weaker in bidirectional sync (don't track Java collections).

### ORM rules not applicable in our model

| ORM Rule | Why not applicable | How we cover |
|---|---|---|
| **ORM 8** (flush order for UNIQUE FK) | We use B+tree with engine-level uniqueness; INSERT/DELETE in one TX handled via write-set (later-wins). | Covered by write-set phase-1 validation (POINT 2 trio) |
| **ORM 9** (ManyToMany ≠ CascadeType.REMOVE) | Our `@XdStorageAutoSave` is always 1:N (or N:1), not M:N. M:N covered via intermediate entities. | N/A |
| **ORM 10** (hashCode/equals for Set) | We use List/Array for `StoreAsClassObjects`; `StoreAsSingleObject` = one child. Set not used. | N/A |
| **ORM 6** (orphanRemoval + collection proxy) | No `orphanRemoval` here; collections not wrapped in proxy classes (we read by index). | N/A |

### Summary

> Our rules **fully cover** ORM 1-7 (≥90% match).
> ORM 8-10 are not applicable to our model (different architecture).
> **FK-4 is our architectural decision** (child survives independently),
> not a bug, but intentional design. Documented in test `ForeignKeyUpdateScenariosTest`
> as "Rule 4: collection remove ≠ object delete".

- [2.1.0 task 42](../tasks/2.1.0/42-foreign-keys.md) — the feature spec + acceptance
- [2.1.0 task 50](../tasks/2.1.0/50-fk-cost-benchmark.md) — the FK-cost A/B benchmark (open)
- Commit `9964901` — the write-set integration record (the POINTS, the Cloner fix, the estate numbers)
- `docs/GUIDE.md` — the user-facing FK chapter; `docs/EMBEDDING.md` — the veto contract
- Test base classes: `AbstractForeignKeySpecificationTest` (mock) /
  `AbstractForeignKeyBaseSpecificationTest` (real storage, btree + hash variants)
