# Embedding XdStorage — the contract

Operational contract for applications embedding XdStorage 2.0. Everything here is behavior of the
current code, stated so embedding surprises are design-time, not production-time.

## Lifecycle

- **Open** via `XdStorageProvider.newOrGetFileStorage(name, folder[, format][, XdStorageConfig])`.
  The provider keeps a per-JVM registry keyed by **name**: a repeat call with the same name returns
  the *existing* instance and **ignores the folder argument** (a WARN is logged). Call `shutdown()`
  to unregister before reopening under changed parameters.
- **The folder is exclusively locked** (`.xdstorage.lock`, OS file lock + same-JVM registry) for the
  storage's lifetime. A second opener — another process or another instance in this JVM — gets
  `XdStorageFolderLock.XdStorageInUseException` (unchecked; the provider API predates the lock and
  cannot add a checked exception).
- **`shutdown()` is idempotent.** After it every storage call throws
  `XdStorageRuntimeException("storage ... is shut down")`. In-flight transactions are NOT waited for —
  quiesce your workers before shutting down.
- **Threads:** a fixed pool (default 15) of non-daemon `xdstorage-worker-N` threads per storage.
  A forgotten `shutdown()` therefore blocks JVM exit. Options:
  `XdStorageConfig.builder().daemonWorkerThreads(true)` (workers can't block exit) and/or
  `.registerShutdownHook(true)` (best-effort close on JVM exit — does NOT substitute for daemon
  workers: hooks run only after the last non-daemon thread exits).
- **Timeouts are enforced lazily:** a timed-out transaction is actually rolled back when a lock wait,
  record latch, or the rollback-only sweeper trips — not by an independent expiry timer.

## Transactions and threads

- **Transactions are bound to the owning thread** (`getCurrentTransaction()` by thread identity).
  Every CRUD call without an explicit transaction uses the current thread's binding and throws
  `"method must be executed in transaction"` when absent.
- **Thread pools inherit bindings:** a pool thread that abandons its transaction leaves it attached —
  the next task on that thread sees it as "current". Always commit/rollback in a `finally`, or pass
  transactions explicitly.
- **One transaction may be used from many threads** (parallel streams): the engine counts active
  threads per transaction and defers rollback until they all leave.
- A failed CRUD operation throws but does NOT auto-rollback the transaction; wrapper-generated
  accessors and search errors do mark it rollback-only. Commit of a rollback-only transaction throws —
  and the failure **tells you who poisoned the transaction**: the first mark records its cause
  (`transaction.getRollbackOnlyCause()` — the marking site, the root exception, the transaction's age
  at the mark, the cascade source), the commit failure names the site and chains the root exception as
  its cause, and repeat marks are counted on the record instead of overwriting it.
- Nested transactions: `beginTransaction(parentTx, timeout)`; a nested rollback does not poison the
  parent (downward-only rollback-only cascade).

## JVM-wide static state (per-JVM, not per-storage)

- `XdStorageProvider` registry (name → instance).
- Wrapper-codegen caches (`XdStorageClassMetadataRegistry`, proxy compilation engine): per-class
  metadata computed once, never evicted — warm them once, share across storages.
- **`COMMIT_SEQUENCE` is a single global counter** shared by all storages in the JVM — required for
  consistent cross-storage MVCC ordering.
- The deferred-id observer registry is JVM-global and keyed by class.
- The generated-class loader is a static `URLClassLoader` over `./gencode` — **created relative to
  the process working directory at first use and pinned for the JVM's life**. App-server redeploys
  that expect classloader isolation will not work with engine-generated wrappers.

## Codegen requirements

- The engine generates wrapper classes **at runtime** via the system Java compiler: a **JDK is
  required at runtime — a JRE silently degrades to dummy wrappers** (broken equals/hashCode; an
  ERROR is logged once, the failure is cached for the JVM lifetime, there is no retry).
- The write target `./gencode/` must be **writable**; a read-only working directory has the same
  permanent-dummy-wrapper failure mode.
- Entity classes must be **top-level** (nested classes cannot be compiled on JDK 9+).

## Files and durability

- The storage folder is engine-owned: `structure/*.yaml`, `data/<Class>/*.yaml` (YAML default) plus
  `.xdstorage.lock` and the format marker `.xdstorage.meta`. Do not edit files while the storage is
  open.
- Default write-out is process-crash-safe (atomic rename), NOT power-loss-safe. Enable
  `XdStorageConfig.builder().durableWrites(true)` for fsync-per-commit power-loss safety.
- Resource files carry an EOF sentinel; a truncated file fails loudly at open. Pre-sentinel legacy
  storages: open once with `acceptMissingEofSentinel(true)` and let the first commit rewrite the files.
- Stale `*.tmp` files from a previous crash are swept at open.

### Cascade save and transactions

`@XdStorageAutoSave` on a parent's child field cascades an upsert of the annotated children
(collections, single references, arrays, and MAP values that carry an id-field) inside the
parent's save/update call — same transaction, same 2PC atomicity, child triggers and
observers fire as for explicit saves, and a failed cascade aborts the save. Removed children
are NOT deleted; re-save re-upserts every annotated child. Full contract:
The guide (docs/GUIDE.md) § Cascade save; executable proof: `XdStorageAutoSaveCascadeTest`.

### After a power loss

The default configuration is **process-crash safe (atomic rename), not power-loss safe** —
power loss additionally loses the OS page cache, so a "successfully written" file can come
back with its tail missing. The verified behavior matrix
(`XdStoragePowerLossRecoveryMatrixTest` — simulated by truncation, every cell pinned):

| Damage (simulated) | Outcome with defaults | Recovery |
|---|---|---|
| commit journal tail torn | **opens clean** (lost-tail WARN; records after the cut discarded by design) | none — recent commits since the cut are lost |
| index/tree metadata (`index.yaml`, tree files) torn | **fails LOUDLY at open/use** (sentinel refusal) | reopen once with `XdStorageConfig.builder().acceptMissingEofSentinel(true)` and verify your objects; rebuild indexes if reads misbehave |
| structure files/journals torn | opens clean (the same lost-tail semantics) | none |
| redundant copies (the search tree holds entity copies) | a torn data journal is masked — reads keep working | none (do not rely on this: it is redundancy, not a guarantee) |

**What is lost:** everything since the last durable point — with `durableWrites=false`
(the default) that is unbounded (the OS page cache is lost with the power); with
`durableWrites=true` (fsync per commit, measured ~8× write cost — BENCHMARKS § Canonical)
the loss shrinks to nothing.

**The recipe:** (0) run `XdStorageProvider.verify(folder)` — the read-only integrity walk
(VERIFY, docs/tasks/2.1.0/26) that mechanizes these checks: per-file sentinel state
(`TRUNCATED` names the file), journal state and record counts (torn tails named), data
objects vs id-index entries per class, the format marker, orphaned journals, stale `.tmp`
litter, and a live folder lock (reported as IN USE, never refused). The verdict: `OK` —
nothing to act on; `WARNINGS` — damage the engine tolerates by design (torn journal tails,
base-less journals, litter, legacy v1 files); `CORRUPT` — a v2 file without its sentinel,
an unparseable file, a count mismatch. (1) reopen normally — most damage opens clean or
fails loudly; (2) on a loud
`TRUNCATED` sentinel failure, reopen once with `acceptMissingEofSentinel(true)` — the storage
opens with the readable prefix and rewrites files on the next commit (expect partial content:
verify critical objects); (3) if the lost tail is unacceptable, restore from backup
(§ Backup and restore) instead of accepting the truncation.

**The `durableWrites` default is OFF by explicit decision** (2.1.0 re-decision, docs/tasks/
2.1.0/23): the ~8× write cost would falsify the published throughput envelope, and the
matrix shows the default path never opens silently-partial. Flip
`XdStorageConfig.builder().durableWrites(true)` when power-loss durability matters more than
write throughput — that is exactly the incident class the flag exists for.

The tested operability contract: **close the storage (`shutdown()`), copy the folder with
plain file operations, open the copy** under a new storage name. Every read answers from the
copy: object counts, load-by-id, indexed EQUALS and range queries across all storage policies
(the executable proof: `XdStorageBackupRestoreWalkthroughTest` in the reliability suite).

- The `.xdstorage.lock` FILE travels with the backup and is harmless — a clean `shutdown()`
  releases the OS file lock; the copy re-acquires its own. (A stale lock file from an unclean
  shutdown is equally harmless — also pinned by the test.) What must NOT be copied is an
  **open** folder: the exclusive OS lock blocks same-machine copies, and a copied-while-open
  folder has no defined content state.
- No export/import step, no format conversion — the copy IS a restorable backup. For
  scheduled backups: close (or snapshot from a second machine while the first is closed),
  `cp -r`, reopen.

## Observability

- Logging: the library logs through **SLF4J** (`org.slf4j.Logger`). The jar ships **no logging
  backend and no logging configuration** — the backend and its configuration are the
  application's choice: logback, log4j2 via `log4j-slf4j2-impl`, `slf4j-jdk14`, or none (a
  backend-less application sees at most SLF4J's single documented "no provider" notice, never
  engine output). Level discipline: INFO is reserved for rare lifecycle events (storage
  open/shutdown, journal checkpoint); the per-operation trace is DEBUG. Ready-made quiet and
  diagnostic configurations (log4j2 + logback) live in
  [docs/examples/logging/](examples/logging/README.md).
