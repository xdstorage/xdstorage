# Foreign Key Cache — Listeners, Waiters, and Integration with the Transactional Resource Model

**Date:** 2026 (current session)
**Scope:** How FK cache records receive phase notifications, how deferred-ID waiters promote pairs, and how the FK cache integrates into the listener/observer architecture.
**Prerequisite:** `FOREIGN_KEY_REDESIGN_INTEGRATION.md` — the integration points are defined there. This document is the **implementation guide** for listeners and waiters.

---

## 1. Architecture — The Resource Manager and Resource Lifecycle

### 1.1 The Resource Manager (`XdStorageAbstractResourcesManager`)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│  XdStorageAbstractResourcesManager (registry + lock point of ALL open resources)   │
│                                                                                     │
│  Resources:                                                                       │
│    • IXdStorageDaoResource — base data (StoreAsClassObjects/StoreAsSingleObject)  │
│    • IXdStorageIndexResource — id-index (hash or BTree)                          │
│    • IXdStorageSearchIndexResource — search index                                │
│    • IXdStorageResource — references (StoreAsSingleObject parent/child bookkeeping)│
│    • IXdStorageForeignKeyResource — FK pairs (B+tree front + reverse)            │
│                                                                                     │
│  Lock model:                                                                      │
│    • internalLockResource() creates on first use, registers into transaction      │
│    • Registration adds resource to transaction.forCommit queue (priority queue)  │
│    • Lock acquisition during commit: REVERSE order (shared resources first)      │
│      — the fix from RECIDENT (task 47): acquisition is reverse, execution is forward│
│                                                                                     │
│  Resource lifecycle (per transaction):                                            │
│    1. internalLockResource() — creates (first use) / returns existing, registers   │
│    2. resource.prepare(tx) — loads file into cache (idempotent)                  │
│    3. resource.performFirstPhaseCommit(tx, record) — writes changes (phase 1)   │
│    4. resource.performSecondPhaseCommit(tx, record) — publishes changes (phase 2)│
│    5. resource.unlockAfterCommit(tx) — releases commit lock                      │
│    6. resource.release(tx) — clears transaction contours, decrements usage count │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 The Transaction Commit Protocol (`XdStorageTransaction.commitInternal`)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│  XdStorageTransaction.commitInternal() — THE COMMIT ORCHESTRATOR                    │
│                                                                                     │
│  Phase 0: Lock acquisition (REVERSE commit order)                                   │
│    forCommit.drain() → sorted reverse → lockForCommit() for each                   │
│    → lockedResources = [resource1, resource2, ...]                                │
│                                                                                     │
│  Phase 1: performFirstPhaseCommit() — write to disk (invisible to others)           │
│    for each resource in COMMIT ORDER:                                              │
│      record = new XdStorageTransactionResourceChanges(resource)                     │
│      record.setCommitSerial(commitSerial)  ← ALL records get ONE serial             │
│      resource.performFirstPhaseCommit(tx, record)  ← the actual write              │
│      firstPhaseCommittedResources.put(resourceId, record)                         │
│                                                                                     │
│  Phase 2: performSecondPhaseCommit() — publish to cache (visible to others)        │
│    for each resource in COMMIT ORDER:                                              │
│      resource.performSecondPhaseCommit(tx, record)  ← publish                      │
│                                                                                     │
│  Phase 3: unlockAfterCommit() — release all locks                                   │
│    for each resource in lockedResources: unlockAfterCommit(tx)                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 The Resource Types and Their Commit Orders

| Resource Type | Commit Order | Purpose |
|---|---|---|
| `DATA_RESOURCE_ORDER` (1000) | 1st | Base data of entities (class-shared fragments + per-object files) |
| `CHILDREN_DATA_RESOURCE_ORDER` (2000) | 2nd | Children embedded in parents (committed after parent base data) |
| `CROSS_DATASOURCE_FK_RESOURCE_ORDER` (2500) | 3rd | FK pairs spanning different datasources |
| `INDEX_RESOURCE_ORDER` (3000) | 4th | Id-index resources (after all data) |
| `SEARCH_INDEX_RESOURCE_ORDER` (5000) | 5th | Search-index resources (last, after data + id-index) |
| `FOREIGN_KEY_RESOURCE_ORDER` (7500) | 6th | FK B+tree resources (last — after everything they point at) |

