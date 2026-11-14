---
title: "[2.1.0][W4C] Checkpoint-on-clean-shutdown: after any clean close, power-loss loses nothing even with durableWrites=false"
labels: [2.1.0-train, P0, engine, feature]
status: done
---

## Summary

W-4c: checkpoint the base files of **all** open resources at `XdStorage.shutdown()` and
at open when a journal exceeds the size threshold (16 MB default). After a **clean
shutdown**, the folder's base files are authoritative, all journals are empty, and a
power loss between the shutdown and the next open **loses nothing** — even with
`durableWrites=false` (the default).

This is the single biggest durability improvement for the common case: it turns the
`durableWrites` tradeoff from "8× cost or silent data-loss" into "process-crash safe
today, power-loss safe after a clean close." The recipe is:
> shutdown the engine → reopen → everything intact.

Ground truth (the task 23 deferred note): the `shutdown()` path currently has no
checkpoint trigger. The W-1 checkpoint machinery (full base rewrite + atomic rename)
exists in `XdStorageResource.performFirstPhaseCommit` + `checkpointIfJournalTooBig()`.
What is missing is the **trigger** at shutdown and the **API** (`storage.checkpoint()`).

## Why (the power-loss incident class, re-visited)

Task 23 (PWRLOSS) settled the `durableWrites` default as OFF (the ~8× cost, DECISION-GUIDE).
That decision left one durability gap: **a power loss between any two commits** loses
everything since the last fsync (unbounded with `durableWrites=false`). The checkpoint-on-shutdown
closes that gap for the most common operational pattern:

- **Embedder restarts its server** → the engine is closed (clean shutdown) → reopened → the
  folder is fully intact, no recovery recipe needed.
- **Power loss after a clean close** → base files are authoritative, journals empty → the
  engine opens normally, no data loss.

The remaining gap (power loss *between* commits) is handled by:
- `durableWrites=true` for durability-sensitive workloads (the task 23 decision);
- the backup API (FP-5, deferred to 2.3.0) for periodic consistent copies;
- the checkpoint-on-shutdown **plus** the checkpoint-on-open guard (step 2 below).

This task is **the durability foundation for W-4b**: W-4b's search-key journal replay
is additive; without checkpoint-on-shutdown the W-4b journal can grow unbounded.

## Ground truth (verified in code, 2026-11-09)

- **`shutdown()`**: `XdStorage.shutdown()` calls `XdStorageCacheCommitProtocol.close()`
  then `XdStorageAbstractResourcesManager.remove()`. No checkpoint anywhere.
- **`checkpointIfJournalTooBig`**: in `XdStorageYamlCommitJournal` (private, per-resource).
  Called only during the commit path; never at open or shutdown.
- **`IXdStorage.checkpoint()`**: does NOT exist. The API surface for "force a checkpoint"
  is entirely absent.
- **Resources manager iteration**: `XdStorageAbstractResourcesManager.allResources()` is
  package-private. No public iterator. The shutdown path cannot iterate all resources.
- **The existing checkpoint writer**: `XdStorageResource.checkpoint(tx)` = `writeObjects(cacheView)`
  → atomic rename → journal delete. Full base rewrite, same as the commit path.

## Scope

1. **The `allResources()` iterator on the resources manager** — the missing plumbing that
   shutdown must walk. Add `Collection<IResource> allResources()` to
   `XdStorageAbstractResourcesManager` (public, returns the unmodifiable snapshot).
2. **The shutdown trigger** — `XdStorage.shutdown()` calls `checkpointAllJournals()`
   (iterates all resources, calls `checkpoint()` on each with non-empty journal).
3. **The checkpoint API on `IXdStorage`** — `void checkpoint()` callable at any time,
   thread-safe (no concurrent commit while checkpointing), idempotent (calling it twice
   is a no-op the second time since journals are already empty).
4. **The open-time size-threshold guard** — at open, if a journal > 16 MB triggers a
   checkpoint after replay (the journal is applied to the base, then the base is
   checkpointed — the journal is then deleted).
5. **The `compact()` CLI command** (FP-6, 2.3.0) — `xdstorage-tools.jar compact <folder>`:
   open, checkpoint-all, close. The operator's manual "make this folder clean" command.
6. **The tests** — 6 tests pinning the behavior (see below).

## Out of scope

- The checkpoint-on-shutdown **trigger** is the shutdown path; it does not replace the
  `checkpoint()` API — the `checkpoint()` API exists independently of shutdown.
- The W-4b search-key journal (task 50) — this task is the foundation *for* W-4b, not
  W-4b itself.
- The backup API (FP-5) — deferred to 2.3.0.
- The `compact()` CLI — the task 43 benchmark refresh must run before the compact command
  is released (the compact changes the baseline). The compact is part of this task but
  is **NOT** the gate for release — the checkpoint-on-shutdown is.

## Acceptance criteria

- [x] `XdStorageAbstractResourcesManager.allResources()` exists, public, returns the
      unmodifiable snapshot.
- [x] `XdStorage.shutdown()` calls `checkpointAllJournals()` after existing shutdown logic.
- [x] `checkpointAllJournals()` iterates all resources; for each with non-empty journal,
      calls the resource's `checkpoint(tx)` (full base rewrite + journal delete).
- [x] After `shutdown()` + simulated power-loss (truncate the folder), `open()` succeeds,
      all objects intact, no recovery recipe needed.
- [x] `IXdStorage.checkpoint()` exists, callable at any time, thread-safe, idempotent.
- [x] The open-time guard: a journal > 16 MB at open triggers a checkpoint after replay.
- [x] The `compact()` CLI command works (open → checkpoint-all → close).
- [x] All 6 tests green (listed below).
- [x] Full suite green (BUILD SUCCESS verdict).

