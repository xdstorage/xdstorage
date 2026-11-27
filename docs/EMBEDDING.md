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
  diagnostics). Scheduled for rework in 2.2.0 (optional task); until then, prefer flat
  transactions with explicit compensating code.
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
- The runtime-generated-class loader is a static `URLClassLoader` over `./gencode` — **created
  relative to the process working directory when the first wrapper is compiled at runtime, and
  pinned for the JVM's life**; it is never created when the wrappers were generated at build
  time (§ Running on a JRE). App-server redeploys that expect classloader isolation do not work
  with runtime-generated wrappers; build-time wrappers are ordinary classes of the
  application's own loader and are reloaded with it.

## Codegen requirements

Every entity class gets a generated **wrapper** — the subclass that gives it transactional
access, lazy loading and a working `equals`/`hashCode`. The engine cannot work without one, and
takes it from the first of these paths that delivers it:

1. **Build-time wrappers** (since 2.2.0) — generated while the application is built into its
   own classes, found through `META-INF/xdstorage/aot-wrappers.properties`. No compiler and no
   directory to write: the application runs on a **JRE** and from a **read-only working
   directory** (§ Running on a JRE).
2. **Runtime generation** — the system Java compiler of a **JDK**, when a wrapper is first
   needed, into `./gencode/` under the process working directory, which must be **writable**.
   (In the quickstart that is the first *load* — the first save compiles nothing; BENCHMARKS
   § Cold start.)
3. **Neither**: opening a storage fails with `XdStorageRuntimeException` naming the cause and
   the fix — before the storage folder is locked or touched. Nothing degrades silently: until
   2.2.0 a JRE or a read-only working directory went on with *dummy* wrappers behind one ERROR
   line (broken `equals`/`hashCode`).

A build-time wrapper is accepted only if it was generated for the class **as it is loaded
now**: the generator records a fingerprint of the class and the engine version next to every
wrapper, and the engine checks it when it takes the wrapper. One that does not match is
*stale* — the class or the engine changed after the last build. On a JDK the engine compiles a
fresh wrapper and logs one WARN; on a JRE the first use of the class fails with the same
exception, naming the stale wrapper. Either way: **rebuild the application**.

What the entity classes themselves must satisfy is unchanged:

- Every persisted field follows **JavaBeans**: a getter AND a setter. A field that has a
  getter but no setter (a common slip on generated/Lombok-annotated collections) is **dropped
  from persistence with a WARN** — it is never written and never restored; nothing round-trips.
  The engine logs the drop once per class at metadata load; check the WARN before assuming a
  collection "did not save".
- Entity classes must be **top-level** (nested classes cannot be compiled on JDK 9+).

## Running on a JRE

Since 2.2.0 ([task 2.2.0/08](tasks/2.2.0/08-fp3-jre-aot.md)). Three steps: generate the wrappers
when the application is built, ship a runtime without the compiler, run. The quickstart does
all three without Docker in
[`examples/quickstart/run-on-jre.sh`](../examples/quickstart/run-on-jre.sh) — the demo runs from
a read-only working directory, and inside a sandbox whose whole root filesystem is read-only —
and packages the same steps in its `Dockerfile`.

### 1. Generate the wrappers at build time — Maven

```xml
<plugin>
    <groupId>io.github.xdstorage</groupId>
    <artifactId>xdstorage-codegen-maven-plugin</artifactId>
    <version>2.2.0</version>                 <!-- the engine's version -->
    <executions>
        <execution>
            <goals>
                <goal>generate-wrappers</goal> <!-- bound to the process-classes phase -->
            </goals>
        </execution>
    </executions>
</plugin>
```

The goal scans the application's classes **and its whole compile classpath** — the engine's own
classes are entities too — and writes three wrapper classes per entity plus the manifest into
`target/classes`, so they end up inside the application jar. It uses the generator of the
engine version the application depends on (the plugin carries no engine of its own).

| Parameter | Property | Default | Meaning |
|---|---|---|---|
| `classesDirectory` | — | `${project.build.outputDirectory}` | scanned; where the wrappers and the manifest go |
| `additionalRoots` | — | — | more directories and jars to scan for entity classes |
| `release` | `xdstorage.codegen.release` | `maven.compiler.release`, else `8` | the Java release the wrappers are compiled for |
| `skip` | `xdstorage.codegen.skip` | `false` | skip the generation |

A wrapper compiled for 8 runs on every later Java. The goal is incremental: a wrapper whose
class and engine version are unchanged is not written again (≈ 0.17 s against ≈ 0.7 s cold for
the quickstart — BENCHMARKS § Cold start).

The plugin is published under the engine's version. Until a release carries it, build it from
the repository: `mvn install -DskipTests` in the root, then
`mvn -f tools/xdstorage-codegen-maven-plugin/pom.xml install`.

### Other build tools — the command line

```bash
java -cp <xdstorage.jar and its dependencies> io.github.xdstorage.tools.GenerateWrappers \
     --release 8 --output <classes directory> <classes directory> <the engine's jar and every jar that holds entities>
```

