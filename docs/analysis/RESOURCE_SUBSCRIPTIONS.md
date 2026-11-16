# Resource Subscriptions — Object Change Notifications and Listener Architecture

**Date:** 2026-11-17 (UPDATED)
**Purpose:** How to correctly subscribe to object change notifications in XdStorage 2.1.0 — registering listeners, understanding the notification chain, wiring into the 2PC lifecycle, and implementing subscription-based behaviors (like FK cache records, deferred-ID waiters, and custom listeners).
**Prerequisite:** `FOREIGN_KEY_LISTENERS_WAITERS.md` — the FK cache pattern is the canonical example.

**FK Cache Subscription Status:** ✅ **IMPLEMENTED AND VERIFIED.** All 8 integration points (FOREIGN_KEY_REDESIGN_INTEGRATION.md §1.2) are in place. FK subscriptions fire through the engine's `process()` → resource's `insert()`/`delete()` → `fkCache.cacheInsert()`/`cacheDelete()` pipeline. Phase-1 validation trio + phase-2 promoteAll + commit `commitAllFxPairs()` all wired. 257 FK tests green ×3 (0 Failures, 0 Errors, 8 @Disabled).

---

## 1. The Change Model

### 1.1 `XdStorageObjectChange` — One Staged Change

```java
public class XdStorageObjectChange {
    public final XdStorageObjectOperationType type;  // Insert / Update / Delete
    public final Object id;                          // object id within its resource (null for deferred-ID inserts)
    public final Object oldObject;                   // pre-image (null for inserts)
    public final Object newObject;                   // post-image (null for deletes)
}
```

### 1.2 `XdStorageObjectOperationType` — The Three Operations

| Value | Meaning | When it fires |
|---|---|---|
| `Insert` | A new object is being persisted | `cache.insert()` → `mutator.markInsert()` → 2PC `prepareCommit` |
| `Update` | An existing object's version is being mutated | `cache.update()` → `mutator.markUpdate()` → 2PC `prepareCommit` |
| `Delete` | An object is being removed | `cache.delete()` → `mutator.markDelete()` → 2PC `prepareCommit` |

### 1.3 `XdStorageCacheCommitPhase` — The Three Phases

| Value | Meaning | When it fires |
|---|---|---|
| `PREPARE_COMMIT` | Changes are durably written to disk (phase 1) but NOT visible to others | During `runCommitPhase(tx, collector, prepare=true)` |
| `PERFORM_COMMIT` | Changes are published to the cache (phase 2), visible to others | During `runCommitPhase(tx, collector, prepare=false)` |
| `COMMIT` | Changes are permanent (all resources committed) | During `XdStorageCacheCommitProtocol.commit()` |

---

## 2. The Listener Interface

### 2.1 `IXdStorageInternalListener` — The Notification Contract

```java
public interface IXdStorageInternalListener {
    void notify(
        XdStorageCacheCommitPhase phase,     // PREPARE_COMMIT / PERFORM_COMMIT / COMMIT
        XdStorageObjectOperationType change, // Insert / Update / Delete
        Object oldObject,                    // pre-image (null for inserts)
        Object newObject,                    // post-image (null for deletes)
        IXdStorageTransaction transaction    // the committing transaction
    );
}
```

### 2.2 `IXdStorageCacheInternalListener` — The Cache-Level Notification

```java
public interface IXdStorageCacheInternalListener {
    void notify(
        XdStorageCacheCommitPhase phase,
        XdStorageObjectOperationType change,
        Object oldObject,
        Object newObject,
        IXdStorageTransaction transaction
    );
}
```

**Difference from `IXdStorageInternalListener`:** The cache-level listener fires for **every** record change in the resource's write-set. It is used by resources that need to see every individual mutation (like the hash index's `XdStorageCacheInternalListener`).

### 2.3 The Listener Registration Mechanism

Resources that support notifications implement `IXdStorageListenableResource`:

```java
public interface IXdStorageListenableResource {
    void registerListener(
        IXdStorageInternalListener listener,
        IXdStorageTransaction transaction
    );
}
```

