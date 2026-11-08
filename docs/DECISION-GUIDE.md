# Choosing XdStorage — a decision guide

For the architect or tech lead evaluating embedded persistence for a JVM application.
Compiled from the outside-architect evaluation ([ARCHITECT-EVALUATION.md](ARCHITECT-EVALUATION.md)
§3–§4) and re-grounded in the canonical benchmark baseline
([BENCHMARKS.md](BENCHMARKS.md), 2026-10-22) — every number below cites a section there.

## The one-paragraph positioning

> **XdStorage is for JVM applications that want to persist their actual domain objects —
> graphs, not rows or bytes — with ACID transactions, declarative indexes and triggers,
> in a human-readable folder, with zero native dependencies and zero servers.** The
> design point is **10⁴–10⁵ objects per class** with **thousands of journaled
> write-transactions per second** (batched commits; unbatched per-object writes at 10⁵ scale measure ~10¹–10² tx/s —
> [measured](BENCHMARKS.md#design-point--the-n50k-boundary-measurement-n50k-docstasks21020))
> with indexed reads from thousands to hundreds of thousands of ops/s depending on
> cache residency; beyond that, choose RocksDB/MapDB and build the object layer yourself.

## How it compares (order-of-magnitude, the honest framing)

Ballpark numbers for the alternatives come from public materials and are workload-dependent;
XdStorage numbers are its own canonical baseline (BENCHMARKS.md § Canonical baseline, JDK 21,
quiet machine, generated tables). The point is the *gaps*, not the digits.

| Product | Model | Transactions / MVCC | Query capability | What you build yourself | XdStorage's edge / gap |
|---|---|---|---|---|---|
| **XdStorage 2.1** | Annotated POJO graphs; 3 placement policies | ACID 2PC; MVCC snapshot/RC reads; triggers in-commit | Criteria engine, secondary+child indexes, range ops, `ORDER BY`/`LIMIT`/`count` | nothing — the annotated class *is* the schema | — |
| **MapDB 3** | `Map`/`Set` over pluggable store | Optional ACID; MVCC store | none (DIY secondary maps) | serialization, indexing, query layer, domain model | XdStorage gives the model + queries; MapDB wins raw simple-op throughput |
| **JetBrains Xodus** | KV snapshots + entity store | ACID, MVCC | scans; rich queries only via DNQ layer | object-graph policies, typed queries | production-proven (YouTrack); no object model |
| **RocksDB** | LSM key-value, `byte[]` | atomic WriteBatch only | none | the entire object/serialization/query layer + JNI dep | 10⁵–10⁶+ writes/s vs XdStorage's ~10³ journaled write-tx/s — choose it when raw write throughput dominates |
| **LMDB (lmdbjava)** | memory-mapped B+tree | single writer, MVCC readers | cursor scans | everything above + native lib | ~10⁶ reads/s; size-vs-address-space limits |
| **Chronicle Map** | off-heap concurrent map | none | none | marshalling, transactions | multi-process shared memory (unique); latency-oriented, not ACID |
| **H2 MVStore** | B+tree maps, MVCC | per-map commit | none (that is H2-SQL's job) | same as MapDB | pure Java foundation; no indexes/search of its own |
| **H2 / SQLite / HSQLDB** | relational | full ACID | SQL — the benchmark everything is measured against | schema + a mapping layer (JDBC/ORM) | unmatched tooling; XdStorage is for when the mapping layer is the pain, not the answer |
| **EclipseStore** | native Java object-graph persistence | lazy/storing semantics, not MVCC queries | none — data lives in heap, query in memory | indexing, querying | same heap≈DB ceiling as XdStorage, without its indexes/queries |

## Where XdStorage wins outright

1. **The typed domain model *plus* a query engine *plus* triggers.** The annotated class is
   the schema; search indexes, child indexes and insert/update/delete triggers are
   annotations on it. Every alternative makes you build at least one of those layers.
2. **Human-readable, pluggable on-disk format.** YAML (default) / XML folders you can `cat`,
   `grep` and diff in code review; custom formats are an SPI. Nobody else in this class
   offers it. Pre-sentinel legacy folders and format generations are handled explicitly
   (EMBEDDING.md § Compatibility).
3. **Real ACID + MVCC semantics at the object level** — 2PC across resources, snapshot or
   READ_COMMITTED reads, triggers inside the committing transaction, deferred-id observers.
4. **Zero native dependencies, Java 8 bytecode, logging via SLF4J only** — deploys anywhere
   a JVM goes, including legacy estates; your logging backend stays yours.
5. **Engineering transparency** — generated benchmark tables bound to archived run manifests
   (BENCHMARKS.md), a flake ledger (FLAKY.md), design records with rejected alternatives.

## Where XdStorage loses today (read before committing)

1. **Write throughput vs KV stores: one to two orders of magnitude.** Writes are
   commit-bound, but the O(N) index rewrite is gone since W-4a (the hash id-index commit
   journal): the canonical write shapes now measure **~3,100 tx/s (journal off) and ~9,000
   tx/s (journal on)** on the data-heavy shape, and the small-write cost is FLAT across the
   design point (0.13–0.27 ms/tx from 10³ to 5·10⁴ objects — BENCHMARKS § Canonical +
   docs/perf/w4a-write-scaling.md). The remaining frontier: search key-tree rewrites
   (W-4b pending) and sustained multi-10³ writes/s; fsync-grade durability costs ~107× on
   the canonical shape (`writeDurable`).
2. **Power loss loses recent commits by default.** Default writes are crash-safe, not
   power-loss-safe; flip `durableWrites(true)` (~107× write cost on the canonical shape)
   when fsync-grade durability matters. The verified recovery matrix and the after-a-power-loss recipe:
   [EMBEDDING.md § After a power loss](EMBEDDING.md#after-a-power-loss).
3. **Heap ≈ working set.** The record cache holds the working set; there is no eviction
   until disk-anchored scans ship (W-4). Measured: **~1.4–2.0 KB heap per object** at
   N=5k–50k ([design-point probe](BENCHMARKS.md#design-point--the-n50k-boundary-measurement-n50k-docstasks21020))
   — fine at 10⁴–10⁵ objects/class (≈70–200 MB per 10⁵ objects for typical entities);
   disqualifying beyond that — for now.
4. **Single process per folder.** The storage folder is exclusively locked
   (EMBEDDING.md § Lifecycle). Read-only tooling operates on a copy/backup, not live —
   except `XdStorageProvider.verify(folder)`, the read-only integrity walk (VERIFY, 2.1.0):
   it takes no lock and reports a live storage as IN USE ("results unreliable") instead of
   refusing, so a folder can be checked before deciding on recovery.
5. **Runtime wrapper codegen** needs a JDK (not JRE) and a writable `./gencode`
   (EMBEDDING.md § Codegen requirements). The AOT build-time-generation fix (FP-3) is on the
   roadmap; until then, containers must ship a JDK.
5. **Adoption maturity**: single-maintainer project, first public release 2026-10. Judge the
   docs and the test estate (770+ tests, flake ledger, kill-9 recovery suites) on their merits.

## Choose XdStorage when most of these hold

- The data is naturally an **object graph of JavaBeans** (documents with children, catalogs,
  configs, sessions, work queues, domain events) and a mapping layer is exactly the overhead
  you are trying to delete.
- Scale is **10⁴–10⁵ objects per class**, a few GB of folder at most.
- Write rate **≤ ~10³ tx/s** (measured ~3k–9k tx/s on the canonical write shapes post-W-4a), reads point-lookup-heavy (indexed reads: 10³–10⁵ ops/s
  depending on residency — BENCHMARKS § Canonical, `loadById_indexBaseline`,
  `searchByPrimaryEquals`).
- **Operability matters**: files you can inspect, a folder you can back up with `cp -r`
  while closed ([tested guarantee](EMBEDDING.md#backup-and-restore)), no server, no native
  library.
- You want **ACID + snapshot reads + triggers without a DBA**, and lazy loading/id
  generation semantics ORM gives you — without ORM's runtime cost and schema.
- Java 8 bytecode or a no-native-lib constraint rules out SQLite/RocksDB/LMDB.
- The data benefits from being **readable by non-JVM tools** (auditors, scripts, editors).

**Concretely good fits:** desktop/rich-client apps, IDE plugins and developer tools,
edge/IoT gateways, k8s operators' local state, microservice-local domain stores below the
SQL line, build/CI tooling state, test fixtures and golden-data sets (readable diffs!),
embedded configuration/feature-flag stores with queries and triggers.

## Do not choose XdStorage (yet) when

- You need **>10³ writes/s sustained**, multi-GB data, or heap-independent datasets →
  RocksDB / MapDB.
- **Multiple processes** must share one store → Chronicle Map, or put a server in front.
- You need **SQL, joins, analytics or full-text search** → H2/SQLite/Lucene.
- You run **JRE-only containers or Graal native-image** today → blocked by runtime codegen
  until FP-3 (AOT wrapper generation) ships; the line moves then.
- You require multi-year vendor support guarantees — see the maturity note above.

## The numbers, in one table (canonical baseline — BENCHMARKS.md)

| What | Measured | Scenario in BENCHMARKS.md |
|---|---|---|
| Point read by id (cache-resident) | ~231k ops/s | `loadById_indexBaseline` |
| Indexed EQUALS search | ~227k ops/s (≈ a plain id load) | `searchByPrimaryEquals` |
| Selective range (`BETWEEN`, `GT`) | ~24k / ~14k ops/s (vs ~1.6k for a scan) | `searchByPrimaryBetweenSelective`, `searchByPrimaryGT` |
| Paged range (`ORDER BY` + `LIMIT 50`) | ~17.8k ops/s | `searchByPrimaryBetweenPagedLimit50` |
| Scan path (no selective primary) | ~1.6k ops/s — O(dataset), keep primaries `EQUALS`/selective | `searchByScanNotEqualsPrimary` |
| Read-only transaction (begin→load→commit) | ~78.5k ops/s | `readOnlyTx` |
| Indexed save+delete (entity with children) | ~752 ops/s (flat at tiny N — the scaling story is below) | `saveAndDeleteIndexedShipWithCrew` |
| Indexed rename/update | ~2.0k ops/s (W-4b pending on the key-tree path) | `updateShipRenameSearchKey` |
| Data-heavy write (journal on/off) | **~9,000 / ~3,100 tx/s** — the O(N) index rewrite removed by W-4a | `writeDataHeavy` |
| `durableWrites=true` | ~82 ops/s — **fsync costs ~107×** on this shape; off by default | `writeDurable` |
| Batch commit | 2,435 tx/s @ 10 obj → 31 tx/s @ 1000 obj; objects/s now **flat ~24k–31k** at every batch size | `commitBatch` |
| Small write at N=50k | **0.13 ms/tx** (flat from 10³ to 5·10⁴ — was 64.9 ms/tx pre-W-4a) | docs/perf/w4a-write-scaling.md |

Conditions: 2026-10-24 (post-W-4a index journaling), JDK 21.0.7, quiet Linux box —
**AMD Ryzen 9 5900X (12 cores / 24 threads), 64 GB RAM, NVMe SSD (970 EVO Plus), ext4** —
WARN-console logging, YAML format. The storage pair matters: the durability cost (~107×
with `durableWrites`) is an NVMe-ext4 fsync cost — an HDD shifts it materially. Full
manifest in `docs/perf/canonical-baseline-2026-10-24/` (the before-set:
`docs/perf/canonical-baseline-2026-10-22/`; the full before/after delta table lives in
BENCHMARKS.md § Before/after W-4a). Re-run: `bash scripts/run-benchmarks.sh full`.

Every number above has its field frame and a per-family "in real terms" reading on
[BENCHMARKS.md](BENCHMARKS.md) (§ In context — what the figures measure, the sourced
order-of-magnitude cross-product comparisons, and the write-gap fix plan).

## Where to go next

- [README.md](../README.md) — the full guide (data model, transactions, search engine).
- [examples/quickstart](../examples/quickstart/README.md) — a runnable end-to-end sample:
  open → tx → save → indexed query → range query → commit → reopen → shutdown.
- [EMBEDDING.md](EMBEDDING.md) — the operational contract (lifecycle, threads, codegen,
  durability, compatibility matrix).
- [PERFORMANCE-RECOMMENDATIONS.md](PERFORMANCE-RECOMMENDATIONS.md) — how index type, fill
  factor and fragment size trade off; the structural knobs.
- [FLAKY.md](FLAKY.md) and [BENCHMARKS.md](BENCHMARKS.md) — how the project measures and
  discloses its own quality. Trust, but verify — the tools to verify are in the repo.
