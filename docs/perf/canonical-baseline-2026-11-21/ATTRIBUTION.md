# Attribution notes — the 2026-11-21 baseline (BENCHREFRESH, task 43)

The honest-delta section in BENCHMARKS.md quotes this attribution; the raw A/B numbers
below are the record. The method: the SAME single-fork JMH protocol, the same box, the
same day, the OLD baseline revision (`dffaf9b`, checked out into a throwaway worktree)
vs TODAY's HEAD — the machine-state drift cancels out of the same-code comparison.

## The drift artifact: commitBatch

| Trial (single fork) | dffaf9b TODAY | HEAD TODAY | same-code delta | dffaf9b ARCHIVE |
|---|---|---|---|---|
| batch=10 | 1,542.9 | 1,458.3 | **-5.5% (noise)** | 2,435.5 |
| batch=100 | 161.2 | 158.3 | -1.8% | 310.3 |
| batch=500 | 33.8 | 31.5 | -6.8% | 61.7 |
| batch=1000 | 16.6 | 16.0 | -3.6% | 31.0 |

dffaf9b TODAY does NOT reproduce its own archive on the batch shapes (-37% at batch=10)
while it DOES on the search-write shapes below — the October run's system state differs
in a scenario-dependent way (the batch shapes are the most allocation/GC-sensitive).
The archived "commitBatch -43..-55%" is a DRIFT ARTIFACT, not a code regression.

## The real code regression: saveAndDeleteIndexedShipWithCrew

| Trial (single fork) | dffaf9b TODAY | HEAD TODAY | same-code delta | dffaf9b ARCHIVE |
|---|---|---|---|---|
| saveAndDeleteIndexedShipWithCrew | 780.8 | 596.1 | **-23.6% — REAL** | 752.1 |
| updateShipRenameSearchKey | 1,865.5 | 1,723.3 | **-7.6% (borderline)** | 2,028.2 |

dffaf9b TODAY reproduces its archive here (781 vs 752; 1,866 vs 2,028) — so the
saveAndDelete drop is CODE (the 2.1.0 correctness wave on the indexed-write path: the
TREEOVERLAY staging/apply + the W-4b search-key journal), and the rename drop is mostly
drift with a small real residual. See BENCHMARKS.md § the honest delta for the reading.

## Conditions

Both arms: AMD Ryzen 9 5900X, Temurin/OpenJDK 21.0.7, the same loadavg band (1.1-1.4),
WARN-console logging, YAML, fragment 25. The full-run manifest.json sits beside this file.