**Critical ordering rule:** FK resources are committed **LAST** (order 7500). This is intentional — they point at data that must already be durable when the FK is written. The FK pair `parentId → childId` references records that must exist in their respective resources.

### 1.4 Lock Acquisition Order (REVERSE)

```
Acquisition (lock): REVERSE order (shared first, unique last)
  FOREIGN_KEY_RESOURCE_ORDER (7500) ← locked LAST (unique per resource)
  SEARCH_INDEX_RESOURCE_ORDER (5000)
  INDEX_RESOURCE_ORDER (3000)
  CROSS_DATASOURCE_FK_RESOURCE_ORDER (2500)
  CHILDREN_DATA_RESOURCE_ORDER (2000)
  DATA_RESOURCE_ORDER (1000) ← locked FIRST (shared across many transactions)

Execution (commit): FORWARD order (shared first, unique last)
  DATA_RESOURCE_ORDER (1000) ← written FIRST
  CHILDREN_DATA_RESOURCE_ORDER (2000)
  CROSS_DATASOURCE_FK_RESOURCE_ORDER (2500)
  INDEX_RESOURCE_ORDER (3000)
  SEARCH_INDEX_RESOURCE_ORDER (5000)
  FOREIGN_KEY_RESOURCE_ORDER (7500) ← written LAST
```

**Why REVERSE acquisition?** RECIDENT (task 47): if acquisition is forward, a transaction acquires its unique resources first and then WAITS for shared ones — every waiter behind it inherits the stall, cascading past transaction budgets. Reverse acquisition means a transaction only holds unique/fast resources while waiting for shared ones; the order stays uniform across all transactions, so it remains deadlock-free.

---

## 2. The Listener/Notification Architecture

### 2.1 The `IXdStorageInternalListener` Interface

```java
public interface IXdStorageInternalListener {
    void notify(XdStorageCacheCommitPhase phase,
                XdStorageObjectOperationType change,
                Object oldObject, Object newObject,
                IXdStorageTransaction transaction);
}
```

**The `notify()` method is called from `XdStorageCacheCommitProtocol.runCommitPhase()`:**

```java
private void runCommitPhase(final XdStorageTransaction transaction,
                            final XdStorageTransactionResourceChanges collector,
                            final boolean prepare) {
    for each changed record:
        // Feed the collector
        collector.addChangeObject(type, record.getId(), oldObject, newObject);

        // Mark the record's phase
        if (prepare) {
            record.prepareCommit(transaction);  // phase = PREPARE_COMMIT
            listener.notify(PREPARE_COMMIT, type, oldObject, newObject, transaction);  // ← FIRE!
        } else {
            record.performCommit(transaction);  // phase = PERFORM_COMMIT
            listener.notify(PERFORM_COMMIT, type, oldObject, newObject, transaction);  // ← FIRE!
        }
    }
}
```

**The listener chain:**
```
XdStorageCacheCommitProtocol.runCommitPhase()
  └── listener.notify(PREPARE_COMMIT, INSERT, old, new, tx)
        └── XdStorageCacheContext.internalListener (set by XdStorageCacheContext.setInternalListener())
              └── XdStorageTriggerManager.performTriggers(tx, changes)
                    └── For each change (INSERT/UPDATE/DELETE):
                          └── trigger → engine.process(object, type, tx)
                                ├── incomeRelations (child's @XdStorageParentObject)
                                │     └── FK engine.process() ← THE FK ENTRY POINT
                                └── outcomeRelations (parent's @XdStorageAutoSave)
                                      └── FK engine.process() ← THE FK ENTRY POINT
```

### 2.2 The Trigger Chain (where FK pairs are registered)

