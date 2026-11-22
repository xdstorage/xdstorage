---
title: "[2.2.0][CONTENTIONPROBE] Lock-wait distribution at scale: the contention probe beyond t=10"
labels: [2.2.0-train, P2, engine, performance, feeds-task-01]
status: open
---

## Summary

The PROFILE ledger's concurrent scenario (t=10, N=5k) answered the contention question
partially: at t=10 the contention manifests as **first-committer-wins conflicts**
(3,532 / 2,522 on the hot workers — the DESIGNED response) with thin parks
(44 ThreadPark events / 30 s). The consistency-core task 01 needs the PARK/wait
distribution at the contention scales where the B3 families lived (t=50–100, the
hot-record skew) — the evidence this probe must produce before the design round.

## The probe sketch

1. The scenarios: t=25/50/100 × the uniform-key and the hot-key (90% of the ops on
   10% of the records) workloads; the update-tx shape + the mixed read/write;
2. The evidence per run: the ThreadPark distribution (the durations, the blockers —
   from `jfr view contention-by-class/site/thread`), the conflict counts, the
   throughput, the p99;
3. The output: the park-vs-conflict ratio at each scale — the input the consistency-
   core design round (task 01) needs to decide between the striping/locking models.

## Acceptance

- [ ] The park-vs-conflict table at t=10/25/50/100, the uniform and the hot-key.
- [ ] The results filed into task 01's evidence section.

Estimate: ~0.5 day. Evidence: docs/perf/profile-2026-11-23/LEDGER.md § the concurrent
writers (the t=10 baseline this extends).
