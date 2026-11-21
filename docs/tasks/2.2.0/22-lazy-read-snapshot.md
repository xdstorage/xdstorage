---
title: "[2.2.0][READSNAP] Lazy read-snapshot capture — pay the task-40 alias-safety cost only on the first mutation"
labels: [2.2.0-train, P2, engine, performance, codegen]
status: open
---

## Summary

The 2.1.0 correctness wave made every read-register clone the pre-transaction state
(`prevSnapshot` — the task-40 canonical-aliasing net). The measured cost is **~10–33%
depending on the graph size** (the WRAPLAYER A/B, task 53's ledger — the earlier ~2×
estimate was wrong), accepted for 2.1.0 as the correctness price. This task makes the
capture **lazy**: snapshot on the FIRST mutation of the record in the transaction, not at
read — read-only transactions stop paying entirely.

## Why

- Every load currently clones; read-dominated workloads (the majority of embedder
  profiles) pay for a safety net they never trigger.
- The generated wrappers already intercept field writes — the natural capture point is
  the first wrapper setter (or the mark path), which the codegen owns.
- Task 53's ledger names this the 2.2.0 candidate explicitly ("the generated-setter lazy
  capture stays in the 2.2.0 ledger").

## The known hard part (decide inside the task, with a pin)

The **collection-getter bypass**: a mutation made through a lazily-obtained collection
handle (`getChildren().add(...)`) never touches a setter — the lazy capture would miss it
and the alias channel re-opens. The options: (a) keep the EAGER capture for collection
fields only (lazy for scalars — most of the measured win, no bypass), (b) intercept the
collection getters too (the wrapper returns a recording delegate — deeper codegen), (c)
prove the bypass is unreachable through the documented API and rely on the loud WARN
family. The design round picks ONE and pins the choice with a test that fails if the
channel re-opens (the MassFill survivor shape).

## Scope

1. The design note: the chosen option above, the capture point, what happens on
   markUpdate/markDelete when no snapshot exists yet (capture-then-mark vs veto —
   current behavior is capture-lazily-at-mark which takes the ALREADY-MUTATED state and
   is the documented residue), the codegen diff shape.
2. Implementation + the alias-safety estate as the gate: MassFillSecond survivors=0 ×3,
   the resolver/reopen suites, the resolver 5/5, the diag contract pins, the full
   quick-gate ×3.
3. Benchmark before/after: the canonical read-heavy shapes + the load shapes; regenerate
   the affected BENCHMARKS rows; CHANGELOG Changed entry.

## Out of scope

The rollback restore mechanics (fillObject stays); the W-4d memory train (task 05 owns
retention/eviction); any change to the commit-time revalidation contract.

## Acceptance criteria

- [ ] The design note with the bypass decision and its pin.
- [ ] The measured before/after on the canonical shapes (the win stated honestly — if it
      is <5% on the real shapes, REJECT the change and record why: complexity must buy
      measurable wins).
- [ ] The alias-safety estate ×3 green (survivors=0); quick-gate ×3 green.
- [ ] BENCHMARKS + CHANGELOG updated.

Estimate: ~2–3 sessions (codegen + the bypass decision + verification).

## References

- Task 53's post-release ledger (the lazy-capture entry and the residue description)
- The WRAPLAYER A/B (commit `bb22d1f`) — the 10–33% measurement
- Task 40 (the aliasing family the snapshot nets against)
- EMBEDDING § Transactions and threads (the concurrency contract the capture protects)
