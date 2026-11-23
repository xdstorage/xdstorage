---
title: "[2.2.0][VISBUDGET] The infra-visibility wait redesign: from poll-and-deadline to commit-notification (and the config surface)"
labels: [2.2.0-train, P2, engine, design]
status: open
---

## Summary

The interim hotfix (2.1.2/2.2.0) raised the transaction default to 15 s and made the
infra-visibility wait follow the transaction's own timeout — the first-transaction
codegen no longer trips "serving absent". But the MECHANISM remains a 5 ms
poll-and-deadline loop: a reader blocks on a wall-clock budget while the foreign
transaction holds the lazy infra record invisibly-uncommitted. This task redesigns the
mechanism: the commit publish **notifies** the waiters (event-driven resolution), the
poll loop becomes the fallback only, and the budget gets a config surface.

## Why (the incident that forces the redesign)

A real user's first transaction on a cold JVM pays the runtime wrapper-codegen (a javac
invocation) while holding the lazily-created infra records invisibly-uncommitted; any
concurrent reader waited ≤3 s (the old absolute cap) and then served ABSENT — a
wrong-answer-on-first-use for exactly the person evaluating the library. The budget
raise fixes the symptom; the redesign removes the wall-clock coupling:

- the wait duration should be "until the owner commits/rolls back", not "until a timer
  fires" — a commit notification resolves it in microseconds;
- the budget semantics ("whose timeout?") were implicit code — they belong in the
  config and the docs;
- the same poll-deadline pattern may exist at other infra waits (the audit item).

## Scope

1. **The design note**: the notification mechanism (the publish path signals the
   per-record/per-key waiters — a latch/condition keyed by the cache-record id), the
   fallback poll (bounded, for the crash cases where no notification ever fires), the
   budget config (`infraVisibilityWaitMillis`, default = follow the tx timeout), and
   the audit of the remaining wall-clock waits on infra paths.
2. **Implementation** behind the existing READ-COMMITTED infra contract (task 53's
   live-canonical serving is the neighbor — the design must not regress it).
3. **The pins**: the staged-commit choreography property (the task-03 MVCC harness
   pattern) extended to the infra wait (the foreign commit resolves a sleeping reader
   in < the poll interval); the cold-first-transaction scenario from the incident as a
   deterministic test.
4. The gates: the quick-gate ×3 + the concurrent-writers benchmark row unchanged
   (FKBENCH numbers must not regress — the notification must not add a publish-path
   cost).

## Out of scope

The tx-timeout default (already raised to 15 s by the hotfix — this task owns the
MECHANISM, not the number); the commit-time revalidation (LOSTUPD-2's owned surface);
the B-link tree upgrade.

## Acceptance criteria

- [ ] The design note with the notification mechanism and the fallback policy.
- [ ] The cold-first-transaction deterministic test green (the incident pinned).
- [ ] The staged-commit choreography property green (the notification resolves a
      sleeping reader faster than the poll fallback).
- [ ] The FKBENCH concurrent-writers row unchanged (no publish-path regression).
- [ ] The quick-gate ×3 green (BUILD SUCCESS verdicts).

Estimate: ~1–2 sessions (the design half + the implementation).

## References

- `XdStorageResourceCache.waitForRecordResolution` — the poll-deadline loop (the hotfix
  comment records the incident)
- `XdStorageConfig.defaultTransactionTimeoutMillis` — the raised default (the interim)
- Task 53 — the live-canonical serving (the neighbor contract) and the eliminated
  3001 ms wait (the earlier instance of the same pattern)
- EMBEDDING § Transactions and threads — the infra READ-COMMITTED contract