```
transaction.save(parent, tx)
  └── XdStorageTransaction.commitTransaction(tx)
        └── XdStorageCacheCommitProtocol.commit(tx)
              └── for each resource:
                    └── resource.performFirstPhaseCommit(tx, record)
                          └── runCommitPhase(tx, record, prepare=true)
                                └── listener.notify(PREPARE_COMMIT, INSERT, parent, parent, tx)
                                      └── XdStorageTriggerManager.performTriggers(tx, changes)
                                            └── For each change:
                                                  └── engine.process(parent, INSERT, tx)  ← FK ENTRY
                                                        ├── processIncomeRelations()
                                                        └── processOutcomeRelations()
                                                              └── resource.insert(parentId, childId, tx)  ← CURRENT (BUGGY)
                                                                    └── DIRECTLY into B+tree!
```

### 2.3 The `IXdStorageListenableResource` Interface

```java
public interface IXdStorageListenableResource {
    void registerListener(IXdStorageInternalListener listener, IXdStorageTransaction transaction);
}
```

**This is the mechanism by which a resource registers a listener for phase notifications.** The listener is called during PREPARE_COMMIT and PERFORM_COMMIT phases for every change the resource sees.

### 2.4 The `IXdStorageInternalListener` Notification Model

| Phase | What it means | When it fires |
|---|---|---|
| `PREPARE_COMMIT` | Changes are durably persisted (phase 1) but NOT yet visible to other transactions | During `runCommitPhase(tx, collector, true)` |
| `PERFORM_COMMIT` | Changes are published to the cache (phase 2), visible to other transactions | During `runCommitPhase(tx, collector, false)` |
| `COMMIT` | Changes are permanent (all resources committed) | During `commit(tx)` in `XdStorageCacheCommitProtocol.commit()` |

---

## 3. The FK Cache Record — Receiving Phase Notifications

### 3.1 The FK Cache Record's Phase State Machine

```java
// XdStorageForeignKeyCacheRecord.java
public final class XdStorageForeignKeyCacheRecord {
    private volatile XdStorageCacheCommitPhase phase;

    void prepareCommit(XdStorageTransaction tx) { phase = PREPARE_COMMIT; }
    void performCommit(XdStorageTransaction tx) { phase = PERFORM_COMMIT; }
    void commit(XdStorageTransaction tx) { phase = COMMIT; }
    void rollback(XdStorageTransaction tx) { phase = PREPARE_COMMIT; }  // reset

    String key() { return pair.key(); }
    XdStorageForeignKeyPair pair() { return pair; }
    XdStorageCacheCommitPhase phase() { return phase; }
    String transactionId() { return transactionId; }
    boolean isValidated() { return validated; }
}
```

### 3.2 How the Cache Receives Phase Notifications

The FK cache is NOT a resource in the traditional sense — it is NOT registered into the transaction's `forCommit` queue. Instead, it is a **standalone state machine** that receives notifications through the FK engine's `process()` method:

```java
// The FK engine's process() is called from XdStorageTriggerManager.performTriggers()
// which is called from XdStorageCacheContext.internalListener.notify()

// The FK engine's process() calls:
//   processIncomeRelations() / processOutcomeRelations()
// which call:
//   foreignKeyCache.cacheInsert(pair, tx)  ← registers in PREPARE
//   foreignKeyCache.cachePerform(pair, tx) ← promotes to PERFORM
//   foreignKeyCache.cacheCommit(pair, tx)  ← persists to B+tree

// The FK cache does NOT receive notifications from the resource listener chain.
// It receives them from the FK engine's process() which is called from the listener chain.
```

### 3.3 The Integration Point — Where the Cache Gets Notified

The FK cache receives notifications through **3 integration points** (from `FOREIGN_KEY_REDESIGN_INTEGRATION.md`):

| # | Integration Point | Method | When |
|---|---|---|---|
| 1 | PREPARE | `fkCache.cacheInsert(pair, tx)` | During `processIncomeRelations()` / `processOutcomeRelations()` in PREPARE_COMMIT |
| 3 | PERFORM | `fkCache.cachePerform(pair, tx)` | During `processIncomeRelations()` / `processOutcomeRelations()` in PERFORM_COMMIT |
| 5 | COMMIT | `fkCache.cacheCommit(pair, tx)` | During `processIncomeRelations()` / `processOutcomeRelations()` in COMMIT |

