---
title: "[2.2.0][OSPATH] OS-dependent paths: audit, abstract, and test beyond Linux"
labels: [2.2.0-train, P1, engine, testing]
status: open (the audit, the platform statement and the Windows CI leg are in; three runs triaged — the first, 25 red, and the second, 1 red, all in the tests; the third, 1 red, the first ENGINE defect of the leg, fixed in task 51; the leg waits for a green run)
---

## Summary

Audit every OS-sensitive surface — file paths (case sensitivity, separators, the
`File`-based APIs), charset handling, file locking semantics, atomic-rename behavior,
`Files.walk` ordering assumptions, the folder-lock implementation — and add a Windows
CI leg (or a documented Windows-unsupported statement with the exact blockers).

## Why (discovery § First priority 8)

The engine is developed and CI-tested on Linux only; the atomic-rename, folder-lock and
charset behaviors differ materially on Windows/macOS. An embedder on Windows hits
differences the project has never run.

## Scope

1. The audit: every `File`/`Path`/charset/lock site classified (portable / POSIX-dependent
   / Windows-divergent).
2. The Windows CI leg (GitHub windows-latest) running the non-stress suite; failures
   triaged into fixes vs documented divergences.
3. Charset: the default-charset writes (`Charset.defaultCharset()` in the journal/writer)
   pinned to UTF-8 explicitly.
4. EMBEDDING.md gains a supported-platforms statement.

Estimate: ~1–2 sessions for audit + first Windows triage.

## Acceptance criteria

- [x] The audit: every File/Path/charset/lock site classified (portable / POSIX-dependent /
      Windows-divergent) — 88 API sites reviewed (the table below). The audit classifies
      API USE; the OS-semantics questions it cannot answer from the source are listed
      under "What the audit cannot settle" for the Windows triage.
- [ ] The Windows CI leg (GitHub windows-latest) running the non-stress suite — the
      `windows` job ran three times. 2026-11-27, commit `6240cad`: 1746 run, 2
      failures + 23 errors, every one of them in the tests, none in the engine — the
      triage is below and the fixes are in the tree. 2026-11-28, `main` `22ce727` (PR
      #111 merged): **1746 run, 0 failures, 1 error** — a race inside one test, also
      fixed ("The second run" below). 2026-11-28, `main` `8d34020` (that fix merged):
      **1746 run, 1 failure** — the first engine defect the leg found, the shutdown
      checkpoint cutting a commit between its phases ([51](51-shutdown-checkpoint-commit-race.md)),
      fixed ("The third run" below). It stays `continue-on-error` until a run is green.
      Done when that run is green.
- [x] Charset: no `Charset.defaultCharset()` and no default-charset `String`/reader/
      writer constructor in the engine (re-checked 2026-11-27); the one debug write that
      used `String.getBytes()` (`XdStorageClassGenerator`, the `xdstorage.dump.gencode`
      dump — which also wrote to a hard-coded `/tmp`) now writes UTF-8 into the temp
      directory.
- [x] EMBEDDING.md gains a supported-platforms statement — § Supported platforms, stating
      what has been RUN (Linux x86_64, Temurin 17 and 21) and what has not (macOS;
      Windows until the leg is green; Java 8–11 as runtimes).
- [x] CHANGELOG updated — under `[Unreleased]`, Changed.
- [x] Quick-gate ×3 green — on Linux, `clean test -Pquick-gate` **1746/0f/0e/7 skipped ×3, consecutive, BUILD SUCCESS**, 0 checkstyle violations, on a quiet box (2026-11-27, branch `parallel-work` — the six parallel-work branches merged; `main` is 1360). Repeated on the tree with the Windows fixes: `clean test -Pquick-gate`
      **1746/0f/0e/7 ×3, consecutive, BUILD SUCCESS**, 0 checkstyle violations, and
      `clean verify` once green (03:56). Once more after the second run's fix: **1746/0f/0e/7
      ×3, consecutive**, `clean verify` green, and one run in the Windows class order
      (`-Dsurefire.runOrder=alphabetical`) 1746/0/0/7. The Windows run is the line above.

## Audit results (2026-11-25, re-checked 2026-11-27)

### Classified sites (88 total)

| Site | Classification | Windows-impact | Fix needed |
|------|---------------|----------------|------------|
| `File.separatorChar` for classpath (3 sites) | Portable (Java API) | None | None |
| `Files.move(..., ATOMIC_MOVE)` (2 sites) | Windows-divergent | Falls back to REPLACE_EXISTING | Already has fallback |
| `new File(parent, name)` (15 sites) | Portable (Path resolution) | None | None |
| `StandardCharsets.UTF_8` (16 sites) | Portable | None | Already explicit |
| `Files.walk(folder)` (1 site) | Portable (ordering via sort) | None | None |
| `FileLock.tryLock()` (1 site) | Portable (POSIX + Windows) | None | None |
| `FileChannel.write(UTF-8)` (2 sites) | Portable | None | None |
| `FileOutputStream`/`FileInputStream` (20 sites) | Portable | None | None |
| `Files.exists()/delete()/createDirectories()` (15 sites) | Portable | None | None |
| `File.pathSeparator` (2 sites) | Portable (Java API) | None | None |
| `String.replace(File.separatorChar, '/')` (1 site) | Portable (normalization) | None | None |
| `System.lineSeparator()` in lock file (1 site) | Portable (diagnostic only) | None | None |

### No `Charset.defaultCharset()` usage found — all writes use `StandardCharsets.UTF-8`.
### No `Files.walk` ordering assumptions that affect correctness (engine-owned ASCII paths, sorted).
### `ATOMIC_MOVE` fallback verified: `REPLACE_EXISTING` + non-durable rename in both locations.
### `FileLock.tryLock()` works identically on POSIX + Windows (Java NIO abstraction).

### What the audit cannot settle (for the Windows triage)

Reading the source cannot tell how Windows behaves at run time. What the leg's first
run should be read for — hypotheses, in the order they would bite:

1. **Replacing a file another handle holds open.** POSIX lets `rename` replace an open
   file; Windows refuses it (a sharing violation — an `IOException`, not the
   `AtomicMoveNotSupportedException` the write-out catches). The engine's reads are
   short-lived, so the window is narrow, but a commit racing a read can fail there.
2. **Path length.** `data/<fully.qualified.ClassName>/<Class>-<id>.yaml` plus the
   `journal/` sub-directory can pass Windows' 260-character limit for a deep package
   and a long id.
3. **Case-insensitive names.** Two classes, fields or index names that differ only in
   case share a file on Windows.
4. **Reserved device names.** An index named `aux`, `con`, `nul` … becomes a file name.
5. **Payload paths.** The id-index stores fragment paths verbatim — a folder moved
   between operating systems carries the other system's separators (already recorded in
   EMBEDDING § Upgrade from 2.0.0, limitation 3).
