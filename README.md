# XdStorage

**XdStorage** is an embedded, file-backed **object storage for Java**. Plain JavaBeans/POJOs annotated with a
small set of annotations are persisted into a folder of human-readable files with **ACID transactions, MVCC
snapshot reads, secondary and child indexes, a criteria-based search engine, triggers, and pluggable on-disk
formats** (YAML, XML). There is no server, no native code and no external dependency to operate — the whole
database lives next to your application, in one folder you can back up with `cp -r`.

## Quick start

```java
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.FilesFormat;

// 1. Open (or get) a named storage in a folder. YAML is the default format.
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("mydb", "./data/mydb", 250, FilesFormat.YAML);

// 2. All operations run inside a transaction.
Ship ship = new Ship();
ship.setName("Alpha");
IXdStorageTransaction tx = storage.beginTransaction(15000L);   // 15 s timeout
try {
    ship.setTonnage(100);
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
                    IXdStorageCriterionOperator.EQUALS, "Alpha")), readTx);

    readTx.commit();
} catch (Throwable t) {
    readTx.rollback();
    throw t;
}

// 4. Release the storage (unregisters it from the provider and stops its threads).
storage.shutdown();
```

Another example from the green, asserted lifecycle test
[`QuickstartTest`](examples/quickstart/src/test/java/quickstart/QuickstartTest.java)
(open → bulk save → indexed query → commit → **reopen from disk** → load again → shutdown);
range operators (`GT`/`GTE`/`LT`/`LTE`/`BETWEEN`/`IN`), `ORDER BY`/`LIMIT`/`OFFSET` and
`count()` work on the same query type — the test asserts them before and after the reopen.
In a query, the *primary criterion* evaluates against that index's primary value — the
**first** field in `indexFieldNames` (here `country`), not the object id. Imports:
`io.github.xdstorage.{IXdFileStorage, XdStorageProvider, XdStoragePolicy}`,
`io.github.xdstorage.annotations.*`, `io.github.xdstorage.search.query.*`,
`io.github.xdstorage.transaction.IXdStorageTransaction`,
`java.util.{Arrays, Collection}`.

From Maven Central (Java 8+ bytecode; runtime dependencies: `slf4j-api` and `commons-lang3` only —
no logging backend ships, bind your own):

```xml
<dependency>
    <groupId>io.github.xdstorage</groupId>
    <artifactId>xdstorage</artifactId>
    <version>2.0.0</version>
</dependency>
```

The current development version (2.1.0) is not yet released; build it from source with `mvn install`.

## When to take it

- Your data is naturally an **object graph of JavaBeans** — documents with children, catalogs, sessions,
  configs, work queues, domain events — and a mapping layer (JDBC/ORM) is exactly the overhead you are
  trying to delete.
- Scale fits **10⁴–10⁵ objects per class** (a few GB of folder at most; the measured working-set cost is
  ~1.4–2 KB heap per object).
- Writes run **~10³ transactions/s** journaled (measured ~3.1k–9k tx/s on the canonical shapes,
  the small-write cost flat across the design point); reads are point-lookup-heavy (indexed reads
  measure 10³–10⁵ ops/s).
- **Operability matters**: files you can `cat`, `grep` and diff in code review, `cp -r` backups while
  closed, no DBA, no native library, Java 8 bytecode.
- You want **ACID + snapshot reads + triggers without a server** — ORM-like semantics without ORM's
  runtime cost and schema.

## When not (yet)

- You need **>10³ writes/s sustained**, multi-GB or heap-independent datasets → RocksDB / MapDB.
- **Multiple processes** must share one store (a folder is exclusively locked) → Chronicle Map, or put a
  server in front.
- You need **SQL, joins, analytics or full-text search** → H2 / SQLite / Lucene.
- You run **JRE-only containers or Graal native-image** — runtime wrapper code generation needs a JDK
  (build-time generation is on the roadmap).
- You require multi-year vendor-support guarantees — a young single-maintainer project; judge the
  ~800-test suite, the published benchmark runs and the known-flakes documentation on their merits.

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