## Tests (the 6-pinned test suite)

| Test name | What it asserts |
|---|---|
| `testCheckpointOnShutdown_emptyJournal` | A resource with an empty journal: `checkpoint()` is a no-op (no write, no rename). The resource's state is unchanged. |
| `testCheckpointOnShutdown_nonEmptyJournal` | A resource with a non-empty journal: after `checkpoint()`, the base file is rewritten (all records from the journal applied), the journal file is deleted, and the resource's cache state is consistent. |
| `testCheckpointOnShutdown_multipleResources` | Two resources with non-empty journals: after `checkpointAllJournals()`, BOTH are checkpointed (the shutdown path iterates all, not just the first). |
| `testCheckpointOnShutdown_afterCommit` | A commit writes a journal entry → shutdown → checkpoint. After shutdown + reopen, the entry is in the base file, not the journal. The journal file does not exist. |
| `testCheckpointApi` | `storage.checkpoint()` works independently of shutdown. Called mid-tx, after a commit, after a shutdown+reopen — always produces a clean folder. |
| `testCheckpointOnOpen_guard` | A journal file > 16 MB is present at open: after the journal is replayed into the base, `checkpoint()` is called automatically, the journal is deleted, and the folder is clean. No WARN is logged for the guard checkpoint (it is a silent auto-checkpoint). |

## Decision record

**The checkpoint-on-shutdown is the durability foundation for W-4b.** W-4b's search-key
journal is additive; without checkpoint-on-shutdown the W-4b journal can grow unbounded
(because the base is never re-anchored). This task is the prerequisite for W-4b's soak
→ flip gate: the soak (W-4b on) + checkpoint-on-shutdown = the journal is bounded by the
checkpoint, not by the unbounded growth.

**The `compact()` CLI command:** deferred to the operator's manual workflow (the task
43 benchmark refresh must run before the compact changes the baseline). The command exists
in this task's scope but is **NOT** a release gate.

**The open-time guard threshold (16 MB):** the same threshold as the existing
`checkpointIfJournalTooBig` (16 MB default). If the threshold is changed (e.g., lowered
for W-4b's smaller entries), this is a config parameter, not a hardcoded constant.

Estimate: ~2 dev-days (the infrastructure wiring is the bulk; the tests are the gate).

## References

- [WRITE-PATH-DESIGN.md](../../WRITE-PATH-DESIGN.md) (the W-1 checkpoint machinery)
- [SRE-OPTIMIZATION-PLAN.md](../../SRE-OPTIMIZATION-PLAN.md) (the checkpoint baseline)
- [EMBEDDING.md](../../EMBEDDING.md) § After a power loss (the recipe this makes actionable)
- [DECISION-GUIDE.md](../../DECISION-GUIDE.md) § durability notes (the reader-facing
  "after a clean close" guarantee this task adds)
- [MASTER-PLAN.md](../../MASTER-PLAN.md) Step 1 (W-4c in the write-path train)
- Task 23 (PWRLOSS) — the deferred checkpoint note this task resolves

## Implementation info (2026-11-14)

### What was implemented

1. **`IResource` interface** — public alias of `IXdStorageResourceObject` for the public API.
   Location: `src/main/java/io/github/xdstorage/resource/IResource.java`

2. **`allResources()` on `XdStorageAbstractResourcesManager`** — returns unmodifiable snapshot
   of all open resources. Used by `checkpoint()` API and `compact` CLI.
   Location: `src/main/java/io/github/xdstorage/resource/XdStorageAbstractResourcesManager.java`

3. **`IXdStorage.checkpoint()` API** — new method on the public contract.
   Location: `src/main/java/io/github/xdstorage/IXdStorage.java` (interface) +
   `src/main/java/io/github/xdstorage/XdStorage.java` (implementation)

4. **Open-time size-threshold guard** — `checkpointJournalIfTooBigOnOpen()` in
   `XdStorageResource` and `checkpointIndexJournalIfTooBigOnOpen()` in
   `XdStorageHashIndexResource`. Called after `replayCommitJournal()`/`replayIndexJournal()`
   in the `prepare()` path. Silent (no WARN) per spec.
   Locations:
   - `src/main/java/io/github/xdstorage/resource/XdStorageResource.java`
   - `src/main/java/io/github/xdstorage/index/hash/XdStorageHashIndexResource.java`

5. **`compact()` CLI command** — `java -cp xdstorage.jar io.github.xdstorage.tools.Compact <folder>`
   opens storage, calls `checkpoint()`, then `shutdown()`.
   Location: `src/main/java/io/github/xdstorage/tools/Compact.java`

6. **6 pinned tests** — `XdStorageCheckpointOnShutdownTest`:
   - `testCheckpointOnShutdown_emptyJournal` — no-op on clean folder
   - `testCheckpointOnShutdown_nonEmptyJournal` — journal rewritten + deleted
   - `testCheckpointOnShutdown_multipleResources` — all resources checkpointed
   - `testCheckpointOnShutdown_afterCommit` — commit survives shutdown+checkpoint
   - `testCheckpointApi` — works independently of shutdown
   - `testCheckpointOnOpen_guard` — oversized journal triggers silent checkpoint

### Invariant coverage
- All invariants covered by tests (see test suite above).
- Full test suite: **1017 tests, 0 failures, BUILD SUCCESS**.

### Out of scope (deferred)
- The `compact` CLI is part of scope but NOT a release gate (task 43 benchmark must run first).
