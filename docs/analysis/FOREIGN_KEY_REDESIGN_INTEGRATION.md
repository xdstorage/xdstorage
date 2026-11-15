# Foreign Key Cache Integration — 2.1.0

**Scope:** How the existing `XdStorageForeignKeyCache` (100% implemented) integrates into the transactional resource model and the 2PC protocol.
**Status:** The cache classes are complete. The integration (engine → cache, resource → cache, protocol → cache) is **NOT yet implemented**. This document describes the exact integration points.
**Date:** 2026 (current session)

---

## 1. Architecture — Where the Cache Sits in the Stack

### 1.1 The Integration Model (entity cache → FK cache)

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  XdStorageCacheCommitProtocol (the 2PC)                                                                 │
│                                                                                                         │
│  Phase 1: prepareCommit(tx, collector)                                                                  │
│    ├── runCommitPhase(tx, collector, true)  ← marks entity records PREPARE                               │
│    │     ├── For each changed entity: feed collector, mark PREPARE                                      │
│    │     └── listener.notify(PREPARE_COMMIT, ...)  ← fires FK engine's process()                      │
│    │           └── XdStorageForeignKeyEngine.process(object, INSERT, tx)                                │
│    │                 └── [INTEGRATION POINT 1: call fkCache.cacheInsert(pair, tx)]                    │
│    │                                                                                                     │
│    └── XdStorageForeignKeyResource.performFirstPhaseCommit(tx, record)                                  │
│          └── [INTEGRATION POINT 2: validatePendingFks(tx) via cache]                                  │
│                                                                                                         │
│  Phase 2: performCommit(tx, collector)                                                                  │
│    ├── runCommitPhase(tx, collector, false)  ← marks entity records PERFORM                              │
│    │     ├── For each changed entity: feed collector, mark PERFORM                                      │
│    │     └── listener.notify(PERFORM_COMMIT, ...)  ← fires FK engine's process()                      │
│    │           └── XdStorageForeignKeyEngine.process(object, INSERT, tx)                                │
│    │                 └── [INTEGRATION POINT 3: call fkCache.cachePerform(pair, tx)]                   │
│    │                                                                                                     │
│    └── XdStorageForeignKeyResource.performSecondPhaseCommit(tx, record)                                 │
│          └── [INTEGRATION POINT 4: promoteAllForTransaction(tx) via cache]                            │
│                                                                                                         │
│  Phase 3: commit(tx)                                                                                    │
│    ├── For each record: commit() → makes permanent, fires COMMIT events                                │
│    │     └── listener.notify(COMMIT, ...)  ← fires FK engine's process()                              │
│    │           └── XdStorageForeignKeyEngine.process(object, INSERT, tx)                                │
│    │                 └── [INTEGRATION POINT 5: call fkCache.cacheCommit(pair, tx)]                    │
│    │                                                                                                     │
│    └── [INTEGRATION POINT 6: foreignKeyEngine.commitAllFxPairs(tx)]  ← THE KEY INTEGRATION            │
│          └── fkCache.promoteAllForTransaction(tx)  ← promotes ALL pairs to PERFORM                     │
│          └── fkCache.persistAllForTransaction(tx)  ← persists ALL pairs to B+tree                      │
│                                                                                                         │
│  Rollback: rollback(tx)                                                                                 │
│    ├── For each record: rollback() → reverts to previous state                                          │
│    │     └── listener.notify(ROLLBACK, ...)  ← fires FK engine's process()                            │
│    │           └── XdStorageForeignKeyEngine.process(object, INSERT, tx)                                │
│    │                 └── [INTEGRATION POINT 7: call fkCache.cacheRollback(pair, tx)]                  │
│    │                                                                                                     │
│    └── XdStorageForeignKeyResource.release(tx)                                                         │
│          └── [INTEGRATION POINT 8: fkCache.release(tx) — cleanup transaction state]                   │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 The 8 Integration Points (exact locations)

