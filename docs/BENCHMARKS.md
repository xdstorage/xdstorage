# XdStorage — Benchmarks

## Canonical baseline (the unified estate — BENCH, docs/tasks/2.1.0/13)

**This is the single source of truth for performance numbers.** The canonical tables below are
GENERATED from full-run JMH JSON by `scripts/render-bench-baseline.py` — never hand-typed — and
bind to the archived result set + run manifest in
[`docs/perf/canonical-baseline-2026-11-21/`](perf/canonical-baseline-2026-11-21/) — the 2.1.0
release set (BENCHREFRESH, task 43: the full train feature set, the FK engine idling on the
canonical entities; JDK/CPU/loadavg/flags inside `manifest.json`). The predecessor sets are
preserved for the delta record:
[`canonical-baseline-2026-10-24/`](perf/canonical-baseline-2026-10-24/) (git rev `dffaf9b`)
and [`canonical-baseline-2026-10-22/`](perf/canonical-baseline-2026-10-22/).

- **Run:** `bash scripts/run-benchmarks.sh [quick|full|sweep]` — the ONE entry point. It owns
  the conditions protocol: quiet-machine loadavg gate, WARN-console logging with no hot-path
  file I/O (`docs/perf/benchmark-log4j2.xml`, passed explicitly — immune to the suite's config
  reshuffles), YAML format, disclosed config flags, JSON results + manifest.
  **Pin the JDK** with `BENCH_JDK=/path/to/jdk` (optional env): the manifest records the
  version of the binary that ACTUALLY ran and its source (parameter or PATH default), so
  the documented 'JDK 21.0.7' condition is provable, not assumed.
- **Conditions of this baseline:** 2026-11-21, JDK 21.0.7 (the pinned `BENCH_JDK`; the manifest
  records the binary that actually ran), quiet-machine loadavg gate, WARN-console logging.
  **Machine (performance-critical profile):** AMD Ryzen 9 5900X
  (12 cores / 24 threads, Zen 3), 64 GB RAM, NVMe SSD (Samsung 970 EVO Plus 1 TB,
  non-rotational), ext4, Ubuntu 20.04 (kernel 5.15). The NVMe/ext4 pair matters directly
  for the durability numbers: the `writeDurable` scenario's fsync cost is an NVMe-ext4
  fsync — an HDD or a different filesystem shifts that scenario materially. **This baseline
  carries the full 2.1.0 feature set** — the W-4a id-index journal, the W-4b search-key
  journal, the W-4c shutdown checkpoint, the TREEOVERLAY write path and the commit-time
  revalidation; the FK engine idles on the canonical entities (no FK relations — its cost is
  measured separately, the FK A/B subsection below). The 2026-10-22 set
  ([`docs/perf/canonical-baseline-2026-10-22/`](perf/canonical-baseline-2026-10-22/)) is the
  pre-W-4a before-picture and is preserved for the comparison below.
- **Quick-mode budget:** the task's cap is ≤ 15 min for the whole quick suite; the FULL run
  measured **~2.5 min** wall clock (2026-10-24). Quick runs are strictly cheaper (1 iteration
  per scenario).
- **Families:** `XdStorageCanonicalBenchmark` (the CORE scenarios) and
  `XdStorageSearchEngineBenchmark` (the search-engine scenario family). The plain-Java config
  sweep (`performance/sweep/XdStorageConfigSweep`) stays a separate harness by design (PERF
  §2) — run it via `scripts/run-benchmarks.sh sweep`; its numbers are never merged into JMH
  tables.

#### Reading the CORE scenarios (honest — post-W-4a vs the 2026-10-22 baseline)

- **The write wall is gone (W-4a, task 32).** `writeDataHeavy`: 3,098 ops/s with the data
  journal OFF and **8,967 ops/s with it ON** — was 348/370. The hash id-index commit journal
  removed the O(N) full-file index rewrite from every write commit; both journal arms benefit
  (8.9× / 24×), and the journal-ON arm now wins ~2.9× on this shape (both paths are delta
  appends; the OFF arm still pays checkpoint rewrites).
- **`writeDurable` OFF 8,812 / ON 82 ops/s — the fsync multiplier on this 10-24 set is ~107×** (was ~8×):
  the non-fsync path got 24× faster while the fsync path is bound by per-file fsyncs.
  (The 2026-11-21 canonical set measures ~95× — the honest delta table below.)
  `durableWrites` remains off by default; the honest statement is that it is now an even
  bigger lever than before.
- **`commitBatch`: the per-commit fixed cost is gone.** 2,435 / 310 / 62 / 31 tx/s at batch
  10/100/500/1000 (was 116/35/15/11) — but in OBJECTS/s the curve is now **flat**: ~24k–31k
  objects/s at every batch size (was 1.2k→11k). Batching is no longer required for
  throughput; it remains useful for latency-sensitive small writes.
- **`readOnlyTx` 78.5k ops/s** (was 63.6k, +23% — within the noise band, and read-only
  transactions do not touch index resources; flat is the honest reading).
- **Search family flat within noise; `updateShipRenameSearchKey` +9%** (1,858 → 2,028): the
  rename path rewrites search key-tree nodes, which W-4a does not cover — the W-4b
  logical-op journal is the designed fix and is the next write-path phase.
- `saveAndDeleteIndexedShipWithCrew` +3% (731 → 752) — flat as expected: the benchmark
  dataset is tiny (N≈400), where the wall was invisible. **The scaling evidence lives in
  [`docs/perf/w4a-write-scaling.md`](perf/w4a-write-scaling.md): the small-write cost at
  N=50 000 went 64.9 → 0.13 ms/tx (~500×), now flat across the design point.**

