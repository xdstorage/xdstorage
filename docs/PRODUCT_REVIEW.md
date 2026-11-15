# Product Review: XdStorage 2.1.0 — A Potential User's Perspective

**Reviewer role:** Architect / tech lead evaluating XdStorage for an embedded Java application (order management system with 10⁴–10⁵ orders, each with ~20 line items).
**Scope:** What I see from the outside — docs, benchmarks, the 959-test suite, the CHANGELOG, the EMBEDDING, the decision guide, the quickstart example.
**Tone:** The same honest framing the project itself uses — numbers where available, "this is a gap" where it is. No vendor-speak.

---

## 1. What I See as Positive

### 1.1 The documentation is the product's strongest surface.

The project does not hide anything. The **DECISION-GUIDE.md** (a competitive comparison with sourced order-of-magnitude numbers — RocksDB, LMDB, Chronicle Map, MapDB) is the kind of document a real architect reads. It honestly states: *"XdStorage wins: the typed domain model plus a query engine plus triggers. XdStorage loses: one to two orders below KV stores."* Nobody else in this class publishes that honest gap with numbers.

The **EMBEDDING.md** is a real operational contract — not a marketing one. It states the thread-binding model, the deferred-id timing, the folder-lock rule, the codegen requirement (JDK, not JRE; `./gencode` must be writable), the nested-transaction load caveat ("NOT RECOMMENDED for load-heavy use"), and the zombie-latch decision ("the obligation is on the caller"). An embedder can read this and know every surprise before production.

The **BENCHMARKS.md** is generated from archived runs (machine profile, JDK version, flags recorded). The numbers are traceable to a JSON result. The "in real terms" reading of each scenario is there. This is how engineering transparency should look.

The **CHANGELOG** follows Keep a Changelog, records on-disk format generations (v1/v2), and lists every fix with its task file link and the exact reproduction condition (BUG-B: "two live generations of one entity FQCN collide on one wrapper class" — this is a real bug, documented with the exact reproduction).

The **FLAKY.md** exists. Nobody publishes their flaky tests. The project does. The known-flake ledger is a trust signal.

### 1.2 The engine does more than the class of alternatives.

The comparison table is generous. Here is what XdStorage has that MapDB, RocksDB, Chronicle Map, H2 MVStore, and Xodus do not:

| Feature | XdStorage | Alternatives |
|---|---|---|
| Annotated POJO **is the schema** | ✅ | Every alternative needs a mapping layer |
| Triggers (insert/update/delete) | ✅ | No alternative in this class |
| MVCC snapshot reads | ✅ | RocksDB (atomic batches only); Chronicle (none); H2 (MVCC but no object layer) |
| Human-readable YAML/XML storage | ✅ | Only XdStorage |
| Pluggable serialization (SPI) | ✅ | Only XdStorage |
| `explain()` query plan (INDEX_SEEK / RANGE_WALK / SCAN) | ✅ | No alternative exposes the plan |
| `verify(folder)` integrity walk (CORRUPT/WARNINGS/OK) | ✅ | No alternative |
| Cascade save (`@XdStorageAutoSave`) | ✅ | No alternative |
| Referential integrity (FK) via persistent B+tree | ✅ | No alternative |
| Streaming reads (`watch`, predicate filter) | ✅ | No alternative |
| Zero external dependencies (SLF4J only, 65 KB) | ✅ | RocksDB needs JNI; LMDB needs native |

The "everything you'd build yourself" column in the decision guide is the honest summary: every alternative needs a serialization, indexing, query, and domain-model layer. XdStorage delivers all four.

### 1.3 The 2.1.0 train is a coherent engineering campaign, not a scatter-shot.

The CHANGELOG's `[Unreleased]` section maps every item to a task file with a number:
- 26: `verify(folder)`
- 25: `explain()`
- 22: `@XdStorageAutoSave`
- 42: foreign keys
- 32: W-4a index journaling
- 23: power-loss recovery
- 24: journal file naming
- 28: javadoc coverage
- 29: README landing page
- 30: benchmarks in context

This is the kind of engineering discipline that signals: "someone is actually running this." The task files have status (open/done), scope, acceptance criteria, tests, and decision records. The "design round" gate appears where design matters (W-4b, W-4d). The "soak → flip" protocol appears where defaults matter.

### 1.4 The engine's honest limitation is quantified.