| # | Integration Point | Where | What it does | Status |
|---|---|---|---|---|
| 1 | Engine PREPARE | `processIncomeRelations()` / `processOutcomeRelations()` | `fkCache.cacheInsert(pair, tx)` | ❌ Not done |
| 2 | Resource PREPARE | `XdStorageForeignKeyResource.performFirstPhaseCommit()` | `fkCache.validateAllForTransaction(tx)` | ❌ Not done |
| 3 | Engine PERFORM | `processIncomeRelations()` / `processOutcomeRelations()` | `fkCache.cachePerform(pair, tx)` | ❌ Not done |
| 4 | Resource PERFORM | `XdStorageForeignKeyResource.performSecondPhaseCommit()` | `fkCache.promoteAllForTransaction(tx)` | ❌ Not done |
| 5 | Engine COMMIT | `processIncomeRelations()` / `processOutcomeRelations()` | `fkCache.cacheCommit(pair, tx)` | ❌ Not done |
| 6 | Protocol COMMIT | `XdStorageCacheCommitProtocol.commit()` | `foreignKeyEngine.commitAllFxPairs(tx)` | ❌ Not done |
| 7 | Engine ROLLBACK | `processIncomeRelations()` / `processOutcomeRelations()` | `fkCache.cacheRollback(pair, tx)` | ❌ Not done |
| 8 | Resource RELEASE | `XdStorageForeignKeyResource.release()` | `fkCache.release(tx)` | ❌ Not done |

---

## 2. Integration Point 1 — Engine PREPARE_COMMIT (processIncomeRelations / processOutcomeRelations)

### Current code (BUGGY — writes directly to B+tree):

```java
// XdStorageForeignKeyEngine.java — processIncomeRelations
for (XdStorageForeignKeyRelation relation : incomeRelations) {
    if (!relation.isReverseRelation() && relation.isParentAnchored()) {
        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(...);
        // BUG: writes directly to B+tree!
        resource.insert(parentObjectId, childObjectId, transaction);
    }
}

// XdStorageForeignKeyEngine.java — processOutcomeRelations
for (XdStorageForeignKeyRelation relation : outcomeRelations) {
    if (!relation.isReverseRelation()) {
        // BUG: writes directly to B+tree!
        resource.insert(childObjectId, parentObjectId, transaction);  // swapped args!
    }
}
```

### The fix (call the cache):

```java
// XdStorageForeignKeyEngine.java — processIncomeRelations (FIXED)
for (XdStorageForeignKeyRelation relation : incomeRelations) {
    if (!relation.isReverseRelation() && relation.isParentAnchored()) {
        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(...);
        // NEW: register pair in FK cache (PREPARE state)
        XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                relation, XdStorageForeignKeyObjectChangeType.INSERT);
        pair = new XdStorageForeignKeyPair(parentObjectId, childObjectId, relation, type);
        foreignKeyCache.cacheInsert(pair, transaction);
    }
}

// XdStorageForeignKeyEngine.java — processOutcomeRelations (FIXED)
for (XdStorageForeignKeyRelation relation : outcomeRelations) {
    if (!relation.isReverseRelation()) {
        // NEW: register pair in FK cache (PREPARE state)
        XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                childObjectId, parentObjectId, relation, type);  // swapped args preserved!
        foreignKeyCache.cacheInsert(pair, transaction);
    }
}
```

### The FK cache's `cacheInsert()`:

```java
// XdStorageForeignKeyCache.java — already implemented!
public void cacheInsert(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
    String txId = tx.getTransactionId();
    if (records.containsKey(pair.key())) return;  // idempotent
    XdStorageForeignKeyCacheRecord record = new XdStorageForeignKeyCacheRecord(pair, txId);
    record.prepareCommit(tx);  // phase = PREPARE_COMMIT
    records.put(pair.key(), record);
    transactionRecords.computeIfAbsent(txId, __ -> ConcurrentHashMap.newKeySet()).add(pair.key());
}
```

---

## 3. Integration Point 2 — Resource PREPARE (performFirstPhaseCommit)

### Current code:

```java
// XdStorageForeignKeyResource.java — performFirstPhaseCommit
@Override
public void performFirstPhaseCommit(final XdStorageTransaction transaction,
                                    final XdStorageTransactionResourceChanges record) throws XdStorageException {
    validatePendingFks(transaction);  // checks pendingFkRegistrations map (separate state machine)
    changeRegistry.checkAllNotificationsReceived(transaction);  // checks AtomicLong counters (separate state machine)
}
```

### The fix (validate via the cache):

