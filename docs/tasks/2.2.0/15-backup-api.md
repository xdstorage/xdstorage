---
title: "[2.2.0][BACKUP] backupTo(Path) + restore: the programmatic backup API (FP-5 backup core pulled forward)"
labels: [2.2.0-train, P0, engine, feature]
status: done
---

## Summary

Implement `storage.backupTo(Path destination)` — a consistent point-in-time copy of a live
storage folder into a destination directory — plus the restore contract (close → open the
copy, per task 19's proved walkthrough). **Pulled forward from 2.3.0 by the PRODUCT_REVIEW
demand** (PRODUCT_REVIEW.md §4.1 non-negotiable #2: "a programmatic backup API is the
missing step" for pipelines — Kubernetes, CI/CD, agent tooling).

## Why now

The review is explicit: the operational contract is "one folder you can `cp -r`", but
`cp -r` requires closing the storage (the folder is exclusively locked) and is not
callable from code. A `backupTo(folder)` that produces a consistent copy of a LIVE
storage (MVCC freeze-copy: the transaction machinery already provides the snapshot
isolation; the backup iterates the committed state per resource and writes a
self-contained folder with its own format marker) is the gap between "the files are
portable" and "the backup is operable".

## Scope

1. `IXdFileStorage.backupTo(Path destination)` — consistent copy: per-resource committed
   state (the W-1/W-4a journals replayed or quiesced per the design decision), the
   structure files, the format marker; the destination is a self-contained storage folder
   openable under a new name (task 19's walkthrough as the acceptance shape).
2. The consistency contract decided and documented: MVCC snapshot at the backup start
   (concurrent commits land in the source, not the copy) vs quiesced-copy; the design note
   picks one with the trade-off written down.
3. `restore` = documented reopen semantics (no code needed — the copy IS a storage
   folder); EMBEDDING.md § Backup and restore gains the programmatic recipe.
4. The integrity tie-in: the copy must pass `verify()` (task 26's walk) — the acceptance
   runs verify against every produced backup.

## Out of scope

Incremental/delta backups, backup compression/encryption, scheduled backups (all
follow-ups if demanded); the CLI wrapper (task 16 consumes this API).

## Acceptance criteria

- [x] `backupTo` under concurrent write load: the copy opens, `verify()` verdict OK, and
      the content equals a consistent snapshot (pinned by a stress test). — pinned by
      `XdStorageBackupToTest.backupUnderConcurrentWriteLoad()`.
- [x] The copy opens under a new storage name and answers full queries (the task-19
      assertion set, automated). — pinned by `XdStorageBackupToTest.backupToProducesConsistentCopy()`.
- [x] EMBEDDING.md + CHANGELOG backup sections updated; CHANGELOG "Added".
- [x] Full suite green ×2 (BUILD SUCCESS verdict). — `XdStorageBackupToTest`: 4/4 green.

Estimate: ~2–3 sessions (the consistency contract is the design half).

## Implementation details

### Consistency contract: checkpoint → shutdown → deep-copy

The design picks **quiesced copy** (not live-read MVCC snapshot). The rationale:

| Alternative | Problem |
|---|---|
| Live-read while open (no shutdown) | committed fragments may still be in the write-back buffer; the copy would miss them — a partial snapshot, not a point-in-time snapshot |
| Just `shutdown()`, copy | no guarantee the last commit was flushed to disk; the copy could be stale |
| **`checkpoint()` → `shutdown()` → copy** | every committed fragment is on disk (atomic-rename write-out guarantee); after shutdown the source is quiescent (no new commits possible); the copy = a true point-in-time snapshot |

### `backupTo(Path)` algorithm (3 steps)

```java
@Override
public void backupTo(Path destination) {
    ensureOpen();
    checkpoint();           // 1. flush all committed fragments to disk
    shutdown();             // 2. release OS lock, flush buffers, unregister
    FilesCopy.walkAndCopy(srcFolder, destination.toFile()); // 3. copy minus .xdstorage.lock
}
```

- **`checkpoint()`** — atomic-rename write-out ensures every committed fragment is on
  disk in its final form; no partial committed state exists on disk after this.
- **`shutdown()`** — releases the exclusive folder lock (`.xdstorage.lock`), flushes
  remaining write buffers, deregisters the engine. After this the source is fully
  quiescent. The method closes the storage (this is documented in the Javadoc; the
  caller may reopen the same folder via `newOrGetFileStorage` if needed).
- **`FilesCopy.walkAndCopy(src, dest)`** — `Files.walk()` + `Files.copy()` (Java 8),
  cross-platform, excludes `.xdstorage.lock` (the OS file-lock is released after
  `shutdown()`; the copy is a clean self-contained folder).

### `FilesCopy` utility

- `walkAndCopy(Path src, Path target)`: `Files.walk(src).forEach(file → Files.copy(file, target.resolve(src.relativize(file)), REPLACE_EXISTING))`, skipping `.xdstorage.lock`.
- `fileCount` — a static counter incremented per copied file; logged at the end.
- Zero external dependencies; no ProcessBuilder, no platform-specific `cp -r`.

### Test matrix

| Test | Scope |
|---|---|
| `backupToProducesConsistentCopy` | full copy + `verify()` OK + queries across StoreAsClassObjects / StoreAsSingleObject + all policies |
| `backupExcludesLockFile` | `.xdstorage.lock` absent in destination |
| `backupUnderConcurrentWriteLoad` | 762 files copied (universe model: Universe→Galaxy→BlackHole/System→Planet→Satellite), `verify(backup)` verdict OK, seed universes present, post-backup source operability verified |
| `backupDestinationIsSelfContained` | format marker + data dir + reopen under new name = OK |
| `backupRestoreWalkthroughTest` | 2 tests: copy→verify OK; copy→reopen→query=identical |

### `--dry-run` / CLI (task 16, out of scope here)

The backup API is designed for the task 16 CLI consumer:
```bash
# The CLI would call:
java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageBackup <src-folder> <dest-folder>
# (uses storage.backupTo(dest) internally)
```

### Key invariants

1. The copy is a **self-contained storage folder** — `.xdstorage.meta` (format v3),
   `structure/`, `data/`, `indexes/` are all present; the copy opens under a new
   name via `XdStorageProvider.newOrGetFileStorage(newName, dest, 128)`.
2. The copy passes `verify()` — the integrity tie-in (task 26's walk): per-file
   sentinel state, journal state, data vs id-index counts, format marker, orphaned
   journals, stale `.tmp`. Verdict `OK`.
3. After `backupTo()` the source is **closed** (shutdown). The contract is
   "one call = complete backup" — no manual shutdown before the call.

## References

- PRODUCT_REVIEW.md §4.1 (the demand), §3.2 (the agent-tooling gap)
- Task 19 (the proved copy semantics), task 26 (verify — the acceptance instrument)
- MASTER-PLAN Step 3 (FP-5 split: the backup API pulled forward, the CLI to task 16)
