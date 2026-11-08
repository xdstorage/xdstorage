---
title: "[2.2.0][FACADELOAD] Facade load test: investigate the failure causes and the performance improvement paths"
labels: [2.2.0-train, P1, engine, performance]
status: open
---

## Summary

`XdStorageFacadeStressTest` (the storage-facade load test: N workers × M operations —
save/load/update/delete through the facade under contention, then the ACID empty-check)
is **excluded from the gate and release profiles of 2.1.0** (maintainer's decision
2026-10-26) and scheduled for this investigation: why it fails, and which write-path /
lock-path improvements raise the facade flow's performance.

## Why

The facade flow is the product's front door — its throughput and stability ARE the user
experience. The historical failures span the documented families (the hot-node latch
contention — R-37/B3; the retry-budget exhaustions; the validator "рассинхрон структуры"
family), and the current state has the test excluded rather than green. The 2.1.0 write
fixes (W-4a/c index journaling) changed the commit path materially; the investigation
re-baselines the facade behavior on the new write path.

## Scope

1. **Reproduce and classify**: run the facade stress in isolation ×N; classify every
   failure (latch timeout / retry budget / validator divergence / rollback-only cascade —
   task 34's diagnostics name them now) with rates.
2. **Profile the facade flow**: where the time goes per facade operation under
   contention (the W-4 methodology — per-resource phase-1 shares, lock waits, journal
   appends); the bottleneck ledger ranked by measured share.
3. **The improvement paths**, evaluated against the ledger: the B3 lock striping
   (MASTER-PLAN Step 2 — the hot-node latch family), the W-4b search-tree journal (the
   rename/update path), the retry/latch budget tuning, commit-batch ergonomics. Produce
   the prioritized fix plan with estimates.
4. **Un-exclude when green**: the test returns to the nightly (and the gate if the
   runtime budget allows) with 10 consecutive passes.

## Out of scope

Implementing the improvements in this task (each becomes its own issue); changing the
ACID empty-check contract.

## Acceptance criteria

- [ ] The failure classification with rates (every failure attributed to a named family).
- [ ] The facade-flow profile: the per-operation cost ledger ranked by measured share.
- [ ] The prioritized improvement plan with per-item estimates and linked issues.
- [ ] The test's exclusion status decided and documented (gate/nightly/RC).

Estimate: ~2–3 sessions (investigation + the plan; the fixes are separate issues).

## References

- [FLAKY.md](../../FLAKY.md) (the historical facade families), MASTER-PLAN Step 2 (B3)
- Task 45 (the commit-refusal diagnosis), W4-DESIGN (the write-path methodology)
- [EMBEDDING.md](../../EMBEDDING.md) § Transactions and threads (the facade contract)
