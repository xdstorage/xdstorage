---
title: "[2.1.0][BENCHREFRESH] Benchmark refresh: full canonical re-run, baseline regeneration, and the honest delta vs 2026-10-24"
labels: [2.1.0-train, P1, performance, ci, chore]
status: done
---

## Summary

The canonical baseline ([docs/perf/canonical-baseline-2026-10-24/](perf/canonical-baseline-2026-10-24/))
predates the recent feature wave: the foreign-key engine (task 42, default ON — every
save/update/delete of a related class now maintains front/reverse B+tree entries),
`explain()` groundwork, and the W-4 series' final state all landed after it. Re-run the full
canonical suite, regenerate the baseline tables, and publish the honest delta vs the
2026-10-24 set — so the release notes and the decision guide quote numbers that include
everything 2.1.0 actually ships.

## Why

The FK engine adds per-mutation work to EVERY write of a related class (the exact scenarios
the baseline's write scenarios cover: `writeDataHeavy`, `commitBatch`,
`saveAndDeleteIndexedShipWithCrew`, `updateShipRenameSearchKey`). If it costs 10%, the
baseline hides it; if the W-4a gains offset it, the baseline proves it. Either way, the
2.1.0 release notes need one authoritative set of numbers measured with everything enabled
— this is the same discipline that caught the W-3 "neutral on this shape" verdict and the
~107× fsync reality.

## Scope (the todo list, in order)

1. **Pre-flight**: quiet machine (the runner gates on loadavg); `mvn clean test` green
   (BUILD SUCCESS verdict) before measuring — never benchmark a red tree.
2. **Run**: `bash scripts/run-benchmarks.sh full` — the ONE entry point (conditions
   protocol owned by the script: quiet-gate, WARN-console logging, manifest with git rev +
   `indexJournalWrites`/`enableForeignKeys` flags disclosed **and the machine profile —
   CPU model/cores, RAM GB, root filesystem, disk kind — disclosed in the manifest and
   restated in the doc header; performance-critical subset only: CPU/RAM/storage/fs, no
   hostname or desktop details**). **Pin the JDK via `BENCH_JDK=/path/to/jdk`** — the
   canonical baseline is JDK 21.0.7; the manifest records the version REPORTED BY THE
   BINARY THAT RUNS plus its source (parameter vs PATH), so a wrong-PATH run cannot be
   mistaken for the documented baseline.
3. **Archive**: copy the result set (jmh-full.json + manifest.json) to
   `docs/perf/canonical-baseline-<date>/` beside its two predecessors.
4. **Regenerate**: `python3 scripts/render-bench-baseline.py <new-jmh-json>` → replace the
   canonical tables in [BENCHMARKS.md](../../BENCHMARKS.md) (never hand-typed).
5. **Delta vs 2026-10-24**: extend the existing "Before/after" pattern with a
   "vs 2026-10-24 (pre-FK)" column or a sibling table — every scenario, honestly: FK cost
   where it appears, flat where flat, regressions named (a >10-15% regression on any
   scenario is a finding that blocks the release notes' performance claims until explained
   or fixed).
6. **Cascading number updates** (every doc quoting the baseline):
   - [DECISION-GUIDE.md](../../DECISION-GUIDE.md) § numbers table + design-point sentence;
   - [W4-DESIGN.md](../../W4-DESIGN.md) status (the W-4a gains confirmed on the new set);
   - [README.md](../../README.md) honest-limitation block (write numbers);
   - [PERFORMANCE-RECOMMENDATIONS.md](../../PERFORMANCE-RECOMMENDATIONS.md) banner (only if
     the headline numbers moved materially).
7. **The delta-scope note (updated 2026-11-14)**: the 2026-10-24 baseline PREDATES the
   W-4b search-key journal (64d33f5), the W-4c checkpoint-on-shutdown (06007c9) and the
   foreign-key engine (a32b6a4 + fixes) — the refresh's delta covers all four: the W-4a
   write gains confirmed, the W-4b search-write cost, the FK maintenance overhead on
   related-class writes, and the checkpoint-on-shutdown effect on the restart shape. The
   W-4b readiness question answers itself from this data.

## Out of scope

- Implementing performance fixes for whatever the delta reveals (file issues);
- N50K probe re-run (task 20's harness — a separate follow-up if the design-point claim
  needs re-validation after the FK feature);
- config-sweep re-run (the sweep's t/fragment recommendations are orthogonal to the FK
  change).

## Acceptance criteria

- [ ] New result set + manifest archived under `docs/perf/` with `enableForeignKeys` in
      the disclosed flags.
- [ ] BENCHMARKS.md canonical tables regenerated from the new JSON; the before/after
      section covers BOTH predecessors (2026-10-22 pre-W-4a and 2026-10-24 pre-FK).
