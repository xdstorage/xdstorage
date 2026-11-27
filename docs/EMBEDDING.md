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
  parent (downward-only rollback-only cascade). **NOT RECOMMENDED for load-heavy use**: the
  nested commit/rollback machinery is a known critical-under-load path (abandoned-nested
  latch cascades, zombie sweeps, mid-commit poisoning — see the transaction-failure
  diagnostics). The cross-thread abandoned-nested sweep gap was fixed in 2.2.0
  (task 12: `CROSS_THREAD_ABANDON_MILLIS = 5000` + force-sweep in `skipOrWaitForInternalTransactions`).
  The remaining throughput envelope (per-record YAML floor ~20–50 ms, commit-lock queue depth)
  is an architectural limitation of the suspension model, not a correctness defect.
  Prefer flat transactions with explicit compensating code.
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

- The `.xdstorage.lock` FILE travels with the backup and is harmless — a clean `shutdown()`
  releases the OS file lock; the copy re-acquires its own. (A stale lock file from an unclean
  shutdown is equally harmless — also pinned by the test.) What must NOT be copied is an
  **open** folder: the exclusive OS lock blocks same-machine copies, and a copied-while-open
  folder has no defined content state.
- No export/import step, no format conversion — the copy IS a restorable backup. For
  scheduled backups: close (or snapshot from a second machine while the first is closed),
  `cp -r`, reopen.

- **Programmatic backup** (FP-5, 2.2.0, docs/tasks/2.2.0/15): `IXdFileStorage.backupTo(Path)
  —` a consistent point-in-time copy of a storage folder into a destination directory.
  The method internally checkpoints all journals, shuts the storage down, and deep-copies
  every file (minus `.xdstorage.lock`) to the destination. The destination is a
  self-contained storage folder openable under a new name. The produced copy passes
  `XdStorageProvider.verify(destination)` verdict OK. The executable proof: `XdStorageBackupToTest`
  (reliability suite). Out of scope: incremental/delta backups, compression/encryption,
  scheduled backups.

  ```java
  // 1. build content...
  // 2. backupTo: the method shut downs the source internally
  storage.backupTo(Paths.get("/data/backups/snapshot-2026-11-24"));
  // 3. verify (optional but recommended)
  XdStorageProvider.verify(Paths.get("/data/backups/snapshot-2026-11-24")); // OK
  // 4. open the copy
  IXdFileStorage copy = XdStorageProvider.newOrGetFileStorage("snapshot-name", "/data/backups/snapshot-2026-11-24", 128);
  ```

  **Consistency contract:** the source is quiesced at the backup point (all committed
  fragments are on disk via atomic-rename write-outs). No in-flight uncommitted changes
  can leak into the copy. A backup of an OPEN storage is unsupported — the caller must
  ensure the source is idle or let `backupTo` shut it down internally.

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
| Windows x86_64 | **A CI leg runs the non-stress suite on `windows-latest`** (`ci.yml`, job `windows`, since 2.2.0). Until its result is green and triaged ([task 04](tasks/2.2.0/04-os-dependent-paths.md)), treat Windows as *expected to work, not verified*. |
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

then open with `FilesFormat.XML`. (For YAML-era folders there is nothing to rename.)

### Schema migration — the `schema-diff` and `migrate` tooling (task 17)

For operational workflow, XdStorage ships two CLI tools over the engine's structure
metadata:

#### `schema-diff` — compare two folders or a folder against live classes

```bash
# Compare two folders:
java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff ./old-db ./new-db

# Compare a folder against live Java classes (useful before deploy):
java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff ./my-db --against-classes com.example.EntityA com.example.EntityB

# JSON output for pipelines (exit 0 = no diffs, 1 = diffs found):
java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageSchemaDiff ./old ./new --json
```

Output is one line per change: `<class> <FIELD/IDX/CLASS> <ADDED/REMOVED/CHANGED> <details>`.
For example: `com.example.User FIELD CHANGED name String->varchar(255)`.
The `--json` output is a `[{"class":"...","kind":"FIELD","action":"CHANGED","details":"..."}]` array.

**What it compares:**
- **Fields**: name, declared type (e.g. `String`, `Integer`, `Long`). ADDED / REMOVED / CHANGED.
- **Search indexes**: index name, indexed field names + types, child-class routing. ADDED / REMOVED / CHANGED.
- **Classes**: present in one folder but not the other (ADDED / REMOVED).
- **Format version**: from `.xdstorage.meta` (v1 = no marker, v2 = YAML sentinel, v3 = `indexes/` layout).

#### `migrate` — apply the v1→v2 recipe

```bash
# Preview what would change (folder stays byte-identical):
java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageMigrate ./my-db --dry-run

# Apply the full v1→v2 migration:
java -cp xdstorage.jar io.github.xdstorage.tools.XdStorageMigrate ./my-db --script builtin:v1-to-v2
```

The `builtin:v1-to-v2` recipe (idempotent, safe to re-run):
1. Opens the folder with `acceptMissingEofSentinel(true)` — the legacy YAML reader
   (EMBEDDING S-1: a truncated file is normally rejected, this flag allows reading it).
2. The first `checkpoint()` + `tx.commit()` rewrites all touched data files with the
   S-1 EOF sentinel (per EMBEDDING § After a power loss). No manual sentinel needed.
3. Renames all `*.json` → `*.xml` (the 1.x JSON→XML recipe from this section).
4. Upgrades `.xdstorage.meta` to `formatVersion=3` (the task 51 LAYOUTMIG stamp).

After migration the folder opens cleanly under 2.2.0 (no `acceptMissingEofSentinel` needed).

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