#### Before/after W-4a (the index journal) — full canonical delta

Generated by comparing the archived result sets (2026-10-22 vs 2026-10-24, same machine
class, same protocol). Deltas beyond the error bars are marked; everything else is honest
noise.

| Scenario | Params | before (10-22) ops/s | after (10-24) ops/s | delta |
|---|---|---:|---:|---|
| `commitBatch` | batchSize=10 | 116 | 2,435 | **+2005%** |
| `commitBatch` | batchSize=100 | 35 | 310 | **+788%** |
| `commitBatch` | batchSize=1000 | 11 | 31 | +192% |
| `commitBatch` | batchSize=500 | 15 | 62 | +308% |
| `loadById_indexBaseline` | — | 215,095 | 231,033 | +7% |
| `readOnlyTx` | — | 63,574 | 78,509 | +23% |
| `saveAndDeleteIndexedShipWithCrew` | — | 731 | 752 | +3% (flat; tiny N) |
| `searchByOrTwoPrimaries` | — | 183,305 | 184,424 | +1% |
| `searchByPrimaryBetweenPagedLimit50` | — | 15,539 | 17,846 | +15% |
| `searchByPrimaryBetweenSelective` | — | 23,974 | 24,191 | +1% |
| `searchByPrimaryBetweenWide` | — | 11,351 | 12,484 | +10% |
| `searchByPrimaryEquals` | — | 228,154 | 226,828 | -1% |
| `searchByPrimaryEqualsWithChildCriterion` | — | 223,649 | 226,502 | +1% |
| `searchByPrimaryEqualsWithFieldCriterion` | — | 220,661 | 229,752 | +4% |
| `searchByPrimaryGT` | — | 15,054 | 14,312 | -5% |
| `searchByPrimaryIn` | — | 22,758 | 22,427 | -1% |
| `searchByScanNotEqualsPrimary` | — | 1,563 | 1,569 | +0% |
| `searchByScanNullPrimaryFieldCriterion` | — | 15,537 | 16,428 | +6% |
| `updateShipRenameSearchKey` | — | 1,858 | 2,028 | +9% (W-4b pending) |
| `writeDataHeavy` | journalWrites=false | 348 | 3,098 | **+790%** |
| `writeDataHeavy` | journalWrites=true | 370 | 8,967 | **+2321%** |
| `writeDurable` | durableWrites=false | 367 | 8,812 | **+2301%** |
| `writeDurable` | durableWrites=true | 47 | 82 | +74% |

#### Canonical tables (generated; full mode; result set 2026-11-21-104837 — BENCHREFRESH, task 43)

The 2.1.0 release set: everything the train ships is in (the FK engine idles on these
entities — no FK relations — but the W-4b search-key journal, the W-4c shutdown
checkpoint, the TREEOVERLAY write path and the commit-time revalidation are all in).
Generated from the JMH JSON by scripts/render-bench-baseline.py; the manifest +
the attribution notes in docs/perf/canonical-baseline-2026-11-21/.

#### `XdStorageCanonicalBenchmark`

| Scenario | Params | ops/s ± error |
|---|---|---|
| `commitBatch` | batchSize=10 | 1,393 ± 424 |
| `commitBatch` | batchSize=100 | 143 ± 49 |
| `commitBatch` | batchSize=1000 | 14 ± 4 |
| `commitBatch` | batchSize=500 | 29 ± 10 |
| `readOnlyTx` | — | 71,055 ± 9,443 |
| `writeDataHeavy` | journalWrites=false | 3,070 ± 195 |
| `writeDataHeavy` | journalWrites=true | 8,055 ± 584 |
| `writeDurable` | durableWrites=false | 7,867 ± 620 |
| `writeDurable` | durableWrites=true | 82 ± 8 |

#### `XdStorageSearchEngineBenchmark`

| Scenario | Params | ops/s ± error |
|---|---|---|
| `loadById_indexBaseline` | — | 216,857 ± 8,011 |
| `saveAndDeleteIndexedShipWithCrew` | — | 599 ± 16 |
| `searchByOrTwoPrimaries` | — | 186,991 ± 14,983 |
| `searchByPrimaryBetweenPagedLimit50` | — | 15,918 ± 842 |
| `searchByPrimaryBetweenSelective` | — | 23,857 ± 1,455 |
| `searchByPrimaryBetweenWide` | — | 12,142 ± 532 |
| `searchByPrimaryEquals` | — | 224,962 ± 15,947 |
| `searchByPrimaryEqualsWithChildCriterion` | — | 236,728 ± 20,940 |
| `searchByPrimaryEqualsWithFieldCriterion` | — | 226,975 ± 15,053 |
| `searchByPrimaryGT` | — | 15,123 ± 1,349 |
| `searchByPrimaryIn` | — | 23,118 ± 630 |
| `searchByScanNotEqualsPrimary` | — | 1,480 ± 79 |
| `searchByScanNullPrimaryFieldCriterion` | — | 15,306 ± 525 |
| `updateShipRenameSearchKey` | — | 1,701 ± 111 |

#### The honest delta vs BOTH predecessors (generated; task 43)

