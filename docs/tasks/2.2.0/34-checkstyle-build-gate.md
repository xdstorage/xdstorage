---
title: "[2.2.0][CHECKSTYLE] Checkstyle in the build: the bounded ruleset, the committed baseline, the ratchet gate (+ the exec-bit sweep)"
labels: [2.2.0-train, P2, build, tech-debt]
status: open
---

## Summary

The build has JaCoCo (measure-only) and the enforcer, but NO static analysis. The
external evaluation (2026-11-23, §3.4) makes this the headline quality ask. The
measured baseline it cites: 240 lines >160 chars, 75 methods >60 lines, 14 >100,
~1270 fully-qualified names in code, 11 `System.err/out.println` in main, 138 of 227
core files carrying the executable bit. None of this is visible to the build today.

## Why

- A solo project with AI-agent waves needs the machine enforcing the floor: the agents
  will re-introduce `System.err` debugging and 300-line methods unless the build refuses.
- The javadoc campaign proved the pattern works here (1372 missing-doc warnings → 0,
  enforced by the release profile). Checkstyle gets the same treatment, phased.

## Scope

1. **The plugin + the ruleset v1 (bounded, deliberate):** maven-checkstyle-plugin bound
   to the build; the v1 ruleset is the near-free subset: RegexpSingleline bans
   (`System.out.print`, `System.err.print`, `printStackTrace` in `src/main/java`),
   LineLength 160, basic naming, no star imports. MethodLength/FileLength start as
   warnings — the 75-long-method debt is NOT mass-refactored (the refactors ride their
   own tasks; checkstyle only stops NEW ones as the ratchet tightens).
2. **The baseline + the ratchet:** the first run's violation report is committed as the
   baseline; the gate fails only when the count GROWS (the javadoc-campaign ratchet
   pattern). The banned-regex subset goes to `failOnViolation` immediately (after 43
   clears the 11 existing stderr sites).
3. **The exec-bit sweep:** 138 files `100755` → `644` (zero-risk mechanical, one commit).
4. **The decision recorded**: where the gate lives (the default build vs the release
   profile vs a CI leg) — the recommendation is the default `verify` (it is fast) so
   every local run sees it.
5. Phase 2 (a separate decision, not this task): PMD/SpotBugs; the coverage floor for
   the measure-only JaCoCo.

## Out of scope

Mass method-length refactoring; PMD/SpotBugs; the JaCoCo thresholds (listed as the
phase-2 candidates only).

## Acceptance criteria

- [ ] `mvn verify` runs checkstyle; the ratchet gate green on the current tree.
- [ ] The baseline committed; the CI leg enforces the no-growth rule.
- [ ] The regex bans fail the build (proven once by a deliberate violation in a scratch
      commit, then reverted).
- [ ] The exec bits normalized (`git ls-files -s src/main/java` shows only 100644).
- [ ] The quick-gate ×3 green.

Estimate: ~1–2 sessions.

## References

- The external evaluation 2026-11-23, §3.4 (the metrics table) and §4.3 (the process
  critique motivating the machine enforcement)
- The javadoc campaign (2.1.0, task 28): the ratchet + the gate mechanics precedent
- Task 43 VERIFYAPI — clears the 11 existing stderr sites (the regex ban's prerequisite)
