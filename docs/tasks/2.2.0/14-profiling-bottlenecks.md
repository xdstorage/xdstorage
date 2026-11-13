---
title: "[2.2.0][PROFILE] Engine-wide profiling and bottleneck ledger — measured evidence for the 2.2.0 priorities"
labels: [2.2.0-train, P1, engine, performance]
status: open
---

## Summary

Profile the whole engine under the canonical workloads and produce the **bottleneck
ledger ranked by measured share** — the evidence base for the 2.2.0 priorities (the
consistency-core rebuild task 01, the W-4d memory train task 05, B3 lock contention, the
binary-format gate task 33). The write path was profiled in task 31 (W-4a/c shipped from
it); this task extends the discipline to **everything else**: reads (cold/warm), search
(range walks, shaping, scans), lock behavior under contention, memory/GC, wrapper codegen
and FK maintenance overhead.

## Why (architect framing)

Every big 2.2.0 decision currently rests on indirect evidence: the lock contention is
known only from stress-test failure messages (R-37/B3 families — "retry budget exhausted",
"locked for update" counts), the memory ceiling from a static estimate (~1.4–2 KB/object,
task 20), the serialization share from a study admission (PERF §9). The house rule for
2.2.0-class work is **measured, not guessed** — the write-path ledger (task 31) proved the
method: profile to ≥80% accounted, rank by share, only then design. This task runs the
same discipline engine-wide and becomes the evidence base the road-map tasks cite.

## Scope

1. **The profiling harness** (sweep/canonical family, task 13's conditions protocol —
   quiet-gate, WARN-console logging, manifest): per-scenario runs with
   - **CPU sampling** (async-profiler flamegraphs or JFR; `-prof` per-run archived),
   - **allocation profiling** (the GC pressure per scenario — feeds W-4d/FP-9),
   - **lock/wait accounting** (the existing `XdStorageMetrics` counters + lock-wait
     sampling; the contention families' real wait distribution),
   - **wall-clock phase attribution** where instrumentation exists (the 2PC phases,
     journal appends, write-outs).
2. **The scenario matrix** (from the canonical estate, post-FK configuration disclosed):
   point reads warm/cold, indexed search (EQUALS/range/paged), scans, data-heavy writes,
   indexed save+delete, batch commits, read-only tx bursts, concurrent-writer windows.
   Each at N=5k and N=50k (task 20's probe sizes).
3. **The ledger**: bottlenecks ranked by measured share, each with (a) the evidence
   artifact (flamegraph/counter excerpt), (b) the fix family it belongs to
   (B3 striping / W-4d memory / binary format / journal hardening / codegen), (c) whether
   an existing task covers it — new issues filed for uncovered ones.
4. **The 2.2.0 evidence pack**: each road-map task (01/05) and the FP-9 gate (task 33)
   receives its measured input — the lock-wait distribution for the consistency-core
   design, the allocation profile for the memory train, the parse-share for the binary
   format decision.

## Out of scope

- Implementing any optimization (findings become issues; fixes belong to their families);
- micro-benchmarks of single methods (JMH estate already covers the macro shapes);
- profiling the test suite itself.

## Acceptance criteria

- [ ] Flamegraphs/allocation profiles archived under `docs/perf/` with manifests
      (the task-13 layout).
- [ ] The ledger: top bottlenecks ranked by measured share, each mapped to a fix family
      and — where uncovered — a filed issue.
- [ ] The 2.2.0 evidence pack delivered: lock-wait distribution (feeds task 01), the
      allocation profile (feeds task 05), the parse share (feeds the FP-9 gate, task 33).
- [ ] No production code changes; the canonical suite unaffected (BUILD SUCCESS verdict).

Estimate: ~2 sessions (the harness ~0.5, the profiling runs and the ledger the rest).
Runs on a quiet machine (the task-13 gate); coordinates with task 43's refreshed baseline
(the same scenario names, so the ledger and the baseline tables cross-reference).

## References

- Task 31 (the write-path ledger — the method this extends), task 13 (the protocol)
- discovery.md § First priority (the consistency core — needs the lock-wait evidence)
  and § Memory (the allocation evidence for W-4d)
- [W4-DESIGN.md](../../W4-DESIGN.md) § Bottleneck ledger (the format to extend)
- MASTER-PLAN Step 2 (B3/W-4d — the consumers)