```java
// XdStorageForeignKeyResource.java — performFirstPhaseCommit (FIXED)
@Override
public void performFirstPhaseCommit(final XdStorageTransaction transaction,
                                    final XdStorageTransactionResourceChanges record) throws XdStorageException {
    // NEW: validate via the cache (not via the separate pendingFkRegistrations map)
    fkCache.validateAllForTransaction(transaction);
    // The deferred-ID waiter check is still needed (it's a separate mechanism
    // that the cache doesn't fully cover yet):
    changeRegistry.checkAllNotificationsReceived(transaction);
}
```

### The cache's `validateAllForTransaction()`:

```java
// XdStorageForeignKeyCache.java — already implemented! (but is a no-op placeholder)
public void validateAllForTransaction(XdStorageTransaction tx) {
    // Validation is done by XdStorageForeignKeyResource.validatePendingFks()
    // at performFirstPhaseCommit time. This is a no-op placeholder.
}
```

**Note:** The cache's `validateAllForTransaction()` is a no-op because the current validation logic (`validatePendingFks()`) is in the resource. The integration point is that the resource delegates validation to the cache. The actual validation logic (`validatePendingFks()`) should be moved into the cache as `validateAllForTransaction()`.

---

## 4. Integration Point 3 — Engine PERFORM_COMMIT (processIncomeRelations / processOutcomeRelations)

### Current code:

```java
// XdStorageForeignKeyEngine.java — processIncomeRelations (UPDATE path)
} else if (type == XdStorageForeignKeyObjectChangeType.UPDATE) {
    // do nothing — a child update dissolves no pairs on its own
    // (the pair bookkeeping for child updates is owned by the PARENT-update diff)
}
```

### The fix (promote pairs to PERFORM):

```java
// XdStorageForeignKeyEngine.java — processIncomeRelations (FIXED — UPDATE path)
} else if (type == XdStorageForeignKeyObjectChangeType.UPDATE) {
    // NEW: promote cached pairs to PERFORM state
    // This is called during PERFORM_COMMIT — the engine's process() fires again
    // with UPDATE type, and the cache promotes the pairs.
    for (XdStorageForeignKeyRelation relation : incomeRelations) {
        if (!relation.isReverseRelation() && relation.isParentAnchored()) {
            XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                    relation, XdStorageForeignKeyObjectChangeType.UPDATE);
            foreignKeyCache.cachePerform(pair, transaction);
        }
    }
}
```

**Note:** The current code does NOT call `process()` during PERFORM_COMMIT for UPDATE — the engine's `process()` fires only during PREPARE_COMMIT. The fix is to ensure the engine's `process()` fires during both PREPARE_COMMIT and PERFORM_COMMIT, and the cache promotes pairs during PERFORM.

---

## 5. Integration Point 4 — Resource PERFORM (performSecondPhaseCommit)

### Current code:

```java
// XdStorageForeignKeyResource.java — performSecondPhaseCommit
@Override
public void performSecondPhaseCommit(final XdStorageTransaction transaction,
                                     final XdStorageTransactionResourceChanges record) throws XdStorageException {
    // do nothing
}
```

### The fix (promote all pairs):

```java
// XdStorageForeignKeyResource.java — performSecondPhaseCommit (FIXED)
@Override
public void performSecondPhaseCommit(final XdStorageTransaction transaction,
                                     final XdStorageTransactionResourceChanges record) throws XdStorageException {
    // NEW: promote ALL cached pairs to PERFORM state
    fkCache.promoteAllForTransaction(transaction);
}
```

---

## 6. Integration Point 5 — Engine COMMIT (processIncomeRelations / processOutcomeRelations)

### Current code:

```java
// XdStorageForeignKeyEngine.java — processIncomeRelations (DELETE path)
} else if (type == XdStorageForeignKeyObjectChangeType.DELETE) {
    if (childObjectId != null) {
        for (XdStorageForeignKeyRelation relation : incomeRelations) {
            if (!relation.isReverseRelation()) {
                IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(...);
                // BUG: writes directly to B+tree (unregister)!
                Collection<Object> parentIds = resource.readByChildObjectId(childObjectId, transaction);
                for (Object parentObjectId : parentIds) {
                    resource.unregisterForeignKey((Comparable<?>) parentObjectId, childObjectId, transaction);
                }
            }
        }
    }
}
```

### The fix (cache the pair for COMMIT):