**The critical insight:** The FK cache is NOT a resource. It is a **standalone state machine** that is driven by the FK engine's `process()` method. The FK engine's `process()` is called from the listener chain (`XdStorageTriggerManager.performTriggers()` → `XdStorageCacheContext.internalListener.notify()`).

### 3.4 The Cache's `commitAllFxPairs()` — The Key Integration Point

The FK cache's `commitAllFxPairs()` is called from `XdStorageCacheCommitProtocol.commit()` **AFTER** all entity records are committed:

```java
// XdStorageCacheCommitProtocol.commit(tx)
public void commit(final XdStorageTransaction transaction) throws XdStorageException {
    // Phase 1: commit all entity records (already done above)
    cache.workspace().removeUndoLog(transactionId);
    for each record: record.commit(transaction);

    // Phase 2: commit all FK pairs (THE KEY INTEGRATION POINT)
    if (foreignKeyEngine != null) {
        foreignKeyEngine.commitAllFxPairs(transaction);
    }
}

// XdStorageForeignKeyEngine.commitAllFxPairs(tx)
public void commitAllFxPairs(XdStorageTransaction tx) {
    fkCache.promoteAllForTransaction(tx);  // promote ALL pairs to PERFORM
    fkCache.persistAllForTransaction(tx);  // persist ALL pairs to B+tree
}
```

**This is the single point where the FK cache transitions from "cached" to "persisted."** All FK pairs for a transaction are promoted to PERFORM state and then written to the B+tree.

---

## 4. The Deferred-ID Waiter — Migrating to the FK Cache

### 4.1 The Current Implementation (PARTIAL — separate from cache)

```java
// XdStorageForeignKeyChangeRegistry.java
public void registerChildIdWaiter(Comparable<?> parentId,
                                  IXdStorageIdObservableWrapper childObject,
                                  XdStorageTransaction transaction) {
    incrementInsertWaitingIdentifier(transaction);  // AtomicLong counter
    childObject.addObserver(new XdStorageAbstractIdObserver() {
        @Override
        public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
            try {
                registry.registerForeignKey(parentId, (Comparable<?>) childId, transaction);
                decrementInsertWaitingIdentifier(transaction);
            } catch (XdStorageException e) {
                throw new XdStorageRuntimeException(e);
            }
        }
    });
}
```

**The problem:** The AtomicLong counter (`insertWaitingIdentifiers`) is a separate state machine from the FK cache. It is not wired to the cache phases. The FK cache's `validateAllForTransaction()` is a no-op placeholder.

### 4.2 The Correct Implementation (defer-ID waiter lives in the FK cache)

```java
// XdStorageForeignKeyCache.java — cacheInsertWithDeferredId (NEW method)
public void cacheInsertWithDeferredId(XdStorageForeignKeyPair pair,
                                      XdStorageIdObservableWrapper childObject,
                                      XdStorageTransaction tx) {
    // 1. Register the pair in the cache (as PREPARE)
    cacheInsert(pair, tx);

    // 2. Register the deferred-ID waiter on the child object
    childObject.addObserver(new XdStorageAbstractIdObserver() {
        @Override
        public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
            // When the child's ID lands:
            // a) Update the pair in the cache (promote to PERFORM)
            var record = records.get(pair.key());
            if (record != null && record.phase() == XdStorageCacheCommitPhase.PREPARE_COMMIT) {
                record.performCommit(tx);  // promote to PERFORM
            }
            // b) If the pair is now in PERFORM state, it's ready for COMMIT
            //    (persistAllForTransaction will write it to B+tree)
        }
    });
}
```

**The migration path:**
- **Phase 1:** The FK cache's `cacheInsertWithDeferredId()` is called instead of `changeRegistry.registerChildIdWaiter()`.
- **Phase 2:** The FK cache's `validateAllForTransaction()` is called at `performFirstPhaseCommit()` time. It validates that all deferred-ID waiters have resolved (the AtomicLong counter is replaced by checking `record.phase() == PERFORM_COMMIT`).
- **Phase 3:** The FK cache's `commitAllFxPairs()` is called at `commit()` time. It promotes all pairs to PERFORM and writes them to the B+tree.

### 4.3 The `pendingFkRegistrations` Migration (map → cache)

