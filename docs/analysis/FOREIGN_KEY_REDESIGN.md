# Foreign Key Redesign — 2.1.0 (Invalid behavior: registered on PREPARE_COMMIT → must go through cache phases)

**Current bug:** All FK pairs are registered **directly** in the B+tree during `PREPARE_COMMIT` phase (via `resource.insert(parentId, childId, transaction)` in `XdStorageForeignKeyEngine.processIncomeRelations()` / `processOutcomeRelations()`). This is invalid — all pairs **MUST** go through the cache phase pipeline (PREPARE_COMMIT → PERFORM_COMMIT → COMMIT) like all entities do, so the 2PC can validate them before persisting to disk.

---

## UPDATE 2026-11-16 — IMPLEMENTED (with one documented deviation)

The integration is DONE: the shared FK write-set (`XdStorageForeignKeyCache`, owned by
`XdStorageForeignKeyResourcesManager`, per-transaction keys, INSERT/DELETE intents with
later-wins), the engine/resource split restored (the engine calls the resource; the
resource stages intents), phase-1 = the FULL validation trio (validatePendingFks + the
write-set structural validation + the waiter counters), phase-2 = promoteAll, POINT 6 =
`XdStorageCacheCommitProtocol.commit()` → `foreignKeyEngine.commitAllFxPairs` (promote +
write-set close), POINT 7/8 = rollback/release discard the write-set, plus the read-through
overlay for `readByParentObjectId`/`readByChildObjectId`.

**The documented deviation (contour-correct persistence):** this document assumed the FK
trees are dumb storage that can be written once at COMMIT. In THIS engine the FK trees are
**infrastructure caches with their own 2PC contour** — mutating them after their resources'
phases ran orphanes the tree records ("Illegal state of record to commit", proven in the
first integration run: the orphaned tree-object record poisoned every later committer).
Therefore the pair's tree write happens at REGISTRATION time (operation time — inside the
contour window), becomes invisible-until-commit through the node caches' MVCC, rolls back
with the transaction through the W-4a journals, and is made permanent by the trees' own
commit — which IS the commit phase of the pair's lifecycle. The write-set adds what the
trees do not provide: the in-transaction read-through overlay, the phase-gated intent
state, the structural phase-1 validation, and the explicit rollback/release hygiene.
A second defect this work fixed at the engine level: `XdStorageSmartCloner.fillAndWrap`
left the hydrated reference wrapper marked "unpacked", so any later `cloneAndWrap`
re-packed the stored state over the caller's mutations (the update-window
`resource.read` resurrected `setParent(null)` — the ClearLink family veto).

**Status table (the integration points):** POINT 1 ✅ (the resource stages intents at
registration), POINT 2 ✅ (phase-1 full trio), POINT 3/4 ✅ (promoteAll at phase-2),
POINT 5 ✅ (dissolution stages DELETE intents), POINT 6 ✅ (the protocol commit call),
POINT 7 ✅ (rollback discards), POINT 8 ✅ (release discards, per-transaction). The
deferred-ID waiters and `pendingFkRegistrations` stay in the resource/ChangeRegistry by
design: they validate UNRESOLVED pairs that have nothing to stage yet; the write-set
covers resolved pairs. Pinned tests: `XdStorageForeignKeyCacheTest` (10) + the
real-storage estate (91 tests across 27 classes, green ×3).

---

---

## 0. Implementation Status (PRE-INTEGRATION SNAPSHOT — kept for the record)

| Class | Status | Notes |
|---|---|---|
| `XdStorageForeignKeyCache` | ✅ **100% implemented** | `cacheInsert()`, `cachePerform()`, `cacheCommit()`, `cacheRollback()`, `validateAllForTransaction()`, `release()`, `commitAllFxPairs()`, `promoteAllForTransaction()`, `persistAllForTransaction()` — ALL methods exist |
| `XdStorageForeignKeyCacheRecord` | ✅ **100% implemented** | Phase transitions (PREPARE_COMMIT, PERFORM_COMMIT, COMMIT, ROLLBACK), `key()`, `pair()`, `phase()`, `transactionId()`, `isValidated()`, `equals()`, `hashCode()` — ALL methods exist |
| `XdStorageForeignKeyPair` | ✅ **100% implemented** | `parentId`, `childId`, `relation`, `type`, `key()`, `writeToTree()`, `equals()`, `hashCode()`, `toString()` — ALL methods exist |

