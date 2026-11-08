---
title: "[2.2.0][NESTED] Nested transactions: load-safety rework (OPTIONAL) — the isolation stress test returns to the gate"
labels: [2.2.0-train, P2, engine, bug]
status: open
---

## Summary

**OPTIONAL task** (the maintainer's decision 2026-10-26): nested transactions
(`beginTransaction(parentTx, timeout)`) are declared **NOT RECOMMENDED for load-heavy use**
— the nested commit/rollback machinery is a known critical-under-load path. This task
reworks that machinery so the isolation stress test (`XdStorageNestedTransactionIsolationStressTest`,
excluded from the gate/release profiles of 2.1.0) returns to the gate green, and the
not-recommended status can be lifted (or the feature deprecated instead — a decision for
this task's end).

## Why (the historical + current evidence)

- The R-40/R-41 campaigns fixed five distinct nested-transaction defects (child lock-leak
  cascades, nested-vs-parent self-conflict, parent poisoning by nested rollback, zombie
  abandoned nested txs, ghost nodes) — each fixed under load, each a separate session.
- The remaining known sharp edges: the mid-commit poisoning path (a nested tx expiring
  while the parent commits compensates silently — task 45 documents it), the abandoned
  nested sweeps' latch cascades, and the latch timeouts under the 10-worker isolation
  storm.
- The field verdict (maintainer, 2026-10-26): the machinery is critical under load and the
  feature should not be recommended until this task lands.

## Scope

1. The design note: the failure inventory (R-40/R-41 + task 45's findings), the rework
   options (latch-free nested bookkeeping; timeout inheritance; the suspension machinery
   vs a flat-tx emulation layer), and the recommendation — **fix** or **deprecate**.
2. If FIX: implement behind a flag, the isolation stress test returns to the gate,
   10-consecutive-pass green.
3. If DEPRECATE: the API marked deprecated, EMBEDDING/GUIDE state the removal train, the
   stress test stays excluded permanently with the deprecation documented.
4. Either way: EMBEDDING/GUIDE/CHANGELOG updated; the 2.1.0 gate/profile exclusions
   reviewed.

## Out of scope

Changing flat-transaction semantics; the facade load investigation (task 13 of this train).

## Acceptance criteria

- [ ] The design note with the fix-or-deprecate recommendation.
- [ ] If FIX: the isolation stress test un-excluded from the gate, 10 consecutive passes.
- [ ] If DEPRECATE: the deprecation documented across EMBEDDING/GUIDE/CHANGELOG.
- [ ] Full suite green ×2 (BUILD SUCCESS verdict).

Estimate: ~2–4 sessions (optional — scheduled when capacity allows).

## References

- R-40/R-41 (OPTIMIZATION-PLAN.md) — the five fixed defects and the machinery
- Task 45 (2.1.0) — the mid-commit poisoning diagnosis
- [EMBEDDING.md](../../EMBEDDING.md) § Transactions (the not-recommended note)
