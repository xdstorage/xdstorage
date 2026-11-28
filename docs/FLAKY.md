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

## Excluded by decision (2026-10-26, maintainer) — RE-INCLUDED 2026-11-21

**Both classes run again in every suite — the default run, `-Pquick-gate` and the nightly —
since `59ff544` (TREEOVERLAY, task 53: "stress family 6/7 green") removed their `<exclude>`
entries from the pom.** The rows below keep the decision and what was known then; any text
elsewhere that still calls them excluded is stale. Observed on one machine on 2026-11-27,
full-suite runs: `XdStorageFacadeStressTest` 26/26 green;
`XdStorageNestedTransactionIsolationStressTest` 25 green, 1 red — in the one run that shared
the box with a second Maven build — and 10/10 isolated on `main`.

| Test class | Signature | Decision |
|------------|-----------|----------|
| `XdStorageNestedTransactionIsolationStressTest` (@Tag stress) | the nested commit/rollback machinery is critical under load (latch cascades, zombie sweeps) | excluded 2026-11-08 (2c43b8c) → **re-included 2026-11-21 (`59ff544`)**. 2.2.0 task 12 closed one more gap (a nested transaction running on another thread is swept after 5 s instead of holding its parent for the whole timeout); the feature's NOT RECOMMENDED status is the maintainer's call (EMBEDDING/GUIDE/CHANGELOG) |
| `XdStorageFacadeStressTest` (@Tag stress) | the facade load flow: the historical families (hot-node latches, retry budgets, validator divergence) | excluded 2026-11-08 (2c43b8c) → **re-included 2026-11-21 (`59ff544`)**; 2.2.0 task 13 stays the record of the load investigation |

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
- **Gate policy (at the time):** `FacadeStress` and `NestedIsolation` were excluded from
  the gate/default run per the status decision 2c43b8c (the throughput envelope, R-STORM-
  ENVELOPE above); their correctness coverage lives in the invariant tests; the envelope
  belongs to 2.2.0 task 10. **Superseded 2026-11-21 (`59ff544`): both are back in every
  suite — see "Excluded by decision" above.**
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

## 2026-11-28: XdStorageRollbackOnlyCauseTest — the capture list read while a worker appended (FIXED)

- **Signature (CI, `main` `22ce727` — the first run of the merged `parallel-work`, job
  `windows`):** `warnLoggedOncePerTransaction_RepeatMarksNotLogged:358 »
  ConcurrentModificationException` — 1 error in 1746; `gate` and `jdk21` green on the
  same commit. Never seen on Linux.
- **Root cause:** a race inside the one test, not between tests, and not OS-specific.
  The test iterated the appender's `ArrayList` with no lock, while the appender appends
  under one. And the appender is offered more than it is asked for:
  `config.getLoggerConfig("…transaction.XdStorageTransaction")` returns the nearest
  CONFIGURED logger — `io.github.xdstorage`, the whole engine — so every WARN of every
  thread reaches the list. One does, every time, from another thread: the first
  `markRollbackOnly` arms the asynchronous rollback sweeper
  (`XdStorageTransactionManager.registerRollbackOnlyTransaction`), whose WARN is logged
  from `xdstorage-worker-1` a moment after the mark. Measured in a diagnostic copy with
  the window widened to 1.5 s: event 0 — the mark, on the test thread; event 1 — the
  sweeper, on the worker, 12 ms later. The test's loop is a handful of statements; for
  that append to land inside it the test thread must be preempted right there — which the
  Windows runner did once. The neighbouring class
  was suspected first (Windows orders classes alphabetically) and ruled out by the same
  diagnostic: no foreign thread at the class boundary, no foreign event in the window.