**The cache infrastructure is COMPLETE.** The only thing missing is the **integration** — wiring the cache into the FK engine, the FK resource, and the 2PC protocol.

### What has been done (100%):
- `XdStorageForeignKeyCache` — the FK pair cache (all methods implemented)
- `XdStorageForeignKeyCacheRecord` — the per-pair state machine (all methods implemented)
- `XdStorageForeignKeyPair` — the pair object (all methods implemented)
- `XdStorageForeignKeyPair.writeToTree()` — writes pair to B+tree (front + reverse)
- `XdStorageForeignKeyCache.persistAllForTransaction()` — persists all pairs to B+tree
- `XdStorageForeignKeyCache.commitAllFxPairs()` — promotes to PERFORM + persists

### What has NOT been done (integration missing):
- ❌ The FK engine does NOT call `fkCache.cacheInsert(pair, tx)` — it still calls `resource.insert()` directly
- ❌ The FK engine does NOT call `fkCache.cachePerform(pair, tx)` during UPDATE
- ❌ The FK engine does NOT call `fkCache.cacheCommit(pair, tx)` during COMMIT
- ❌ The FK engine does NOT call `fkCache.cacheRollback(pair, tx)` during ROLLBACK
- ❌ `XdStorageCacheCommitProtocol.commit()` does NOT call `foreignKeyEngine.commitAllFxPairs(tx)`
- ❌ `XdStorageForeignKeyResource.performFirstPhaseCommit()` does NOT call `fkCache.validateAllForTransaction(tx)`
- ❌ `XdStorageForeignKeyResource.performSecondPhaseCommit()` does NOT call `fkCache.promoteAllForTransaction(tx)`
- ❌ `XdStorageForeignKeyResource.release()` does NOT call `fkCache.release(tx)`
- ❌ The deferred-ID waiter (`changeRegistry.registerChildIdWaiter()`) is NOT migrated to the cache
- ❌ The `pendingFkRegistrations` map is NOT migrated to the cache
- ❌ The update listener (`changeRegistry.registerUpdateListener()`) is NOT migrated to the cache

### What needs to be done (integration):
- **9 integration points** to wire the FK cache into the engine, resource, and 2PC protocol
- **3 migration tasks**: deferred-ID waiter, pendingFkRegistrations, update listener
- **9 tests** for the cache (listed in `FOREIGN_KEY_REDESIGN_INTEGRATION.md`)
- **11 tests** for listeners and waiters (listed in `FOREIGN_KEY_LISTENERS_WAITERS.md`)

---

## 1. Current State — How FK Pairs Flow Now (BUGGY)

### 1.1 The current flow (PREPARE_COMMIT only)

```
transaction.save(parent, tx)
  └── XdStorageCacheCommitProtocol.commit(tx)
        ├── PREPARE_COMMIT (phase 1)
        │     ├── runCommitPhase(tx, collector, prepare=true)  ← marks records prepare-committed
        │     │     └── listener.notify(PREPARE_COMMIT, INSERT, ...)
        │     │           └── XdStorageForeignKeyEngine.process(object, INSERT, tx)
        │     │                 ├── processIncomeRelations → resource.insert(parentId, childId, tx)
        │     │                 │     └── **DIRECTLY inserts into B+tree** (BUG: should go to cache)
        │     │                 └── processOutcomeRelations → resource.insert(parentId, childId, tx)
        │     │                       └── **DIRECTLY inserts into B+tree** (BUG: should go to cache)
        │     │
        │     └── XdStorageForeignKeyResource.performFirstPhaseCommit(tx, record)
        │           ├── validatePendingFks(tx)  ← checks pendingFkRegistrations map
        │           └── changeRegistry.checkAllNotificationsReceived(tx)  ← checks AtomicLong counters
        │
        ├── PERFORM_COMMIT (phase 2)
        │     └── runCommitPhase(tx, collector, prepare=false)  ← marks records perform-committed
        │           └── listener.notify(PERFORM_COMMIT, ...)
        │
        └── COMMIT (phase 3)
              └── commit(tx)  ← makes records permanent, fires COMMIT events
```

