# Analysis documents — index and mapping to the trains

Five analysis/planning documents produced by the developer sessions (2026-11), covering
the two 2.2.0 road-map themes. **Every recommendation is mapped to a train task** — the
tables below are the reconciliation. Status annotations inside each document are updated
as items land.

## The index

| Document | What it is | Feeds | State |
|---|---|---|---|
| [CONSISTENCY_ANALYSIS.md](CONSISTENCY_ANALYSIS.md) | The FK/consistency subsystem analysis (the dual-tree persistence, the registry, the veto flows, the divergence families) | 2.2.0 tasks 01/02/03 | the analysis baseline |
| [CONSISTENCY_IMPLEMENTATION_PLAN.md](CONSISTENCY_IMPLEMENTATION_PLAN.md) | The step-by-step consistency plan (P0→P3, ~18–20 sessions total) | 2.2.0 tasks 01/02/06 (+ P2/P3 items below) | the execution order is the plan of record |
| [DURABILITY_ANALYSIS.md](DURABILITY_ANALYSIS.md) | The durability analysis (journal, checkpoint, fsync, recovery across all main code) | 2.2.0 tasks 48/49/05 + 15 | the analysis baseline |
| [DURABILITY_IMPLEMENTATION_PLAN.md](DURABILITY_IMPLEMENTATION_PLAN.md) | The step-by-step durability plan (P0→P3, ~13–14 dev-days) | 2.2.0 tasks 48/49 (done) + 05/15/16 | P0 shipped; P1–P3 open |
| [FOREIGN_KEYS.md](FOREIGN_KEYS.md) | The FK subsystem — the consolidated subsystem document (architecture, the 2PC write-set + the 8 POINTS, waiters, the manual-ID mechanism, the honest limitations ledger; supersedes the six deleted FOREIGN_KEY_*.md docs) | 2.1.0 tasks 42/50, 2.2.0 tasks 01/02 | current (verified 2026-11-16) |
| [COMMIT_ATOMICITY_DESIGN.md](COMMIT_ATOMICITY_DESIGN.md) | The cross-resource crash atomicity of a commit: the hole PROVEN on real crash folders, the protocol as it is, five options with the rejected ones, the recommendation (one durable point of no return behind a flag) | 2.2.0 task 50 (+ the task-06 batch trailer it builds on) | design round 2026-11-24; implementation open |

## The reconciliation (plan item → train task)

| Plan item | Priority | Train task | State |
|---|---|---|---|
| Consistency-core rebuild (j.u.c primitives + enable the disabled B+Tree tests) | P0 | [2.2.0/01](../tasks/2.2.0/01-consistency-core-standard-primitives.md) | open |
| Loud-failures inventory (silent-recovery → LOUD/KEEP+count) | P0 | [2.2.0/02](../tasks/2.2.0/02-loud-failures-no-quiet-self-heals.md) | open |
| Journal/WAL TX-ID hardening (sentinel v3) | P1 | [2.2.0/06](../tasks/2.2.0/06-journal-wal-txid.md) | LANDED 2026-11-24 (the batch trailer + the loud mid-file corruption; the cross-resource marker → [2.2.0/50](../tasks/2.2.0/50-commit-marker-cross-resource-atomicity.md)) |
| Cross-resource crash atomicity (the commit marker) | P0 | [2.2.0/50](../tasks/2.2.0/50-commit-marker-cross-resource-atomicity.md) | open (the design round done) |
| W-4d memory train (disk-anchored scans + eviction + retention) | P1 | [2.2.0/05](../tasks/2.2.0/05-w4d-memory-train.md) | open |
| Property-based tests (jqwik over the engine invariants) | P1 | [2.2.0/03](../tasks/2.2.0/03-property-based-tests.md) | open |
| Checkpoint-on-clean-shutdown (W-4c) | — | **DONE** (2.1.0 task 48, commit `06007c9`) | closed |
| W-4b search-key logical-op journal | — | **DONE** (2.1.0 task 49, commit `64d33f5`) | closed |
| Backup API `backupTo(Path)` (FP-5 core) | P1 | [2.2.0/15](../tasks/2.2.0/15-backup-api.md) | open |
| Recovery recipe automation (`verify` recipe steps + the `recover` command) | P2 | [2.2.0/19](../tasks/2.2.0/19-recovery-recipe-automation.md) | open |
| Cross-process consistency/fsync coordination (HA) | P3 | [2.2.0/20](../tasks/2.2.0/20-cross-process-coordination.md) | open |
| No-sentinel auto-repair for legacy folders | P3 | folded into [2.2.0/17](../tasks/2.2.0/17-migration-schema-diff.md) (the migrate command's legacy-recipe mode) | folded |
| The consistency-core/durableWrites tradeoff documentation | P2 | folded into 2.1.0 task 44 (the tech-writer pass) + the EMBEDDING/BENCHMARKS notes (already partially landed) | folded |

## Reading order

1. CONSISTENCY_ANALYSIS.md + DURABILITY_ANALYSIS.md — the state of the two subsystems.
2. The two IMPLEMENTATION_PLANs — the step-by-step execution (the P0s are the train's
   opening items).
3. FOREIGN_KEYS.md — the FK-specific record (the subsystem document, the limitations
   ledger feeds the 2.2.0 consistency core).

The plans' own execution notes are binding: no new dependencies, no format bumps for
the WAL hardening, no default behavior changes, and **task 03's property tests are the
acceptance gate for task 01's consistency-core rebuild**.
