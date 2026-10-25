---
title: "[2.1.0][T-3b] Facade API audit (happy+failure matrix) and test-litter cleanup"
labels: [2.1.0-train, P1, testing, chore]
status: done
---

## Summary

Two halves: (a) prove every public `IXdFileStorage` method has at least one happy-path and one
failure-path test — the completeness audit the coverage map cannot give; (b) clean the test
litter accumulated across campaigns (`/temp` package, empty packages, the `/performance`
package's mixed content).

## Why

Coverage percentage says nothing about *which* methods are tested — an embedder's contract is
the facade, so the audit is per-method, not per-line. The litter is real: `temp/SpecificTest`
+ `Entity`/`EntityWrapper` sit in a package named for disposal; `entities`/`updaters` are
empty; `/performance` mixes stress families, genuine unit tests (BTreeGenerationStrategyTest,
ObjectGraphPolicyAndIndexTest, YamlRoundTripTest), a diagnostic (GraphFlowDiagnosticTest) and
JMH benchmarks. Decision record: [MASTER-PLAN.md](../../MASTER-PLAN.md) § Track T-3.

## Scope

1. **The facade matrix** — enumerate all `IXdFileStorage` (+ `XdStorageProvider`) public
   methods; for each: the test class/method that covers happy path and failure path. Gaps get
   filled (small focused tests; reuse entity helpers). The matrix goes into the PR — it is the
   deliverable, and future tasks update it.
2. **`/temp` dissolved** — `SpecificTest` is either a real missing case (relocate into the
   proper package, rename meaningfully) or dead (delete after confirming); the entity helpers
   move to wherever sibling tests keep theirs (or a shared test-infra location — do NOT
   rename `src/main/test` itself, F-6).
3. **Empty packages resolved** — `entities`, `updaters` (and `operation` if task 06 hasn't
   landed yet): fill per 06's plan or remove the package declaration.
4. **`/performance` content decision** — stress families get `@Tag("stress")` (task 05 did the
   tagging; this task confirms nothing load-bearing runs untaged in the default set);
   benchmarks (no `Test` suffix) stay surefire-invisible — document that in the package's
   package-info; `GraphFlowDiagnosticTest` gets a javadoc line saying what it diagnoses and
   when to run it.
5. All of it is test-side — **zero production-code changes** in this task.

## Out of scope

- The facade method's *semantics* changes (bugs found during the audit become separate issues
  with reproduction tests — file them, don't fix silently here).

## Acceptance criteria

- [ ] Facade matrix complete in the PR: no public method without named happy+failure tests
      (or an explicit linked issue explaining the gap).
- [ ] `/temp` gone; no empty test packages; `/performance` documented via package-info.
- [ ] Zero diffs under `src/main/java` (CI-enforceable by inspection of the PR file list).
- [ ] Full suite green ×2 (BUILD SUCCESS verdict); counts unchanged except added audit tests.

## Landed (2026-10-25): the facade matrix + the litter cleanup

### The facade matrix (method → happy → failure)

Legend: **bold** = test added by this task; everything else names the pre-existing suite.

| Method | Happy | Failure |
|---|---|---|
| `getName` | **FacadeMethodAudit.getName_MatchesOpenedName** | — (no failure mode) |
| `getMetrics` | **Audit.getMetrics_TransactionCountersMove** + XdStorageMetricsTest | — (counters never fail) |
| `registerTrigger` | XdStorageTriggerFacadeTest | TriggerManagerTest (trigger misbehavior fires at commit) |
| `beginTransaction()` / `(timeout)` | FirstCommitterWins, BTreeFullCollapse (15000L), TransactionTest | timeout sweep: isolation stress suites; NestedTransactionIsolationStress |
| `beginTransaction(parent[, timeout])` | NestedTransactionIsolationStressTest, TransactionTest | R-41 isolated nested rollback: TransactionTest |
| `commitTransaction` | TransactionTest + every suite | rollback-only commit throws: XdStorageReadOnlyCommitPathTest |
| `rollbackTransaction` | TransactionTest + S4Lifecycle | late/racing rollback suppressed (R-58): CacheRollbackGuardTest |
| `save(Object)` / `(Object, tx)` | CrudOperations (both policies), AutoSaveCascadeTest, FirstCommitterWins | CrudOperations: outside-transaction + entity-without-id-field |
| `save(Collection[, tx])` | BTreeFullCollapse.insertBatch, stress initOperations | element pipeline shared with the single form (refusals per element, first failure stops) |
| `has(cl, id[, tx])` | **Audit.has_TrueForStored_FalseForMissing** | **Audit.has_OutsideTransaction_FailsLoudly** |
| `load(Object ref[, tx])` (hydration) | stress purge hydration (Xml/Yaml Multithreads) | dangling: "cannot load by reference" (task-40 evidence chain; InvisibleReference at resource level) |
| `load(Class, id[, tx])` | CrudOperations.Load_ById, FCW check | **Audit.load_MissingId_ReturnsNull_AuditFinding** — ⚠ AUDIT FINDING below |
| `load(Class[, tx])` (all) | BTreeFullCollapse (empty + loaded), FCW check | — (empty storage = empty list, no failure mode) |
| `load(Class, predicate[, tx])` | **Audit.load_ByPredicate_FiltersInSnapshot** + resource-cache predicate suites | predicate throw fails the read (cache-level suites) |
| `load(Collection[, tx])` (hydration) | stress purges | same dangling semantics as `load(Object ref)` |
| `update(Object[, tx])` | FirstCommitterWins, AutoSaveCascade | **FCW stale-write rejection** (first-committer-wins) |
| `update(Collection[, tx])` | shares the element pipeline (FCW single-form semantics per element) | same rejection per element |
| `delete(Object[, tx])` / `delete(Collection[, tx])` | **BTreeFullCollapse (deleteAll)** | **BTreeFullCollapse phantom delete throws + no resurrection** |
| `getTransactionManager` | **Audit.getTransactionManager_BeginCommitRoundTrip** | — |
| `shutdown` | S4LifecycleTest (+ idempotency) | post-shutdown calls throw: S4Lifecycle |
| `getVersion` | **Audit.getVersion_ReturnsLibraryVersion** + XdStorageVersionTest | — |
| `registerStructureUpdater` | XdStorageSchemaEvolutionTest | — |
| `executeStructureUpdate` | XdStorageSchemaEvolutionTest (ScriptedUpdater incl. retry-failure) | **Audit.executeStructureUpdate_OutsideTransaction_FailsLoudly** |
| `load(cl, indexName, query[, tx])` | SearchEngineIntegrationTest, SearchRangeReopenTest | unknown index: SearchManagerTest ("requred index" — see task 39) |
| `count(cl, indexName, query[, tx])` | QueryShapingTest (count == load.size), SearchRangeReopenTest | same unknown-index path |
| `watch(cl, watcher[, tx])` | **Audit.watch_Facade_FeedsSnapshotObjects** + SearchManager/ResourceCache watch suites | callback throw fails the scan (SearchManager contract) |
| `Provider.newOrGetFileStorage/*` | ProviderAndPoliciesTest (idempotent same-name pool) | S3FolderLock (folder-locked), FormatMeta (format refusal) |
| `Provider.verify(String/File/Path)` | XdStorageVerifyTest | **Audit.verify_FileAndPathOverloads_MatchTheStringForm** (overloads agree) |
| `Provider.getVersion` | **Audit.getVersion** + XdStorageVersionTest | — |

### ⚠ Audit finding (filed, not fixed — T-3b rule)

**`load(Class, id)` returns `null` for a missing id — the javadoc says it throws.**
`XdStorage.load(Class, Object, tx)` returns whatever `resource.read(id, tx)` produces
(`null` on a cache/index miss, both Hash and BTree paths), while load-by-REFERENCE throws
"cannot load by reference" for the same situation. The task-28 javadoc (written for the
covered contract) documents the throwing semantics for BOTH load families — wrong for
load-by-id. Pinned as observed by
**`XdStorageFacadeMethodAuditTest.load_MissingId_ReturnsNull_AuditFinding`**. The
alignment decision (make load-by-id throw = behavior change; or fix the javadoc = docs
change) belongs to the owner; until then the javadoc overpromises.

### Landed: the litter cleanup

- **`/temp` deleted** — `SpecificTest` was a `main()`-only scratch class (no `@Test`;
  surefire ran zero tests from it), `Entity`/`EntityWrapper` were used only by it.
- **`/updaters` deleted** — `XdStarSystemUpdateNameMovedToAnotherField` was referenced by
  nothing; the structure-evolution suite carries its own `ScriptedUpdater` fixture.
- **`/operation` kept** — the 9 operations are the live stress-test fixtures.
- **`entities` was never empty** (13 fixture files — the task premise predated the fixture
  reorganization); `entities/map` likewise.
- **`/performance/package-info.java`** documents the four content kinds (stress-tagged
  families, genuine unit tests, the GraphFlowDiagnostic repro, and the surefire-invisible
  benchmarks/sweeps with their runner script); `GraphFlowDiagnosticTest`'s javadoc now
  says what it diagnoses and when to run it.

## Verification

```bash
git diff --stat main...HEAD -- src/main/java   # must be empty
mvn clean test && mvn clean test
```

## References

- [MASTER-PLAN.md](../../MASTER-PLAN.md) § Track T-3
- Tasks 04 (map), 05 (tags), 06 (package suites this builds on)

Estimate: ~1 dev-day. Depends on: 04, 05; partially 06.