**The bug:** `resource.insert(parentId, childId, tx)` writes DIRECTLY to the B+tree. It does NOT:
1. Register the pair in the cache (`XdStorageCacheObjectChange`)
2. Wait for the pair to reach PERFORM_COMMIT confirmation
3. Wait for the pair to reach COMMIT confirmation

The current code has a partial mitigation (`pendingFkRegistrations` map + `validatePendingFks()` + `checkAllNotificationsReceived()`) but these are **not** the same as the cache phase pipeline. The `pendingFkRegistrations` map is a separate state machine that is not wired to the cache phases.

### 1.2 The partial mitigation that exists (and its gaps)

| Mechanism | What it does | Why it's not the fix |
|---|---|---|
| `pendingFkRegistrations` map | Tracks pending FK pairs for manual-ID parents not yet persisted | Separate from cache; not wired to phases |
| `pendingFkRegistrationsByObject` map | Tracks pending FK pairs where parent has no ID yet | Separate from cache; not wired to phases |
| `validatePendingFks(tx)` | Checks if all pending FK pairs have their parents persisted | Only runs at `performFirstPhaseCommit`, not at COMMIT |
| `changeRegistry.checkAllNotificationsReceived(tx)` | Checks deferred-ID waiter counters | Only runs at `performFirstPhaseCommit`, not at COMMIT |

These mechanisms are **additive patches** on top of the invalid behavior. The correct fix is to route all FK pairs through the cache phase pipeline.

---

## 2. Redesign Rules

### 2.1 The FK Cache (XdStorageForeignKeyCache)

Implement a dedicated FK cache that stores pairs exactly like entities are stored:

```java
// XdStorageForeignKeyCache.java — the FK pair cache (mirrors XdStorageResourceCache)
public final class XdStorageForeignKeyCache {
    // The cache stores FK pairs as XdStorageForeignKeyCacheRecord objects
    // Each record has a phase state: PREPARE → PERFORM → COMMIT (or ROLLBACK)
    private final Map<String, XdStorageForeignKeyCacheRecord> records = new ConcurrentHashMap<>();

    // Per-transaction state (mirrors XdStorageCacheRecord phase flags)
    // Key = transactionId, Value = set of record IDs that this transaction staged
    private final Map<String, Set<String>> transactionRecords = new ConcurrentHashMap<>();

    // The cache is the authoritative source of truth for FK pairs in a transaction
    // before they are persisted to B+tree at COMMIT phase
    // This is the same model as XdStorageResourceCache: the cache is the
    // transaction's write-set for FK pairs, just as XdStorageResourceCache is
    // the transaction's write-set for entity records
}
```

### 2.2 XdStorageForeignKeyCacheRecord (mirrors XdStorageCacheRecord)

```java
// XdStorageForeignKeyCacheRecord.java — a single FK pair in the cache
public final class XdStorageForeignKeyCacheRecord {
    // The pair being tracked
    private final FxStorageForeignKeyPair pair;  // parentId, childId, relation
    // The phase state (same as XdStorageCacheRecord)
    private volatile XdStorageCacheCommitPhase phase;  // PREPARE | PERFORM | COMMIT | ROLLBACK
    // The transaction that owns this record
    private final String transactionId;
    // Whether the pair has been validated (parent exists, child not orphaned, etc.)
    private volatile boolean validated;

    // Phase transitions (same as XdStorageCacheRecord)
    void prepareCommit(XdStorageTransaction tx) { phase = XdStorageCacheCommitPhase.PREPARE; }
    void performCommit(XdStorageTransaction tx) { phase = XdStorageCacheCommitPhase.PERFORM; }
    void commit(XdStorageTransaction tx) { phase = XdStorageCacheCommitPhase.COMMIT; }
    void rollback(XdStorageTransaction tx) { phase = XdStorageCacheCommitPhase.ROLLBACK; }

    // The pair's identity (for cache lookup)
    @Override
    boolean equals(Object o) { ... }  // by (parentId, childId, relation)
    int hashCode() { ... }
}
```

### 2.3 The XdStorageForeignKeyPair (the pair object)

