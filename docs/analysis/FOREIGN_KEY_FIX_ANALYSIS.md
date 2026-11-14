# Foreign Key Manual-ID Parent Registration Bug — Analysis & Fix

## 1) Summary

### Problem
`ForeignKeyManualIdSpecificationTest.testSaveSingleChildWithoutParentThrows` failed with:
```
Expected io.github.xdstorage.exceptions.XdStorageRuntimeException to be thrown,
but nothing was thrown.
```

The test saves a child entity whose parent has a **manual ID** (set on the Java object but never persisted). The expected behavior: `commitTransaction` should throw `XdStorageRuntimeException` because the FK points to a non-existent parent.

### Root Cause
The FK engine's `processIncomeRelations()` unconditionally calls `resource.insert(parentId, childId, tx)` whenever the parent reference carries a non-null ID. For manual-ID parents this bypasses the auto-ID waiter mechanism — the FK is registered immediately to a parent that doesn't exist.

**The FK tree's `has()` check in `isParentPersistedInIndex()` was being called with the WRONG ID** (childId instead of parentId) in the outcome-relation path, because the FK engine swaps arguments for parent-save: `insert(childId, parentId)` for the outcome-relation (parent's children list) vs `insert(parentId, childId)` for the income-relation (child's parent reference).

### Fix
Added a per-transaction `pendingFkRegistrations` map + `validatePendingFks()` called at phase-1 commit. The logic distinguishes income-relation vs outcome-relation paths by checking if `childId` (2nd arg) is visible in the **parent's index**:
- If `!parentId in parent_index && !childId in parent_index` → unsaved parent → **track as pending** → validate at commit → throw if parent still missing
- If `parentId in parent_index` → parent already saved in this tx → clear stale pending
- If `childId in parent_index` (outcome path) → the real parent ID is the 2nd arg → don't track

---

## 2) Anomalies

### A2.1 `parentId` is the child's ID in outcome-relation `insert()`
When the FK engine processes the parent's outcome relations (parent's `getChildren()` list), it calls:
```java
resource.insert(childObjectId, parentObjectId, transaction);
```
Here the first argument (`parentId` in `registerForeignKey`) is actually the **child's ID**, not the parent's. My `isParentPersistedInIndex(parentId, tx)` was checking the child's ID against the parent's index → always `false` → incorrectly tracked as pending.

