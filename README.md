# XdStorage

**XdStorage** is an embedded, file-backed **object storage for Java**. Plain JavaBeans/POJOs annotated with a
small set of annotations are persisted into a folder of human-readable files with **ACID transactions, MVCC
snapshot reads, secondary and child indexes, a criteria-based search engine, triggers, and pluggable on-disk
formats** (YAML, XML). There is no server, no native code and no external dependency to operate — the whole
database lives next to your application, in one folder you can back up with `cp -r`. And because the
format is readable, the database is **directly machine-readable too** — grep it in a script, diff it in a
code review, commit it as a test fixture, or point an AI agent at it with no transformation layer in
between.

## Quick start

```java
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import java.util.Collection;

// 1. Open (or get) a named storage in a folder. YAML is the default format.
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("mydb", "./data/mydb", 250, FilesFormat.YAML);

// 2. All operations run inside a transaction.
Ship ship = new Ship();
ship.setName("Alpha");
IXdStorageTransaction tx = storage.beginTransaction(15000L);   // 15 s timeout
try {
    ship.setTonnage(100);
    ship.addCrewMember(new CrewMember("Pilot"));

    storage.save(ship, tx);            // insert; the id is generated and set on the object
    tx.commit();
} catch (Throwable t) {
    tx.rollback();
    throw t;
}

// 3. Read it back by id, or find it with the search engine.
IXdStorageTransaction readTx = storage.beginTransaction();
try {
    Ship loaded = storage.load(Ship.class, ship.getId(), readTx);

    Collection<Ship> found = storage.load(Ship.class, "ship_idx",
            new XdStorageSearchQuery(new XdStoragePrimaryCriterion(
                    IXdStorageCriterionOperator.EQUALS, "Alpha"), null), readTx);

    readTx.commit();
} catch (Throwable t) {
    readTx.rollback();
    throw t;
}

// 4. Release the storage (unregisters it from the provider and stops its threads).
storage.shutdown();
```