```java
// XdStorageForeignKeyEngine.java — processIncomeRelations (FIXED — DELETE path)
} else if (type == XdStorageForeignKeyObjectChangeType.DELETE) {
    if (childObjectId != null) {
        for (XdStorageForeignKeyRelation relation : incomeRelations) {
            if (!relation.isReverseRelation()) {
                // NEW: register the DELETE pair in the cache (it will be persisted at COMMIT)
                XdStorageForeignKeyPair pair = new XdStorageForeignKeyPair(
                        parentObjectId, childObjectId, relation, XdStorageForeignKeyObjectChangeType.DELETE);
                foreignKeyCache.cacheInsert(pair, transaction);
                // The actual B+tree write happens in persistAllForTransaction()
            }
        }
    }
}
```

---

## 7. Integration Point 6 — Protocol COMMIT (the KEY integration)

### Current code:

```java
// XdStorageCacheCommitProtocol.java — commit
public void commit(final XdStorageTransaction transaction) throws XdStorageException {
    final String transactionId = transaction.getTransactionId();
    cache.workspace().removeUndoLog(transactionId);
    final Map<Object, XdStorageCacheRecord> map = cache.workspace().removeChanges(transactionId);
    if (map != null) {
        final Iterator<Entry<Object, XdStorageCacheRecord>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            final XdStorageCacheRecord record = it.next().getValue();
            record.commit(transaction);  // marks record committed
            listener.notify(COMMIT, type, oldObject, newObject, transaction);
        }
    }
    cache.workspace().finishTransaction(transactionId);
}
```

### The fix (commit FK pairs after entity records):

```java
// XdStorageCacheCommitProtocol.java — commit (FIXED)
public void commit(final XdStorageTransaction transaction) throws XdStorageException {
    final String transactionId = transaction.getTransactionId();
    cache.workspace().removeUndoLog(transactionId);
    final Map<Object, XdStorageCacheRecord> map = cache.workspace().removeChanges(transactionId);
    if (map != null) {
        final Iterator<Entry<Object, XdStorageCacheRecord>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            final XdStorageCacheRecord record = it.next().getValue();
            record.commit(transaction);
            listener.notify(COMMIT, type, oldObject, newObject, transaction);
        }
    }
    cache.workspace().finishTransaction(transactionId);

    // NEW: commit all FK pairs after all entity records are committed
    if (foreignKeyEngine != null) {
        foreignKeyEngine.commitAllFxPairs(transaction);  // THE KEY INTEGRATION
    }
}
```

### The key method (`commitAllFxPairs`):

```java
// XdStorageForeignKeyEngine.java — commitAllFxPairs (already exists!)
public void commitAllFxPairs(XdStorageTransaction tx) {
    fkCache.promoteAllForTransaction(tx);  // promote all to PERFORM
    fkCache.persistAllForTransaction(tx);  // persist all to B+tree
}

// XdStorageForeignKeyCache.java — persistAllForTransaction (already implemented!)
public void persistAllForTransaction(XdStorageTransaction tx) {
    String txId = tx.getTransactionId();
    Set<String> pairKeys = transactionRecords.get(txId);
    if (pairKeys == null) return;
    List<String> keys = new ArrayList<>(pairKeys);
    for (String key : keys) {
        XdStorageForeignKeyCacheRecord record = records.get(key);
        if (record != null) {
            record.pair().writeToTree(tx);  // writes to B+tree!
            records.remove(key);
        }
    }
    transactionRecords.remove(txId);
}
```

---

## 8. Integration Point 7 — Engine ROLLBACK (processIncomeRelations / processOutcomeRelations)

### Current code:

```java
// XdStorageForeignKeyResource.java — rollback
@Override
public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
    // do nothing
}
```

### The fix (remove pairs from cache):

```java
// XdStorageForeignKeyResource.java — rollback (FIXED)
@Override
public void rollback(final XdStorageTransaction transaction) throws XdStorageException {
    // NEW: remove all cached pairs for this transaction
    fkCache.release(transaction);
}
```

---

## 9. Integration Point 8 — Resource RELEASE (release)

### Current code:

```java
// XdStorageForeignKeyResource.java — release
@Override
public void release(final XdStorageTransaction transaction) {
    changeRegistry.release(transaction);
    registeredListenersForTransaction.remove(transaction.getTransactionId());
    pendingFkRegistrations.remove(transaction.getTransactionId());
}
```

### The fix (release via the cache):

