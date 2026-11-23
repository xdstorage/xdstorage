---
title: "[2.2.0][COMMITMARK] Cross-resource commit marker: a crash during a commit leaves the transaction all-or-nothing"
labels: [2.2.0-train, P0, engine, durability]
status: open
---

## Summary

One commit is appended to several journals in a row — the data journal and the id-index
journal of every class it touches — and nothing records that the commit as a whole reached its
point of no return. A process killed while a commit is in flight can therefore leave the
transaction HALF applied after the restart. This task lands ONE durable point of no return
(the commit journal at the folder root) behind a config flag, so that after any crash the
transaction is either wholly there or wholly absent.

## Why (the evidence — executed, not read)

`XdStorageCrashAtomicityCharacterizationTest` (reliability) kills a child process that has
committed ONE transaction over two classes and reopens the folder with one of the
transaction's four journal appends removed — the state a crash one append earlier leaves:

| the append that did not reach the disk | rows readable after the restart |
|---|---|
| the customer's data journal | the order only |
| the customer's id-index journal | the order only |
| the order's data journal | the customer only |
| the order's id-index journal | the customer only |

Whichever single append is missing, exactly one of the two rows survives. A transaction over
ONE class is not exempt: its data and its id-index are two of these appends. The design
round — the protocol as it is, the constraints, five options with the rejected ones, the
recommendation and the order of work — is
[COMMIT_ATOMICITY_DESIGN.md](../../analysis/COMMIT_ATOMICITY_DESIGN.md). EMBEDDING § Files and
durability discloses the hole until this lands.

## Scope

1. **The fail-point seam.** An engine-internal observer called between the phase-1 appends
   of `XdStorageTransaction.commitInternal` (not public API), so a test can halt the process
   at an exact seam — today's crash harness can only kill before or after a commit.
2. **The write path behind a flag** (`atomicCommits`, default off): phase 1 appends each
   resource's batch as today, the batch trailer reading as prepared; after the LAST append
   returns, one record is appended to `<folder>/tx-commit.yaml` — the commit point; fsynced
   before phase 2 under `durableWrites`.
3. **Recovery under the flag:** the commit journal is read once per open into the set of
   committed transactions; a resource's replay applies a prepared batch only when its
   transaction is in that set, and skips-and-counts it otherwise. Batches without a trailer
   (every journal written before 2.2.0) and compensation batches replay as today.
4. **Compaction:** the commit journal is rewritten without every entry whose serial is below
   the lowest serial found in any resource journal of the folder (a scan of the batch
   trailers); journals without trailers postpone it until a checkpoint sweeps them.
5. **The neighbors:** `verify()` reports the commit journal; the EMBEDDING layout, the
   durability matrix and the compatibility table gain the file; the backup copy (task 15)
   includes it.
6. **The measurements:** FKBENCH at 1/5/10 threads and the durable shape, the flag on
   against off, the same protocol — before the default flips (soak-then-flip, the W-3 rule).

## Out of scope

A single write-ahead log for the whole folder (option D of the design — a storage-engine
redesign); multi-process writers (the single-writer contract stays); making the folder
marker move (the change is additive).

## Acceptance criteria

- [ ] The characterization test is turned around: for EVERY seam — before the first append,
      between each pair, after the last, after the commit returned — either both rows or
      none are readable. One-class transactions (data + id-index), an update over three
      classes, a delete and the rollback-only-between-phases path each have their seam test.
- [ ] With the flag off the tree behaves exactly as before (the existing journal estate
      unchanged, no new file written).
- [ ] With the flag on: a folder written by an earlier version opens and replays as before;
      the quick-gate ×3 and the nightly ×3 green (the soak).
- [ ] FKBENCH t=10 ≥ 100 tx/s and p99 < 100 ms with the flag on; the durable shape's
      regression stated as a number (target: within 10%).
- [ ] `verify()` and the EMBEDDING layout / durability matrix / compatibility table carry the
      commit journal; CHANGELOG `Added` entry.

## Verification

```bash
mvn test -Dtest="XdStorageCrashAtomicityCharacterizationTest,XdStorageJournalCrashRecoveryTest,XdStorageCommitJournalWalTest"
mvn clean test          # BUILD SUCCESS verdict (never a piped grep)
```

## References

- [COMMIT_ATOMICITY_DESIGN.md](../../analysis/COMMIT_ATOMICITY_DESIGN.md) — the design round
- Task 06 (the batch trailer this builds on; the loud mid-file corruption)
- [TX_COMMIT_ROLLBACK_MODEL.md](../../analysis/TX_COMMIT_ROLLBACK_MODEL.md) § 5 (the failure
  family this closes)
- The crash harness: `XdStorageCrashCommitter`, `XdStorageCrashedStorages` (test scope)

Estimate: ~2–3 sessions + the soak window.