In Gradle, a `JavaExec` task after `classes` with `classpath = sourceSets.main.runtimeClasspath`
and those arguments (`sourceSets.main.output.classesDirs` + the runtime classpath entries as the
roots). Exit status 1 is a usage error, 2 a failed generation; the stdout line carries the
counts.

### 2. A runtime without the compiler

A jlink image of the modules the engine and its dependencies need at runtime (the list
`run-on-jre.sh` links):

```
jlink --add-modules java.base,java.logging,java.management,java.naming,java.transaction.xa,java.xml,java.sql,jdk.unsupported \
      --strip-debug --no-header-files --no-man-pages --output target/jre
```

Add whatever your own dependencies need (a logging backend may ask for more modules). A vendor
JRE works the same — any runtime in which `jdk.compiler` is absent. Verified on a jlink image
of OpenJDK 21; the bytecode is `release=8`.

### 3. Run

`java -cp <application jar:dependencies> …` — nothing special about it. The working directory
may be read-only; the storage folder must be writable, as always.

### What you will see when a wrapper is missing

| Situation | On a JDK | On a JRE |
|---|---|---|
| no wrapper for the class | compiled when first needed (in the quickstart: at the first load), into `./gencode` | **the open fails**: "… no pre-generated wrapper is on the classpath and this runtime has no Java compiler … Fix: generate the wrappers at build time with the xdstorage-codegen Maven plugin (docs/EMBEDDING.md, Running on a JRE), or run on a JDK." |
| the wrapper is stale | one WARN, compiled when first needed | **the first use of the class fails**: "… it is STALE — generated for fingerprint …, the class as loaded has … Fix: rebuild the application to refresh the wrappers (a clean build), or run on a JDK." |
| `./gencode` cannot be created | **the open fails**: "… the working directory is not writable. Fix: generate the wrappers at build time …, or start the application from a writable working directory." | never reached — no directory is needed |

The exception is the cause of the engine's regular `XdStorageRuntimeException`; its message is
repeated on the outermost line, where a log shows it.

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
- **A crash DURING a commit can leave the transaction half applied.** One commit is appended to
  several journals in a row — the data journal and the id-index journal of every class it
  touches — and nothing yet records that the commit as a whole reached its point of no return
  ([50](tasks/2.2.0/50-commit-marker-cross-resource-atomicity.md)). Commits that RETURNED are
  safe after a restart; a process killed while a commit was in flight is not guaranteed to be
  either fully there or fully absent. One resource at a time (one class, one commit) is not
  exempt: its data and its id-index are two journals.
- **A commit journal damaged in its middle is refused, not skipped** (WAL, docs/tasks/2.2.0/06).
  A truncated tail — a crash mid-append — is discarded as before, and the warning names the
  exact commit serials lost. But a record that cannot be parsed while further complete batches
  follow it in the same file is damage: the storage refuses to open that resource with
  `… is CORRUPT in batch N …`, instead of silently dropping every later commit in the journal
  (the behavior before 2.2.0). Restore the folder from a backup, or rebuild the journal from
  the base file knowingly.
- Resource files carry an EOF sentinel; a truncated file fails loudly at open. Pre-sentinel legacy
  storages: open once with `acceptMissingEofSentinel(true)` and let the first commit rewrite the files.
- Stale `*.tmp` files from a previous crash are swept at open.
- **Trust boundary:** the files carry class names the engine instantiates — open only a folder
  your application wrote, or one whose author you trust. The XML reader processes no DTD: a
  `<!DOCTYPE>` in an XML-format folder is refused loudly (the external-entity hardening,
  docs/tasks/2.2.0/36); what a foreign folder may do is designed in docs/tasks/2.2.0/37.

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
| Runtime | any JRE/JDK 8+ **for the bytecode**. A **JDK** when the engine generates the wrappers itself (§ Codegen requirements); with build-time wrappers a **JRE** is enough (§ Running on a JRE — verified on a jlink image of OpenJDK 21) |
| Building XdStorage itself | JDK **17+**, Maven **3.6.3+** (enforced) |
| CI / tested runtimes | Temurin **17** (the full-suite gate + the nightly ×3) and Temurin **21** (the quick-gate profile on pushes/PRs + the full suite nightly — JDK21, task 27); 21 is also the canonical-baseline runtime. Bytecode stays `release=8` either way |

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

**Commit-journal batch trailers (2.2.0, docs/tasks/2.2.0/06).** Every batch a 2.2.0 library
appends to a commit journal is followed by one comment line naming its commit serial and its
transaction (`# xdstorage-meta serial=17 tx=<id>`) and then by the usual `# xdstorage-eof`.
Additive: nothing migrates, the sentinel line is unchanged, and a 2.1.x library skips the
trailer as a comment and replays such a journal exactly as before. A 2.2.0 library reads
journals without trailers (every version before it) unchanged. The reader checks each trailer
against its batch and refuses the journal when the two disagree.

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

