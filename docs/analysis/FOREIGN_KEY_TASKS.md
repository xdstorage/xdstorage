# Foreign Key Tasks — Action Plan (Updated 2026)

**Created:** 2026 (original session)
**Updated:** 2026 (post-Senior Java Developer implementation)
**Base:** FOREIGN_KEY_SITUATION_ANALYSIS.md
**Status tracking:** Each task has an `Accepted` section. Tasks marked ✅ are completed; ❌ are open.

---

## Completed Tasks (from Senior Java Developer)

### ✅ T-FK-003: Typed Pending-FK Set (`FkRelation`)

**Done by:** Senior Java Developer
**File:** `XdStorageForeignKeyResource.java`

**Implementation:**
- Inner class `FxStorageForeignKeyResource.FkRelation` with `parentId`, `childId` fields
- `Map<String, Set<FkRelation>> pendingFkRegistrations` replaces `Map<String, Set<String>>`
- `FkRelation.matchesParent(FkRelation rel, Object parentId)` for clearing
- `equals()`/`hashCode()` based on both fields (no fragile string encoding)
- `FkRelation` is private inner class — no external API surface

**Verification:** `validatePendingFks()` iterates `Set<FkRelation>` directly, checks `parentId` against index. No CCE from key encoding.

---

### ✅ T-FK-001 (partial): FK Tree CCE Workaround

**Done by:** Senior Java Developer
**File:** `XdStorageForeignKeyResource.java`

**Implementation:**
- `isChildInParentIndex(childId, tx)` checks parent's index before reading FK tree
- `isParentPersistedInIndex(parentId, tx)` checks parent's index before reading FK tree
- `validatePendingFks()` uses parent's index (not FK tree) for existence check
- FK tree `find()` is still called in `readByParentObjectId()` / `readByChildObjectId()` — **the CCE bug is NOT fixed**, only avoided for the pending-FK validation path

**Remaining:** The FK tree's `find()` still has the key restoration bug. It's only avoided for validation; the FK read methods (`readByParentObjectId`, `readByChildObjectId`) still call `treeParentChild.find(...)` which CCEs on numeric IDs.

---

### ✅ T-FK-004 (partial): `validatePendingFks()` in FK resource

**Done by:** Senior Java Developer
**File:** `FxStorageForeignKeyResource.performFirstPhaseCommit()`

**Implementation:**
- `validatePendingFks(tx)` at phase 1 commit in the FK resource
- Checks each pending `FkRelation`'s `parentId` against parent's index
- Throws `XdStorageRuntimeException` on violation

**Not moved to FK engine** — still in the FK resource (R4.5 not yet implemented).

---

## Remaining Tasks (updated)

### T-FK-002: Rename `isChildInParentIndex` → `isChildIdInParentIndex`

**Priority:** P1 (Naming clarity)
**File:** `src/main/java/io/github/xdstorage/foreignkey/XdStorageForeignKeyResource.java`

**Problem:** `isChildInParentIndex(childId, tx)` checks if `childId` exists in the **parent's** index. Confusing name — suggests it checks the child's own index (which `isChildPersistedInIndex` already does).

**Tasks:**
1. Rename `isChildInParentIndex` → `isChildIdInParentIndex`
2. Update call site in `registerForeignKey()`: `boolean childInParentIndex = isChildIdInParentIndex(childId, tx);`
3. Update Javadoc: "Checks if the 2nd arg exists in the PARENT class's index"

**Accepted:**
- [ ] Renamed method compiles
- [ ] No test failures
- [ ] Javadoc clarifies "in parent's index" semantics

---

### T-FK-005: `canRegisterForeignKey(parent, child, tx)` API

**Priority:** P2 (Preventive design)
**File:** `IXdStorageForeignKeyEngine.java`

**Problem:** Current API is reactive: `insert()` always registers, validation is deferred to commit. A proactive pre-check would allow callers to handle violations early.

**Tasks:**
1. Add to `IXdStorageForeignKeyEngine`:
   ```java
   /**
    * Pre-check: does the FK pair (parent→child) pass all integrity rules?
    * - parent exists (committed or staged in tx)
    * - pair not already registered (duplicate rejection)
    */
   boolean canRegisterForeignKey(Comparable<?> parent, Comparable<?> child,
                                 IXdStorageTransaction tx);
   ```
