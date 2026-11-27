# Embedding XdStorage — the contract

Operational contract for applications embedding XdStorage 2.1. Everything here is behavior of the
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
  `"method must be executed in transaction"` when absent. The deliberate exception: `explain`
  works without a transaction **by design** (the plan is static index metadata — no snapshot
  needed; the internal probe transaction is rolled back immediately).
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
- **Concurrent writers are safe on the shared index trees (2.1.0):** B+tree key mutations
  stage into the writing transaction's overlay and apply at its commit, and the commit
  acquisition order is total — concurrent transactions queue instead of deadlocking on the
  class-level trees (10 concurrent writers measured 121–158 tx/s with p99 84–113 ms;
  the 2.0.0-era behavior collapsed the same load through lock-timeout cycles).
- **Conflicts are re-validated at commit time (2.1.0):** first-committer-wins covers the
  whole stage→commit window — if a foreign transaction committed a change to a record this
  transaction read, the loser's commit fails **loudly** (the conflict names the record) with
  the stored data intact; the retry pattern is a fresh read in a new transaction. Staged-but-
  aborted foreign work does not trigger false conflicts (the semantics are content-based).
- Nested transactions: `beginTransaction(parentTx, timeout)`; a nested rollback does not poison the
  parent (downward-only rollback-only cascade). **NOT RECOMMENDED for load-heavy use** — the
  status decision of 2026-10-26 stands until [task 12](tasks/2.2.0/12-nested-transactions-load-safety.md)
  is closed (lifted or deprecated); see the transaction-failure diagnostics for the failure
  families. Two rules a caller meets: a nested transaction is bound to the thread that began
  it, and a parent's commit does not wait out a nested transaction left running on ANOTHER
  thread — after 5 seconds that transaction is rolled back, and the parent with it (2.2.0; a
  nested transaction abandoned on its own thread, or past its timeout, is swept as before).
  The isolation stress test of the machinery (`XdStorageNestedTransactionIsolationStressTest`,
  8 workers with 100 ms budgets) runs in the default suite. Prefer flat transactions with
  explicit compensating code.
- **Abandoned top-level transactions are NOT force-swept at `beginTransaction`** (the
  ZOMBIELATCH decision, docs/tasks/2.1.0/36-zombie-latch-purge): a worker that swallows a
  failure without commit/rollback leaves its transaction latched until the lazy timeout
  enforcement trips — but the record latches are ADOPTABLE the moment the owner is
  dead (the R-27 latch skips for infrastructure + the dead-owner adoption make stale
  latches non-blocking for new work), the sweeper still rolls the zombie on contact,
  and a begin-time force-sweep would add cross-transaction interference to every
  `beginTransaction` on a reused pool thread for a hygiene bug the caller must fix
  (always terminate your transaction in a `finally`). The obligation is on the caller;
  the stress estates pin it (every worker `catch` ends its transaction).

## JVM-wide static state (per-JVM, not per-storage)

- `XdStorageProvider` registry (name → instance).
- Wrapper-codegen caches (`XdStorageClassMetadataRegistry`, proxy compilation engine): per-class
  metadata computed once, never evicted — warm them once, share across storages.
- **`COMMIT_SEQUENCE` is a single global counter** shared by all storages in the JVM — required for
  consistent cross-storage MVCC ordering.
- The deferred-id observer registry is JVM-global and keyed by class. **Not a leak since
  2.1.0 (task 46)**: the segments hold weak-reference values — a wrapper lives exactly as
  long as somebody holds it (its owning transaction); a registration whose id never lands
  and whose transaction completed is reclaimed by the GC, so the registry no longer
  survives `storage.shutdown()` with dead entries (the historical WeakHashMap leaked
  because its values strongly referenced their keys).
- The generated-class loader is a static `URLClassLoader` over `./gencode` — **created relative to
  the process working directory at first use and pinned for the JVM's life**. App-server redeploys
  that expect classloader isolation will not work with engine-generated wrappers.

## Codegen requirements

- The engine generates wrapper classes **at runtime** via the system Java compiler: a **JDK is
  required at runtime — a JRE silently degrades to dummy wrappers** (broken equals/hashCode; an
  ERROR is logged once, the failure is cached for the JVM lifetime, there is no retry).
