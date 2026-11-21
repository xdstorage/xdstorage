# Verification protocol — estates, gates, benchmarks

The verification hierarchy for engine work. Each level gates the next; a
release claim requires level 5 ×3.

## 1. The levels

| Level | What | Command | Green bar |
|---|---|---|---|
| 1 | compile | `mvn compile -Pquick-gate` | 0 errors |
| 2 | the single failing test (isolated) | `mvn test -Dtest="X#method" -Pquick-gate` | the repro/fix confirmed |
| 3 | the test class | `mvn test -Dtest="X" -Pquick-gate` | all green |
| 4 | the family (the FK estate / the suite) | `mvn test -Dtest="io.github.xdstorage.foreignkey.**.*Test"` | 372/0f/0e |
| 5 | **the full quick-gate** | `mvn test -Pquick-gate` | **1344+/0f/0e** (the count grows with each landed pin; the honest count of the day) |
| 6 | FKBENCH (the write-path claims) | `XdStorageFkContentionBenchmark -Dfkbench=true` | the target numbers |

## 2. The rules

1. **×3 for a release claim**: the flake families are probabilistic (the
   MassFillSecond survivors: 1-4 intermittent). ×1 green proves nothing.
2. **rm -rf target/surefire-reports** before aggregating (the stale reports
   lie — the proven trap).
3. **Never two maven builds on one target/**: the concurrent builds destroy
   class files → NoClassDefFoundError cascades that look like engine bugs.
   **Check `ps aux | grep surefire` BEFORE every launch**; the parallel agent
   sessions over one project are the recurring hazard.
4. **The full quick-gate for any ENGINE change**: the families (the caches,
   the indexes, the FK estate, MassFill) catch what the target tests miss —
   proven repeatedly (the MassFill convoy, the deferred-id regression).
5. **The benchmark before/after for any write-path claim**: FKBENCH (task 50)
   + the canonical estate (task 13, scripts/run-benchmarks.sh).
6. **ALWAYS run the tests with `timeout N`** (the maintainer's directive,
   2026-11-21): a hung run = the analysis material (the jstack at the hang,
   the stuck-test identification); the gate without a timeout hung 10+ minutes
   on the BTreeStress latch-wait family. `timeout 1500 mvn clean test ...`
   for the full gate; the per-test timeouts scale with the test's own
   duration bounds.
7. **The test hygiene**: no unbounded future.get()/awaitTermination in the
   tests — a stuck worker must fail LOUDLY at the timeout, not hang the gate
   (the BTreeStress fix: future.get(60s)).

## 3. The pinning-test discipline (the behaviors after a refactor)

Every fix lands with the tests that PIN the fixed behavior (they fail on the
reintroduction). The pinning map for the W-4/overlay program:

| Behavior | Pinning test |
|---|---|
| delete-child-alone veto | DeleteScenarios.testDeleteOnlyOneChildThrows (btree+hash) |
| dangling-ref end-state veto | UpdateScenarios #6 (btree+hash) |
| remove-from-collection ≠ delete | UpdateScenarios #4/#5 |
| deferred-ID: parent-first resolve / child-first veto / idempotent | FxForeignKeyDeferredIdResolverTest 5/5 |
| rollback restores all records (no survivors) | MassFillSecond audit check (0 survivors) |
| full collapse leaves no files / no resurrection | XdStorageBTreeFullCollapseTest |
| stale write rejected (first-committer-wins) | XdStorageFirstCommitterWinsTest |
| reopen key types | XdStorageForeignKeyReopenKeyTypeTest ×3 |
| overlay: veto-path undo | НОВЫЙ: veto → rollback → все записи/записи индексов восстановлены |
| overlay: internal-tx staging | НОВЫЙ: staging под internal id → apply на commit → данные целы |
| overlay: purge integrity under concurrency | MassFillFirst/Second БЕЗ semaphore (the permits neutered 1→64: landed 2026-11-21, ×3 survivors=0) |
| total acquisition order (the deadlock pin) | НОВЫЙ `XdStorageBTreeOverlayTest.concurrentCommits_OppositeResourceOrders_NoDeadlock` — 2 threads × 40 rounds over the two shared btree trees in the opposite orders → both commit, no lock timeouts |
| overlay visibility contract | `XdStorageBTreeOverlayTest.stagedInsert_VisibilityContract` — the own tx sees the staged pair, the foreign doesn't until the commit |
| rollback = discard | `XdStorageBTreeOverlayTest.rollback_DiscardsOverlay` + XdStorageTreeStructureRollbackTest 2/2 |

## 4. The known-flaky registry (do not chase without a repro)

| Test | The flake | The state |
|---|---|---|
| MassFillSecond survivors | task-40 canonical contamination (intermittent 1-4 systems) | documented; the read-snapshot prototype verified 0×5 (reverted with the overlay) |
| MassFillSecond deletes=0 | the commit convoy + the window | mitigated (warmup/backoff/sharding); the endgame = the overlay |
| The FK suite write-lock timeouts | the commit convoy (8 pre-existing errors historically) | the same convoy family |
| XdStorageTreeStructureRollbackTest | @Disabled RED by design | task 41; the overlay makes it green |

## 5. The benchmark protocol

- FKBENCH: `XdStorageFkContentionBenchmark -Dfkbench=true` — FK on/off ×
  1/5/10 threads, each worker owns a universe, serial warmup, 5s window, the
  in-window commits only.
- The canonical estate: scripts/run-benchmarks.sh (JMH, task 13).
- The claims: any number published in BENCHMARKS.md needs the full conditions
  protocol (the manifest, quiet-gate logging) — task 13's discipline.

## 6. The benchmark discipline (BENCHREFRESH 43 + FKBENCH 50, 2026-11-21/22)

1. **The canonical tables are GENERATED** (render-bench-baseline.py,
   render-fk-bench-table.py, render-bench-delta.py) — never hand-typed. The delta
   table's separator row MUST match the header's column count (a mismatch = the GFM
   table renders as raw text — the silent killer; count the pipes).
2. **An archived-vs-new delta is NOT a code delta.** The attribution method: re-run
   the OLD revision TODAY (a throwaway worktree + the same single-fork protocol); if
   the old rev reproduces its archive on shape X → a gap on X is code; if not → the
   archive was environment-flattered (drift). The drift is scenario-dependent (the
   allocation/GC-heavy batch shapes drift the most; the search-write shapes hold).
   Archive the attribution beside the result set (ATTRIBUTION.md).
3. **The nightly smoke shape is FROZEN**: the row-count assertions (9 canonical +
   14 search, ZERO FK rows) — a heavy-preload benchmark joins a DEDICATED invocation,
   never the smoke set (the FK benchmark's preload would add ~20 min).
4. **The manifest discloses the flags AND the machine profile** (CPU/RAM/fs — no
   hostnames); BENCH_JDK pins the JDK and the manifest records the version FROM THE
   BINARY THAT RUNS.
5. **A benchmark asserts its data** (the field-by-field integrity vs the
   deterministic model) — a measurement without the integrity gate proves nothing.
