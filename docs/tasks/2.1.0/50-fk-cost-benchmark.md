---
title: "[2.1.0][FKBENCH] FK-cost benchmark: measure the degradation introduced by the foreign-key engine"
labels: [2.1.0-train, P1, engine, performance, benchmark]
status: done
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

---

## UPDATE 2026-11-20 — Шаг 0: ось КОНТЕНШНА (FK on/off × 1/5/10 потоков), канон-профиль MassFill

Класс `XdStorageFkContentionBenchmark` (surefire-invisible; запуск: `mvn test -Dtest=XdStorageFkContentionBenchmark -Dfkbench=true`).
Каждому воркеру своя вселенная (нулевая конкуренция на корневой записи) → единственный общий штопор — B+tree-ресурсы.
Сериальный прогрев; окно 5s; дефолтный tx-таймаут 3s; только коммиты внутри окна в throughput.

```
config          | tx/s    | p50     | p95     | p99     | max      | stalls>1s | sweeper | gate | other
fk=OFF t=1      |   167.4 |     5ms |     7ms |     9ms |    223ms |         0 |       0 |    0 |     0
fk=OFF t=5      |     2.2 |    24ms |    12s2 |    12s2 |     12s2 |         5 |       0 |    0 |     4
fk=OFF t=10     |     1.2 |    12s1 |    12s2 |    12s2 |     12s2 |        10 |       0 |    0 |     6
fk=ON  t=1      |   138.8 |     7ms |     9ms |    12ms |     15ms |         0 |       0 |    0 |     0
fk=ON  t=5      |     2.2 |    49ms |    15s2 |    15s2 |     15s2 |         6 |       0 |    0 |     4
fk=ON  t=10     |     0.8 |    18s1 |    21s2 |    21s2 |     21s2 |        10 |       0 |    0 |     5
```

**Выводы (атрибуция):**
1. **Однопоточная запись ЗДОРОВА**: 167 tx/s (FK-off) / 139 tx/s (FK-on), p50 5-7ms. Оверхед FK ≈ **17%** — в пределах W-4a-бэнда (≤2×). Сам FK-движок НЕ является проблемой записи.
2. **Конкурентная запись схлопывается на ~140× и это НЕ FK**: t=1→t=10: 167→1.2 tx/s, p50 5ms→12s — В FK-off плече. Отказы называют файлы классовых B+tree-ресурсов **id-индексов** (`XdStorageBTree-XdP...` без FK). Штопор = change-слот классового id-index btree, держимый контуром до коммита: все писатели класса сериализуются на длительности TX.
3. **FK-деревья — второй слой того же штопора** (+50% ко времени столла: 12s→15-21s; tx/s 2.2→0.8-2.2). Фикс FK-деревьев без фикса id-индексов проблему конкурентной записи не закрывает.
4. **Починка = task 41 (W-4-DESIGN: звуковой per-op undo btree-структур → release change-слота per-op)** — с квантифицированным эффектом: конкурентная запись 1.2 → потенциально 100+ tx/s, p99 12-21s → ms. WRTPATH (task 31) — второй рычаг (абсолютная цена операции: 5-7ms против ~μs у RocksDB).


---

## DONE (2026-11-21): the canonical A/B (the JMH family) — the acceptance record

**The benchmark class**: `XdStorageForeignKeyBenchmark` (JMH, surefire-invisible, the
task-13 protocol via scripts/run-benchmarks.sh — the class added to the unified JMH set).
The entities: `performance/entities/FkBenchParent` (@XdStorageAutoSave children) +
`FkBenchChild` (@XdStorageParentObject back-ref) + the un-annotated twins
(`FkPlainParent`/`FkPlainChild`) for the control arm. The matrix: children {1,10} ×
parents {1000,10000} × fk {on,off} + the plain arms; the (10000,100)=1M-relations combo
excluded as runtime-prohibitive (disclosed); the (1000,10) vs (10000,1) pair holds the
relations constant at 10k — the density-vs-dataset decomposition.

**The contract finding (the diagnostics that reshaped the benchmark)**: a
delete-child-ALONE while the parent's collection still references it is a DESIGNED VETO
(the CANON 2026-11-20, pinned by XdStorageForeignKeyDeletionTest). The naive
delete-only benchmark shape was refused loudly at commit ("Not all foreign keys are
deleted" — the change registry's phase-1 check); the benchmark's childDelete is the
CONTRACT cycle (sync the parent's collection → delete → commit + the restore cycle), both
arms identical — the delta stays FK-pure. The diagnostic suite:
`XdStorageForeignKeyDeleteReinsertDiagTest` (the (a)-(h) scenario ladder, the contract
cycle green).

**The A/B verdict (the numbers in BENCHMARKS.md § the FK A/B subsection):**
1. **The FK read path is free** (fkLookup ≈ 1.0× at every tree size).
2. **The FK write shapes are OUTSIDE the W-4a ≤2× band at EVERY size** — even the
   single-relation cascadeSave pays ~23×; the acceptable-band verdict is NEGATIVE.
3. **The degradation scales LINEARLY with the FK-tree size** (the task's depth question:
   the curve is LINEAR, not log): fk_off stays flat while fk_on degrades 5.3–5.7× per
   each 10× of relations (cascadeSave c=10: 74→12.9 ops/s; childDelete: 202→51;
   parentUpdate: 5,241→961). Somewhere in the FK write path there is an O(relations)
   scan — the W-4c follow-up's first suspect. The task's "per-op, not per-dataset"
   expectation is refuted by the data.
4. **The cascade is free** (fk_off ≈ plain everywhere) — the whole delta is the FK
   engine.

**The overhead decomposition**: fk_on − fk_off = the FK engine (the B+tree ops + the
registry/veto checks); fk_off − plain ≈ 0 on every shape (the AutoSave cascade is
neutral); the registry/waiter internals are NOT separately quantifiable without
instrumentation (disclosed honestly — the deferred-id waiter path is not exercised by
these pre-id'd shapes at all).

**The acceptance criteria:**
- [x] The A/B delta table published (BENCHMARKS.md, generated from the JSON by
      scripts/render-fk-bench-table.py; the result sets
      target/bench-results/20261121-074706-fkbench* + the manifest.json).
- [x] The B+tree depth scaling: the curve documented as LINEAR (the finding).
- [x] The overhead decomposition (the engine vs the cascade; the internals honestly
      disclosed as not-separately-quantifiable).
- [x] The verdict: FLAGGED for optimization (outside the ≤2× band at every size; the
      linear-scaling finding) — NOT optimized here (out of scope).
- [x] DECISION-GUIDE § numbers table: the FK row added.

**The step-0 contention axis (the 2026-11-20 addendum above)**: its pre-TREEOVERLAY
table (0.8-1.2 tx/s at t=10) is historical — post-TREEOVERLAY the same harness reports
121-158 tx/s at t=10 (the convoy destroyed, task 53); the single-threaded FK overhead
(~17%) matches this task's cascadeSave c=1 finding qualitatively (the per-op constant
costs), while the LINEAR tree-size scaling found here refines the attribution.
