# XdStorage — Architectural Analysis & Specification

**Project:** XdStorage (`io.github.xdstorage:xdstorage`)
**Version:** 2.1.0 (SNAPSHOT)
**Repository:** `./Java/projects/xdstore`
**Artifact:** `io.github.xdstorage/xdstorage` (JAR, Apache 2.0 License)
**Bytecode target:** Java 8 (JDK 8); build/test runtime: JDK 17
**Build system:** Maven 3 (no wrapper; `pom.xml` at project root)

---

## 1. Overview — What Is XdStorage?

XdStorage is an **embedded, ACID-compliant, object-to-file storage engine** for Java. It stores Java objects (POJOs) as human-readable **YAML** or **XML** files on the local filesystem, with full transaction support, B+tree / hash indexes, a criteria-based search engine, triggers, foreign-key constraints, schema migrations, and runtime code generation for wrapper classes.

There are **no native dependencies** and **no external databases** — everything runs in-process with only standard JDK libraries plus `commons-lang3` and `slf4j-api` at compile time.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              APPLICATION CODE                               │
│          (uses annotations, calls XdStorageProvider.newOrGetFileStorage(...))│
└─────────────────────────────────────────────────────────────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                     ▼
    ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
    │  XdStorage       │ │  XdStorage       │ │  IXdFileStorage  │
    │  (implements)    │ │  Provider (facade)│ │  (contract)      │
    └────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘
             │                    │                      │
             └────────────────────┼─────────────────────┘
                                  ▼
                    ┌─────────────────────────┐
                    │   XdStorageServicesLocator│  ← wiring hub (single storage instance)
                    └─────────────────────────┘
                                  │
        ┌──────────┬──────────────┼──────────────┐
        ▼          ▼              ▼              ▼
   ┌────────┐ ┌────────┐  ┌──────────┐ ┌────────────┐
   │Resource │ │Transaction│ │ Trigger   │ │  Search    │
   │Manager  │ │ Manager   │ │ Manager   │ │  Manager   │
   │          │ │           │ │           │ │            │
   └────┬───┘ └────┬──────┘ └────┬──────┘ └─────┬──────┘
        │          │             │              │
        ▼          ▼             ▼              ▼
   ┌──────────────────────────────────────────────────────┐
   │              Resource Cache + MVCC Layer              │
   │   (XDCache2PC protocol: prepare / perform phases)   │
   └──────────────────────────────────────────────────────┘
        │
        ▼
   ┌──────────────────────────────────────────────────────┐
   │         IXdStorageDaoResource (DAO interface)        │
   │   ┌──────────┐  ┌──────────┐  ┌──────────────┐     │
   │   │ Resource  │  │ Id-Index │  │ Search-Index  │     │
   │   │ (data)   │  │ (hash/B+ │  │ (search)     │     │
   │   └────┬─────┘  └────┬─────┘  └──────┬───────┘     │
   └──────────────────────────────────────────────────────┘
        │
        ▼
   ┌──────────────────────────────────────────────────────┐
   │   Serialization (XML or YAML) + Atomic File I/O      │
   │   (atomic rename, EOF sentinel, journaling, fsync)  │
   └──────────────────────────────────────────────────────┘
```

---

## 3. Module Structure

| Module (package) | Files | Purpose |
|---|---|---|
| `io.github.xdstorage` | 4 | Core facade (`XdStorage`, `XdStorageProvider`), config (`XdStorageConfig`), policies (`XdStoragePolicy`), metrics (`XdStorageMetrics`) |
| `io.github.xdstorage.annotations` | 9 | Class-level and field-level annotations (`@XdStorageObjectId`, `@XdStorageObjectPolicy`, `@XdStorageObjectSearchIndex`, `@XdStorageParentObject`, `@XdStorageAutoSave`, etc.) |
| `io.github.xdstorage.btree` | 6 | B+tree implementation (`XdStorageBTree`, `XdStorageBTreeNode`, `XdStorageBTreeId`, invariants, viewer) |
| `io.github.xdstorage.code` | 16 | Runtime code generation: simple wrappers, unmodifiable wrappers, observable wrappers, abstract class generators, block getters/setters generators |
| `io.github.xdstorage.exceptions` | 6 | Exception hierarchy (`XdStorageException` → `XdStorageRuntimeException`, `XdStorageConnectionException`, `XdStorageIOException`, `XdStorageReferenceInvisibleException`) |
| `io.github.xdstorage.factories` | 7 | Cloning framework (`XdStorageSmartCloner`, `XdStorageClonerCollectionProcessor`, `XdStorageClonerArrayProcessor`, `XdStorageClonerFieldPacker/Unpacker`, reference providers) |
| `io.github.xdstorage.foreignkey` | 8 | Foreign key engine (`XdStorageForeignKeyEngine`, registry, resources manager, violation exception, relation model) |
| `io.github.xdstorage.helpers` | 2 | Simple type helpers (`IXdStorageSimpleTypeHelper`, default impl) |
| `io.github.xdstorage.idgeneration` | 7 | ID generation SPI (`IXdStorageIdGenerator`, Long/Int/String generators, Hi-Lo, UUID) |
| `io.github.xdstorage.index` | 7 | Index resource abstraction (`IXdStorageIndexDaoResource`, hash index, B-tree index, index type enum) |
| `io.github.xdstorage.metrics` | 1 | Live operational counters (`XdStorageMetrics`) |
| `io.github.xdstorage.object` | 3 | Object model (`XdStorageObject`, `XdStorageIdentifiableObject`, converter) |
| `io.github.xdstorage.observing` | 3 | Observer service for lazy-loaded objects (`XdStorageAbstractIdObserver`, `XdStorageObserverService`) |
| `io.github.xdstorage.resource` | 13 | Resource management (`XdStorageResourceCache` — the MVCC cache, `XdStorageResource` (DAO impl), `XdStorageAbstractResource`, naming service, canonical instance registry, isolation levels, commit ordering) |
| `io.github.xdstorage.resource.cache` | 8 | **Two-phase commit protocol** for the cache (`XdStorageCacheCommitProtocol` — prepare/perform, rollback, journal) |
| `io.github.xdstorage.search` | 5 | Search engine (`XdStorageSearchManager`, index resources) |
| `io.github.xdstorage.search.query` | 9 | Query DSL (`XdStorageSearchQuery`, criteria, range, primary criterion, logical functions) |
| `io.github.xdstorage.services` | 1 | **ServicesLocator** — the central wiring hub |
| `io.github.xdstorage.structure` | 4 | Schema management (`XdStorageStructureManager`, class structure, structure IDs) |
| `io.github.xdstorage.structure.update` | 5 | Schema migration scripts (`IXdStorageStructureUpdater`, abstract base, search-index updater) |
| `io.github.xdstorage.transaction` | 8 | Transaction model (`XdStorageTransaction`, `XdStorageTransactionManager`, commit state holder, wrapper, resource changes) |
| `io.github.xdstorage.trigger` | 4 | Trigger system (`XdStorageTriggerManager` — CopyOnWriteArrayList dispatch) |
| `io.github.xdstorage.utils` | 25+ | Utilities: reflection, cloning context, object utils, format meta, version, folder verifier, proxy engine, class info, atomic file |
| `io.github.xdstorage.serialization` | 10 | Serialization SPI (`IXdStorageIOFactory`, readers/writers, YAML/XML impl, commit journal, guard) |
| `io.github.xdstorage.serialization.yaml` | 9 | YAML serialization (tokenizer, emitter, block containers, block objects, commit journal) |

**Total source files:** 217 Java source files
**Total test files:** 331 Java test files (in `src/main/test/`)

---

## 4. Core Data Model

### 4.1 Entity Classes (POJOs)

Entities are **plain JavaBeans** with:
- Public no-arg constructor
- Getters/setters for each field
- `@XdStorageObjectId` on the primary key field
- `@XdStorageObjectPolicy` declaring the storage policy
- Optional `@XdStorageObjectSearchIndex` for search indexing
- Optional `@XdStorageParentObject` for embedded references
- Optional `@XdStorageAutoSave` for cascade save

### 4.2 Storage Policies (`XdStoragePolicy`)

| Policy | Description | Use case |
|---|---|---|
| `StoreWithParentObject` | Embedded — stored inside parent's record. Default. | Value objects, small children |
| `StoreAsSingleObject` | One file per object + reference resource entry | Few large objects (e.g. documents) |
| `StoreAsClassObjects` | Batched into shared fragment files with class-level id index | Typical entity workloads |

### 4.3 On-Disk Format

Each storage has a **folder** containing:
- `XdStorageBTreeNode/` — persistent B+tree node files
- `XdStorageBTree/` — persistent B+tree data files
- `<ClassName>/` — data fragment files (`StoreAsClassObjects` policy) or single-object files
- `<ClassName>_refs/` — reference records (for `StoreAsSingleObject`)
- `<ClassName>_index/` — hash index records or B-tree index files
- `.xdstorage.meta` — format version marker (v1 → v2)
- `structure/` — class structure snapshots

**Format versions:**
- **v1** (1.x era): YAML files without EOF sentinel, no commit journal
- **v2** (2.0+): YAML EOF sentinel (S-1), optional per-transaction commit journal (W-1)

---

## 5. Transaction Model

### 5.1 Thread-Bound Transactions

- Transactions are **bound to the calling thread** at `beginTransaction()`
- Every CRUD method without an explicit transaction argument uses the **current thread's binding**
- A thread that abandons its transaction leaves it bound — **always commit or roll back in a finally**
- Transactions can be **shared across threads** (e.g. parallel streams)
- Failed CRUD operations **throw but do NOT auto-rollback** — the app must call `rollbackTransaction()`
- Transactions are **bounded by timeout** (default 3000ms) — enforced lazily

### 5.2 Nested Transactions

- `beginTransaction(parent)` creates a **nested transaction** inside a parent
- **Downward-only rollback-only cascade**: a nested transaction's rollback does NOT poison the parent
- The parent's `commitTransaction()` waits for all open nested transactions

### 5.3 MVCC Isolation

- **`SNAPSHOT`** (default): readers see a consistent snapshot from when the transaction started
- **`READ_COMMITTED`**: readers always see the latest committed state

### 5.4 Two-Phase Commit (CACHE2PC)

The cache commit protocol (`XdStorageCacheCommitProtocol`) uses a **two-phase commit**:

1. **prepare**: snapshots the transaction's write-set into a collector (the resource's change buffer)
2. **perform**: refreshes the collector with advanced state (deferred IDs, forwarded wrapper states)
3. **finalize**: writes the collected changes to disk (atomic rename, fsync)
4. **rollback**: replays the mutation journal in reverse order

Both phases deliberately share the same body (`runCommitPhase()`) with only a flag differing — this is a **design decision** (CACHE2PC) to ensure identical logic.

### 5.5 Transaction Model Diagram

```
Thread A:              Thread B:
─────────────────      ─────────────────
beginTx() ──────►
  save(obj1)
                     beginTx() ────►
                       save(obj2)
                     commitTx()  ◄───
  save(obj3)
  commitTx()  ───────────────────────►
                                         read(obj1) → stale snapshot
```

---

## 6. Index System

### 6.1 ID Index Types (`XdStorageIndexType`)

| Type | Implementation | Operations |
|---|---|---|
| `Hash` | In-memory `ConcurrentHashMap` + `XdStorageHashIndexRecord` entries on disk | O(1) point lookup, full-walk scan |
| `BTree` | Persistent B+tree object in `XdStorageBTree/` | Ordered traversal, range scans, point lookup |

### 6.2 Secondary Search Indexes (`@XdStorageObjectSearchIndex`)

Each class can declare secondary indexes on specific fields. The search engine:

- Maintains index records on **every insert/update/delete** (lazy-id resolution via `XdStorageSearchIndexRecordConfermer`)
- Executes queries by **picking an access plan** per criterion:
  - **EQUALS** → exact B+tree key lookup
  - **Range operators** (`GT`, `GTE`, `LT`, `LTE`, `BETWEEN`, `IN`) → bounded ordered B+tree walk with early termination
  - **Fallback** → whole-index scan when no index path applies

### 6.3 Query Plan (`XdStorageQueryPlan`)

The `EXPLAIN` path answers the access plan without executing the query:
- `INDEX_SEEK` — exact key lookup
- `INDEX_RANGE_WALK` — bounded walk on ordered tree
- `SCAN` — full index scan (no applicable index path)
- Includes degradation reason, bound shape, post-filters, and shaping summary

---

## 7. Search Engine

### 7.1 Query Model

```java
XdStorageSearchQuery query = new XdStorageSearchQuery();
query.or(primaryEq, secondaryEq);
query.orderByPrimary(true);  // DESC
query.limit(100);
query.offset(50);
```

- **Criteria**: OR-list of (primary, secondary) criterion pairs
- **Shaping**: `orderByPrimary()`, `limit()`, `offset()` (FAILS on negative values)
- **Result shaping ignored by `count()`** (SQL COUNT semantics)

### 7.2 Search Index Resources

Each search index is backed by a `XdStorageSearchIndexResource` that:
- Maintains `XdStorageSearchIndexRecord` entries per indexed field
- Handles id-less object deferral (observable wrappers)
- Executes the query plan against the underlying index (B+tree or hash)

### 7.3 EXPLAIN

```java
XdStorageQueryPlan plan = storage.explain(Customer.class, "by_country", query);
```

The plan is **static** (class-level metadata only) — no transaction needed for the base case. A transactional form (`explain(..., tx)`) guards the index-tree availability probe.

---

## 8. Serialization System

### 8.1 SPI: `IXdStorageIOFactory`

```java
interface IXdStorageIOFactory {
    IXdStorageObjectsReader newInstanceReader();
    IXdStorageObjectsWriter newInstanceWriter();
}
```

### 8.2 Formats (`FilesFormat`)

| Format | Files | Commit Journal |
|---|---|---|
| `XML` | `*.xml` | No (journal only supported for YAML) |
| `YAML` | `*.yaml` | Yes (append-only, configurable) |

### 8.3 Commit Journaling (W-1 / W-4a)

- **`journalWrites`** (default `true`): data/reference resources append to a journal file instead of rewriting the whole file
- **`indexJournalWrites`** (default `true`): hash id-index commits append to a journal
- **`journalCheckpointBytes`** (default 16 MB): triggers a checkpoint (full base rewrite + journal reset)
- The journal system handles **torn writes** (partial writes from crashes) with the EOF sentinel mechanism

### 8.4 YAML Serialization

The YAML engine is a custom tokenizer/emitter (not a library) that produces:
- Block containers (`YamlBlockContainersWriter/Reader`)
- Block objects (`YamlBlockObjectsWriter/Reader`)
- Simple objects (`YamlObjectsWriter/Reader`)
- Commit journal (`YamlCommitJournal`)

---

## 9. Trigger System

### 9.1 Model

```java
interface IXdStorageTrigger<T> {
    XdStorageObjectOperationType getType();  // INSERT / UPDATE / DELETE
    Class<T> getClazz();
    void perform(T oldObject, T newObject, IXdStorageTransaction tx);
}
```

- Triggers fire **inside the changing transaction**
- A trigger that touches the storage must open its own **nested transaction**
- **CopyOnWriteArrayList** for trigger lists — **lock-free parallel dispatch** across all worker threads
- A failing trigger throws and **rethrows**, failing the commit

### 9.2 Cascade Save (`@XdStorageAutoSave`)

- Field-level annotation on the parent class
- On parent `save`/`update`: cascade-upserts annotated children (`StoreAsClassObjects` / `StoreAsSingleObject`) in the **same transaction**
- Supports: collections, single references, arrays, MAP values (with id-field keys)
- **Cycle-safe**, **deduplication-aware**
- Removed children are **NOT deleted** (cascade-delete is intentionally out of scope)

---

## 10. Foreign Key Engine

```
XdStorageForeignKeyEngine
├── IXdStorageForeignKeyResource (per-class FK resource)
├── IXdStorageForeignKeyEngine (SPI)
├── XdStorageForeignKeyRelation (FK relationship model)
├── XdStorageForeignKeyResourcesManager (per-storage)
├── XdStorageForeignKeyRegistry (global registry)
└── XdStorageForeignKeyViolationException
```

- Enabled by default (`enableForeignKeys = true` in `XdStorageConfig`)
- Validates referential integrity at save time
- The engine tracks FK resources per entity class

---

## 11. Schema Migration

### 11.1 Structure Manager (`XdStorageStructureManager`)

- On first use of a class: builds a snapshot of the class metadata and compares with the persisted structure record
- On mismatch: executes registered **migration scripts** in registration order
- Each migration is **journalled** — already-executed scripts are skipped
- Runs in an **autonomous transaction on the engine executor** (no deadlock risk with caller's transaction)
- Per-class **ReentrantLock** — fast-path for already-migrated classes (lock-free)

### 11.2 Migration SPI

```java
interface IXdStorageStructureUpdater<T> {
    Class<?> getDataClass();
    String getUpdateName();
    XdStorageUpdateStructureResult execute(ResourcesManager mgr, Transaction tx);
    void setResult(XdStorageUpdateStructureResult result, String message, Throwable e);
}
```

---

## 12. Cloning System

### 12.1 Smart Cloner (`XdStorageSmartCloner`)

The engine must clone objects for the cache (to maintain MVCC snapshots). The cloner:

- Uses reflection to navigate the object graph
- Handles: nested POJOs, collections (List, Set, Map, arrays), nulls, primitive wrappers
- **Reference-aware**: detects cycles and shared references
- **ID-aware**: distinguishes between materialized objects and lazy-loaded references

### 12.2 Cloning Components

| Component | Responsibility |
|---|---|
| `XdStorageSmartCloner` | Top-level orchestrator |
| `XdStorageClonerCollectionProcessor` | Collection field handling |
| `XdStorageClonerArrayProcessor` | Array field handling |
| `XdStorageClonerFieldPacker` | Field value packing (serialization) |
| `XdStorageClonerFieldUnpacker` | Field value unpacking (deserialization) |

---

## 13. Code Generation System

### 13.1 Generated Wrappers

At runtime, the engine generates **wrapper subclasses** for every stored entity class:

| Wrapper Type | Purpose |
|---|---|
| `XdStorageSimpleWrapper` | Lazy-loadable: exposes the entity with a reference-flag for unloaded state |
| `XdStorageUnmodifiableWrapper` | Read-only wrapper (all setters throw) |
| `XdStorageObservableWrapper` | Triggers observer callbacks on field access |
| `XdStorageDummyWrapper` | Fallback when no runtime compiler is available |

### 13.2 Code Generation Pipeline

```
XdStorageClassGenerator
├── XdStorageAbstractClassCodeGenerator (base template)
├── XdStorageSimpleWrapperClassCodeGenerator
├── XdStorageUnmodifiableWrapperClassCodeGenerator
├── XdStorageObservableWrapperClassCodeGenerator
├── XdStorageWrapperBlockGettersGenerator
├── XdStorageWrapperBlockSettersGenerator
├── XdStorageUnmodifiableBlockGettersGenerator
└── XdStorageUnmodifiableBlockSettersGenerator
```

Generated classes implement:
- `IXdStorageSimpleWrapper` (get/set id, reference flag, lock)
- `IXdStorageUnmodifiableWrapper` (unmodifiable accessors)
- `IXdStorageObservableWrapper` (observer notification on field reads)

---

## 14. Resource Cache (MVCC)

### 14.1 Architecture

```
XdStorageResourceCache (per-resource per-storage)
├── XdStorageCacheContext (records registry, workspace, id field)
├── XdStorageCacheCommitProtocol (2PC: prepare/perform)
├── XdStorageCacheRecord (per-object cache entry)
├── XdStorageCacheMutator (write-set operations)
├── XdStorageCacheChange (prepared/committed change)
├── XdStorageTransactionWorkspace (per-transaction workspace)
└── IXdStorageCacheInternalListener (audit hook)
```

### 14.2 Cache Operations

| Operation | Behavior |
|---|---|
| `read(id, tx)` | Look in transaction's workspace → cache → resource file |
| `save(obj, tx)` | Stage in workspace write-set → prepare/perform at commit |
| `delete(obj, tx)` | Stage deletion in workspace |
| `loadAll(tx)` | Scan resource, materialize into workspace |
| `watch(watcher, tx)` | Stream objects to callback without full materialization |

### 14.3 Cache Isolation

Each resource cache maintains its own isolation level (configurable at storage open time). The default is `SNAPSHOT` — all reads in a transaction see the same consistent view regardless of concurrent writes.

---

## 15. Configuration (`XdStorageConfig`)

| Knob | Default | Description |
|---|---|---|
| `durableWrites` | `false` | Fsync before atomic rename (power-loss safe) |
| `acceptMissingEofSentinel` | `false` | Open legacy (v1) files without sentinel |
| `daemonWorkerThreads` | `false` | Worker threads are daemon (forgotten shutdown OK) |
| `registerShutdownHook` | `false` | Best-effort shutdown on JVM exit |
| `workerPoolSize` | `15` | Internal worker pool size |
| `defaultTransactionTimeoutMillis` | 3000 | Default transaction timeout |
| `isolationLevel` | `SNAPSHOT` | MVCC isolation for all resource caches |
| `journalWrites` | `true` | Append-only commit journal for data resources |
| `indexJournalWrites` | `true` | Append-only journal for hash id-index |
| `journalCheckpointBytes` | 16 MB | Journal checkpoint threshold |
| `txMaterializedObjectsCap` | 0 (unlimited) | Per-transaction materialization budget |
| `enableForeignKeys` | `true` | FK constraint checking |

---

## 16. Entry Points

### 16.1 Provider Pattern

```java
// Open or get an existing storage
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
    "myDb",                    // unique name
    "/path/to/folder",         // storage folder
    XdStoragePolicy.StoreAsClassObjects
);

