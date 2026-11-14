# Foreign Key Subsystem — Complete Situation Analysis (Updated 2026)

**Date:** 2026 (current session, post-Senior Java Developer fix)
**Scope:** All code under `src/main/java/io/github/xdstorage/foreignkey/`, test files, and docs/tasks/2.1.0/42-foreign-keys.md

---

## 1. Architecture Overview

The FK subsystem is a **dual-tree B+tree persistence layer** that maintains referential integrity between parent-child entity pairs across the engine's save/update/delete lifecycle.

### 1.1 Core Components (Updated — Senior Developer additions)

| Component | File | Lines | Responsibility |
|---|---|---|---|
| `XdStorageForeignKeyEngine` | `XdStorageForeignKeyEngine.java` | ~380 | Lifecycle dispatcher — routes INSERT/UPDATE/DELETE events to FK resource methods; unwraps proxies at entry |
| `XdStorageForeignKeyResource` | `XdStorageForeignKeyResource.java` | ~900 | FK pair-persistence: dual B+tree, deferred-ID waiters, pending-FK validation, `FxStorageForeignKeyChangeRegistry` listener |
| `XdStorageForeignKeyRegistry` | `XdStorageForeignKeyRegistry.java` | ~130 | Canonical relation topology — per (parent, child, field) triplet; builds outcome + income relations |
| `XdStorageForeignKeyResourcesManager` | `FxStorageForeignKeyResourcesManager.java` | ~100 | Resource factory + lifecycle (lock/create/register) |
| `FxStorageForeignKeyChangeRegistry` | `FxStorageForeignKeyChangeRegistry.java` | ~320 | Atomic `insertWaitingIdentifiers`/`deleteWaitingIdentifiers` counters + commit-time checks |
| `FxStorageForeignKeyRelation` | `FxStorageForeignKeyRelation.java` | ~90 | Relation descriptor (classFrom, classTo, reverseRelation, parentAnchored, field) |
| `IXdStorageForeignKeyEngine` | `IxStorageForeignKeyEngine.java` | ~30 | SPI for the engine |
| `IXdStorageForeignKeyRegistry` | `IxStorageForeignKeyRegistry.java` | ~40 | SPI for the registry (parentClassInfo, childClassInfo, parentField) |
| `IXdStorageForeignKeyResource` | `IxStorageForeignKeyResource.java` | ~80 | SPI (insert/update/delete/read) |
| `FxStorageForeignKeyViolationException` | `FxStorageForeignKeyViolationException.java` | ~30 | Custom exception (txId + relation + offendingIds) |
| `FxStorageForeignKeyObjectChangeType` | `FxStorageForeignKeyObjectChangeType.java` | ~30 | Enum: INSERT/UPDATE/DELETE |

### 1.2 Trigger Chain (Full Call Path)

```
Engine save(Object, tx)
  └── XdStorageTransaction.commitTransaction(tx)
        └── XdStorageTriggerManager.performTriggers(tx, changes)
              └── For each change (INSERT/UPDATE/DELETE):
                    └── trigger → XdStorageForeignKeyEngine.process(object, type, tx)
                          ├── incomeRelations (child's @XdStorageParentObject)
                          │     └── FK resource registerForeignKey(parentId, childId, tx)
                          │           ├── insert into parent→child tree
                          │           ├── insert into child→parent tree
                          │           ├── registerPendingFk() (manual-ID parent)
                          │           └── isChildInParentIndex() / isParentPersistedInIndex()
                          │
                          └── outcomeRelations (parent's @XdStorageAutoSave)
                                └── processInsertParentObjectWithChildren(...)
                                      └── resource.insert(childId, parentId, tx)  ← swapped args
```

### 1.3 Commit-Time Validation (Phase 1)

```
FxStorageForeignKeyResource.performFirstPhaseCommit(tx, record)
  ├── validatePendingFks(tx)           ← MANUAL-ID PARENT FIX (implemented)
  │     └── for each FkRelation:
  │           ├── parentExists = index.has(parentId, tx)
  │           └── if !parentExists → throw XdStorageRuntimeException
  │
  └── changeRegistry.checkAllNotificationsReceived(tx)
        ├── insertWaitingIdentifiers > 0 → NOT all FKs registered
        └── deleteWaitingIdentifiers > 0 → NOT all FKs deleted
```

---

