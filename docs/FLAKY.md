# Known flaky test families (quarantine ledger)

The full suite is the default gate (`mvn clean test`). The families below are **documented,
rare flakes under peak load** — each has been A/B-verified as pre-existing and characterized in
[OPTIMIZATION-PLAN.md](OPTIMIZATION-PLAN.md) (R-54..R-58 session, 2026-10-19). They are NOT quarantined from
the default run; `-Pquick-gate` excludes them for a fast low-noise gate, and the nightly CI job
tracks their flake rate (full suite ×3).

## Session 2026-10-19 (Track A): the facade-storm family is FIXED

The `XdStorageFacadeStressTest` "рассинхрон структуры" family (formerly ~1/2 isolated, A/B-proven
pre-existing) is **closed**: the R-32 validator was made sample-consistent (structureVersion /
activeMutations sampling, R-56), the resource-eviction parse churn was eliminated (R-55), the
find retry budget became time-based (R-54), the YAML writer got the missing CME snapshots, and
the rollback paths got compensating index-key deletion (R-57) plus the committed-record rollback
guard (R-58). Verified: 13+ consecutive isolated greens, and a 10/10 green gate of ALL stress
tests (20 classes × 10 consecutive surefire passes, 2026-10-19 night).

| Test class | Family / signature | Rate | Evidence | Status |
|------------|--------------------|------|----------|--------|
| `XdStorageGraphPhantomReadStressTest` | readers crashed on references their snapshot legitimately excluded: `readByReferenceInternal` hydrated with `View.SCAN` (live state), so snapshot readers walked collections containing not-yet-visible phantoms | ~1/11 isolated (fixed) | R-45 → R-58 (View.BY_ID hydration + invisible-reference skip in the generated collection hydration and `XdStorage.load(Collection, tx)`) | **FIXED** (5/5 + gate 10/10) |
| `XdStorageFacadeStressTest` | "рассинхрон структуры" validator divergence / find-budget deaths / CME on write-out | ~1/2 isolated (fixed) | R-54, R-55, R-56, R-57, R-58; OPTIMIZATION-PLAN §5 | **FIXED** (13+ isolated greens; gate 10/10) |
| `XdStorageBTreeStressTest` | merge/split-storm anomalies under FULL-SUITE load: RW-lock hold-and-wait stalls (writeCount=2 holder starved 5s; R-56 diagnostics also captured a mutator inside the mutation section while another thread held the tree write lock) | ~1 per full-suite run; 0 in isolated 10× gate | R-29 lock family (wait-rescheduling approaches exhausted, see R-26/R-28/R-29); R-56 diagnostics | **open — Track B3** (contention removal); tolerated as backpressure in isolated runs |
| `XdStoragePhantomReadIsolationStressTest` | read-lock contention under full-suite load | flaky in-suite only | R-13, R-18, R-58c | known family, stable green in the 10× gate |
| `XmlDataStorageMultithreadsTest` | concurrent purge vs rolled-back tx in afterEach | flaky ~1/3 in-suite (fixed) | R-18, R-21, R-31, R-57, R-58 | **FIXED** (5/5 + gate 10/10) |

## Under contention watch (2026-10-23, post-FK commits)

| Test class | Signature | Status |
|------------|-----------|--------|
| `XdStorageBTreeTargetedDeleteStressTest` (@Tag stress) | targeted deletes under heavy concurrency collect lock-deadlock/structure-anomaly exceptions on a single hot B+Tree | fixed-2026-11-01 (task 42 session): the `IllegalMonitorStateException` storm (unguarded unlocks in `findChildAndDeletePair` after mid-descent rebalances) is fixed with R-33 guarded unlocks; the worker filter tolerates the two transient races ("does not exists", first-committer-wins on the hot tree-record re-save). Residual (~1/3 runs under full-suite load): the DESIGNED `REBALANCE_RETRY_BUDGET` exhaustion (10 attempts) on one hot subtree — livelock containment, not corruption; retry-budget tuning is the open question |
| `BTreeResourceCacheIntegrationTest.testIntegration_HeavyParallelLoad_NoDeadlocksOrTimeouts` (@Tag integration) | 15 workers on one index tree: retry-budget exhaustion + latch timeouts on the hot node (id 15) | open — same family; the failures are lock-contention backpressure, no structure corruption reported |

## Excluded by decision (2026-10-26, maintainer)

| Test class | Signature | Decision |
|------------|-----------|----------|
| `XdStorageNestedTransactionIsolationStressTest` (@Tag stress) | the nested commit/rollback machinery is critical under load (latch cascades, zombie sweeps) | excluded from the gate/release profiles **and the nightly** (ci.yml `-Dtest='!…'`); rework is OPTIONAL 2.2.0 task 12 (fix or deprecate); the feature is documented NOT RECOMMENDED (EMBEDDING/GUIDE/CHANGELOG) |
| `XdStorageFacadeStressTest` (@Tag stress) | the facade load flow: the historical families (hot-node latches, retry budgets, validator divergence) | excluded from the gate/release profiles **and the nightly**; 2.2.0 task 13 = the failure-cause investigation + the performance improvement plan |