```java
// XdStorageForeignKeyPair.java — the FK pair (mirrors the existing XdStorageForeignKeyRelation)
public final class XdStorageForeignKeyPair {
    final Comparable<?> parentId;
    final Comparable<?> childId;
    final XdStorageForeignKeyRelation relation;  // the relation descriptor (classFrom, classTo, field)
    final XdStorageForeignKeyObjectChangeType type;  // INSERT | UPDATE | DELETE

    // The pair's state machine:
    // INSERT: pair is being registered (child without parent, or parent without children)
    // UPDATE: pair is being modified (parent reference changed)
    // DELETE: pair is being dissolved (child deleted, parent deleted)

    // Validation:
    // - For INSERT: parent must exist (or be pending-registered)
    // - For DELETE: child must be deleted in same transaction (or parent must be deleted)
    // - For UPDATE: pair must exist before the update
}
```

### 2.4 Phase-by-phase rules

#### PREPARE_COMMIT

```
XdStorageCacheCommitProtocol.prepareCommit(tx, collector)
  └── For every changed entity:
        ├── PREPARE_COMMIT event fires
        │     └── XdStorageForeignKeyEngine.process(entity, type, tx)  ← still called here
        │           └── BUT: instead of resource.insert() → DIRECTLY into B+tree
        │              → resource.cacheInsert(pair) → XdStorageForeignKeyCache.addRecord(pair, tx)
        │
        └── XdStorageForeignKeyResource.performFirstPhaseCommit(tx, record)
              ├── validatePendingFks(tx)  ← VALIDATE: all pairs reach PREPARE state
              │     ├── splits relationId keys
              │     ├── checks parentExists in parent index (committed + staged)
              │     └── throws XdStorageRuntimeException on violation
              │
              └── changeRegistry.checkAllNotificationsReceived(tx)  ← VALIDATE: all waiters received
                    ├── insertWaitingIdentifiers > 0 → NOT all FKs registered (child with null parent)
                    └── deleteWaitingIdentifiers > 0 → NOT all FKs deleted (orphaned parent ref)
```

**The PREPARE phase does:**
1. **Cache the pair:** `resource.cacheInsert(pair)` registers the pair in `XdStorageForeignKeyCache` as PREPARE state
2. **Validate:** `validatePendingFks(tx)` checks all pending pairs have their parents persisted
3. **Check waiters:** `checkAllNotificationsReceived(tx)` checks all deferred-ID waiters resolved
4. **Do NOT write to B+tree yet:** the pair stays in the cache, not in the tree