- The write target `./gencode/` must be **writable**; a read-only working directory has the same
  permanent-dummy-wrapper failure mode.
- Every persisted field follows **JavaBeans**: a getter AND a setter. A field that has a
  getter but no setter (a common slip on generated/Lombok-annotated collections) is **dropped
  from persistence with a WARN** — it is never written and never restored; nothing round-trips.
  The engine logs the drop once per class at metadata load; check the WARN before assuming a
  collection "did not save".
- Entity classes must be **top-level** (nested classes cannot be compiled on JDK 9+).

## Files and durability

- The storage folder is engine-owned: `structure/*.yaml`, `data/<Class>/*.yaml` (YAML default),
  `indexes/<Class>/<Class>.yaml` (id-index / B+Tree heads and nodes — see the layout migration,
  docs/tasks/2.1.0/51, EMBEDDING § Upgrade from 2.0.0 below);
  **all commit journals live under `data/<Class>/journal/`** — including the id-index and
  search-key journals (task 54 JRNLDATA: the index journals are DATA CARRIERS — the sole
  durable location of the un-checkpointed id→fragment addressability between checkpoints —
  and live with the data they protect, not inside the derived-index directory)
  plus `.xdstorage.lock` and the format marker `.xdstorage.meta`. Do not edit files while the storage is
  open.
- On open, legacy `data/XdStorageBTree/` and `data/XdStorageBTreeNode/` directories are
  **moved** into `indexes/` (one-time, loud INFO); new storages create the layout directly. This
  is a **layout discovery** — not a format break — the format version stays the same.
- Default write-out is process-crash-safe (atomic rename), NOT power-loss-safe. Enable
  `XdStorageConfig.builder().durableWrites(true)` for fsync-per-commit power-loss safety.
- Resource files carry an EOF sentinel; a truncated file fails loudly at open. Pre-sentinel legacy
  storages: open once with `acceptMissingEofSentinel(true)` and let the first commit rewrite the files.
- Stale `*.tmp` files from a previous crash are swept at open.

### Cascade save, foreign keys, and transactions

`@XdStorageAutoSave` on a parent's child field cascades an upsert of the annotated children
(collections, single references, arrays, and MAP values that carry an id-field) inside the
parent's save/update call — same transaction, same 2PC atomicity, child triggers and
observers fire as for explicit saves, and a failed cascade aborts the save. Removed children
are NOT deleted; re-save re-upserts every annotated child. Full contract:
GUIDE (docs/GUIDE.md) § Cascade save; executable proof: `XdStorageAutoSaveCascadeTest`.

**The foreign-key operational contract** (the pair `@XdStorageAutoSave` + `@XdStorageParentObject`):

- Each relation is TWO persistent B+trees — the **front** tree (parent id → child ids) and the
  **reverse** tree (child id → parent id) — created at first save and maintained automatically
  in the SAME transaction as the data write; the application never touches them. They double
  as the navigation index: "load the children of P" / "load the parent of C" answer from the
  trees without a data scan (measured free, ~1.0× — BENCHMARKS § FK A/B).
- A child participates in the relation only when its back-reference carries
  `@XdStorageParentObject`; without the marker the relation does not exist for the FK engine.
- Deletion ordering: children first, the parent after — a secondary-key entry is removed with
  the child's deletion, in the same transaction.
- **The three vetoes are commit-time hard failures** (the commit rolls back, no partial state):
  a duplicate (parent, child) pair; deleting a child whose parent still references it;
  deleting a parent that still has live children. Design your delete cycle accordingly:
  remove the child from the parent's collection, update the parent, then delete the child.
- Id timing is engine-handled: auto-generated (deferred) child ids wait via the deferred-id
  waiters; manual-id parents are validated at commit.
- `enableForeignKeys=false` skips the trees and the vetoes (the cascade still runs). Embedded
  children (`StoreWithParentObject`) bypass FK validation by design. In-transaction reads see
  the transaction's own secondary-key changes before commit (read-your-own-read; the trees are
  per-transaction staged).