```java
// The current pendingFkRegistrations map is a SEPARATE state machine from the cache.
// It tracks pairs where the parent's ID is not yet set.
// These pairs should be registered in the FK cache instead.

// OLD: pendingFkRegistrations.put(txId, setOfPairs)
// NEW: fkCache.cacheInsert(pair, tx)  ← same cache, same mechanism

// The validatePendingFks(tx) method becomes:
// fkCache.validateAllForTransaction(tx)  ← validates all cached pairs
```

---

## 5. The FK Resource's Listener Registration (for UPDATE path)

### 5.1 The Current Implementation (for the UPDATE path)

The current implementation registers listeners on the parent's index resource to detect when the parent's reference to the child is removed:

```java
// XdStorageForeignKeyChangeRegistry.java — registerUpdateListener
public void registerUpdateListener(XdStorageResource resource,
                                   Comparable<?> parentObjectId,
                                   Comparable<?> childObjectId,
                                   XdStorageObjectIdField parentIdField,
                                   IXdStorageTransaction transaction) {
    incrementDeleteWaitingIdentifier(transaction);  // AtomicLong counter
    resource.registerListener(new IXdStorageInternalListener() {
        @Override
        public void notify(XdStorageCacheCommitPhase phase,
                           XdStorageObjectOperationType change,
                           Object oldObject, Object newObject,
                           IXdStorageTransaction transaction) {
            if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                    && change == XdStorageObjectOperationType.Update) {
                Object pId = oldObject == null ? parentObjectId : parentIdField.get(oldObject);
                if (pId.equals(parentObjectId)
                        && isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                    try {
                        registry.unregisterForeignKey(parentObjectId, childObjectId, transaction);
                        decrementDeleteWaitingIdentifier(transaction);
                    } catch (XdStorageException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }, transaction);
}
```

### 5.2 The Correct Implementation (integrated into the FK cache)

The listener should be registered in the FK cache, not in the resource:

```java
// XdStorageForeignKeyCache.java — registerUpdateListener (NEW method)
public void registerUpdateListener(XdStorageForeignKeyPair pair,
                                   XdStorageResource resource,
                                   XdStorageObjectIdField parentIdField,
                                   IXdStorageTransaction transaction) {
    // Register the listener on the parent's resource
    resource.registerListener(new IXdStorageInternalListener() {
        @Override
        public void notify(XdStorageCacheCommitPhase phase,
                           XdStorageObjectOperationType change,
                           Object oldObject, Object newObject,
                           IXdStorageTransaction transaction) {
            if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                    && change == XdStorageObjectOperationType.Update) {
                // Check if the parent's reference to the child was removed
                if (isReferenceToChildObjectDeleted(newObject, clParentField, clChildInfo, childObjectId)) {
                    // Promote the pair to PERFORM (the parent's update removed the reference)
                    var record = records.get(pair.key());
                    if (record != null && record.phase() == XdStorageCacheCommitPhase.PREPARE_COMMIT) {
                        record.performCommit(tx);  // promote to PERFORM
                    }
                }
            }
        }
    }, transaction);
}
```

---

## 6. Implementation Guide — Step by Step

### Step 1: Wire the FK Cache into the Listener Chain

```java
// XdStorageForeignKeyEngine.java — processIncomeRelations (FIXED)
private void processIncomeRelations(...) {
    for (XdStorageForeignKeyRelation relation : incomeRelations) {
        if (!relation.isReverseRelation() && relation.isParentAnchored()) {
            if (childObjectId != null) {
                // NEW: register in FK cache (PREPARE state)
                XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                        parentObjectId, childObjectId, relation, XdStorageForeignKeyObjectChangeType.INSERT);
                foreignKeyCache.cacheInsert(pair, transaction);
            } else {
                // Deferred-ID: register in FK cache with observer
                XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                        relation, XdStorageForeignKeyObjectChangeType.INSERT);
                IXdStorageIdObservableWrapper observable = XdStorageObserverService.getObservableWrapper(object);
                foreignKeyCache.cacheInsertWithDeferredId(pair, observable, transaction);
            }
        }
    }
}
```

