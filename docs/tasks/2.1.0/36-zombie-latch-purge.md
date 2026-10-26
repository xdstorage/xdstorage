---
title: "[2.1.0][ZOMBIELATCH] The post-pressing purge dies on zombie record latches — abandoned worker transactions hold latches past the purge's timeout"
labels: [2.1.0-train, P1, engine, testing, bug]
status: open
---

## Summary

`XdStorageMassFillAndCleanStressTest` (Сценарий 2, the release-gate stress test: 10 threads
of stepwise fill for 5 s → graceful stop → cascade purge from the universes down → clean
folder) fails **3 of 3 runs** deterministically. The purge transaction dies on the first
zombie latch:

```
XdStorageException: transaction <id> should be rolled back by timeout.
  Object with id <uuid> is locked for undefined
  at XdStorageMassFillAndCleanStressTest:177   (the purge's lazy 'planets' hydration)
```

The cleanup that is supposed to prove a clean folder cannot run while abandoned worker
transactions still hold record latches — and the purge's default 3-second timeout is far
below the latch-holder lifetime.

## Why (the analysis)

1. **The workers abandon transactions mid-scope.** The fill loop opens a transaction, then
   `catch (Throwable ignored) { /* коллизии легитимны */ }` — a failure after
   `beginTransaction` (a latch timeout under the 10-thread cross-fire, an optimistic
   conflict) is swallowed **without commit/rollback**. The transaction stays bound to the
   pool thread, holding record latches, and the worker continues to the next iteration —
   where `beginTransaction` on a thread with an already-bound tx hands back or stacks the
   zombie (the thread-binding contract, EMBEDDING § Transactions and threads).
2. **Zombies hold latches; the sweeper is lazy and async.** Timeout enforcement is lazy by
   design (EMBEDDING): a zombie is rolled only when something trips over it or the sweeper
   gets to it — the purge's fresh transaction (default 3 s timeout) trips first, waits its
   3 s on the latch, and dies with `locked for undefined` (the holder's state: an
   abandoned, never-started zombie in `undefined`).
3. **The purge itself has no latch tolerance**: one latch timeout marks it rollback-only
   (the R-31 mark path) and the whole cascade dies; the per-galaxy `catch` logs a skip but
   the poisoned transaction refuses everything afterwards, so the purge's final commit
   fails and the leak assertions never execute.
4. Note the difference from task 35: this test's fill phase ends with a **graceful**
   `executor.shutdown()` + `awaitTermination(5s)` — the zombies are not crash residue but
   **test-code hygiene** (swallowed exceptions abandoning txs) meeting an **engine
   sharp edge** (no sweep for abandoned TOP-LEVEL transactions on a pool thread; R-41's
   force-sweep covers nested txs only).

## Scope

1. **Test hygiene first (the honest half)**: every worker `catch` must end its transaction
   (`tx.rollback()` in the catch) — a stress worker may tolerate failures, never abandon
   them. Same fix in both worker loops; the fill phase then leaves no zombies by
   construction.
2. **The engine sharp edge, evaluated**: an abandoned TOP-LEVEL transaction on a pool
   thread has no force-sweep analog of R-41's nested sweep — the zombie latches until the
   lazy timeout enforcement trips. Evaluate a manager-level rule (on `beginTransaction` on
   a thread whose bound tx is past its timeout, force-sweep it first — the R-41 pattern
   generalized) with the same WARN-and-sweep shape; decide and document either way
   (EMBEDDING § Transactions).
3. **The purge hardening**: explicit long timeout on the cleanup transaction (PERF study
   §8.3's standing advice — pass 60–120 s for batch/cleanup work, not the 3 s default) +
   bounded retry-on-latch-timeout (the R-36 backoff pattern) so a transient latch does not
   kill a 10-minute cleanup.
4. **Un-disable and gate**: `@Disabled` removed; the test passes the task-23-style gate
   (10 consecutive passes); any residual flake ledgered in FLAKY.md with a rate.

## Out of scope

- Making swallowed-transaction-abandonment legal (the contract stays: always commit or
  rollback); eager timeout enforcement (a background expiry timer — the EMBEDDING-documented
  lazy model is a design decision); changing the record-latch timeout default.

## Acceptance criteria

- [ ] Worker loops always terminate their transactions (pinned by an assertion or the
      restructured loop); no zombie survives the fill phase.
- [ ] The purge transaction uses explicit long timeouts + bounded latch retry; a
      transient latch no longer kills the cascade.
- [ ] The manager-level abandoned-tx rule: decided, implemented, or rejected WITH the
      reasoning in EMBEDDING.md.
- [ ] `@Disabled` removed; 10 consecutive isolated passes; full suite green ×2.
- [ ] CHANGELOG/EMBEDDING updated; no new FLAKY rows without a rate.

Estimate: ~1–2 dev-days.

## References

- Disabling commit `b3aeacb`; the three red runs (2026-10-23) with the identical
  `locked for undefined` signature at the purge's `load(planets)`
- Task 35 (the sibling purge-robustness task — Сценарий 1's dangling-reference mode)
- R-41 (the nested force-sweep this generalizes), R-36 (the retry/backoff pattern),
  PERF study §8.3 (the explicit-timeout advice)
- [EMBEDDING.md](../../EMBEDDING.md) § Transactions and threads (the binding contract)