- **The cost is real and measured**: cascade-save 23–160×, child delete 12–73×, parent update
  1.4–12.5× vs FK-off, scaling linearly with the FK-tree size; FK reads are free. Enable FK
  for integrity-critical graphs, not hot write loops (BENCHMARKS § FK A/B; GUIDE § Foreign
  keys for the full rules).

### After a power loss

The default configuration is **process-crash safe (atomic rename), not power-loss safe** —
power loss additionally loses the OS page cache, so a "successfully written" file can come
back with its tail missing. The verified behavior matrix
(`XdStoragePowerLossRecoveryMatrixTest` — simulated by truncation, every cell pinned):

| Damage (simulated) | Outcome with defaults | Recovery |
|---|---|---|
| commit journal tail torn | **opens clean** (lost-tail WARN; records after the cut discarded by design) | none — recent commits since the cut are lost |
| index/tree metadata (`indexes/<Class>/index.yaml`, tree files) torn | **fails LOUDLY at open/use** (sentinel refusal) | reopen once with `XdStorageConfig.builder().acceptMissingEofSentinel(true)` and verify your objects; rebuild indexes if reads misbehave |
| structure files/journals torn | opens clean (the same lost-tail semantics) | none |
| redundant copies (the search tree holds entity copies) | a torn data journal is masked — reads keep working | none (do not rely on this: it is redundancy, not a guarantee) |

**What is lost:** everything since the last durable point — with `durableWrites=false`
(the default) that is unbounded (the OS page cache is lost with the power); with
`durableWrites=true` (fsync per commit, measured ~95× write cost on the canonical shape —
BENCHMARKS § Canonical) the loss shrinks to nothing.

**Cleanup after a crash (the recovery-purge recipe).** A torn multi-file commit can leave
residue: a persisted collection referencing a record whose insert never became durable,
and foreign-key pairs whose record is gone. Reading such a collection throws
`cannot load by reference` (loud, by design) and marks the reading transaction
rollback-only — so a recovery purge must NOT run in one shared transaction. The recipe
(see `XdStorageCrashAndPurgeStressTest` for the full implementation):

1. **One transaction per subtree** (per parent and its children) — a poisoned
   (rollback-only) transaction is rolled back and the purge continues with a fresh one;
   per-element `catch` in a shared transaction cannot survive a rollback-only mark.
2. **Children-first order**, failures counted and skipped — a skipped subtree is retried
   in the next pass; repeat until no progress is made.
3. **Root-down scorched earth** as the last pass: load every record of each class
   (children first) and delete, tolerating per-record failures.
4. **Drop the FK indexes of the purged classes before the by-class pass.** The by-class
   pass deletes children and parents in separate transactions, so a foreign-key
   expectation armed in the children's transaction would veto at its own commit before
   the parents' transaction dissolves the pairs. The records are being deleted anyway —
   drop the FK tree files of the purged classes (both directions) and reopen: the purge
   then runs without FK enforcement, exactly like disabling constraints for a recovery
   purge in a conventional database.
5. **A class whose index tree the crash broke** (repeated "node referenced but
   missing" on every access) cannot route deletes at all: drop that class's data
   directory AND its tree structure files, reopen — the class is being purged anyway,
   so dropping its store IS the cleanup.
6. **Verify**: per-class emptiness checks always execute; embedded children
   (`StoreWithParentObject`) are counted by their record FILES on disk (the facade
   cannot load embedded classes — the disk is the honest witness).

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
2.1.0/23): the fsync write cost (~8× at the 2.0-era decision point, measured ~95× on the
canonical 2026-11-21 baseline — the non-fsync path got 24× faster since) would falsify the
published throughput envelope, and the matrix shows the default path never opens silently-partial.
Flip `XdStorageConfig.builder().durableWrites(true)` when power-loss durability matters more than
write throughput — that is exactly the incident class the flag exists for.

### Backup and restore

The tested operability contract: **close the storage (`shutdown()`), copy the folder with
plain file operations, open the copy** under a new storage name. Every read answers from the
copy: object counts, load-by-id, indexed EQUALS and range queries across all storage policies
(the executable proof: `XdStorageBackupRestoreWalkthroughTest` in the reliability suite).