### A2.2 `childId` is the parent's ID in outcome-relation `insert()`
In the same outcome-relation call, `childId` = `parentObjectId` (the **real** parent's ID). This is the distinguishing signal: if `childId` exists in the parent's index, we're in the outcome-relation path and the parent IS persisted.

### A2.3 FK tree key-type mismatch (Long vs String)
The FK tree stores keys as raw strings from disk (the `restoreSearchKeyTypes` mechanism exists for search indexes but not FK trees). When `find()` compares raw String keys against a Long search key via `objectId.compareTo(key)`, it throws `ClassCastException: Long cannot be cast to String` because `String.compareTo()` expects a String argument.

**Impact:** Any FK existence check (`has()`, `read()`) against the FK tree directly fails for numeric IDs. The fix avoids reading the FK tree and instead checks the parent's own id-index (which has proper key restoration).

### A2.4 `isChildPersistedInIndex` was not needed; `isChildInParentIndex(childId, tx)` is the real discriminator
The original `isChildPersistedInIndex(childId, tx)` checked the child's index (always `true` for saved children). The real discriminator is `isChildInParentIndex(childId, tx)` — does the 2nd arg exist in the **parent's** index? For the outcome path, `childId` = parent's real ID → `true`. For the unsaved-parent path, `childId` = child's ID → `false`.

### A2.5 `isParentPersistedInIndex` returns `false` for both unsaved-parent AND outcome paths
Both paths have `parentId` NOT in the parent's index:
- Unsaved parent: `parentId` = parent's manual ID → not persisted → `false`
- Outcome path: `parentId` = child's ID → not in parent's index → `false`

This made the original `!parentInIndex && childInChildIndex` logic conflate the two cases.

---

## 3) What has to review

| Item | Details |
|------|---------|
| FK tree `find()` key restoration | FK tree nodes are read without `restoreSearchKeyTypes()`. The raw String keys cause CCE when compared against typed search keys. Either apply restoration or avoid reading the FK tree. |
| `insert(childId, parentId)` argument order in `processInsertParentObjectWithChildObject()` | This is by design (outcome-relation = parent's children list), but the swapped naming in `registerForeignKey` (`parentId` = first arg) is confusing. |
| `has()` method on `IXdStorageIndexDaoResource` vs `IXdStorageDaoResource` | The parent's index uses `IXdStorageIndexDaoResource.has(Object, Tx)`. The single-object references resource uses `IXdStorageDaoResource.read(Object, Tx) != null`. Both should work. |
| `isChildInParentIndex` vs `isChildPersistedInIndex` naming | `isChildPersistedInIndex(childId, tx)` is misleading — it checks child's ID in child's index. The real discriminator is `isChildInParentIndex(childId, tx)`. |
| `validatePendingFks` performance | Currently iterates pending set and checks each parent. For large FK graphs this could be O(n²). Could be optimized with a parent-set check. |
| Pre-existing test bugs | `OutcomeRelationsForeignKeyTest`, `IncomeAndObservabilityForeignKeyTest`, `StructuralGroupsForeignKeySpecificationTest` all have pre-existing infrastructure bugs (null factory, null resources manager) unrelated to this fix. |

---

## 4) What has to refactor

### R4.1 FK tree key restoration
The FK tree's `find()` should either:
- Apply `restoreSearchKeyTypes()` before comparison (like the search index does), OR
- Convert the search key to String format before comparison (fragile), OR
- Avoid reading the FK tree entirely and use the parent/child id-indexes instead (current fix)

### R4.2 Argument naming in `processInsertParentObjectWithChildObject()`
```java
// Current (confusing):
resource.insert(childObjectId, parentObjectId, transaction);
// Should be documented or renamed:
resource.insert(insertedChildId, referencingParentId, transaction);
```

### R4.3 `registerForeignKey` interface
The method signature `registerForeignKey(Comparable<?> parentId, Comparable<?> childId)` is misleading when called from the outcome-relation path where `parentId` = child's ID. Consider:
```java
// Option 1: Add a source flag
registerForeignKey(Comparable<?> parentRef, Comparable<?> childRef, boolean fromIncomeRelation, ...)

// Option 2: Document in Javadoc that arguments are positional, not semantic
```

### R4.4 `pendingFkRegistrations` as a `Set<String>`
Using string-encoded relation IDs (`parentId\u001FchildId`) is fragile. Consider:
```java
private final Map<String, Set<FkRelation>> pendingFkRegistrations = ...;
private record FkRelation(Comparable<?> parentRef, Comparable<?> childRef) {}
```

### R4.5 `validatePendingFks` could be moved to FK engine
The validation belongs conceptually in the FK engine's `process` method, not in the FK resource's phase-1 commit. Moving it would make the FK engine the single source of truth for FK integrity.

---

## 5) Which tests must be written to catch anomalies

### T5.1 FK registration with swapped arguments (outcome-relation path)
```java
@Test
void testOutcomeRelationRegistrationDoesNotTrackPending() {
    // Save parent (triggers outcome-relation FK registration with swapped args)
    // Verify no pending FK is registered for the parent
    // Verify commit succeeds (parent is visible in index)
}
```

### T5.2 Manual-ID parent not saved → FK rejected
```java
@Test
void testManualIdParentNotSavedThrowsOnCommit() {
    // Create child with manual-ID parent reference
    // Save child only (parent NOT saved)
    // commitTransaction should throw XdStorageRuntimeException
}
```

### T5.3 Manual-ID parent saved LATER in same transaction
```java
@Test
void testManualIdParentSavedLaterInSameTxSucceeds() {
    // Create child with manual-ID parent reference
    // Save child (FK registered as pending)
    // Save parent (pending cleared)
    // commitTransaction should NOT throw
}
```

### T5.4 FK tree CCE for numeric IDs
```java
@Test
void testFkTreeHasNoCceForNumericIds() {
    // Register FK with Long keys
    // Call read()/find() on the FK tree
    // Should not throw ClassCastException
}
```

### T5.5 Multiple pending FKs for same parent
```java
@Test
void testMultipleChildrenWithSameUnsavedParent() {
    // Create multiple children referencing same unsaved manual-ID parent
    // Save all children
    // commit should throw (parent not saved)
}
```

### T5.6 Parent saved in different transaction then referenced
```java
@Test
void testParentPersistedInPrevTxIsVisibleInCurrentFkCheck() {
    // Persist parent in tx1, commit
    // Create child referencing parent in tx2
    // Save child, commit — should succeed
}
```

### T5.7 Hash index vs BTree index consistency
```java
@Test
void testHashIndexFKValidationSameAsBTree() {
    // Run the same FK scenarios on both hash and BTree indexes
    // Verify identical behavior
}
```

---

## 6) Refactor and Fix Plan

### Phase 1 — Immediate fixes (done)
- [x] Add `isChildInParentIndex()` discriminator in `registerForeignKey`
- [x] Add `pendingFkRegistrations` map + `registerPendingFk`/`clearPendingFk`
- [x] Add `validatePendingFks()` called in `performFirstPhaseCommit`
- [x] Fix `fkRelationId` separator to avoid CCE
- [x] Add `isChildPersistedInIndex()` for the child's own index check (kept as defensive)
- [x] All FK tests green: 12/12 Manual-ID, 24/24 Save/Update/Delete scenarios

### Phase 2 — Refactoring (next)
1. Replace `Set<String> pendingFkRegistrations` with `Set<FkRelation>` typed wrapper
2. Move `validatePendingFks()` from FK resource to FK engine's `process()` method
3. Add `restoreSearchKeyTypes()` to FK tree's `read()` path (or document why it's not needed)
4. Rename `isChildPersistedInIndex` → `isChildIdInParentIndex` for clarity

### Phase 3 — Test additions (next)
1. Write T5.1–T5.7 above
2. Add a stress test: 1000 children referencing 100 unsaved parents → verify all rejected at commit
3. Add a negative test: FK with same-child-saved-later → verify not rejected

### Phase 4 — Architecture review
1. Audit: are there other places where FK is registered with swapped args?
2. Audit: does the FK tree's `insert()` method correctly handle all key types?
3. Consider: should the FK engine expose a `canRegisterForeignKey(parent, child, tx)` method?
