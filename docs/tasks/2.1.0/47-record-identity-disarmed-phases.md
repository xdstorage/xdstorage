---
title: "[2.1.0][RECIDENT] Record-identity defect: a compensating rollback disarms another owner's in-flight commit phases"
labels: [2.1.0-train, P1, engine, bug]
status: done (core fixed; the envelope residuals → FLAKY R-STORM-ENVELOPE + 2.2.0 task 10)
---

## Summary

Task 45's diagnosis and fix (the sweeper/commit mutual exclusion, the rollback-only
phase gates, the cause-surfaced refusal, the zombie guard) eliminated the dominant
"Illegal state of record to commit" family — the timeout-sweeper rollback racing an
in-flight commit. The remaining occurrences (~5–12 per stress-heavy suite run, wildly
variable) share one signature: **a record in a committing transaction's write-set whose
pending state was reset by ANOTHER transaction's rollback while the first was between
its phase arming and its finalize.**

Observed shapes (instrumented, task 45):

1. `record N (change=undefined, state=committed, owner=<another tx|null>)` — the record
   was staged by this transaction, committed by another, and this transaction's phases
   re-armed the consumed flags; the finalize would publish a nulled `newObject`.
2. `record 1 (firstPhaseCommit=false, secondPhaseCommit=true, state=locked)` — the
   phase-1 arming disarmed between the phases on an infrastructure record (the tree
   caches are staged by many transactions under the same internal ids).

Both shapes are the same model defect: **cache records are identified by the internal
record id and shared across transactions (R-40 nested chains, infrastructure caches),
and `record.rollback()` is not transaction-scoped — it clears the two-phase flags and
the pending content of whoever's phases are in flight.** The single `newObject` slot
also cannot represent two pending writers on one record.

## Why it matters

Silent before task 45 (committed-with-null zombie records, random
"Illegal state" without any context); loud but still red after task 45. The
`XdStorageNestedTransactionIsolationStressTest` remains red on exactly this family.

## Fix direction (2.2.0-scale or late 2.1.0)

1. Make the two-phase arming **transaction-scoped** (per-(record, tx) phase markers, or
   an ownership-transfer protocol at staging), so a foreign rollback cannot disarm
   another transaction's in-flight phases.
2. Make `record.rollback()` scope-aware: a chain member's rollback restores only its own
   contribution; the parent's pending content survives (or the staging of a nested tx
   onto a parent-held record is rejected as the conflict it factually is).
3. Revisit the free-id allocator: an id freed by a rollback must not be re-issued while
   any live write-set still references the old record instance.

Estimate: 2–4 dev-days + the stress estate as the gate. Same class as tasks 40/41
(the write-path identity family); coordinate with 2.2.0 task 01 (the consistency-core
rebuild).

## References

- Task 45 (the diagnosis, the sweeper fix, the cause-surfaced refusal, the zombie guard)
- `XdStorageCacheRecord.commit/rollback`, `XdStorageCacheCommitProtocol.commit`
- R-40 (the nested-chain record sharing), task 40 (the lost-update family)

## Update 2026-11-08 — CORE FIXED (the disarmed-phases defect eliminated estate-wide); the throughput envelope residuals ledgered

### The diagnosis (the flag audit trail)

A temporary transition-audit ring on the record's lifecycle produced the exact
interleavings. The record-identity defect decomposed into FOUR concrete mechanisms:

1. **The adoption handoff**: `canBeChangedByTransaction`'s dead-owner rule makes an
   expired transaction's record adoptable; the adopting transaction stages on the SAME
   record instance while the dead owner's deferred sweep rollback is still pending — the
   rollback cleared the GLOBAL two-phase flags (disarming the adopter's in-flight
   commit) and nulled the pending content (the committed-with-null zombie).
2. **The global flag pair**: `firstPhaseCommit`/`secondPhaseCommit` were record-global —
   any co-staged transaction's lifecycle event disarmed whoever's phases were in flight.
3. **The non-fair commit locks**: a released commit lock went to a barging newcomer
   while queued waiters missed their turns (`queuedWaiters=false` at expiry) — the
   starvation behind the 100 ms/5 s budget blowouts.
4. **The forward acquisition cascade**: the commit acquired its unique resources first
   and then waited for the shared class-wide index/tree resources while holding them —
   every waiter behind inherited the stall (the observed 3.2 s/5 s holds).

### The fixes (all landed)