- **A copy made by hand is tied to the original's PATH.** The id index names the file of
  every record by the path the storage's folder was opened with. Restoring a copy to the SAME
  path is a full restore. Opening a copy at ANOTHER path while the original still exists reads
  the ORIGINAL's files; with the original gone, it answers `null` for records that sit in its
  own `data/`. (The executable proof above restores next to the original — which is why it
  passes.) For a copy that stands anywhere, use `backupTo` below.
- The `.xdstorage.lock` FILE travels with the backup and is harmless — a clean `shutdown()`
  releases the OS file lock; the copy re-acquires its own. (A stale lock file from an unclean
  shutdown is equally harmless — also pinned by the test.) What must NOT be copied is an
  **open** folder: a copied-while-open folder has no defined content state, and on Windows
  the copy does not even complete — the lock on `.xdstorage.lock` is mandatory there, and no
  other handle reads that file until the storage is closed. On Linux and macOS the lock is
  advisory and such a copy succeeds, which makes the undefined state easier to miss.
- No export/import step, no format conversion — the copy IS a restorable backup. For
  scheduled backups: close (or snapshot from a second machine while the first is closed),
  `cp -r`, reopen.

- **Programmatic backup** (FP-5, 2.2.0, docs/tasks/2.2.0/15):
  `IXdFileStorage.backupTo(Path)` — a copy that stands on its own and holds what was committed
  when it was taken.

  ```java
  storage.backupTo(Paths.get("/data/backups/snapshot-2026-11-27"));   // closes `storage`
  XdStorageProvider.verify(Paths.get("/data/backups/snapshot-2026-11-27"));  // OK
  IXdFileStorage copy = XdStorageProvider.newOrGetFileStorage("snapshot",
          "/data/backups/snapshot-2026-11-27", 128);
  IXdFileStorage live = XdStorageProvider.newOrGetFileStorage("live", "/data/live-db", 128);  // keep working
  ```

  **The contract.** The call closes the source — its shutdown checkpoint puts every committed
  change into the base files — then copies the folder, without the lock file, and rewrites the
  record paths inside the copy to the copy's own location. So the copy answers the same
  queries whether or not the original exists, and passes `XdStorageProvider.verify`. Reopen
  the source to keep working. The destination must be a new or an empty directory outside the
  storage folder; one the call created is removed again if the copy fails. A commit that was
  in flight when the copy was taken is cut between its journal appends — the state a crash at
  that instant leaves, with the same limits for a commit spanning several classes.

  The executable proofs: `XdStorageBackupToTest` (every policy and index kind answered from
  the copy with the source moved away; a relative source folder; the refusals) and
  `XdStorageBackupUnderLoadStressTest` (the copy taken while four threads commit — no
  transaction is cut in half). Out of scope: incremental or scheduled backups, compression,
  encryption.

- `verify(folder)` (task 26) is the acceptance instrument — run it against every produced
  backup folder before trusting its content.

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

`XdStorageConfig`: `enableForeignKeys=true` (W-4/feature default — relations to independent-policy children
  (`@XdStorageAutoSave` + `@XdStorageParentObject`) are backed by persistent front/reverse
  B+tree indexes; deleting a still-referenced child fails loudly; set `false` to disable the
  enforcement), `indexJournalWrites=true` (W-4a default — hash id-index commits journaled: O(delta)
appends instead of the O(N) full-file rewrite; a clean shutdown checkpoints every journal,
so after a clean close a power loss loses nothing even with `durableWrites=false`),
`journalWrites=true` (W-3 default — data-class commits journaled,
`false` = classic full-file rewrite), `journalCheckpointBytes=16MB`, `durableWrites=false`,
`acceptMissingEofSentinel=false`, `txMaterializedObjectsCap=0` (unlimited),
`daemonWorkerThreads=false`, `registerShutdownHook=false`,
`workerPoolSize=15`, `defaultTransactionTimeoutMillis=15000`, `isolationLevel=SNAPSHOT`.
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
| CI / tested runtimes | Temurin **17** (the full-suite gate + the nightly ×3) and Temurin **21** (the quick-gate profile on pushes/PRs + the full suite nightly — JDK21, task 27); 21 is also the canonical-baseline runtime. Bytecode stays `release=8` either way |

