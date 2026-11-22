# Changelog

All notable changes to XdStorage are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[SemVer](https://semver.org/). The release procedure itself lives in
[RELEASE-PLAN.md](docs/RELEASE-PLAN.md).

**On-disk format generations** (recorded per storage folder in `.xdstorage.meta`,
see `XdStorageFormatMeta`):

- **v1** — the 1.x era: YAML files without an end-of-file sentinel, no commit journal.
- **v2** — the 2.0 era: YAML EOF sentinel (S-1), optional per-transaction commit journal
  (W-1, `journalWrites` ON by default since W-3).
- **v3** — the 2.1.0 era: the `indexes/` subdirectory layout; the marker is stamped at the
  automatic migration (a 2.1.0 folder cannot be opened by 2.0.0 — see the Breaking entry
  below).

## [2.1.1] — 2026-11-22

### Fixed

- **commons-lang3 `3.8.1` → `3.18.0`** — [CVE-2025-48924](https://nvd.nist.gov/vuln/detail/CVE-2025-48924)
  (uncontrolled recursion in `ClassUtils.getClass()` → a denial-of-service `StackOverflowError`;
  every commons-lang3 line before 3.18.0 is affected, including the `3.8.1` pin of 2.1.0/2.0.0).
  The engine's own exposure was minimal (basic helpers only, no hostile class-name inputs on
  the engine's paths) — the bump is the published-CVE patch discipline, not a reported exploit.
  This patch carries exactly this one fix: the full suite (1345 tests) green on the bump,
  nothing else changed.

## [2.1.0] — 2026-11-22

The train's user-facing changes; everything below has landed on the train and ships with
the 2.1.0 release.

**Release notes (the release-time disclosures):** upgrading from 2.0.0 runs the automatic
on-disk layout migration (the folder marker is stamped `formatVersion=3` — a 2.1.0 folder
cannot be opened by 2.0.0; back the folder up first, see the Breaking entry below; the
format generations are recorded in the header above). Known flake row at release
(documented, load-specific, no structure corruption):
`XdStorageBTreeResourceCacheIntegrationTest.testIntegration_HeavyParallelLoad_NoDeadlocksOrTimeouts`
(`@Tag integration`) — lock-contention backpressure under full-suite load only; see
[FLAKY.md](docs/FLAKY.md).

### Breaking

- **On-disk layout change** ([51](docs/tasks/2.1.0/51-layout-migration-upgrade.md)):
  indexes and B+Tree files relocated to a dedicated `indexes/` subdirectory. The
  migration runs automatically at open — a 2.0.0 folder is migrated in place (the old
  layout is not preserved; the data fragments stay in `data/`, the stacked journals are
  settled lazily by the journal-naming chain). The layout generation is stamped:
  after the migration the folder marker reads `formatVersion=3` — **a 2.1.0 folder
  cannot be opened by 2.0.0** (the newer-format gate refuses loudly; before this stamp
  both generations wrote `formatVersion=2` and the refusal claim was aspirational).
  The migration is crash-tolerant: a crash mid-migration is completed at the next open;
  an unmovable file (a Windows lock) refuses the open loudly instead of continuing with
  a silently empty index. **A 2.0.0 user must back up their folder before upgrading**
  (the standard `cp -r` while closed). See EMBEDDING "Upgrade from 2.0.0".
- **Index journals relocated into `data/<Class>/journal/`**
  ([54](docs/tasks/2.1.0/54-index-journals-to-data.md)): the id-index and search-key
  commit journals are DATA CARRIERS (the sole durable location of the un-checkpointed
  id→fragment addressability between checkpoints) and now live with the class's data
  instead of inside the derived-index `indexes/` directory. All of a class's commit
  journals — the fragments, the id-index, the search-key — sit in one place
  (`data/<Class>/journal/`); the journals found at the older spellings (the 2.1.0-dev
  `indexes/<Class>/journal/` or the siblings) settle into the data location automatically
  at the resource's first prepare.

### Added

- **`verify(folder)`** ([26](docs/tasks/2.1.0/26-verify-integrity-walk.md)) — a read-only
  integrity walk over a storage folder (the after-a-power-loss recipe, mechanized): per-file
  EOF-sentinel state and entry counts, journal state and torn-tail detection, per-class data
  objects vs id-index entries, the format marker, orphaned journals, stale `.tmp` litter, and
  a live folder lock (reported IN USE, never refused). Verdict `OK` / `WARNINGS` (damage the
  engine tolerates by design) / `CORRUPT` (truncated v2 files, unparseable files, count
  mismatches). Never repairs, never opens a storage, never takes the lock; corruption is
  detected on copies in the test suite — the folder is byte-identical after a verify.
- **B2** — read-only transaction commit path, verified and pinned by tests: no-change
  transactions commit through a cheap release path (no 2PC record iteration, zero file
  write-outs); measured 0.63–0.74 ms/op per read transaction at N=5000–40000 on the Hash path
  (the PERF study §5.3 ~25 ms figure no longer reproduces — BENCHMARKS.md addendum).
  Contract coverage: committed-count metrics, snapshot isolation under a concurrent writer,
  rollback-only still throws, a parent still waits for an open nested read-only transaction.
- **FP-1 phase 1** — query power pack, range primaries: `GT`/`GTE`/`LT`/`LTE`/`BETWEEN`/`IN`
  on the primary criterion, evaluated as an ordered bounded walk of the index B+tree with early
  termination (selective ranges run ~15× the scan they replace; lower-bound-only and wide ranges
  cost like a scan — GUIDE § Search engine has the operator table and plan/fallback matrix).
  Contract: `BETWEEN` bounds are inclusive (`XdStorageRange`); nulls never satisfy a range
  operator; mixed-type bounds fail loudly.
- **FP-1 phase 2 (FP-1b)** — result shaping: `ORDER BY` on the primary index value (native key
  order on the ordered walk; explicit in-snapshot sort on the scan path; ASC/DESC with nulls as
  the greatest value and a stable sort for equal keys; secondary-field ordering explicitly
  unsupported — the index orders by key trees, not arbitrary fields), `LIMIT`/`OFFSET` pagination
  after ordering (negative arguments fail loudly at construction; `LIMIT 0` is a legal empty
  page; on a single-criterion ascending range walk the limit is pushed down — the walk stops when
  the page is complete, ~25% ahead of the unbounded same-window range), and
  `count(cl, indexName, query[, tx])` — the record-level count of snapshot-visible matches
  without building the result collection (ignores the shaping, SQL `COUNT` semantics; `count(q)`
  is exactly `load(q).size()` on the same snapshot). Keyset/cursor pagination is the filed
  follow-up for the `OFFSET` re-walk cost.
- **EXPLAIN** ([25](docs/tasks/2.1.0/25-explain-query-plan.md)): `storage.explain(Class,
  indexName, query)` — the read-only query plan (`XdStorageQueryPlan`): INDEX_SEEK /
  INDEX_RANGE_WALK / SCAN, the id-index type, the range bound shape, the post-filter
  criteria, the degradation reason (including the FP-1a "range on a Hash id index → scan"
  fallback) and the `ORDER BY`/`LIMIT`/`OFFSET` shaping summary. Extracted from the routing
  classification the execution lives by — the plan cannot disagree with it — and it never
  executes anything. GUIDE § Query plans; DECISION-GUIDE § the runtime face of the cost model.
- **FP-11** — decision-guide page (comparison vs MapDB/Xodus/RocksDB/Chronicle/EclipseStore)
  + runnable example app.
- **AUTOSAVE** ([22](docs/tasks/2.1.0/22-autosave-cascade-children.md)) — the
  `@XdStorageAutoSave` field annotation: cascade-save of independent-policy children on
  parent save/update, in the caller's transaction with triggers/observers/id-generation and
  rollback identical to explicit saves; cycles terminate, duplicates dedup, removed children
  stay persisted (loudly documented — cascade-delete is out of scope). Shapes: collections,
  single references, arrays, and MAP values with an id-field (keys are never candidates).
  GUIDE § Cascade save and § Foreign keys + EMBEDDING carry the contract;
  `XdStorageAutoSaveCascadeTest` pins it.
- **Referential integrity — foreign keys** ([42](docs/tasks/2.1.0/42-foreign-keys.md)) —
  relations between a parent and its independent-policy children (annotated with
  `@XdStorageAutoSave` on the parent's field and `@XdStorageParentObject` on the child's
  back-reference) are backed by persistent front/reverse B+tree indexes: the entries are
  created and cleaned in the same transaction as the objects, the trees double as the
  parent↔child navigation index, and **deleting a child that is still referenced by
  its parent (or a parent that still has live children) is refused loudly at commit**.
  Flag: `enableForeignKeys` (default ON). GUIDE § Foreign keys carries the full rules and
  the measured cost; BENCHMARKS § FK A/B the canonical numbers.
- **BENCH** — the benchmark estate unified: `scripts/run-benchmarks.sh [quick|full|sweep]`
  is the one entry point under a documented conditions protocol (quiet-load gate, WARN-console
  benchmark logging config, YAML format, JSON results + run manifests); the CORE scenarios
  (read-only tx, journal on/off, durableWrites on/off, commit-batch scaling, FP-1 shapes)
  live in `XdStorageCanonicalBenchmark` with machine-readable `@Param` dimensions;
  `docs/BENCHMARKS.md` is the single canonical baseline (generated, traceable to archived
  result sets); the nightly smoke asserts the final scenario list.
- **D-1/D-4** — JaCoCo coverage report in CI; JMH regression alert in the nightly.
- **Track T** (MASTER-PLAN) — formal test groups: every stress/integration class carries
  JUnit 5 `@Tag("stress")`/`@Tag("integration")`; the `release` profile excludes by tag
  (filename patterns stay as a backstop until 3.0), while `quick-gate` keeps its documented-flaky
  name list (the two structural tags cannot express it). The naming↔tag pairing is enforced in
  both directions by `XdStorageTestGroupingContractTest` in the default run. Vintage cleanup:
  JUnit 4 left the build — and the audit found the two JUnit-4-only serialization tests had been
  silently dead all along (no vintage engine was ever configured); migrated to JUnit 5, they now
  run in every suite. Gap-fill suites (T-3a) landed for the dark packages — `structure`
  8%→54%, `trigger` 73%→88%, `index` 75%→100% — pinning the trigger contour, Hi-Lo/UUID id
  contracts, index-cache and object-model semantics, and discovering four engine defects on
  the way (structure-resource journal replay NPE after reopen, id-addressability after a
  structure update, auto-generated ids unreachable by id after reopen, and the
  `UUID_GENERATOR` field property never auto-generating) — each documented with an
  acceptance-grade disabled test in docs/tasks/2.1.0/06; the facade happy/failure-path
  audit (T-3b) landed as `XdStorageFacadeMethodAuditTest` (11 gap-fill tests; its finding —
  `load(Class,id)` returns null on a missing id while load-by-reference throws — is the
  documented contract).

### Changed

- **Documented API surface** ([28](docs/tasks/2.1.0/28-javadoc-coverage.md)) — every public
  method of the main tree carries javadoc (full contract prose on the published API tier —
  facade, config, annotations, query model, exceptions/metrics/id-generation SPI, with the
  EMBEDDING.md gotchas on the methods themselves; class-level + contract one-liners on the
  machinery tier), the Tier-1 packages gained `package-info` overviews, and the release
  profile's long-deferred doclint promise (RELEASE-PLAN §3) is closed: the release build now
  runs `doclint=missing,syntax` with `failOnWarnings=true`, so the published javadoc jar can
  never silently regress. Coverage gate: `mvn javadoc:javadoc -Ddoclint=missing` green.

- **Journal file naming and placement** ([24](docs/tasks/2.1.0/24-journal-file-naming.md)) —
  journal files live in the `journal` SUBDIRECTORY of their resource's directory
  (`data/<Class>/journal/<base-without-ext>-journal.yaml`, `structure/journal/...`), named with
  a suffix on the extension-less base — a class folder holds only bases, and no stacked
  extensions (`Order-1.yaml` no longer carries an `Order-1.yaml.journal.yaml` twin). Older
  namings are migrated automatically: a 2.0.0-era stacked-named journal (and the pre-subdirectory
  sibling spelling) is replayed and moved into the journal directory at its replay
  (un-checkpointed commits survive the upgrade); when several namings are present the
  journal-directory file wins and the superseded twins are removed (WARN). A journal of a
  resource the session never touches keeps its old name until that resource's next prepare —
  content-safe either way. The journal content stays YAML; journaling itself remains
  YAML-format only (an XML storage refuses `journalWrites` loudly at open, unchanged).

- **Index journaling (W-4a/c)** — the hash id-index commits are journaled
  ([32](docs/tasks/2.1.0/32-w4-write-path-implementation.md), design in
  [W4-DESIGN.md](docs/W4-DESIGN.md)): the O(N)-per-commit full-file index rewrite becomes an
  O(delta) append; measured small-write cost at 50 000 objects: **64.9 → 0.13 ms/tx** (the
  write-scaling wall across the design point is gone). A clean shutdown now checkpoints
  every journal — after a clean close, a power loss loses nothing even with
  `durableWrites=false`. Flag: `indexJournalWrites` (default ON after a green full-suite
  soak; `false` restores the legacy full-file rewrite).
- **The README is a landing page** (landed,
  [29](docs/tasks/2.1.0/29-readme-landing-restructure.md)) — the first screen answers
  what-it-is / when-to-take-it / when-not, carries a worked example (entity sketch + save +
  query + reopen) copied line-for-line from the green quickstart test, the comparison mini-table
  vs the close relatives, and the honest write-throughput limitation **with the fix plan**
  ([docs/W4-DESIGN.md](docs/W4-DESIGN.md)). The deep reference moved
  section-for-section to [docs/GUIDE.md](docs/GUIDE.md) (the README halved); the quickstart
  example's stale known-defect note (BUG-E, fixed in this train) was corrected.
- **Benchmarks in context** (landed,
  [30](docs/tasks/2.1.0/30-benchmarks-context.md)) — BENCHMARKS.md § In context states what the
  numbers measure (ACID multi-resource commits of typed graphs, not raw KV ops) and frames the
  canonical baseline against the field with sourced, dated public figures (RocksDB, Chronicle Map,
  LMDB, MapDB — marketing claims labeled); every canonical scenario family gained an "in real
  terms" reading; the write numbers carry the same limitation + fix-plan link as the README.
  The write-path analysis and the W-4 design round landed on the same train
  ([31](docs/tasks/2.1.0/31-write-path-analysis-design.md),
  [32](docs/tasks/2.1.0/32-w4-write-path-implementation.md) — [W4-DESIGN.md](docs/W4-DESIGN.md)).

- **W-4b — search-key logical-op journal** ([49](docs/tasks/2.1.0/49-w4b-search-key-journal.md)):
  search-index key-tree mutations append O(delta) redo per search tree instead of rewriting
  the touched node files — the last O(N) wall in the indexed write path. Flag:
  `searchIndexJournalWrites` (default ON after the soak).
- **W-4c — checkpoint on clean shutdown** ([48](docs/tasks/2.1.0/48-checkpoint-on-shutdown.md)):
  every commit journal is checkpointed at `shutdown()` — after a clean close, a power loss
  loses nothing even with `durableWrites=false`.
- **Track L (MASTER-PLAN) — logging through the SLF4J API; log4j2 test-only.** The library
  now logs via `slf4j-api` — the only logging artifact in its dependency tree (65 KB, no
  dependencies of its own; previously `log4j-core` 2.23.1 (~1.8 MB + CVE surface) arrived
  transitively with a bundled config that logged the engine's per-operation INFO to the
  consumer's stdout). **`log4j-core` is no longer provided** — the backend is the
  application's choice: logback, log4j2 via `log4j-slf4j2-impl`, `slf4j-jdk14`, or none.
  Example consumer configurations (log4j2 + logback, quiet and diagnostic-verbose) ship in
  `docs/examples/`; see EMBEDDING.md § Observability. **Migration:** code that relied on
  log4j2 arriving transitively declares a backend explicitly; an application without any
  backend sees at most SLF4J's single documented "no provider" notice.
- **Quiet-by-default level discipline** (Track L-2). The engine's per-operation INFO lines
  (save/delete/commit/rollback/prepare traces) are demoted to DEBUG; INFO remains for rare
  lifecycle events (storage open/close, journal checkpoint). This addresses the log spam
  observed around the 2.0.0 release: a consumer with a backend now sees lifecycle events
  only, and a consumer without one sees nothing.
- **Nested transactions are NOT RECOMMENDED for load-heavy use** (decision 2026-10-26):
  the nested commit/rollback machinery is a known critical-under-load path; flat
  transactions with explicit compensating code are the recommended pattern. The rework is
  scheduled for 2.2.0 (optional task). The isolation stress test is excluded from the
  gate/release profiles (it stays in the nightly and the RC protocol).
- **The facade load stress test moves to 2.2.0 for investigation**: the failure-cause
  analysis and the write-performance improvement paths for the facade flow are planned
  (docs/tasks/2.2.0/13); the test is excluded from the gate/release profiles.
- **The canonical baseline refreshed for the 2.1.0 feature set** ([43](docs/tasks/2.1.0/43-benchmark-refresh.md)):
  the 2026-11-21 run (23 trials, machine profile pinned, `enableForeignKeys` disclosed) is the
  new reference in BENCHMARKS.md. The honest delta vs 2026-10-24 separates machine drift from
  code (the old revision re-run today): the reads are flat; **the one real code regression is
  `saveAndDeleteIndexedShipWithCrew` at −24%** — the price of the correctness wave (the
  overlay staging + the search-key journal), tracked in the W-4 ledger with the rest of the
  post-release performance backlog.
- **The foreign-key cost is measured canonically** ([50](docs/tasks/2.1.0/50-fk-cost-benchmark.md)):
  the JMH A/B (`enableForeignKeys` on/off + an un-annotated control) is published in
  BENCHMARKS.md § FK A/B and DECISION-GUIDE — the honest verdict is that the FK **write**
  shapes run outside the engine's ≤2× constant-factor band (cascade-save 23–160×, child delete
  12–73×, parent update 1.4–12.5×, scaling linearly with the FK-tree size), while the **read**
  path is free (~1.0×). Enable foreign keys for integrity-critical graphs, not for hot write
  loops; the O(relations) scan behind the linear scaling is the first W-4c post-release item.
- **JDK 21 joins the CI matrix** ([27](docs/tasks/2.1.0/27-jdk21-ci-matrix.md)): the suite is
  verified on Temurin 17 (the gate) and 21 (a parallel CI leg: quick-gate on push/PR, the full
  suite nightly); the shipped bytecode stays Java 8 (`release=8`). See EMBEDDING § JDK matrix.

### Fixed

- **Concurrent writers no longer collapse on the shared index trees** (the TREEOVERLAY
  program, [53](docs/tasks/2.1.0/53-tree-overlay.md)): B+tree key mutations (id-indexes,
  FK trees) stage into the writing transaction's overlay — invisible to others until the
  commit — and apply as a batch at the commit; the commit acquisition order across the
  resource bands is **total** (the tie-break by the resource id), which removed the
  acquisition-deadlock family (the 2.0.x behavior: 10 concurrent writers decayed to
  ~1 tx/s through 12–20 s lock-timeout cycles). Measured (FKBENCH, task 50's conditions):
  10 writers **121–158 tx/s, p99 84–113 ms**, no t=1 regression; the whole stress family
  (MassFill purge audits, the B+tree stress, reopen-after-checkpoint) green ×3.
- **Commit-time conflict revalidation — the lost-update window is closed**
  ([53](docs/tasks/2.1.0/53-tree-overlay.md) LOSTUPD-2): first-committer-wins previously
  fired at stage time only, so a foreign commit landing inside the stage→commit window won
  silently. Phase 1 now re-validates the records the transaction read against the true
  last-commit timestamps: the loser fails **loudly** (the conflict names the record and the
  foreign commit) with the stored data intact — retry = a fresh read in a new transaction.
  Content-based semantics: staged-but-aborted foreign work causes no false conflicts
  (pinned by `XdStorageCommitTimeRevalidationTest`).
- **Mixed-type keys in one index tree no longer throw `ClassCastException`** (A2.3,
  [53](docs/tasks/2.1.0/53-tree-overlay.md)): id-index and FK trees compare keys tolerantly
  (numeric coercion both sides, string fallback) across find/descend/range — a tree holding
  both `Long` and `String` keys (e.g. an FK relation between a UUID-id parent and an
  auto-`Long`-id child) is usable. The typed **range queries keep a designed loudness**: a
  range bound whose type is foreign to the declared field type fails with a clear
  `XdStorageRuntimeException` at query construction (the accidental CCE it replaces was
  worse), and `IN` stays equals-membership. Pinned by `XdStorageMixedKeyFindTest`.


A defect discovered by a train's own work ships with the train that discovered it — the
reopen/defect family below was found and fixed on this train (BUG-B is the one carried
limitation; see Known issues at the end of this section):

- **BUG-A** ([15](docs/tasks/2.1.0/15-commit-journal-replay-npe.md)) — a structure update can
  no longer strand the folder: journal/data records embed `java.lang.Class` values that raw
  `Class.forName` could not resolve for application-loaded entity generations → null identity
  → a replay NPE that made the folder unopenable. Class resolution now goes through the
  metadata registry (any classloader), unresolvable journal upserts are skipped loudly
  (documented recovery), and the codegen shared loader bridges application code sources.
- **BUG-C** ([17](docs/tasks/2.1.0/17-auto-generated-id-reopen-addressability.md)) — numeric
  ids are addressable by id after a reopen: the hash id-index resource's `clInfo` describes
  the RECORD class (`XdStorageHashIndexRecord.objectId`, declared `Object`), so record keys
  loaded from YAML stayed raw strings and every numeric-id lookup missed after reopen
  (String ids masked it for years). Keys are now typed on cache fill; no migration needed
  (on-disk index records were always correct).
- **BUG-D** ([14](docs/tasks/2.1.0/14-uuid-generator-field-property.md)) — `UUID_GENERATOR`
  now generates: the property never activated auto-generation (all three gates covered: both
  id-index resources at save time + the writer's four write-out sites, unified into one
  helper); the mux validates `UUID_GENERATOR` to String ids loudly.
- **DUPKEYS** — MULTI B+trees (duplicate keys AND duplicate (key, record) pairs, the FK
  index trees) are correct: the targeted `delete(key, value)` no longer misses pairs lying
  left of an equal separator (the descent went right on equality; separators are
  non-strict bounds, so duplicates legally sit on both sides) — it searches every candidate
  subtree between the equal separators. Contract: `delete(key, value)` removes ONE
  occurrence (the tree is a multiset); `delete(key)` removes the earliest entry of the key;
  `update(key, value)` replaces every record of the key; duplicates keep insertion order;
  record matching is `equals`, then `Comparable` (incomparable types no longer throw a CCE).
  Thread safety: a MULTI-tree mutation is exclusive — it holds the tree write lock for the
  whole operation and first drains the leaf-chain walks in flight (readers stay parallel to
  each other), so the left-side borrow/merge can neither deadlock against a chain reader nor
  silently skip and leave an underfilled node. The full structural invariant set
  (occupancy, balance, separator bounds, parent pointers, leaf chain, no orphan node, no
  leaked lock) is verified after every operation by `BTreeDuplicatesPropertyTest` (both
  modes, t=2/3/5), `BTreeDuplicatesScenarioTest` and `BTreeDuplicatesConcurrencyStressTest`.
- **PWRLOSS** ([23](docs/tasks/2.1.0/23-power-loss-recovery.md)) — the power-loss story is
  now a verified matrix, a documented recipe, and an explicit default decision: every file
  class × truncation point × config ends in a clean open with a WARN or a loud sentinel
  refusal — **never a silently-partial open** (`XdStoragePowerLossRecoveryMatrixTest`); the
  after-a-power-loss recovery recipe lives in EMBEDDING.md. **The `durableWrites` default was
  re-decided and KEPT OFF**: the fsync write cost (measured ~8× at decision time; the
  canonical 2026-11-21 baseline measures ~95× — the non-fsync path got 24× faster since)
  would falsify the published throughput envelope; the recovery recipe + the one-line
  `durableWrites(true)` escalation cover the durability-sensitive embedder (EMBEDDING §
  After a power loss). Checkpoint-on-clean-shutdown shipped on this train as W-4c (Changed
  above).
- **BUG-E** ([18](docs/tasks/2.1.0/18-bug-search-index-key-types-after-reopen.md)) — FIXED:
  search-index key types are restored at reopen. After a close/open cycle, range, `EQUALS` and
  `count` on a **numeric** search index crashed (`Long` bound vs raw-`String` persisted keys and
  records inside the B+Tree node files). The fix walks each search tree ONCE per reopen
  (CAS-guarded, same lock discipline as the read walk) and restores the key values to the indexed
  class's primary index field type, the object pointers to the entity id-field type, and the index
  records' `primaryIndexValue`/id — the type authority is the declared field type (the BUG-C
  lesson, now a shared `XdStorageKeyTypeRestore` utility). No migration: on-disk keys were always
  written typed. String-keyed indexes were unaffected, which is why every earlier reopen
  regression missed it. Discovered by the FP-11 example app; its quickstart asserts range + count
  after the reopen again.

### Known issues (carried)

- **BUG-B** ([16](docs/tasks/2.1.0/16-id-addressability-after-structure-update.md)) — open,
  refined: the codegen wrapper identity is NAME-based, so two live generations of one entity
  FQCN collide on one wrapper class. Filed with the exact reproduction; generation-aware
  wrapper identity is the design decision to make. Everything else from the defect train
  (BUG-A/C/D/E, DUPKEYS, PWRLOSS) is fixed in this release.

## [2.0.0] — 2026-10-21 (first public release)

The 2.0 engineering campaign is journaled in detail in
[OPTIMIZATION-PLAN.md](docs/OPTIMIZATION-PLAN.md) (engine, R-1..R-59),
[SRE-OPTIMIZATION-PLAN.md](docs/SRE-OPTIMIZATION-PLAN.md) (S-1..S-5, W-1..W-3, O/C phases),
[CODEGEN-OPTIMIZATION-PLAN.md](docs/CODEGEN-OPTIMIZATION-PLAN.md) and
[PERFORMANCE-RECOMMENDATIONS.md](docs/PERFORMANCE-RECOMMENDATIONS.md). Highlights below.

### Breaking

- **Package and Maven coordinates renamed**: the library moves from the never-published
  `org.flib.xdstorage` namespace to **`io.github.xdstorage`** — groupId `io.github.xdstorage`,
  artifactId `xdstorage`, root Java package `io.github.xdstorage` (subpackages keep their
  relative structure). Every `import org.flib.xdstorage.…` becomes
  `import io.github.xdstorage.…`; nothing was ever published under the old coordinates,
  so there is no dependency-graph migration — only import renames.
- **`FilesFormat.JSON` removed** (A2/F-5): the constant silently wrote XML content into
  `.json`-named files (the "default" factory is an XML implementation). Code passing
  `FilesFormat.JSON` must switch to `FilesFormat.XML` or `FilesFormat.YAML`. A 1.x folder
  written through the old constant is an XML-format folder with a `.json` extension —
  migrate by renaming `*.json` → `*.xml` inside `data/` and `structure/` and reopening with
  `FilesFormat.XML` (recipe in [EMBEDDING.md](docs/EMBEDDING.md) § Compatibility).
- **YAML readers reject files without the EOF sentinel by default** (S-1). 1.x-era YAML
  folders open with `XdStorageConfig.builder().acceptMissingEofSentinel(true)`; 2.0-created
  folders are unaffected.
- **Version scheme**: pom version moves `2.0` → `2.0.0` (strict semver from now on).

### Added

- **Commit journal** (W-1..W-3): append-only per-transaction journal with O(delta) appends,
  prepare-replay, size-threshold checkpoint, failed-commit compensation; per-transaction
  materialization budget (`txMaterializedObjectsCap`) with loud failure instead of unbounded
  heap; `journalWrites` defaults ON (DATA classes; B+tree infrastructure excluded after a
  soak-caught reopen divergence fix).
- **Operability** (S-phase): exclusive cross-process folder lock (`.xdstorage.lock`); JVM
  shutdown hook + daemon-worker option; loud `XdStorageRuntimeException` after `shutdown()`;
  stale `.tmp` sweep at open with a file-count summary; `XdStorageMetrics`
  (`storage.getMetrics()` / JVM-wide `engineEvents()`).
- **API freeze** (A2): on-disk format marker `.xdstorage.meta` + startup INFO
  ("opened/created storage X, format vN"), legacy-1.x detection and loud rejection of
  folders written by a newer library; `getVersion()` on the public storage interface and
  `XdStorageProvider.getVersion()` (semver from the Maven-filtered version resource).
- **Release engineering** (E-1..E-4, D-3): Apache-2.0 LICENSE, this CHANGELOG, semver
  `2.0.0`, pom metadata (description/url/scm/licenses), `release` profile (sources +
  javadoc jars, GPG signing, Central Portal publish, release-only enforcer rules),
  reproducible builds (`project.build.outputTimestamp`), tag-triggered release workflow,
  Dependabot.

### Changed

- Engine defaults from the configuration study: worker threads **t=64**, fragment size
  **frag=128** ([PERFORMANCE-RECOMMENDATIONS.md](docs/PERFORMANCE-RECOMMENDATIONS.md)).
- Wrapper codegen hardened to the JavaBean specification
  ([CODEGEN-OPTIMIZATION-PLAN.md](docs/CODEGEN-OPTIMIZATION-PLAN.md)).
- Test suite: ~706 tests (up from ~120 at the 2.0 campaign start), stress/JMH families
  included; benchmarks and baselines in [BENCHMARKS.md](docs/BENCHMARKS.md); flake ledger in
  [FLAKY.md](docs/FLAKY.md) (one documented, tolerated full-suite-load row → Track B3).

### Fixed

- **Track A flake families** (R-54..R-58): facade-storm, GraphPhantom and Xml purge flakes
  resolved — 10/10 all-stress gate, full suite green; 13 regression pins (`333e864`).
- **StackOverflowError on deep B+trees** (R-59): iterative leaf-chain walk in
  `node.read/find`.
- **Reopen divergence** (R-53): commit journal now covers DATA classes only (B+tree
  infrastructure exclusion), caught by a 24h soak.
- Full engine journal: R-1..R-59 in [OPTIMIZATION-PLAN.md](docs/OPTIMIZATION-PLAN.md).
  (The reopen defect family BUG-A/C/D/E and the FK/defect-train fixes of 2.1.0 live in the
  [Unreleased] section above — they were discovered and shipped after this release.)

## [1.x] — historical (never tagged)

Pre-history: no tagged releases; on-disk format v1. Notable arcs from the git log: XML
storage core, B+tree secondary indexes and hardening, search engine, YAML format
introduction, wrapper codegen (simple/unmodifiable/observable), resource cache, MVCC
snapshot isolation, PostgreSQL support removed. 1.x folders open in 2.0 per the
compatibility matrix in [EMBEDDING.md](docs/EMBEDDING.md).

[2.0.0]: https://github.com/xdstorage/xdstorage/releases/tag/v2.0.0