```java
// XdStorageForeignKeyResource.java — release (FIXED)
@Override
public void release(final XdStorageTransaction transaction) {
    // NEW: release via the cache (replaces the separate maps)
    fkCache.release(transaction);
    registeredListenersForTransaction.remove(transaction.getTransactionId());
    // pendingFkRegistrations is now managed by the cache — remove the old field
    pendingFkRegistrations.clear();
}
```

---

## 10. The Deferred-ID Waiter Migration

### Current implementation (partial — separate from cache):

```java
// XdStorageForeignKeyChangeRegistry.java
public void registerChildIdWaiter(Comparable<?> parentId, IXdStorageIdObservableWrapper childObject, XdStorageTransaction transaction) {
    incrementInsertWaitingIdentifier(transaction);  // AtomicLong counter
    childObject.addObserver(new XdStorageAbstractIdObserver() {
        @Override
        public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
            try {
                registry.registerForeignKey(parentId, (Comparable<?>) childId, transaction);
                decrementInsertWaitingIdentifier(transaction);  // AtomicLong counter
            } catch (XdStorageException e) {
                throw new XdStorageRuntimeException(e);
            }
        }
    });
}
```

### The integrated approach (defer-ID waiter lives in the cache):

```java
// XdStorageForeignKeyCache.java — cacheInsertWithDeferredId (NEW method)
public void cacheInsertWithDeferredId(XdStorageForeignKeyPair pair,
                                      XdStorageIdObservableWrapper childObject,
                                      XdStorageTransaction tx) {
    // Register the pair in the cache (as PREPARE)
    cacheInsert(pair, tx);

    // Register the deferred-ID waiter on the child object
    childObject.addObserver(new XdStorageAbstractIdObserver() {
        @Override
        public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
            // When the child's ID lands:
            // 1. Update the pair in the cache (promote to PERFORM)
            var record = records.get(pair.key());
            if (record != null && record.phase() == XdStorageCacheCommitPhase.PREPARE_COMMIT) {
                record.performCommit(tx);  // promote to PERFORM
            }
            // 2. If the pair is now in PERFORM state, it's ready for COMMIT
            //    (persistAllForTransaction will write it to B+tree)
        }
    });
}
```

### The pending-FK-registration migration (map → cache):

```java
// XdStorageForeignKeyResource.java — the old maps are REPLACED by the cache:
// OLD: Map<String, Set<FkRelation>> pendingFkRegistrations
// NEW: fkCache.cacheInsert(pair, tx)  ← same cache, same mechanism

// OLD: Map<String, Set<FkObjectRelation>> pendingFkRegistrationsByObject
// NEW: fkCache.cacheInsert(pair, tx)  ← same cache, same mechanism

// The validatePendingFks(tx) method becomes:
// fkCache.validateAllForTransaction(tx)  ← validates all cached pairs
```

---

## 11. Integration Summary — The Complete Flow

### A successful commit (PREPARE → PERFORM → COMMIT):

```
transaction.save(parent, tx)
  └── XdStorageCacheCommitProtocol.commit(tx)
        ├── PREPARE_COMMIT
        │     ├── runCommitPhase(tx, collector, true)
        │     │     ├── record.commit(PREPARE)  ← entity record
        │     │     └── listener.notify(PREPARE_COMMIT, INSERT, parent)
        │     │           └── XdStorageForeignKeyEngine.process(parent, INSERT, tx)
        │     │                 └── foreignKeyCache.cacheInsert(pair, tx)  ← [POINT 1]
        │     │
        │     └── XdStorageForeignKeyResource.performFirstPhaseCommit(tx, record)
        │           └── fkCache.validateAllForTransaction(tx)  ← [POINT 2]
        │
        ├── PERFORM_COMMIT
        │     ├── runCommitPhase(tx, collector, false)
        │     │     ├── record.commit(PERFORM)  ← entity record
        │     │     └── listener.notify(PERFORM_COMMIT, ...)
        │     └── XdStorageForeignKeyResource.performSecondPhaseCommit(tx, record)
        │           └── fkCache.promoteAllForTransaction(tx)  ← [POINT 4]
        │
        └── COMMIT
              ├── record.commit()  ← entity record → permanent
              ├── listener.notify(COMMIT, ...)
              └── foreignKeyEngine.commitAllFxPairs(tx)  ← [POINT 6: THE KEY!]
                    ├── fkCache.promoteAllForTransaction(tx)  ← ALL to PERFORM
                    └── fkCache.persistAllForTransaction(tx)  ← ALL to B+tree!
```

