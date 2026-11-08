---
title: "[2.1.0][BENCHREFRESH] Benchmark refresh: full canonical re-run, baseline regeneration, and the honest delta vs 2026-10-24"
labels: [2.1.0-train, P1, performance, ci, chore]
status: open
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
7. **The W-4b readiness note**: record whether the new baseline still supports the W-4b
   (search-tree logical-op journal) priority — if the rename/update scenario moved a lot,
   say so.

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
