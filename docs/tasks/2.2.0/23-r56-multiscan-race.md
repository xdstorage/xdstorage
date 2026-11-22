---
title: "[2.2.0][R56SCAN] Multi-scan race: version-validated walk retry — intermittent wrong EMPTY results under concurrent writes"
labels: [2.2.0-train, P1, engine, bug, testing]
status: open
---

## Summary

The B+tree hand-over-hand chain walk releases the tree read latch at the first leaf; a
**split landing in the prewarm window** (the firstLeaf capture → the prewarm → the walk)
moves keys to freshly created leaves while the walk traverses the STALE chain → the
intermittent **wrong EMPTY query results** (the "phantoms" family). This is the nearest
remaining "silent wrong answer" defect: no corruption, no crash — a concurrent reader
just misses data that a single-threaded reader sees. Historical evidence: the stash-
verified HEAD baseline measured **24,810 phantom reads** in one BTreeStress run; on the
2.1.0 overlay engine the same run measures **0** — but 0 in a probabilistic run means
the race is masked, not fixed. This task designs the deterministic repro and lands the
proper fix: the **version-validated retry with a resettable viewer** (or the snapshot-
leaf chain — the design round decides).

## Why

- Silent wrong READ results are the worst observable class for an adopter: no error, no
  ledger row — just a query that sometimes returns less than it should under load.
- The latch-held-walk experiment (holding the read latch for the whole walk) was tried
  and REVERTED with evidence: RW-latch writer starvation + the node-latch semantics made
  it worse (27k phantoms in one run). **Do not re-attempt that blind** — the F-2 lesson
  applies; the rejected experiment is recorded in task 53's ledger.
- The concurrency program (task 53) removed the corruption vector; this task removes the
  remaining read-side race so "correct under concurrency" is a construction, not a run
  outcome.

## Scope

1. **The design round first** (the house rule): (a) the deterministic repro — a staged
   split-in-walk harness (the walk forced to observe a mid-walk split; the current
   BTreeStress family is probabilistic and cannot gate); (b) the fix design: the
   version-validated retry with a resettable viewer vs the snapshot-leaf chain — pick
   one, record the rejected option with reasons; (c) the interaction audit with the
   overlay (the task-53 staging changes what "stale" means at the walk) and with the
   F-2 frozen-inventory rules (no wait-rescheduling re-attempts).
2. **Implementation** on the walk/viewer path (the id-index and the FK trees share the
   walk machinery — both covered).
3. **The gate**: the deterministic repro pin (fails on the pre-fix code — prove it red-
   before); BTreeStress ×3 + the nightly watch unchanged; the property-tests suite
   (task 03) gains the concurrent walk/split interleaving property; the full quick-gate ×3.
4. **Out of scope**: the B-link tree upgrade (a separate design-gated item — MASTER-PLAN
   item 20); the overlay staging internals (task 53's owned surface).

## Acceptance criteria

- [ ] The deterministic repro exists, is red-before-proven on the pre-fix code, and is
      green on the fix (no probabilistic gating).
- [ ] The design note: the chosen mechanism, the rejected option with reasons, the
      overlay-interaction audit.
- [ ] BTreeStress ×3 + the nightly ×3 clean; the quick-gate ×3 green (BUILD SUCCESS
      verdicts).
- [ ] The property test for walk/split interleavings lands with task 03's suite.
- [ ] FLAKY.md's BTreeStress note updated (the race is CLOSED, not masked).

Estimate: ~2–3 sessions (the repro design is the hard half).

## References

- Task 53's ledger — the R-56 sections (the stash baseline 24810, the latch-held
  experiment and its revert, the "the proper fix" note)
- [FLAKY.md](../../FLAKY.md) — the BTreeStress row (RESOLVED-masked note) and the
  BTreeResourceCacheIntegration watch row
- MASTER-PLAN item 20 (the intake record: "folds into 01/03" superseded by this filing)
- The failed-experiment record — the F-2 rule: rejected variants are deliverables