**The PREPARE phase throws if:**
- A pair's parent does not exist (neither persisted nor pending-registered)
- A pair's child is not deleted (but the pair is being dissolved)
- A deferred-ID waiter did not resolve (child with null parent, parent's ID never landed)

#### PERFORM_COMMIT

```
XdStorageCacheCommitProtocol.performCommit(tx, collector)
  └── For every changed entity:
        └── PERFORM_COMMIT event fires
              └── XdStorageForeignKeyEngine.process(entity, type, tx)  ← called again
                    └── resource.cachePerform(pair) → XdStorageForeignKeyCache.promote(pair, PERFORM)
```

**The PERFORM phase does:**
1. **Promote pair to PERFORM state:** the pair is now confirmed for commit
2. **No validation needed:** PREPARE already validated
3. **No B+tree write yet:** the pair stays in cache

**The PERFORM phase does nothing else.** This is the "confirmation" phase where the engine acknowledges the pair is ready to be persisted.

#### COMMIT

```
XdStorageCacheCommitProtocol.commit(tx)
  └── For every changed entity:
        └── COMMIT event fires
              └── XdStorageForeignKeyEngine.process(entity, type, tx)  ← called again
                    └── resource.cacheCommit(pair) → XdStorageForeignKeyCache.persist(pair)
                          └── **NOW** writes pair to B+tree
```

**The COMMIT phase does:**
1. **Persist the pair:** `resource.cacheCommit(pair)` writes the pair to the B+tree (front + reverse)
2. **Clean up cache:** remove the record from `XdStorageForeignKeyCache`
3. **Clean up transaction state:** remove `pendingFkRegistrations` and `pendingFkRegistrationsByObject` entries

**The COMMIT phase throws if:**
- The pair's parent was never persisted (rollback of the INSERT)
- The pair's child was never deleted (rollback of the DELETE)

#### ROLLBACK

```
XdStorageCacheCommitProtocol.rollback(tx)
  └── For every changed entity:
        └── ROLLBACK event fires
              └── resource.cacheRollback(pair) → XdStorageForeignKeyCache.remove(pair)
```

**The ROLLBACK phase does:**
1. **Remove the pair from cache:** the pair is discarded
2. **No B+tree write:** the pair never reached COMMIT, so it never touches the tree
3. **Clean up transaction state:** remove all pending registrations

---

## 3. Implementation Details

### 3.1 XdStorageForeignKeyCache (the cache)

```java
// src/main/java/io/github/xdstorage/foreignkey/XdStorageForeignKeyCache.java
public final class XdStorageForeignKeyCache {
    private final Map<String, XdStorageForeignKeyCacheRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> transactionRecords = new ConcurrentHashMap<>();

    /** Register a pair for a transaction (called during PREPARE_COMMIT). */
    void cacheInsert(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
        String txId = tx.getTransactionId();
        // Check for duplicate pairs (same as current validatePendingFks logic)
        // If the pair already exists in the cache, skip (idempotent)
        if (records.containsKey(pair.key())) return;

        // Register in the cache
        var record = new XdStorageForeignKeyCacheRecord(pair, txId);
        record.prepareCommit(tx);
        records.put(pair.key(), record);
        transactionRecords.computeIfAbsent(txId, __ -> ConcurrentHashMap.newKeySet()).add(pair.key());
    }

    /** Promote a pair to PERFORM state (called during PERFORM_COMMIT). */
    void cachePerform(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
        var record = records.get(pair.key());
        if (record != null) {
            record.performCommit(tx);
        }
    }

    /** Persist a pair to B+tree and remove from cache (called during COMMIT). */
    void cacheCommit(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
        var record = records.get(pair.key());
        if (record != null) {
            // Validate one more time before persisting (safety net)
            validateBeforePersist(pair, tx);
            // Write to B+tree
            pair.writeToTree(tx);
            // Remove from cache
            records.remove(pair.key());
            transactionRecords.get(pair.key()).remove(pair.key());
        }
    }

    /** Remove a pair from cache (called during ROLLBACK). */
    void cacheRollback(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
        var record = records.get(pair.key());
        if (record != null) {
            record.rollback(tx);
            records.remove(pair.key());
            transactionRecords.get(pair.key()).remove(pair.key());
        }
    }

    /** Validate all pairs for a transaction before persisting (called at COMMIT). */
    void validateAllForTransaction(XdStorageTransaction tx) {
        var pairKeys = transactionRecords.get(tx.getTransactionId());
        if (pairKeys == null) return;
        for (var key : pairKeys) {
            var pair = records.get(key);
            if (pair != null) {
                validatePair(pair, tx);
            }
        }
    }
}
```

### 3.2 XdStorageForeignKeyCacheRecord (the record)

```java
// src/main/java/io/github/xdstorage/foreignkey/XdStorageForeignKeyCacheRecord.java
public final class XdStorageForeignKeyCacheRecord {
    private final XdStorageForeignKeyPair pair;
    private final String transactionId;
    private volatile XdStorageCacheCommitPhase phase;

    XdStorageForeignKeyCacheRecord(XdStorageForeignKeyPair pair, String txId) {
        this.pair = pair;
        this.transactionId = txId;
        this.phase = XdStorageCacheCommitPhase.NONE;
    }

    void prepareCommit(XdStorageTransaction tx) {
        phase = XdStorageCacheCommitPhase.PREPARE;
    }

    void performCommit(XdStorageTransaction tx) {
        phase = XdStorageCacheCommitPhase.PERFORM;
    }

    void commit(XdStorageTransaction tx) {
        phase = XdStorageCacheCommitPhase.COMMIT;
    }

    void rollback(XdStorageTransaction tx) {
        phase = XdStorageCacheCommitPhase.ROLLBACK;
    }

    // The pair's identity key (for cache lookup)
    String key() { return pair.key(); }
    XdStorageForeignKeyPair pair() { return pair; }
    XdStorageCacheCommitPhase phase() { return phase; }
    String transactionId() { return transactionId; }

    // The same equals/hashCode as XdStorageForeignKeyPair (by parentId, childId, relation)
    @Override
    boolean equals(Object o) { return pair.equals(o); }
    int hashCode() { return pair.hashCode(); }
}
```

### 3.3 XdStorageForeignKeyEngine changes

```java
// src/main/java/io/github/xdstorage/foreignkey/XdStorageForeignKeyEngine.java

// NEW FIELD: the FK cache
private final XdStorageForeignKeyCache fkCache;

// NEW: the cache-insert method (replaces resource.insert())
private void cacheInsertPair(XdStorageForeignKeyRelation relation,
                              XdStorageForeignKeyObjectChangeType type,
                              XdStorageTransaction tx) {
    var pair = new XdStorageForeignKeyPair(relation, type);
    fkCache.cacheInsert(pair, tx);
}

// The processIncomeRelations method (REPLACED):
private void processIncomeRelations(XdStorageClassInfo clInfo, Object object,
                                    XdStorageForeignKeyObjectChangeType type,
                                    Collection<XdStorageForeignKeyRelation> incomeRelations,
                                    XdStorageTransaction transaction) {
    // ... (same logic to find relations) ...
    for (XdStorageForeignKeyRelation relation : incomeRelations) {
        if (!relation.isReverseRelation() && relation.isParentAnchored()) {
            // OLD: resource.insert(parentObjectId, childObjectId, transaction);
            // NEW: cacheInsertPair(relation, type, transaction);
            cacheInsertPair(relation, type, transaction);
        }
    }
}

// The processOutcomeRelations method (REPLACED):
private void processOutcomeRelations(XdStorageClassInfo clInfo, Object object,
                                     XdStorageForeignKeyObjectChangeType type,
                                     Collection<XdStorageForeignKeyRelation> outcomeRelations,
                                     XdStorageTransaction transaction) {
    // ... (same logic to find relations) ...
    for (XdStorageForeignKeyRelation relation : outcomeRelations) {
        if (!relation.isReverseRelation()) {
            // OLD: resource.insert(parentObjectId, childObjectId, transaction);
            // NEW: cacheInsertPair(relation, type, transaction);
            cacheInsertPair(relation, type, transaction);
        }
    }
}
```

### 3.4 XdStorageForeignKeyResource changes

```java
// src/main/java/io/github/xdstorage/foreignkey/XdStorageForeignKeyResource.java

// The performFirstPhaseCommit (CHANGED):
@Override
public void performFirstPhaseCommit(final XdStorageTransaction transaction,
                                    final XdStorageTransactionResourceChanges record) {
    // OLD: validatePendingFks(tx) + checkAllNotificationsReceived(tx)
    // NEW: validate via the cache
    fkCache.validateAllForTransaction(transaction);
    // The validation throws if any pair's parent is not persisted
    // or any deferred-ID waiter did not resolve
}

// The performSecondPhaseCommit (CHANGED):
@Override
public void performSecondPhaseCommit(final XdStorageTransaction transaction,
                                     final XdStorageTransactionResourceChanges record) {
    // Promote all pairs to PERFORM state
    fkCache.promoteAllForTransaction(transaction);
}

// The COMMIT phase (NEW method on the engine, not on the resource):
// This is called from XdStorageCacheCommitProtocol.commit(tx) after all records are committed
public void commitAllFxPairs(XdStorageTransaction tx) {
    fkCache.persistAllForTransaction(tx);
}

// The release (CHANGED):
@Override
public void release(final XdStorageTransaction transaction) {
    // OLD: registeredListenersForTransaction.remove(...) + pendingFkRegistrations.remove(...)
    // NEW: cache cleans up its own transaction state
    fkCache.release(transaction);
}
```

### 3.5 XdStorageCacheCommitProtocol integration

```java
// src/main/java/io/github/xdstorage/resource/cache/XdStorageCacheCommitProtocol.java

// In commit(tx) — after all records are committed, before unlock:
public void commit(final XdStorageTransaction transaction) throws XdStorageException {
    // ... existing commit logic ...

    // NEW: commit all FK pairs after all entity records are committed
    foreignKeyEngine.commitAllFxPairs(transaction);

    // ... rest of existing commit logic ...
}
```

---

## 4. Deferred-ID Waiter Migration

The current `XdStorageForeignKeyChangeRegistry` uses `AtomicLong` counters + `IXdStorageIdObservableWrapper` observers. This is a **partial** implementation that is not wired to the cache phases.

### The correct approach

The deferred-ID waiter should be migrated to the cache:

```java
// Instead of: changeRegistry.registerChildIdWaiter(parentId, childObject, tx)
// Use: fkCache.cacheInsertWithDeferredId(pair, childObject, tx)

// XdStorageForeignKeyCache.cacheInsertWithDeferredId:
void cacheInsertWithDeferredId(XdStorageForeignKeyPair pair,
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
            // 2. If the pair is now in PERFORM state, it's ready for COMMIT
            var record = records.get(pair.key());
            if (record != null && record.phase() == XdStorageCacheCommitPhase.PREPARE) {
                record.performCommit(tx);  // promote to PERFORM
            }
        }
    });
}
```

### The pending-FK-registration migration

The current `pendingFkRegistrations` map is a **separate** state machine from the cache. It should be migrated:

```java
// Instead of: pendingFkRegistrations.put(txId, setOfPairs)
// Use: fkCache.cacheInsert(pair, tx)  ← same method, same cache

// The validatePendingFks(tx) method becomes:
// fkCache.validateAllForTransaction(tx)  ← validates all cached pairs
```

The `pendingFkRegistrationsByObject` map (for manual-ID parents not yet persisted) is the same mechanism — it tracks pairs where the parent's ID is not yet set. These pairs are in the cache as PREPARE state; when the parent's ID lands (via the deferred-ID observer), the pair is promoted to PERFORM state.

---

## 5. Acceptance Criteria

- [ ] **All FK pairs go through the cache** (PREPARE → PERFORM → COMMIT), not directly into the B+tree
- [ ] **PREPARE phase validates all pairs** (parent exists, deferred-ID waiters resolved)
- [ ] **PERFORM phase promotes pairs** (no-op beyond state transition)
- [ ] **COMMIT phase persists pairs to B+tree** (and only then)
- [ ] **ROLLBACK phase removes pairs from cache** (never touches B+tree)
- [ ] **The deferred-ID waiter is migrated** to the cache (no more separate AtomicLong counters)
- [ ] **The pending-FK-registration is migrated** to the cache (no more separate pendingFkRegistrations map)
- [ ] **Full suite green** (959+ tests)
- [ ] **The FK veto (MassFillAndClean)** is still correctly detected (the FK system works as designed)

---

## 6. Implementation Order (Recommended)

| Step | Task | Why |
|---|---|---|
| 1 | **XdStorageForeignKeyCache** (the cache class) | The foundation; all other changes depend on it |
| 2 | **XdStorageForeignKeyCacheRecord** (the record) | The per-pair state machine |
| 3 | **XdStorageForeignKeyEngine** (cacheInsert replacement) | The core engine change |
| 4 | **XdStorageForeignKeyResource** (validate/perform/commit) | The 2PC phase integration |
| 5 | **XdStorageCacheCommitProtocol** (commitAllFxPairs) | The protocol-level wiring |
| 6 | **Deferred-ID waiter migration** (AtomicLong → cache) | Clean up the partial implementation |
| 7 | **pendingFkRegistrations migration** (map → cache) | Clean up the partial implementation |
| 8 | **Tests** (6-pinned test suite) | The acceptance gate |
| 9 | **Full suite ×2** | The release gate |

---

## 7. Open Questions

1. **The cache is per-transaction or global?** — The answer: per-transaction (like XdStorageResourceCache). Each transaction has its own set of cached pairs.
2. **The cache's thread safety?** — The answer: same as XdStorageResourceCache (ConcurrentHashMap, no external sync needed).
3. **The cache's cleanup when transaction rolls back?** — The answer: `fkCache.release(tx)` is called in `XdStorageForeignKeyResource.release(tx)`, which removes the transaction's entries from the cache.
4. **The cache's memory leak?** — The answer: same as XdStorageResourceCache (the cache is bounded by the number of active transactions, which is bounded by the transaction timeout).

---

## 8. References

- `XdStorageForeignKeyEngine.java` — the current engine (the buggy code to replace)
- `XdStorageForeignKeyResource.java` — the current resource (the partial mitigation)
- `XdStorageForeignKeyChangeRegistry.java` — the current deferred-ID waiter (the partial implementation)
- `XdStorageCacheCommitProtocol.java` — the 2PC protocol (the phase pipeline to follow)
- `XdStorageCacheRecord.java` — the entity cache record (the model to mirror)
- `docs/tasks/2.1.0/42-foreign-keys.md` — the original FK spec
- `docs/tasks/2.1.0/34-cache2pc.md` — the CACHE2PC design (the phase pipeline)
- `docs/tasks/2.1.0/47-record-identity-disarmed-phases.md` — the record-identity fix (the phase model)