### Supported platforms

Stated as what has been **run**, not what is expected to work.

| Platform | Status |
|---|---|
| Linux x86_64 | **Tested.** The full-suite gate and the nightly ×3 run on it (`ubuntu-latest`), on Temurin 17 and 21; the benchmark figures in BENCHMARKS.md come from a Linux box. |
| Windows x86_64 | **A CI leg runs the non-stress suite on `windows-latest`** (`ci.yml`, job `windows`, since 2.2.0). Its first run (2026-11-27) came back 1746 run, 25 failed — every failure in the tests themselves: the folder lock's file copied while the storage was open, `/` spelled as the separator, a thread check over the whole JVM ([the triage](tasks/2.2.0/04-os-dependent-paths.md)). The engine showed no Windows defect in that run. The fixes are in; until a run with them is green, treat Windows as *expected to work, not verified*. |
| macOS | **Not tested.** No CI leg runs there. |
| Java 8–11 runtimes | **Not tested by CI.** The bytecode is `release=8` and uses only the Java 8 platform API (H-5), but the suite runs on 17 and 21 only. |

What the 2.2.0 audit ([task 04](tasks/2.2.0/04-os-dependent-paths.md), 88 file, charset and
lock sites) established about the engine's portability: storage paths go through `Path` and
`Files` (the classpath is the only place the separator is spelled out); every write names
its charset — UTF-8, or ISO-8859-1 where `Properties.store` fixes it for the format marker;
the folder lock is a `FileLock`, the same NIO call on POSIX and Windows; and the write-out
asks for an atomic rename and logs one WARN when the filesystem cannot deliver it
(`AtomicMoveNotSupportedException`) — the move is then a plain replace, and a crash during
the swap may lose that file. Filesystems without rename support (FAT, exFAT) therefore trade
the crash-safety contract of § Files and durability for portability; keep the storage folder
on one that supports it (NTFS, ext4, APFS).

### Field types and their stored form

GUIDE § Field types lists what a field may hold; this is the same list as it meets the disk
and an upgrade.

| Type | Stored as | A folder written before 2.2.0 |
|---|---|---|
| primitives and wrappers, `Number` (`BigDecimal`, `BigInteger`, …), `String`, `Character`, `Boolean` | text | reads as before |
| `java.util.Date`, `java.lang.Class`, enums | epoch milliseconds, class name, constant name | reads as before |
| `UUID`, `Instant`, `LocalDate`, `LocalDateTime`, `Duration` | their `toString()` forms (canonical UUID; ISO-8601) | **the field was not written at all** — a save dropped it silently; it reads `null`, as it did before |
| an index over one of the five | — | **the search tree is unreadable** (`invalid state of database`), before and after the upgrade. With the storage closed, remove the index's two tree files (`indexes/XdStorageBTree/XdStorageBTree-<Class>-<index>.yaml`, `…-key_<index>.yaml`); the index answers empty from then on — `executeStructureUpdate` does not repair it. Run `verify` afterwards. |
| any other class | a child object or a reference | reads as before |

Adding a simple type is an engine change (`XdStorageObjectUtils.isSimpleType` and
`XdStorageDefaultSimpleTypeHelper`); applications cannot register one.

### On-disk format compatibility

The format generation is recorded per storage folder in `.xdstorage.meta`
(`formatVersion`; see `XdStorageFormatMeta`):

| Folder was written by | 2.1 library behavior |
|---|---|
| 2.1+ (marker `formatVersion=3`) | opens normally; startup INFO announces `format v3` |
| 2.0 (marker `formatVersion=2`) | opens normally; the one-time **layout migration** runs at open (see "Upgrade from 2.0.0" below) and the marker is rewritten to `3` on success. The journals may carry the 2.0.0-era stacked naming (`<base>.yaml.journal.yaml`) or the sibling spelling — 2.1 replays them and migrates the files into the `journal/` subdirectory automatically; no manual rename |
| 1.x (no marker, files present) | opens with a legacy INFO; **YAML** content additionally requires `acceptMissingEofSentinel(true)` until the first commit rewrites the files; **XML** content opens as-is |
| a **newer** library (marker `formatVersion` > 3) | loud `XdStorageRuntimeException` before any engine state is built — upgrade the library |
| fresh/empty folder | marker `formatVersion=3` written at open |

