---
title: "[2.1.0][CRASHPURGE] The crash-residue purge dies on dangling references — the post-power-loss cleanup must be reference-tolerant"
labels: [2.1.0-train, P1, engine, testing, bug]
status: open
---

## Summary

`XdStorageCrashAndPurgeStressTest` (Сценарий 1, the release-gate stress test: 10 threads of
heterogeneous graph writes for 3 s → hard `shutdownNow()` (the power-loss proxy) → reopen →
cascade purge from the universes down → assert every class is empty) fails **2 of 3 runs**
with the same deterministic signature. The purge transaction dies on a **dangling child
reference left by the simulated power loss**:

```
XdStorageRuntimeException: transaction <id> marked as rollback only and will be rolled back
  (by lazy-load of field 'planets' on XdStarSystem at tx-age 77 ms: cannot load by reference
   object of class XdPlanetSimpleWrapper with idgeneration 1)
  at XdStorageCrashAndPurgeStressTest:183
Caused by: XdStorageException: cannot load by reference … (…:173)
```

The purge is supposed to prove that a post-crash cleanup can return the folder to empty —
instead it is killed by exactly the residue a crash leaves.

## Why (the analysis)

1. **The crash phase leaves dangling references by design.** `workerPool.shutdownNow()`
   kills threads mid-commit; the 2PC's documented tolerance is per-resource — a torn
   multi-file commit leaves SOME resources with the commit and some without. The visible
   scar: a `XdStarSystem`'s persisted `planets` collection references a planet id whose
   insert never became durable. At reopen, hydrating that collection throws
   `cannot load by reference` — correct loudness for application reads.
2. **The purge is only half reference-tolerant.** The test's purge loop wraps each GALAXY
   in `catch (Exception ignored) {}` (the disabling commit even restructured the writes to
   reduce the noise) — but the SYSTEM/PLANET level has no such tolerance, so one dangling
   planet reference poisons the whole purge transaction (the new RBCAUSE diagnostics from
   task 34 name the poisoner precisely — the analysis above is its output).
3. **The leak assertions never execute.** The four all-empty checks (universes/galaxies/
   systems/planets) sit after the purge — on the red runs they are unreached, so the
   actual leak question stays unanswered.

Note: the disabling commit `b3aeacb` also weakened the test's WRITE phase —
`storage.save(hole, tx)` → `storage.update(g, tx)` (XdBlackHole is
`StoreWithParentObject`; saving it independently hits the policy gate) and removed
`storage.save(s, tx)` + the **XdSatellite leak assertion**. The satellite part needs
re-examination, not silent removal: if `XdSatellite` is embedded in `XdPlanet`, deleting
the planet deletes it and the assertion should hold — its failure suggests independently
saved satellite files orphaned by planet deletion (the disabling diff removed exactly the
independent saves, which may have been masking a real leak).

## Scope

1. **Make the purge crash-residue-tolerant** (the test first, the engine question second):
   per-element try/catch at the system/planet level (the galaxy pattern, completed);
   unhydratable references are counted and reported in the summary instead of killing the
   purge. The engine-side question this surfaces: should the purge use-case have a
   recovery-friendly traversal (load-or-skip), or is per-element tolerance in the caller
   the contract? Decide and document (EMBEDDING.md § After a power loss gains the
   "cleanup after a crash" recipe — task 23's recipe covers open failures, not cleanup).
2. **Restore the full assertions**: every class asserted empty after the purge; the
   XdSatellite leak assertion re-added and investigated (embedded-child deletion contract
   vs orphaned independent files — whichever it is, it gets its own pinned test).
3. **Restore the honest write phase**: decide whether `save(hole)`/`save(s)` on embedded
   children should fail loudly (current contract) — the test must not paper over it by
   switching to `update(parent)` without a comment; the graph-write phase should save only
   independent-policy objects and update the embedding parent, with that documented.
4. **Un-disable and gate**: `@Disabled` removed; the test runs stable (the task-23-style
   gate: 10 consecutive passes) before this task closes; failures ledgered in FLAKY.md
   with rates if any remain.

## Out of scope

- Making cross-resource 2PC crash-atomic (the torn-commit tolerance is the documented
  guarantee class); changing the loud `cannot load by reference` behavior for application
  reads; FP-6 recovery tooling (2.3.0).

## Acceptance criteria

- [ ] `@Disabled` removed; the test passes 10 consecutive isolated runs.
- [ ] The purge tolerates dangling references at every graph level; the summary reports
      skipped counts; the all-empty assertions always execute.
- [ ] The XdSatellite leak question is answered with a pinned test (embedded-deletion
      contract) — not by an removed assertion.
- [ ] The post-crash cleanup recipe documented in EMBEDDING.md; full suite green ×2.

Estimate: ~1–2 dev-days.

## References

- Disabling commit `b3aeacb` (the weakening diff is evidence, not just noise)
- Task 23 (the power-loss matrix — the crash proxy this test implements at graph scale),
  task 34 (the RBCAUSE diagnostics that named this failure precisely)
- [EMBEDDING.md](../../EMBEDDING.md) § After a power loss (the recipe to extend)