### Step 2: Wire the FK Cache into the FK Resource's performFirstPhaseCommit

```java
// XdStorageForeignKeyResource.java — performFirstPhaseCommit (FIXED)
@Override
public void performFirstPhaseCommit(final XdStorageTransaction transaction,
                                    final XdStorageTransactionResourceChanges record) throws XdStorageException {
    // NEW: validate via the cache (replaces validatePendingFks + checkAllNotificationsReceived)
    foreignKeyCache.validateAllForTransaction(transaction);
    // The validate throws if any pair's parent is not persisted
    // or any deferred-ID waiter did not resolve
}
```

### Step 3: Wire the FK Cache into the FK Resource's performSecondPhaseCommit

```java
// XdStorageForeignKeyResource.java — performSecondPhaseCommit (FIXED)
@Override
public void performSecondPhaseCommit(final XdStorageTransaction transaction,
                                     final XdStorageTransactionResourceChanges record) throws XdStorageException {
    // NEW: promote ALL pairs to PERFORM state
    foreignKeyCache.promoteAllForTransaction(transaction);
}
```

### Step 4: Wire the FK Cache into the 2PC Protocol

```java
// XdStorageCacheCommitProtocol.java — commit (FIXED)
public void commit(final XdStorageTransaction transaction) throws XdStorageException {
    // ... existing commit logic ...

    // NEW: commit all FK pairs after all entity records are committed
    if (foreignKeyEngine != null) {
        foreignKeyEngine.commitAllFxPairs(transaction);
    }

    // ... rest of existing commit logic ...
}
```

### Step 5: Wire the FK Cache into the FK Resource's release

```java
// XdStorageForeignKeyResource.java — release (FIXED)
@Override
public void release(final XdStorageTransaction transaction) {
    // NEW: release via the cache (replaces registeredListenersForTransaction.remove(...) + pendingFkRegistrations.remove(...))
    foreignKeyCache.release(transaction);
}
```

### Step 6: Migrate the Deferred-ID Waiter

```java
// XdStorageForeignKeyChangeRegistry.java — registerChildIdWaiter (FIXED)
public void registerChildIdWaiter(Comparable<?> parentId,
                                  IXdStorageIdObservableWrapper childObject,
                                  XdStorageTransaction transaction) {
    // NEW: delegate to the FK cache
    foreignKeyCache.cacheInsertWithDeferredId(
            new XdStorageForeignKeyPair(parentId, null, relation, INSERT),
            childObject, transaction);
}
```

### Step 7: Migrate the pendingFkRegistrations

```java
// XdStorageForeignKeyResource.java — the old maps are REPLACED by the cache:
// OLD: Map<String, Set<FkRelation>> pendingFkRegistrations
// NEW: fkCache.cacheInsert(pair, tx)  ← same cache, same mechanism

// OLD: Map<String, Set<FkObjectRelation>> pendingFkRegistrationsByObject
// NEW: fkCache.cacheInsert(pair, tx)  ← same cache, same mechanism
```

---

## 7. Tests to Add (9-pinned test suite)

| Test | What | Integration point |
|---|---|---|
| `testCacheInsertAndCommit` | A pair registered in cache is persisted to B+tree at COMMIT | POINT 6 |
| `testCacheInsertAndRollback` | A pair registered in cache is NOT persisted at ROLLBACK | POINT 7 |
| `testCacheInsertDuplicate` | A duplicate pair registration is idempotent (no double-write) | POINT 1 |
| `testCachePerformPromotes` | `cachePerform()` promotes PREPARE → PERFORM | POINT 3 |
| `testCachePromoteAll` | `promoteAllForTransaction()` promotes ALL pairs to PERFORM | POINT 4 |
| `testCacheValidateThrows` | `validateAllForTransaction()` throws when a pair's parent is missing | POINT 2 |
| `testCacheRelease` | `release()` clears all pairs for a transaction | POINT 8 |
| `testDeferredIdWaiter` | A deferred-ID waiter promotes the pair to PERFORM when ID lands | Deferred-ID |
| `testPendingFkMapMigrated` | `pendingFkRegistrations` is fully replaced by the cache | Migration |
| `testUpdateListenerRemovesPair` | A listener on the parent's resource removes the FK pair when reference is cleared | Listener |
| `testCommitAllFxPairsInOrder` | `commitAllFxPairs()` promotes to PERFORM then persists to B+tree | POINT 6 |
| `testCommitAllFxPairsIdempotent` | Calling `commitAllFxPairs()` twice is a no-op (pairs already persisted) | POINT 6 |