The asserted end-to-end lifecycle lives in the green
[`QuickstartTest`](examples/quickstart/src/test/java/quickstart/QuickstartTest.java)
(open → bulk save → indexed query → commit → **reopen from disk** → load again → shutdown);
range operators (`GT`/`GTE`/`LT`/`LTE`/`BETWEEN`/`IN`), `ORDER BY`/`LIMIT`/`OFFSET` and
`count()` work on the same query type — the test asserts them before and after the reopen.
In a query, the *primary criterion* evaluates against that index's primary value — the
**first** field in `indexFieldNames` (here the ship's `name`), not the object id. The imports
in the example are its complete set; the page's other snippets add
`io.github.xdstorage.search.query.{XdStorageRange, XdStorageQueryPlan}`, `java.util.Arrays`,
the annotation package `io.github.xdstorage.annotations.*`, and
`io.github.xdstorage.XdStoragePolicy` for the storage-policy examples.

**Query plans** (`storage.explain(Class, indexName, query)`): see the path the engine would
take **before** running the query — `INDEX_SEEK` (an `EQUALS` primary criterion on the
search tree), `INDEX_RANGE_WALK` (a `GT`/`GTE`/`LT`/`LTE`/`BETWEEN`/`IN` criterion on a
B+tree id-index class, with the bound shape reported) or `SCAN` (no criteria,
`NOT_EQUALS`/non-seekable criteria, an index tree not built yet, or the FP-1a fallback: a
range on a class whose **id index is Hash** degrades to a scan, with the reason in the
plan). The plan also lists which secondary criteria ride as post-filters and the active
`ORDER BY`/`LIMIT`/`OFFSET` shaping. Read-only: no records are materialized and no
transaction is required.

From Maven Central (Java 8+ bytecode; runtime dependencies: `slf4j-api` and `commons-lang3` only —
no logging backend ships, bind your own):

```xml
<dependency>
    <groupId>io.github.xdstorage</groupId>
    <artifactId>xdstorage</artifactId>
    <version>2.1.3</version>
</dependency>
```

The latest main is built from source with `mvn install`.

On a JDK the engine compiles the wrapper classes it needs itself (when each entity class is
first used, into `./gencode`). To run on a **JRE**, or from a read-only working directory,
generate them while the application is built — the `xdstorage-codegen-maven-plugin` does it
([EMBEDDING § Running on a JRE](docs/EMBEDDING.md#running-on-a-jre)).

## What's new in 2.1.0

- **Concurrent writes unlocked.** Index mutations stage per-transaction and apply at commit
  under a total acquisition order: 10 concurrent writers measure **121–158 tx/s (p99
  84–113 ms)** where 2.0.x collapsed the same load to ~1 tx/s through lock-timeout cycles.
- **Foreign keys and cascade save.** `@XdStorageAutoSave` + `@XdStorageParentObject` buy the
  cascade upsert, persistent parent↔child navigation without a data scan, and delete vetoes.
  The cost is measured, not marketing: FK **writes** run 1.4–160× slower than FK-off
  (cascade-save 23–160×, child delete 12–73×, parent update 1.4–12.5×; reads free) —
  [GUIDE § Foreign keys](docs/GUIDE.md#foreign-keys-and-the-secondary-keys) has the
  rules; enable FK for integrity-critical graphs, not hot write loops.
- **Query power pack.** Range primaries (`GT`/`GTE`/`LT`/`LTE`/`BETWEEN`/`IN`), `ORDER BY`/
  `LIMIT`/`OFFSET`, `count()`, and the `explain()` plans above.
- **`verify(folder)`** — a read-only integrity walk for the after-a-power-loss recipe
  (`OK` / `WARNINGS` / `CORRUPT`; never repairs, never takes the lock).
- **The write-scaling wall is gone.** Data, id-index and search-key commits journal O(delta)
  appends, and a clean `shutdown()` checkpoints every journal: the small-write cost is flat
  **0.13 ms/tx at 5·10⁴ objects** (was 64.9), and after a clean close a power loss loses
  nothing even without fsync.
- **Commit-time conflict revalidation.** First-committer-wins now covers the whole
  stage→commit window — the lost-update window is closed; the loser fails loudly with the
  data intact.

## When to take it

- Your data is naturally an **object graph of JavaBeans** — documents with children, catalogs, sessions,
  configs, work queues, domain events — and a mapping layer (JDBC/ORM) is exactly the overhead you are
  trying to delete.
- Scale fits **10⁴–10⁵ objects per class** (a few GB of folder at most; the measured working-set cost is
  ~1.4–2 KB heap per object).
- Writes run **~10³ transactions/s** journaled (measured ~3.1k–8.1k tx/s on the canonical shapes,
  the small-write cost flat across the design point); reads are point-lookup-heavy (indexed reads
  measure 10³–10⁵ ops/s).
- **Operability matters**: files you can `cat`, `grep` and diff in code review, `cp -r` backups while
  closed, no DBA, no native library, Java 8 bytecode.
- **Test fixtures and golden data** stored as YAML — committed, diffed and reviewed in git next to
  the code that asserts them ([GUIDE § the readable-on-disk
  story](docs/GUIDE.md#the-readable-on-disk-story-fixtures-review-and-machine-readable-storage)).
- You want **ACID + snapshot reads + triggers without a server** — ORM-like semantics without ORM's
  runtime cost and schema.

## When not (yet)

- You need **>10³ writes/s sustained**, multi-GB or heap-independent datasets → RocksDB / MapDB.
- **Multiple processes** must share one store (a folder is exclusively locked) → Chronicle Map, or put a
  server in front.
- You need **SQL, joins, analytics or full-text search** → H2 / SQLite / Lucene.
- You run **Graal native-image** — not tested: the wrappers can be generated at build time since
  2.2.0, which removes the compiler from the picture, but the native-image leg has not been run
  ([2.2.0/08](docs/tasks/2.2.0/08-fp3-jre-aot.md)). **JRE-only containers are fine**: generate the
  wrappers at build time and no JDK is needed at runtime
  ([EMBEDDING § Running on a JRE](docs/EMBEDDING.md#running-on-a-jre)).
- You require multi-year vendor-support guarantees — a young single-maintainer project; judge the
  1,300+-test suite, the published benchmark runs and the known-flakes documentation on their merits.

## How it compares

| | Model | Transactions | What you build yourself |
|---|---|---|---|
| **XdStorage** | annotated POJO graphs | ACID 2PC, MVCC snapshots, triggers run inside the commit | nothing — the annotated class *is* the schema |
| H2 / SQLite / HSQLDB | relational | full ACID | schema + a JDBC/ORM mapping layer |
| MapDB | `Map`/`Set` over a pluggable store | optional ACID | serialization, indexing, query layer |
| Xodus | KV snapshots + entity store | ACID, MVCC | the object-graph layout; typed queries live in a separate layer |
| EclipseStore | native Java object graphs | lazy load/save, no query or MVCC layer | indexing, querying |
| RocksDB | LSM key-value, `byte[]` | atomic write batches only | the entire object/query layer (+ a JNI dependency) |

The full table (LMDB, Chronicle Map, H2 MVStore), the sourced order-of-magnitude numbers and the
"where it wins / where it loses" analysis: [docs/DECISION-GUIDE.md](docs/DECISION-GUIDE.md).

## The honest limitation: write throughput

Writes were the project's known weak spot — measured, and **the fix has landed in 2.1.0**
(journals for the data files, the id index and the search keys, with a checkpoint on clean
shutdown): the small-write cost is now **flat 0.13–0.27 ms/tx from 10³ to 5·10⁴
objects** (it degraded linearly to 64.9 ms/tx at 5·10⁴ before), and the canonical write shapes
measure **~3.1k tx/s journal-off / ~8k tx/s journal-on** on data-heavy writes (was 348/370;
the 2.6× journal ratio re-confirmed on the 2026-11-21 baseline). That is still one to two orders
below raw KV stores, and two frontiers remain: **concurrent** write throughput on the heavy
indexed shapes (10 writers measure 121–158 tx/s aggregate — the 2.0.x convoy is fixed, the
per-writer cost is the frontier), and **relation writes**: foreign-key maintenance costs up to
160× vs FK-off (cascade-save 23–160×, child delete 12–73×, parent update 1.4–12.5×), scaling
linearly with the tree size (GUIDE § Foreign keys) — enable FK for integrity, not for hot write
loops. The indexed save+delete shape carries a
-24% correctness-wave cost, attributed honestly in BENCHMARKS.md § the honest delta.
**The analysis, design, and measurements are public**:
[docs/W4-DESIGN.md](docs/W4-DESIGN.md),
[docs/perf/w4a-write-scaling.md](docs/perf/w4a-write-scaling.md), the full before/after
canonical delta in [docs/BENCHMARKS.md](docs/BENCHMARKS.md). Also know before adopting:
power-loss safety is opt-in (`durableWrites(true)`, ~95× write cost on the canonical shape —
fsync binds; by default commits are process-crash-safe, and a clean shutdown checkpoints every
journal). Every limit, with numbers:
DECISION-GUIDE § ["Where XdStorage loses today"](docs/DECISION-GUIDE.md#where-xdstorage-loses-today-read-before-committing).

## The full guide

The deep reference moved out of this README — [docs/GUIDE.md](docs/GUIDE.md) carries it, section for
section. **The API reference is the javadoc**: the facade javadoc
(`IXdStorage`/`IXdFileStorage`/`XdStorageProvider`) mirrors the EMBEDDING.md contract — the
thread-transaction binding, rollback-only marks, deferred-id timing and folder-lock rules are
documented on the methods themselves — and the published jar's pages live at
`https://javadoc.io/doc/io.github.xdstorage/xdstorage/latest`. The build enforces the coverage:
`mvn javadoc:javadoc -Ddoclint=missing` must stay green, and the release profile fails on any
javadoc warning. Guide contents:

- [Features at a glance](docs/GUIDE.md#features-at-a-glance) — the complete capability list.
- [Terminology](docs/GUIDE.md#terminology) — the glossary every page conforms to.
- [Defining the data model](docs/GUIDE.md#defining-the-data-model) — entity rules, storage policies,
  the primary id index, search indexes, field properties, cascade save (`@XdStorageAutoSave`),
  [foreign keys and the secondary
  keys](docs/GUIDE.md#foreign-keys-and-the-secondary-keys), parent references and lazy children.
- [Opening and closing a storage](docs/GUIDE.md#opening-and-closing-a-storage) — the provider registry,
  the exclusive folder lock, `XdStorageConfig`, `shutdown()` semantics.
- [Transactions](docs/GUIDE.md#transactions) — explicit transactions, timeouts, 2PC semantics,
  rollback-only (with first-cause capture: `getRollbackOnlyCause()` names who poisoned the transaction
  and the commit failure chains the root exception), derived transactions for triggers.
- [CRUD operations](docs/GUIDE.md#crud-operations) — save/has/load/update/delete and bulk forms.
- [The search engine](docs/GUIDE.md#search-engine) — criteria and operators, the plan/fallback matrix,
  `ORDER BY`/`LIMIT`/`OFFSET`/`count`, `explain()` query plans, and the cost model.
- [Triggers](docs/GUIDE.md#triggers) · [Identifier generation and
  observers](docs/GUIDE.md#identifier-generation-and-observers) · [Streaming reads and predicate
  filtering](docs/GUIDE.md#streaming-reads-and-predicate-filtering).
- [Serialization formats](docs/GUIDE.md#serialization-formats) — incl. [the readable-on-disk
  story](docs/GUIDE.md#the-readable-on-disk-story-fixtures-review-and-machine-readable-storage)
  (fixtures, review, AI agents) · [Structure updates (schema
  migration)](docs/GUIDE.md#structure-updates-schema-migration).
- [Operability: verify, backups, and after a power loss](docs/GUIDE.md#operability-verify-backups-and-after-a-power-loss).
- [Concurrency model and internals](docs/GUIDE.md#concurrency-model-and-internals) ·
  [Performance](docs/GUIDE.md#performance) — the measured cost model and the tuning recommendations.
- [EMBEDDING.md](docs/EMBEDDING.md) — the operational contract: threads, statics, codegen, lifecycle,
  durability, compatibility matrix.

## Building and testing

```bash
mvn test           # full suite (unit + integration + stress), single surefire fork with -Xmx2g
mvn install        # build the jar and install it locally
mvn -Prelease -Dgpg.skip=true verify   # release-shaped build: binary + sources + javadoc jars
```

Notes:

- The build pins `--release 8` — post-JDK-8 API usage fails compilation even on a JDK 17 toolchain
  (the build itself enforces JDK 17+ / Maven 3.6.3+).
- Tested runtimes (CI): Temurin **17** (the full-suite gate on every push + the nightly ×3) and
  Temurin **21** (the quick-gate profile on pushes/PRs + the full suite nightly). The library
  bytecode remains `release=8`; 21 is also the runtime behind the canonical benchmark baseline
  (BENCHMARKS.md) — see docs/tasks/2.1.0/27-jdk21-ci-matrix.md.
- Test storages are created under `./target/`; stale storage folders from interrupted runs can pollute a
  re-run, so prefer `mvn clean test` when tests misbehave.
- The full suite is run in a single fork; rare stress flakes under extreme machine load are documented
  in [docs/FLAKY.md](docs/FLAKY.md).
- Releases are cut by pushing a `v*` tag (`.github/workflows/release.yml`); the procedure is
  [docs/RELEASE-PLAN.md](docs/RELEASE-PLAN.md).

## Project documentation

| Page | Contents |
|---|---|
| [docs/GUIDE.md](docs/GUIDE.md) | the full reference manual (all sections above) |
| [docs/ANALYSIS-ARCHITECTURE.md](docs/ANALYSIS-ARCHITECTURE.md) | the full engine specification: architecture, modules, transaction model, indexes, serialization, FK, migration, codegen, the MVCC cache |
| [docs/PRODUCT_REVIEW.md](docs/PRODUCT_REVIEW.md) | an external product review: the evaluator's positives, gaps, and the adoption criteria |
| [docs/DECISION-GUIDE.md](docs/DECISION-GUIDE.md) | the competitive comparison, the design point, the honest limits |
| [docs/EMBEDDING.md](docs/EMBEDDING.md) | the operational contract: lifecycle, threads, codegen, durability, compatibility |
| [docs/BENCHMARKS.md](docs/BENCHMARKS.md) | the JMH methodology and the canonical baseline, with run manifests |
| [docs/PERFORMANCE-RECOMMENDATIONS.md](docs/PERFORMANCE-RECOMMENDATIONS.md) | the configuration-tuning study (fragment size, B+tree `t`, index types) |
| [docs/W4-DESIGN.md](docs/W4-DESIGN.md) | the W-4 write-path design record — journals, the measured acceptance |
| [docs/WRITE-PATH-DESIGN.md](docs/WRITE-PATH-DESIGN.md) | the journal-based commit design — the write-path roadmap |
| [docs/OPTIMIZATION-PLAN.md](docs/OPTIMIZATION-PLAN.md) | the performance/architecture optimization backlog |
| [docs/SRE-OPTIMIZATION-PLAN.md](docs/SRE-OPTIMIZATION-PLAN.md) | the operational hardening plan (durability, locking, metrics) |
| [docs/MASTER-PLAN.md](docs/MASTER-PLAN.md) | the roadmap |
| [docs/ARCHITECT-EVALUATION.md](docs/ARCHITECT-EVALUATION.md) | the outside-architect review |
| [docs/FLAKY.md](docs/FLAKY.md) | known flaky test families and quarantine conventions |
| [docs/RELEASE-PLAN.md](docs/RELEASE-PLAN.md) | release engineering |
| [examples/quickstart](examples/quickstart/README.md) | the runnable end-to-end sample (the code behind the example) |