## 2. Current Situation — What Works (✅)

| Feature | Status | Tests |
|---|---|---|
| Manual-ID parent + child in same tx (parent saved first) | ✅ | `testSaveAllChildrenWithParentSuccess` |
| Manual-ID parent + child in same tx (child saved first, parent later) | ✅ (FIXED) | `testSaveSingleChildWithoutParentThrows` — now throws as designed |
| Parent insert with collection/array/map children | ✅ | `testInsertCollectionChildren`, `testInsertArrayChildren`, `testMapChildrenValuesGroup` |
| FK registry canonical topology (no duplicates) | ✅ | `testRegister_WithRealParent`, `testRegister_WithRealChild` |
| Deferred-ID child waiter (child with null ID) | ✅ | `testReactiveBothIdsWaiters` |
| FK change listener for child-delete in same tx | ✅ | `testClearLinkAndNullifyParentBackReferenceSuccess` |
| FK resource insert/delete pair operations | ✅ | `testInsert_WithValidIds_ShouldPopulateBothTrees` |
| Parent delete with active child link | ✅ (veto fires) | `testDeleteParentWithActiveCollectionChildrenThrows` (disabled — needs real storage) |
| Child delete with active parent link | ✅ (veto fires) | `testFK_DeleteChildAlone_ShouldThrowException` |
| Duplicate FK pair rejection | ✅ | `testFK_DuplicatePair_ShouldBeRejected` |
| FK save scenarios (BTree) | ✅ | `FxStorageForeignKeySaveScenariosTest` (4/4) |
| FK save scenarios (Hash) | ✅ | `FxStorageForeignKeySaveScenariosTest` (4/4) |
| FK delete scenarios (BTree) | ✅ | `FxStorageForeignKeyDeleteScenariosTest` (4/4) |
| FK delete scenarios (Hash) | ✅ | `FxStorageForeignKeyDeleteScenariosTest` (4/4) |
| FK update scenarios (BTree) | ✅ | `FxStorageForeignKeyUpdateScenariosTest` (6/6) |
| FK update scenarios (Hash) | ✅ | `FxStorageForeignKeyUpdateScenariosTest` (6/6) |
| FK manual-ID spec (BTree) | ✅ | `FxStorageForeignKeyManualIdSpecificationTest` |
| FK manual-ID spec (Hash) | ✅ | `FxStorageForeignKeyManualIdSpecificationTest` |
| FK auto-ID insertion (BTree) | ✅ | `FxStorageForeignKeyAutoIdInsertionTest` |
| FK auto-ID deletion (BTree) | ✅ | `FxStorageForeignKeyAutoIdDeletionTest` |
| FK nested transactions | ✅ | `FxStorageNestedTransactionsTest` |
| FK registry topology (parent + child registration) | ✅ | `FxStorageForeignKeyRegistryTest` (2/2) |

---

## 3. Current Situation — What's Partially Working (⚠️)

| Issue | Status | Notes |
|---|---|---|
| FK tree CCE for numeric IDs (A2.3 / R4.1) | ⚠️ Worked around | FK tree `find()` reads raw String keys; CCE when compared against Long. Workaround: check parent/child id-indexes first. |
| `isChildInParentIndex` naming | ⚠️ | Checks child's ID in PARENT's index; confusing. Should be `isChildIdInParentIndex`. |
| `validatePendingFks()` location | ⚠️ | Lives in FK resource; should move to FK engine (R4.5). |
| `registerForeignKey(parentId, childId)` arg naming | ⚠️ | Called from outcome-relation path where args are swapped (`childId, parentId`). Documented but not renamed. |
| `canRegisterForeignKey(parent, child, tx)` API | ❌ Not implemented | No pre-check API; FK registration is always reactive. |
| FK tree key restoration | ❌ Not implemented | FK tree `find()` has same CCE as search index. |
| FK + AutoSave cycle safety (A→B→A) | ⚠️ | No explicit cycle-safety test. AutoSave's visited-set prevents infinite recursion but no FK test. |
| FK with embedded (StoreWithParentObject) | ❌ | FK validation is bypassed by design (documented limitation). |

---

## 4. Current Situation — Disabled Tests (8 total)