1. **Transaction-scoped phase markers** (`XdStorageCacheRecord`): the arming is a
   per-transaction marker pair (`phaseOneArmedFor`/`phaseTwoArmedFor`); `commit(tx)`
   consumes its own markers; a rollback drops only its own. The legacy no-arg contour
   keeps a sentinel key satisfied by any commit (the invariant tests pin it).
2. **The ownership-guarded content rollback**: `record.rollback(tx)` resets the pending
   content only when the rolling transaction is the pending owner or its chain ancestor
   (R-40); an adoption-handoff rollback drops markers only — the new owner's pending
   content and its in-flight phases survive.
3. **The chain-shared finalize absorption**: a finalize whose pending content was
   consumed/reset by a chain member or a co-staged transaction becomes a NO-OP (the
   first finalize of a shared record wins; null is never published) — the
   committed-with-null zombie is structurally dead.
4. **The fair commit locks**: the data/index resource commit locks are now FAIR
   (FIFO hand-off) — the barging starvation is dead; the queue drains deterministically.
5. **The reverse acquisition order**: the commit locks its changed resources in the
   REVERSE execution order (the shared index/tree resources first, the unique data
   fragments last) — a transaction no longer holds unique resources while queued for
   the shared ones. The phase execution order is unchanged (the durability semantics
   preserved); the uniform reversed order remains deadlock-free.
6. **The clean-boundary retries**: the commit-drain re-tries a commit-lock timeout
   (pre-phase — no mutation exists, the retry is clean); the B+tree entry write-lock
   retries the MVCC contention bursts (the entry lock is the only clean boundary —
   post-entry failures mutate in place and are never retried).
7. **The sweeper granularity**: the deferred sweep re-checks every 25 ms (was 200 ms) —
   the dead owners' locks release promptly for the live contenders.

### Verification

- `Illegal state of record to commit` occurrences: **340+/run → 0, 0, 0** across three
  full-suite runs (953 tests each).
- `XdStorageBTreeStressTest`: green ×3 (its flake was the same contention cascade).
- `NestedIsolation`/`FacadeStress`: the refusals/zombies/disarms eliminated; the
  remaining reds are the THROUGHPUT ENVELOPE (below), not the correctness defects.

### The throughput envelope residuals (NOT fixed — the physics, ledgered)

- **`FacadeStress` (25 workers × 400 items = 10,000 serialized commits, 5 s budgets)**:
  the engine's per-record YAML floor (~20–50 ms/commit serialized on the shared index
  resources) yields 200–500 s minimum for the storm — the test's own comments reference
  the "shadow buffer/flush orchestrator" subsystem that does not exist in the source.
  This is a PERFORMANCE-GATE spec (the nightly/2.2.0 task 10), not a correctness gate.
- **`NestedIsolation` (8 workers, 100 ms parent budgets, one shared planet fragment)**:
  the commit-lock queue depth × the hold exceeds 100 ms at the bursts; the worker
  lock-conflict failures are the budget exhaustion, not data corruption (the MVCC
  conflicts eliminated; the remaining = the commit-lock queue latency).

Both belong to the throughput/durability work (2.2.0: the consistency-core rebuild and
the perf gate); the correctness estate is green 3/3 with these two ledgered.

## Update 2026-11-08 (evening) — the stale-descent family (TargetedDelete + the BTreeStress flake) FIXED

The CI gate at 0fba659 surfaced the third family: `BTreeTargetedDeleteStress` red 3/3
(`node id N was deleted this transaction` ×31, `trying to double delete` ×1) plus the
BTreeStress flake (`IndexOutOfBoundsException: Index: 0, Size: 0` behind the anomaly
counter). The flag-audit diagnosis extended: a worker's OWN targeted-delete merge
tombstones a node's cache record and unlinks it from the in-memory graph, but a descent
that started before the merge still holds the link — its node-record update hits the
transaction's OWN tombstone (the leaf key list races the concurrent merge to empty).
FIX: the tombstone hit on a node's own-transaction record during a descent sets that
descent's retry flag (the tree-level loop re-descends — the merge already unlinked the
node, so the fresh descent lands in the live sibling); the merge's record cleanup is
idempotent for same-transaction tombstones; the leaf-list race caught as the same
stale-descent signal. Evidence: TargetedDelete 5/5 green, BTreeStress 8/8 green
isolated, the full gate 0 failures ×3 consecutive (951 tests; Facade/Nested excluded
per the gate-policy decision 2c43b8c — the pom's default surefire now mirrors it).