The README says: *"Writes are the project's known weak spot — measured, and the main fix has landed in 2.1.0 (W-4a)."* The numbers: **~3,100 tx/s (journal off) / ~9,000 tx/s (journal on)** on the data-heavy shape. The small-write cost is **flat 0.13–0.27 ms/tx from 10³ to 5·10⁴ objects** (before, it degraded linearly to 64.9 ms/tx). The remaining frontier is the search key-tree rewrite (W-4b pending).

This is the honest framing: *"That is still one to two orders below raw KV stores, and one path remains whole-file: the search key-tree rewrites."*

### 1.5 The quickstart is a real, runnable demo.

`cd examples/quickstart && mvn test` — one test class walks the entire lifecycle: open → transaction → save → indexed query → range query with `ORDER BY DESC` + `LIMIT` → `count()` → commit → shutdown → reopen → load → shutdown. The example is the code to copy. This is how a real product should present itself.

### 1.6 The engineering is mature for the age of the project.

First public release: 2026-10. Yet the project has:
- 959 tests (up from ~120 at the 2.0 start)
- A flake ledger (FLAKY.md)
- A design round protocol (R-28/R-29/R-30 rejection reasons documented)
- A soak-then-flip gate for defaults
- A benchmark estate (3 entry points: quick/full/sweep)
- A release profile with GPG signing, Central Portal, reproducible builds, Dependabot
- A task-file system with open/done status, acceptance criteria, decision records
- A "rejected alternatives" section in every design doc

This is not a student project. This is a single maintainer who is actually building a product.

---

## 2. What I See as Negative

### 2.1 Single maintainer. First public release October 2026.

There are no production references. No customer logos. No adoption story. The README says: *"a young single-maintainer project; judge the ~800-test suite, the published benchmark runs and the known-flakes documentation on their merits."* This is honest, but it is also a risk: if the maintainer steps away, the project dies. For a production decision, this is a material risk.

### 2.2 The JDK requirement. No JRE. No AOT.

The engine generates wrapper classes at runtime via the system Java compiler. **"A JRE silently degrades to broken wrappers."** The codegen target `./gencode/` must be writable. Entity classes must be top-level. The AOT fix (FP-3) is on the roadmap but not implemented.

For a product that claims *"zero dependencies, zero native, Java 8 bytecode"* — requiring a JDK at runtime and a writable working directory is a real constraint. AOT (Java 8 bytecode, no codegen) is the roadmap item. Without it, containers must ship a JDK (which defeats the "zero dependencies" claim in practice).

### 2.3 The wrapper-codegen collision bug (BUG-B, still open).

After the A/C fixes, the remaining seam is precise: two live generations of one entity FQCN collide on one wrapper class. The fix is "generation-aware wrapper identity." This is a real bug in the codegen layer. For a product that says *"the annotated class IS the schema"* — the schema identity must be stable across reloads.

### 2.4 The nested-transaction load path is a known critical path.

The CHANGELOG states: *"Nested transactions are NOT RECOMMENDED for load-heavy use (decision 2026-10-26): the nested commit/rollback machinery is a known critical-under-load path."* This is a real gap. A product that claims ACID semantics should not have a critical-under-load path in its transaction model.

### 2.5 No backup/restore API yet. No migration tool.

The EMBEDDING says: *"the backup and restore API (FP-5) — deferred to 2.3.0."* For a product whose operational contract is *"one folder you can cp -r"*, the absence of a backup API is a gap. The recipe is: close the storage, `cp -r` the folder, open under a new name. That works. But a `backupTo(Path)` API is the missing step.

The migration story is the same: "rename `*.json` → `*.xml`." For a product with format generations (v1/v2), a migration tool would be the right call.

### 2.6 No SQL. No joins. No full-text search.

This is listed in the README's "When not" section, and it is fair. But the project's decision guide puts XdStorage in the same comparison class as H2/SQLite, and a reader who sees *"Xodus — no query or MVCC"* might assume XdStorage's query is equivalent to SQL. It is not. It is a criteria-based search engine with range operators. For a product that claims *"ACID transactions, declarative indexes and triggers"* — the absence of a SQL layer is a gap that a real architect needs to see clearly.

### 2.7 The 2.2.0 roadmap is large and unbounded.

The 2.2.0 roadmap includes: consistency-core rebuild, loud-failures inventory, property-based tests, W-4b search-key journal, W-4d disk-anchored scans, backup API, recovery recipe automation, cross-process durability coordination, no-sentinel repair, the server-mode feasibility document (which contradicts the "no servers" positioning). That is a lot of P0/P1 work for a 2.2.0 that has not shipped a 2.1.0.

