---
title: "[2.1.0][CRASHPURGE] The crash-residue purge dies on dangling references — the post-power-loss cleanup must be reference-tolerant"
labels: [2.1.0-train, P1, engine, testing, bug]
status: done
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

## Fixed (2026-11-01)

- **The purge is crash-residue-tolerant at every level**: the purge was restructured —
  one transaction per GALAXY subtree (a wrapper lazy-load failure marks the WHOLE
  transaction rollback-only, so per-element catches in a shared transaction cannot
  survive it), failures counted in `skippedSubtrees`, retry rounds until no progress,
  then a root-down scorched-earth pass. The all-empty assertions always execute.
- **Engine-side recovery semantics (the decided engine question — "load-or-skip" lives
  in the caller, but residue must not veto):** a foreign-key pair whose counterpart
  record no longer exists (a torn crash commit) is residue — the FK engine dissolves it
  at the child's delete immediately (parent-record probe) and the FK resource resolves
  dangling expectations whose child record is gone at commit, instead of arming an
  un-liftable veto.
- **The recovery purge drops the FK indexes of the purged classes** before the
  by-class pass (both directions: front trees keyed by the parent, reverse trees by
  the child) — the by-class pass deletes children and parents in separate transactions
  and the orphaned-entry expectation armed in the children's tx would veto before the
  parents' tx dissolves the pairs; FK enforcement is meaningless for a dropping store.
  A class whose index tree the crash broke (every access = "node referenced but
  missing") gets its store dropped (fragments + tree structure files) and reopened.
- **The XdSatellite leak question answered**: XdSatellite/XdBlackHole are
  `StoreWithParentObject` — stored INSIDE the parent's file, deleted with the parent,
  NOT facade-loadable (the policy gate is the design). The pinned leak assertion is
  the DISK: zero independent record files under their data directories after the purge.
- **The write phase documented**: only independent-policy objects are saved;
  `StoreWithParentObject` children are written via parent updates (the policy gate for
  independent saves is the design, now commented at the write site).

## Acceptance criteria

- [x] `@Disabled` removed; the test passes 10 consecutive isolated runs. — GATE MET:
      10/10 (after an initial 8/10 whose two failures drove the FK-tree-drop step).
- [x] The purge tolerates dangling references at every graph level; the summary reports
      skipped counts; the all-empty assertions always execute.
- [x] The XdSatellite leak question is answered with a pinned test (embedded-deletion
      contract) — the disk-level zero-files assertion for both embedded classes.
- [x] The post-crash cleanup recipe documented in EMBEDDING.md § After a power loss
      ("Cleanup after a crash", 6 steps); full suite ×2 run (926 tests, 0 failures;
      the single error in both runs is the task-42-documented MassFill FK veto — the
      concurrent-fill divergence, out of this task's scope).

Estimate: ~1–2 dev-days.

## References

- Disabling commit `b3aeacb` (the weakening diff is evidence, not just noise)
- Task 23 (the power-loss matrix — the crash proxy this test implements at graph scale),
  task 34 (the RBCAUSE diagnostics that named this failure precisely)
- [EMBEDDING.md](../../EMBEDDING.md) § After a power loss (the recipe to extend)
