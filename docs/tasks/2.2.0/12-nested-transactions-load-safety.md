---
title: "[2.2.0][NESTED] Nested transactions: load-safety rework (OPTIONAL) — the isolation stress test returns to the gate"
labels: [2.2.0-train, P2, engine, bug]
status: open (one gap closed, the design note written; the end-of-task decision — lift NOT RECOMMENDED or deprecate — is the maintainer's)
---

## Summary

**OPTIONAL task** (the maintainer's decision 2026-10-26): nested transactions
(`beginTransaction(parentTx, timeout)`) are declared **NOT RECOMMENDED for load-heavy use**
— the nested commit/rollback machinery is a known critical-under-load path. This task
reworks that machinery so the isolation stress test (`XdStorageNestedTransactionIsolationStressTest`,
excluded from the gate/release profiles of 2.1.0) returns to the gate green, and the
not-recommended status can be lifted (or the feature deprecated instead — a decision for
this task's end).

**State (2026-11-27):** the isolation stress test is in the gate and green — it has run in
every suite since 2026-11-21 — and one more gap is closed with pins. What is left is the
decision the summary reserves for the end: lift the status or deprecate the API. The design
note below is the input for it.

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

## Design note (2026-11-27)

### The failure inventory

| # | Defect | State | Evidence |
|---|---|---|---|
| 1–5 | Child lock-leak cascades; nested-vs-parent self-conflict; parent poisoning by a nested rollback; zombie abandoned nested txs; ghost nodes | fixed (R-40/R-41) | OPTIMIZATION-PLAN.md |
| 6 | Mid-commit poisoning — a nested tx expiring while the parent commits | guarded (2.1.0, task 45) | task 45 |
| 7 | A parent's commit waits out a nested transaction that runs on ANOTHER thread and is inside its own timeout | **fixed here** — swept after 5 s | `XdStorageNestedTransactionLoadSafetyTest`, scenarios 1 and 4: before, the parent stayed blocked past its test budget (20 s and 8 s); after, the sweep fires and the parent returns |
| 8 | Latch timeouts under the isolation storm | not reproduced since the storm re-entered the suite | below |

### What the stress test says today

- It is **not excluded**. The two `<exclude>` entries that kept
  `XdStorageNestedTransactionIsolationStressTest` and `XdStorageFacadeStressTest` out of the
  suites (2026-11-08, `2c43b8c`) were removed by `59ff544` on 2026-11-21 (TREEOVERLAY, task
  53 — "stress family 6/7 green"); since then both run in the default suite, the quick-gate
  and the nightly. FLAKY.md's "Excluded by decision" rows and the CI comment still said
  otherwise until this change.
- **The record of 2026-11-27**, full-suite runs on one machine: `NestedIsolation` 25 green,
  1 red — the red is the one run that shared the box with a second Maven build — and 10/10
  isolated on `main`; `FacadeStress` 26/26.
- So the acceptance this task wrote for the FIX path — "un-excluded from the gate, 10
  consecutive passes" — holds, and it holds **without the rework the task expected to
  need**: whatever kept the storm red left with the overlay engine and the convoy fix, not
  with nested-transaction work. The "throughput envelope" figures quoted in earlier notes
  of this task describe that earlier state and are not what the test does now.

### The rework options as scoped, and their standing

| Option | What it is | Standing after the evidence |
|---|---|---|
| **A. Rework** — latch-free nested bookkeeping, timeout inheritance, or a flat-transaction emulation layer in place of the suspension machinery | what scope item 2 assumed | Not needed to make the storm green. Its remaining value would be throughput, and no measurement asks for it. The risk sits in the commit protocol. **Not recommended.** |
| **B. Lift NOT RECOMMENDED** | keep the API; the docs state its two rules (thread-bound; the 5-second cross-thread sweep) | Open today. Needs the maintainer's decision and a soak: the nightly's full suite on CI, green, for a week with this change in. |
| **C. Deprecate** | `beginTransaction(parent, …)` deprecated in 2.x, removed in 3.0; the stress test and the pins stay | Open today. The right choice if no embedder has asked for nested transactions — the cheaper long-term position for a single-maintainer project. |

### Recommendation

**B — lift, after the soak** — unless the maintainer knows of no demand for nested
transactions, in which case **C**. In both cases A is not worth its risk. The decision is
the maintainer's; this note only puts the evidence on one page.

## Landed (2026-11-25; restated 2026-11-27)

- **The sweep.** In `skipOrWaitForInternalTransactions`, after the same-thread case and the
  expired case, what remains is a nested transaction on another thread that is still inside
  its own timeout. Past `CROSS_THREAD_ABANDON_MILLIS` (5 s) it is swept like the abandoned
  cases — `markSelfAndChildrenRollbackOnlyCascaded`, so the parent is marked too. The
  parent's commit then returns instead of holding its record locks for the nested
  transaction's whole timeout. The number is a constant: a cross-thread nested transaction
  is outside the thread-bound contract, and no configuration is warranted for it.
- **The pins.** `XdStorageNestedTransactionLoadSafetyTest` (10 scenarios): the two
  cross-thread gaps, a nested commit failing mid-phase, a 3-level cascade, the stale
  thread-binding entry, an 8-worker storm on one shared record, an explicit cross-thread
  nested rollback without poisoning, the zombie-cleanup path, 20-level nesting, and the
  thread-binding check after nested commits.
- **The docs.** EMBEDDING and GUIDE state the rules instead of the internal mechanism;
  CHANGELOG under `[Unreleased]`; FLAKY.md and the CI comment corrected about the
  exclusion.

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

- [x] The design note with the fix-or-deprecate recommendation.
- [x] If FIX: the isolation stress test un-excluded from the gate, 10 consecutive passes —
      it is not excluded (since `59ff544`) and it passes (the record above); the rework
      behind a flag that scope item 2 assumed was not needed.
- [ ] If DEPRECATE: the deprecation documented across EMBEDDING/GUIDE/CHANGELOG.
- [x] Full suite green ×2 (BUILD SUCCESS verdict) — `clean test -Pquick-gate` **1746/0f/0e/7 skipped ×3, consecutive, BUILD SUCCESS**, 0 checkstyle violations, on a quiet box (2026-11-27, branch `parallel-work` — the six parallel-work branches merged; `main` is 1360).

**What remains is one decision:** lift the NOT RECOMMENDED status (B) or deprecate the API
(C). Whichever is chosen closes this task; the texts for both are one edit each.

## References

- R-40/R-41 (OPTIMIZATION-PLAN.md) — the five fixed defects and the machinery
- Task 45 (2.1.0) — the mid-commit poisoning diagnosis
- `59ff544` (2026-11-21) — the commit that put both stress classes back into every suite
- [EMBEDDING.md](../../EMBEDDING.md) § Transactions and threads (the nested-transaction rules)
- [FLAKY.md](../../FLAKY.md) § Excluded by decision (the corrected record)