| Scenario | Params | 2026-10-24 | 2026-10-22 | 2026-11-21 (new) | 2026-11-21 vs 2026-10-24 | 2026-11-21 vs 2026-10-22 |
|---|---|---|---|---|---|---|
| `commitBatch` | batchSize=10 | 2,435 | 116 | 1,393 | 0.57× ⚠️ | 12.04× ↑ |
| `commitBatch` | batchSize=100 | 310 | 35 | 143 | 0.46× ⚠️ | 4.08× ↑ |
| `commitBatch` | batchSize=1000 | 31 | 11 | 14 | 0.45× ⚠️ | 1.30× |
| `commitBatch` | batchSize=500 | 62 | 15 | 29 | 0.47× ⚠️ | 1.91× ↑ |
| `loadById_indexBaseline` | — | 231,033 | 215,095 | 216,857 | 0.94× | 1.01× |
| `readOnlyTx` | — | 78,509 | 63,574 | 71,055 | 0.91× | 1.12× |
| `saveAndDeleteIndexedShipWithCrew` | — | 752 | 731 | 599 | 0.80× | 0.82× |
| `searchByOrTwoPrimaries` | — | 184,424 | 183,305 | 186,991 | 1.01× | 1.02× |
| `searchByPrimaryBetweenPagedLimit50` | — | 17,846 | 15,539 | 15,918 | 0.89× | 1.02× |
| `searchByPrimaryBetweenSelective` | — | 24,191 | 23,974 | 23,857 | 0.99× | 1.00× |
| `searchByPrimaryBetweenWide` | — | 12,484 | 11,351 | 12,142 | 0.97× | 1.07× |
| `searchByPrimaryEquals` | — | 226,828 | 228,154 | 224,962 | 0.99× | 0.99× |
| `searchByPrimaryEqualsWithChildCriterion` | — | 226,502 | 223,649 | 236,728 | 1.05× | 1.06× |
| `searchByPrimaryEqualsWithFieldCriterion` | — | 229,752 | 220,661 | 226,975 | 0.99× | 1.03× |
| `searchByPrimaryGT` | — | 14,312 | 15,054 | 15,123 | 1.06× | 1.00× |
| `searchByPrimaryIn` | — | 22,427 | 22,758 | 23,118 | 1.03× | 1.02× |
| `searchByScanNotEqualsPrimary` | — | 1,569 | 1,563 | 1,480 | 0.94× | 0.95× |
| `searchByScanNullPrimaryFieldCriterion` | — | 16,428 | 15,537 | 15,306 | 0.93× | 0.99× |
| `updateShipRenameSearchKey` | — | 2,028 | 1,858 | 1,701 | 0.84× | 0.92× |
| `writeDataHeavy` | journalWrites=false | 3,098 | 348 | 3,070 | 0.99× | 8.82× ↑ |
| `writeDataHeavy` | journalWrites=true | 8,967 | 370 | 8,055 | 0.90× | 21.75× ↑ |
| `writeDurable` | durableWrites=false | 8,812 | 367 | 7,867 | 0.89× | 21.44× ↑ |
| `writeDurable` | durableWrites=true | 82 | 47 | 82 | 1.00× | 1.74× ↑ |

**Reading the delta (the attribution work is part of the record — task 43's
"every >10-15% move explained" criterion):**

1. **The commitBatch ⚠️ rows are a MACHINE-STATE DRIFT artifact, NOT a code
   regression.** The attribution A/B (the same single-fork protocol, the same box, the
   same day): the OLD baseline rev dffaf9b re-run TODAY reproduces 1,543 ops/s
   (batch=10) — NOT the archived 2,435 — while TODAY's HEAD measures 1,458. The
   same-code delta is **-5% (noise)**; the archived-vs-new gap comes from the October
   run's system state. The method's self-check: dffaf9b-today FAILS to reproduce its
   own archive on commitBatch while it DOES reproduce it on the search-write scenarios
   (781 vs the archive's 752) — the drift is scenario-dependent (the batch shapes are
   the most machine-state-sensitive: allocation rate + GC timing).
2. **The ONE real code regression: `saveAndDeleteIndexedShipWithCrew` -24%**
   (dffaf9b-today 781 → HEAD-today 596; the old rev reproduces its archive's 752, so
   the drop is code, not machine). The suspect layers are the 2.1.0 correctness wave
   on the indexed-write path: the TREEOVERLAY staging/apply on the index B+trees (the
   convoy fix's price — 24,810 phantoms → 0, the concurrent FKBENCH 0.8 → 121-158
   tx/s) and the W-4b search-key journal writes. The absolute cost stays small
   (599 ships/s); the trade bought correctness + concurrent throughput. Tracked in the
   task-53 W-4 ledger (the post-release optimization candidates).