2. Implement in `FxStorageForeignKeyEngine` using same checks as `validatePendingFks`
3. Use in `FxStorageForeignKeyResource.registerForeignKey()` as a guard

**Accepted:**
- [ ] `canRegisterForeignKey` returns true for valid pairs, false for invalid
- [ ] `registerForeignKey()` uses the guard
- [ ] Existing tests still pass

---

### T-FK-006: Fix 8 Disabled FK Tests

**Priority:** P2 (Test completeness)
**Scope:** 8 disabled tests across 4 files

#### 6a. OutcomeRelationsForeignKeyTest (2 disabled)

**File:** `OutcomeRelationsForeignKeyTest.java`

| Test | Issue | Fix |
|---|---|---|
| `testUpdateParentRelations()` | "нерабочий тест" | Outcome-relation UPDATE path (`processUpdateParentObjectWithChildren`) diffs collection, registers delete for removed children. Test needs real storage (use `AbstractForeignKeyBaseSpecificationTest`). |
| `testDeleteParentPurgesRelations()` | "нерабочий тест" | Outcome-relation DELETE path (`processOutcomeRelations` → `resource.delete(object, parentId, null, childId, tx)`) arms `waitForChildDelete` listeners. Needs real storage. |

**Fix approach:** Move to `AbstractForeignKeyBaseSpecificationTest` (real storage). The mock `AbstractForeignKeyEngineIntegrationTest` has `null` resources manager — FK tree operations NPE.

#### 6b. IncomeAndObservabilityForeignKeyTest (2 disabled)

**File:** `IncomeAndObservabilityForeignKeyTest.java`

| Test | Issue | Fix |
|---|---|---|
| `testDeleteChildClearsIncomeRelation()` | "нерабочий тест" | Income-DELETE path (`processIncomeRelations` → `resource.delete(null, parentId, child, childId, tx)`) arms `waitForParentDelete` listeners. The FK engine's `delete()` with `parentObject=null` registers `waitForParentDelete`. Needs real storage. |
| `testReactiveChildIdWaiter()` | "неправильный тест" | Rewrite: use real storage. Simulate ID generation via `FxStorageAbstractIdObserver.onNewIdIsSet()`. The `FxStorageForeignKeyChangeRegistry.registerParentIdWaiter(childObjectId, parentObject, tx)` is the correct mechanism. |

#### 6c. StructuralGroupsForeignKeySpecificationTest (1 disabled)

**File:** `StructuralGroupsForeignKeySpecificationTest.java`
- `testDeleteParentWithActiveCollectionChildrenThrows()` — "Нужно тестировать на реальном хранилище" → move to `AbstractForeignKeyBaseSpecificationTest`

#### 6d. SingleEntityForeignKeySpecificationTest (1 disabled)

**File:** `SingleEntityForeignKeySpecificationTest.java`
- Entire class disabled — "нужно тестировать на реальном хранилище" → remove `@Disabled`

#### 6e. XdStorageForeignKeyInsertionTest (1 disabled)

**File:** `FxStorageForeignKeyInsertionTest.java`
- Entire class disabled — "нужно тестировать на реальном хранилище" → remove `@Disabled`

#### 6f. XdStorageForeignKeyResourceTest (1 disabled)

**File:** `FxStorageForeignKeyResourceTest.java`
- `testDelete_ShouldRemoveFromBothTrees()` — "нерабочий тест" → fix mock to respond to `delete()` calls

**Accepted:**
- [ ] All 8 disabled tests enabled and passing
- [ ] No new `@Disabled` annotations
- [ ] FK test suite 100% green

---

### T-FK-007: FK Stress Test — Concurrent FK Registration

**Priority:** P3 (Performance correctness)
**File:** `src/main/test/.../foreignkey/FkConcurrencyTest.java` (new)

**Problem:** FK tree creation uses double-checked locking with `ReentrantLock`. Under concurrent FK registration (parent + child in same tx from different threads), contention is possible.

