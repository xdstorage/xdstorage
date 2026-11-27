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
| `XdStorageBTreeStressTest` | merge/split-storm anomalies under FULL-SUITE load: RW-lock hold-and-wait stalls (writeCount=2 holder starved 5s; R-56 diagnostics also captured a mutator inside the mutation section while another thread held the tree write lock) | ~1 per full-suite run; 0 in isolated 10× gate | R-29 lock family (wait-rescheduling approaches exhausted, see R-26/R-28/R-29); R-56 diagnostics | **RESOLVED 2026-11-21 — the TREEOVERLAY program (task 53)**: the root cause was an ACQUISITION DEADLOCK (the INDEX-band tie order arbitrary per tx), fixed by the total acquisition order; the concurrent per-tx overlay staging fixed the phantoms (0 vs the stash-verified HEAD baseline 24810); green ×3 in the final gate. The LATENT remainder (the R-56 multi-scan walk race — the latch-held-walk experiment failed and reverted; the fix = the version-validated retry / snapshot-leaf chain) folds into 2.2.0 tasks 01/03 as a pinning target (MASTER-PLAN item 20) |
| `XdStoragePhantomReadIsolationStressTest` | read-lock contention under full-suite load | flaky in-suite only | R-13, R-18, R-58c | known family, stable green in the 10× gate |
| `XmlDataStorageMultithreadsTest` | concurrent purge vs rolled-back tx in afterEach | flaky ~1/3 in-suite (fixed) | R-18, R-21, R-31, R-57, R-58 | **FIXED** (5/5 + gate 10/10) |

## Under contention watch (2026-10-23, post-FK commits)

| Test class | Signature | Status |
|------------|-----------|--------|
| `XdStorageBTreeTargetedDeleteStressTest` (@Tag stress) | targeted deletes under heavy concurrency collect lock-deadlock/structure-anomaly exceptions on a single hot B+Tree | fixed-2026-11-01 (task 42 session): the `IllegalMonitorStateException` storm (unguarded unlocks in `findChildAndDeletePair` after mid-descent rebalances) is fixed with R-33 guarded unlocks; the worker filter tolerates the two transient races ("does not exists", first-committer-wins on the hot tree-record re-save). Residual (~1/3 runs under full-suite load): the DESIGNED `REBALANCE_RETRY_BUDGET` exhaustion (10 attempts) on one hot subtree — livelock containment, not corruption; retry-budget tuning is the open question. 2026-11-23: 1 failure / 2 full-suite runs (1 success between them; isolated ×3 green) — within the documented band; the insert retry budget moved to the R-54 deadline+backoff the same day (task 33), which may shift this test's contention profile — watch the rate |
| `BTreeResourceCacheIntegrationTest.testIntegration_HeavyParallelLoad_NoDeadlocksOrTimeouts` (@Tag integration) | 15 workers on one index tree: retry-budget exhaustion + latch timeouts on the hot node (id 15) | open — same family; the failures are lock-contention backpressure, no structure corruption reported. **Green ×3 in the 2.1.0 RC protocol (2026-11-22, the full suite incl. this class)** — the row stays open as the load-specific watch, not a release blocker |
| `XdStorageBTreeDuplicateInvariantTest.testConcurrentDuplicateInsertAndTargetedDeleteIsThreadSafe` | the concurrent targeted delete of a VERIFIED-EXISTING duplicate pair threw `"does not exists"` (hot_0/payload_0_6) — the silent-wrong-result class under full parallelism (200 deletes / 4 hot keys) | 1 occurrence on the CI 2-vCPU runner (2026-11-23, the gate leg; **the jdk21 leg passed the identical 1358 suite simultaneously** — the race evidence); 0/3 locally on the 24-thread box | **open — task 32 DUPRACE** (the targeted-delete descent race; the deterministic repro required, the probabilistic CI run cannot gate) |

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

## 2026-11-08 (evening): BTreeResourceCacheIntegrationTest — the hot-key budget vs its own spec (FIXED)

- **Signature (CI 425d7e6):** 8 workers failed with "transaction ... should be rolled
  back by timeout. Object with id 13 is locked for update" — the 12-wave collision on
  one key (`(workerId + j) % 100 == 12`) exhausting a **100 ms** lock budget on the 2-core
  runner.
- **Root cause:** the test's own budget/contract mismatch — the comment specifies
  "промышленный таймаут в 15 000 мс для стабильного прохождения пиковой ForkJoin
  нагрузки" while the code passed `100L`. The lazy timeout enforcement worked as
  designed.
- **Fix:** the budget corrected to the documented 15000 ms (the queue of a 12-deep
  hot-key collision plus the disk spikes fits comfortably). Green ×3 isolated,
  957/0/0 ×2 full suites.

## 2026-11-27: CrashAndPurge — one red in the top-5 wave's gate, NOT attributed (task 08)

- **Signature (quick-gate run 2 of 3 on the task-08 tree, a quiet box):**
  `XdStorageCrashAndPurgeStressTest.testScenario1_PowerLossAndSubsequentPurge` —
  "Утечка! Найдены остаточные XdGalaxy!" after a purge that skipped **84**
  subtrees/records as "crash residue"; every skip was
  `XdStorageException: cannot load by reference object of class …XdPlanet…SimpleWrapper
  with id N` — a dangling child reference left by the hard `shutdownNow()` cut (the
  mechanism of task 2.1.0/35). Runs 1 and 3 of the same gate skipped 3 and 0 and passed.
- **The residue is timing-dependent, and the failure sat at a level no other run
  reached:** in every other full-suite run of this wave the skip count was ≤ 28 (13 runs
  on the tree WITHOUT task 08: 0–28; 2 runs with it: 3 and 0); the failing run skipped
  84 in three purge rounds.
- **Not reproduced in isolation, on either tree:** 15/15 passes WITHOUT task 08
  (`850ab69`, skips 0–30) and 15/15 WITH it (0–22); pinned to one CPU core — more
  residue — 3/3 and 3/3 (skips up to 45 and 35).
- **Status — NOT A/B-proven pre-existing, and not shown to be a regression.** By code
  reading the purge path (the reference loads, the lazy-load rollback-only mark) is
  untouched by task 08, and the wrapper classes load and work in every run; the only
  evidence of a difference is that the single red fell inside 3 runs on the new tree
  against 0 in 13 on the old one — not significant at these counts. What would settle
  it: the nightly ×3 rate watch, or a reproduction of the ≥ 80-skip residue outside the
  full suite.
- **Not quarantined.** The scenario is the release-gate proof that a post-crash purge
  returns the folder to empty; a red here must be read (which class stayed, how many
  skips), not retried blind.
