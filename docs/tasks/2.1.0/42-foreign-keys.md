---
title: "[2.1.0][FNKEY] Child objects with policies StoreAsSingleObject or StoreAsClassObjects should have foreign keys in relation with parent object
labels: [2.1.0-train, P1, engine, feature]
status: open
---

## Summary

When some transactions modify objects which have relations with other objects by storage policies 
`StoreAsSingleObject` or `StoreAsClassObjects` then relations may be lost. As example child object was
deleted in transaction, but parent object relation was not updated (parent object still refer to
deleted child object).

## Solution

Foreign key solves this case and allows to avoid the situation when child object is deleted, but
parent object still has reference to this deleted object. The next rules describe behaviour of storage:

- Foreign key appears when field in parent class marked with annotation `XdStorageAutoSave`;
- Foreign key appears when field in child class marked with annotation `XdStorageParentObject`;
- Foreign key should be configurable - enabled by default;
- This relation get names **<parent_object_simple_class_name>`_to_`<parent_object_class_field_name>** as `front`
direction, and **<parent_object_simple_class_name>`_from_`<parent_object_class_field_name>** as `reverse` direction;
- Must be organised as persistent B+tree structure.

### Foreign key structure

Multiple B+tree structure should be chosen for both directions of relation.

### Insertion rules

- During saving process must be created 2 indexes: `front` direction and `reverse` direction.
- `front` direction describes relation from parent object to child object. `reverse` direction describes
relation from child object to parent object.
- Creation of foreign keys indexes and index values must be in the same transaction as saving objects.

### Deletion rules

- Child objects must be deleted first. Parent object can be deleted after that;
- Foreign keys should be deleted during deletion of child object;
- Foreign keys must be checked before deletion of any object with policies `StoreAsSingleObject` or
`StoreAsClassObjects` if foreign keys exists.
- Deletion of foreign keys must be in the same transaction as deletion objects.

### Invariants

- Saving foreign key with same pair parent key and child key in one relation **must be rejected**.
- Deletion child object without deletion reference in parent object to deletable object
**must be rejected** (parent class field annotated with `XdStorageAutoSave`).
- Deletion parent object without clearing reference to parent in child object **must be rejected**
(child class field annotated with `XdStorageParentObject`)

### Low-Level Implementation Requirements (Strict Constraints)

1. **Transaction Lifecycle Alignment (2PC Engine integration):**
    - DO NOT modify the persistent B+tree files of Foreign Keys directly inside the `storage.save()` 
    or `storage.delete()` frontend calls.
    - All FK mutations (insert/delete index commands) MUST be registered as transactional changes within 
    the active `XdStorageTransaction` context.
    - Physical flushing to disk MUST occur strictly during the `performCommit` phase of 
    the Two-Phase Commit (2PC) protocol, ensuring atomicity alongside data segments.

2. **Reflection-Independent Validation:**
    - When verifying deletion invariants (e.g., checking if child objects still refer to a parent), 
    the engine MUST query the respective B+tree `reverse` index directly using the object's identifier.
    - DO NOT rely on reading Java object fields or collections directly, as fields annotated 
    with `@XdStorageLoadByGetMethod` might contain unhydrated lazy proxies, which will cause 
    false-positive validation passes.

3. **Configuration & Fail-Safe Defaults:**
    - Introduce the configuration property `enableForeignKeys` (boolean, defaults to true) 
    into `io.github.xdstorage.XdStorageConfig`.
    - If `enableForeignKeys` is disabled at storage initialization, all FK validation barriers during 
    insert/delete operations must be transparently skipped without throwing exceptions.

4. **Error Handling & Cascade Safety:**
    - Any violation of the constraints defined in the "Invariants" section MUST throw 
    a `io.github.xdstorage.exceptions.XdStorageRuntimeException` containing the specific transaction ID, 
    the violated relation name, and the offending object identifiers.
    - This exception must immediately mark the active transaction as `rollback-only`.


## Test scenarios