6. **Line endings in the format.** Whether every writer emits `\n` (and every reader
   accepts `\r\n`) — the leg shows it as a sentinel or round-trip failure.

**What the first run said (2026-11-27):** none of the six produced a failure. 1 is the
one still open — the leg runs the non-stress suite only, so a commit racing a read on
Windows is unexercised; 2–4 were not hit because the suite has no such name (a risk for
an embedder, unchanged); 5 is unchanged; 6 is answered for every writer/reader pair the
suite exercises.

## The first Windows run (2026-11-27) — the triage

Commit `6240cad`, job `windows` (`mvn clean test -Pquick-gate`, 05:13): **Tests run: 1746,
Failures: 2, Errors: 23, Skipped: 7.** The `gate` and `jdk21` jobs on the same commit:
1746/0/0/7 each. **All 25 are defects of the tests, none of the engine** — three habits
that Linux never punishes.

| Red | Tests | What Windows said | Cause | Fix |
|-----|-------|-------------------|-------|-----|
| 21 errors | `XdStorageIndexJournalTest` (3), `XdStorageJournalNamingTest` (5), `XdStoragePowerLossRecoveryMatrixTest` (8), `XdStorageVerifyTest` (2), `XdStorageW1JournalTest.testTornTailDiscarded`, `PropJournalEquivalenceTest.replayCheckpointAndDirectPaths_AgreeWithTheModel`, `PropCoreContractsTest.fkVetoAndLegalCycle_HoldUnderRandomShapes` | an `IOException` out of `Files.copy` / `readAllBytes` in a folder walk (`… » IO The p…`, `… » FileSystem C…` — the summary cuts it) | the tests build their crash proxy by copying the folder **while the storage is open**, `.xdstorage.lock` included. The engine holds that file under an OS lock: mandatory on Windows — no other handle reads it; advisory on POSIX — the read succeeds. | one test helper, `StorageFolderCopy.copy(from, to)`: the copy leaves the lock file out (the nine call sites in six classes); the file-truth dump of `PropCoreContractsTest` skips it too |
| 1 failure + 2 errors | `XdStorageJournalNamingTest.journalNameIsContentMatchedForEveryBaseExtension`, `XdStorageMultiLineStringReopenTest` (2) | `expected: </db/data/Order/…> but was: <\db\data\Order\…>`; `no data base file under …` | a literal `/` spelled as the separator — in an expected string, and in a `contains("/data/")` over a path that says `\` | the expectation built the way the engine builds it, as a `Path`; the path normalized before the `contains` |
| 1 failure — a cascade of the 21 | `XdStorageS4LifecycleTest.testDaemonWorkerThreadsOption` | `worker threads must be daemon in daemon mode ==> expected: <true> but was: <false>` | the test judged EVERY thread of the JVM named `xdstorage-worker-*`. The names are per storage (`xdstorage-worker-1` in each), and the 21 tests above died before their `storage.shutdown()` — their storages stayed open, non-daemon workers alive. Windows orders the classes differently from Linux; the cascade itself is not OS-specific (below). | the thread judged is the one that ran THIS storage's own task |

### The proof, on Linux

No Windows machine is at hand for development, so the mechanism was reproduced rather than
guessed. A throwaway copy of the tree with one patch — never committed — makes the held lock
file unreadable (`setReadable(false)` at acquire, restored at release): the mandatory lock,
spelled in permission bits. The same patch prints, when the forked JVM exits, what the suite
left open.

| Copy | Result | Left open at JVM exit |
|---|---|---|
| `6240cad` + the emulation | **1746 run, 2 failures, 21 errors** — the same 21 lock-file tests as on Windows, plus the S-4 failure | 54 non-daemon workers, 10 folder locks: the storages of the failed tests (`legacy`, `idx-jrnl`, `sibling`, `both`, `live-torn`, …) |
| the fixes + the emulation | **1746 run, 1 failure, 0 errors** — the 21 are gone | 3 workers, 1 lock: the storage of the one test the emulation itself breaks |
| the fixes, no emulation, the probe only | **1746/0/0/7, BUILD SUCCESS** | **0 workers, 0 locks** |

The S-4 cascade is visible in the Linux order of the first row: `XdStorageJournalNamingTest`
(#205), `XdStorageIndexJournalTest` (#206) and `XdStorageW1JournalTest` (#209) fail before
their `shutdown()`, and `XdStorageS4LifecycleTest` runs immediately after it (#210).

The one failure left under the emulation is the emulation's, not the tree's:
`XdStorageVerifyTest.verifyWhileOpenReportsInUse`. `verify()` probes the lock by opening the
file read-write and trying the lock (`XdStorageFolderVerifier.probeFolderLock`); Windows
allows that open and refuses only the read, permission bits cannot say the same — so the
probe reports "not in use". The test passed on the real Windows run. A second variant of the
emulation (write-only instead of no access) behaves identically.

### Measurement traps met

- **The job log is a tail.** The step prints the last 100 lines of the Maven log, and
  surefire's `Results:` block cuts every message after a few words. The full text is the
  `mvn-log-windows` artifact, next to the surefire reports — the job-log archive alone
  does not carry it.
- **`mvn checkstyle:check` from the command line** (once `generate-test-sources` has run)
  also scans `target/generated-test-sources`: 2539 violations of generated JMH code. The
  gate is the lifecycle execution at `validate` — the FIRST `You have N Checkstyle
  violations` line of the build.
- **`failOnViolation=false`:** exit code 0 says nothing; the count is the verdict.
- **An emulation is exactly as true as its model.** The residual red above was read (and
  its cause found in the engine's own probe), not waved off as noise.

### The second run (`main` `22ce727`, 2026-11-28) — 1 red, a race inside one test

Job `windows` on the merge of PR #111: **Tests run: 1746, Failures: 0, Errors: 1, Skipped:
7** — the 25 are gone. `gate` and `jdk21` on the same commit: 1746/0/0/7. The one error
was not in the first run's list because it is a race, not a Windows habit:
`XdStorageRollbackOnlyCauseTest.warnLoggedOncePerTransaction_RepeatMarksNotLogged:358 »
ConcurrentModificationException`. The test read its list of captured log events while the
storage's own sweeper — armed by the test's first `markRollbackOnly` — was appending to it
from a worker thread; the appender sat on the whole engine's logger config, not the
transaction logger's (`getLoggerConfig` returns the nearest CONFIGURED logger). The full
write-up, with the diagnostic that found it, is in FLAKY.md (2026-11-28). The fix is
test-side again; the engine is untouched.

What this run does not say: that the race is Windows-only. The same interleaving exists
on Linux — the test thread must be preempted inside a loop of a handful of statements —
and a slower runner only widens the window.

Traps of this round:

- **A red new to the second run is not new code.** With the 25 out of the way, a race that
  was there all along got its first chance to be the only red.
- **The class order is the file system's.** surefire's default `runOrder` is `filesystem`:
  what `listFiles` returns — sorted on NTFS, hash order on ext4 (the Linux order was read
  off a run; the Windows one is NTFS's documented enumeration, not read off a report).
  `-Dsurefire.runOrder=alphabetical` reproduces it on Linux — which here ruled the
  neighbouring class out instead of in.
- **A harness must print its own verdict.** Two stress runs through surefire (the old test
  method, 6000 repetitions under CPU load) ended with the fork exiting, code 0, around
  repetition 5830 and surefire reporting `Tests run: 0` — no per-repetition result at all
  (not investigated: a throwaway harness, 50 MB of stdout). The third ran outside surefire,
  on the test classpath, and printed its own count.
- **A rare race needs its interleaving forced, not just its stress repeated.** 6000
  repetitions under load hit nothing on Linux; a 50 ms pause inserted where the Windows
  runner paused the thread made the old loop fail 199 times out of 200 (FLAKY.md, 2026-11-28).

### The third run (`main` `8d34020`, 2026-11-28) — 1 red, the first engine defect

Job `windows` on the merge of the second fix: **Tests run: 1746, Failures: 1, Errors: 0,
Skipped: 7** — the rollback-only race stayed fixed. The one failure:
`XdStorageBackupUnderLoadStressTest.theCopyHoldsWholePairsAndTheSourceReopens:117` — "the
copy holds 15 pair(s), but 16 had committed when backupTo returned".

This one is not a habit of the tests. The shutdown checkpoint of a data resource could
fold its journal while a commit of another thread was between its phases, and delete the
redo of a commit that then returned success — a committed record gone at the next open.
The slower runner widened a window that no Linux run had hit. The mechanism, the
deterministic reproduction and the fix are in
[task 51](51-shutdown-checkpoint-commit-race.md); why it must not be read as a flake is
in FLAKY.md.

What this run says about the leg: two runs paid for the portability of the tests, the
third found an engine race. Hypothesis 1 above ("replacing a file another handle holds
open") is still not what failed.

## Changes made

1. **EMBEDDING.md** — § Supported platforms: tested / a CI leg exists / not tested.
2. **ci.yml** — the `windows` job: the non-stress suite on `windows-latest`, under
   `shell: bash` with logs under `$RUNNER_TEMP` (the Windows runner's default shell is
   PowerShell and has no `/tmp`), `continue-on-error` until the first triage; the Maven
   log is uploaded next to the surefire reports.
3. **XdStorageClassGenerator** — the debug dump: UTF-8, the temp directory.
4. **CHANGELOG.md** — one entry under `[Unreleased]` (the earlier `## [2.2.0] — 2026-12-01`
   section, with a date that was not a release and two copies of the 2.1.3 fixes, is gone).
