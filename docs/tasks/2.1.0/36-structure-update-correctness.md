---
title: "[2.1.0][STRUCTFIX] Structure-update correctness — the FAILURE-mislabel, equals/hashCode, the ignored tx parameter, and the dead transform override"
labels: [2.1.0-train, P2, engine, bug]
status: done
---

## Summary

Four findings of the 28-javadoc-coverage campaign, all in the stored-schema layer
(`structure/`, `structure/update/`):

1. **`XdStorageStructureManager.updateStructure` mislabels a prior success as FAILURE.** In the
   "script has been executed earlier" branch the code calls `setResult(res, ...)` where `res`
   still holds the initial `FAILURE` — `getResult()` answers FAILURE with the message
   "has been executed earlier" for a script that actually succeeded. Any caller branching on
   the result takes the failure path.
2. **`XdStorageClassStructure` and `XdStorageClassIndexStructure` override `equals` without
   `hashCode`.** Both are persisted `@XdStorageObjectId` records that end up in hash-based
   collections — an equals/hashCode contract violation with nondeterministic lookup behavior.
3. **`checkAndUpdateStructureIfNeed(Class<?>, XdStorageTransaction)` ignores its `tx`
   parameter** — the verification/migration actually runs in an internally created autonomous
   transaction on the engine executor, while `IXdFileStorage.executeStructureUpdate` requires
   the caller to be inside a transaction. Decide: honor the caller's transaction (2PC-atomic
   with the caller's work) or make the autonomous-transaction contract explicit — either way,
   today's signature suggests a guarantee the code does not give.
4. **`XdStorageSearchIndexStructureUpdater.transform(...)` is a dead override** — always
   returns `null` and is never invoked (`execute` is overridden and re-indexes via watchers),
   so the inherited record-rewrite machinery is unused here. Remove the override or wire it,
   per what the updater really needs.

## Scope

- (1) fix the result propagation + a regression test asserting SUCCESS with the
  "executed earlier" note;
- (2) add consistent `hashCode` to both records (equals semantics unchanged);
- (3) the decision first (one paragraph here or in the class doc), then the code/doc change —
  honoring the caller's transaction is the preferred outcome if the executor allows it;
- (4) delete the dead override (git keeps the history) or wire it, with the updater's
  re-index flow covered by an existing test (16's id-addressability suite is the closest).

## Fixed (2026-11-01)

- (1) The "executed earlier" branch reports `SUCCESSFULLY` now (the stale `res` carried
  the initial FAILURE into a successful script's result). Regression-pinned in the
  evolution ledger test (`assertNotEquals(FAILURE, ...)` on the skipped updater).
- (2) Consistent `hashCode` added to both records; the contract pinned by
  `XdStorageStructureRecordsContractTest` (equality, hash equality, hash-set behavior,
  difference sanity — both records).
- (3) DECISION RECORDED (class javadoc): the migration does NOT run in the caller's
  transaction — DDL-like semantics (a business-tx rollback must not resurrect the old
  schema over migrated data) + the deadlock history of the executor-offloaded
  autonomous transaction; the call stays SYNCHRONOUS, so the caller's subsequent
  operations run against the migrated schema. The `tx` parameter stays for API
  compatibility and is documented as unused.
- (4) The dead `transform` override removed structurally: the base's `transform` is a
  LOUD default (`UnsupportedOperationException` naming the class) instead of abstract —
  watcher-based updaters no longer carry a null-returning ceremony override, and a
  record-rewriting subclass that forgets the override fails loudly at migration time
  instead of silently writing null records.
- BONUS (the dual-generation work of task 16 landed through this suite): the structure
  record's identity is the class FQCN; `XdStorageClassIndexStructureId.toString` handles
  the FQCN form.

## Acceptance criteria

- [x] All four resolved with a test or an explicit recorded decision.
- [x] `equals`/`hashCode` contract test on both structure records.
- [x] Full suite green ×2. — 926 tests ×2, 0 failures; the single error is the
      task-42-documented MassFillAndClean FK veto (out of scope here).

Estimate: 1 d.

## References

- [28-javadoc-coverage](28-javadoc-coverage.md) — the campaign that surfaced all four
- [16-id-addressability-after-structure-update](16-id-addressability-after-structure-update.md)
  — the structure-update defect family this extends