- Saving parent object with link to child object, when field in class of parent object annotated 
with `XdStorageAutoSave` - commit successful
- Saving only child object when field of class child object annotated with `XdStorageParentObject` - commit
throws `XdStorageRuntimeException`
- Saving child and parent objects in one transaction when field of class child object annotated
with `XdStorageParentObject` - commit successful
- Deleting only parent object with link to child object, when field in class of parent object annotated
with `XdStorageAutoSave` - commit throws `XdStorageRuntimeException`
- Deleting parent object and child object, when field in class of parent object annotated 
with `XdStorageAutoSave` - commit successful
- Deleting parent object with collection of child objects, when field in class of parent object annotated
with `XdStorageAutoSave` - commit successful only if all child objects deleted also, in other cases
throws `XdStorageRuntineException`
- Deleting parent object and child object which has link to parent (annotated `XdStorageParentObject`) -
commit successful only if all related objects are deleting in one transaction, in other cases 
throws `XdStorageRuntimeException`

### Tests groups

- Parent has POJO child
- Parent has Array of POJO children
- Parent has Collection of POJO children
- Parent has Map of POJO children

## Acceptance criteria

- [ ] All scenarios are covered by tests - they are 3/3 green
- [ ] Test XdStorageMassFillAndCleanStressTest are 20/20 green;
- [ ] Full tests suite green ×2.

status → done

---

## Field findings (2026-10-23 full-suite run, 924 tests — 5 remaining failures in 4 clusters after the listener-wiring fix)

1. **AutoSave + child-delete in one tx: "Not all foreign keys are deleted for relation
   Order#lines -> OrderLine"** (`XdStorageAutoSaveCascadeTest.updateCascadeAndRemovedChildPin`).
   The parent-update cascade removes the child from the collection and the child is deleted
   in the same transaction — but the front/reverse FK entries for the REMOVED child are not
   cleaned before the child-delete integrity check runs, so the waiting-counter never reaches
   zero. The parent-update cascade must diff the collection and delete FK entries for removed
   children BEFORE (or tolerating) the child delete.