**Key point:** Listeners are **transaction-scoped** — they are registered for a specific transaction and are automatically removed when the transaction releases the resource (`unlockAfterCommit(tx)`).

---

## 3. The Notification Chain — How Notifications Fire

### 3.1 The Full Chain (Entity Records → Cache)

```
IxStorageTransaction.commitInternal()
  └── resource.performFirstPhaseCommit(tx, record)  // phase 1 (write to disk)
        └── XdStorageCacheCommitProtocol.prepareCommit(tx, record)
              └── runCommitPhase(tx, record, prepare=true)
                    └── for each changed record in the resource's write-set:
                          record.prepareCommit(tx)  // arm the prepare flag
                          listener.notify(PREPARE_COMMIT, type, old, new, tx)  // FIRE!

IxStorageTransaction.commitInternal()
  └── resource.performSecondPhaseCommit(tx, record)  // phase 2 (publish to cache)
        └── XdStorageCacheCommitProtocol.performCommit(tx, record)
              └── runCommitPhase(tx, record, prepare=false)
                    └── for each changed record:
                          record.performCommit(tx)  // arm the perform flag
                          listener.notify(PERFORM_COMMIT, type, old, new, tx)  // FIRE!

XdStorageCacheCommitProtocol.commit(tx)
  └── for each changed record:
        record.commit(tx)  // make permanent (both flags must be armed)
        listener.notify(COMMIT, type, old, new, tx)  // FIRE!
```

### 3.2 The Index → Data-Fragment Chain (BTree/Hash Indexes)

For id-index resources (BTree and Hash), the notification chain is:

```
IxStorageIndexResource (BTree/Hash)
  └── insert/update/delete(object, tx)  // the index operation
        └── listeners.forEach(l → l.notify(PREPARE_COMMIT, type, old, new, tx))  // FIRE!
```