**Strings with line breaks (2.2.0).** A string that contains a line break is stored in a
line-safe form — double quotes with the escapes `\n`, `\r`, `\"`, `\\` — instead of the
single-quoted form every other string keeps. Nothing else changed: a folder written by any
earlier version opens and reads identically (no earlier version ever wrote a value that
starts with a double quote). The rule goes one way only: **a 2.1.x library must not open a
2.2.0 folder that contains such a string** — it returns that one value mangled (the quotes
and the escapes as literal text), everything else intact. The marker is unchanged
(`formatVersion=3`), so 2.1.x does not refuse on its own; do not downgrade such a folder, or
back it up first (§ Backup and restore). Before 2.2.0 a line break inside a string could not
be stored at all — it corrupted the record.

The folder is single-writer (exclusive lock); a folder may be read concurrently only by the
owning process. There is no multi-process write access and no down-level writer after a
format bump — that is what the marker check is for.

### Upgrade from 2.0.0 (task 51 LAYOUTMIG)

The 2.1.0 on-disk **layout** moved the id-index, references, search-index and B+Tree infra
files into a dedicated `indexes/` subdirectory. The content format is unchanged; the layout
generation is stamped in `.xdstorage.meta` (`formatVersion=3`).

**The upgrade procedure:**
1. Close the 2.0.0 process cleanly (or accept that a dirty close leaves un-checkpointed
   journal tails — the migration handles both; a backup is still recommended: `cp -r` while
   closed).
2. Open the same folder with 2.1.0. The one-time migration runs at open, before any resource:
   - the interim `index/` directory → `indexes/`;
   - `data/<Class>/index.yaml` (the id-index), `data/<Class>/references.yaml` (the
     StoreAsSingleObject classes), `data/<Class>/<indexName>.yaml` (the search bases) →
     `indexes/<Class>/`;
   - `data/XdStorageBTree/` and `data/XdStorageBTreeNode/` → `indexes/`.
3. The **data fragments stay in `data/`** (their paths are persisted inside the id-index
   payload — moving them would orphan every record; the fragments do not move, so the
   payload keeps resolving). The stacked 2.0.0 journals stay in place too — each resource's
   first `prepare` replays and settles them into `journal/` automatically (task 24). All
   commit journals — including the id-index and search-key ones — live under
   `data/<Class>/journal/` (task 54): a 2.1.0-dev folder whose index journals sit under
   `indexes/` settles them into `data/<Class>/journal/` lazily at the first prepare; a
   2.0.0 folder has no index journals at all (W-4a/W-4b are 2.1.0 features).
4. After a full success the format marker is rewritten `2 → 3`. From then on **a 2.0.0
   library refuses the folder loudly** (the newer-format gate) — the downgrade refusal is
   enforced by the stamp.

**Crash tolerance:** the migration is a sequence of idempotent steps (each checks
source-exists/dest-absent). A crash mid-migration leaves a half-migrated folder with the
marker still at `2`; the next open detects the remainder structurally and completes it
(pinned by the crash-matrix tests on the golden 2.0.0 fixture).

**Failure policy:** any unmovable file (a Windows lock, permissions) = a loud open refusal
(`LAYOUTMIG` in the message). Continuing with a half-moved index would surface as a silently
empty id index and the first save would overwrite the fragment-0 content — a refused open is
the safe behavior. Resolve the file lock / permissions and reopen.

**Known limitations:** (1) the id-index payload stores fragment paths verbatim — a folder
created with a RELATIVE folder path stays relocatable, a folder created with an ABSOLUTE path
relocates only via takeover (task 24); (2) an index literally named like a data fragment
(`<Class>`/`<Class>-…`) would stay behind and surface as an empty index — verify after the
upgrade (`XdStorageProvider.verify`); (3) the 2.0.0-era folders written on Windows may carry
OS-separated payload paths — verify on Windows before relying on the upgrade there.

### Migrating a 1.x folder that used `FilesFormat.JSON`