- Metrics: `storage.getMetrics()` — per-storage counters (transactions begun/committed/rolled back,
  rollback-only marks, timeout sweeps, resource write-outs with nanos, active gauge). JVM-wide
  anomaly counters (lock-acquisition timeouts, 1s+ lock waits, find-retry budget exhaustions):
  `XdStorageMetrics.engineEvents()`. JMX/Micrometer export is left to the embedder — read the
  snapshot periodically.

## Configuration recap

`XdStorageConfig`: `indexJournalWrites=true` (W-4a default — hash id-index commits journaled: O(delta)
appends instead of the O(N) full-file rewrite; a clean shutdown checkpoints every journal,
so after a clean close a power loss loses nothing even with `durableWrites=false`),
`journalWrites=true` (W-3 default — data-class commits journaled,
`false` = classic full-file rewrite), `journalCheckpointBytes=16MB`, `durableWrites=false`,
`acceptMissingEofSentinel=false`, `txMaterializedObjectsCap=0` (unlimited),
`daemonWorkerThreads=false`, `registerShutdownHook=false`,
`workerPoolSize=15`, `defaultTransactionTimeoutMillis=3000`, `isolationLevel=SNAPSHOT`.
Journal files live in the `journal` SUBDIRECTORY of their resource's directory
(`data/<Class>/journal/<base>-journal.yaml`, e.g. `data/Order/journal/Order-1-journal.yaml`,
`data/Order/journal/index-journal.yaml`, `structure/journal/...`) — a class folder holds only
bases. JRNLNAME (2.1): the journal name is a SUFFIX on the extension-less base — never a stacked
extension. Older namings are read-compatible and migrate automatically at the file's next replay:
the 2.0.0-era stacked name (`Order-1.yaml.journal.yaml`) and the pre-subdirectory sibling name
(`Order-1-journal.yaml` next to the base); when several namings are present the journal-directory
file wins and the superseded twins are removed (WARN). A journal of a resource the session never
touches keeps its old name (its appends land inside it, nothing is lost) until that resource's
next prepare.