Writes were the project's known weak spot — measured, and **the main fix has landed in 2.1.0**
(W-4a, index journaling): the small-write cost is now **flat 0.13–0.27 ms/tx from 10³ to 5·10⁴
objects** (it degraded linearly to 64.9 ms/tx at 5·10⁴ before), and the canonical write shapes
measure **~3.1k tx/s journal-off / ~9k tx/s journal-on** on data-heavy writes (was 348/370).
That is still one to two orders below raw KV stores, and one path remains whole-file: the search
key-tree rewrites (the rename/update scenario, W-4b pending — measured ~2.0k ops/s, +9%).
**The analysis, design, and measurements are public**:
[docs/W4-DESIGN.md](docs/W4-DESIGN.md),
[docs/perf/w4a-write-scaling.md](docs/perf/w4a-write-scaling.md), the full before/after
canonical delta in [docs/BENCHMARKS.md](docs/BENCHMARKS.md). Also know before adopting:
power-loss safety is opt-in (`durableWrites(true)`, ~107× write cost on the canonical shape —
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
- [Defining the data model](docs/GUIDE.md#defining-the-data-model) — entity rules, storage policies,
  the primary id index, search indexes, field properties, cascade save (`@XdStorageAutoSave`),
  parent references and lazy children.
- [Opening and closing a storage](docs/GUIDE.md#opening-and-closing-a-storage) — the provider registry,
  the exclusive folder lock, `XdStorageConfig`, `shutdown()` semantics.
- [Transactions](docs/GUIDE.md#transactions) — explicit transactions, timeouts, 2PC semantics,
  rollback-only (with first-cause capture: `getRollbackOnlyCause()` names who poisoned the transaction
  and the commit failure chains the root exception), derived transactions for triggers.
- [CRUD operations](docs/GUIDE.md#crud-operations) — save/has/load/update/delete and bulk forms.
- [The search engine](docs/GUIDE.md#search-engine) — criteria and operators, the plan/fallback matrix,
  `ORDER BY`/`LIMIT`/`OFFSET`/`count`, and the cost model.
- [Triggers](docs/GUIDE.md#triggers) · [Identifier generation and
  observers](docs/GUIDE.md#identifier-generation-and-observers) · [Streaming reads and predicate
  filtering](docs/GUIDE.md#streaming-reads-and-predicate-filtering).
- [Serialization formats](docs/GUIDE.md#serialization-formats) · [Structure updates (schema
  migration)](docs/GUIDE.md#structure-updates-schema-migration).
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
| [docs/DECISION-GUIDE.md](docs/DECISION-GUIDE.md) | the competitive comparison, the design point, the honest limits |
| [docs/EMBEDDING.md](docs/EMBEDDING.md) | the operational contract: lifecycle, threads, codegen, durability, compatibility |
| [docs/BENCHMARKS.md](docs/BENCHMARKS.md) | the JMH methodology and the canonical baseline, with run manifests |
| [docs/PERFORMANCE-RECOMMENDATIONS.md](docs/PERFORMANCE-RECOMMENDATIONS.md) | the configuration-tuning study (fragment size, B+tree `t`, index types) |
| [docs/WRITE-PATH-DESIGN.md](docs/WRITE-PATH-DESIGN.md) | the journal-based commit design — the write-path roadmap |
| [docs/OPTIMIZATION-PLAN.md](docs/OPTIMIZATION-PLAN.md) | the performance/architecture optimization backlog |
| [docs/SRE-OPTIMIZATION-PLAN.md](docs/SRE-OPTIMIZATION-PLAN.md) | the operational hardening plan (durability, locking, metrics) |
| [docs/MASTER-PLAN.md](docs/MASTER-PLAN.md) | the roadmap |
| [docs/ARCHITECT-EVALUATION.md](docs/ARCHITECT-EVALUATION.md) | the outside-architect review |
| [docs/FLAKY.md](docs/FLAKY.md) | known flaky test families and quarantine conventions |
| [docs/RELEASE-PLAN.md](docs/RELEASE-PLAN.md) | release engineering |
| [examples/quickstart](examples/quickstart/README.md) | the runnable end-to-end sample (the code behind the example) |