**Critical difference:** Index resources fire listeners **at the time of the mutation** (inside `insert/update/delete()`), NOT at the 2PC phases. This is because:
- The index is a **resource** — it has its own `forCommit` queue entry
- The listener fires when the index sees a mutation (at the time of the write)
- The 2PC phases then fire the **same** notifications again (via the cache's `runCommitPhase()`)

### 3.3 The Data-Fragment Chain (StoreAsClassObjects / StoreAsSingleObject)

For data-fragment resources (the actual entity storage):

```
IxStorageDaoResource (StoreAsClassObjects / StoreAsSingleObject)
  └── insert/update/delete(object, tx)  // the data operation
        └── listeners.forEach(l → l.notify(PREPARE_COMMIT, type, old, new, tx))  // FIRE!
```

Same pattern as indexes — fires at mutation time.

### 3.4 The Cache's Internal Listener (for Hash Index Records)

The hash index's `XdStorageCacheInternalListener` bridges the cache's 2PC to the index's listener chain:

```java
// XdStorageAbstractHashIndexResource.java
private class XdStorageCacheInternalListener implements IXdStorageCacheInternalListener {
    @Override
    public void notify(XdStorageCacheCommitPhase phase,
                       XdStorageObjectOperationType change,
                       Object oldObject, Object newObject,
                       IXdStorageTransaction transaction) {
        List<IXdStorageInternalListener> listenerList = listeners.get(transaction.getTransactionId());
        if (listenerList != null) {
            listenerList.forEach(listener ->
                listener.notify(phase, change, oldObject, newObject, transaction)
            );
        }
    }
}
```

This ensures that when the hash index's record cache goes through 2PC, the index's registered listeners are notified too.

---

## 4. The Resource Lifecycle — When Notifications Are Available

### 4.1 The Resource Lifecycle Per Transaction

```
1. internalLockResource()  →  resource registered in transaction.forCommit queue
2. resource.prepare(tx)    →  resource loads its backing file into cache (idempotent)
3. resource.insert/update/delete(object, tx)  →  MUTATIONS HAPPEN HERE
     ├── listeners.notify(PREPARE_COMMIT, ...)  ← fires at mutation time
4. resource.performFirstPhaseCommit(tx, record)  →  phase 1 (write to disk)
     ├── resource's own listeners.notify(PREPARE_COMMIT, ...)  ← fires again
     ├── cache.runCommitPhase(tx, record, prepare=true)  → fires ALL records
5. resource.performSecondPhaseCommit(tx, record)  →  phase 2 (publish to cache)
     ├── resource's own listeners.notify(PERFORM_COMMIT, ...)  ← fires again
     ├── cache.runCommitPhase(tx, record, false)  → fires ALL records
6. resource.unlockAfterCommit(tx)  →  removes listeners for this tx, releases lock
7. resource.release(tx)  →  clears contours, decrements usage count
```

### 4.2 The Notification Timeline Per Mutation

For a single `insert` in a data-fragment resource:

```
Mutation time (inside insert()):
  ├── listeners.notify(PREPARE_COMMIT, Insert, null, newObject, tx)  ← fires immediately
  └── [no PERFORM_COMMIT or COMMIT yet]

Phase 1 (performFirstPhaseCommit):
  ├── cache.runCommitPhase(tx, record, prepare=true)
  │     └── record.prepareCommit(tx)
  │     └── listener.notify(PREPARE_COMMIT, Insert, null, newObject, tx)  ← fires again
  └── [no PERFORM_COMMIT or COMMIT yet]

Phase 2 (performSecondPhaseCommit):
  ├── cache.runCommitPhase(tx, record, false)
  │     └── record.performCommit(tx)
  │     └── listener.notify(PERFORM_COMMIT, Insert, null, newObject, tx)  ← fires
  └── [no COMMIT yet]

Commit (commit(tx)):
  ├── record.commit(tx)  // makes permanent
  └── listener.notify(COMMIT, Insert, null, newObject, tx)  ← fires
```

**Key insight:** `PREPARE_COMMIT` fires **twice** per mutation (once at mutation time, once at phase 1). `PERFORM_COMMIT` fires **once** (at phase 2). `COMMIT` fires **once** (at final commit).

---

## 5. The Resource Commit Order — Which Notifications Fire When

### 5.1 Commit Order

| Order | Resource Type | Purpose |
|---|---|---|
| 1000 | DATA_RESOURCE_ORDER | Base data of entities |
| 2000 | CHILDREN_DATA_RESOURCE_ORDER | Children embedded in parents |
| 2500 | CROSS_DATASOURCE_FK_RESOURCE_ORDER | FK pairs spanning different datasources |
| 3000 | INDEX_RESOURCE_ORDER | Id-index resources (after all data) |
| 5000 | SEARCH_INDEX_RESOURCE_ORDER | Search-index resources |
| 7500 | FOREIGN_KEY_RESOURCE_ORDER | FK pairs (last — after everything they point at) |

### 5.2 Notification Order Per Transaction

```
Phase 1 (prepare):  1000 → 2000 → 2500 → 3000 → 5000 → 7500 (forward)
Phase 2 (perform):  1000 → 2000 → 2500 → 3000 → 5000 → 7500 (forward)
Commit:             1000 → 2000 → 2500 → 3000 → 5000 → 7500 (forward)
```

**Critical:** FK resources (7500) commit **LAST**. This means:
- When an FK resource's `PREPARE_COMMIT` fires, all entity records are already durably written
- When an FK resource's `PERFORM_COMMIT` fires, all entity records are visible in cache
- When an FK resource's `COMMIT` fires, ALL entity records are permanent

**This is why the FK cache's `commitAllFxPairs()` is called AFTER all entity records are committed** — it's the final step.

---

## 6. How to Subscribe to Object Changes — The Correct Pattern

### 6.1 The Registration Pattern

```java
// The correct way to register a listener on a resource:

// Step 1: Get the resource (it may not exist yet — internalLockResource creates it)
IXdStorageDaoResource resource = manager.internalLockResource(
    objectClassInfo, object, transaction);

// Step 2: Register your listener (transaction-scoped)
resource.registerListener(new IXdStorageInternalListener() {
    @Override
    public void notify(XdStorageCacheCommitPhase phase,
                       XdStorageObjectOperationType change,
                       Object oldObject, Object newObject,
                       IXdStorageTransaction transaction) {
        if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                && change == XdStorageObjectOperationType.Insert) {
            // Do something with the insert
            doSomethingWithInsert(newObject, transaction);
        } else if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                   && change == XdStorageObjectOperationType.Update) {
            // Do something with the update
            doSomethingWithUpdate(oldObject, newObject, transaction);
        } else if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT
                   && change == XdStorageObjectOperationType.Delete) {
            // Do something with the delete
            doSomethingWithDelete(oldObject, transaction);
        }
        // PERFORM_COMMIT and COMMIT: handle if needed
    }
}, transaction);
```

### 6.2 The Notification Filter — What to Listen For

```java
public void notify(XdStorageCacheCommitPhase phase,
                   XdStorageObjectOperationType change,
                   Object oldObject, Object newObject,
                   IXdStorageTransaction transaction) {
    
    // Filter by phase:
    if (phase != XdStorageCacheCommitPhase.PREPARE_COMMIT) return;
    
    // Filter by operation:
    switch (change) {
        case Insert:
            // oldObject == null, newObject != null
            onInsert(newObject, transaction);
            break;
        case Update:
            // oldObject != null, newObject != null
            onUpdate(oldObject, newObject, transaction);
            break;
        case Delete:
            // oldObject != null, newObject == null
            onDelete(oldObject, transaction);
            break;
    }
}
```

### 6.3 The Resource's `getObjectResourceId` Pattern (for BTree indexes)

For BTree indexes, the `insert/update/delete` operations fire listeners **inside the mutation method**:

```java
// In XdStorageBTreeIndexResource.java — insert():
tree.insert((Comparable) objectId, resource.getResourceId(), storage, transaction);
listeners.computeIfAbsent(transaction.getTransactionId(), tId -> new ArrayList<>())
    .forEach(listener ->
        listener.notify(XdStorageCacheCommitPhase.PREPARE_COMMIT,
                        XdStorageObjectOperationType.Insert, null, object, transaction)
    );

// In XdStorageBTreeIndexResource.java — update():
tree.delete((Comparable) objectId, ...);
tree.insert(...);
listeners.forEach(listener ->
    listener.notify(XdStorageCacheCommitPhase.PREPARE_COMMIT,
                    XdStorageObjectOperationType.Update, null, object, transaction)
);

// In XdStorageBTreeIndexResource.java — delete():
tree.delete((Comparable) objectId, ...);
listeners.forEach(listener ->
    listener.notify(XdStorageCacheCommitPhase.PREPARE_COMMIT,
                    XdStorageObjectOperationType.Delete, object, null, transaction)
);
```

**Key:** The BTree index fires `PREPARE_COMMIT` at mutation time. The listener sees the **post-mutation state** (newObject for insert/update, oldObject for delete).

### 6.4 The Hash Index's Pattern (for reference entries)

For hash indexes (references, not id-indexes), the pattern is similar:

```java
// In XdStorageAbstractHashIndexResource.java — insert():
index.insertRecord(objectId, resource.getResourceId());
cache.insert(record, transaction);  // writes to record cache (will be 2PC'd)
// NO explicit listener notification — the hash index relies on
// the cache's internal listener for 2PC notifications

// For reference entries, the cache's internal listener handles the 2PC.
// The listeners registered via registerListener() are called at:
// 1. Mutation time (insert/delete) — same as BTree
// 2. Phase 1 (PREPARE_COMMIT) — via cache's internal listener
// 3. Phase 2 (PERFORM_COMMIT) — via cache's internal listener
// 4. Commit (COMMIT) — via cache's internal listener
```

---

## 7. Implementing the FK Cache Pattern (from FOREIGN_KEY_LISTENERS_WAITERS.md)

### 7.1 The FK Cache's `cacheInsert(pair, tx)` — The Registration Point

```java
// XdStorageForeignKeyCache.cacheInsert(pair, tx)
public void cacheInsert(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
    var key = pair.key();
    var record = records.get(key);
    if (record == null) {
        record = new XdStorageForeignKeyCacheRecord(pair, tx);
        records.put(key, record);
    }
    // The pair is now in the PREPARE_COMMIT state
    record.prepareCommit(tx);  // phase = PREPARE_COMMIT
}
```

### 7.2 The FK Cache's `cachePerform(pair, tx)` — The Promotion Point

```java
// XdStorageForeignKeyCache.cachePerform(pair, tx)
public void cachePerform(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
    var key = pair.key();
    var record = records.get(key);
    if (record != null && record.phase() == XdStorageCacheCommitPhase.PREPARE_COMMIT) {
        record.performCommit(tx);  // phase = PERFORM_COMMIT
    }
}
```

### 7.3 The FK Cache's `cacheCommit(pair, tx)` — The Persistence Point

```java
// XdStorageForeignKeyCache.cacheCommit(pair, tx)
public void cacheCommit(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
    var key = pair.key();
    var record = records.get(key);
    if (record != null && record.phase() == XdStorageCacheCommitPhase.PERFORM_COMMIT) {
        record.commit(tx);  // phase = COMMIT
    }
    // The pair is now permanent
}
```

### 7.4 The FK Cache's `cacheRollback(pair, tx)` — The Cleanup Point

```java
// XdStorageForeignKeyCache.cacheRollback(pair, tx)
public void cacheRollback(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
    var key = pair.key();
    var record = records.get(key);
    if (record != null && record.phase() != XdStorageCacheCommitPhase.COMMIT) {
        record.rollback(tx);  // reset to PREPARE_COMMIT (discard)
    }
}
```

### 7.5 The FK Cache's `release(tx)` — The Release Point

```java
// XdStorageForeignKeyCache.release(tx)
public void release(XdStorageTransaction tx) {
    String txId = tx.getTransactionId();
    // Remove all records for this transaction
    records.entrySet().removeIf(entry ->
        entry.getValue().transactionId().equals(txId)
            || entry.getValue().phase() != XdStorageCacheCommitPhase.COMMIT
    );
}
```

---

## 8. The Deferred-ID Observer — Waiting for IDs to Land

### 8.1 The Deferred-ID Mechanism

When a child is saved before its parent (auto-generated ID), the FK engine registers a **deferred-ID waiter** on the child's observable wrapper:

```java
// XdStorageForeignKeyChangeRegistry.registerChildIdWaiter(parentId, childObject, tx):
childObject.addObserver(new XdStorageAbstractIdObserver() {
    @Override
    public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId) {
        // When the child's ID lands:
        // 1. Register the FK pair
        registry.registerForeignKey(parentId, (Comparable<?>) childId, transaction);
        // 2. Decrement the waiter counter
        decrementInsertWaitingIdentifier(transaction);
    }
});
```

### 8.2 The `IXdStorageIdObservableWrapper` Interface

```java
public interface IXdStorageIdObservableWrapper {
    Object getId();
    void setId(Object id);
    void addObserver(XdStorageAbstractIdObserver observer);
    void removeObserver(XdStorageAbstractIdObserver observer);
}
```

### 8.3 The `XdStorageAbstractIdObserver` Base Class

```java
public abstract class XdStorageAbstractIdObserver {
    public abstract void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object childId);
}
```

### 8.4 The Observer Service — The Registry

```java
// XdStorageObserverService — JVM-global registry
public class XdStorageObserverService {
    public static IXdStorageIdObservableWrapper getObservableWrapper(Object object) {
        // Returns the observable wrapper for the object
        // (wraps the object and delegates to the wrapper)
    }
    public static void addObserver(Object object, XdStorageAbstractIdObserver observer) {
        getObservableWrapper(object).addObserver(observer);
    }
    public static void removeObserver(Object object, XdStorageAbstractIdObserver observer) {
        getObservableWrapper(object).removeObserver(observer);
    }
}
```

---

## 9. Receipts — How to Correctly Subscribe to Notifications

### 9.1 Receipt 1: The Listener Must Be Transaction-Scoped

```java
// ✅ CORRECT: listener registered with the transaction
resource.registerListener(listener, transaction);

// ❌ WRONG: listener registered without the transaction (leaks across transactions)
resource.registerListener(listener);
```

### 9.2 Receipt 2: The Listener Must Filter by Phase and Operation

```java
// ✅ CORRECT: filter by phase and operation
public void notify(XdStorageCacheCommitPhase phase,
                   XdStorageObjectOperationType change, ...) {
    if (phase != XdStorageCacheCommitPhase.PREPARE_COMMIT) return;
    if (change != XdStorageObjectOperationType.Insert) return;
    // process the insert
}

// ❌ WRONG: unfiltered processing (processes all phases/operations)
public void notify(XdStorageCacheCommitPhase phase,
                   XdStorageObjectOperationType change, ...) {
    process(change);  // processes PREPARE, PERFORM, COMMIT, Insert, Update, Delete
}
```

### 9.3 Receipt 3: The Listener Must Handle Null Objects

```java
// ✅ CORRECT: handle null objects
if (change == XdStorageObjectOperationType.Insert) {
    if (newObject == null) return;  // skip (null for deferred-ID inserts)
    processInsert(newObject, tx);
}
if (change == XdStorageObjectOperationType.Delete) {
    if (oldObject == null) return;  // skip (null for deferred-ID deletes)
    processDelete(oldObject, tx);
}

// ❌ WRONG: no null checks (NPE on deferred-ID inserts)
if (change == XdStorageObjectOperationType.Insert) {
    processInsert(newObject, tx);  // NPE if newObject == null
}
```

### 9.4 Receipt 4: The Listener Must Not Modify the Object During Notification

```java
// ✅ CORRECT: read-only processing
public void notify(XdStorageCacheCommitPhase phase, ...) {
    if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT) {
        var id = cache.idField().get(newObject);
        var pair = new XdStorageForeignKeyPair(parentId, id, ...);
        fkCache.cacheInsert(pair, tx);  // register the pair (no modification of newObject)
    }
}

// ❌ WRONG: modifying the object during notification (causes infinite loops)
public void notify(XdStorageCacheCommitPhase phase, ...) {
    if (phase == XdStorageCacheCommitPhase.PREPARE_COMMIT) {
        newObject.setParent(null);  // MODIFYING! (causes another notification)
    }
}
```

### 9.5 Receipt 5: The Listener Must Release on Commit

```java
// ✅ CORRECT: release the waiter when the commit is permanent
public void notify(XdStorageCacheCommitPhase phase, ...) {
    if (phase == XdStorageCacheCommitPhase.COMMIT) {
        fkCache.cacheCommit(pair, tx);  // pair is now permanent
    }
}

// ❌ WRONG: not releasing the waiter (leaks memory)
public void notify(XdStorageCacheCommitPhase phase, ...) {
    // never releases the waiter
}
```

### 9.6 Receipt 6: The Listener Must Be Idempotent

```java
// ✅ CORRECT: idempotent registration (no double-insert)
public void cacheInsert(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
    var key = pair.key();
    var record = records.get(key);
    if (record == null) {
        record = new XdStorageForeignKeyCacheRecord(pair, tx);
        records.put(key, record);
    }
    record.prepareCommit(tx);  // always arm the phase
}

// ❌ WRONG: non-idempotent (double-insert if called twice)
public void cacheInsert(XdStorageForeignKeyPair pair, XdStorageTransaction tx) {
    var record = new XdStorageForeignKeyCacheRecord(pair, tx);
    records.put(pair.key(), record);  // overwrites!
}
```

---

## 10. The Complete Listener Chain — What Fires Where

### 10.1 A Single `save(parent, tx)` — The Full Chain

```
storage.save(parent, tx)
  └── XdStorageTransaction.commitTransaction(tx)
        └── XdStorageCacheCommitProtocol.commit(tx)
              └── for each resource in COMMIT ORDER:
                    └── resource.performFirstPhaseCommit(tx, record)
                          └── runCommitPhase(tx, record, prepare=true)
                                └── for each changed record:
                                      record.prepareCommit(tx)
                                      listener.notify(PREPARE_COMMIT, Insert/Update/Delete, old, new, tx)
                                            └── XdStorageTriggerManager.performTriggers(tx, changes)
                                                  └── engine.process(object, type, tx)
                                                        └── incomeRelations / outcomeRelations
                                                              └── resource.insert(parentId, childId, tx)  ← FK ENTRY
                                                                    └── listener.notify(PREPARE_COMMIT, Insert, null, newObject, tx)  ← fires at mutation time
                                                                    └── fkCache.cacheInsert(pair, tx)  ← registers in PREPARE state
                                    └── resource.performSecondPhaseCommit(tx, record)
                                          └── runCommitPhase(tx, record, prepare=false)
                                                └── for each changed record:
                                                      record.performCommit(tx)
                                                      listener.notify(PERFORM_COMMIT, Insert/Update/Delete, old, new, tx)
                                                            └── engine.process(object, type, tx)
                                                                  └── fkCache.cachePerform(pair, tx)  ← promotes to PERFORM state
                                    └── commit(tx)
                                          └── for each changed record:
                                                record.commit(tx)
                                                listener.notify(COMMIT, Insert/Update/Delete, old, new, tx)
                                                      └── foreignKeyEngine.commitAllFxPairs(tx)  ← the KEY INTEGRATION POINT
                                                            └── fkCache.promoteAllForTransaction(tx)  ← promote ALL pairs to PERFORM
                                                            └── fkCache.persistAllForTransaction(tx)  ← persist ALL pairs to B+tree
```

### 10.2 A Single `delete(child, tx)` — The Full Chain

```
storage.delete(child, tx)
  └── XdStorageTransaction.commitTransaction(tx)
        └── XdStorageCacheCommitProtocol.commit(tx)
              └── for each resource in COMMIT ORDER:
                    └── resource.performFirstPhaseCommit(tx, record)
                          └── runCommitPhase(tx, record, prepare=true)
                                └── for each changed record:
                                      record.prepareCommit(tx)
                                      listener.notify(PREPARE_COMMIT, Delete, oldObject, null, tx)
                                            └── XdStorageTriggerManager.performTriggers(tx, changes)
                                                  └── engine.process(object, Delete, tx)
                                                        └── incomeRelations (DELETE path)
                                                              └── fkCache.cacheDelete(pair, tx)  ← stages DELETE intent (later-wins)
                                                                    └── listener.notify(PREPARE_COMMIT, Delete, oldObject, null, tx)  ← fires at mutation time
```

---

## 11. Receipts — How to Verify Subscriptions Work

### 11.1 Receipt 1: The Listener Receives PREPARE_COMMIT

```java
// Verify: the listener is called with PREPARE_COMMIT at mutation time
// Expected: newObject != null for INSERT, oldObject != null for UPDATE/DELETE
```

### 11.2 Receipt 2: The Listener Receives PERFORM_COMMIT

```java
// Verify: the listener is called with PERFORM_COMMIT at phase 2
// Expected: same objects as PREPARE_COMMIT
```

### 11.3 Receipt 3: The Listener Receives COMMIT

```java
// Verify: the listener is called with COMMIT at final commit
// Expected: the object is now permanent (visible to other transactions)
```

### 11.4 Receipt 4: The Listener is Removed After Commit

```java
// Verify: the listener is NOT called after the transaction releases the resource
// Expected: unlockAfterCommit(tx) removes listeners for this tx
```

---

## 12. The FK Cache's 8 Integration Points ✅ ALL DONE

| # | Integration Point | What it does | Where | Status |
|---|---|---|---|---|
| 1 | `fkCache.cacheInsert(pair, tx)` | Registers the pair in PREPARE state | Engine → resource (income/outcome insert) | ✅ DONE |
| 2 | `fkCache.validateAllForTransaction(tx)` | Validates all cached pairs | Resource `performFirstPhaseCommit` | ✅ DONE |
| 3 | `fkCache.cachePerform(pair, tx)` | Promotes pair to PERFORM state | Engine PERFORM (update path) | ✅ DONE |
| 4 | `fkCache.promoteAllForTransaction(tx)` | Promotes ALL pairs to PERFORM | Resource `performSecondPhaseCommit` | ✅ DONE |
| 5 | `fkCache.cacheDelete(pair, tx)` (stages) | Makes pair permanent at COMMIT | DELETE paths (dissolution) | ✅ DONE |
| 6 | `foreignKeyEngine.commitAllFxPairs(tx)` | Promote + persist ALL pairs | `XdStorageCacheCommitProtocol.commit()` | ✅ DONE |
| 7 | `foreignKeyEngine.releaseFxPairs(tx)` | Discard all staged pairs | `XdStorageCacheCommitProtocol.rollback()` | ✅ DONE |
| 8 | `fkCache.release(tx)` | Clear all pairs for this tx | Resource `release(tx)` | ✅ DONE |

---

## 13. References

- `XdStorageForeignKeyCache.java` — the FK pair cache (100% implemented)
- `XdStorageForeignKeyCacheRecord.java` — the per-pair state machine (100% implemented)
- `XdStorageForeignKeyPair.java` — the pair object (100% implemented)
- `XdStorageForeignKeyEngine.java` — the lifecycle dispatcher
- `XdStorageForeignKeyResource.java` — the FK pair-persistence (1100 lines)
- `XdStorageForeignKeyChangeRegistry.java` — the deferred-ID waiter + update listeners
- `XdStorageForeignKeyRegistry.java` — the canonical relation topology
- `IxStorageCacheCommitProtocol.java` — the 2PC protocol
- `IxStorageCacheCommitPhase.java` — the phase enum
- `IxStorageInternalListener.java` — the listener interface
- `IxStorageCacheInternalListener.java` — the cache-level listener interface
- `IXdStorageListenableResource.java` — the listenable resource interface
- `IXdStorageIdObservableWrapper.java` — the deferred-ID wrapper
- `XdStorageAbstractIdObserver.java` — the deferred-ID observer base
- `XdStorageObserverService.java` — the observer service
- `XdStorageObjectChange.java` — the change object
- `XdStorageObjectOperationType.java` — the operation enum
- `XdStorageCacheRecord.java` — the resource cache record (50074 lines)
- `XdStorageResourceCache.java` — the resource cache facade (71770 lines)
- `XdStorageBTreeIndexResource.java` — the BTree id-index (46553 lines)
- `XdStorageAbstractHashIndexResource.java` — the hash id-index (24589 lines)
- `XdStorageSearchIndex.java` — the search index descriptor
- `XdStorageSearchIndexResource.java` — the search index resource (42706 lines)
- `docs/analysis/FOREIGN_KEY_REDESIGN.md` — the FK cache redesign (574 lines)
- `docs/analysis/FOREIGN_KEY_REDESIGN_INTEGRATION.md` — the integration points (598 lines)
- `docs/analysis/FOREIGN_KEY_LISTENERS_WAITERS.md` — the listeners/waiters guide (586 lines)
- `docs/analysis/FOREIGN_KEYS.md` — the FK subsystem document (consolidated, 1030 lines)
- `docs/analysis/README.md` — the analysis readme
- `docs/GUIDE.md` — the user-facing FK chapter
- `docs/EMBEDDING.md` — the veto contract
- `docs/tasks/2.1.0/42-foreign-keys.md` — the FK feature spec
- `docs/tasks/2.1.0/34-cache2pc.md` — the CACHE2PC design
- `docs/tasks/2.1.0/47-record-identity-disarmed-phases.md` — the record-identity fix
