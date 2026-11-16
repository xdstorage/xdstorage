---
title: "[2.1.0][FKBENCH] FK-cost benchmark: measure the degradation introduced by the foreign-key engine"
labels: [2.1.0-train, P1, engine, performance, benchmark]
status: open
---

## Summary

The foreign-key engine (task 42, `enableForeignKeys=true` default ON, 2,243 lines of
new code) maintains front/reverse B+tree relation entries on every save/update/delete
of a related class. The cost has **never been measured canonically** — the baseline
numbers (writeDataHeavy 3,098/8,967, commitBatch, saveAndDelete) were recorded BEFORE
the FK engine landed and describe entities with NO FK relations. This task measures
the FK cost A/B (flag ON vs OFF) across the canonical shapes and publishes the delta.

## Why (the cost decomposition)

The FK engine adds per-mutation work proportional to the number of relations per
object:

| Operation | FK work | B+Tree ops per relation |
|---|---|---|
| save(parent) with N children | N × FK-insert (front + reverse) | 2 B+tree inserts per child |
| save(child) | FK-insert (reverse + front) | 2 B+tree inserts |
| delete(child) | FK integrity check + entry removal | 2 B+tree lookups + 2 deletes |
| update(parent link change) | old FK entries deleted, new inserted | 2 lookups + 2 deletes + 2 inserts |
| read (no FK touch) | 0 | 0 |

Plus the FK-engine's own overhead: the registry lookup, the waiter registration
(the deferred-id waiters), the proxy unwrap at entry — all O(1) per object but with
constant factors that add up under a high-throughput loop.

**The expected degradation is proportional to the FK-relation density**, not to the
dataset size: a parent with 20 children pays 40 FK B+tree inserts per save; a parent
with 0 children pays 0. The degradation is per-op, not per-dataset — the B+tree depth
grows only with the relation count, and the FK trees are per-relation (not per-dataset).

## Scope

1. **New benchmark class** `XdStorageForeignKeyBenchmark` (the canonical family, task
   13's conditions protocol — quiet-gate, WARN-console logging, manifest):
   - **@Param fkEnabled** = true | false — the A/B arm
   - **@Param childrenPerParent** = 1 | 10 | 100 — the relation density
   - **@Param datasetSize** = 1_000 | 10_000 — the FK B+tree depth scaling
   - **Entities**: `FkParent` (`@XdStorageAutoSave` on the children collection) +
     `FkChild` (`@XdStorageParentObject` on the back-reference) — the FK-bearing shape
   - **Control arm**: the same entity graph WITHOUT the annotations (FK OFF), same N
2. **Benchmark methods**:
   - `cascadeSave` — save a parent with N children via `@XdStorageAutoSave` (the
     FK engine creates 2×N B+tree entries)
   - `childDelete` — delete a child (the FK integrity check + entry removal)
   - `parentUpdate` — update the parent (FK entries checked/rebuilt for the diff)
   - `fkLookup` — the FK reverse-tree lookup (does child X have a parent?)
3. **The A/B delta**: `enableForeignKeys=true` vs `false` on the same entity graph,
   the same operation sequence — the measured degradation per scenario.
4. **The B+tree depth scaling**: N = 1k / 10k / 100k relations in the FK tree — the
   FK lookup/write cost at scale (the B+tree depth grows ~log_N, the per-op cost
   grows ~depth).
5. **The honest report**: the measured degradation per scenario, the flagged
   constant-factor costs, and the verdict — is the FK overhead within the acceptable
   band (the W-4a threshold: the ≤3× constant-factor rule) or does it need
   optimization (W-4c: the FK-tree checkpoint on shutdown).

## The A/B methodology (task 13's conditions protocol)

```bash
# FK ON (the default — enableForeignKeys=true)
mvn test -Dtest=XdStorageForeignKeyBenchmark 2>&1 | tee docs/perf/fk-benchmark-on.log
# FK OFF (the baseline)
mvn test -Dtest=XdStorageForeignKeyBenchmark -DenableForeignKeys=false 2>&1 | tee docs/perf/fk-benchmark-off.log
```

The benchmark config flag: `XdStorageConfig.builder().setEnableForeignKeys(false)` —
the entities keep their annotations in both arms (the A/B isolates the FK-engine cost
from the annotation-parsing cost).

## Out of scope

- Optimizing the FK engine (findings become issues — the fix belongs to the FK task 42
  follow-up or the W-4c follow-up)
- Micro-benchmarks of individual FK methods (the B+Tree ops are already covered by
  the existing BTree stress suite)

## Acceptance criteria

- [ ] The A/B delta table published: every scenario measured with FK ON and OFF, the
      degradation % per operation, the error bars.
- [ ] The B+tree depth scaling: the FK lookup/write cost at N = 1k / 10k / 100k — the
      curve shape (flat / linear / log) documented.
- [ ] The FK-engine overhead (the registry lookup, the waiter registration, the proxy
      unwrap) quantified separately from the B+tree ops.
- [ ] The degradation verdict: within the acceptable band (≤2× on the write shapes,
      per the W-4a rule) or flagged for optimization (W-4c or W-4b follow-up).
- [ ] The numbers land in BENCHMARKS.md § Canonical (the FK A/B subsection) and
      DECISION-GUIDE § numbers table.

Estimate: ~2–3 dev-days (the benchmark class + the entities + the A/B runs + the docs).

## References

- Task 42 (the FK feature — the design, the veto semantics, the config flag)
- docs/analysis/FOREIGN_KEYS.md — the consolidated FK subsystem document (the limitations ledger)
- [W4-DESIGN.md](../../W4-DESIGN.md) § Bottleneck ledger (the format this extends)
- Task 43 (the benchmark estate — the conditions protocol this uses)
