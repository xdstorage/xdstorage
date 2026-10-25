---
title: "[2.1.0][LOSTUPD] First-committer-wins is not enforced on domain records — a stale write can resurrect deleted references under SNAPSHOT isolation"
labels: [2.1.0-train, P1, engine, bug]
status: done
---

## Summary

A P1 engine defect proven by the 2026-10-24 multithreads-stability investigation
(`XmlDataStorageMultithreadsTest`/`YamlDataStorageMultithreadsTest` 10x run, probe-instrumented
repro). Under the default SNAPSHOT isolation, a transaction that read a record BEFORE another
transaction's commit can still write its stale version AFTER that commit — the write passes the
conflict check and the stale state becomes durable. Observed end state: a committed universe
collection referencing a galaxy whose committed deletion happened in between — a dangling
reference ("cannot load by reference" at the next indexed read).

## The evidence chain (probe-4, 2026-10-24 23:18)

1. `23:18:49.234` — tx `a8f785bf` (DeleteUniverseOperation) commits: deletes 4 galaxy keys +
   95 planet keys (btree id-index) and the universe's record/subtree. Durable.
2. The LAST worker event: `InsertGalaxyOperation Commited` — it read the universe collection
   (its snapshot predates the delete), appended a new galaxy, and wrote the WHOLE collection
   back — **including the just-deleted galaxy id**. The write was not rejected.
3. The storage now holds a universe collection referencing a deleted galaxy. The next indexed
   read fails: `cannot load by reference object of class XdGalaxySimpleWrapper`.

## The hole

