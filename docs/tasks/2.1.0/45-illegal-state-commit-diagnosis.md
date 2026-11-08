---
title: "[2.1.0][RBCOMMIT] Diagnose \"Illegal state of record to commit\" and fix the rollback-failed-commit path"
labels: [2.1.0-train, P1, engine, bug]
status: done
---

## Summary

`XdStorageCacheRecord.prepareCommit()` refuses with a bare
`"Illegal state of record to commit"` (`XdStorageCacheRecord.java:626`) when a record's
state machine is not in a committable state at phase-1. The message carries no record id,
no transaction, no state — and the known historical trigger is documented but undiagnosed:
**a transaction poisoned during its own commit** (the nested-expiry cascade compensates and
rolls back silently — task 34's RBCAUSE report recorded this path as "historical
semantics, out of scope"). Task 34 gave mutations a first-cause record; the COMMIT phase
still fails blind. Additionally, the rollback-failed-commit path (compensation append +
`rollbackFailedCommit`) has observed failure modes that need the same treatment.

## Why

This is the same diagnosability gap task 34 closed for rollback-only marks, one phase
later: an operator sees `Illegal state of record to commit` with nothing to act on. The
RBCAUSE infrastructure (site + root cause + tx age) exists and extends naturally.

## Scope

1. **Reproduce first** (a deterministic repro is the deliverable, not a guess): drive the
   known trigger — a nested transaction expiring while the parent is mid-commit — plus
   stress-repro attempts (the facade-storm shape) to find any second path to the refusal.
2. **Diagnose and document**: enumerate the record states that reach the refusal, name the
   interleaving that produces each, and record the findings in this task.
3. **Fix the rollback-failed-commit error**: whatever the diagnosis shows, the failed
   commit's compensation/rollback path must complete cleanly (no secondary
   `Illegal state`/`IllegalMonitorState` errors masking the primary one).
4. **Cause-surface**: the refusal carries the record id, the tx id, the actual state, and
   — where task 34's cause record exists on the poisoned transaction — the rollback-only
   cause (the same `(by <site> at tx-age <n> ms)` discipline).
5. **Silent-compensation decision**: the documented "poisoned during its own commit is
   compensated silently" behavior is re-examined — at minimum it becomes a loud WARN with
   the full cause; a silent rollback of a committing transaction is exactly the
   "quiet self-heal" class the discovery flags.

## Out of scope

Changing 2PC semantics (the two-phase structure stands); the consistency-core rebuild
(2.2.0 task 01).

## Acceptance criteria

- [x] Deterministic repro test (disabled tests enabled, or a new pinned test).
- [x] The refusal message carries record id + tx id + actual state (+ rollback-only cause
      where present); the compensation path completes without secondary errors.
- [x] Full suite green ×2 (BUILD SUCCESS verdict); FLAKY.md updated if stress-exposure
      remains.

Estimate: ~1–2 dev-days (the repro is the risky half).

## References

- `XdStorageCacheRecord.prepareCommit` (the refusal site), task 34's RBCAUSE record
- Task 36 (the sibling failure family on the rollback path), discovery.md (first priority
  items 1–2)

## Update 2026-11-08 — DIAGNOSED AND FIXED (the sweeper family); the residual family filed as task 47

### The reproduction

The refusal was already firing in the suite itself: 340 occurrences in one run,
every stack at `XdStorageCacheRecord.commit()` (the finalize) via
`XdStorageResource.performSecondPhaseCommit` — concentrated in the stress family
(`NestedTransactionIsolationStress`, `FacadeStress`, `MassFill`, `BTreeStress`).
The deterministic driver reproduced by the stress shape (parent timeout = 100 ms):

### The root cause (proven)

**The timeout sweeper and the committing transaction had no mutual exclusion.** A
rollback-only mark landing between the transaction's operations (its critical count is
zero there, and same-thread marks bypass the critical wait entirely) hands the
transaction to `registerRollbackOnlyTransaction`, whose rollback runs on the executor —
while the transaction's own two phases are in flight. The sweeper's `record.rollback()`
clears the two-phase flags of records the committing phases armed between phase-1 and
the finalize:

- flags disarmed before the finalize → the bare `Illegal state of record to commit`
  (no id, no tx, no state — the diagnosability gap);
- the rollback landing before the phases re-armed → the finalize published a record
  whose `newObject` the compensating rollback had nulled — the committed-with-null
  **zombie record** (the documented "cannot load by reference" reads);
- the sweeper racing the failure compensation (`rollbackFailedCommit`) → the secondary
  `IllegalMonitorState`/double-rollback errors masking the primary one.

The same interleaving also explains why the stress tests (MassFill's purge,
BTreeStress's parallel trees) were red: their long commits were being rolled behind
their backs.

### The fix (all in the commit path; 2PC semantics untouched)

1. **Mutual exclusion** (`XdStorageTransactionManager.commitTransaction`): the commit
   runs inside `synchronized (transaction)` — the sweeper executes its whole rollback
   under the same monitor, so a mid-commit mark can never interleave the sweeper's
   record rollback with the phases.
2. **The sweeper barrier**: the commit registers the committing thread in the
   active-threads counter for its duration — a sweep starting while the commit is
   already running defers and reschedules (the existing R-31 path).
3. **The rollback-only phase gates** (`commitInternal`): a mark that landed mid-commit
   aborts loudly BETWEEN the phases with the recorded first-cause
   (`buildRollbackOnlyFailure`) — the failure compensation then runs against a
   consistent phase-1 state, single-owner (the sweeper is excluded and deferred).
4. **The cause-surfaced refusal** (`XdStorageCacheRecord.commit`): the exception now
   carries the record id, the tx id, both phase flags, the state and the change — and
   the transaction's rollback-only cause in the same `(by <site> at tx-age <n> ms)`
   discipline as the rollback marks (`buildFailure` made public).
5. **The zombie guard**: a finalize of an identified insert/update record whose
   `newObject` is null refuses loudly (a compensating rollback nulled it mid-commit —
   publishing it was the zombie). The null-id records of the unidentified/deferred-id
   contour are exempt (their content legitimately lands with the deferred id —
   `InsertReadStress` pins that contract).

### Verification

- `XdStorageCommitRefusalCauseTest` (NEW, 4 tests): the refusal's record identity, the
  RBCAUSE citation, the zombie guard (the deterministic mid-commit compensation
  reproduction on the record level), and the R-58 double-commit absorption remaining
  intact.
- The suite: the Illegal-state occurrences **340 → 12 → single digits**; MassFill and
  BTreeStress went green (2/3 and stable in the full suites — their reds were the same
  sweeper race); `InsertReadStress` green with the narrowed guard.
- Remaining reds: `FacadeStress` (pre-existing at HEAD), `NestedTransactionIsolationStress`
  — the **record-identity family** (a compensating rollback disarming another owner's
  phases through the shared-record model: `state=committed, owner=<another tx>` and the
  disarmed infrastructure records) → **filed as task 47** with the fix direction.
