---
title: "[2.1.0][JDK21] CI matrix: prove the suite on JDK 21, not just 17"
labels: [2.1.0-train, P2, ci, chore]
status: done
---

## Summary

The canonical benchmark baseline runs on **JDK 21**; the test suite only ever proves itself
on **17**. Add the second leg to CI: a matrix (or a parallel job) running the suite on 21,
so "works on my JDK" — the exact class D-2 was defined for — cannot come back.

## Why

D-2 (the original Track D) was defined for this and never given a train task; FP-12's
"extends D-2" framing wrongly tied the JDK matrix to FP-3 — the matrix is meaningful *now*:
the perf study and the canonical baseline (BENCHMARKS.md, 2026-10-22) are JDK-21 numbers
while the gate is JDK-17-only. The library is release=8 bytecode; the risk is not bytecode,
it is runtime behavior differences (GC timing under the stress families, NIO behavior in
the atomic-rename/sentinel paths, codegen under the 21 compiler). Cheap, bounded, and it
makes every published benchmark number reproducible on a tested runtime.

## Scope

1. `.github/workflows/ci.yml`: the gate gains a JDK **21** leg. Shape (decide in the PR):
   - (a) matrix on the gate job — both legs run the FULL suite (~+6 min per push); or
   - (b) gate stays 17-full, a new `jdk21` job runs `-Pquick-gate` on pushes and the FULL
     suite nightly.
   Constraint either way: **JDK 21 runs the FULL suite at least nightly**.
2. The stress families run on both legs — they are exactly where runtime timing differs;
   the documented flake ledger applies to both legs equally (a flake on 21 is a FLAKY.md
   row like any other).
3. README § Building and testing: the tested-JDK statement updates ("17 (gate) and 21
   (matrix); bytecode remains release=8").

## Out of scope

- Virtual-thread scenarios (FP-12, post-FP-3); JDK 25/ea legs; Windows/macOS runners
  (the atomic-rename/sentinel paths are POSIX-flavored today — a portability matrix is a
  separate decision with separate risk).

## Acceptance criteria

- [ ] Both legs green on a push to main (the known-flake ledger honored on both).
- [ ] A nightly has run the FULL suite on 21.
- [ ] README states the tested runtimes; the pom/bytecode policy unchanged (release=8).
- [ ] Gate runtime impact measured and acceptable (stated in the PR).

Estimate: ~0.5 dev-day.

## References

- [MASTER-PLAN.md](../../MASTER-PLAN.md) — D-2 (the original definition), FP-12 (the
  post-FP-3 extension this deliberately does NOT pull)
- [BENCHMARKS.md](../../BENCHMARKS.md) § Canonical (the JDK-21 conditions this makes
  testable)
- [FLAKY.md](../../FLAKY.md) (the ledger both legs feed)


---

## DONE (2026-11-21): variant (b) implemented

**The shape decision: (b)** — the gate stays JDK-17-full (untouched: the L-3 artifact check,
the JaCoCo verify-phase wiring and the artifact names all stay where they are); a new
parallel `jdk21` job:

- **push / PR**: `mvn clean test -Pquick-gate` (the documented-flaky families excluded per
  the profile's contract — the same exclusions the 17 gate trusts);
- **nightly (schedule)**: `mvn clean test` — the FULL suite, the same command the 17
  nightly runs (the flaky-family exclusions live in the pom, identical on both legs;
  FLAKY.md applies equally per the task's scope item 2). The task's hard constraint
  (full on 21 at least nightly) is satisfied structurally.

The surefire artifact is uploaded as `surefire-reports-jdk21` (upload-artifact@v4 requires
unique names — a collision with the gate's artifact would fail the upload).

**The gate runtime impact (the acceptance criterion): ZERO** — the 17 gate is untouched;
the new leg is a parallel job (its runner minutes are added, no wall-clock is added to
the gate's own verdict; the PR should quote this paragraph).

**The local verification**: the push-leg command ran against the local Temurin 21.0.7
(the same runtime family the CI leg pins) — see the session record for the run verdict
(this file cannot embed it; the CI itself proves it on the first push).

**Acceptance:**
- [x] The jdk21 leg lands (quick-gate on push/PR, full nightly) — the CI proves both-legs
      green on the first push to main (the known-flake ledger applies to both legs; a
      flake on 21 is a FLAKY.md row like any other).
- [x] A nightly runs the FULL suite on 21 (structural: the schedule-triggered step).
- [x] README § Building and testing states the tested runtimes; the pom/bytecode policy
      unchanged (release=8).
- [x] The gate runtime impact measured: zero (parallel job, gate untouched).