The 1.x `JSON` constant actually wrote **XML content** into `.json`-named files. Migrate:

```bash
# storage shut down, folder at ./data/db:
find ./data/db -name '*.json' -exec bash -c 'mv "$1" "${1%.json}.xml"' _ {} \;
```

then open with `FilesFormat.XML`. (For YAML-era folders there is nothing to rename.) Both
steps — this rename at any depth, and the EOF sentinel for YAML-era files — are what
`XdStorageMigrate --script builtin:v1-to-v2` does (§ Schema migration).

### Schema migration — the `schema-diff` and `migrate` tools (task 17)

Two command-line tools for an operator. Both print their report to stdout and give their
verdict as the exit status.

#### `schema-diff` — compare what two folders store, or a folder and the live classes

```bash
# two folders:
java -cp <xdstorage.jar> io.github.xdstorage.tools.XdStorageSchemaDiff ./old-db ./new-db

# a folder against the live classes (the classes must be on the tool's classpath):
java -cp <xdstorage.jar:the application's classes> io.github.xdstorage.tools.XdStorageSchemaDiff ./my-db \
    --against-classes com.example.Order com.example.Customer

# JSON for pipelines:
java -cp <xdstorage.jar> io.github.xdstorage.tools.XdStorageSchemaDiff ./old-db ./new-db --json
```

**What a folder stores — and what it does not.** The engine keeps no schema apart from its
records. A class's structure — fields with their types, the search indexes, the storage
policy — is written only when the application ran `executeStructureUpdate(Class)` for that
class, as one record in `structure/XdStorageClassStructure.yaml`. `schema-diff` compares
those records and nothing else. A folder without that file has nothing to compare: the tool
says so on stderr, prints `nothing to compare` and exits 0 — it does not report classes as
added or removed on that account. Classes whose data is in the folder without a stored
structure are named in the note.

Output, one line per change: `<class> <CLASS|FIELD|IDX|POLICY> <ADDED|REMOVED|CHANGED> <details>`
— for example `com.example.Order FIELD ADDED status String` or
`com.example.Customer IDX ADDED byName/active Boolean`. `--json` prints the same as a JSON
array (notes go to stderr).

Exit codes: 0 no differences, or nothing to compare; 1 differences found; 2 usage; 3 a folder
cannot be read — missing, unreadable, or XML-format (YAML folders only).

#### `migrate` — bring a 1.x folder to the form this library opens by default

```bash
java -cp <xdstorage.jar> io.github.xdstorage.tools.XdStorageMigrate ./my-db --dry-run
java -cp <xdstorage.jar> io.github.xdstorage.tools.XdStorageMigrate ./my-db [--script builtin:v1-to-v2]
```

The only script, `builtin:v1-to-v2`, works on the folder's files; no entity class is needed
on the classpath. It:

1. renames every `*.json` file, at any depth, to `*.xml` — the 1.x `JSON` constant wrote XML
   under that name (§ Migrating a 1.x folder that used `FilesFormat.JSON`);
2. appends the end-of-file sentinel `# xdstorage-eof` to every YAML file that lacks it. Every
   file the engine writes ends with that line, and the default configuration refuses a file
   without it as truncated (§ Files and durability);
3. opens the folder with the default configuration — for an XML folder without journaling,
   since that format does not journal — which also runs the 2.0.0 layout migration when the
   marker says `formatVersion=2`; closes it; and runs `XdStorageProvider.verify`.

The result opens with the default configuration. A 1.x folder stays without a format marker,
as the engine keeps it, so `verify` reports WARNINGS for the marker alone.

**It cannot tell a truncated 1.x file from a whole one:** the sentinel is appended on the
same reading the lenient open gives the file — complete. Keep a copy of the folder until the
result is verified. `--dry-run` prints the plan and leaves the folder byte for byte; a second
run finds nothing to do.

Exit codes: 0 migrated or nothing to migrate (`--dry-run` with nothing to do); 1 `--dry-run`
found work; 2 usage; 3 the folder is in use, a step failed, or the result does not verify.

**What it does not do:** transform record content or run structure updates — those stay with
the application (`executeStructureUpdate`, GUIDE § Structure updates).

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