---

## 8. Acceptance Criteria

- [ ] **POINT 1:** `processIncomeRelations()` / `processOutcomeRelations()` call `fkCache.cacheInsert(pair, tx)` instead of `resource.insert()`
- [ ] **POINT 2:** `performFirstPhaseCommit()` delegates to `fkCache.validateAllForTransaction(tx)`
- [ ] **POINT 3:** `processIncomeRelations()` / `processOutcomeRelations()` call `fkCache.cachePerform(pair, tx)` during UPDATE path
- [ ] **POINT 4:** `performSecondPhaseCommit()` calls `fkCache.promoteAllForTransaction(tx)`
- [ ] **POINT 5:** `processIncomeRelations()` / `processOutcomeRelations()` call `fkCache.cacheInsert(pair, tx)` during DELETE path
- [ ] **POINT 6:** `commit()` in `XdStorageCacheCommitProtocol` calls `foreignKeyEngine.commitAllFxPairs(tx)`
- [ ] **POINT 7:** `rollback()` in `XdStorageForeignKeyResource` calls `fkCache.release(tx)`
- [ ] **POINT 8:** `release()` in `XdStorageForeignKeyResource` calls `fkCache.release(tx)`
- [ ] **Deferred-ID waiter:** migrated to cache (no more separate AtomicLong counters)
- [ ] **pendingFkRegistrations:** migrated to cache (no more separate map)
- [ ] **Update listener:** migrated to FK cache (no more separate listeners)
- [ ] **Full suite green** (959+ tests)
- [ ] **FK veto (MassFillAndClean)** still correctly detected

---

## 9. References

- `XdStorageForeignKeyCache.java` — the FK pair cache (100% implemented)
- `XdStorageForeignKeyCacheRecord.java` — the per-pair state machine (100% implemented)
- `XdStorageForeignKeyPair.java` — the pair object (100% implemented)
- `XdStorageForeignKeyEngine.java` — the lifecycle dispatcher (integration POINT 1, 3, 5)
- `XdStorageForeignKeyResource.java` — the FK pair-persistence (integration POINT 2, 4, 7, 8)
- `XdStorageCacheCommitProtocol.java` — the 2PC protocol (integration POINT 6)
- `XdStorageForeignKeyChangeRegistry.java` — the deferred-ID waiter (partial implementation)
- `IXdStorageInternalListener.java` — the listener interface (the notification contract)
- `IXdStorageListenableResource.java` — the listenable resource interface
- `IXdStorageIdObservableWrapper.java` — the deferred-ID wrapper interface
- `XdStorageAbstractIdObserver.java` — the deferred-ID observer base class
- `XdStorageObserverService.java` — the observer service (JVM-global wrapper registry)
- `XdStorageCacheCommitPhase.java` — the phase enum (PREPARE_COMMIT, PERFORM_COMMIT, COMMIT)
- `XdStorageResourceCommitOrder.java` — the commit order constants
- `XdStorageAbstractResourcesManager.java` — the resource manager
- `XdStorageTransaction.java` — the transaction commit protocol
- `docs/tasks/2.1.0/42-foreign-keys.md` — the original FK spec
- `docs/tasks/2.1.0/34-cache2pc.md` — the CACHE2PC design
- `docs/tasks/2.1.0/47-record-identity-disarmed-phases.md` — the record-identity fix
- `docs/analysis/FOREIGN_KEY_REDESIGN.md` — the redesign document
- `docs/analysis/FOREIGN_KEY_REDESIGN_INTEGRATION.md` — the integration points document
- `docs/analysis/FOREIGN_KEY_SITUATION_ANALYSIS.md` — the situation analysis
- `docs/analysis/FOREIGN_KEY_TASKS.md` — the task list (13 remaining tasks)