## Compatibility & support statement

E-6: what "supported" means, stated up front so upgrades are design-time decisions.

### JDK matrix

| Artifact | Requirement |
|---|---|
| Library bytecode | Java **8** (`release=8`, platform API pinned — H-5) |
| Runtime | any JRE/JDK 8+ **for the bytecode** — but see the codegen requirement above: wrapper generation needs a **JDK at runtime** until FP-3 (AOT) ships |
| Building XdStorage itself | JDK **17+**, Maven **3.6.3+** (enforced) |
| CI / tested runtimes | Temurin 17 (gate + nightly); 21 used in development |

### On-disk format compatibility

The format generation is recorded per storage folder in `.xdstorage.meta`
(`formatVersion`; see `XdStorageFormatMeta`):

| Folder was written by | 2.0 library behavior |
|---|---|
| 2.0+ (marker `formatVersion=2`) | opens normally; startup INFO announces `format v2`. Older folders may carry journals in pre-subdirectory namings (the 2.0.0-era `<base>.journal.yaml` stack, or the sibling `<base>-journal.yaml`) — 2.1 replays them and migrates the files into the `journal/` subdirectory automatically; no manual rename |
| 1.x (no marker, files present) | opens with a legacy INFO; **YAML** content additionally requires `acceptMissingEofSentinel(true)` until the first commit rewrites the files; **XML** content opens as-is |
| a **newer** library (marker `formatVersion` > 2) | loud `XdStorageRuntimeException` before any engine state is built — upgrade the library |
| fresh/empty folder | marker `formatVersion=2` written at open |

The folder is single-writer (exclusive lock); a folder may be read concurrently only by the
owning process. There is no multi-process write access and no down-level writer after a
format bump — that is what the marker check is for.

### Migrating a 1.x folder that used `FilesFormat.JSON`

The 1.x `JSON` constant actually wrote **XML content** into `.json`-named files. Migrate:

```bash
# storage shut down, folder at ./data/db:
find ./data/db -name '*.json' -exec bash -c 'mv "$1" "${1%.json}.xml"' _ {} \;
```

then open with `FilesFormat.XML`. (For YAML-era folders there is nothing to rename.)

### Support and deprecation policy

- **Semver**: MAJOR = API or on-disk format break; MINOR = features, config-default changes,
  format additions; PATCH = fixes. Format generation bumps are MINOR events (they are
  additive); format *removals* are MAJOR.
- **Deprecation policy**: a deprecated API or format keeps read/write support for at least
  two MINOR releases before removal, with the removal and its migration recipe in
  CHANGELOG.md.
- **Flake transparency**: known rare stress flakes are tracked publicly in
  [FLAKY.md](FLAKY.md); a release ships either with zero open rows or with the open rows
  named in its release notes (2.0.0: the BTreeStress full-suite-load contention row, Track B3).
- **Performance contract**: numbers and baselines live in [BENCHMARKS.md](BENCHMARKS.md);
  a release that regresses a benchmark scenario >25% vs the recorded baseline is called out
  in its release notes.