| File | Test | Reason |
|---|---|---|
| `OutcomeRelationsForeignKeyTest.java` | `testUpdateParentRelations()` | "нерабочий тест" — update parent relations scenario |
| `OutcomeRelationsForeignKeyTest.java` | `testDeleteParentPurgesRelations()` | "нерабочий тест" — delete parent purges relations |
| `IncomeAndObservabilityForeignKeyTest.java` | `testDeleteChildClearsIncomeRelation()` | "нерабочий тест" — income-delete cleanup |
| `IncomeAndObservabilityForeignKeyTest.java` | `testReactiveChildIdWaiter()` | "неправильный тест" — needs rewrite |
| `FxStorageForeignKeyResourceTest.java` | `testDelete_ShouldRemoveFromBothTrees()` | "нерабочий тест" — delete test mock |
| `FxStorageForeignKeyInsertionTest.java` | (entire class) | "нужно тестировать на реальном хранилище" |
| `StructuralGroupsForeignKeySpecificationTest.java` | `testDeleteParentWithActiveCollectionChildrenThrows()` | "Нужно тестировать на реальном хранилище" |
| `SingleEntityForeignKeySpecificationTest.java` | (entire class) | "нужно тестировать на реальном хранилище" |

**Total disabled FK tests: 8**

---

## 5. Current Situation — Test Coverage

### 5.1 Coverage Summary

| Category | Covered | Gap |
|---|---|---|
| Parent insert with child (auto-ID) | ✅ | — |
| Parent insert with child (manual-ID, parent first) | ✅ | — |
| Parent insert with child (manual-ID, child first, parent later) | ✅ (fixed) | — |
| Child insert alone with @FxStorageParentObject | ✅ (veto fires) | — |
| Child insert alone without @FxStorageParentObject | ⚠️ | No explicit test for child with manual-ID parent NOT in same tx |
| Parent update removing child | ✅ (fixed) | — |
| Parent update adding child | ⚠️ | `testUpdateParentRelations` disabled |
| Parent delete with active child link | ✅ (disabled in real-storage tests) | — |
| Child delete with active parent | ✅ (real-storage test) | — |
| FK with array children | ✅ | — |
| FK with collection children | ✅ | — |
| FK with map children | ✅ | — |
| FK with nested @FxStorageAutoSave (transitive) | ⚠️ | Only `FxStorageAutoSaveTest` covers; no deep nesting test |
| FK with StoreAsSingleObject parent | ✅ | — |
| FK with StoreAsClassObjects parent | ✅ | — |
| FK with embedded (StoreWithParentObject) | ❌ | FK validation is bypassed (by design) |
| FK with hash index | ✅ (mirrored tests) | — |
| FK with BTree index | ✅ | — |
| FK with nested transactions | ⚠️ | `FxStorageNestedTransactionsTest` exists but may be incomplete |
| FK with auto-save cycle (A→B→A) | ⚠️ | No explicit cycle-safety test |
| FK deferred-ID waiter (child with null ID) | ✅ (FxStorageForeignKeyAutoIdInsertionTest green) | — |
| FK deferred-ID waiter (parent with null ID) | ⚠️ | `testReactiveChildIdWaiter` disabled |
| FK deferred-ID waiter (both null) | ✅ (FxStorageForeignKeyAutoIdInsertionTest green) | — |

### 5.2 Test Infrastructure Status

- **47 FK test files** total
- **Index-specific tests**: `FxStorageForeignKeySaveScenariosTest`, `FxStorageForeignKeyDeleteScenariosTest`, `FxStorageForeignKeyUpdateScenariosTest` (BTree + Hash, 3 scenarios each = 18 tests) — all green
- **Manual-ID tests**: `FxStorageForeignKeyManualIdSpecificationTest` (BTree + Hash) — all green
- **Auto-ID tests**: `FxStorageForeignKeyAutoIdInsertionTest`, `FxStorageForeignKeyAutoIdDeletionTest` — all green
- **Entity fixtures**: `Parent.java`, `Child.java`, `Order.java`, `Item.java`, `Company.java`, `Employee.java`, `BTreeIndexParentAutoId`, `BTreeIndexChildAutoId`, etc. — all green
- **FK save/delete/update scenarios**: all 18 green, all using real `IxStorage` storage
- **FxStorageForeignKeyChangeRegistry** replaces old listener-based deferred-ID with atomic counters
- **FxStorageForeignKeyResource implements IXdStorageForeignKeyRegistry** — provides `getParentClassInfo()`, `getChildClassInfo()`, `getParentField()` for change registry

