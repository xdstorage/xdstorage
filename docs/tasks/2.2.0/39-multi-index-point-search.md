---
title: "[2.2.0][IDXPT] Point search on a MULTI (duplicate-key) tree scans the whole leaf chain — the R-46 ban revisited with the task-32 walk machinery"
labels: [2.2.0-train, P2, engine, performance]
status: open
---

## Summary

The engine deliberately forbids vertical descents on MULTI trees (the javadoc at
`XdStorageBTree.java:1398–1399`, the ban comment at 1441 — R-46: splits may reorder
duplicates between left and right branches). The consequence: EVERY point search on a
secondary index with duplicate keys scans the whole leaf chain. The evaluation's
cited row: at N=50k the selective range query falls from 5 219 to 110 ops/s
(BENCHMARKS.md § N50k). Task 21 fixed the FK relations path (the keyed descent, the
router LEFT); the GENERIC index search still pays the chain.

## Why

- Secondary indexes with non-unique keys are the common case (status, dates, owners) —
  the search cost growing linearly with the INDEX SIZE (not the result size) is the
  worst scaling law the engine has.
- The building blocks now exist and are battle-tested: the DUPKEYS between-equal-
  separators descent (2.1.0) + the task-32 delete chain walk (deep-locked, chain-atomic
  within the walk frame). R-46's reasons can now be addressed by the same discipline
  instead of the blanket ban.

## Scope

1. The design note FIRST (the house rule): why the blanket ban existed, why the
   between-separators descent + bounded chain walk is sound against the R-46 split
   reordering (the task-32 walk's locking argument restated for reads), and what the
   epoch/walk retry (task 23) adds on the read side.
2. The implementation: the MULTI point search descends into the key's separator window
   and walks only that key's range — never the whole chain.
3. The benchmark gate: the N50k selective-range row restored toward the unique-tree
   band; the honest before/after in BENCHMARKS.md (the FKBENCH attribution method).
4. The gates: the btree estate ×3, the DUPKEYS/DUPRACE families (the descent is shared
   ground), the invariant suite, the quick-gate ×3.

## Out of scope

The B-link tree upgrade; the FK relation paths (task 21 owns them); range-query shapes
beyond the point/bounded-range case.

## Acceptance criteria

- [ ] The design note with the R-46 rebuttal recorded.
- [ ] The N50k selective-range benchmark row re-measured, the delta published.
- [ ] The DUPKEYS + DUPRACE estates ×3 green (the shared descent unchanged where it must be).
- [ ] The quick-gate ×3 green.

Estimate: ~2–3 sessions.

## References

- The external evaluation 2026-11-23, §3.3.4 (+ BENCHMARKS.md § N50k, their line refs)
- `XdStorageBTree.java:1394–1441` — the javadoc + the R-46 ban comment
- Task 32 DUPRACE (the chain-walk machinery + its locking argument)
- Task 21 FKWRITE (the keyed-descent precedent on the FK paths)
- Task 23 R56SCAN (the epoch/retry the read walk inherits)
