---
title: "[2.2.0][MEMORY] W-4d: disk-anchored scans, record-cache eviction, resource retention — the memory train"
labels: [2.2.0-train, P0, engine, performance]
status: open
---

## Summary

The memory road map in one task-cluster (W4-DESIGN § W-4d + the discovery's Memory items):
**disk-anchored scans** (scans iterate records from base+journal, not the cache),
**record-cache eviction** (LRU over committed/unlocked/non-infra records — the F-3 thaw),
**resource retention during transactions** (hold the data resource only while transactions
work with it), and the **off-heap vs on-heap decision** (the OPTIMIZATION-PROPSAL-01 input
recorded in W4-DESIGN). Removes the heap ≈ DB ceiling (~1.4–2 KB/object measured).

## Scope

1. The W-4d design round (mandatory — persisted-state semantics): the eviction policy, the
   disk-anchored scan path, the retention contract, and the off-heap-vs-LRU decision with
   the release=8 constraint stated.
2. Implementation behind flags, the task-13 protocol gates (kill-9, replay equivalence,
   honest benchmarks), task 20's N50K probe as the before/after instrument.
3. The leak fix prerequisite: task 46 (2.1.0) must land first — a leak plus eviction is
   untestable.

Estimate: ~3–5 sessions + the design round.
