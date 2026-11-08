---
title: "[2.1.0][RBCOMMIT] Diagnose \"Illegal state of record to commit\" and fix the rollback-failed-commit path"
labels: [2.1.0-train, P1, engine, bug]
status: open
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

- [ ] Deterministic repro test (disabled tests enabled, or a new pinned test).
- [ ] The refusal message carries record id + tx id + actual state (+ rollback-only cause
      where present); the compensation path completes without secondary errors.
- [ ] Full suite green ×2 (BUILD SUCCESS verdict); FLAKY.md updated if stress-exposure
      remains.

Estimate: ~1–2 dev-days (the repro is the risky half).

## References

- `XdStorageCacheRecord.prepareCommit` (the refusal site), task 34's RBCAUSE record
- Task 36 (the sibling failure family on the rollback path), discovery.md (first priority
  items 1–2)