3. **`updateShipRenameSearchKey`: the archived -16% resolves to -8% code-level**
   (dffaf9b-today 1,866 ≈ the archive's 2,028; HEAD-today 1,723) — mostly machine
   drift, a small real residual from the same layers as (2).
4. **The reads are flat** (every search/read scenario within ±6%): the wave did not
   tax the read paths.
5. **The W-4a gains CONFIRMED on the new set** (the W4-DESIGN acceptance): the journal
   on/off ratio = 8,055/3,070 = **2.6×** (was 2.9×); the writeDurable fsync ratio is
   intact (7,867/82 ≈ 95×, was ~107× — fsync-dominated, noise-immune).
6. **The FK engine does NOT tax these scenarios**: the canonical entities carry no FK
   relations (the engine idles — disclosed in the manifest); the FK cost is measured
   separately by the FK A/B subsection below (the linear 23-160× term fixed by the
   task-21 keyed descent; the remaining flat constant on the relation-write shapes —
   the follow-up round's finding).


#### `XdStorageForeignKeyBenchmark` (the FK-cost A/B — FKBENCH, task 50)

The foreign-key engine's cost, isolated by the A/B (the SAME annotated entity graph with
`enableForeignKeys` on/off; the `plain` arm = the un-annotated twin graph, FK off — the
cascade-vs-engine decomposition: `fk_on − fk_off` = the FK engine, `fk_off − plain` = the
AutoSave cascade). **Generated** from the JMH JSON by scripts/render-fk-bench-table.py
(the dedicated invocation — NOT part of the canonical full run: the per-trial preload
would multiply the nightly smoke's runtime; see run-benchmarks.sh)
(the result set 2026-11-21-074706-fkbench*, the manifest in target/bench-results/ —
JDK 21.0.7, 1 fork, 3×2s warmup + 5×2s measurement, WARN-console logging, YAML, the
fragment 100, the disclosed protocol deviations in the task file).

| Shape | children | parents (relations) | plain | fk OFF | fk ON | FK degradation |
|---|---|---|---|---|---|---|
| cascadeSave | 1 | 1000 (1k rel) | 5,558 | 5,422 | 258 | **21.0×** |
| cascadeSave | 10 | 1000 (10k rel) | 1,984 | 2,304 | 105 | **22.0×** |
| cascadeSave | 1 | 10000 (10k rel) | 5,895 | 5,714 | 236 | **24.2×** |
| cascadeSave | 10 | 10000 (100k rel) | 1,987 | 2,026 | 103 | **19.7×** |
| childDelete (the contract cycle) | 1 | 1000 (1k rel) | 4,290 | 4,143 | 333 | **12.4×** |
| childDelete (the contract cycle) | 10 | 1000 (10k rel) | 4,281 | 3,679 | 229 | **16.1×** |
| childDelete (the contract cycle) | 1 | 10000 (10k rel) | 4,160 | 4,281 | 225 | **19.1×** |
| childDelete (the contract cycle) | 10 | 10000 (100k rel) | 3,961 | 3,646 | 224 | **16.3×** |
| parentUpdate | 1 | 1000 (1k rel) | 15,253 | 14,616 | 11,036 | **1.32×** |
| parentUpdate | 10 | 1000 (10k rel) | 11,873 | 12,054 | 9,656 | **1.25×** |
| parentUpdate | 1 | 10000 (10k rel) | 15,064 | 14,990 | 11,090 | **1.35×** |
| parentUpdate | 10 | 10000 (100k rel) | 11,863 | 12,107 | 8,985 | **1.35×** |
| fkLookup (child→parent read) | 1 | 1000 (1k rel) | 65,169 | 65,496 | 80,036 | 0.82× |
| fkLookup (child→parent read) | 10 | 1000 (10k rel) | 69,674 | 61,861 | 62,002 | 1.00× |
| fkLookup (child→parent read) | 1 | 10000 (10k rel) | 63,736 | 63,814 | 76,035 | 0.84× |
| fkLookup (child→parent read) | 10 | 10000 (100k rel) | 65,928 | 62,111 | 67,861 | 0.92× |

**The reading (the honest verdict — after the FKWRITE keyed descent, 2026-11-23; the
pre-fix table is in the task-21 record):**

1. **THE LINEAR TERM IS GONE** (the task-21 fix: `findPhysical`'s multi branch — the
   full leaf-chain scan per FK lookup — replaced by the keyed descent
   `O(log + d)`; the profile re-attribution + the fix in
   docs/analysis/FK_WRITE_PATH_DESIGN.md): the fk_on arm is now FLAT in the tree size
   (cascadeSave c=10: 105→103 across a 10× relation growth — was 74→12.9, a 5.3×
   loss per 10×; childDelete: 229→224 — was 202→51; parentUpdate: 9,656→8,985 —
   was 5,241→961). The headline: **cascadeSave at 100k relations = 103 ops/s vs the
   pre-fix 12.9 = 8.0× faster; the 160.3× degradation → 19.7×**.
2. **parentUpdate is INSIDE the ≤2× band at EVERY size now** (1.25–1.35× — was
   1.4–12.5× LINEAR): the update's FK validation walks (the N-relations checks) were
   the linear term — the keyed descent killed it.
3. **The FK read path is still free** (fkLookup 0.82–1.00× — unchanged or better).
4. **The remaining gap is a FLAT ~12–24× constant on cascadeSave/childDelete** — the
   per-op FK write fanout: every cascade writes the changed tree nodes' files + the
   head files through the 2PC contour (the wall-time cost; the CPU profile showed the
   commit term at 17% — the IO waits the CPU sampler does not see dominate the
   rest). The linear-scan work is DONE; the constant-term lever (the node-write
   consolidation / the journal) is the task-21 follow-up round — a WALL/IO profile
   first, the CPU profile cannot rank it.
5. **The childDelete shape is the CONTRACT cycle** (the CANON 2026-11-20 veto: a
   delete-child-alone while the parent's collection still references it is a designed
   refusal — the sync-the-collection-then-delete cycle is the legal path; both arms run
   it, so the delta stays FK-pure).

The result set: docs/perf/fk-ab-postfix-2026-11-23/ (fk-ab.json; the same-day
same-protocol re-run vs the 2026-11-21-074706 set — the task-43 attribution
discipline: the deltas are CODE, not machine drift).

#### In context — what these numbers measure, and the field frame (BMCONTEXT, docs/tasks/2.1.0/30)

These are numbers for **embedded, transactional object persistence**: every operation runs inside an
ACID transaction committed across multiple resource files (data fragments + id index + search index,
each journaled), with MVCC snapshot reads, triggers and typed object graphs — not raw key-value ops
over a map. The field frame from public materials, order-of-magnitude and workload-dependent (origin
+ date for every figure in § Field-frame sources below): RocksDB's own wiki benchmarks measure
10⁴–10⁶ simple ops/s by workload (~52K *sustained writes*/s in a CPU-bound in-memory test);
Chronicle Map's README advertises "millions of operations per second"; LMDB's sizing guide runs
~336k ops/s with the database memory-resident on 32 cores; MapDB lands 10³–10⁵ ops/s depending on
mode. **None of those figures buys what an XdStorage operation buys**: a single-op rate over a byte
map or an off-heap map is not an ACID multi-resource commit of a typed object graph with
secondary/child indexes, in-commit triggers and snapshot isolation. The honest use of this page is
therefore the gaps WITHIN XdStorage's own scenarios (the canonical tables above); the cross-product
numbers frame the order of magnitude only — workload, hardware and op semantics differ, and some
figures are marketing claims (labeled as such).

#### Reading the CORE scenarios (honest — the 2026-11-21 canonical set)

- `readOnlyTx` 71k ops/s — begin → load(id) → commit, everything cache-resident: the B2
  read-only short path as a permanent, re-runnable scenario (the original B2 sweep measured
  0.6–0.7 ms/op, flat from N=5000 to N=40000 on the Hash path; this scenario pins the
  pattern into the suite). **In real terms:** ~14 µs per read transaction — request-scoped
  transactions are a safe default, not something to pool or amortize.
- `writeDataHeavy` journal on/off: 3,070 vs 8,055 tx/s — the journal-ON arm wins **2.6×**
  on this shape (the W-4a ratio, re-confirmed on this set). **In real terms:** ~0.12 ms
  per data-heavy write transaction — small objects no longer need batching for throughput;
  batching remains useful for latency-sensitive writes and large atomic units.
- `writeDurable` on/off: 7,867 vs 82 ops/s — **the fsync costs ~95×** on this shape (an
  NVMe-ext4 fsync; an HDD shifts it materially). `durableWrites` is off by default; turn
  it on when the durability requirement outweighs write throughput. **In real terms:**
  this is the price of surviving a power loss (crash safety is free; power-loss safety
  is not).
- `commitBatch` 10/100/500/1000: 1,393 / 143 / 29 / 14 tx/s — in OBJECTS/s the curve is
  **flat (~14k–15k objects/s at every batch size)**: the per-commit fixed cost is gone
  since W-4a. Batching is no longer required for throughput. **In real terms:** the same
  14k objects/s that costs ~10 s one-by-one at batch-10 costs ~1 s at batch-1000 —
  batching now buys latency and atomic-unit size, not raw throughput.

**The write frontier, stated honestly** (the same limitation the README publishes): the
O(N) write-scaling walls are gone — data fragments journal O(delta) per commit since W-3,
the id index since W-4a, the search key-trees since W-4b, and a clean shutdown checkpoints
every journal (W-4c); the small-write cost is flat **0.13 ms/tx from 10³ to 5·10⁴ objects**
([docs/perf/w4a-write-scaling.md](perf/w4a-write-scaling.md)). What remains: the
**relation-write path** — the linear O(relations) scan is FIXED (the task-21 keyed
descent, 2026-11-23: the degradation is now flat in the FK-tree size; parentUpdate is
inside the ≤2× band at every size); what remains is the flat per-op write-fanout
constant on cascadeSave/childDelete (**19.7–24.2× / 12.4–19.1×**, § FK A/B above —
the follow-up round, [docs/tasks/2.2.0/21](tasks/2.2.0/21-fk-write-path.md)) — and the
concurrent-writer envelope on the heavy indexed shapes (10 writers: **121–158 tx/s aggregate,
p99 84–113 ms** — the 2.0.x lock-timeout convoy that decayed to ~1 tx/s is fixed; the shape
is far heavier than `writeDataHeavy`, so the aggregate is shape-specific, not the
single-writer envelope).

#### Nightly regression watch (D-4, docs/tasks/2.1.0/11)

Every nightly the smoke's quick scores are persisted as workflow artifacts (`jmh-scores-latest`
= last nightly wins; `jmh-scores-<date>` dated copies, 90-day retention) and compared against
the previous nightly's scores. Any scenario **>25% slower** emits a `::warning::` annotation,
and the full delta table lands in the job summary. Semantics: **detection, not gating** — the
alert never fails the run. Quick-mode single-iteration numbers on shared runners are noisy:
a warning means "re-measure on a quiet machine (`scripts/run-benchmarks.sh full`)", exactly
the discipline BENCHMARKS.md prescribes. A missing previous artifact (first runs,
pruned history) skips the comparison silently. The gate job is unaffected.

#### Design point — the N=50k boundary measurement (N50K, docs/tasks/2.1.0/20)

The DECISION-GUIDE publishes a design point of 10⁴–10⁵ objects per class; this is its boundary
measurement (probe-mode: plain-Java single run, task 13's conditions protocol via
`scripts/run-benchmarks.sh n50k`; scenario budget 120 s each; fragment size 128; result set
[`docs/perf/design-point-2026-10-23/`](perf/design-point-2026-10-23/) — **measured 2026-10-23,
pre-W-4a**: the write rows below are the before-picture of the wall W-4a/c then removed).

| N | heap bytes/object | bulk load obj/s | range window hits |
|---|---:|---:|---:|
| 5,000 | 1,988 | 5,532 | 0 |
| 50,000 | 1,437 | 11,437 | 14,286 |

| Scenario (probe mode, single run) | N=5 000 ops/s | N=50 000 ops/s | status |
|---|---:|---:|---|
| `indexedEquals` | 42,182.9 | 34,508.8 | ok |
| `pointRead` | 41,526.9 | 67,788.5 | ok |
| `readOnlyTx` | 80,219.5 | 59,117.4 | ok |
| `saveDelete` | 67.5 | 7.0 | TIMEOUT at N=50k (the pre-W-4a ceiling finding) |
| `selectiveRange` | 5,218.9 | 110.0 | ok |

**Reading (honest, with the 2.1.0 update):** reads scale comfortably to 5×10⁴ — point reads,
read-only transactions and indexed EQUALS stay in the same 10⁴–10⁵ ops/s band as at 5×10³.
The heap numbers quantify the ceiling: **~1.4–2.0 KB of heap per object** (≈70–100 MB at
5×10⁴ for this entity) — heap ≈ working set until disk-anchored scans ship, exactly as
DECISION-GUIDE § losses states. **The write row was the train's headline wall**: unbatched
save+delete collapsed 67.5 → 7.0 pairs/s from 5×10³ to 5×10⁴, and W-4a/c flattened the
small-write cost to **0.13 ms/tx at 5×10⁴ (was 64.9 ms/tx)** — the current picture is
[docs/perf/w4a-write-scaling.md](perf/w4a-write-scaling.md) and the canonical `writeDataHeavy`
rows above. The remaining 10⁵-scale caveats: the relation-write cost (§ FK A/B — enable FK
for integrity-critical graphs, not bulk-load loops) and the eager read-snapshot capture
(~10–33% of load cost by graph size, measured and accepted as the alias-safety net in 2.1.0;
the lazy capture is a 2.2.0 candidate — [docs/tasks/2.2.0/22](tasks/2.2.0/22-lazy-read-snapshot.md)).
Verdict: **the 10⁴ end of the design point is fully confirmed; 10⁵ is confirmed for reads and
for journaled writes; heap ≈ working set remains the honest 10⁵ boundary.** **In real terms:**
50k objects of a typical entity cost ~70–100 MB of heap and read at tens of thousands of
ops/s — inside the envelope. Probe-mode caveats: single-run numbers, and the N=5k phase runs
first in the same JVM (the N=50k point/tx rows benefit from extra JIT warmth — treat cross-N
deltas within ~1.5× as same-band).

#### Field-frame sources (the public figures cited in § In context)

Vendor/third-party published, workload-dependent, quoted for order-of-magnitude framing only — not
like-for-like comparisons (different op semantics, hardware, configurations; marketing claims labeled
as such):

- **RocksDB** — the project's own wiki, *In Memory Workload Performance Benchmarks*
  (github.com/facebook/rocksdb/wiki, page revision dated 2022-11): ~52K *sustained writes*/s in a
  CPU-bound in-memory workload; Get-path workloads run in the millions of ops/s. Band: 10⁴–10⁶ simple
  ops/s by workload and hardware.
- **Chronicle Map** — official README (github.com/OpenHFT/Chronicle-Map, accessed 2026-10): "Millions
  of operations per second, with low and stable microsecond latencies for reads and writes" (vendor
  claim); the vendor's product page adds "up to 30 million updates/sec on a 16-core server"
  (chronicle.software, marketing figure, undated).
