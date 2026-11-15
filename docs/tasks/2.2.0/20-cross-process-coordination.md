---
title: "[2.2.0][XPROC] Cross-process consistency coordination (HA deployments)"
labels: [2.2.0-train, P3, engine, durability]
status: open
status: open
---

## Summary

Both implementation plans name cross-process coordination as the HA-deployment
follow-up: today the folder lock makes multi-process sharing impossible by contract
(EMBEDDING § Lifecycle), and the durability story assumes a single writer. This task
is the design-first investigation of what a SECOND process can safely do against the
same folder: the fsync coordination (P3-1 in the durability plan) and the
read-only-consistency coordination (a reader process observing another process's
commits without the exclusive lock).

## Why

The field demand is real but bounded: HA deployments need a second process for
monitoring/backup while the primary runs; today even a read-only second process is
impossible by design. The single-writer contract is what makes the engine's consistency
model tractable — this task evaluates what can be relaxed WITHOUT breaking it, design
first, implementation only if the design survives the round.

## Scope

1. The design document: (a) the fsync-coordination options for a backup process
   (checkpoint-aware reads vs the quiesced window vs the WAL-tail follow), (b) the
   read-only concurrent reader (the lock-free snapshot walk over the committed files),
   (c) the failure modes each option introduces.
2. The positioning guard: the single-writer WRITE contract is not on the table — the
   task evaluates READ-side coordination only; a second WRITER stays impossible by
   design.
3. The decision: which option is the 2.3.0 candidate (if any), with the acceptance
   shape written.

## Out of scope

Multi-writer coordination (the exclusive-lock contract stands); network serving (the
server-mode feasibility task owns that separately); implementing any option here.

## Acceptance criteria

- [ ] The design document with the options, the failure modes per option, and the
      recommendation.
- [ ] The decision recorded in MASTER-PLAN (go/no-go for a 2.3.0 implementation task).
- [ ] No code changes.

Estimate: ~1 session (design only).

## References

- docs/analysis/DURABILITY_IMPLEMENTATION_PLAN.md § P3-1 (the origin)
- docs/analysis/CONSISTENCY_IMPLEMENTATION_PLAN.md § P3-1 (the consistency analogue)
- Task 15 (the backup API — the safe alternative for the backup use case)
- EMBEDDING.md § Lifecycle (the exclusive-lock contract)
