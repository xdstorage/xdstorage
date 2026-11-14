# Durability — Implementation & Extension Plan

**Created:** 2026 (current session)
**Based on:** DURABILITY_ANALYSIS.md
**Scope:** Step-by-step implementation plan for durability improvements
**Ordering:** P0 → P1 → P2 → P3 (by priority, not by effort)

---

## Plan P0: Data Safety (Blocking Release)

### P0-1: Checkpoint-on-Clean-Shutdown (W-4c)

**Priority:** P0
**Effort:** ~2 dev-days
**Risk:** Low (additive, no new persistence semantics)
**Depends on:** W-1 (journal already exists), W-4a (index journal exists)

#### Step 1: Add the checkpoint-on-shutdown trigger

**File:** `src/main/java/io/github/xdstorage/transaction/XdStorage.java` (or the services locator)

```java
// In shutdown() method, before unregistering the storage:
void shutdown() {
    // ... existing shutdown logic ...
    checkpointAllJournals();  // NEW: trigger checkpoint for all open resources
}

void checkpointAllJournals() {
    // 1. Acquire the resources manager
    // 2. Iterate all cached resources (the manager knows them)
    // 3. For each resource with a non-empty journal:
    //    - call the resource's checkpoint() method (full base rewrite)
    //    - the checkpoint method: writeObjects(cacheView) → atomic rename → journal delete
    // 4. After all checkpoints, the journals are all empty
    // Effect: after a clean close, base files are authoritative, journals empty
    // Power loss after clean close → loses nothing even with durableWrites=false
}
```

**Key challenge:** The resources manager (`XdStorageAbstractResourcesManager`) must expose a method to iterate all cached resources. Currently it's package-private. Add:
```java
public Collection<IResource> allResources();
```

**Checkpoint method on the resource:**
```java
public void checkpoint(XdStorageTransaction tx) {
    // 1. Lock the resource (same as phase-1 write-out)
    // 2. writeObjects(cacheView, writer) — full base rewrite (existing writer, R-43 guard)
    // 3. Atomic rename to target path (existing mechanism)
    // 4. Delete the journal file (if it exists)
    // 5. Unlock
}
```

**Step 1 acceptance:**
- [ ] `shutdown()` calls `checkpointAllJournals()`
- [ ] All open resources with non-empty journals are checkpointed
- [ ] After shutdown + power-loss simulation, open → clean, all objects intact
- [ ] No journal files remain after shutdown (all empty)

#### Step 2: Add the size-threshold checkpoint guard at open time

**File:** `src/main/java/io/github/xdstorage/serialization/yaml/XdStorageYamlCommitJournal.java`

At open, if a journal is larger than the threshold (default 16 MB):
```java
void checkpointIfTooBig(Path journalPath) {
    long size = Files.size(journalPath);
    if (size > journalCheckpointBytes) {
        log.warn("journal {} is {} bytes (exceeds {} threshold) — triggering checkpoint",
                journalPath, size, journalCheckpointBytes);
        checkpoint();
    }
}
```

**Step 2 acceptance:**
- [ ] A journal > 16 MB at open triggers a checkpoint after replay
- [ ] The checkpoint is idempotent (replay-then-checkpoint is safe)
- [ ] No new file corruption

#### Step 3: Add the `checkpoint()` API to `IXdStorage`

**File:** `src/main/java/io/github/xdstorage/IXdStorage.java`

```java
/**
 * Forces a checkpoint for the storage: rewrites all base files for all open
 * resources (full file write, atomic rename), then deletes all journals.
 * After this call, the folder is in "clean" state — base files authoritative,
 * journals empty.
 */
void checkpoint();
```

**Step 3 acceptance:**
- [ ] `checkpoint()` is callable at any time (not just shutdown)
- [ ] After `checkpoint()`, all base files are authoritative, journals empty
- [ ] The call is thread-safe (no concurrent commit while checkpointing)

