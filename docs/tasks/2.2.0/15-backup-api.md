---
title: "[2.2.0][BACKUP] backupTo(Path) + restore: the programmatic backup API (FP-5 backup core pulled forward)"
labels: [2.2.0-train, P0, engine, feature]
status: done (backupTo closes the storage and writes a copy that stands on its own; the 2026-11-24 version locked the engine and its copy still read the original — both gone)
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
      the content equals a consistent snapshot (pinned by a stress test).
      `XdStorageBackupUnderLoadStressTest` — four threads commit pairs (two records in one
      transaction) until the call closes the storage under them; in the copy and in the
      reopened source every pair is whole and every pair committed before the call returned
      is present; `verify` is not CORRUPT; the source saves and loads afterwards. The
      2026-11-24 test of this name had no concurrency in it and let the call's failure
      through (see below).
- [x] The copy opens under a new storage name and answers full queries (the task-19
      assertion set, automated). `XdStorageBackupToTest.theCopyAnswersWhenTheSourceIsGone` —
      every policy and index kind, **with the source folder moved away first**, and no file
      of the copy naming the source.
- [x] EMBEDDING.md + CHANGELOG backup sections updated; CHANGELOG "Added".
- [x] Full suite green ×2 (BUILD SUCCESS verdict) — `clean test -Pquick-gate` **1746/0f/0e/7 skipped ×3, consecutive, BUILD SUCCESS**, 0 checkstyle violations, on a quiet box (2026-11-27, branch `parallel-work` — the six parallel-work branches merged; `main` is 1360).

Estimate: ~2–3 sessions (the consistency contract is the design half).

## Decision 1 — the consistency contract: a quiesced copy

| Alternative | Problem |
|---|---|
| Live-read while open (no shutdown) | committed fragments may still be in the write-back buffer; the copy would miss them — a partial snapshot, not a point-in-time snapshot |
| Just `shutdown()`, copy | no guarantee the last commit was flushed to disk; the copy could be stale |
| **close, then copy** | the shutdown checkpoint moves every committed change into the base files; after the close nothing writes to the folder, so the copy is one point in time |

The contract costs the application its open storage: the call closes it, and the caller
reopens the folder to keep working. A commit in flight when the call lands is cut between
its journal appends — the state a crash at that instant leaves, torn tails discarded at
the next open, with the same limits for a commit spanning several classes (task 50).

## Decision 2 — the copy names its own files

A storage folder is not self-contained by construction: the id index stores the PATH of
every record's file — the folder path exactly as the storage was opened, absolute or
working-directory-relative. A copy of the folder therefore points into the ORIGINAL.
Measured on the 2026-11-24 version: with the source moved away, the copy answered `null`
for records that sat in its own `data/`; `verify` said OK; the tests passed only because
the source was next to the copy.

`backupTo` now rewrites that prefix in every file of the copy to the copy's own path
(`FilesCopy`): the prefix is replaced only where a path is written — after a quote or the
end of an XML tag, before a path separator — in each of the three spellings a path takes in
the files (plain, single-quoted YAML with the apostrophe doubled, XML with its entities).
The same fact belongs in the operator's contract: a copy made by hand is tied to the
original's path (EMBEDDING § Backup and restore says so now).

## Decision 3 — no lock around `checkpoint()`

The 2026-11-24 version guarded `checkpoint()` with a new global lock that every
`beginTransaction` had to pass, and waited in it until no operation of any transaction was
in flight (`drain()`). Two things were wrong with it:

- **It deadlocked.** The id generators open a transaction INSIDE a save, which the facade
  counts as an operation in flight. So: thread A saves a record with a generated id;
  thread B calls `checkpoint()` — or `backupTo`, or anything that closes the storage — and
  holds the lock, waiting for A's operation; A's save asks for the lock to begin its
  transaction. Reproduced with one save racing one `checkpoint()` per iteration: 5 runs of
  5 deadlocked (always at iteration 1300 of a fixed sequence); `main`: 5 × 2000 iterations,
  no hang; this version: 5 × 2000, no hang.
- **It did not do what it claimed.** The counter it waited on counts parallel operations
  in flight, not open transactions: `checkpoint()` returned in 2 ms while another thread
  held an open, idle transaction.

Both are gone: `checkpoint()`, the transaction manager and the services locator are
`main`'s again. Pinned: `aTransactionBeginsWhileAnotherThreadCheckpoints`.

## The destination

A new or an empty directory, outside the storage folder (a destination inside it would be
copied into itself). One the call created is removed again when the copy fails. Refusals
leave the storage open.

## The pins

`XdStorageBackupToTest` (8): the copy answers every query with the source moved away; a
relative source folder; the lock file not copied; the call closes the source; a destination
inside the folder, a non-empty destination, a shut-down storage — refused; a transaction
begins while another thread checkpoints. `XdStorageBackupUnderLoadStressTest` (1, the
concurrency criterion). `XdStorageBackupRestoreWalkthroughTest` (task 19, unchanged — its
"copy by hand" proof stands for a restore next to the original; see EMBEDDING).

## Not done

- A backup that does not close the storage (an MVCC freeze-copy read while open). The
  task allowed choosing the quiesced copy; the live one stays a follow-up if demanded.
- Incremental or scheduled backups, compression, encryption; the CLI wrapper (task 16).

## References

- PRODUCT_REVIEW.md §4.1 (the demand), §3.2 (the agent-tooling gap)
- Task 19 (the proved copy semantics), task 26 (verify — the acceptance instrument)
- MASTER-PLAN Step 3 (FP-5 split: the backup API pulled forward, the CLI to task 16)