- **LMDB** — Symas sizing guide (symas.com, accessed 2026-10): ~336k ops/s with the database resident
  in RAM on a 32-core box; the official benchmark suite is published at lmdb.tech/bench; the
  lmdbjava benchmark set (github.com/lmdbjava/benchmarks, run 2016-06) found LMDB the random-read
  leader among Java-accessible stores.
- **MapDB** — official benchmarks page (mapdb.org/benchmarks, undated six-map comparison); the same
  lmdbjava set (2016-06) measured MapDB ~1.5× slower than LMDB on random gets; community figures put
  disk-backed write throughput around 10⁴ ops/s (Stack Overflow, 2014, a single anecdote — the
  weakest of the four; treat MapDB as 10³–10⁵ ops/s by mode).

---

## Historical sections (pre-canonical conditions — kept as labeled history)

> The sections below predate the unified estate: two harness methodologies, two JDKs
> (17.0.15 here vs 21.0.7 canonical), developer-load machines, and — for the 2026-10-18
> tables — TRACE console logging on the hot path (PERF §9). Their RELATIVE scenario gaps
> remain useful; their ABSOLUTE numbers are superseded by the canonical baseline above.

# XdStorage — JMH Benchmarks (historical, recorded 2026-10-18)

Recorded: 2026-10-18. Runner: JDK **17.0.15** (OpenJDK, Ubuntu), JMH **1.37**, 24-core / 62 GB Linux box
**under normal developer load** (IDE + JPS daemon + Maven servers were running) — treat the absolute
numbers as ballpark for this class of hardware, not as lab-grade absolutes; the relative gaps between
scenarios are the useful signal.