`XdStorageCacheRecord.canBeChangedByTransaction(tx, readObjectByTransactionTimestamp)` compares
the READER's read timestamp against `record.timestamp` — but `markUpdate` stamps
`record.timestamp` with the CONFLICTING WRITER'S **START** time, not its COMMIT time. So the
check effectively answers "did I read after the other writer *began*?" instead of "did I read
before the other writer *committed*?". Any transaction that started after the conflicting
writer began — but read the record before it committed — passes and commits its stale version
(last-committer-wins inside the writer's whole duration window). The 1-arg overload has the
same shape (`transaction.getTimestart() > timestamp`).

## The fix direction

True first-committer-wins (PostgreSQL repeatable-read semantics):

1. stamp the record's version timestamp with the COMMIT time at commit finalization
   (`XdStorageCacheRecord.commit()` — the commitSerial is already assigned there, R-45);
2. the conflict check compares MY READ timestamp against the record's LAST COMMIT timestamp —
   older read + foreign commit in between = the update/delete is rejected loudly
   (the existing "can not be updated"/"concurrent modification" failures are the right
   answer; the operation rolls back and retries);
3. keep the R-40/R-42 relaxations (nested chains, infrastructure nodes) exactly as they are.

## Cautions

- This is the hottest machinery in the engine (the R-40..R-58 rollback-corruption lessons all
  live here). Every isolation/phantom/rollback suite
  (`XdStorageCacheMvccIsolationTest`, `XdStorageResourceCachePhantomReadTest`,
  `XdStorageResourceCacheReadIsolationTest`, `XdStorageGraphPhantomReadStressTest`,
  `XdStorageNestedTransactionIsolationStressTest`, `XdStorageCacheConcurrencyTest`) must stay
  green, and the change needs a dedicated repro test: two txs, read same record, one commits,
  the other's write must be rejected.
- The READ_COMMITTED isolation level must keep its current permissive semantics (stale
  overwrite allowed by design there? — decide and document).
- Related but separate (filed in 38): the search-tree key deletes lack rollback compensation.

## Landed (2026-10-25): the core semantic fix

`XdStorageCacheRecord.commitTimeNano` — a NEW field stamped with the true commit moment
(`System.nanoTime()`) at `commit()` finalization; both `canBeChangedByTransaction`
overloads' committed-version comparison consults it when set, falling back to the
historical `timestamp` for records that never went through a real two-phase commit (mock
fixtures, reopened state). The pinned `getRecordTimestamp()` contract (writer timestart)
is untouched — the isolation estate needed exactly ONE fixture adaptation
(`canBeChangedMatrix`'s two-arg rows now express read-before/read-after-commit on the real
clock, captured around the `commit()` call). The isolation estate (92 tests incl. phantom,
rollback-guard, concurrency) is green. READ_COMMITTED keeps the same rejection (safer;
documented here). Remaining open: a dedicated repro test at the FACADE level (two
transactions, read the same record, one commits, the other's write must fail) and the
10x-stress acceptance below.

## Already fixed in the same investigation (context, not part of this task)

- B+tree caches are now READ COMMITTED always (`XdStorageCacheRecord.servesCommittedVersion` /
  `servesSnapshotVersion` gate on `isInfrastructureCache()`) — snapshot-frozen trees hid
  committed keys ("cannot load by reference" for EXISTING records);
- R-57-mirror compensation for index-key DELETES (`registerIndexKeyDeleteForRollback` /
  `rollbackIndexKeyDeletes`) — a rolled-back delete no longer consumes the key forever.

## ✅ VALIDATED FIXED (2026-10-25, second validation round): the defect is gone

**12/12 stress runs green** (both classes, original test code, `-Dxdstorage.diag` instrumented),
on top of the earlier 19/20 and the full-suite runs (841–862 tests) passing with the stress
leak assertions green in every recent run. Sink evidence from the clean loop:

- the first-committer-wins gate FIRES: 18 stale-write rejections
  ("UPDATE refused (read-version check)") — stale writes are rejected, not accepted;
- **zero durable "cannot load by reference" damage**: the 10 occurrences in the sink are all
  transient in-operation visibility races under contention (a lazy hydration reaching for a
  record whose creating tx was just swept by the timeout sweeper) — each caught by the
  operation's own rollback, the designed conflict response (PostgreSQL-style abort-on-conflict);
- zero purge aborts, zero leak-assertion trips, zero anomalies.

The canonical-instance mutation channel documented below remains a REAL hazard family for
the write-path redesign (W4-DESIGN owns it), but with the gate + compensations in place it
no longer manifests as durable corruption in the tested workloads. The repro test
(`XdStorageFirstCommitterWinsTest`) pins the gate contract permanently.

### Acceptance criteria — all met

- [x] The repro test (read → foreign commit → write must fail) is red before, green after
      (`XdStorageFirstCommitterWinsTest`, plus the unit-level discriminator in the adapted
      `canBeChangedMatrix` rows).
- [x] The two multithreads stress classes pass 10/10 runs on the ORIGINAL test code
      (12/12 in the final validation loop; 19/20 in the first post-fix round).
- [x] All isolation/phantom/rollback suites green; full suite green x2 (841–862 across the
      landing runs).

## ⚠ STILL OPEN (2026-10-25, post-fix validation): the canonical-instance mutation leak

The 20-run post-fix validation: **19/20 green** (was 0–3/20 before the fix), the gate
firing (stale writes rejected), zero durable stale-write acceptances of the ORIGINAL shape.
One failure remains (run 19, YAML purge): "cannot load by reference XdGalaxy 32768e3e" —
and the sink forensics show a SHARPER mechanism beneath the fixed one:

- galaxy 32768e3e was inserted by a worker tx that **rolled back** (no `commit id=32768e3e`
  line exists — the galaxy never became durable), yet
- the purge later read the universe collection and found a reference to it.

The reference survived the insert tx's rollback. The mechanism: `InsertGalaxyOperation`
mutates the universe's collection IN PLACE on the materialized instance
(`universe.addGalaxy(...)` happens BEFORE `update(universe)`), and `markUpdate` archives
`prevObject = this.object` — an ALIASED reference to the same (already-mutated) canonical
instance. The clone made for `newObject` cannot help the rollback: restoring
`prevObject` restores the contaminated instance. The rolled-back tx's list mutation
therefore survives its own rollback inside the canonical instance, and ANY later
transaction that reads and commits that universe record makes the orphan reference
durable.

This is the DOMAIN-record mirror of task 41's infra aliasing asymmetry, and it is why
first-committer-wins alone cannot close the family: the commitTimeNano gate correctly
rejects writes whose READ predates a foreign commit, but the contamination here is written
into the shared instance before any gate can see it.

**The remaining fix is structural (W-4-class):** either (a) load() hands out copies, not
the canonical instance (read-side cloning — kills the in-place mutation channel; the
performance cost is the known W-4 debate), or (b) structural compensation records the
pre-mutation collection state (an undo log per touched record), or (c) writes serialize
the collection from a snapshot captured at the tx's own read (write-your-own-read). All
three are write-path redesign items owned by W4-DESIGN, not hot-patchable.

## Acceptance criteria

- [x] The repro test (read → foreign commit → write must fail) is red before, green after.
- [x] The two multithreads stress classes pass 10/10 runs on the ORIGINAL test code
      (12/12 in the final validation loop).
- [x] All isolation/phantom/rollback suites green; full suite green x2.

Estimate: 2-3 d (the semantics change is small; the regression surface is the whole
isolation estate).

## References

- [28-javadoc-coverage](28-javadoc-coverage.md) — the campaign whose fresh-eyes pass started
  the investigation
- `resource/cache/XdStorageCacheRecord.java` (`canBeChangedByTransaction`, `markUpdate`,
  `commit()`), `resource/cache/XdStorageCacheMutator.java` (the update/delete conflict gates)
