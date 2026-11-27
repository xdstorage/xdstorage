# XdStorage — the full guide

The reference manual. [README.md](../README.md) is the landing page — what XdStorage is,
when to take it, a worked example and the honest limits; this page carries the deep
contract, section for section: the data model, the storage lifecycle, transactions, CRUD,
the search engine, triggers, id generation, streaming reads, formats, structure updates,
the concurrency internals, and the measured performance model with its tuning advice.

## Terminology

One name per concept, used across README, GUIDE, DECISION-GUIDE, EMBEDDING and CHANGELOG:

| Term | Meaning |
|---|---|
| **storage** | one open database = one folder (files + exclusive lock + in-memory caches), addressed by a logical name through `XdStorageProvider`. |
| **storage policy** | the per-class placement choice, `@XdStorageObjectPolicy`: embedded in the parent's file / one file per object / shared fragmented class file. (Not "placement policy".) |
| **fragment** | one `data/<Class>/<Class>-<n>.yaml` file holding up to `fragmentSize` objects of a `StoreAsClassObjects` class. |
| **id index** | the per-class primary key index (Hash by default, or B+tree via `@XdStorageObjectIdIndexType`); maps id → fragment record. |
| **search index** | a named secondary index declared by `@XdStorageObjectSearchIndex`; its *primary index value* is the first `indexFieldNames` entry. |
| **child index** | a search index attached to child objects via `childrenIndexes`. |
| **commit journal** | the per-resource append-only journal family that replaces whole-file rewrites: the **data journal** (`journalWrites`, W-3), the **id-index journal** (`indexJournalWrites`, W-4a) and the **search-key journal** (`searchIndexJournalWrites`, W-4b). |
| **checkpoint** | folding a journal back into its base file — at the 16 MB size threshold and at every clean `shutdown()` (W-4c). |
| **EOF sentinel** | the end-of-file marker that makes truncated files fail loudly at open instead of silently serving partial data. |
| **overlay staging** | the 2.1.0 write path: B+tree key mutations stage into the writing transaction's overlay and apply as a batch at its commit (the concurrency unlock). |
| **deferred id** | an engine-generated id assigned during the commit; observers are notified, and FK relations wait for it via the deferred-id waiters. |
| **secondary keys / FK relation** | the two persistent B+trees (front + reverse) the engine maintains per `@XdStorageAutoSave` + `@XdStorageParentObject` relation; see [Foreign keys](#foreign-keys-and-the-secondary-keys). |
| **veto** | a referential-integrity refusal at commit: deleting a still-referenced child, deleting a parent with live children, or duplicating a (parent, child) pair — the commit rolls back. |

## Features at a glance

- **Embedded file storage** — one storage is one folder; the provider keeps a named singleton per storage.
- **POJO data model** — ordinary JavaBeans with getters/setters; collections, arrays and maps are supported
  as field types; nested object graphs are stored according to a per-class policy.
- **ACID transactions** — explicit `beginTransaction / commit / rollback`, atomic multi-resource 2PC commit,
  automatic rollback of a failed commit (the database returns to a valid state), transaction timeouts,
  `markRollbackOnly`; read-only transactions commit through a cheap release path (no 2PC record
  iteration, zero file write-outs).
- **MVCC** — writers produce new versions; readers work against a consistent snapshot
  (`SNAPSHOT` / `READ_COMMITTED` read isolation).
- **Three storage policies** — embed an object inside its parent file, one file per object, or a shared
  per-class file with fragmentation.
- **Indexes** — a primary id index (hash or B+tree, configurable) plus declarative secondary search indexes,
  including indexes over child objects.
- **Search engine** — criteria queries (`EQUALS`, `NOT_EQUALS`, `CONTAINS`, `IS_NULL`, `IS_NOT_NULL`,
  range operators `GT`/`GTE`/`LT`/`LTE`/`BETWEEN`/`IN`), AND/OR combinations, negation, child-object
  criteria; indexed lookups cost about the same as an id load.
- **Result shaping** — `ORDER BY` on the primary index value, `LIMIT`/`OFFSET` pagination, and
  `count()` without materializing the result.
- **Query plans** — `explain(Class, indexName, query)` shows the path the engine would take
  (`INDEX_SEEK` / `INDEX_RANGE_WALK` / `SCAN`, the bound shape, the degradation reason) before
  running the query, without executing it. **`explain` works without a transaction, by design**
  (the plan is static index metadata — no snapshot is needed; the internal probe transaction
  is rolled back immediately), unlike the CRUD/search reads which refuse without one.
- **Concurrent writers** — index mutations stage per-transaction (the overlay) and apply at
  commit under a total acquisition order: 10 concurrent writers measure 121–158 tx/s
  (p99 84–113 ms) on the heavy indexed shape where 2.0.x collapsed to ~1 tx/s.
- **Triggers** — `Insert` / `Update` / `Delete` callbacks executed within the committing transaction.
- **Identifier generation** — persistent per-class counters for `Long`/`Integer`, UUID for `String`,
  fully pluggable custom generators; deferred-id observers get notified when an id is assigned.
- **Cascade save** — `@XdStorageAutoSave` upserts annotated independent children when the parent is
  saved or updated, in the same transaction.
- **Referential integrity (foreign keys)** — relations between a parent and its
  independent-policy children (`@XdStorageAutoSave` + `@XdStorageParentObject`) are backed by
  persistent front/reverse B+tree indexes: cascade save, persistent parent↔child navigation
  without a data scan, and delete vetoes — all maintained automatically in the same transaction
  (`enableForeignKeys`, on by default; see [Foreign keys](#foreign-keys-and-the-secondary-keys)).
- **Operability** — `verify(folder)` (a read-only integrity walk over a folder: sentinel state,
  journal tails, data-vs-index counts, litter — `OK` / `WARNINGS` / `CORRUPT`), `cp -r`
  backup while closed, `getMetrics()` counters, and an exclusive folder lock that fails a
  second opener loudly instead of corrupting data.
- **Pluggable serialization** — YAML (default), XML; the reader/writer pair is an SPI
  (`IXdStorageIOFactory`) so you can implement your own format. (The 1.x `FilesFormat.JSON`
  constant was removed in 2.0.0 — it wrote XML content; see [EMBEDDING.md](EMBEDDING.md) § Compatibility.)
- **Streaming reads** — iterate a class without materializing the whole collection (`watch`), or filter with
  a predicate (`load(Class, IXdStoragePredicate)`).
- **Structure updates** — registered updaters migrate persisted structures (e.g. rebuild a search index after
  its annotation changed) via `executeStructureUpdate`.
- **Pure Java** — bytecode stays Java 8 compatible; the tested runtimes are JDK 17 (the
  full-suite gate) and JDK 21 (the CI leg and the canonical-baseline runtime); a **JRE** runs it
  too when the wrapper classes are generated at build time
  ([EMBEDDING § Running on a JRE](EMBEDDING.md#running-on-a-jre)); logging via
  SLF4J — the API jar is the only logging artifact (no backend, no configuration ships in the library).

## Defining the data model

Entities are ordinary JavaBeans. XdStorage reads the class structure once, caches the metadata, and uses a
generated wrapper class per entity — generated at build time when the application ships them, otherwise
compiled at runtime (see [Concurrency model](#concurrency-model-and-internals) and
[EMBEDDING § Codegen requirements](EMBEDDING.md#codegen-requirements)).

### Rules for every entity

- The class **must be a top-level class** (not a nested/inner/anonymous class) — the runtime code generator
  cannot compile wrappers for nested classes on JDK 9+.
- The identifier field is marked with `@XdStorageObjectId` and **must have a getter and a setter**.
- Every persisted field needs **both a getter and a setter** (plain JavaBeans). A field with a
  getter but no setter is **silently dropped from persistence** (a WARN at metadata load) —
  it never round-trips. Auto-generated/Lombok-style collections without a hand-written setter
  are the recurring case.
- The id type must implement `equals()`/`hashCode()`. For custom id classes it must also provide a
  constructor accepting a single `java.lang.String`. Built-in generators support `Long`, `Integer` and
  `String` ids (see [Identifier generation](#identifier-generation-and-observers)).
- Child objects referenced by fields follow their **own** class policy.

### Storage policies — `@XdStorageObjectPolicy`

| Policy | Meaning |
|---|---|
| `StoreWithParentObject` (default) | The object is written inside the file of its parent object (embedded type) — it lives and dies with the parent. |
| `StoreAsSingleObject` | One object — one file on disk. |
| `StoreAsClassObjects` | All objects of the class are packed into shared class-file **fragments** (the `fragmentSize` parameter caps how many objects go into one fragment before a new fragment file is created). |

```java
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class Ship { ... }

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
public class CrewMember { ... }
```

Only `StoreAsClassObjects` and `StoreAsSingleObject` classes are top-level (independently addressable)
stored objects; `StoreWithParentObject` children live and die with their parent.

### Primary id index — `@XdStorageObjectIdIndexType`

Chooses the physical index type for the id index of the class:

```java
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 64)  // t = B+tree minimum degree
public class Ship { ... }
```

`XdStorageIndexType.Hash` (default) gives O(1) point lookups and the best cold reads after restart;
`BTree` adds ordered keys, native range walks over the id index, and ~2× bulk-insert throughput.
`t` is the B+tree minimum degree — default 64 (the configuration-study optimum; stay within 32–128,
never below 8 — see [Performance](#performance)).

### Search indexes — `@XdStorageObjectSearchIndex`

Declares a named secondary index over one or more fields. The **first** field of `indexFieldNames` is the
*primary* index value (backed by a B+tree — the fast path for `EQUALS` queries); the remaining fields are
secondary values filterable after the lookup. `childrenIndexes` attaches indexes of child objects
(`childFieldName` is the field on this class, `childClassIndexName` is the index name declared on the child
class).

```java
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(
        indexName = "ship_idx",
        indexFieldNames = {"name", "tonnage"},          // "name" is the primary index value
        t = 64,
        childrenIndexes = {
                @XdStorageObjectChildSearchIndex(childFieldName = "captain", childClassIndexName = "crew_idx"),
                @XdStorageObjectChildSearchIndex(childFieldName = "crew",    childClassIndexName = "crew_idx")
        })
public class Ship {

    @XdStorageObjectId
    private Long id;

    private String name;
    private int tonnage;

    private CrewMember captain;             // single child object  — indexed
    private List<CrewMember> crew;          // collection of children — indexed
    // arrays and Map values work the same way

    public void addCrewMember(CrewMember member) {
        if (crew == null) crew = new ArrayList<>();
        crew.add(member);
    }

    // getters and setters for every field ...
}

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
@XdStorageObjectSearchIndex(indexName = "crew_idx", indexFieldNames = {"rank"}, t = 64)
public class CrewMember {

    @XdStorageObjectId
    private Long id;

    private String rank;

    @XdStorageParentObject
    private Ship parent;      // back-reference, filled automatically on load

    public CrewMember() {}
    public CrewMember(String rank) { this.rank = rank; }

    // getters and setters ...
}
```

### Field properties — `@XdStorageObjectFieldProperties`

Configures per-field storage parameters and id generation:

```java
@XdStorageObjectId
@XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
private Long id;

@XdStorageObjectFieldProperties(length = 1000)   // declared field length
private String name;
```

- `length` — declared length of the field (default 255).
- `idGeneratorType` — who assigns the id when it is still null at save time:

  | `idGeneratorType` | Long / Integer id | String id |
  |---|---|---|
  | `DATABASE_GENERATOR` *(default)* | engine Hi-Lo batches (per class, persisted counter; a reopen burns the unused batch remainder — at most batch-size−1 ids) | engine UUID |
  | `UUID_GENERATOR` | **loud validation error** (a numeric id cannot carry a UUID) | engine UUID |
  | `CUSTOM_GENERATOR` + `idGeneratorClass` | the SPI class generates (constructed with the services locator when such a constructor exists, else no-arg) | same |

  An app-assigned (non-null) id is **never overwritten** in any mode. Generated Long/Integer
  ids are addressable by id after a reopen; UUID ids likewise (BUG-D contract,
  docs/tasks/2.1.0/14 — before the fix `UUID_GENERATOR` never generated and the client had
  to assign). A custom generator SPI implements `IXdStorageIdGenerator` — its `generate`
  receives the entity class and the running transaction.

### Cascade save — `@XdStorageAutoSave`

Marks a parent's child-object field for cascade-save: when the parent is saved or updated,
the annotated children under independent policies (`StoreAsClassObjects`, `StoreAsSingleObject`)
are cascade-upserted in the same transaction — triggers, observers, id generation and rollback
behave exactly as for explicit saves.

```java
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class Order {
    @XdStorageObjectId private String id;
    @XdStorageAutoSave private List<OrderLine> lines;   // OrderLine: StoreAsClassObjects too
    @XdStorageAutoSave private Delivery delivery;       // single reference works as well
}
```

Contract (pinned by `XdStorageAutoSaveCascadeTest`):

- child id `null` → insert; a generated id backfills the **same instance**; id set → update
  if the index knows it, insert otherwise (upsert);
- the child's own `@XdStorageAutoSave` fields cascade further — cycles (A→B→A) terminate;
  the same instance via two fields saves once;
- rollback is automatic (one transaction): a failed cascade aborts the save — nothing persists;
- **removed children are NOT deleted** — a child taken out of the collection keeps its
  independent resource (cascade-delete is deliberately out of scope);
- AutoSave persists what the field holds — no merging of foreign changes (MVCC governs
  conflicts as for explicit saves);
- re-save re-upserts every annotated child (idempotent, write-amplifying for wide
  collections — dirty checking is a future optimization);
- an element class under `StoreWithParentObject` is a no-op with a metadata WARN (already
  embedded in the parent's file);
- shapes: collections, single references, **arrays**, and **Maps** (a MAP-typed field
  cascades its VALUES that carry an id-field — the keys are never candidates; id-field-less
  values are WARN-skipped). A child with a parent back-reference should use the standard
  embedded shape (§ Parent references and lazy children) — the cascade is for independent
  children.

### Foreign keys and the secondary keys

The cascade pair buys more than save-time convenience. Annotating the two sides of a relation
(`@XdStorageAutoSave` on the **parent's** child field, `@XdStorageParentObject` on the
**child's** back-reference) tells the engine to maintain it as a first-class structure:
the cascade upsert, a persistent bidirectional parent↔child navigation, and
referential-integrity vetoes. What each side does:

- `@XdStorageAutoSave` (parent's field) — the cascade upsert contract (§ [Cascade save](#cascade-save--xdstorageautosave)).
- `@XdStorageParentObject` (child's field) — populated when the child is loaded, and the
  marker that makes the child participate in the FK relation. **Without it the relation does
  not exist for the FK engine** — even with `@XdStorageAutoSave` on the parent's field.

**What the engine maintains per relation — the secondary keys.** Each relation is TWO
persistent B+trees: the **front** tree (parent id → child ids) and the **reverse** tree
(child id → parent id). They are created at the first save and maintained automatically
in the SAME transaction as the data write — never by the application. The trees ARE the
navigation index: "load all children of P" and "load the parent of C" answer from the trees
without scanning the data (measured free: ~1.0× vs FK-off — [BENCHMARKS.md](BENCHMARKS.md)
§ FK A/B, `fkLookup`). Identity: the relation is canonical per **(parent class, child class,
parent field name)** — that triplet derives the resource id, and duplicate declarations of
the same triplet are rejected.

**The maintenance rules** (each automatic, in the caller's transaction):

- `save(parent)` with N children → 2×N secondary-key entries; `save(child)` → 2;
- an update that diffs the parent's collection → deletions + insertions per changed pair;
- **deletion ordering**: children are deleted first, the parent after; a secondary-key
  entry is removed with the child's deletion, in the same transaction;
- **the three vetoes** — hard failures at commit (the commit rolls back, no partial state):
  a duplicate (parent, child) pair; deleting a child whose parent still references it;
  deleting a parent that still has live children. The legal "remove a child" cycle is:
  remove it from the parent's collection, update the parent, then delete the child;
- id timing is engine-handled: auto-generated (deferred) child ids wait via the
  deferred-id waiters; manual-id parents are validated at commit (a parent saved in the
  same transaction clears the pending check).

**Boundaries and configuration:**

- `enableForeignKeys` (default ON) — setting it `false` skips the secondary-key trees and
  the vetoes; the cascade itself still runs.
- Embedded children (`StoreWithParentObject`) bypass FK validation by design — they live
  and die with the parent's file.
- In-transaction reads see the transaction's own secondary-key changes before commit
  (read-your-own-read): the trees are per-transaction staged like every B+tree mutation
  (the overlay, § [Concurrency model](#concurrency-model-and-internals)).

**The cost, honestly.** FK maintenance is proportional to the RELATION count per mutation,
not to the dataset, and the FK **read** path is free — but the FK **write** shapes measure
outside the engine's ≤2× constant-factor band at every size: cascade-save **23–160×**,
child delete **12–73×**, parent update **1.4–12.5×** vs the same graph with FK off,
scaling LINEARLY with the FK-tree size ([BENCHMARKS.md](BENCHMARKS.md) § FK A/B). Enable
foreign keys for integrity-critical graphs; do not enable them on hot write loops that
don't need the integrity. The O(relations) scan behind the linear scaling is the filed
2.2.0 fix. The deep subsystem record for maintainers:
[docs/analysis/FOREIGN_KEYS.md](analysis/FOREIGN_KEYS.md) — the user-facing text above
stands alone without it.

### Parent references and lazy children

- `@XdStorageParentObject` on a field marks the back-reference to the owning object; it is populated when
  the child is loaded.
- `@XdStorageLoadByGetMethod` on a child-collection field makes the generated getter **lazy**: the first
  call loads the collection from the storage (`storage.load(getter result, tx)`) and caches it. The first
  access must happen inside a transaction; if that load fails, the transaction is marked rollback-only and
  a `XdStorageRuntimeException` is thrown — the mark's captured cause (`getRollbackOnlyCause()`) names the
  lazy-loaded field and carries the root failure.

## Opening and closing a storage

```java
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, folder);          // default fragment size (128), YAML
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, folder, config);  // default fragment size (128)
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, folder, fragmentSize);
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, folder, fragmentSize, FilesFormat.YAML);
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, folder, fragmentSize, XdStorageConfig.defaults());
IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, folder, fragmentSize, FilesFormat.YAML, config);
```

- `name` — logical storage name. The provider keeps a **registry**: calling `newOrGetFileStorage` again with
  the same name returns the *existing* instance (until `shutdown()`), even if the folder was deleted and
  recreated.
- `folder` — the directory holding all storage files (created on demand). The folder is **exclusively
  locked** for the storage's lifetime (`.xdstorage.lock`): a second process — or a second instance in the
  same JVM — on the same folder fails loudly with `XdStorageFolderLock.XdStorageInUseException` instead of
  corrupting data. The lock is released by `shutdown()`.
- `fragmentSize` — number of objects per fragment file for the `StoreAsClassObjects` policy. The
  overloads without this parameter use the engine default `128` (the configuration-study optimum —
  see [Performance](#performance)).
- `FilesFormat` — `YAML` (default) or `XML`. (The 1.x `JSON` constant was removed in 2.0.0; a
  1.x folder written through it holds XML content in `.json`-named files — migration recipe in
  [EMBEDDING.md](EMBEDDING.md) § Compatibility.)
- `XdStorageConfig` — open-time knobs (all defaults reproduce the pre-config behavior):
  `durableWrites` (fsync file+directory per commit — power-loss safe at fsync cost),
  `acceptMissingEofSentinel` (legacy migration: open pre-sentinel storages instead of failing loudly on
  truncated files), `journalWrites` (ON by default since W-3 — data-class commits are journaled;
  turn off for the classic full-file rewrite), `txMaterializedObjectsCap` (per-transaction read
  budget, 0 = unlimited), `daemonWorkerThreads` (worker pool cannot block JVM exit), `registerShutdownHook`
  (best-effort `shutdown()` on JVM exit), `workerPoolSize`, `defaultTransactionTimeoutMillis`,
  `isolationLevel` (`SNAPSHOT` default, `READ_COMMITTED`).

`shutdown()` **must be called** when you are done: it stops the storage worker threads, releases the folder
lock and removes the instance from the provider registry, allowing the name/folder to be reopened cleanly
afterwards. It is idempotent; after it, every storage call throws a loud `XdStorageRuntimeException`
("storage ... is shut down") instead of failing deep inside the engine.

The `IXdFileStorage` interface extends `IXdStorage`, so every storage also exposes `getName()`,
`getTransactionManager()`, and trigger registration.

## Transactions

XdStorage is transactional by design: **every operation runs inside a transaction**. Each facade method has
two forms — with an explicit transaction, and without one (the latter resolves the transaction bound to the
current thread and throws `XdStorageException("method must be executed in transaction")` if there is none).

```java
IXdStorageTransaction tx = storage.beginTransaction();        // default timeout: 3 s
IXdStorageTransaction tx = storage.beginTransaction(15000L);  // explicit timeout in ms
try {
    // ... storage.save / load / update / delete / search with tx ...
    tx.commit();
} catch (Throwable t) {
    tx.rollback();      // or tx.markRollbackOnly() to poison it for the caller
    throw t;
}
```

> **Nested transactions (`beginTransaction(parentTx, timeout)`) are NOT RECOMMENDED for
> load-heavy use.** A nested rollback correctly does not poison the parent, but the nested
> commit/rollback machinery is a known critical-under-load path (abandoned-nested latch
> cascades, zombie sweeps, mid-commit poisoning). Prefer flat transactions with explicit
> compensating code; the rework is scheduled for 2.2.0.

Semantics:

- A transaction **times out** after the given number of milliseconds; using a dead transaction throws.
- `commit()` runs a two-phase commit across every resource the transaction touched (object files, id
  indexes, search indexes). If the commit fails at any phase, the changes are rolled back and the database
  returns to a valid state.
- `markRollbackOnly()` flags the transaction so a later `commit()` is refused. The refusal is
  diagnosable: the FIRST mark (by a wrapper accessor, a search error, a sweep or your own call —
  optionally `markRollbackOnly(site, cause)`) records `getRollbackOnlyCause()` — the marking site, the
  root exception, the transaction's age at the mark and the cascade source — and the commit failure
  names the site and chains the root exception as its cause.
- `beginTransaction(IXdStorageTransaction parent)` derives a transaction from a parent one — this is the
  correct way for **triggers** to run their own storage work while remaining inside the committing
  transaction.
- **Conflicts are re-validated at commit time:** first-committer-wins covers the whole
  stage→commit window — if a foreign transaction committed a change to a record this transaction
  read, the loser's commit fails **loudly** (the conflict names the record and the foreign commit)
  with the stored data intact; the retry pattern is a fresh read in a new transaction.
  Staged-but-aborted foreign work causes no false conflicts (the semantics are content-based).
- `storage.getTransactionManager()` exposes the underlying
  `IXdStorageTransactionManager` (`getCurrentTransaction()`, `getTransaction(id)`, `isTransactionAlive(tx)`,
  ...).

## CRUD operations

All operations are available in a `(...)` and a `(..., IXdStorageTransaction)` form, and in single-object and
`Collection` forms. Examples below use the explicit-transaction form.

### save — insert a new object

```java
Ship ship = new Ship();
ship.setName("Alpha");
storage.save(ship, tx);
// ship.getId() is now set (DATABASE_GENERATOR ids are assigned during save)
```

`save` is an **insert**: saving an object whose id already exists in the storage (in a later transaction)
throws `XdStorageException`. Use `update` to modify a saved object. Bulk form: `save(Collection<?>, tx)`.

### has — existence check by id

```java
boolean exists = storage.has(Ship.class, ship.getId(), tx);
```

### load — read objects

```java
Ship ship = storage.load(Ship.class, id, tx);            // by id, or null if absent
Collection<Ship> all = storage.load(Ship.class, tx);     // every object of the class

// load into already-existing instances (their @XdStorageObjectId values are used as keys):
storage.load(existingShip, tx);
storage.load(collectionOfShips, tx);

// filter with a predicate (full scan over the class):
Collection<Ship> big = storage.load(Ship.class, s -> s.getTonnage() > 100, tx);
```

Loaded graphs are reconstructed according to the policies: embedded children and `@XdStorageParentObject`
back-references are filled in; `@XdStorageLoadByGetMethod` collections are resolved lazily.

### update — modify a saved object

```java
ship.setTonnage(200);
storage.update(ship, tx);
```

`update` writes a new version of the object. If the *primary search-index value* changed (e.g. the ship was
renamed), the search-engine key is transferred — the object becomes findable under the new value and the old
one stops matching, without duplicating the record. Bulk form: `update(Collection<?>, tx)`.

### delete — remove an object

```java
storage.delete(ship, tx);
```

The object, its reference entry and its search-index records are removed in the same transaction; embedded
children are removed together with the parent. Bulk form: `delete(Collection<?>, tx)`.

## Search engine

The search engine maintains a B+tree of `(primary index value → record)` plus per-field and per-child
value sets for each `@XdStorageObjectSearchIndex`. Queries are built from criteria and executed with
`load(Class, indexName, query, tx)`; an unknown `indexName` throws `XdStorageException`.

```java
XdStorageSearchQuery query = new XdStorageSearchQuery(
        primaryCriterion,        // first branch (see below); may be null for a pure scan
        criterion);              // additional filter(s) for this branch; may be null
query.or(primaryCriterion2, criterion2);   // additional OR branches
```

- **Primary criterion** — `new XdStoragePrimaryCriterion(operator, value)` evaluates against the primary
  index value (the first `indexFieldNames` entry). `EQUALS` uses the B+tree directly; the range operators
  `GT` / `GTE` / `LT` / `LTE` / `BETWEEN` / `IN` walk the ordered index with early termination; `NOT_EQUALS`
  (or a `null` primary) performs a full index scan.
- **Field criterion** — `new XdStorageCriterion("tonnage", operator, value)` filters fetched records by a
  secondary indexed field.
- **Child criterion** — `new XdStorageChildCriterion(childFieldName, ChildClass.class, "fieldName",
  operator, value)` matches objects by values in their indexed child objects.
- **Operators** — `IXdStorageCriterionOperator.{EQUALS, NOT_EQUALS, CONTAINS, IS_NULL, IS_NOT_NULL,
  GT, GTE, LT, LTE, BETWEEN, IN}` (`CONTAINS` is a substring match on strings, case-sensitive);
  `IXdStorageChildCriterionOperator.CONTAINS` for child criteria.
- **Range operators — comparison contract:**
  - `BETWEEN` takes an inclusive `new XdStorageRange(from, to)`; both bounds are inclusive
    (pinned choice); `from > to` matches nothing; a single-point range matches exactly that value.
  - `IN` takes a `Collection` (or array); duplicates are harmless; an empty collection matches nothing;
    membership is `equals`-based (a foreign type simply never matches — no error).
  - **Nulls never satisfy a range operator** (`IS_NULL` / `IS_NOT_NULL` remain the null tests).
  - Comparisons are per field type: numbers numerically, strings lexicographically, `Comparable`
    contract otherwise. A comparable-type mismatch (e.g. a `String` bound on a numeric field) and a
    `null` bound fail **loudly** with `XdStorageRuntimeException` — never silently-empty. Keys of
    DIFFERENT types stored in one index tree (e.g. an FK relation between a UUID-id parent and an
    auto-`Long`-id child) compare tolerantly — numeric coercion both sides, string fallback; the
    accidental `ClassCastException` of earlier versions is gone (the designed loudness stays on
    the query-construction checks above).
- **Plan / fallback matrix** — the operator is API-wide; the available index decides the plan:

  | Primary criterion | Plan |
  |---|---|
  | `EQUALS` | B+tree point lookup |
  | `GT` / `GTE` / `LT` / `LTE` / `BETWEEN` / `IN` | ordered bounded walk of the index B+tree (early termination past the upper bound; `IN` = one membership-filtered walk; `GT`/`GTE` have no upper bound and cost like a scan) |
  | anything else (`NOT_EQUALS`, `CONTAINS`, `IS_NULL`, …) | full index scan (unchanged) |

  All plans return the same results for the same filter (verified by the boundary suite); results keep
  today's ordering contract. The walk starts at the first leaf — the engine deliberately avoids vertical
  descents for duplicate-bearing trees — so the win grows with selectivity of the **upper** bound.
- **Result shaping — `ORDER BY`, `LIMIT`/`OFFSET`, `COUNT`:**
  - `query.orderByPrimary(descending)` orders by the primary index value. On the ordered-walk path
    this is the native key order (free); on the scan path it is an explicit collect-and-sort within
    the transaction snapshot (O(matches), stated honestly). Secondary-field ordering is not
    supported — the search index orders by key trees, not arbitrary fields. Nulls are the greatest
    value (last in ASC, first in DESC); the sort is stable for equal keys. `DESC` walks the full
    range (the leaf chain is singly linked, there is no reverse walk) — the page is exact, the walk
    cost is not reduced.
  - `query.limit(n)` / `query.offset(n)` paginate after ordering. Negative arguments fail loudly at
    query construction; `LIMIT 0` is a legal empty page; an offset past the end yields an empty
    page. On a single-criterion ascending range walk the limit is **pushed down**: the walk stops
    as soon as the page is complete (measured: `LIMIT 50` over a 100-key window runs ~25% ahead of
    the unbounded same-window range — [BENCHMARKS.md](BENCHMARKS.md) § FP-1b). Without
    `ORDER BY`, pages follow the engine's evaluation order — deterministic within a snapshot, not
    an API contract. `OFFSET` re-walks the skipped prefix; keyset/cursor pagination is the filed
    follow-up if that cost matters at scale.
  - `storage.count(cl, indexName, query[, tx])` — record-level count of the snapshot-visible
    matches, without building the result collection; it ignores `ORDER BY`/`LIMIT`/`OFFSET` (SQL
    `COUNT` semantics). Every counted record passes the same snapshot-visibility read as a `load`,
    so `count(q)` is exactly `load(q).size()` on the same snapshot.
  - Snapshot semantics: a page read inside a transaction reflects that transaction's snapshot —
    concurrent inserts stay invisible; the index tree is live, so a concurrent deletion leaves the
    walk too (the documented live-index exposure, identical to the range operators' result sets).
- **Custom predicates** — `XdStorageComplexCriterion` (`and(...)` / `or(...)`) and `XdStorageCriterionNot`
  let you compose arbitrary AND/OR/NOT filters over a record.
- **Query plans — `explain()`** — `storage.explain(Cl, indexName, query)` returns the plan the
  engine WOULD take, without executing anything: the path (`INDEX_SEEK` for an `EQUALS` primary on
  the search tree; `INDEX_RANGE_WALK` for a range primary on a B+tree-indexed class, with the bound
  shape reported; `SCAN` otherwise — including the fallbacks: no criteria, `NOT_EQUALS`/non-seekable
  criteria, an index tree not built yet, or a range on a class whose **id index is Hash**), the
  secondary criteria that ride as post-filters, the degradation reason, and the active
  `ORDER BY`/`LIMIT`/`OFFSET` shaping. The plan is built from the same routing classification the
  execution lives by — it cannot disagree with it — and it is read-only: no records are
  materialized and no transaction is required. This is tx-free **by design** (the plan is static
  index metadata — no snapshot is needed; the internal probe transaction is rolled back
  immediately) — `explain` is the one facade method that works without a transaction, pinned by
  the property estate.

Examples:

```java
// exact match on the primary value
Collection<Ship> found = storage.load(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Beta"), null), tx);

// primary + secondary field filter
Collection<Ship> byTonnage = storage.load(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Gamma"),
                new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.EQUALS, 301)), tx);

// OR of two primaries
XdStorageSearchQuery orQuery = new XdStorageSearchQuery(
        new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Alpha"), null);
orQuery.or(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Gamma"), null);
Collection<Ship> two = storage.load(Ship.class, "ship_idx", orQuery, tx);

// range on the primary value (inclusive bounds), with a secondary-field filter
Collection<Ship> byRange = storage.load(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new XdStorageRange(200, 400)),          // inclusive [200, 400]
                new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.GTE, 300)), tx);

// membership
Collection<Ship> inList = storage.load(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.IN,
                        Arrays.asList("Alpha", "Gamma", "Delta")), null), tx);

// child criterion: ships of Alpha whose crew contains a member ranked "Pilot"
Collection<Ship> byCrew = storage.load(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, "Alpha"),
                new XdStorageChildCriterion("crew", CrewMember.class, "rank",
                        IXdStorageChildCriterionOperator.CONTAINS, "Pilot")), tx);

// full scan: NOT_EQUALS on the primary value (all ships not named "Alpha")
Collection<Ship> notAlpha = storage.load(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS, "Alpha"), null), tx);

// result shaping: ordered page + count
Collection<Ship> page3 = storage.load(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new XdStorageRange(200, 400)),
                new XdStorageCriterion("tonnage", IXdStorageCriterionOperator.GTE, 300))
                .orderByPrimary(false)   // ORDER BY primary ASC (true = DESC)
                .offset(100)             // skip the first 100 matches
                .limit(50), tx);         // then take 50 — the walk stops when the page is full
long howMany = storage.count(Ship.class, "ship_idx",
        new XdStorageSearchQuery(
                new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.BETWEEN,
                        new XdStorageRange(200, 400)), null), tx);   // ignores ORDER BY/LIMIT/OFFSET
```

Cost model (see [Performance](#performance)): an indexed `EQUALS` search costs roughly the same as a plain
id load; `OR` branches add one tree lookup each; scan paths (`NOT_EQUALS`, no primary criterion) visit the
whole index and are ~3–4× slower on typical datasets — keep an `EQUALS` primary in queries when you can.

## Triggers

Triggers are callbacks executed **inside the committing transaction**, after the corresponding operation has
been applied to the resource cache. Register them on the storage before use:

```java
public class ShipInsertTrigger extends XdStorageAbstractInsertTrigger<Ship> {

    @Override
    public XdStorageObjectOperationType getType() { return XdStorageObjectOperationType.Insert; }

    @Override
    public Class<Ship> getClazz() { return Ship.class; }

    @Override
    protected void perform(Ship object, IXdStorageTransaction tx) {
        // called with the new object on every committed insert of Ship
        // for your own storage work here, derive a transaction: storage.beginTransaction(tx)
    }
}

storage.registerTrigger(new ShipInsertTrigger());
```

- `XdStorageAbstractInsertTrigger<T>` receives the new object; `XdStorageAbstractDeleteTrigger<T>` receives
  the old (deleted) object. For update-style old+new pairs, implement `IXdStorageTrigger<T>` directly and
  return `XdStorageObjectOperationType.Update` from `getType()`.
- The operation types are `Insert`, `Update`, `Delete`.
- A trigger that touches the storage itself **must** begin and finish its own (derived) transaction.

## Identifier generation and observers

The `idGeneratorType` of the `@XdStorageObjectId` field (via `@XdStorageObjectFieldProperties`) controls who
assigns identifiers:

| Type | Behaviour |
|---|---|
| `DATABASE_GENERATOR` (default) | On insert, if the id is `null`, the storage generates it and sets it back on the object: `Long`/`Integer` — persistent per-class counters (they survive restarts); `String` — random UUID. |
| `CUSTOM_GENERATOR` | Storage calls your generator: set `idGeneratorClass = MyGenerator.class` where `MyGenerator implements IXdStorageIdGenerator` (a constructor taking `XdStorageServicesLocator` is supported for DI). |
| `UUID_GENERATOR` | For a `String` id: the engine generates a UUID when the id is `null` at save time; an app-assigned id is kept untouched. A numeric id with `UUID_GENERATOR` fails loudly at validation (a numeric field cannot carry a UUID). |

```java
public class MonthPrefixGenerator implements IXdStorageIdGenerator {
    @Override
    public Object generate(Class<?> cl, IXdStorage storage, IXdStorageTransaction tx) {
        return "ship-" + System.nanoTime();
    }
}

@XdStorageObjectId
@XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.CUSTOM_GENERATOR,
                                idGeneratorClass = MonthPrefixGenerator.class)
private String id;
```

Because generated ids are written back through generated *observable* wrappers, you can subscribe to id
assignment:

```java
XdStorageAbstractIdObserver observer = new XdStorageAbstractIdObserver() {
    @Override
    public void onNewIdIsSet(IXdStorageIdObservableWrapper wrapper, Object id) {
        System.out.println("assigned id = " + id);
    }
};
observer.observeIfPossible(ship);   // safe no-op for non-wrapper objects; throws on null
```

## Streaming reads and predicate filtering

`watch` streams every object of a class through your callback without materializing the result collection:

```java
storage.watch(Ship.class, ship -> System.out.println(ship.getName()), tx);
```

`IXdStoragePredicate<T>` is a functional interface usable both as a load filter (see
[CRUD](#crud-operations)) and in internal resource scans.

## Serialization formats

The on-disk format is chosen when the storage is created:

```java
XdStorageProvider.newOrGetFileStorage("db", "./data/db", 250, FilesFormat.YAML);  // default
XdStorageProvider.newOrGetFileStorage("db", "./data/db", 250, FilesFormat.XML);
```

The format is implemented as a pair of streaming reader/writer factories:

- `IXdStorageIOFactory` — creates a reader/writer pair per resource;
- `IXdStorageObjectsReader` / `IXdStorageObjectsWriter` — the actual (de)serialization SPI.

Built-in implementations: a hand-rolled block-based **YAML** writer/reader (`serialization.yaml.*`, the
default format, with commit-journal support) and the **XML** factory (`XdStorageDefaultIOFactory`).
Every storage folder records its on-disk format generation in `.xdstorage.meta`
(`formatVersion` — see [EMBEDDING.md](EMBEDDING.md) § Compatibility). To add a custom
format, implement the factory/writer/reader triple and wire it into `XdStorageServicesLocator`.

The physical naming of resource files is handled by `IXdStorageResourceNamingService` (default:
`XdStorageResourceNamingService`, which derives file names from the storage folder, format and resource ids).

**A storage folder is data you can trust only as far as its author.** The files carry class names the
engine instantiates, so open a folder your application wrote — or one whose author you trust. The
XML reader processes no DTD: a `<!DOCTYPE>` (the route for external-entity, or XXE, attacks — reading
local files, calling remote addresses) is refused with a clear error instead of being obeyed
([36](tasks/2.2.0/36-xml-reader-xxe-hardening.md)). What opening a folder from someone else may and
may not do is the subject of [37](tasks/2.2.0/37-data-folder-trust-boundary.md).

### The readable-on-disk story: fixtures, review, and machine-readable storage

A storage folder is plain YAML — the same file a transaction writes is the file you can
`cat`, `grep`, diff in a code review, commit as a test fixture, or point an LLM agent (or any
script) at **without any transformation layer**: the human-readable format is machine-readable
by construction. A real fragment of the `Ship` class (one file holds up to `fragmentSize`
objects; the `# xdstorage-eof` sentinel is what makes truncation loud):

```yaml
objects:
    - object:
        class: 'fixturegen.Ship'
        fields:
            - field:
                name: 'name'
                type: 'java.lang.String'
                value: 'Alpha'
            - field:
                name: 'tonnage'
                type: 'int'
                value: '120'
            - field:
                name: 'id'
                type: 'java.lang.Long'
                value: '1'

# xdstorage-eof
```

Because fixtures are files, they live in git next to the code that asserts them: a retuned
fixture is a reviewable diff, not a database migration script —

```diff
            - field:
                name: 'tonnage'
                type: 'int'
-               value: '120'
+               value: '180'
```

(Do not edit the files while the storage is open — the engine owns the folder; author
fixtures by running code, or by editing a closed folder's copy. The full layout:
[EMBEDDING.md](EMBEDDING.md) § Files and durability.)

Strings are stored single-quoted, an apostrophe doubled (`'it''s'`). The one exception is a
string with a line break: it takes double quotes and the escapes `\n` / `\r`
(`"first line\nsecond line"`), so that a value always stays on one physical line — the
property the tab-indented layout and the EOF sentinel are built on. A folder written by an
earlier version reads identically; see [EMBEDDING.md](EMBEDDING.md) § On-disk format
compatibility for the one downgrade rule.

## Structure updates (schema migration)

When the declared structure of a class changes (for example a search index got new fields), persisted
structures need to be rebuilt. XdStorage does this through pluggable updaters:

```java
// register a custom updater and run all pending updates for the class
storage.registerStructureUpdater(new MyStructureUpdater());
List<IXdStorageStructureUpdater> executed = storage.executeStructureUpdate(MyEntity.class);
```

- An updater implements `IXdStorageStructureUpdater<T>` (`getDataClass()`, `getUpdateName()`,
  `execute(resourcesManager, tx)`, result/error accessors); `XdStorageAbstractStructureUpdater` is a base
  class.
- The built-in `XdStorageSearchIndexStructureUpdater` re-builds a search index record set when the
  structure manager detects that the persisted index no longer matches the class annotations; it is
  registered automatically during the structure check.

## Operability: verify, backups, and after a power loss

- **`XdStorageProvider.verify(folder)`** — a read-only integrity walk over a storage folder,
  no lock taken (a live storage reports IN USE, "results unreliable", never refused):
  per-file EOF-sentinel state (`TRUNCATED` names the file), journal state and torn tails,
  data objects vs id-index entries per class, the format marker, orphaned journals, stale
  `.tmp` litter. Verdicts: `OK` — nothing to act on; `WARNINGS` — damage the engine
  tolerates by design; `CORRUPT` — a v2 file without its sentinel, an unparseable file, a
  count mismatch. Never repairs, never opens a storage; the folder is byte-identical after.
- **Backup = `shutdown()`, `cp -r`, open the copy** under a new storage name — the tested
  contract ([EMBEDDING.md](EMBEDDING.md) § Backup and restore); no export/import step, no
  format conversion. A `.xdstorage.lock` file copied along is harmless.
- **After a power loss** — the verified damage matrix and the recovery recipe (verify first,
  then the accept-truncation / restore-from-backup decision, then the per-subtree purge with
  FK indexes dropped): [EMBEDDING.md](EMBEDDING.md) § After a power loss.
- **Metrics** — `storage.getMetrics()` (per-storage transaction/write-out counters) and
  `XdStorageMetrics.engineEvents()` (JVM-wide anomaly counters): [EMBEDDING.md](EMBEDDING.md)
  § Observability.

## Concurrency model and internals

- **Everything is transactional and multi-versioned.** Writers stage changes in a per-transaction workspace
  and install them during the two-phase commit; readers see a consistent snapshot (`SNAPSHOT`) or the latest
  committed state (`READ_COMMITTED`), never a half-committed one.
- **Concurrent writers stage, then apply (2.1.0).** B+tree key mutations — id indexes, search keys,
  FK trees — stage into the writing transaction's overlay, invisible to others until the commit, and
  apply as a batch at its commit; the commit acquisition order across the resource bands is **total**
  (the tie-break by the resource id). This removed the acquisition-deadlock family: 10 concurrent
  writers measure **121–158 tx/s (p99 84–113 ms)** on the heavy indexed shape, where 2.0.x decayed
  to ~1 tx/s through lock-timeout cycles. First-committer-wins is re-validated at commit time
  (§ [Transactions](#transactions)) — the convoy fix did not reopen the lost-update window.
- **Wrapper code generation.** For each entity class the storage needs generated wrapper classes:
  an *observable* wrapper (write-through + deferred-id observation) and an *unmodifiable* wrapper (the
  snapshot view handed to readers). They come from the application's own classes when they were generated
  at build time (EMBEDDING § Running on a JRE — checked by a fingerprint of the class and the engine
  version); otherwise the engine compiles them at first use, which takes a JDK and a writable working
  directory — and when neither is possible, opening the storage fails by name instead of degrading.
  This is why entities must be top-level classes with full getters/setters.
- **Worker threads.** Each storage instance owns a fixed thread pool (default 15, `workerPoolSize`) of named
  `xdstorage-worker-N` threads used for parallel resource I/O during load/commit, the timeout sweeper and
  triggers. Threads are non-daemon by default (pre-config behavior); with
  `XdStorageConfig.builder().daemonWorkerThreads(true)` a forgotten `shutdown()` cannot block JVM exit.
  Always call `shutdown()` when finished — it is idempotent and optionally registered as a JVM shutdown
  hook (`registerShutdownHook(true)`).
- **Resource layout.** A storage folder contains fragment files per class resource, reference files for
  `StoreAsSingleObject` classes, id-index files (hash/B+tree persisted as B+tree nodes), search-index
  trees, and structure metadata. Fragment size caps the number of objects per `StoreAsClassObjects`
  fragment.
- **Rollback safety.** Any failure during commit triggers rollback of the already-processed phases
  (including index changes), returning the database to the last valid state.

## Performance

Two measurement sources back this section: JMH facade benchmarks
([BENCHMARKS.md](BENCHMARKS.md)) and the configuration-tuning study
([PERFORMANCE-RECOMMENDATIONS.md](PERFORMANCE-RECOMMENDATIONS.md)), which swept the
structural knobs — B+tree fill factor `t`, fragment size, id-index type, search-index `t` — through the
full data lifecycle (bulk insert → warm reads → update → cold restart → delete).

Measured cost model (the canonical baseline — [BENCHMARKS.md](BENCHMARKS.md) § Canonical tables,
JDK 21.0.7, quiet machine, 2026-11-21; full numbers and methodology there):

- Indexed search through the facade costs ≈ a plain id load (~225k vs ~217k ops/s, cache-resident) —
  the B+tree lookup + record read adds almost nothing; secondary-field and child criteria are evaluated
  on the already-fetched record and add ~0%.
- `OR` of two primaries is near-linear in the number of branches (~187k ops/s).
- Scan paths (`NOT_EQUALS` / no primary criterion) visit every record: ~1.5k ops/s — ~150× below
  the point path (O(dataset), and the gap grows linearly with the data).
- Range primaries walk the ordered index with early termination: a selective upper-bounded range
  (tight `BETWEEN` ~24k ops/s, `IN` ~23k) runs ~16× the scan it replaces; a lower-bound-only
  `GT` (~15k — no early termination, the whole key range is walked, only matches are fetched)
  and a wide range (~12k) close in on the data-covered window's cost.
- Writes are commit-bound: the canonical shapes measure rename ~1.7k ops/s, save+delete ~600 ops/s
  (the one attributed regression, -24% code-level vs 2026-10-24 — the correctness wave's price),
  data-heavy writes ~3.1k–8k tx/s with the journals (2.6× journal-on ratio) — the
  75/39/127 figures of the TRACE era are historical (BENCHMARKS § history).
- Concurrent writes: index mutations stage per-transaction and apply at commit under a total
  acquisition order — 10 concurrent writers measure **121–158 tx/s (p99 84–113 ms)** on the heavy
  indexed shape, where 2.0.x collapsed to ~1 tx/s on lock-timeout cycles.
- Foreign keys tax writes, not reads: cascade-save **23–160×**, child delete **12–73×**, parent
  update **1.4–12.5×** vs FK off (linear in the FK-tree size; reads ~1.0×) — enable FK for
  integrity-critical graphs, not hot write loops ([BENCHMARKS.md](BENCHMARKS.md) § FK A/B).
- The read snapshot is captured eagerly: ~10–33% of load cost by graph size, measured and accepted
  as the alias-safety net in 2.1.0; the lazy capture is a 2.2.0 candidate.
- A read-only transaction (begin → load → commit) is cheap at any residency: ~71k ops/s
  (~14 µs/op) cache-resident on the canonical baseline, and the config-sweep harness measures
  0.63–0.74 ms/op **flat from N=5000 to N=40000** with zero file write-outs — high-rate per-op
  read transactions are safe as the dataset grows.

### Tuning recommendations

Compiled from [PERFORMANCE-RECOMMENDATIONS.md](PERFORMANCE-RECOMMENDATIONS.md) (measured at
N=5000 YAML entities; the relative gaps are the signal — re-run the sweep at production volume before
locking choices, recipe in its § 9). The first three recommendations are **already the engine
defaults**:

- **Fragment size: 128–512 objects per fragment** — engine default `128` (the `newOrGetFileStorage`
  overloads without an explicit fragment size). Aim for fragment files of tens to low hundreds of KB.
  **Above ~1 MB is a red flag**: every commit rewrites each touched fragment file whole (a
  one-fragment-per-class layout with 4.5 MB files made the update pass +85% slower). **Below ~10 KB is
  a red flag too**: 626 tiny files cost ~30% insert throughput and doubled the cold scan.
- **B+tree fill factor `t`: 32–128, default 64** in both annotations. Warm lookups are `t`-insensitive —
  the penalty of a bad `t` is structural. At N=5000, `t=2` produced ~10 000 node files (12×
  index-size amplification), 129 ms commits, a 19× slower delete-all and 387 µs cold lookups, versus
  3.7 ms commits and 87 µs cold lookups at `t=64` (cold reads pay ~35 µs per tree level — deep trees
  tax every uncached lookup after a restart). **Never ship `t` below 8.**
- **Search index `t = 64`** (same annotation default): indexed saves ~5× faster than the old `t=500`
  default and the first cold query lands ~19 ms earlier; warm query latency is `t`-insensitive.
- **Id index type — choose by workload** (`@XdStorageObjectIdIndexType`):
  - **Hash** (default): best cold-read latency after restart (52 vs 87 µs/op — one fragment load vs a
    tree descent), fewest files (12 vs 101 at N=5000), simplest operations; equality-only.
  - **B+Tree**: insert-heavy pipelines (~2.1× bulk insert) and anything that needs ordered keys or
    id-range traversal.
- **Query shapes:** keep the primary criterion selective — `EQUALS`, or a range with a tight upper
  bound. Treat `NOT_EQUALS`, primary-`CONTAINS` and primary-less queries as reporting-tier operations
  (full index scan). Index child fields you actually filter on: each indexed child collection adds
  per-save tree maintenance.
- **Transactions:** the 3 s default timeout is tight for batch writes — pass explicit timeouts for
  bulk batches (the study's harness uses 120 s for 500-object batches); raising the default to 10–30 s
  is reasonable for batch-oriented services.
- **Durability:** commits are always process-crash-safe (atomic rename, journaled appends); enable
  `durableWrites(true)` when the machine can lose power — it adds one fsync per written file per
  commit, measured **~95×** on the canonical shape (an NVMe-ext4 fsync bound; an HDD shifts it).
- **Journaling is the default write path** — all three journals are ON by default:
  `journalWrites` (data-class commits, W-3), `indexJournalWrites` (id-index commits, W-4a)
  and `searchIndexJournalWrites` (search-key mutations, W-4b) append O(delta) records
  instead of rewriting files, and a clean `shutdown()` checkpoints every journal (W-4c) —
  after a clean close, a power loss loses nothing even with `durableWrites=false`. The
  remaining write-latency wall is the **relation-write path**: FK maintenance scales linearly
  with the FK-tree size ([BENCHMARKS.md](BENCHMARKS.md) § FK A/B; the 2.2.0 fix is filed),
  plus sustained multi-10³ writes/s under concurrency. Design record:
  [WRITE-PATH-DESIGN.md](WRITE-PATH-DESIGN.md), [W4-DESIGN.md](W4-DESIGN.md).
- **Entity layout:** `StoreAsClassObjects` (fragmented) for "many small objects of one class" domains;
  `StoreAsSingleObject` only for genuinely large top-level graphs — one file per object is what makes
  infrastructure file counts explode at small object sizes.
- **Monitor** `storage.getMetrics()` and `XdStorageMetrics.engineEvents()`: sustained growth of
  `resourceWriteOutNanos` per write-out ⇒ fragments too big or the disk saturating;
  `timeoutSweptRollbacks` / `rollbackOnlyMarks` > 0 ⇒ transaction timeouts too tight; any
  `lockAcquisitionTimeouts` / `lockWaitsOver1s` / `findRetryBudgetExhaustions` ⇒ sustained write
  contention. Healthy-range thresholds per counter: the study's § 10.

Run the benchmarks yourself — `scripts/run-benchmarks.sh` is the one entry point (it owns the
conditions protocol: quiet-machine gate, WARN-console logging, YAML format, JSON results +
run manifest):

```bash
bash scripts/run-benchmarks.sh quick   # the smoke (~minutes)
bash scripts/run-benchmarks.sh full    # the canonical baseline
bash scripts/run-benchmarks.sh sweep   # the plain-Java config sweep (PERFORMANCE-RECOMMENDATIONS.md)
bash scripts/run-benchmarks.sh n50k    # the design-point boundary probe
```

## Project layout

Main packages (`io.github.xdstorage`):

| Package | Contents |
|---|---|
| (root) | `IXdStorage`, `IXdFileStorage`, `XdStorage` facade, `XdStorageProvider`, `XdStoragePolicy`, watcher/predicate interfaces |
| `annotations` | All entity annotations |
| `transaction` | Transaction interface, manager, 2PC commit state |
| `search`, `search.query` | Search engine, indexes, criteria query API |
| `resource`, `resource.cache` | Resource DAOs, naming, MVCC record/workspace cache, commit protocol |
| `index`, `index.hash`, `index.btree` | Hash and B+tree id-index resources |
| `serialization`, `serialization.yaml` | IO factory SPI and the YAML/XML implementations |
| `trigger`, `observing` | Insert/update/delete triggers, deferred-id observers |
| `idgeneration` | Id generator SPI, counters, UUID generator |
| `structure`, `structure.update` | Class/index structure metadata and updaters |
| `code` | Runtime wrapper class generators |
| `factories`, `utils`, `helpers`, `lock`, `btree`, `object`, `exceptions` | Cloning, reflection helpers, read-write lock, B+tree engine, generic object holders, exception hierarchy |

Related pages: [EMBEDDING.md](EMBEDDING.md) (the operational contract),
[BENCHMARKS.md](BENCHMARKS.md) (measurements), [FLAKY.md](FLAKY.md) (flake ledger),
[WRITE-PATH-DESIGN.md](WRITE-PATH-DESIGN.md) (the write-path roadmap).
