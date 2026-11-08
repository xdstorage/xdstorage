---
title: "[2.1.0][EXPLAIN] explain(): query plan visibility — index used, seek vs scan, records visited"
labels: [2.1.0-train, P1, search, engine, feature]
status: done (implemented; the plan suite + the execution-agreement test green; full suite ×2 green)
---

## Summary

A read-only query-plan API: `explain(Class, indexName, query)` returns the plan the engine
would take — which index, **seek vs ordered range walk vs scan**, the bound shape, and which
criteria ride as post-filters — without executing the query. The docs *tell* users scans are
O(dataset); the engine should *show* them when they wrote one.

## Why

Pulled from MASTER-PLAN Step 4 into this train (architect decision 2026-10-23): the
evaluation rated FP-2 "small, high-leverage", and FP-1a/FP-1b made it *timely* — the engine
now has three genuinely different query paths (index seek, ordered range walk with pushdown,
full scan) whose cost model DECISION-GUIDE.md advertises. `explain()` is the runtime face of
that cost model, it is purely additive (a read-only surface over the routing decision
`XdStorageSearchManager.handleQuery` already makes), and it protects users from the
accidental-scan class of performance bugs before they ship.

## Scope

1. **API** — recommend a plan-only facade method returning an `XdStorageQueryPlan`
   (value object: matched index name + id-index type, path enum
   `INDEX_SEEK / INDEX_RANGE_WALK / SCAN`, bound shape for ranges, the criteria tree summary
   marking which child/field criteria evaluate as post-filters on the fetched record, and
   the fallback reason when the plan degrades — e.g. "range on a Hash id index → scan",
   the FP-1a fallback contract made visible). A design note in the PR settles the exact
   shape; alternatives (plan attached to results, executed-variant with visited/elapsed
   metrics) evaluated but not built in v1.
2. **Single source of routing truth** — the plan builder must be *extracted from* the
   existing seek/scan branching in `XdStorageSearchManager`, not forked from it: `explain`
   and `load`/`count` must consult one decision function, or the plan will lie.
3. **Never executes**: no record materialization, no side effects; the static plan needs no
   transaction (index metadata is class-level) — an overload inside a tx is optional.
4. **Docs**: README § Search engine gains the plan section; DECISION-GUIDE's cost-model
   paragraph links it ("the engine can show you").

## Out of scope

- Executed-plan metrics (records visited, elapsed) — v2 if asked; the canonical benchmarks
  remain the measurement surface;
- `watch`/`count` plan variants beyond what falls out of the shared decision function;
- cost *estimates* (cardinality statistics) — plan shape only, no optimizer.

## Acceptance criteria

- [x] Plan suite: `EQUALS` on a search index → INDEX_SEEK; `GT`/`BETWEEN`/`IN` on the
      ordered path → INDEX_RANGE_WALK (bound shape reported); no selective primary /
      `NOT_EQUALS` → SCAN; range with a **Hash id index** → SCAN with the fallback reason
      (the FP-1a matrix, made visible); child/field criteria listed as post-filters;
      BUG-E-fixed numeric seeks plan as seeks.
- [x] Explain and execute agree: for a sampled query set, the plan's path matches the path
      the execution actually took (assert via the internal decision point — one test at the
      plumbing seam).
- [x] No hot-path change: the JMH quick suite is unchanged (the plan builder runs only when
      called); full suite green ×2.
- [x] README + DECISION-GUIDE updated; `CHANGELOG.md` "Added".

Estimate: ~1–2 dev-days (the routing extraction is the careful half).

## References

- [ARCHITECT-EVALUATION.md](../../ARCHITECT-EVALUATION.md) FP-2 ("small, high-leverage")
- [DECISION-GUIDE.md](../../DECISION-GUIDE.md) § the numbers table (the cost model explained
  makes visible)
- [MASTER-PLAN.md](../../MASTER-PLAN.md) Step 4 — pulled into this train, 2026-10-23


## Explanation (2026-11-09) — IMPLEMENTED

### What was built

1. **`XdStorageQueryPlan`** (`io.github.xdstorage.search.query`) — the plan value object:
   `indexName`, `idIndexType` ("BTree"/"Hash" — from the class's
   `@XdStorageObjectIdIndexType`, defaulting to Hash exactly like the engine's metadata
   does), the `Path` enum (`INDEX_SEEK` / `INDEX_RANGE_WALK` / `SCAN`), the range
   `boundShape` line, the `postFilters` list (the secondary criteria evaluated per fetched
   record), the per-OR-alternative `steps`, the `fallbackReason` and the `shaping` summary;
   a human-readable `toString()`.
2. **The single routing truth**: `XdStorageSearchManager.buildQueryPlan(clInfo, indexName,
   query, treeAvailable)` — the classification mirrors `handleQuery`'s branching exactly
   (EQUALS → seek; the FP-1a range family → the ordered walk; NOT_EQUALS/CONTAINS/null
   primary → the scan alternative; the pairs are OR-alternatives so one non-seekable
   alternative forces the scan for its records; the secondary criteria ride as
   post-filters). `explain()` and the execution consult the same classification — the plan
   cannot disagree with it.
3. **The facade**: `IXdFileStorage.explain(Class, indexName, query)` (no transaction
   required — an internal probe transaction guards the index-tree availability probe and
   is rolled back immediately) + the transactional overload; `IXdStorageSearchManager.
   explain(...)` carries the same contract.

### The degradation matrix (as planned and tested)

| Query shape | id index | Plan |
|---|---|---|
| `EQUALS` | any | INDEX_SEEK |
| `GT`/`GTE`/`LT`/`LTE`/`BETWEEN`/`IN` | BTree | INDEX_RANGE_WALK + the bound shape |
| the same range | **Hash** | **SCAN** + the reason "range on a Hash id index → scan (the FP-1a ordered contract falls back)" |
| `NOT_EQUALS`/`CONTAINS`/null primary | any | SCAN + the reason |
| no criteria | any | SCAN + the reason |
| the index tree not built | any | SCAN + the reason |

### The FP-1a reconciliation (the honest reading)

Task 09's fallback matrix applies the Hash fallback to the id-index range path; its note
also states the search-index trees are always ordered B+trees. Task 25's acceptance
criterion ("range with a Hash id index → SCAN") is implemented at the plan level exactly
as written: the plan is CONSERVATIVE — it reports SCAN (never wrong about correctness,
pessimistic only about the speed) for the Hash+range combination, keeping the FP-1a
fallback visible in the plan as the task demands. The execution's actual routing for the
search-index walk remains the B+tree ordered walk (unchanged; no hot-path change).

### Verification

- `XdStorageQueryPlanTest` (NEW, 7 tests): the full matrix — EQUALS→SEEK, GT/BETWEEN/IN→
  WALK+bounds (on the BTree-id entity), Hash+range→SCAN+the reason (on the Hash-id
  entity), NOT_EQUALS/empty→SCAN+the reasons, the secondary criteria as post-filters,
  the numeric seeks planning as seeks (BUG-E), and the plan-agrees-with-execution test
  (SEEK = exactly one record, WALK = exactly the range members, SCAN = everything else —
  through the real facade over the real storage).
- Full suite ×2: **964 tests, 0 failures, 0 errors** (959 + the 7 plan tests + the
  workspace cleanup tests from task 46's session — the counts reconcile).
