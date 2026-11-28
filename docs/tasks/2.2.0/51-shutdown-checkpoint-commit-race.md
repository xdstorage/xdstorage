---
title: "[2.2.0][SHUTCKPT] The shutdown checkpoint cut a commit between its phases — a commit that returned success was lost at the next open"
labels: [2.2.0-train, P0, engine, durability, bug]
status: done (2026-11-28: the data resource's shutdown checkpoint takes the commit lock first, as the index resources always did; the shutdown refuses new writes, then waits for the ones in flight; a commit after the shutdown is refused on both routes; 3 deterministic pins; quick-gate green x3, the full suite green)
---

## Summary

The checkpoint that runs OUTSIDE a commit — `checkpoint()`, `shutdown()` and
`backupTo(Path)` all reach it through `checkpointJournalOnShutdown()` — against a commit
that is BETWEEN ITS PHASES: its redo is already in the data journal, nothing is published
in the cache yet. Three defects, one hole:

1. **A committed transaction was lost on disk.** The data resource folded its journal
   under the journal's append lock only: it wrote the committed view — without that
   transaction — and deleted the journal, redo record included. The commit went on to its
   second phase and returned success; at the next open the record was gone.
2. **`shutdown()` returned under a write.** It set the closed flag and checkpointed at
   once; a commit already in flight kept writing into the folder after the call returned —
   a backup copy taken after it was not one point in time, and a reopen could meet the old
   instance's last appends.
3. **The transaction's own `commit()` was not refused after the shutdown.** Only the
   facade checked the closed flag; `transaction.commit()` ran a whole commit into the shut
   storage.

## Found by

The third Windows run of the quick-gate (`main` `8d34020`, 2026-11-28, job `windows`;
`gate` and `jdk21` green on the same commit):
`XdStorageBackupUnderLoadStressTest.theCopyHoldsWholePairsAndTheSourceReopens` — "the copy
holds 15 pair(s), but 16 had committed when backupTo returned". Not a habit of the tests
this time: the slower runner widened a window that every Linux run had missed by luck.

## Why (the mechanism)

A commit acquires the commit lock of every resource it changes, up front, and holds it
through both phases. Phase 1 appends every resource's journal; phase 2 publishes every
resource's cache. Between a resource's phase 1 and its phase 2 lie the other resources'
phase-1 appends of the same commit — I/O, so a wide window on a slow disk.

The id-index and search-index resources had always guarded their shutdown checkpoint
against it ("Busy? (live commit in progress) — leave journal for replay"). The DATA
resource had not: its checkpoint took the journal's append lock, which serializes it with
appends but not with the phase-1-to-phase-2 window, because the append lock is released
as soon as the append is done. The size-triggered checkpoint inside a commit was never at
risk — it runs on the committing thread, which holds the lock.

## The fix

- `XdStorageResource.checkpointJournalOnShutdown()` takes the resource's commit lock
  FIRST, in the order a commit takes its locks (commit, then journal), with a short
  timeout; a held lock means a live commit: the journal stays and replays at the next
  open. Called from inside this thread's own commit, it does nothing.
- `shutdown()` refuses, then waits. The transaction manager notes that the shutdown began
  (every commit is refused from that moment) and `shutdown()` waits — up to the default
  transaction timeout — until neither a commit nor a staging save/update/delete is in
  flight. The staging operations matter: the search-index journal is written while a
  transaction stages its changes, not at its commit. Each writer registers BEFORE it
  checks that the storage is open, so a shutdown that began in between either sees the
  write and waits for it, or refuses it. The thread the triggers run on counts as inside
  the commit too: a shutdown called from a trigger does not wait for the commit that is
  waiting for that trigger.
- A write still in flight when the wait runs out is cut the way a crash cuts it: the
  checkpoint leaves the busy resource's journal alone, it replays at the next open, and
  one WARN names the storage and the timeout.

## The proof — deterministic, no seam in the engine

`XdStorageShutdownCheckpointRaceTest` (reliability suite, 3 pins). The phase-1 order is
the data resource first and the id index after it, and every journal append runs under the
monitor of the journal file's canonical path (`XdStorageYamlCommitJournal.lockFor`). A
holder thread takes the id-index journal's monitor: the committing thread stops inside
that append — the data redo is on disk, phase 2 has not begun. The test does what it
wants to the storage, then lets the holder go.

| Pin | Engine before | Engine after |
|---|---|---|
| `checkpoint()` while the commit is between its phases | "the record 'in-flight' is gone after the reopen — a committed transaction was lost" | green: both records after the reopen |
| `shutdown()` while the commit is between its phases | "shutdown returned while a commit was still between its phases" | green: shutdown waits, the commit is folded into the base, both records after the reopen |
| a commit after `shutdown()` — the facade and the transaction's own `commit()` | the transaction's `commit()` was not refused: 368 bytes written into the journal of a shut storage | green: both routes refuse, nothing on disk |

**The gate.** `clean test -Pquick-gate` **1749/0f/0e/7 ×3, consecutive, BUILD SUCCESS**, and
`clean verify` once green, 0 checkstyle violations, on a quiet box. Five more in-suite runs
of the same tree: 3 green, 2 red — both in ledgered stress families and both one race in the
lock's own timeout message (a `NullPointerException` where the lock conflict belongs), older
than this task and fixed by the next commit; FLAKY.md 2026-11-28 has the reading.

## Found on the way — the lock's own timeout message

Two of the eight in-suite gate runs of this fix went red in ledgered stress families, with
one signature between them: `NullPointerException: Cannot invoke "java.lang.Thread.getName()"
because "this.writeLockThread" is null`, from the message `XdStorageReadWriteLock` builds
when a wait times out. The holder field is volatile; the message read it twice ("not null?
then `getName()`") and the holder released the lock in between. What should have been the
lock conflict — which both storms classify as backpressure — left as an NPE, which they
classify as an anomaly. Older than this task: the same NPE reddened `t35-quickgate-1` of
the top-5 wave (2026-11-24). Fixed in this branch's second commit: each message reads the
holder once. Not pinned — a check-then-act on a volatile field has no seam. The
backpressure itself stays what it was.

**The gate of the branch with both fixes.** `clean test -Pquick-gate`
**1749/0f/0e/7 ×3, consecutive, BUILD SUCCESS**, and `clean verify` once green, 0 checkstyle
violations, on a quiet box. Three runs before those, on the same code: two green, one red in
`XdStorageCrashAndPurgeStressTest` — a residue of 74, the family the top-5 wave ledgered (84
on `t08-quickgate-2`); FLAKY.md has the reading across the trees.

## Not changed

- The id-index and search-index checkpoints: already guarded, the pins above cover them
  as the second resource of the parked commit.
- The search-index journal's own model (records are appended while a transaction stages,
  before its commit) — a different contract, not touched.
- The cross-resource commit marker (task 50): a commit cut between its journal appends
  by a crash — or by a shutdown whose wait ran out — is still cut there.

## References

- Task 15 (backup: the contract and the stress test that found it), task 04 (the Windows
  leg and its three runs), FLAKY.md 2026-11-28 (why this one is not a flake).
- The shutdown checkpoint itself: task 2.1.0/48 (W-4c); the two-phase commit: RBCOMMIT
  (task 2.1.0/45).
