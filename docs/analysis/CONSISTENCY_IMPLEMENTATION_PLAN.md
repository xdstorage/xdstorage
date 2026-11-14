# Consistency (Foreign Key) — Implementation & Extension Plan

**Created:** 2026 (current session)
**Based on:** CONSISTENCY_ANALYSIS.md
**Scope:** Step-by-step implementation plan for consistency improvements
**Ordering:** P0 → P1 → P2 → P3 (by priority, not by effort)

---

## Plan P0: Consistency Core (Blocking 2.2.0)

### P0-1: Consistency-Core Rebuild (Task 01)

**Priority:** P0
**Effort:** ~5–8 sessions
**Risk:** HIGH (touches the lock infrastructure that the FK engine depends on)
**Depends on:** Task 03 (property-based tests) — the acceptance criteria

#### Step 1: Design note (mandatory before code)

**File:** `docs/tasks/2.2.0/01-consistency-core-standard-primitives.md` (design note section)

**Scope:** The lock-site inventory (RW-lock, record latch, deep locks, hand-over-hand), the primitive mapping, and the three R-28/R-29 rejection reasons addressed one by one.

**Lock-site inventory (from current code):**
- `FxStorageReadWriteLock` — the spin/notify lock (the one being replaced)
- `FxStorageCacheRecord` latch — per-record latch (the one being replaced)
- `FxStorageBTreeNode` lock — per-node lock (the one being replaced)
- `FxStorageForeignKeyResource` per-tree locks — already ReentrantLock ✅
- `FxStorageForeignKeyResourcesManager` `ConcurrentHashMap` lock — already J.u.c ✅
- `FxStorageForeignKeyEngine` lock-free (uses ConcurrentHashMap) ✅

**Primitive mapping:**
| Current | Replacement | Rationale |
|---|---|---|
| FxStorageReadWriteLock | ReentrantReadWriteLock | Fairness, interruptibility, diagnostics |
| FxStorageCacheRecord latch | Condition (via ReentrantLock) | Standard wait/notify on Condition |
| FxStorageBTreeNode lock | ReentrantLock (already fair) | Already fair; keep the fairness |

**R-28/R-29/R-30 rejection reasons (from OPTIMIZATION-PLAN.md):**
- "re-scheduling waiters on the SAME structure" — rejected in 2.2.0 framing: "rebuild on proven primitives, not re-schedule on the SAME structure"
- The three rejected variants are evidence of what NOT to repeat, not proof that standards fail
- **Decision:** use ReentrantReadWriteLock with fair ordering (the fairness is the key property the R-28 variants tried to achieve manually)

**Step 1 acceptance:**
- [ ] Design note written in the task file
- [ ] Lock-site inventory complete (all sites listed with current type)
- [ ] Primitive mapping complete (all sites mapped)
- [ ] R-28/R-29 rejection reasons addressed one by one
- [ ] The design is reviewed and approved (the "design round" gate)

#### Step 2: Implement the replacement

**File:** `src/main/java/io/github/xdstorage/lock/FxStorageReadWriteLock.java` → `FxStorageReentrantReadWriteLock.java` (new)

```java
public class FxStorageReentrantReadWriteLock {
    private final ReentrantReadWriteLock rwLock;
    private final boolean fair;
    public FxStorageReentrantReadWriteLock(boolean fair) {
        this.rwLock = new ReentrantReadWriteLock(fair);
        this.fair = fair;
    }
    public void lock() { rwLock.writeLock().lock(); }
    public void unlock() { rwLock.writeLock().unlock(); }
    // ... read lock methods, tryLock, etc.
}
```

**File:** `src/main/java/io/github/xdstorage/resource/cache/FxStorageCacheRecord.java` → latch replacement

```java
// Old: FxStorageCacheRecord.latch = new FxStorageCacheRecordLatch()
// New:
private final ReentrantLock lock = new ReentrantLock(true); // fair
private final Condition notEmpty = lock.newCondition();
// Wait: lock.lock(); while (!condition) notEmpty.await(); ... lock.unlock();
// Signal: lock.lock(); condition = true; notEmpty.signal(); lock.unlock();
```

