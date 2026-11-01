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