// Verify a folder (read-only integrity check)
XdStorageVerifyReport report = XdStorageProvider.verify("/path/to/folder");

// Shutdown
storage.shutdown();  // releases folder lock, unregisters name
```

### 16.2 Custom Configuration

```java
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
    "myDb",
    "/path/to/folder",
    XdStoragePolicy.StoreAsClassObjects,
    FilesFormat.YAML,
    XdStorageConfig.builder()
        .durableWrites(true)
        .workerPoolSize(32)
        .journalCheckpointBytes(32L * 1024 * 1024)
        .build()
);
```

---

## 17. Test Suite Architecture

### 17.1 Test Organization

```
src/main/test/io/github/xdstorage/
├── AbstractXdStorageTest/          ← shared test base
├── autosave/                       ← cascade save tests (13 classes)
├── btree/                          ← B+tree stress/invariant tests (25+)
├── code/                           ← codegen tests (12+)
├── foreignkey/                     ← FK constraint tests
├── index/                          ← index resource tests
├── integration/                    ← full-engine integration tests
├── proxy/                          ← proxy compilation tests
├── serialization/                  ← XML/YAML serialization tests
├── search/                         ← search engine tests
├── structure/                      ← schema migration tests
├── transaction/                    ← transaction model tests
├── trigger/                        ← trigger tests
└── ...
```

### 17.2 Test Coverage (2.1.0 train)

- **331 test classes** total
- Coverage gaps filled during the 2.1.0 train:
  - `structure`: 8% → 54%
  - `trigger`: 73% → 88%
  - `index`: 75% → 100%
- JUnit 5 `@Tag("stress")` / `@Tag("integration")` groups
- JMH benchmarks in `scripts/run-benchmarks.sh [quick\|full\|sweep]`

---

## 18. Project Layout

```
xdstore/
├── pom.xml                          ← Maven build (version 2.1.0-SNAPSHOT)
├── CHANGELOG.md                     ← Keep a Changelog format
├── ANALYSIS-ARCHITECTURE.md         ← This document
├── src/
│   ├── main/java/io/github/xdstorage/
│   │   ├── XdStorage.java          ← Implementation (217 total files)
│   │   ├── XdStorageProvider.java  ← Public factory
│   │   ├── IXdStorage.java         ← Core contract
│   │   ├── IXdFileStorage.java     ← Extended contract (search, watchers)
│   │   ├── XdStorageConfig.java    ← Configuration
│   │   ├── XdStoragePolicy.java    ← Storage policies
│   │   ├── XdStorageVersion.java   ← Semver version reader
│   │   ├── annotations/            ← 9 annotation types
│   │   ├── btree/                  ← B+tree impl
│   │   ├── code/                   ← Codegen (16 files)
│   │   ├── exceptions/             ← 6 exception types
│   │   ├── factories/              ← Cloner framework
│   │   ├── foreignkey/             ← FK engine (8 files)
│   │   ├── helpers/                ← Simple type helpers
│   │   ├── idgeneration/           ← 7 ID generators
│   │   ├── index/                  ← Index abstraction
│   │   ├── metrics/                ← Operational metrics
│   │   ├── object/                 ← Object model
│   │   ├── observing/              ← Observer service
│   │   ├── resource/               ← Resource cache + DAO (13 files)
│   │   ├── resource/cache/         ← 2PC commit protocol
│   │   ├── search/                 ← Search engine
│   │   ├── search/query/           ← Query DSL (9 files)
│   │   ├── services/               ← ServicesLocator
│   │   ├── structure/              ← Schema manager
│   │   ├── structure/update/       ← Migration SPI
│   │   ├── transaction/            ← Transaction model (8 files)
│   │   ├── trigger/                ← Trigger system
│   │   ├── utils/                  ← 25+ utility classes
│   │   └── serialization/          ← SPI + YAML/XML impl (19 files)
│   └── main/test/                  ← 331 test classes
├── examples/quickstart/            ← Maven project with Customer/Account entities
├── scripts/                        ← Benchmark runners (Python + shell)
├── docs/                           ← Task specifications, plans, proposals
├── gencode/                        ← Generated code from quickstart
├── teststorage/                    ← Test fixture data
├── facade_stress_db/               ← Stress test data
└── .github/workflows/              ← CI pipeline
```

---

## 19. Design Decisions & Rationales

| Decision | Rationale |
|---|---|
| No external DB dependency | Pure Java, zero native deps; "embedded" means truly embedded |
| YAML as default format | Human-readable, diffable; XML retained for backward compatibility |
| Thread-bound transactions | Simple mental model; avoids thread-safety complexity in application code |
| CopyOnWriteArrayList for triggers | Lock-free parallel dispatch across all worker threads |
| Two-phase cache commit (CACHE2PC) | Guarantees identical state in both phases; deferred ID handling |
| Journal-based writes (YAML) | O(δ) per-commit instead of O(N) rewrite; handles torn writes |
| Runtime code generation | Wrapper classes provide lazy-load, unmodifiable, and observable semantics without reflection overhead in the hot path |
| `SNAPSHOT` isolation default | Consistent reads across a transaction without locking overhead |
| Nested transactions (downward rollback) | Isolation of failure scope; parent survives child failure |
| EOF sentinel format | Detects truncated files at open-time (S-1); powers the integrity verifier |
| Java 8 bytecode target | Maximum compatibility; JDK 17 used only for build/test |
| `slf4j-api` (not a backend) | Consumer chooses their logging framework; library stays backend-neutral |
| Maven `outputTimestamp` | Reproducible builds — byte-identical jars for the same source |

---

## 20. Known Issues & Technical Debt (from CHANGELOG 2.1.0)

| Issue | Status |
|---|---|
| Two JUnit-4 tests were silently dead (no vintage engine) | **Fixed** — migrated to JUnit 5 |
| `UUID_GENERATOR` field property never auto-generates IDs | **Discovered** — documented with disabled test |
| Id-addressability after structure update | **Discovered** — documented |
| Auto-generated IDs unreachable after reopen | **Discovered** — documented |
| Structure-resource journal replay NPE on reopen | **Discovered** — documented |
| `@XdStorageAutoSave` cascade-delete not implemented | **Deliberately deferred** — documented as out of scope |
| Facade happy/failure-path audit incomplete | **Open** (2.1.0) |
| Keyset/cursor pagination (OFFSET re-walk cost) | **Filed** for follow-up |
| JSON format removal (was writing XML to `.json`) | **Fixed** in 2.0.0 — constant removed from `FilesFormat` |

---

## 21. Deployment & Integration

### 21.1 Maven Dependency

```xml
<dependency>
    <groupId>io.github.xdstorage</groupId>
    <artifactId>xdstorage</artifactId>
    <version>2.1.0-SNAPSHOT</version>