## Conventions

- A run is judged by Maven's own `BUILD SUCCESS`/`Tests run: N, Failures: M` line — **never by a
  piped grep's exit code** (the R-52 gotcha: `mvn test | grep ERROR; echo $?` reports grep's status
  and a failed build masqueraded as success until launch).
- **T-2 grouping contract** (docs/tasks/2.1.0/05): a stress class is BOTH named per convention
  (`*StressTest`, or the legacy-named `XmlDataStorageMultithreadsTest`) AND carries
  `@Tag("stress")`; integration classes live in the `integration` package AND carry
  `@Tag("integration")`. The pairing is enforced in both directions by
  `XdStorageTestGroupingContractTest` in the default run; the `release` profile excludes by tag
  since 2.1.0 (filename patterns kept as a backstop until 3.0). The `quick-gate` profile stays
  name-based — its "documented flaky families only" contract cannot be expressed with the two
  structural tags.
- Storage name + directory must be **unique per test run** (R-50 stale-dir contamination):
  `File.delete` legally fails on IDE-held files → the storage parses stale disk state → confusing
  first-save failures. Never gate test logic on `deleteDir` success.
- JMH trials that fail are **silently skipped** — count the rows in the final table
  ([BENCHMARKS.md](BENCHMARKS.md) gotcha #1; the nightly CI job asserts 9 rows).
- Un-quarantine a family only after its R-entry records a fix with A/B evidence.
- Track-A gate (2026-10-19): **10/10 green consecutive runs of all 20 stress tests** +
  the full suite green except the in-suite BTreeStress tail (Track B3).

## 2026-11-08: the saturated-storm envelope (R-STORM-ENVELOPE, task 47's residuals)

- **Signature:** the worker lock-conflict/commit-lock-timeout failures in
  `XdStorageFacadeStressTest` (25×400 = 10,000 commits, 5 s budgets) and
  `XdStorageNestedTransactionIsolationStressTest` (8 workers, 100 ms budgets, one shared
  planet fragment); 2 failures/run, stable across runs.
- **Root cause:** the THROUGHPUT ENVELOPE, not the correctness defects — the per-record
  YAML floor (~20–50 ms/commit serialized on the shared index resources) and the
  commit-lock queue depth × the hold vs the tx budgets. The correctness families behind
  the historical reds (the disarmed phases, the zombies, the sweeper races, the barging
  starvation, the acquisition cascades) are FIXED (task 47); `Illegal state of record to
  commit` = 0 across the suites; BTreeStress green ×3.
- **Policy:** the release profile excludes the stress tag (2.1.0) — these belong to the
  nightly/perf gate (2.2.0 task 10). The FacadeStress's scale additionally assumes the
  "shadow buffer/flush orchestrator" subsystem referenced in its comments but not
  present in the source — implement that subsystem or rescale the test before expecting
  green in the default run.

## 2026-11-08 (evening): the stale-descent family FIXED (the TargetedDelete red + the BTreeStress flake)

- **Signature:** `XdStorageBTreeNode id N was deleted this transaction` /
  `trying to double delete one object ... N` in `BTreeTargetedDeleteStress`
  (deterministic 3/3 red); `IndexOutOfBoundsException: Index: 0, Size: 0` at
  `insertIntoThisNode` behind `BTreeStress`'s anomaly counter (the ~1/3 suite flake).
- **Root cause:** the worker's OWN earlier targeted-delete merge tombstoned a node's
  cache record and unlinked it from the in-memory graph, but a descent that started
  before the merge still held the link — its node-record update/delete hit the
  transaction's own tombstone (the insert path had no retry wiring at all; the delete
  path's rebalance borrow/join peers and the merge record-cleanup had the same
  exposure), and the leaf key list mutated between the position scan and the mutation
  produced the IndexOutOfBounds.
- **Fix (task 47's stale-descent pattern, estate-wide):** the tombstone hit on a node's
  own-transaction record sets the descent's retry flag (the tree-level loop re-descends
  from the root — the merge already unlinked the node, so the fresh descent lands in
  the live sibling); the merge record-cleanup is idempotent for same-transaction
  tombstones (the in-memory unlink is what matters); the leaf-list race caught as the
  same stale-descent signal.
- **Gate policy:** `FacadeStress` and `NestedIsolation` are excluded from the
  gate/default run per the status decision 2c43b8c (the throughput envelope, R-STORM-
  ENVELOPE above) — the pom's default surefire config now mirrors the quick-gate/
  nightly/release policy. Their correctness coverage lives in the invariant tests; the
  envelope belongs to 2.2.0 task 10.
- **Evidence:** BTreeTargetedDeleteStress 5/5 green; BTreeStress 8/8 green (isolated);
  the full gate (951 tests): **0 failures ×3 consecutive runs, BUILD SUCCESS ×3.**
