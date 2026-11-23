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

## PROGRESS (2026-11-24): LANDED — the gate is live in warnings mode

**What landed (three commits):**
1. `9beaba8` STYLE — the formatting-only pass: 1 956 code lines wrapped, 63 comment
   lines reflowed, the 12 comment-tab characters → spaces, trailing whitespace
   stripped, the missing final newlines appended. 326 files. **Mechanically proven
   semantics-free**: the code-token stream identical to HEAD (after folding the
   constant literal concatenations the wraps introduce), every string literal
   byte-equal (a char-lexer comparison), every comment word-equal. Listed in
   `.git-blame-ignore-revs` (this file's neighbor at the repo root) — blame stays
   usable in the hot engine files.
2. `b875b3c` IMPORTS — 294 dead import lines removed (62 main + 232 test
   unused/redundant/duplicate), flagged by the ruleset; compile-verified.
3. The gate commit (this one) — `checkstyle.xml` + `suppressions.xml` at the repo
   root + the maven-checkstyle-plugin 3.3.1 (checkstyle 10.12.4) bound to the
   `validate` phase with `includeTestSourceDirectory=true`.

**The ruleset v1 (the standard, objective subset):** LineLength 120 (UTF-16 units —
the javac metric), FileTabCharacter, the print bans (`System.out/err`, 
`printStackTrace` — a library must not own the application's streams),
UnusedImports (processJavadoc=true: a javadoc-referenced import is USED — the
release doclint gate stays intact), RedundantImport.

**Phase-1 policy (the maintainer's call, 2026-11-24): WARNINGS ONLY.**
`severity=warning` + `failOnViolation=false` — the gate reports and never reds the
build. **The flip to fail-fast is a one-line change** (severity=error +
failOnViolation=true) — and the tree stands at ZERO violations today, so the flip
is safe whenever the maintainer wants enforcement on; the decision is recorded
here rather than silently made.

**The suppressions (the honest debt, each owned by a task):**
- `XdStorageFolderVerifier` + `Compact` — the print bans only (43 VERIFYAPI owns
  the structured report; 11 stderr sites).
- `src/main/test/**` — the print bans only: the suites' `System.out` diagnostics
  are the test corpus's own reporting style, not the artifact (403 sites).

**The measurements:** the pre-wave census (char-based, the eval's own method
validated: >160 = 246 ≈ the eval's 240): 1 232 code + 36 comment lines over 120 in
main, 830 + 34 in tests, 461 star imports, 138 files with the exec bit, 0 tabs in
main (the house norm held), 24 files without the final newline. The gotchas the
round surfaced: awk `length()` counts BYTES in a C locale (the first census was
2× inflated on the Cyrillic files — the char recount matched the eval); an emoji
is 2 UTF-16 units (9 test lines sat at exactly 121); a bare `mvn javadoc:jar`
outside the release profile fails on HEAD too (the 4 pre-existing doclint errors —
not this wave's doing).

**Phase-2 ratchet candidates (each its own decision, not this task):** AvoidStarImport
(461 sites — the expansion is an IDE-grade sweep), the Indentation module (brittle
on legacy), MethodLength/FileLength, naming, the JaCoCo coverage floor, PMD/SpotBugs,
the exec-bit normalization sweep (138 files, zero-risk, still pending), the CI
checkstyle-report upload.

## References

- The external evaluation 2026-11-23, §3.4 (the metrics table) and §4.3 (the process
  critique motivating the machine enforcement)
- The javadoc campaign (2.1.0, task 28): the ratchet + the gate mechanics precedent
- Task 43 VERIFYAPI — clears the 11 existing stderr sites (the regex ban's prerequisite)