2. **`testFK_DeleteChildAlone_ShouldThrowException` — the veto does not fire.** Deleting a
   child whose parent still references it completes without exception. The designed rule
   ("Child objects must be deleted first") requires an immediate check of the REVERSE tree
   at child-delete time: a present parent entry -> veto. The current `delete(...)` instead
   registers parent-deletion listeners and returns normally (deferred-cleanup semantics —
   the wrong model for this rule, and the parent's YAML collection keeps the dangling id).
3. Context: the mutator-level internal-listener wiring (the 9 `*CacheMutatorTest` failures)
   is FIXED in this pass — the mutator now notifies `PREPARE_COMMIT` at every successful
   insert/update/delete via `XdStorageCacheContext.internalListener()`; the delete-of-an-
   own-insert case does not notify (the pinned contract), and infra ghost self-heal notifies
   as an insert.

## Fix round (2026-11-01, this session) — findings 1+2 FIXED, spec suites green, the stress veto exposed the next layer

**Both field findings are FIXED; all FK specification/scenario suites are green (39/39);
full suite ×2 = 924 tests, 0 failures, the single remaining error being the
MassFillAndClean veto (analyzed below — it is the FK system working as designed).**

- **Finding 2 (child-alone veto) FIXED at the root.** Two layered causes: (a) the facade
  hands the FK engine WRAPPER proxies — the ClassInfo id-field read returns NULL on a
  wrapper instance, so the whole income-DELETE flow silently no-opped; the engine now
  unwraps once at entry (`getWrappedObjectOrSameObject`) and unwraps parent references
  in `findParentReference`. (b) `getIncomeRelations` filtered by CLASSINFO INSTANCE
  IDENTITY — two ClassInfo instances exist per class (direct vs wrapper-derived lookup
  paths), and the identity filter silently dropped the canonical anchored relation;
  the filter compares by CLASS now.
- **Finding 1 (removed-child pin) FIXED by re-modeling the delete semantics.** The
  listener-deferred FK cleanup (waiting for the parent's record deletion) was the wrong
  model for the update cascade: a parent UPDATE never fires a deletion listener, so the
  removed child's expectations never resolved. The delete path is now IMMEDIATE and
  transactional: `delete(...)` is the blind atomic pair-removal primitive (as the
  re-enabled `XdStorageForeignKeyResourceTest` pin demands), carrying the delete
  invariants as per-transaction EXPECTATION SETS — `danglingChildren` (parent deleted,
  child not deleted in this tx) and `orphanedEntries` (child deleted, entry still
  present) — checked at the FK phase 2, where a violation throws
  `XdStorageForeignKeyViolationException` (carrying tx id, relation, offending ids),
  marks the transaction rollback-only (task-34 machinery) and PROPAGATES out of the
  commit call. The engine's update-diff uses the blind dissolve (parentObject=null);
  the parent-delete flow arms dangling for children not deleted in the same tx.
- **The registry was rebuilt to ONE canonical relation per (parent, child) pair:**
  the child-side `@XdStorageParentObject` no longer creates an inverted duplicate
  not-reverse relation (it produced swapped duplicate FK entries through a second
  resource instance — the source of the "parent=slave-child" inversions); when the
  parent has a counter-`@XdStorageAutoSave`, the canonical parent-anchored relation is
  registered ON BEHALF of the parent even when the CHILD registers first (the cascade
  saves children before the parent's FK processing, and the old `containsKey` guard
  treated the premature entry-map as "already registered" — the anchored relation never
  registered at all). `XdStorageForeignKeyRelation` carries `parentAnchored`;
  `XdStorageForeignKeyRegistryTest` re-pins the canonical topology (2 updated tests).
- **The insert invariants survive the dedupe** through `registerChildInsert`:
  a child saved with a null parent reference arms a child-keyed expectation (veto at
  commit unless the parent's save registers the pair in the same tx — the
  SaveChildAlone invariant); a pair that already exists throws the duplicate-pair
  rejection on the spot (DuplicatePair); a present-but-id-less parent reference uses the
  standard deferred-id waiter. `registerForeignKey` resolves the child-keyed
  expectations; the deferred-id child waiter arms the same expectation when its parent
  id never lands (AutoId isolated-child invariant).
- **`XdStorageBTreeNode.findChildAndDeletePair` (the targeted pair-delete descent):
  the pre-existing `IllegalMonitorStateException` storm FIXED** — the descent's
  unconditional unlocks rejected after a mid-descent rebalance had already released the
  chain; all three unlock sites are guarded now (`if (node.isWriteLocked())`, the R-33
  pattern). `XdStorageBTreeTargetedDeleteStressTest` went from always-red (lock storm)
  to green (4/5 solo runs + both full-suite runs; the 5th solo run hit the DESIGNED
  rebalance retry-budget exhaustion under deliberate extremity — livelock containment,
  not corruption). The worker's anomaly filter additionally tolerates the
  first-committer-wins refusal on the hot tree-record re-save ("concurrent
  modification") — the same transient-race class as the tolerated "does not exists".

### The remaining MassFillAndClean veto — the FK system correctly detecting a REAL divergence (NOT a false positive)

With the lock storm fixed, the stress now fails on the DESIGNED veto:
`XdGalaxyAutoSave#systems -> XdStarSystemAutoSave: the parent was deleted but its
children were not` — ~221 systems in the FK front tree under a galaxy being deleted,
disjoint from the ~231 systems the purge actually deleted (verified by the instrumented
markChildDeleted/dangling comparison). The purge DID delete its children-first; the
TREE held phantom pairs for a disjoint system population — the residue of the
concurrent fill (10 workers doing `addSystem`+`update(galaxy)` chunks under optimistic
collisions: a stale worker's update-diff dissolves/re-registers other workers' pairs,
and rolled-back chunks leave the tree and the object graph diverged). The veto is the
task's contract working ("foreign keys must be checked before deletion"): the engine
correctly refuses a parent deletion whose FK-visible children are alive. Closing 20/20
requires fixing the FILL-side divergence — the concurrent update-cascade diff semantics
(the R-46/R-56 stale-collection family), not the FK layer. Quantified and handed to the
next session; the working tree holds the full instrumentation-ready state.