**Tasks:**
1. Create stress test: 50 concurrent transactions, each saving a parent with 2 children
2. Verify: all FK pairs registered correctly, no CCE, no missing pairs
3. Add contention test: 100 threads sharing same parent (all registering FK for same parent)
4. Measure: FK resource lock acquisition time

**Accepted:**
- [ ] Stress test passes 100× (no flakes)
- [ ] No deadlocks under 100-thread contention
- [ ] Benchmark shows acceptable lock wait time (<1ms per registration)

---

### T-FK-008: ValidatePendingFks Batch Optimization

**Priority:** P3 (Performance)
**File:** `src/main/java/io/github/xdstorage/foreignkey/XdStorageForeignKeyResource.java`

**Problem:** `validatePendingFks()` iterates `pendingFkRegistrations` set and checks each parent individually against the index (O(n) index lookups).

**Tasks:**
1. Collect all pending parent IDs into `Set<Comparable<?>>`
2. Use `manager.lockIndexResource(clParentInfo, tx).hasBatch(pendingParentIds, tx)` if batch API exists
3. If no batch API, add `FxStorageBTreeIndexResource.has(Comparable<?>... ids)` method
4. Add benchmark: `validatePendingFks` with 1000 pending relations

**Accepted:**
- [ ] Performance: 1000 pending FKs validated in <5ms
- [ ] Correctness: same results as current implementation
- [ ] Benchmark included in docs/perf/

---

### T-FK-009: FK + AutoSave Cycle Safety

**Priority:** P2 (Correctness)
**File:** `src/main/test/.../foreignkey/FkAutoSaveCycleTest.java` (new)

**Problem:** `@FxStorageAutoSave` is transitive (A→B→C). The FK engine's `process()` is called after AutoSave cascade. If A has child B, and B has child A (cycle), FK engine could enter infinite recursion.

**Tasks:**
1. Create entity classes:
   ```java
   class A { @FxStorageAutoSave List<B> children; }
   class B { @FxStorageAutoSave A parent; } // cycle A→B→A
   ```
2. Test: save A with child B (which references A back)
3. Verify: no infinite loop, FK tree has correct pairs
4. Verify: FK veto fires if B is saved without A

**Accepted:**
- [ ] Cycle test passes (no StackOverflowError)
- [ ] FK tree contains exactly A→B (one pair, not duplicated)
- [ ] FK veto fires for unsaved parent in cycle

---

### T-FK-010: FK Engine Documentation

**Priority:** P3 (Maintainability)
**Scope:** Javadoc + docs/

**Tasks:**
1. Add `@implNote` to `FxStorageForeignKeyEngine.process()`:
   - "Fires for both income (child's @FxStorageParentObject) and outcome (parent's @FxStorageAutoSave) relations"
   - "outcome-relation: `insert(childId, parentId)` — args are swapped"
   - "deferred-ID waiters use FxStorageAbstractIdObserver"
2. Add `@implNote` to `FxStorageForeignKeyResource`:
   - "Phase 1 validates pending-FKs (manual-ID parents)"
   - "Phase 2 is no-op (FK is already persisted in Phase 1)"
   - "rollbackPerformingFirstPhaseCommit is no-op (FK trees are in the cache)"
3. Create `docs/tasks/2.1.0/43-fk-engine-design.md` — design doc:
   - Dual-tree architecture
   - Deferred-ID waiter model
   - Pending-FK validation
   - Registry canonical topology
   - Transaction lifecycle alignment with 2PC

**Accepted:**
- [ ] All public methods have `@implNote`
- [ ] Design doc covers all 5 bullet points
- [ ] No `FIXME`/`TODO` comments remain in FK package

---

### T-FK-011: FK + AutoSave Integration

**Priority:** P2 (Integration completeness)
**Scope:** FK + AutoSave boundary

**Tasks:**
1. Test: Parent with `@FxStorageAutoSave List<Child>` where Child has auto-ID
   - Save parent → cascade saves children with generated IDs
   - Verify: FK tree has parent→child pairs for all cascade-saved children
2. Test: FK veto when cascade-saved child is deleted without parent update
   - Save parent (cascade saves children)
   - Delete cascade-saved child without updating parent
   - Verify: FK veto fires