- **Fix:** the appender keeps the transaction logger's events only; the list is read
  through `snapshot()` under the appender's lock; the test counts the events of ITS
  transaction (logger name + transaction id).

  **The proof.** The natural timing reproduces nothing on Linux — the OLD method, 6000
  repetitions under CPU load (48 spinning threads on 24 cores, `-Xint`), 0 hits — so the
  interleaving was forced: a diagnostic copy pauses the test thread for 50 ms right after
  the iterator over the captured events is created, the pause the Windows runner imposed
  once. Old loop: `ConcurrentModificationException` in **199 of 200** (in the 200th the
  cold first worker had not logged yet). Fixed loop, same pause: **0 of 200**. The red is
  the CI run itself; the pair shows the fix removes exactly that failure.

## 2026-11-28 (later): XdStorageBackupUnderLoadStressTest — NOT a flake, an engine defect (FIXED)

- **Signature (CI, `main` `8d34020` — the second fix merged, job `windows`):**
  `theCopyHoldsWholePairsAndTheSourceReopens:117` — "the copy holds 15 pair(s), but 16 had
  committed when backupTo returned"; 1 failure in 1746; `gate` and `jdk21` green on the
  same commit.
- **It is not a flake, and reading it as one would have hidden a data loss.** The
  shutdown checkpoint of a DATA resource folded its journal under the append lock only.
  A commit of another thread that was between its phases — the redo appended, nothing
  published — was in neither the base the fold wrote nor the journal it deleted: the
  commit returned success and the record was gone at the next open. `checkpoint()`,
  `shutdown()` and `backupTo` all run that fold. The window is the other resources'
  phase-1 appends of the same commit: I/O, so wider on the Windows runner.
- **Deterministic on Linux, with no seam in the engine:**
  `XdStorageShutdownCheckpointRaceTest` parks the committing thread inside its id-index
  append by holding that journal's monitor. Before the fix: the record gone after the
  reopen; `shutdown()` returned under the commit; `transaction.commit()` after the
  shutdown wrote 368 bytes into the journal of a shut storage. After: 3/3 green.
- **Fix:** task 51 — the data resource takes its commit lock before the fold (as the
  index resources always did), the shutdown refuses new writes and waits for the ones in
  flight, and the transaction's own `commit()` is refused after the shutdown too.

## 2026-11-28 (later): two stress reds in the SHUTCKPT gate — one race in the lock's own timeout message (FIXED)

- **Signature.** Two reds in 8 in-suite runs on the task-51 tree, in two ledgered
  contention families: `XdStorageBTreeTargetedDeleteStressTest` — "Конфликт блокировок MVCC:
  … Превышен таймаут ожидания: 100 мс" from a READER thread (`Write-lock acquisition failed
  after 103 ms (holder thread: null …)`); `XdStorageNestedTransactionIsolationStressTest` —
  "Крах транзакционного контракта вложенности" wrapping
  `NullPointerException: Cannot invoke "java.lang.Thread.getName()" because
  "this.writeLockThread" is null`, at `XdStorageReadWriteLock.describeLockState:239`.
- **The cause, found in the lock, not in task 51.** The holder field of
  `XdStorageReadWriteLock` is volatile. The timeout message read it TWICE — "not null? then
  `getName()`" — and the holder released the lock between the two reads: what should have
  been the lock conflict (`XdStorageRuntimeException`, which both storms classify as
  backpressure) left as a `NullPointerException`, which they classify as an anomaly. The
  same NPE, from the other message (`lockRead:224`), reddened a run of the top-5 wave on
  2026-11-24 (`t35-quickgate-1`, 1379/1f); the "holder thread: null" timeout line stands in
  green runs of every tree since the baseline `d1f36d1`. Task 51 touches no lock; in
  isolation the targeted-delete storm is 0/20 red on `main` and 0/20 on the task-51 tree.
- **Fix:** each message reads the holder once (`final Thread holder = writeLockThread;`).
  Not pinned: a check-then-act on a volatile field has no seam, and a probabilistic pin
  would be a flake by construction.
- **What remains is the ledgered family itself:** under a full suite the 100 ms MVCC budget
  of a hot record times out — backpressure, counted, not red. And read this before trusting
  the targeted-delete storm's green: in every run of this session, on every tree,
  "Успешных точечных удалений пар: 0" — each delete is refused as a concurrent modification
  and skipped, so the test is a readers' storm around one hot record.