- [ ] Every doc quoting baseline numbers checked and updated (the cascade list above).
- [ ] Any regression >10-15% on a scenario: named in BENCHMARKS.md with an explanation or
      a linked issue BEFORE the release notes are drafted.
- [ ] The JMH smoke row assertion still passes (the nightly shape unchanged).

Estimate: ~0.5–1 dev-day (the run is ~2.5 min; the cascade updates are the work).

## References

- Task 13 (the benchmark estate + protocol), task 32 (the before/after pattern to extend)
- [BENCHMARKS.md](../../BENCHMARKS.md) § Canonical baseline + § Before/after W-4a
- [CHANGELOG.md](../../../CHANGELOG.md) `[Unreleased]` (the release-notes source of truth)


---

## DONE (2026-11-21): the refresh executed, the honest delta published, one real regression found and attributed

**The run**: `BENCH_JDK=/usr/lib/jvm/java-21-openjdk-amd64 bash scripts/run-benchmarks.sh full`
(the script's own quiet-gate passed at loadavg 1.40; the manifest records the JDK FROM THE
BINARY — 21.0.7 via the BENCH_JDK parameter, rev 92d2c37, the same Ryzen 9 5900X box as both
predecessors). 23 trials = 9 canonical + 14 search — the shape intact.

**The pipeline adjustment made during this task (the deliberate correction of the task-50
decision):** the FK-cost benchmark moved OUT of the runner's unified JMH set into a
DEDICATED documented invocation — its per-trial preload (up to 100k entities) would have
multiplied the nightly smoke's runtime by ~20 min and violated this task's "the nightly
shape unchanged" criterion. The smoke re-run verified: 9 + 14 rows, ZERO FK rows. The
`enableForeignKeys` flag added to the manifest template (this task's scope item 2).

**The archive**: docs/perf/canonical-baseline-2026-11-21/ (jmh-full.json + manifest.json +
ATTRIBUTION.md — the drift/regression analysis record beside its two predecessors).

**The delta findings (BENCHMARKS.md § the honest delta vs BOTH predecessors — generated by
the NEW scripts/render-bench-delta.py, covering 2026-10-24 AND 2026-10-22):**

1. **commitBatch -43..-55% archived = a MACHINE-STATE DRIFT ARTIFACT, not code.** The
   attribution A/B (the old rev dffaf9b re-run TODAY in a throwaway worktree, the same
   single-fork protocol): dffaf9b-today = 1,543 ops/s @ batch=10 (NOT the archived 2,435);
   HEAD-today = 1,458 → the same-code delta -5% (noise). The method self-check: dffaf9b
   fails to reproduce its own archive on the batch shapes while reproducing it on the
   search-write shapes — the drift is scenario-dependent (allocation/GC-sensitive shapes).
2. **ONE real code regression: `saveAndDeleteIndexedShipWithCrew` -24%** (dffaf9b-today
   781 ≈ its archive 752 → HEAD-today 596): the 2.1.0 correctness wave's price on the
   indexed-write path (the TREEOVERLAY staging/apply + the W-4b search-key journal; the
   trade bought 24,810 phantoms → 0 and the concurrent FKBENCH 0.8 → 121-158 tx/s).
   Tracked in the task-53 W-4 ledger — the optimization candidates are post-release.
3. `updateShipRenameSearchKey`'s archived -16% resolves to -8% code-level (mostly drift).
4. The reads flat (±6%); the W-4a journal ratio re-confirmed (2.6×); the fsync ratio
   intact (~95×); the FK engine idles on the canonical entities (no relations — disclosed
   in the manifest; its cost lives in the FK A/B subsection).

**The cascade updates**: DECISION-GUIDE § numbers (readOnlyTx ~71k, saveAndDelete ~600
with the -24% note, commitBatch ~1,400/~14 with the drift note, the conditions paragraph);
W4-DESIGN status (the W-4a gains re-confirmed on the new set, the regression attributed to
the correctness wave, the W-4b readiness answered: the cost visible and bounded);
README honest-limitation (~3.1k/~8k, the rename ~1.7k, the -24% pointer). PERFORMANCE-
RECOMMENDATIONS untouched (it quotes no baseline absolutes — the task's "only if moved
materially" condition not met).

**Acceptance criteria:**
- [x] The result set + manifest archived with `enableForeignKeys` disclosed.
- [x] BENCHMARKS.md canonical tables regenerated from the new JSON; the delta covers BOTH
      predecessors.
- [x] Every quoting doc checked and updated (the cascade list; PERF-RECOMMENDATIONS
      explicitly evaluated and skipped — no baseline absolutes quoted).
- [x] The >10-15% moves named AND explained (the drift artifact + the one real regression
      with its trade framing) — the release notes can quote the new set.
- [x] The JMH smoke row assertion passes (9+14, zero FK rows — verified by the smoke run).
