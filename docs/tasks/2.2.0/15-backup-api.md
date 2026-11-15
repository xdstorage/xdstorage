---
title: "[2.2.0][BACKUP] backupTo(Path) + restore: the programmatic backup API (FP-5 backup core pulled forward)"
labels: [2.2.0-train, P0, engine, feature]
status: open
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

- [ ] `backupTo` under concurrent write load: the copy opens, `verify()` verdict OK, and
      the content equals a consistent snapshot (pinned by a stress test).
- [ ] The copy opens under a new storage name and answers full queries (the task-19
      assertion set, automated).
- [ ] EMBEDDING.md + GUIDE backup sections updated; CHANGELOG "Added".
- [ ] Full suite green ×2 (BUILD SUCCESS verdict).

Estimate: ~2–3 sessions (the consistency contract is the design half).

## References

- PRODUCT_REVIEW.md §4.1 (the demand), §3.2 (the agent-tooling gap)
- Task 19 (the proved copy semantics), task 26 (verify — the acceptance instrument)
- MASTER-PLAN Step 3 (FP-5 split: the backup API pulled forward, the CLI to task 16)