**Step 2 acceptance:**
- [ ] All FxStorageReadWriteLock sites replaced with FxStorageReentrantReadWriteLock
- [ ] All FxStorageCacheRecord latch sites replaced with ReentrantLock + Condition
- [ ] All FxStorageBTreeNode lock sites replaced with ReentrantLock (fair)
- [ ] Full suite green (the "10 consecutive passes" gate)
- [ ] The lock-wait distribution measured (task 13 profiling — feeds the ledger)

#### Step 3: Enable the disabled B+Tree tests

**Files to enable:**
- `FxStorageBTreeDuplicateInvariantTest` (multi-tree balancing and find — the discovery's explicit item)
- `FxStorageCacheRecordInvariantTest` (the invariant suite for the cache records)
- `FxStorageBTreeIndexResourceOperationsTest` (the B+Tree index resource operations)

**Step 3 acceptance:**
- [ ] All 3 tests enabled (no `@Disabled`)
- [ ] All 3 tests green ×10
- [ ] The multi-tree balancing/find tests pass (the discovery's explicit acceptance criterion)

#### Step 4: Add the stress families to the gate

**File:** `pom.xml` (surefire profiles)

| Test family | Gate status | After task 01 |
|---|---|---|
| `FxStorageFacadeStressTest` | ❌ Excluded (gate) | ✅ Include (with 10 consecutive passes) |
| `FxStorageBTreeResourceCacheIntegration` | ❌ Excluded (gate) | ✅ Include (with 10 consecutive passes) |
| `FxStorageBTreeTargetedDeleteStressTest` | ✅ Included (with anomaly filter) | ✅ Keep (with anomaly filter) |
| `FxStorageNestedTransactionIsolationStressTest` | ❌ Excluded (nightly) | ✅ Include (task 12: fix or deprecate) |

**Step 4 acceptance:**
- [ ] The stress families return to the gate (with 10 consecutive passes)
- [ ] The nightly policy documented (the gate is nightly ×3, not full-suite)
- [ ] The exclusion decision documented in FLAKY.md

---

### P0-2: Loud-Failures Inventory (Task 02)

**Priority:** P0
**Effort:** ~2–3 sessions
**Risk:** Low (additive, no new persistence semantics)
**Depends on:** Task 01 (the consistency-core rebuild must land first — the new lock primitives provide the instrumentation for the loud-failure inventory)

#### Step 1: Inventory the silent-recovery paths

**File:** `docs/tasks/2.2.0/02-loud-failures-no-quiet-self-heals.md` (inventory section)

| Site | Trigger | Current behavior | Damage class | Decision |
|---|---|---|---|---|
| R-40: Structural ghost re-materialization | Open | WARN + recover silently | Data-loss (objects exist in journal but not in base) | LOUD |
| R-53: Stub restoration + canonical adoption WARNs | Open | WARN | Data integrity (canonically wrong) | LOUD |
| W-1: Torn-journal-tail discard | Open | WARN + discard | Data-loss (recent commits lost) | KEEP + count (designed behavior) |
| Search-manager null fallbacks | Read | "do not skip object doesn't exist" | False positives (reads miss objects) | LOUD |
| Index-cut tolerance | Read | Silently self-heals | Data corruption (reads return wrong objects) | LOUD |
| S-1: Missing sentinel (legacy) | Open | No exception | Data corruption (truncated file) | KEEP + WARN (documented) |
| FxStorageForeignKeyResource.pendingFkRegistrations | Commit | Throw if not all persisted | Data-loss (orphaned child) | LOUD (already LOUD) |
| FxStorageForeignKeyResource.validatePendingFks | Commit | Throw if not all persisted | Data-loss (orphaned child) | LOUD (already LOUD) |

**Step 1 acceptance:**
- [ ] Inventory complete (all sites listed with behavior + decision)
- [ ] The decision for each site is documented (why LOUD, why KEEP+count, why KEEP+WARN)
- [ ] No site is "undecided"

#### Step 2: Implement the LOUD decisions

**File:** `src/main/java/io/github/xdstorage/.../FxStorage...` (the site in question)

For each LOUD decision:
```java
// Old:
// log.warn("...");
// return null; // or return the partial result

// New:
throw new XdStorageRuntimeException("LOUD: ...");
```

**Step 2 acceptance:**
- [ ] All LOUD decisions implemented (throw at the recovery point)
- [ ] All KEEP+count decisions get metrics counters (a silent recovery that cannot be seen cannot be tuned)
- [ ] All KEEP+WARN decisions stay WARN + add a counter
- [ ] Tests pinned: each LOUD site pinned with a test that verifies the exception is thrown

#### Step 3: Add the index-cut LOUD decision

**File:** `src/main/java/io/github/xdstorage/index/.../FxStorageIndex...` (the index cut detection)

```java
// Old:
// log.warn("index cut detected, self-healing...");
// return the partial result;

// New:
throw new XdStorageRuntimeException("INDEX CUT: index " + indexName + " is truncated at offset " + offset + "; the operator must be notified");
```

**Step 3 acceptance:**
- [ ] Index-cut LOUD (never silently self-heals)
- [ ] The operator is notified (the exception name is actionable)
- [ ] The recovery recipe in EMBEDDING.md updated

#### Step 4: Add the tests

| Test | What |
|---|---|
| `testLoudR40` | Structural ghost re-materialization throws LOUD |
| `testLoudR53` | Stub restoration throws LOUD |
| `testLoudSearchManagerNullFallback` | Null fallback throws LOUD |
| `testLoudIndexCut` | Index cut throws LOUD |
| `testKeepJournalTailDiscard` | Torn journal tail: WARN + count |
| `testKeepMissingSentinel` | Missing sentinel: WARN + count |

**Step 4 acceptance:**
- [ ] All 6 tests green
- [ ] The KEEP+count decisions have counters (the counter value is checked in the test)
- [ ] The KEEP+WARN decisions have WARN messages (the WARN content is verified)

---

### P0-3: Journal/WAL TX-ID Hardening (Task 06)

**Priority:** P1
**Effort:** ~1 session
**Risk:** Low (additive, no new persistence semantics)
**Depends on:** Task 01 (the consistency-core rebuild) — the new lock primitives provide the instrumentation for the WAL hardening

#### Step 1: Sentinel format v3

**File:** `src/main/java/io/github/xdstorage/serialization/yaml/FxStorageYamlCommitJournal.java`

Current sentinel: `# xdstorage-eof`
New sentinel: `# xdstorage-eof serial=<n> tx=<id>`

The enriched sentinel carries the last commit serial + transaction id. A torn tail's decision (discard vs keep) is made on durable metadata, not just presence.

**Step 1 acceptance:**
- [ ] The enriched sentinel format is written by default
- [ ] The plain sentinel (without enriched fields) is still accepted (backward-compatible)
- [ ] The torn-tail discard decision reports the exact lost serial range (the enriched metadata)

#### Step 2: Add the transaction identification

**File:** `src/main/java/io/github/xdstorage/transaction/FxStorageTransaction.java`

The transaction's ID is written into the journal at EOF. This enables cross-storage audit (the "transaction X committed at time T" is now verifiable in the journal).

**Step 2 acceptance:**
- [ ] The transaction ID is written at EOF
- [ ] The journal replay uses the transaction ID to identify which transactions are committed vs which are pending (for the recovery recipe)

#### Step 3: Update the recovery recipe

**File:** `docs/EMBEDDING.md` (After a power loss → Cleanup after a crash)

The enriched sentinel makes the recovery recipe more precise: "records after the LAST enriched sentinel are discarded" is now "records after the LAST enriched sentinel with serial < X are discarded (the X being the last known committed serial)".

**Step 3 acceptance:**
- [ ] The recovery recipe updated with the enriched sentinel semantics
- [ ] The "discard" message names the exact lost serial range (from the enriched sentinel)
- [ ] The WAL hardening is documented in CHANGELOG.md

---

## Plan P1: Capacity & Scaling

### P1-1: W-4d Memory Train (Task 05)

**Priority:** P1
**Effort:** ~3–5 sessions + the design round
**Risk:** Medium (touches the read path — the most risky surface in the engine)
**Depends on:** Task 01 (consistency-core), Task 03 (property-based tests)

#### Step 1: Design round (mandatory before code)

**File:** `docs/tasks/2.2.0/05-w4d-memory-train.md` (design round section)

**Scope:** The eviction policy, the disk-anchored scan path, the retention contract, and the off-heap vs on-heap decision with the release=8 constraint stated.

**Decisions to make:**
- Eviction policy: LRU over committed, unlocked, non-infra records (the OPTIMIZATION-PROPOSAL-01 input)
- Disk-anchored scan path: scans iterate records from base+journal, not the cache (the F-3 thaw)
- Resource retention during transactions: hold the data resource only while transactions work with it (the discovery's "hold the data resource during transactions" item)
- Off-heap vs on-heap: DirectByteBuffer (FFM is Java 9+; release=8 bytecode constraint rules out FFM; DirectByteBuffer lifecycle is fragile on 8) → **on-heap LRU for 2.2.0; off-heap is FP-8 (2.3.0+)**

**Step 1 acceptance:**
- [ ] The design note written in the task file
- [ ] The eviction policy is specified (LRU over committed, unlocked, non-infra)
- [ ] The disk-anchored scan path is specified (scans iterate from base+journal, not cache)
- [ ] The retention contract is specified (hold data resource only while transactions work with it)
- [ ] The off-heap vs on-heap decision is documented (on-heap for 2.2.0, off-heap for 2.3.0+)
- [ ] The design is reviewed and approved (the "design round" gate)

#### Step 2: Implement the eviction

**File:** `src/main/java/io/github/xdstorage/resource/cache/FxStorageResourceCache.java`

```java
// Old:
// private final Map<Object, FxStorageCacheRecord> records = new ConcurrentHashMap<>();
// New:
private final Map<Object, FxStorageCacheRecord> records = new LruCache<>(cacheRecordsCap);
// where LruCache is a thread-safe LRU (size-bounded, eviction on put)
```

The evicted record is not destroyed — it remains in a "cold" state where `read()` checks the cache first; if the record is evicted (not present), re-load from disk (base file + journal).

**Step 2 acceptance:**
- [ ] `cacheRecordsCap > 0` bounds the total cache size
- [ ] Evicted records are re-loaded from disk correctly
- [ ] No OOM under N50K with bounded cache
- [ ] The eviction is documented (the LRU policy in CHANGELOG.md)

#### Step 3: Implement the disk-anchored scan path

**File:** `src/main/java/io/github/xdstorage/resource/FxStorageResource.java` (the resource read path)

When scanning (predicate scan, watch, loadAll), if a record is not in the cache:
```java
public Collection<T> scan(FxStorageTransaction tx) {
    // 1. Try to read from cache (fast path)
    // 2. If a record is evicted (cache miss), re-load from disk:
    //    - load from base file (the full file reader)
    //    - apply journal replay (the journal is already in memory at open)
    //    - cache the loaded record (if the LRU has room, evict the coldest)
    // 3. Continue the scan
    return readFromDisk(tx);
}
```

**Key constraint:** The scan must be MVCC-consistent (the loaded record must be visible in the caller's transaction's snapshot). This is the same constraint as the current read path (cache miss → resource read), but the resource read is now the disk anchor.

**Step 3 acceptance:**
- [ ] Scans are MVCC-consistent after eviction
- [ ] The scan path is the disk anchor (not the cache)
- [ ] The scan performance is measured (the task 13 profiling — the scan path is the measured cost)

#### Step 4: Implement the retention contract

**File:** `src/main/java/io/github/xdstorage/resource/FxStorageResource.java` (the resource lifecycle)

The retention contract: "hold the data resource only while transactions work with it." Currently, the resource is held indefinitely (until shutdown). The contract changes this to: the resource is held only while the transaction is active; when the transaction completes (commit or rollback), the resource is released (the data is no longer needed).

**Step 4 acceptance:**
- [ ] The resource is released when the transaction completes
- [ ] No resource is held longer than necessary
- [ ] The retention contract is documented (in CHANGELOG.md and EMBEDDING.md)

#### Step 5: Add the tests

| Test | What |
|---|---|
| `testEvictionLRU` | LRU eviction works (the oldest accessed record is evicted) |
| `testDiskAnchoredScan` | A scan over an evicted record re-loads from disk |
| `testRetentionContract` | A resource is released when the transaction completes |
| `testNoOOMwithCacheCap` | No OOM under N50K with bounded cache |
| `testMVCCConsistencyAfterEviction` | The scan is MVCC-consistent after eviction |

**Step 5 acceptance:**
- [ ] All 5 tests green
- [ ] The retention contract is documented
- [ ] The disk-anchored scan path is documented

---

### P1-2: Property-Based Tests (Task 03)

**Priority:** P1
**Effort:** ~2–3 sessions
**Risk:** Low (additive, no new persistence semantics)
**Depends on:** Task 01 (the consistency-core rebuild must land first — the property tests protect the core changes)

#### Step 1: Add jqwik dependency

**File:** `pom.xml` (test scope)

```xml
<dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>1.8.5</version>
    <scope>test</scope>
</dependency>
```

#### Step 2: Implement the properties

| Property | What |
|---|---|
| `randomizedCRUDRoundTrip` | Random CRUD operations on all three policies are consistent (load after save == save, load after delete == absent) |
| `randomBTreeOperationSequencesHoldInvariant` | Random BTree insert/delete/balance sequences hold the invariant suite |
| `journalReplayEquivalence` | base + journal == base written directly (the journal is equivalent to the base) |
| `MVCCSnapshotLinearizability` | MVCC snapshot linearizability on a two-thread schedule model |
| `FKInvariantRoundTrip` | FK invariants hold after randomized FK operations (child save without parent veto, parent save without children success, etc.) |

**Step 2 acceptance:**
- [ ] All 5 properties implemented
- [ ] All 5 properties pass (no failures)
- [ ] Every shrunk failing case becomes a pinned regression test

#### Step 3: Add deterministic seeds

**File:** `docs/perf/` (the task 13 protocol)

```java
@Property(distribution = "seeded", seed = 12345)
void randomBTreeOperationSequencesHoldInvariant() { ... }
```

The deterministic seeds are recorded per failing property. The quick-gate budget is respected (property runs are sized to fit; the heavy ones tagged stress).

**Step 3 acceptance:**
- [ ] Every failing property has a deterministic seed
- [ ] The seed is recorded in the task file
- [ ] The seed can reproduce the failure

#### Step 4: Add the gate integration

**File:** `pom.xml` (surefire profiles)

| Property | Gate status | After task 03 |
|---|---|---|
| `FxStorageBTreeInvariantProperty` | ❌ Excluded (gate) | ✅ Include (with the quick-gate budget) |
| `FxStorageJournalReplayEquivalenceProperty` | ❌ Excluded (gate) | ✅ Include (with the quick-gate budget) |
| `FxStorageFKInvariantProperty` | ❌ Excluded (gate) | ✅ Include (with the quick-gate budget) |

**Step 4 acceptance:**
- [ ] The property tests return to the gate (with the quick-gate budget)
- [ ] The nightly policy documented (the gate is nightly ×3, not full-suite)
- [ ] The exclusion decision documented in FLAKY.md

---

## Plan P2: Operability

### P2-1: Recovery Recipe Automation

**Priority:** P2
**Effort:** ~1–2 dev-days
**Risk:** Low (read-only, no mutations)
**Depends on:** `verify()` (task 26, already implemented)

#### Step 1: Extend `verify()` to output the recipe steps

**File:** `src/main/java/io/github/xdstorage/utils/FxStorageFolderVerifier.java`

Add a method:
```java
public FxStorageVerifyReport verifyWithRecipe(Path folder) {
    FxStorageVerifyReport report = verify(folder);
    if (report.verdict == CORRUPT) {
        report.addRecipeSteps(recoverRecipe(report));
    }
    return report;
}

private List<String> recoverRecipe(FxStorageVerifyReport report) {
    List<String> steps = new ArrayList<>();
    for (var entry : report.entries()) {
        if (entry.severity == CORRUPT) {
            steps.add("STEP: " + stepFor(entry.file));
        }
    }
    return steps;
}
```

Where `stepFor(file)` returns:
- For a truncated base: "Open with acceptMissingEofSentinel(true), verify critical objects, commit to rewrite sentinel"
- For a torn journal: "Accept (the journal is designed to be torn — recent commits are lost)"
- For a corrupted tree: "Drop the tree file, reopen — the tree will be rebuilt"
- For an orphaned journal: "Accept (un-checkpointed state is normal for an open folder)"

#### Step 2: Add the CLI `recover` command (FP-6, 2.3.0)

**File:** `src/main/java/io/github/xdstorage/cli/FxStorageTools.java`

```
fxstorage-tools.jar recover <folder>
```

This runs `verifyWithRecipe(folder)` and prints the steps. The user can then manually execute the steps (e.g., the "drop tree file" step requires manual file deletion).

#### Step 3: Add the "automatic safe recovery" path

For cases that are safe to auto-recover:
- Torn journal tail → auto-accept (the design says it's safe)
- Orphaned journal → auto-delete
- Stale `.tmp` → auto-delete

The `recover` command should auto-execute these safe steps and print "auto-recovered: X files fixed" before printing manual steps.

**Acceptance:**
- [ ] `verifyWithRecipe()` produces actionable steps for every corruption class
- [ ] `recover` CLI command works
- [ ] Safe auto-recovery works (torn journal, orphaned journal, stale .tmp)
- [ ] Manual steps are correct (the recipe from EMBEDDING.md)

---

### P2-2: Document the Consistency-Core Tradeoff Prominently

**Priority:** P2
**Effort:** ~0.5 dev-days
**Risk:** None (documentation only)

#### Step 1: Add a prominent note in `FxStorageConfig`

```java
/**
 * The consistency-core rebuild (task 01) replaces the hand-rolled
 * spin/notify lock with standard j.u.c primitives. The tradeoff:
 *
 * <ul>
 *   <li>The new locks are fair (the old locks were unfair — the fairness
 *       was the R-28/R-29/R-30 rejection reason). Fairness adds latency
 *       (the fair lock has a queue; the unfair lock has a spin). The
 *       measured tradeoff is documented in the task 13 profiling.</li>
 *   <li>The new locks are interruptible (the old locks were not — a
 *       timed-out transaction could not interrupt a lock wait). The
 *       new behavior is the correct behavior for a database.</li>
 *   <li>The new locks provide diagnostics (the old locks did not — the
 *       "locked for update" message was the only diagnostic). The new
 *       diagnostics are documented in the task 13 profiling.</li>
 * </ul>
 *
 * The decision to rebuild is P0 (task 01) because the lock infrastructure
 * is the foundation that the FK engine, the B+Tree, and the resource cache
 * all depend on.
 */
public class FxStorageConfig { ... }
```

#### Step 2: Update the EMBEDDING.md "After a power loss" section

Add a more prominent section:
```markdown
### The consistency-core tradeoff

The 2.2.0 consistency-core rebuild (task 01) replaces the hand-rolled
spin/notify lock with standard j.u.c primitives. The tradeoff is:
- Fairness adds latency (the fair lock has a queue; the unfair lock
  has a spin)
- The new locks are interruptible (the old locks were not)
- The new locks provide diagnostics (the old locks did not)

The measured tradeoff is documented in the task 13 profiling (the
lock-wait distribution for the consistency-core design).
```

**Acceptance:**
- [ ] The note in FxStorageConfig is prominent (the Javadoc is the contract)
- [ ] EMBEDDING.md updated with the consistency-core tradeoff
- [ ] The task 13 profiling is referenced (the measured tradeoff is in the profile)

---

## Plan P3: Hygiene

### P3-1: Cross-Process Consistency Coordination

**Priority:** P3
**Effort:** ~1 dev-day
**Risk:** Low (additive, no new persistence semantics)

**Problem:** Two processes sharing the same folder need coordinated fsync. The OS file lock (S-3) ensures only one writer, but if two processes share the folder (e.g., HA deployment), the fsync of one process doesn't guarantee the other sees the durable data.

**Solution:** Add a "consistency coordinator" that:
1. At commit time, after fsync of the local file, also fsync a "consistency marker" file that the other process monitors
2. The marker file is a simple byte sequence (e.g., `0xDEADBEEF`) that changes on every commit
3. The reader (the other process) monitors the marker file and knows when data is consistent across the filesystem

This is the "write-through cache" pattern adapted for file-based storage.

**Implementation:**
```java
// FxStorageConsistencyCoordinator.java
void onCommit() {
    // After fsync of the local file, write the consistency marker
    Files.write(markerPath, new byte[]{0xDE, 0xAD, 0xBE, 0xEF});
}
```

The marker file is in the storage folder (not in the resource directory). It is a simple "I have committed something" signal.

**Acceptance:**
- [ ] `onCommit()` writes the marker
- [ ] The other process monitors the marker (the "consistency check" is a file-modified-time comparison)
- [ ] The coordinator is documented (in CHANGELOG.md and EMBEDDING.md)

---

### P3-2: No-Sentinel-Legacy Folder Auto-Repair

**Priority:** P3
**Effort:** ~1 dev-day
**Risk:** Low (additive)

**Problem:** The `acceptMissingEofSentinel(true)` path rewrites files on first commit, but if the first commit fails, the folder is left in a broken state (partial rewrite). There is no "repair this broken legacy folder" command.

**Solution:** Add a `repair(folder)` command that:
1. Scans all YAML files
2. For each file without a sentinel: rewrites it with the sentinel (using the existing writer)
3. For each file with a corrupted sentinel: deletes it and rewrites from the journal (if available) or reports it as unrecoverable
4. For each file that is completely empty: reports it as unrecoverable

```java
// FxStorageRepair.java
void repair(Path folder) {
    for (var file : walk(folder)) {
        if (!hasSentinel(file)) {
            rewriteWithSentinel(file);
        } else if (sentinelIsCorrupted(file)) {
            if (hasJournal(file)) {
                rewriteFromJournal(file);
            } else {
                reportUnrecoverable(file);
            }
        }
    }
}
```

**Acceptance:**
- [ ] `repair()` fixes all corruptible files
- [ ] `repair()` does not mutate healthy files
- [ ] The repair is documented in EMBEDDING.md

---

## Summary of All Tasks

| Task | Priority | Effort | Status |
|---|---|---|---|
| P0-1: Consistency-core rebuild (task 01) | P0 | 5–8 sessions | ❌ Not started |
| P0-2: Loud-failures inventory (task 02) | P0 | 2–3 sessions | ❌ Not started |
| P0-3: Journal/WAL TX-ID hardening (task 06) | P1 | 1 session | ❌ Not started |
| P1-1: W-4d Memory train (task 05) | P1 | 3–5 sessions + design | ❌ Not started |
| P1-2: Property-based tests (task 03) | P1 | 2–3 sessions | ❌ Not started |
| P2-1: Recovery recipe automation | P2 | 1–2 days | ❌ Not started |
| P2-2: Document the consistency-core tradeoff | P2 | 0.5 days | ❌ Not started |
| P3-1: Cross-process consistency coordination | P3 | 1 day | ❌ Not started |
| P3-2: No-sentinel auto-repair | P3 | 1 day | ❌ Not started |

**Total effort:** ~18–20 sessions

**Execution order (recommended):**
1. **P0-1** (consistency-core rebuild) — the biggest lever for 2.2.0
2. **P0-2** (loud-failures inventory) — the second priority (P0)
3. **P0-3** (WAL TX-ID hardening) — the small win
4. **P2-2** (document the consistency-core tradeoff) — zero risk, high value
5. **P1-2** (property-based tests) — the testing milestone
6. **P1-1** (W-4d memory train) — the biggest capacity risk
7. **P2-1** (recovery recipe automation) — the operability milestone
8. **P3-1** (cross-process consistency) — for HA deployments
9. **P3-2** (no-sentinel repair) — for 1.x migration

---

## Execution Notes

- **No new dependencies.** All implementations use existing JDK classes (LRU is `Collections`-backed, checkpoint is the existing `writeObjects` + atomic rename).
- **No format bumps.** The WAL TX-ID hardening is additive (the enriched sentinel is a new format that the old sentinel is backward-compatible with).
- **No behavior changes for the default.** The existing behavior is preserved; the new items are additive.
- **The consistency-core rebuild is P0 because it is the foundation.** The FK engine, the B+Tree, and the resource cache all depend on it. Without it, none of the other items are safe (the lock infrastructure is the foundation).
- **The property-based tests (task 03) are the acceptance gate for the consistency-core rebuild (task 01).** The task 03 results become the task 01 acceptance criteria — a property-based test that fails after the consistency-core rebuild is the evidence that the core change broke an invariant.