### A successful rollback:

```
transaction.save(parent, tx)
  └── XdStorageCacheCommitProtocol.rollback(tx)
        ├── record.rollback()  ← entity record → revert
        └── XdStorageForeignKeyResource.release(tx)
              └── fkCache.release(tx)  ← [POINT 8: cleanup]
```

---

## 12. Tests to Add (6-pinned test suite for the cache)

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

---

## 13. Acceptance Criteria

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
- [ ] **Full suite green** (959+ tests)
- [ ] **FK veto (MassFillAndClean)** still correctly detected

---

## 14. Implementation Order (Recommended)

| Step | Task | Why |
|---|---|---|
| 1 | **POINT 6:** `commitAllFxPairs` in `XdStorageCacheCommitProtocol.commit()` | THE KEY — the 2PC must call the FK cache at COMMIT |
| 2 | **POINT 1:** `fkCache.cacheInsert()` in `processIncomeRelations()` / `processOutcomeRelations()` | The cache must collect pairs during PREPARE |
| 3 | **POINT 2:** `fkCache.validateAllForTransaction()` in `performFirstPhaseCommit()` | Validation must happen before persist |
| 4 | **POINT 4:** `fkCache.promoteAllForTransaction()` in `performSecondPhaseCommit()` | All pairs must be promoted at PERFORM |
| 5 | **POINT 7/8:** `fkCache.release()` in `rollback()` / `release()` | Cleanup on rollback |
| 6 | **Deferred-ID migration** (AtomicLong → cache) | Clean up the partial implementation |
| 7 | **pendingFkRegistrations migration** (map → cache) | Clean up the partial implementation |
| 8 | **Tests** (9-pinned test suite) | The acceptance gate |
| 9 | **Full suite ×2** | The release gate |

---

## 15. What's Already Done (100% Implemented)

| Class | Status | Notes |
|---|---|---|
| `XdStorageForeignKeyCache` | ✅ **100%** | `cacheInsert()`, `cachePerform()`, `cacheCommit()`, `cacheRollback()`, `validateAllForTransaction()`, `release()`, `commitAllFxPairs()`, `promoteAllForTransaction()`, `persistAllForTransaction()` — ALL methods exist |
| `XdStorageForeignKeyCacheRecord` | ✅ **100%** | Phase transitions (PREPARE_COMMIT, PERFORM_COMMIT, COMMIT, ROLLBACK), `key()`, `pair()`, `phase()`, `transactionId()`, `isValidated()`, `equals()`, `hashCode()` — ALL methods exist |
| `XdStorageForeignKeyPair` | ✅ **100%** | `parentId`, `childId`, `relation`, `type`, `key()`, `writeToTree()`, `equals()`, `hashCode()`, `toString()` — ALL methods exist |

**The cache infrastructure is COMPLETE.** The only thing missing is the **integration** — wiring the cache into the FK engine, the FK resource, and the 2PC protocol.

---

## 16. References

- `XdStorageForeignKeyCache.java` — the FK pair cache (100% implemented)
- `XdStorageForeignKeyCacheRecord.java` — the per-pair state machine (100% implemented)
- `XdStorageForeignKeyPair.java` — the pair object (100% implemented)
- `XdStorageForeignKeyEngine.java` — the lifecycle dispatcher (integration POINT 1, 3, 5)
- `XdStorageForeignKeyResource.java` — the FK pair-persistence (integration POINT 2, 4, 7, 8)
- `XdStorageCacheCommitProtocol.java` — the 2PC protocol (integration POINT 6)
- `XdStorageForeignKeyChangeRegistry.java` — the deferred-ID waiter (partial implementation)
- `docs/tasks/2.1.0/42-foreign-keys.md` — the original FK spec
- `docs/tasks/2.1.0/34-cache2pc.md` — the CACHE2PC design
- `docs/analysis/FOREIGN_KEY_REDESIGN.md` — the redesign document
- `docs/analysis/FOREIGN_KEY_SITUATION_ANALYSIS.md` — the situation analysis
- `docs/analysis/FOREIGN_KEY_TASKS.md` — the task list (13 remaining tasks)