5. **The tests, after the first Windows run** — the helper
   `src/main/test/.../helpers/StorageFolderCopy.java` (a folder copy that leaves the lock
   file out) and the nine test classes of the triage table. `XdStorageFolderLock` and the
   rest of the engine are untouched: the run found no engine defect.
6. **EMBEDDING.md** — § Backup and restore said "the exclusive OS lock blocks same-machine
   copies" of an open folder. True on Windows only; on Linux and macOS the lock is advisory
   and the copy succeeds. The text says both now.
7. **The junit-tester skill** — R13 ("green on one OS is green on one OS") and the Windows
   facts under § Known environment facts, so the next test is written portable.
8. **`XdStorageRollbackOnlyCauseTest` (2026-11-28, after the second run)** — its capture
   appender keeps the transaction logger's events only, the list is read through a snapshot
   under the appender's lock, and the test counts the events of its own transaction.

## Not done

- A green Windows run. The second (`main` `22ce727`) proved the 25 fixes and failed in
  one more test, a race; the third (`main` `8d34020`) proved that fix and failed in the
  backup stress test — an engine race, task 51. Everything is proven on Linux (the
  emulation, the probe, the stress harness, the parked commit) and by reading; only a run
  proves it on Windows.
- `continue-on-error: true` on the `windows` job — drop it once that run is green.
- A macOS leg. The platform is listed as not tested; adding a leg is a CI-cost decision
  for the maintainer.
