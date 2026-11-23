---
title: "[2.2.0][CISTAB] The CI red-rate triage: pushes 17/66 red, nightlies 8/11 red, releases 4/8 red — the failure-family census and elimination"
labels: [2.2.0-train, P1, ci, tech-debt]
status: open
---

## Summary

The evaluation's §4.3 census (the last 100 workflow runs as of 2026-11-23): push runs
49 green / 17 red, nightlies 11 / 8, release workflow 4 / 8. For a project whose
whole verification story rests on the gates, a ~26% push red-rate and a 50% release
red-rate degrade the signal: a red build stops meaning anything. The nightly ×3 watch
(tasks 23/32) covers ONE family (the concurrency races); this task owns the SYSTEMATIC
census.

## Why

- Every red run costs a triage decision by the single maintainer — the census
  converts the recurring cost into a one-time elimination.
- The release workflow's 4/8 red rate is the worst number: release-day noise on a
  Central-publishing pipeline is the most expensive place to guess.

## Scope

1. The census script (`gh run list` + the failure-signature clustering): every distinct
   failure family in the window, classified — RACE (spin the repro task per the house
   pattern), INFRA (the runner, the mvn cache, the network — the workflow fix),
   TIMEOUT/FLAKY (the budget policy decision per task 10), REAL (a genuine defect —
   filed with its own task).
2. The per-family dispositions recorded in this task; the spins filed.
3. The release-workflow deep-dive (the 4/8 line): which steps fail and why — a
   releasing project cannot keep a coin-flip release pipeline.
4. The re-census gate: two weeks after the dispositions, the push red-rate target
   <5%; the nightlies green-or-triaged.

## Out of scope

The stress-in-gate/nightly POLICY (task 10 owns it — this task's data is its input);
the workflow architecture redesign.

## Acceptance criteria

- [ ] The census table committed (the families × the counts × the dispositions).
- [ ] The spins filed; the INFRA fixes landed (the workflow diffs).
- [ ] The release-workflow verdict recorded (each of the 8 runs explained).
- [ ] The re-census target met or the misses re-triaged.

Estimate: ~1–2 sessions + the two-week watch window.

## References

- The external evaluation 2026-11-23, §4.3 (the census numbers)
- Task 10 ci-stress-gate-policy (the policy decision this task feeds)
- Tasks 23/32 (the nightly-watch pattern the race-family spins follow)
- docs/FLAKY.md (the existing registry — the census folds into it)
