---
title: "[2.2.0][CORE] Rebuild the consistency core with java.util.concurrent standard primitives + enable the disabled B+Tree tests"
labels: [2.2.0-train, P0, engine, refactor]
status: open
---

## Summary

Replace the hand-rolled spin/notify lock and latch machinery (XdStorageReadWriteLock,
CacheRecord latch) with **standard `java.util.concurrent` primitives** (ReentrantReadWriteLock,
Condition-based waiting, StampedLock where read-heavy fits), then re-enable the disabled
B+Tree/multi-tree tests: `XdStorageBTreeDuplicateInvariantTest` (multi-tree balancing and
find — the discovery's explicit item), `XdStorageCacheRecordInvariantTest`,
`BTreeIndexResourceOperationsTest`.

## Why (discovery § First priority, items 3+5)

The custom lock was built when standard primitives were believed to wedge under deep
lock chains (R-26/R-28/R-29: three rejected waiting variants). The framing changes in
2.2.0: instead of re-scheduling waiters on the SAME structure, **rebuild the consistency
core on proven primitives** — fairness, interruptibility and диагностика come free, and
the three rejected variants are evidence of what NOT to repeat, not proof that standards
fail. The disabled multi-tree tests are the acceptance targets.

## Scope

1. Design note FIRST: the lock-site inventory (RW-lock, record latch, deep locks,
   hand-over-hand), the primitive mapping, and the three R-28/R-29 rejection reasons
   addressed one by one.
2. Replace per the design; the R-42 relaxations and the R-27 infra latch-skip semantics
   must survive (their contract tests exist).
3. Enable `XdStorageBTreeDuplicateInvariantTest` + the multi-tree balancing/find fixes it
   demands; enable `XdStorageCacheRecordInvariantTest`, `BTreeIndexResourceOperationsTest`.
4. The stress families (facade storm, targeted deletes, BTreeResourceCacheIntegration)
   green on the new core — the 10-consecutive-pass gate per test.

Estimate: ~5–8 sessions (the largest task of the train). Risk: HIGH — mitigated by the
flag-gated rollout (old/new lock behind a config flag through the soak).