### 2.8 The project's naming is confusing.

`FdStorage` is the product. `XdStorage` is the class. The Maven coordinates are `io.github.xdstorage:xdstorage`. The package is `io.github.xdstorage`. The `FdStorage` class is the engine. The `XdStorage` class is the config. The `FdStorageProvider` is the provider. The naming is inconsistent: `Fd` (the engine's internal class) vs `Xd` (the public API). A new reader needs to untangle this.

### 2.9 The documentation is dense.

The CHANGELOG has entries like: *"the W-4b search-key logical-op journal (the O(N) wall for search-index writes) is the last O(N) wall in the write path, and the search-index is the remaining O(N) wall."* This is the same prose repeated. The project writes like it is talking to itself. For a potential user who has never seen the codebase, the dense prose is a barrier. The README is a good landing page, but the deep docs are written in the author's shorthand (task numbers, code names, the "design-round gate" language).

---

## 3. Applicability for AI Agents and MCP Tools

### 3.1 Where XdStorage aligns with agent needs.

| Agent need | XdStorage alignment |
|---|---|
| **A single source of truth (file)** | ✅ The storage IS a folder. The agent reads files directly. No network. |
| **Structured data with a schema** | ✅ The annotated POJO is the schema. The folder is the database. |
| **Transactions (atomicity)** | ✅ ACID 2PC, MVCC snapshots. The agent's tool can commit/rollback. |
| **Triggers (side effects)** | ✅ Insert/update/delete triggers. The agent's tool can register triggers. |
| **Cascade save (complex state changes)** | ✅ `@XdStorageAutoSave`. The agent can save a parent with children atomically. |
| **Referential integrity (FK)** | ✅ Persistent B+tree FK indexes. The agent's tool can enforce FK. |
| **Streaming reads (large data)** | ✅ `watch`, predicate filter. The agent can stream without materializing. |
| **`explain()` (cost model)** | ✅ INDEX_SEEK / RANGE_WALK / SCAN. The agent can choose plans. |
| **`verify(folder)` (integrity)** | ✅ The agent's tool can check folder integrity before reading. |
| **Backup (the universal interface)** | ✅ `cp -r` works. The agent can backup with a tool. |
| **Human-readable storage (debugging)** | ✅ YAML files. The agent can inspect files directly. |
| **No server, no network** | ✅ The storage is local. The agent runs in the same process. |

The alignment is strong. The project's positioning — *"persist actual domain objects in a human-readable folder"* — is exactly the agent's world: a file is the universal interface. The structured data (YAML) is parseable. The schema (POJO annotations) is declarative. The transactions are atomic. The triggers are reactive. The FK are constraints. The backup is a file copy. The verify is a checksum.

### 3.2 What the agent needs that XdStorage does NOT have yet.

| Agent need | XdStorage gap |
|---|---|
| **A backup API (`backupTo(Path)`)** | Deferred to 2.3.0. The agent needs a programmatic backup, not `cp -r`. |
| **A migration tool** | Manual rename (`*.json` → `*.xml`). The agent needs a migration command. |
| **A schema diff tool** | `executeStructureUpdate` exists, but there is no "diff schemas" command. |
| **A query API that returns JSON** | The search engine returns POJOs. For an agent, JSON is the native format. |
| **A read-only API** | `verify(folder)` is read-only. But there is no "read the folder without opening the engine" mode. |
| **A tool for the agent** | No CLI tool (`xdstorage-tools.jar`) is mentioned in the docs (only the `compact` command is planned for 2.3.0). |
| **A schema migration tool** | `executeStructureUpdate` exists but is manual. |
| **A backup/restore CLI** | The `compact` command is planned for 2.3.0. No backup CLI exists yet. |

### 3.3 The biggest potential direction: **the agent's embedded database.**

XdStorage's positioning — *"persist actual domain objects in a human-readable folder with ACID transactions, declarative indexes and triggers, in one folder you can back up with `cp -r`"* — is the agent's native world. The agent reads files. The agent writes files. The agent runs `cp -r`. The agent checks integrity. The agent migrates schemas. The agent runs transactions.

The biggest potential is: **XdStorage as the embedded database for agent applications.** An agent app that persists its own state (the agent's working memory, the tool's history, the conversation's state) — all in one folder, all ACID, all trigger-able, all queryable, all backed up with `cp -r`. This is the "agent memory store" use case. The agent's state is the domain object. The folder is the memory. The transactions are the atomic state changes. The triggers are the reactive side effects. The FK are the constraints. The backup is the universal interface.

The direction: **XdStorage for agent state management.** Not a replacement for a relational database. Not a replacement for RocksDB. A replacement for: "I have a JSON file that I read and write, and sometimes I want ACID, and sometimes I want to query it, and sometimes I want a backup."

---

## 4. What I Would Like to See in This Product to Use It

### 4.1 The non-negotiables (I will not adopt without these):

1. **AOT compilation (FP-3).** The product claims "zero dependencies" and "Java 8 bytecode." A runtime JDK requirement with `./gencode` is not "zero dependencies." AOT is the roadmap item. Without it, the product is not deployable in a JRE-only container.

2. **A backup/restore API (`backupTo(Path)`).** The product's operational contract is *"one folder you can cp -r."* A programmatic backup API is the missing step. Without it, the product cannot be used in a production pipeline that needs programmatic backup (e.g., Kubernetes, CI/CD).

3. **A migration tool.** The v1→v2 migration is `"rename *.json → *.xml."` For a product with format generations (v1/v2), a migration tool is the right call.

### 4.2 The high-priority items (I will adopt after these land):

4. **A CLI tool (`xdstorage-tools.jar`).** The product has `compact` planned for 2.3.0. But a `xdstorage-tools.jar verify folder`, `xdstorage-tools.jar backup folder dest`, `xdstorage-tools.jar migrate folder` would make the product usable from a pipeline. The product has the engineering for a CLI (the `compact` command is already in the codebase).

5. **A schema diff tool.** `executeStructureUpdate` exists. But a "diff schemas" command would make migration operational.

6. **A read-only folder-reader.** The `verify(folder)` exists. But a "read the folder without opening the engine" mode would let the agent inspect the storage without the engine's lifecycle.

7. **The AOT compilation.** This is the non-negotiable. The product claims "zero dependencies" — a runtime JDK is not zero dependencies. AOT is the roadmap item. Without it, the product is not deployable in a JRE-only container.

8. **The SQL layer.** The product's decision guide puts XdStorage in the same comparison class as H2/SQLite. For a product that claims "ACID transactions, declarative indexes and triggers," the absence of a SQL layer is a gap. For an agent, SQL is the native query language. A SQL layer would make XdStorage a direct alternative to H2/SQLite for agent apps.

### 4.3 The nice-to-haves (I will adopt with these):

9. **A schema migration tool.** The v1→v2 migration is manual. A tool would make migration operational.

10. **A read-only folder-reader.** The `verify(folder)` exists. But a "read the folder without opening the engine" mode would let the agent inspect the storage without the engine's lifecycle.

11. **A backup/restore CLI.** The `compact` command is planned for 2.3.0. A full backup/restore CLI would make the product usable from a pipeline.

12. **A schema diff tool.** `executeStructureUpdate` exists. But a "diff schemas" command would make migration operational.

---

## 5. When I Would Choose This Product

### 5.1 I would choose XdStorage when:

| Condition | Why XdStorage wins |
|---|---|
| **My app persists a domain model of 10⁴–10⁵ objects** | The design point is 10⁴–10⁵ objects. The heap cost is ~1.4–2.0 KB per object (≈70–200 MB for 10⁵). This fits the use case. |
| **I want the schema to be the POJO, not a SQL schema** | The annotated class IS the schema. No mapping layer. No SQL DDL. The schema lives in the code. |
| **I want triggers on insert/update/delete** | XdStorage's triggers are the only alternative in this class with triggers. |
| **I want the storage to be a human-readable folder** | YAML/XML files. I can `cat` them. I can diff them in code review. I can `cp -r` backup. |
| **I want ACID + MVCC without a server** | ACID 2PC, MVCC snapshot reads, triggers in-commit. No server. No native code. |
| **My writes are < 10⁴ tx/s** | The measured ~3,100 tx/s (journal off) / ~9,000 tx/s (journal on) fits the use case. |
| **I need foreign keys** | XdStorage's FK are the only alternative in this class with FK. |
| **I need cascade save** | XdStorage's `@XdStorageAutoSave` is the only alternative in this class with cascade save. |
| **I need streaming reads** | XdStorage's `watch` and predicate filter are the only alternative in this class with streaming reads. |
| **I need explain()** | XdStorage's `explain()` is the only alternative in this class with explain. |
| **I need verify(folder)** | XdStorage's `verify(folder)` is the only alternative in this class with verify. |
| **I need pluggable serialization** | XdStorage's SPI is the only alternative in this class with pluggable serialization. |
| **I need zero external dependencies** | SLF4J only (65 KB). No native code. No JNI. |

### 5.2 I would NOT choose XdStorage when:

| Condition | Why XdStorage loses |
|---|---|
| **I need >10⁴ writes/s** | RocksDB wins. XdStorage's 3,100–9,000 tx/s is one to two orders below raw KV. |
| **I need multi-process sharing** | The folder is exclusively locked. Chronicle Map wins. |
| **I need SQL/joins/analytics** | H2/SQLite win. XdStorage's query is criteria-based, not SQL. |
| **I run in a JRE-only container** | XdStorage needs a JDK at runtime. AOT (FP-3) is the fix. |
| **I need a SQL layer** | H2/SQLite win. XdStorage has no SQL. |
| **I need a migration tool** | Manual rename. XdStorage has no migration CLI. |
| **I need a backup CLI** | `cp -r` works, but a programmatic backup API is deferred to 2.3.0. |
| **I need production references** | Single-maintainer, first release October 2026. No logos. |
| **I need vendor support** | A single-maintainer project. No vendor. |

### 5.3 The decision summary.

**I would choose XdStorage for:** an embedded Java application that persists its domain model in a human-readable folder with ACID transactions, declarative indexes and triggers, and a backup that is a file copy. The app has 10⁴–10⁵ objects, writes < 10⁴ tx/s, and the domain model is the schema.

**I would NOT choose XdStorage for:** a multi-process application, a SQL application, a JRE-only container, a high-throughput application, or an application that needs production references.

---

## Appendix: The Honest Numbers

| Metric | XdStorage 2.1.0 | Source |
|---|---|---|
| Small-write cost (flat across design point) | 0.13–0.27 ms/tx | BENCHMARKS.md § Canonical |
| Canonical write throughput (journal off) | ~3,100 tx/s | BENCHMARKS.md § Canonical |
| Canonical write throughput (journal on) | ~9,000 tx/s | BENCHMARKS.md § Canonical |
| Test suite size | 959 tests | CHANGELOG § [2.0.0] |
| Test coverage (track T) | structure 8%→54%; trigger 73%→88%; index 100% | CHANGELOG § [2.1.0] Track T |
| Heap per object | ~1.4–2.0 KB | EMBEDDING.md § After a power loss |
| Write cost `durableWrites=true` | ~8× (47 vs 367 ops/s) | BENCHMARKS.md § Canonical |
| First public release | 2026-10-21 | CHANGELOG § [2.0.0] |
| Production references | 0 | N/A |
| On-disk format generations | v1 (1.x), v2 (2.0+) | CHANGELOG § On-disk format generations |
| Serialization formats | YAML, XML; SPI for custom | CHANGELOG § [2.1.0] Changed |
| Query operators | EQUALS, NOT_EQUALS, CONTAINS, IS_NULL, IS_NOT_NULL, GT, GTE, LT, LTE, BETWEEN, IN | CHANGELOG § [2.1.0] Added |
| Result shaping | ORDER BY, LIMIT, OFFSET, count() | CHANGELOG § [2.1.0] Added |
| Cascade save | `@XdStorageAutoSave` (collections, single refs, arrays, MAP) | CHANGELOG § [2.1.0] Added |
| FK | Persistent front/reverse B+tree; `enableForeignKeys` default ON | CHANGELOG § [2.1.0] Added |
| Streaming reads | `watch`, predicate filter | CHANGELOG § [2.1.0] Added |
| `explain()` | INDEX_SEEK / RANGE_WALK / SCAN | CHANGELOG § [2.1.0] Added |
| `verify(folder)` | CORRUPT / WARNINGS / OK verdict | CHANGELOG § [2.1.0] Added |
| Trigger types | Insert, Update, Delete | EMBEDDING.md § Cascade save and transactions |
| MVCC isolation | SNAPSHOT / READ_COMMITTED | EMBEDDING.md § Transactions and threads |
| Transaction timeouts | Lazy enforcement (on lock wait/latch/rollback-only sweeper) | EMBEDDING.md § Transactions and threads |
| Folder lock | `.xdstorage.lock` (OS file lock + JVM registry) | EMBEDDING.md § Lifecycle |
| Codegen requirement | JDK (not JRE), `./gencode/` writable, top-level classes only | EMBEDDING.md § Codegen requirements |
| JVM-wide state | Provider registry, codegen caches, commit-sequence counter, deferred-id observer registry | EMBEDDING.md § JVM-wide static state |