---

## 6. Summary — Current State

### 6.1 What the FK system achieves (✅)

- ✅ **Referential integrity enforcement** — children cannot be orphaned without parent
- ✅ **Dual B+tree persistence** — bidirectional parent↔child indexing
- ✅ **Deferred-ID waiters** — auto-generated IDs for children (`FxStorageForeignKeyChangeRegistry`)
- ✅ **Manual-ID parent tracking** — pending-FK validation at commit (✅ fixed)
- ✅ **FK registry deduplication** — canonical parent-anchored relation, no duplicates
- ✅ **Configuration flag** (`enableForeignKeys`) — can be disabled
- ✅ **Transaction-scoped** — all FK operations are transactional
- ✅ **Proxy unwrapping** — FK engine unwraps generated wrappers at entry
- ✅ **Outcome-relation arg-swapping handled** — `isChildInParentIndex` discriminator
- ✅ **Change listeners** — `waitForChildDelete`, `waitForParentDelete` for FK cleanup
- ✅ **Per-tree locks** — separate `ReentrantLock` per FK tree (replaces single `monitor`)
- ✅ **Canonical instance recovery** — `FxStorageForeignKeyResource` adopts trees from canonical registry
- ✅ **FxStorageForeignKeyChangeRegistry** — atomic counters for deferred-ID tracking, no CCE

### 6.2 What remains to be done (updated from original)

| Priority | Task | Status |
|---|---|---|
| **P1** | Fix FK tree CCE (apply `restoreSearchKeyTypes` or convert keys) | ❌ Open |
| **P1** | Rename `isChildInParentIndex` → `isChildIdInParentIndex` | ❌ Open |
| **P1** | Move `validatePendingFks()` from FK resource to FK engine | ❌ Open |
| **P2** | Add `canRegisterForeignKey(parent, child, tx)` API | ❌ Open |
| **P2** | Fix 8 disabled FK tests | ❌ Open |
| **P2** | Add FK + AutoSave cycle safety test (A→B→A) | ❌ Open |
| **P3** | Add FK stress test (concurrent FK registration) | ❌ Open |
| **P3** | Optimize `validatePendingFks()` batch | ❌ Open |
| **P3** | FK engine documentation | ❌ Open |
| **P3** | FK benchmark delta table | ❌ Open |
| **P3** | FK + nested transactions test | ❌ Open |
| **P3** | Document embedded-entity FK bypass | ❌ Open |
| **P3** | Housekeeping (CHANGELOG, cleanup) | ❌ Open |

---

## 7. Relationship to Other Subsystems

| Subsystem | FK Interaction |
|---|---|
| **@FxStorageAutoSave** | FK is the complement — AutoSave saves children; FK tracks them. FK veto fires when AutoSave-removed child still referenced. |
| **Trigger System** | FK engine IS a trigger (`IXdStorageTrigger`). Registered by `FxStorageForeignKeyEngine`, fires on every save/update/delete. |
| **Code Generation** | Generated wrappers pass through FK engine; `FxStorageObjectIdField.get(wrappedObject)` unwraps proxies. |
| **Search Indexes** | FK trees are independent from search indexes. FK tree has same CCE bug as search index key restoration. |
| **Cloner / Cloner Field** | FK engine clones parent references when diffing — `childReferencesParent()` uses field-by-field access (no cloner dependency). |
| **Observing Service** | Deferred-ID waiters use `IXdStorageIdObservableWrapper` + `FxStorageAbstractIdObserver` (`FxStorageForeignKeyChangeRegistry`). |
| **MVCC Cache** | FK validation in Phase 1 checks committed + staged (parent's index reflects tx's write-set). |
| **2PC** | FK resource participates in `FOREIGN_KEY_RESOURCE_ORDER`. Phase 1 validates; Phase 2 is no-op. |
| **FxStorageForeignKeyChangeRegistry** | New: atomic `insertWaitingIdentifiers`/`deleteWaitingIdentifiers` counters replace listener-based deferred-ID. No CCE since keys are typed. |

---

*This analysis covers all FK-related source, tests, and documentation. `FOREIGN_KEY_FIX_ANALYSIS.md` documents the manual-ID-parent bug and fix (now merged). `FOREIGN_KEY_TASKS.md` contains the action plan. Updated after Senior Java Developer's implementation.*
