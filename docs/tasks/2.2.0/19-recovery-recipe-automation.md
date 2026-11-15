---
title: "[2.2.0][RECAUTO] Recovery recipe automation: verify() outputs the recipe, the tools CLI gains recover"
labels: [2.2.0-train, P2, engine, feature]
status: open
status: open
---

## Summary

Make the after-a-power-loss recipe actionable: `verify(folder)` gains a `--recipe` mode
that outputs the exact recovery steps for the detected damage (the EMBEDDING recipe,
computed from the folder's actual state), and the tools CLI gains a `recover` subcommand
that executes the safe subset automatically (sentinel backfill, journal reset, the
legacy-flag reopen) — with the destructive steps always requiring an explicit flag.

## Why

Both implementation plans (CONSISTENCY + DURABILITY, docs/analysis/) name recovery
recipe automation as the operability milestone: the recipe exists in EMBEDDING.md prose
(task 23's matrix documented every failure mode), but an operator mid-incident must
translate prose into commands. The verify() report already detects every corruption
class from the matrix — the recipe output is the last step.

## Scope

1. `verify --recipe <folder>`: the walk (task 26's core) extended to emit the recipe
   steps for the detected state (which files are torn, which flag opens them, what the
   checkpoint state is) — the plan, not the execution.
2. The tools CLI `recover` subcommand: executes the SAFE subset automatically
   (checkpoint reset, journal reset after a verified-safe state, the legacy reopen) —
   with the destructive steps behind `--execute` (dry-run prints, `--execute` applies).
3. The EMBEDDING recipe gains the tool mapping: each prose step names the subcommand.

## Out of scope

Automatic repair inside the engine's open path (task 23's rejection stands — a silent
truncation acceptance is the class S-1 exists to prevent); backup/restore (task 15).

## Acceptance criteria

- [ ] Every task-23 matrix corruption class: verify --recipe outputs the correct recipe
      steps for it (pinned by tests on copied folders).
- [ ] The recover subcommand executes the safe subset on a damaged copy and the folder
      reopens clean (the task-23 acceptance shape).
- [ ] EMBEDDING § After a power loss references the tool; the release ships it.
- [ ] Full suite green ×2 (BUILD SUCCESS verdict).

Estimate: ~1–2 sessions.

## References

- docs/analysis/DURABILITY_IMPLEMENTATION_PLAN.md § P2-1 (the steps)
- docs/analysis/CONSISTENCY_IMPLEMENTATION_PLAN.md § P2-1 (the same item, cross-listed)
- Task 26 (verify core), task 16 (the tools CLI), task 23 (the EMBEDDING recipe)