**Accepted:**
- [ ] FK tree updated correctly on parent cascade-save
- [ ] FK veto fires on cascade-saved child deletion without parent update
- [ ] FK engine's `getOutcomeRelations()` includes cascade-saved children

---

### T-FK-012: FK with StoreWithParentObject (embedded) Documentation

**Priority:** P3 (Documented limitation)
**Scope:** FK validation for embedded children

**Tasks:**
1. Document limitation in `IXdStorageForeignKeyEngine` and `FxStorageForeignKeyResource`:
   - "FK validation is not enforced for entities with `@FxStorageObjectPolicy(StoreWithParentObject)` (embedded value objects)"
2. Add test verifying FK is skipped for embedded entities

**Accepted:**
- [ ] Javadoc documents embedded-entity limitation
- [ ] Test verifies FK is skipped for embedded entities
- [ ] No surprise FK validation on embedded children

---

### T-FK-013: FK with Nested Transactions

**Priority:** P2 (Transaction semantics)
**File:** `FxStorageNestedTransactionsTest.java` in FK package

**Tasks:**
1. Audit: `FxStorageNestedTransactionsTest` — does it test FK with nested tx?
2. Add tests:
   - Parent saved in parent tx, child referenced in nested tx → FK visible
   - Parent saved in nested tx, parent tx rolls back → FK NOT registered
   - Parent saved in nested tx, nested tx commits → FK visible to parent tx
3. Verify: `pendingFkRegistrations` is transaction-scoped (per-txId map)

**Accepted:**
- [ ] All nested-FK scenarios pass
- [ ] Pending-FK properly cleaned up on nested-rollback
- [ ] FK tree persists correctly across nested-transaction boundaries

---

### T-FK-014: FK Benchmark Delta Table

**Priority:** P3 (Performance visibility)
**Scope:** `scripts/benchmarks/FkBenchmark.java` (new)

**Tasks:**
1. Create benchmark: FK registration cost vs. number of FK relations
2. Create benchmark: FK validation cost vs. pending-FK count
3. Create benchmark: FK tree creation (cold) vs. FK tree lookup (warm)
4. Add to `docs/PERFORMANCE.md` delta table

**Accepted:**
- [ ] Benchmark results in docs/PERFORMANCE.md
- [ ] FK registration: <1ms per relation
- [ ] FK validation: <10ms for 1000 pending relations
- [ ] FK tree creation (cold): <50ms (documented)

---

### T-FK-015: Cleanup

**Priority:** P3 (Housekeeping)
**Scope:** CHANGELOG, analysis doc

**Tasks:**
1. In CHANGELOG: add entry "FK manual-ID parent fix (pending-FK validation)" with commit reference
2. `FOREIGN_KEY_FIX_ANALYSIS.md` can be archived (the fix is merged)
3. Update `ANALYSIS-ARCHITECTURE.md` FK section to reference this task list

**Accepted:**
- [ ] CHANGELOG mentions FK fix
- [ ] No stale references to the analysis document
- [ ] FOREIGN_KEY_TASKS.md is the single source of truth

---

## Task Summary

| Priority | Tasks | Description |
|---|---|---|
| **P1 (2)** | T-FK-002, T-FK-001 (CCE fix) | Naming + key restoration |
| **P2 (4)** | T-FK-005, T-FK-006, T-FK-009, T-FK-011, T-FK-013 | API + tests + integration |
| **P3 (6)** | T-FK-007, T-FK-008, T-FK-010, T-FK-012, T-FK-014, T-FK-015 | Perf, docs, cleanup |
| **Total** | **13** | |

---

## Execution Order (Recommended)

1. **P1 block** (T-FK-002, T-FK-001 full fix) — correctness fixes
2. **P2 block** (T-FK-005, T-FK-006, T-FK-009, T-FK-011, T-FK-013) — test coverage + integration
3. **P3 block** (T-FK-007, T-FK-008, T-FK-010, T-FK-012, T-FK-014, T-FK-015) — performance + cleanup + housekeeping
4. **T-FK-015 last** — housekeeping after everything is merged