#### Step 4: Add the `compact()` CLI command (FP-6)

**File:** `src/main/java/io/github/xdstorage/cli/XdStorageTools.java` (new, 2.3.0)

```
xdstorage-tools.jar compact <folder>
```

This calls `storage.checkpoint()` on the folder (open, checkpoint, close).

**Step 4 acceptance:**
- [ ] CLI command works from command line
- [ ] After `compact`, folder is in clean state
- [ ] The command is documented in `docs/CLI.md`

#### Step 5: Add tests

**File:** `src/main/test/.../reliability/FxStorageCheckpointOnShutdownTest.java` (new)

| Test | What |
|---|---|
| `testCheckpointOnShutdown_emptyJournal` | No-op when journal is empty |
| `testCheckpointOnShutdown_nonEmptyJournal` | Journal is rewritten to base, journal deleted |
| `testCheckpointOnShutdown_multipleResources` | All resources checked, not just one |
| `testCheckpointOnShutdown_afterCommit` | Journal written, then shutdown → checkpoint |
| `testCheckpointApi` | `storage.checkpoint()` works independently of shutdown |
| `testCheckpointOnOpen_guard` | A large journal at open triggers checkpoint after replay |

**Step 5 acceptance:**
- [ ] All 6 tests green
- [ ] No regression in existing durability tests

---

### P0-2: W-4b (Search-Key Logical-Op Journal)

**Priority:** P0
**Effort:** ~3 dev-days
**Risk:** Medium (replay must go through canonical registry, R-20)
**Depends on:** W-4a (base journal pattern), WRITE-PATH-DESIGN.md design

#### Step 1: Design the logical-op format

**Design (from WRITE-PATH-DESIGN.md):**
```yaml
# Search-key journal: (treeId, op, key, objectId)
- {treeId: "search-tree-Order", op: ADDKEY, key: "Ship-5", objectId: "5"}
- {treeId: "search-tree-Order", op: DELKEY, key: "Ship-3", objectId: "3"}
```