</dependency>
```

### 21.2 Required Runtime Dependencies (compile-scope)

| Artifact | Version | Purpose |
|---|---|---|
| `commons-lang3` | 3.8.1 | String utility, validation |
| `slf4j-api` | 2.0.20 | Logging abstraction (consumer provides backend) |

### 21.3 Typical Usage

```java
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
    "orders", "/data/orders",
    XdStoragePolicy.StoreAsClassObjects
);

try (IxStorageTransaction tx = storage.beginTransaction()) {
    Order order = new Order();
    order.setId(UUID.randomUUID().toString());
    order.setCustomerId(customerId);
    order.setStatus(Status.NEW);
    order.setLines(Collections.singletonList(new Line(...)));
    storage.save(order);  // triggers FK checks, updates indexes, registers trigger events
    tx.commit();
} catch (XdStorageForeignKeyViolationException e) {
    tx.rollback();
    // handle constraint violation
}
```

---

## 22. Performance Characteristics (from docs/BENCHMARKS.md)

| Scenario | Notes |
|---|---|
| Read-only transaction commit | ~0.6–0.7 ms/op at N=5000–40000 (Hash path) — B2 improvement |
| Commit with journal ON vs OFF | Journal adds ~2x cost for small batches; becomes <1x for large batches |
| `StoreAsClassObjects` fragment size | 128 is the measured optimum (docs/PERFORMANCE-RECOMMENDATIONS.md §5) |
| Range query vs scan | Selective ranges run ~15× faster than the scan they replace |
| LIMIT pushdown on ordered walks | ~25% fewer disk reads vs unbounded same-window range |

---

## 23. Architectural Patterns Summary

| Pattern | Where Applied |
|---|---|
| **SPI + concrete impl** | IOFactory, Cloner, IdGenerator, StructureUpdater, Trigger |
| **Facade + Provider** | `XdStorageProvider` → `XdStorage` (singleton-per-name registry) |
| **MVCC with workspace** | Per-resource cache + per-transaction write-set |
| **Two-phase commit** | Cache commit protocol (prepare + perform phases) |
| **Journal + checkpoint** | Append-only commits with periodic full rewrites |
| **Code generation** | Runtime wrapper class emission (lazy-load, unmodifiable, observable) |
| **Observer service** | Deferred ID assignment on index records |
| **Lock-free structures** | CopyOnWriteArrayList (triggers), ConcurrentHashMap (services, states) |
| **Atomic file writes** | Write-to-temp + rename + fsync (power-loss safety) |
| **Thread-bound state** | Transaction manager (ThreadLocal), wrapper lock |
| **Immutable config** | `XdStorageConfig` builder pattern, immutable after build |
| **Hierarchical resources** | DAO resource → index resource → search index resource |
| **Migration journal** | Per-class structure migration scripts (idempotent, journalled) |

---

## 24. Conclusion

XdStorage is a **production-grade embedded storage engine** that bridges the gap between raw file-based persistence and a full relational database. Its architecture is characterized by:

1. **Deep MVCC support** with two-phase commit, configurable isolation, and transaction-scoped snapshots
2. **Rich indexing** (B+tree, hash, secondary search with query planner)
3. **Schema evolution** via versioned migration scripts
4. **Runtime code generation** for wrapper semantics without reflection penalty
5. **Journal-based durability** with power-loss detection (EOF sentinel)
6. **Thread-safe design** using lock-free structures and thread-local binding

The codebase is mature (217 source files, 331 tests) with a clear separation between the **public API tier** (annotations, contracts, provider) and the **engine tier** (cache, commit protocol, serialization, codegen). The project is actively evolving toward 2.1.0 with significant additions in query power (range operators, pagination, EXPLAIN), cascade save, benchmark infrastructure, and integrity verification.

---

*This document was generated as an architectural analysis of `xdstorage` and is intended as a living reference for the codebase. Last updated during the 2.1.0 train.*
