---
title: "[2.2.0][TOOLS] xdstorage-tools: the operations CLI (verify / backup / inspect / migrate)"
labels: [2.2.0-train, P1, engine, feature]
status: open
---

## Summary

A small operations CLI — `xdstorage-tools.jar` with subcommands over the engine's public
APIs — so the storage is operable from pipelines, agents and shells without writing Java:
`verify <folder>` (task 26's integrity walk), `backup <src> <dest>` (task 15's API),
`inspect <folder>` (counts, indexes, journal state, format marker), `migrate <folder>
--to <format>` (the v1→v2 / YAML→XML recipe, mechanized).

**Pulled forward from 2.3.0 (FP-6) by the PRODUCT_REVIEW demand** (§4.2 high-priority #4:
"a `xdstorage-tools.jar verify folder`, `backup folder dest`, `migrate folder` would make
the product usable from a pipeline"; §3.2: the agent-tooling gap).

## Why

Every building block already exists or lands in this train: `verify()` (task 26),
`backupTo()` (task 15), the format-marker check (A2), the structure-update migration
 hooks. The CLI is a thin main-class over them — the value is operational: CI pipelines
 verify before deploy, agents inspect before reading, SREs diagnose without writing Java.

## Scope

1. A single main class with subcommands (`verify` / `backup` / `inspect` / `migrate`),
   exit codes scriptable (0 = OK, non-zero = findings), `--json` output for pipelines.
2. `inspect`: counts per class, index entries vs data, journal state, format marker —
   the read-only folder walk (no engine open), shared with verify's core.
3. `migrate`: the format-generation/serialization recipe mechanized (the v1→v2 sentinel
   backfill and the YAML→XML rewrite via the engine's own reader/writer), with a
   dry-run mode.
4. Packaging: a `-shaded`-style tools jar or a plain jar with a Main-Class manifest —
   the decision in the PR; distribution documented in EMBEDDING § Tooling (new section).

## Out of scope

A server/daemon mode (the 2.2.0 feasibility task 09 decides that separately); a GUI;
remote operation over the network.

## Acceptance criteria

- [ ] Every subcommand scriptable from a pipeline (exit codes + `--json` where useful).
- [ ] `verify` detects each corruption class from task 23's matrix on copied folders.
- [ ] `migrate` round-trips a real v1→v2 folder and a YAML→XML folder (tests pinned).
- [ ] EMBEDDING § Tooling written; the release ships the tools jar (or documents the build).
- [ ] Full suite green ×2 (BUILD SUCCESS verdict).

Estimate: ~2 sessions.

## References

- PRODUCT_REVIEW.md §4.2 (the demand), §3.2 (the agent-tooling gap)
- Task 26 (verify core), task 15 (backup API), task 23 (the recovery recipe the tools serve)
- MASTER-PLAN Step 3 (FP-6 pulled forward per the PRODUCT_REVIEW demand)