- **Replay:** replayed into the live canonical tree at open, through the same code path live inserts use (so splits/rebalances happen naturally, key types are built not parsed)
- **Idempotent:** ADDKEY for existing key is a no-op (tree's own idempotent insert); DELKEY for non-existing key is a no-op
- **No topology stored:** the journal never stores tree structure, only the operations the live tree replays against itself

#### Step 2: Implement the replay at open

**File:** `src/main/java/io/github/xdstorage/serialization/yaml/XdStorageYamlCommitJournal.java`

After loading the base file, replay the journal:
```java
void replayIntoTree(XdStorageBTree tree, Path journalPath) {
    var journal = new XdStorageYamlCommitJournal(journalPath);
    for (var record : journal.readAll()) {
        if (record.op == ADDKEY) {
            tree.insert(record.key, record.objectId, storage, tx);
        } else if (record.op == DELKEY) {
            tree.delete(record.key, storage, tx);
        }
    }
}
```

**Key challenge:** The replay must go through the canonical registry (R-20). The tree being replayed is the one in the registry, not a new instance.

#### Step 3: Add the `searchIndexJournalWrites` config flag

**File:** `src/main/java/io/github/xdstorage/XdStorageConfig.java`

```java
private boolean searchIndexJournalWrites = false;  // default OFF → soak → flip
```

**Step 3 acceptance:**
- [ ] `searchIndexJournalWrites=true` enables journal for search-index trees
- [ ] `searchIndexJournalWrites=false` (default) → full-file rewrite (unchanged behavior)
- [ ] Default OFF until a green soak (the W-3 flip protocol)

#### Step 4: Add the write-out in phase-1

**File:** `src/main/java/io/github/xdstorage/search/XdStorageSearchIndex.java` (the search-index resource)

In `performFirstPhaseCommit`:
```java
if (searchIndexJournalWrites && !isInfrastructureCache()) {
    journal.append(records);  // UPSERT/DELETE for each search-key change
} else {
    writeObjects(cache.readUnsafe(tx));  // full-file rewrite (unchanged)
}
```

#### Step 5: Add tests

| Test | What |
|---|---|
| `testReplayAddKey` | ADDKEY replay adds the key to the tree |
| `testReplayDelKey` | DELKEY replay removes the key |
| `testReplayIdempotent` | Replay twice → same result |
| `testReplayAgainstCanonicalTree` | Replay goes through the canonical registry |
| `testReplaySplitRebalance` | A large replay causes splits/rebalances naturally |
| `testReplayWithExistingBase` | Base + journal replay is consistent |

#### Step 6: Add the soak → flip gate

Per the W-3 flip protocol:
1. Implement with `searchIndexJournalWrites=false` (default)
2. Run soak: full suite with `searchIndexJournalWrites=true`
3. If green → flip default to `true`
4. If any failure → document the failure, defer

**Step 6 acceptance:**
- [ ] The soak is green ×3 (the W-3 protocol: green before flip)
- [ ] After flip, all durability tests still pass
- [ ] BENCHMARKS.md updated with the delta

---

## Plan P1: Capacity & Scaling

### P1-1: W-4d (Disk-Anchored Scans + Record-Cache Eviction)

**Priority:** P1
**Effort:** ~3–4 dev-days + design round
**Risk:** High (touches the read path)
**Depends on:** W-4a/b complete, heap data demands it (task 20's N50K results)

#### Step 1: Design the eviction policy

**From MASTER-PLAN.md:**
- LRU over committed, unlocked, non-infra records
- A scan that meets an evicted record re-loads it from the base file (+ journal replay state already in memory)
- The per-transaction materialization budget (W-2, `txMaterializedObjectsCap`) already bounds the clone side
- Design-round input: off-heap caching (Direct ByteBuffers / FFM — but Java 8 bytecode constraint rules out FFM, so DirectByteBuffer lifecycle is fragile)

**Decided:** on-heap LRU (not off-heap) for 2.2.0. Off-heap is FP-8 (2.3.0+).

#### Step 2: Implement the LRU cache

**File:** `src/main/java/io/github/xdstorage/resource/cache/XdStorageResourceCache.java`

```java
// Instead of: private final Map<Object, XdStorageCacheRecord> records = new ConcurrentHashMap<>();
// Use:
private final Map<Object, XdStorageCacheRecord> records = new LruCache<>(maxSize);
// where LruCache is a thread-safe LRU (size-bounded, eviction on put)
```

**Eviction trigger:** on `put(id, record)` when the map size > `maxSize`, evict the least-recently-used record (the record that was last accessed).

**The evicted record is not destroyed** — it remains in a "cold" state where `read()` checks the cache first; if the record is evicted (not present), re-load from disk (base file + journal).

#### Step 3: Implement disk-anchored scans

**File:** `src/main/java/io/github/xdstorage/resource/XdStorageResource.java` (the resource read path)

When scanning (predicate scan, watch, loadAll), if a record is not in the cache:
```java
public Collection<T> scan(XdStorageTransaction tx) {
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

#### Step 4: Add the config knob

**File:** `src/main/java/io/github/xdstorage/XdStorageConfig.java`

```java
private long cacheRecordsCap = 0;  // 0 = unlimited (current default)
```

The `txMaterializedObjectsCap` (W-2) bounds per-transaction materialization. `cacheRecordsCap` bounds the total cache size across all transactions.

**Acceptance:**
- [ ] `cacheRecordsCap > 0` bounds the total cache size
- [ ] Evicted records are re-loaded from disk correctly
- [ ] Scans are MVCC-consistent after eviction
- [ ] No OOM under N50K with bounded cache

---

### P1-2: Backup API (FP-5)

**Priority:** P1
**Effort:** ~1 dev-day
**Risk:** Low (copy files, same as the existing "close, cp -r, open" recipe)
**Depends on:** MVCC consistency (the snapshot is consistent at the commit point)

#### Step 1: Add the `backupTo(Path)` method

**File:** `src/main/java/io/github/xdstorage/IXdStorage.java`

```java
/**
 * Produces a consistent point-in-time copy of this storage's folder at the given path.
 * The copy is a valid storage folder: it can be opened independently.
 *
 * @param targetPath the destination folder (must not exist)
 * @throws IOException on copy failure
 */
void backupTo(Path targetPath) throws IOException;
```

**Implementation:**
```java
void backupTo(Path targetPath) throws IOException {
    // 1. Ensure the folder is closed (or at least quiesced)
    // 2. Walk the folder, copy all files (including journals, structure, indexes)
    // 3. The copy is a valid folder — it can be opened under a different name
    //    (the existing "close, cp -r, open" recipe)
}
```

**Key constraint:** The backup must be consistent at a single commit point. With MVCC, the "close, cp -r, open" recipe is already consistent (the folder is quiesced at the commit). For a live backup (no close), the snapshot is the last committed transaction's state — which is the best we can do without a freeze-copy.

#### Step 2: Add tests

| Test | What |
|---|---|
| `testBackupTo_newFolder` | Copy to a new folder, open it, verify all objects |
| `testBackupTo_existingFolder` | Error when target exists |
| `testBackupTo_copyJournal` | Journal files are copied (needed for recovery) |
| `testBackupTo_copyStructure` | Structure files are copied |
| `testBackupTo_openCopy` | The copy can be opened under a different name |

#### Step 3: Document in EMBEDDING.md

Add to "Backup and restore" section:
```markdown
### Programmatic backup
```java
storage.backupTo(Paths.get("/backup/2026-11-01"));
```
This produces a consistent point-in-time copy. Open it under a different name.
```

**Acceptance:**
- [ ] All 5 tests green
- [ ] The copy is a valid storage folder
- [ ] EMBEDDING.md updated

---

## Plan P2: Operability

### P2-1: Recovery Recipe Automation

**Priority:** P2
**Effort:** ~1–2 dev-days
**Risk:** Low (read-only, no mutations)
**Depends on:** `verify()` (task 26, already implemented)

#### Step 1: Extend `verify()` to output the recipe steps

**File:** `src/main/java/io/github/xdstorage/utils/XdStorageFolderVerifier.java`

Add a method:
```java
public XdStorageVerifyReport verifyWithRecipe(Path folder) {
    XdStorageVerifyReport report = verify(folder);
    if (report.verdict == CORRUPT) {
        report.addRecipeSteps(recoverRecipe(report));
    }
    return report;
}

private List<String> recoverRecipe(XdStorageVerifyReport report) {
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

**File:** `src/main/java/io/github/xdstorage/cli/XdStorageTools.java`

```
xdstorage-tools.jar recover <folder>
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

### P2-2: Document `durableWrites` Tradeoff Prominently

**Priority:** P2
**Effort:** ~0.5 dev-days
**Risk:** None (documentation only)

#### Step 1: Add a prominent note in `XdStorageConfig`

```java
/**
 * Whether commits fsync the data file and parent directory.
 * <p>
 * <b>WARNING:</b> with this OFF (the default), commits are process-crash-safe
 * (atomic rename) but <b>NOT</b> power-loss-safe. A power loss between the
 * commit and the next fsync loses recent data (the OS page cache is lost with
 * the power). The measured cost of enabling this is ~8× on write throughput
 * (see BENCHMARKS.md § Canonical).
 * <p>
 * For power-loss durability:
 * <ol>
 *   <li>Enable this flag (the tradeoff is documented)</li>
 *   <li>Ensure checkpoint-on-shutdown is active (W-4c)</li>
 *   <li>Or use the backup API (FP-5) for periodic consistent copies</li>
 * </ol>
 */
public boolean isDurableWrites() { ... }
```

#### Step 2: Update the EMBEDDING.md "After a power loss" section

Add a more prominent section:
```markdown
### The durability tradeoff

The default configuration is **process-crash safe, not power-loss safe**.
With `durableWrites=false` (default), a power loss between commits loses recent data.

**If power-loss durability matters:**
```java
XdStorageConfig.builder().durableWrites(true).build()
```

**Cost:** ~8× write throughput (BENCHMARKS.md).

**If you need power-loss durability without the 8× cost:**
1. Enable checkpoint-on-shutdown (W-4c) — after any clean close, power loss loses nothing
2. Use the backup API (FP-5) for periodic consistent copies
```

---

## Plan P3: Hygiene

### P3-1: Cross-Process Durability Coordination

**Priority:** P3
**Effort:** ~1 dev-day
**Risk:** Low (additive, no new persistence semantics)

**Problem:** Two processes sharing the same folder need coordinated fsync. The OS file lock (S-3) ensures only one writer, but if two processes share the folder (e.g., HA deployment with a shared filesystem), the fsync of one process doesn't guarantee the other sees the durable data.

**Solution:** Add a "durability coordinator" that:
1. At commit time, after fsync of the local file, also fsync a "durability marker" file that the other process monitors
2. The marker file is a simple byte sequence (e.g., `0xDEADBEEF`) that changes on every commit
3. The reader (the other process) monitors the marker file and knows when data is durable across the filesystem

This is the "write-through cache" pattern adapted for file-based storage.

**Implementation:**
```java
// XdStorageDurabilityCoordinator.java
void onCommit() {
    // After fsync of the local file, write the durability marker
    Files.write(markerPath, new byte[]{0xDE, 0xAD, 0xBE, 0xEF});
}
```

The marker file is in the storage folder (not in the resource directory). It is a simple "I have committed something" signal.

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
4. For each file that is completely empty: reports as unrecoverable

```java
// XdStorageRepair.java
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
| P0-1: Checkpoint-on-shutdown | P0 | 2 days | ❌ Not started |
| P0-2: W-4b (search-key journal) | P0 | 3 days | ❌ Not started |
| P1-1: W-4d (eviction) | P1 | 3–4 days + design | ❌ Not started |
| P1-2: Backup API (FP-5) | P1 | 1 day | ❌ Not started |
| P2-1: Recovery recipe automation | P2 | 1–2 days | ❌ Not started |
| P2-2: Document durableWrites tradeoff | P2 | 0.5 days | ❌ Not started |
| P3-1: Cross-process fsync coordination | P3 | 1 day | ❌ Not started |
| P3-2: No-sentinel auto-repair | P3 | 1 day | ❌ Not started |

**Total effort:** ~13–14 dev-days

**Execution order (recommended):**
1. **P0-1** (checkpoint-on-shutdown) — the single biggest durability improvement for the common case
2. **P0-2** (W-4b) — the remaining O(N) wall
3. **P2-2** (document the tradeoff) — zero risk, high value
4. **P1-2** (backup API) — the next milestone for 2.3.0
5. **P2-1** (recovery recipe automation) — makes the manual recipe actionable
6. **P1-1** (eviction) — the biggest capacity risk
7. **P3-1** (cross-process fsync) — for HA deployments
8. **P3-2** (no-sentinel repair) — for 1.x migration

---

## Execution Notes

- **No new dependencies.** All implementations use existing JDK classes (LRU is `Collections`-backed, checkpoint is the existing `writeObjects` + atomic rename).
- **No format bumps.** W-4b is additive (the search-key journal file is a new file; existing folders don't have one).
- **No behavior changes for `durableWrites=false`.** The existing behavior is preserved; the new items are additive.
- **The W-3 flip protocol applies to W-4b:** implement with the flag OFF, soak green, then flip.
- **The W-4d eviction is deferred** because it touches the read path (the most risky surface in the engine). It is not P0 — the current unbounded cache is a capacity risk, not a correctness risk.
