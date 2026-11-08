# W-4a acceptance measurement: the write-scaling wall before/after index journaling

Task 32 (docs/tasks/2.1.0/32) · measured 2026-10-23 · harness:
`performance/sweep/W4aWriteScalingMeasure` (single-run, not canonical-JMH — the canonical
integration follows with the W-4b phase). **Machine:** AMD Ryzen 9 5900X (12 cores / 24
threads), 64 GB RAM, NVMe SSD (Samsung 970 EVO Plus), ext4, Ubuntu 20.04 / kernel 5.15,
JDK 21.0.7 — quiet, single run per cell. Run twice on the same machine, same JVM setup:
`indexJournalWrites=false` (the legacy path — behaviorally identical to pre-W-4a) and
`=true`.

| N (objects/class) | bulk-insert commit (1 tx) | small write (ms/tx, 200 txs) |
|---|---|---|
| **flag OFF (before)** | | |
| 1 000 | 76 ms | 2.485 |
| 5 000 | 68 ms | 7.540 |
| 50 000 | 338 ms | **64.900** |
| **flag ON (after)** | | |
| 1 000 | 67 ms | 0.270 |
| 5 000 | 64 ms | 0.180 |
| 50 000 | 606 ms | **0.130** |

**The gate (W4-DESIGN § acceptance):** the N=50k small-write cost within ≤3× of N=1k —
measured 0.48× (0.13 vs 0.27 ms/tx): **the O(N_ids) term is eliminated**; the small-write
cost is now flat across the design point (was 26× degradation).

**Honest notes:**
- the flag-off measurement IS the legacy behavior (the flag-off code path is untouched
  pre-W-4a code) — recorded with the flag present, not before it existed;
- the bulk-insert commit at N=50k is ~1.8× SLOWER with journaling (606 vs 338 ms): a
  50k-object single transaction writes every record to the journals (data + index) —
  the O(delta) format's constant exceeds one bulk rewrite at that batch size. This is the
  expected crossover, not a regression of the small-commit path the wall lives in;
  batch-oriented workloads can checkpoint more aggressively (W-4c's machinery);
- single run per cell, quiet machine, JDK 21 — treat as the gate evidence; the canonical
  JMH integration re-measures.
