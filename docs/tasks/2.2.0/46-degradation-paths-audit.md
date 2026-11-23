---
title: "[2.2.0][DEGPATHS] The degradation-path audit: catch(Throwable) ×69, catch(Exception) ×235, Thread.sleep ×8 in main — the per-site ledger"
labels: [2.2.0-train, P3, engine, tech-debt]
status: open
---

## Summary

The evaluation's §3.4 metrics point at the project's own known posture risk: broad
catches and sleeps in the library core. The counts (verified by grep): 69
`catch (Throwable)`, 235 `catch (Exception ...)`, 8 `Thread.sleep` in src/main/java.
Not every site is wrong — at least one sleep is DELIBERATE and documented (the
R-42/R-54 exponential find-retry backoff, `XdStorageBTree.java:1519–1528`), and the
lock-guard `catch (Throwable)` sites are often the latch discipline itself. The
deliverable is the honest ledger, not a blind sweep.

## Why

- Task 02's inventory covers silent RECOVERY decisions; this is the silent SWALLOW
  inventory — the same posture risk one level down (an exception eaten where the
  caller needed the failure).
- The ledger is the artifact the next waves need: every future agent session can
  consult "this catch is DELIBERATE, here is why" instead of re-auditing.

## Scope

1. The inventory script + the per-site classification, committed into this task:
   DELIBERATE (documented, the contract test exists) / SWALLOW (upgrade per task 02's
   three-way policy: LOUD, or KEEP+count, or KEEP+WARN) / BUG (fix — spun).
2. The Thread.sleep sites: each justified (backoff/timing/dead code) — the unjustified
   ones spun for the proper wait/condition mechanism (coordinate with task 01: the
   standard primitives may absorb several).
3. No code changes in THIS task until the ledger is reviewed — the classification
   round is read-only; the fixes ride the spins.

## Out of scope

The fixes themselves; the catch-site REFACTORING beyond the classification (the
swallow upgrades follow task 02's decided policy, each spun with its own gate).

## Acceptance criteria

- [ ] The ledger committed (each site: file/line, class, verdict, the reason).
- [ ] The spins filed for the BUG-class sites (the numbers recorded here).
- [ ] The review round with the maintainer recorded (the disputed verdicts resolved).

Estimate: ~1–2 sessions (the ledger only).

## References

- The external evaluation 2026-11-23, §3.4 (the counts)
- Task 02 loud-failures (the LOUD / KEEP+count / KEEP+WARN policy the SWALLOW class follows)
- Task 01 consistency-core (the primitive migration that may absorb the sleep sites)
- `XdStorageBTree.java:1519–1528` (the documented-deliberate sleep — the ledger's example row)
