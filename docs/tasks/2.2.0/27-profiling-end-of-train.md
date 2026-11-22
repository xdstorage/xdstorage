---
title: "[2.2.0][PROFILE-END] End-of-train profiling re-run: the matrix vs the 2026-11-23 ledger — the before/after for tasks 24/25/26"
labels: [2.2.0-train, P1, engine, performance, feeds-tasks-24-25-26]
status: open
---

## Summary

Re-run the EXACT profiling matrix of task 14 (the same scenarios, sizes, protocol,
parser — docs/perf/profile-2026-11-23/LEDGER.md) at the END of the 2.2.0 train and
publish the honest before/after vs the 2026-11-23 ledger. This is the measurement
discipline that closes the loop: tasks 24 (the journal-append syscalls), 25 (the parse
allocation) and every other landed 2.2.0 optimization claim their measured improvement
here — a landed optimization without a re-profiled delta is an unfinished task.

## Why (architect framing)

The 2026-11-23 ledger is the BEFORE picture: write_heavy ~34% metadata syscalls,
scan_cold ~50% GC + 6–10% the regex scalar machinery, point_warm ~60% the map lookups,
the t=10 contention = the FCW conflicts. The after-picture must come from the SAME
protocol (one scenario per JVM, asprof ctimer collapsed, the exclusive leaf-bucket
attribution, the canonical conditions) — a different protocol produces incomparable
numbers, and incomparable numbers are how regressions hide.

## Scope

1. **The matrix re-run**: the identical 14 CPU profiles + the 2 alloc profiles + the
   concurrent JFR pass (the driver: XdStorageProfilingDriver; the runner pattern:
   /tmp/matrix-run.sh as of task 14; the parser: scripts/render-profile-ledger.py —
   all exist, zero new code expected).
2. **The comparison ledger**: per scenario, the 2026-11-23 vs the new shares side by
   side; every moved share attributed (the task-43 method: the old-rev-today worktree
   re-run separates the machine drift from the code when a number moves unexpectedly).
3. **The task gates**: 24 JOURNALAPPEND (the lxstat/xstat leaves must shrink toward
   zero — the gate for its done-status), 25 PARSEALLOC (the regex/trim leaves gone),
   the other landed tasks' profiles checked against their claims.
4. **The regression net**: any share that GREW without its task landing = a finding
   that blocks the 2.2.0 release notes until attributed (the task-43 rule).
5. **The evidence pack refresh**: the allocation profile re-fed to the memory train
   (task 05), the contention re-checked at the shipped locking model.

## Out of scope

- New scenario shapes (the matrix stays frozen for comparability; new shapes = new
  tasks);
- fixing anything the comparison reveals (findings become issues).

## Acceptance criteria

- [ ] The re-run archived under docs/perf/profile-<date>/ with the manifest (the
      task-13 layout), the parser outputs included.
- [ ] The comparison ledger: every scenario, 2026-11-23 vs the new run, the moves
      attributed (drift vs code, the task-43 method).
- [ ] The task gates resolved: 24/25 claimed improvements CONFIRMED or REOPENED by the
      numbers.
- [ ] No production code changes; the canonical suite green.

Estimate: ~0.5–1 day (the runs ~40 min; the comparison and the attribution the rest).
Depends on: tasks 24/25 landed (or explicitly not landed — the ledger still runs).

## References

- Task 14 (the BEFORE ledger + the method), task 43 (the attribution method — the
  same discipline for the benchmark deltas), task 13 (the conditions protocol)
- docs/perf/profile-2026-11-23/LEDGER.md (the BEFORE ledger this compares against)
- docs/perf/binfeas-2026-11-22/RESULTS.md (the FP-9 realized numbers — the binary
  format changes the cold-load profile structurally; if FP-9 lands in 2.2.0, the
  comparison notes the structural shift, not a regression)