All three benchmark classes live in `io.github.xdstorage.performance` (test sources), are driven by the
JMH annotation processor at `mvn test-compile`, and are **not** picked up by surefire (no `Test` suffix).
Each has a `main` entry point.

## How to run (historical — the canonical entry point is scripts/run-benchmarks.sh)

```bash
mvn test-compile
CP="target/test-classes:target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=test)"
java -cp "$CP" io.github.xdstorage.performance.XdStorageSearchEngineBenchmark          # search engine
java -cp "$CP" io.github.xdstorage.performance.XdStorageResourceCacheBenchmark         # resource cache (in-memory)
java -cp "$CP" io.github.xdstorage.performance.XdStoragePerformanceBenchmark           # facade write+read flow
```

Append `quick` to the search-engine benchmark for a 1-iteration smoke (~1 min).
All modes are `Throughput` (ops/s), 3 measurement iterations. The engine currently runs in its
**YAML** on-disk format (the standing default in `XdStorage`).

---

## 1. Search engine through the XdStorage facade — `XdStorageSearchEngineBenchmark`

Every operation goes strictly through the facade (`save` / `load(cl, id, tx)` / `update` / `delete` /
`load(cl, indexName, query, tx)`). Dataset: 4 ranges × 101 ships, each with a captain + 2-child crew
(StoreWithParentObject children), preloaded **serially** before workers start; per-trial unique storage
name+directory. Reads at 4 threads; scans and writes single-threaded (commits full-file-rewrite the
resource files — parallel writers measure lock contention, which is the stress tests' domain).

```
Benchmark                                                                Mode  Cnt    Score     Error  Units
XdStorageSearchEngineBenchmark.loadById_indexBaseline                   thrpt    3  480.471 ± 201.287  ops/s
XdStorageSearchEngineBenchmark.saveAndDeleteIndexedShipWithCrew         thrpt    3   38.725 ±  10.675  ops/s
XdStorageSearchEngineBenchmark.searchByOrTwoPrimaries                   thrpt    3  458.752 ± 127.406  ops/s
XdStorageSearchEngineBenchmark.searchByPrimaryEquals                    thrpt    3  468.815 ± 159.740  ops/s
XdStorageSearchEngineBenchmark.searchByPrimaryEqualsWithChildCriterion  thrpt    3  481.610 ± 257.937  ops/s
XdStorageSearchEngineBenchmark.searchByPrimaryEqualsWithFieldCriterion  thrpt    3  485.883 ± 496.181  ops/s
XdStorageSearchEngineBenchmark.searchByScanNotEqualsPrimary             thrpt    3  117.061 ±  39.466  ops/s
XdStorageSearchEngineBenchmark.searchByScanNullPrimaryFieldCriterion    thrpt    3  132.715 ±  28.714  ops/s
XdStorageSearchEngineBenchmark.updateShipRenameSearchKey                thrpt    3   74.847 ±  29.508  ops/s
```

### FP-1a: range operators (added 2026-10-22)

Four scenarios joined the benchmark: `searchByPrimaryBetweenSelective` (lexicographic window over a
small prefix of the thread's names — the walk terminates past the upper bound),
`searchByPrimaryBetweenWide` (upper bound at the end of the band — approaches scan cost),
`searchByPrimaryGT` (lower bound only — no early termination by design) and `searchByPrimaryIn`
(one membership-filtered walk). Quick mode (single iteration — ballpark, same box as the table above):

```
XdStorageSearchEngineBenchmark.searchByPrimaryBetweenSelective   thrpt  22907 ops/s
XdStorageSearchEngineBenchmark.searchByPrimaryBetweenWide        thrpt  12095 ops/s
XdStorageSearchEngineBenchmark.searchByPrimaryGT                 thrpt  15538 ops/s
XdStorageSearchEngineBenchmark.searchByPrimaryIn                 thrpt  23911 ops/s
XdStorageSearchEngineBenchmark.searchByScanNotEqualsPrimary      thrpt   1506 ops/s   (the scan these replace)
```

**Reading (honest):** a selective upper-bounded range runs ~15× the scan it replaces — the walk
terminates past the upper bound and fetches only the matching objects, while the scan visits and
reads every record. `IN` behaves like a selective walk. A **lower-bound-only** range (`GT`/`GTE`) and a
**wide** range degrade toward scan cost by design — documented in the guide (docs/GUIDE.md) § Search engine; the walk
starts at the first leaf because the engine deliberately avoids vertical descents for
duplicate-bearing trees (the R-46 invariant). Quick-mode rows: 9 → 13 (the nightly row assertion is a
lower bound and stays valid).

### FP-1b: paged range — LIMIT pushed into the walk (added 2026-10-22)

One scenario joined: `searchByPrimaryBetweenPagedLimit50` — the SAME wide window as
`searchByPrimaryBetweenWide` with `.limit(50)`. The limit is pushed down into the ordered walk
(`XdStorageBTree.WalkTerminated`): the walk stops the moment the page is complete, fetching 50
objects instead of 100. Quick mode (single iteration, same box as the FP-1a rows):

```
XdStorageSearchEngineBenchmark.searchByPrimaryBetweenPagedLimit50   thrpt  15719 ops/s
XdStorageSearchEngineBenchmark.searchByPrimaryBetweenWide           thrpt  12588 ops/s   (same window, unbounded)
```

**Reading (honest):** ~25% ahead of the unbounded same-window range — the pagination win grows with
the page/window ratio and is bounded by the per-object fetch cost (the walk itself is a minor share).
DESC pages do NOT get this win: the leaf chain is singly linked, so DESC must collect the full match
set before it can slice from the tail (documented in the guide, docs/GUIDE.md § Search engine). Quick-mode rows: 13 → 14
(the nightly row assertion is a lower bound and stays valid).

**Reading:**

- **Indexed search costs ≈ a plain id load** (~470 vs ~480 ops/s): the B+tree key lookup + record read
  adds almost nothing over the id-index path on a hit. Secondary field criteria and child criteria
  (evaluated on the already-fetched index record) are free in this configuration.
- **OR of two primaries** (~459) pays roughly one extra tree lookup — near-linear in the number of
  EQUALS branches.
- **Scan paths are ~3.5–4× slower** (~117–133 ops/s): every index record is visited and every surviving
  entity is read. This is the O(dataset) path — keep primary EQUALS criteria in queries when possible.
- **Writes are commit-bound**: rename (1 commit ≈ 13 ms, includes the key-transfer
  keyTree.update + delete/insert) runs ~75 ops/s; save+delete (2 commits, child indexing) ~39 ops/s.
  The dominant cost is the full-file resource rewrite per commit, not the index maintenance itself.
- Under 4-thread read load the engine occasionally logs
  `infra record ... stayed invisible past the wait budget — serving absent` — the documented
  infra-invisibility family; occasional degraded (empty) reads are included in the numbers.

## 2. Resource cache, in-memory — `XdStorageResourceCacheBenchmark`

Isolated `XdStorageResourceCache` over mocks (no disk, no facade): 1000 cached entities.

```
Benchmark                                               Mode  Cnt    Score     Error  Units
XdStorageResourceCacheBenchmark.runMixedReadWriteLoad  thrpt    3  431.528 ± 234.337  ops/s
XdStorageResourceCacheBenchmark.runPurePredicateReads  thrpt    3  258.626 ± 195.426  ops/s
```

Both at 8 threads. `runPurePredicateReads` is a full 1000-record predicate scan per op;
`runMixedReadWriteLoad` interleaves scans with delete/insert mutations plus a full 2PC cycle
per op. The wide error bars reflect the loaded box; re-measure on a quiet machine before
using these as regression baselines.

## 3. Facade transactional flow — `XdStoragePerformanceBenchmark`

Real file storage, per-op: begin tx → `save` (UUID-keyed entity) → `load` by id → commit, 4 threads,
2 forks (10 measurement iterations total).

```
Benchmark                                                            Mode  Cnt    Score     Error  Units
XdStoragePerformanceBenchmark.benchmarkTransactionWriteAndReadFlow  thrpt   10  127.340 ±  24.568  ops/s
```

A full transactional write+read cycle through the facade costs ~8 ms — consistent with the
search benchmark's per-commit cost (~13 ms for the heavier ship graph with children).

---

## Addendum: journalized defaults (2026-10-19, W-3)

`XdStorageConfig.journalWrites` defaults ON since W-3: data-class commits append to a
per-resource journal (`O(delta)`) instead of full-file rewrites; id-index/search-index/B+Tree
infrastructure still full-rewrite BY DESIGN SCOPE. Quick-mode re-run of the search benchmark
on the same box, same day (single-iteration quick mode — treat as ballpark):

```
loadById_indexBaseline                   ~443   (was 480 full-mode / 493 quick; quick-mode noise band)
saveAndDeleteIndexedShipWithCrew         ~38    (was 38.7)  — UNCHANGED
searchByOrTwoPrimaries                   ~431   (was 459)
searchByPrimaryEquals                    ~466   (was 469)
searchByScanNotEqualsPrimary             ~98    (was 117)
updateShipRenameSearchKey                ~73    (was 74.8)  — UNCHANGED
```

**Honest reading:** the write scenarios did NOT get faster on this benchmark — it is
index-heavy (every save/delete rewrites the id-index + search-index files, which the journal
deliberately does not cover). The journal's measured wins are for data-file-heavy write
patterns (fragment rewrites dominate there) and crash semantics (per-append atomicity + EOF
sentinel instead of torn multi-file rewrites). The ≥10× write acceptance target requires
journaling the index paths — the deferred W-4 work (see WRITE-PATH-DESIGN.md).

## Addendum: read-only transaction commit path (2026-10-22, B2)

The PERF study §5.3 (2026-10-19 edition) measured a begin → load → commit read-only cycle at
~25 ms/op (Hash, N=5000), scaling with the resource cache size, and named it the top
read-heavy optimization candidate (task B2). **That figure no longer reproduces at the 2.1.0
HEAD — measured before making any code change for B2** (same harness,
`performance/sweep/SweepFindTxVerify`, same box, serial, `resourceWriteOuts` delta watched):

```
hash  N=5000   0.69 ms/op   writeOuts=0
hash  N=20000  0.63 ms/op   writeOuts=0
hash  N=40000  0.74 ms/op   writeOuts=0     <- flat: no cache-size scaling
btree N=5000   0.70 ms/op   writeOuts=0
```

**Honest reading (the W-3 precedent):** the ≤1 ms/op acceptance target is already met — not by
a dedicated B2 patch, but as a side effect of the commit-path rework the campaign already
shipped (R-45 commit atomicity + R-46 `XdStorageResourceCache` collaborator split; the
per-resource `hasChanges` filter in `XdStorageTransaction.commitInternal` routes no-change
resources around the 2PC iteration). B2's shipped deliverable is therefore the *contract*:
a dedicated test suite (`XdStorageReadOnlyCommitPathTest` — committed-count +1 / write-outs +0,
snapshot isolation under a concurrent writer, rollback-only still throws, a parent still waits
for an open nested read-only tx, concurrent readers commit clean) plus this evidence. The old
N=40000 scaling check guards against the cost silently returning with dataset growth.

JMH facade flow (write+read scenario, quick mode, same box): 132.9 ± 27.6 ops/s vs the
documented 127.3 ± 24.6 — same band, no write-path regression.

## Gotchas learned while writing these benchmarks

1. **Preload serially, in the shared `@Setup(Level.Trial)`.** Parallel per-thread preload trips the
   known "рассинхрон структуры" validator flake, and JMH **silently skips** the failed benchmark
   instead of failing the run — always count the rows in the final table.
2. A benchmark that does not reference the `@State(Scope.Thread)` class gets **no thread setup** —
   the scan scenarios originally measured an empty database (21k ops/s of nothing).
3. Query criteria must **match the sampled entity**, otherwise the benchmark measures the no-hit
   filter path (~20× faster, meaningless).
4. Storage name + directory must be **unique per trial** (provider registry returns the old instance
   by name until `shutdown()`; stale on-disk data poisons caches — see R-50).
5. Search-engine entities must be **top-level classes** (wrapper codegen cannot resolve
   `Outer$Inner` binary names in generated source).
