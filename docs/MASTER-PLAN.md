# XdStorage — Master Roadmap

Compiled 2026-10-19; updated 2026-10-20 (architect's evaluation integrated); updated
2026-10-21 post-release (Track T/L added; W-4/B3 moved explicitly to 2.2.0); **updated
2026-11-22: the 2.1.0 train is COMPLETE — all 54 work packages closed (33 BINFEAS: the
FP-9 gate GO + the realized experiment; 44 DOCSWRITER: the five-surface one-story pass;
the TREEOVERLAY/convoy wave, LOSTUPD-2, MIXEDKEY, WRAPLAYER, LAYOUTMIG, JRNLDATA, FKBENCH,
JDK21, BENCHREFRESH all landed — item 20); the 2.2.0 intake amended — tasks 21/22 pulled
in (the FK O(relations) write path, the lazy read-snapshot), B3 RESOLVED by the total
acquisition order**. Detailed journals stay authoritative per scope:

| Plan | Scope | State |
|---|---|---|
| [OPTIMIZATION-PLAN.md](OPTIMIZATION-PLAN.md) | Engine campaign journal R-1..R-59 | Phases A/B/D done; **Track A family FIXED (R-54..R-58)**; R-59 SOE fix |
| [SRE-OPTIMIZATION-PLAN.md](SRE-OPTIMIZATION-PLAN.md) | Whole-project SRE plan (Phases 0/S/W/O/C) | 0, S, O, C **DONE**; W-1..W-3 **DONE**; **W-4a/b/c DONE (2.1.0); W-4d = 2.2.0 task 05** |
| [WRITE-PATH-DESIGN.md](WRITE-PATH-DESIGN.md) | Phase W design (journal + checkpoint) | W-1..W-3 implemented; W-4 = the deferred design round |
| [CODEGEN-OPTIMIZATION-PLAN.md](CODEGEN-OPTIMIZATION-PLAN.md) | Wrapper codegen | Items 1/2/5 applied; **items 3/4 superseded by FP-3 (AOT)** |
| [PERFORMANCE-RECOMMENDATIONS.md](PERFORMANCE-RECOMMENDATIONS.md) | Config study (2nd ed, post-R-53) | Recommendations APPLIED (t=64, frag=128); §5.3 **CLOSED** (2.1.0 B2: verified 0.6–0.7 ms/op flat, BENCHMARKS addendum) |
| [ARCHITECT-EVALUATION.md](ARCHITECT-EVALUATION.md) | Outside-architect review, competitive comparison, FP-0..FP-12 feature plan | Adopted; FP-0..FP-12 tracked here as Track F |
| [ANALYSIS-ARCHITECTURE.md](ANALYSIS-ARCHITECTURE.md) | The full engine specification (architecture, modules, transaction model, indexes, serialization, FK, migration, codegen, cache) | Current; maintained alongside the code |
| [docs/PRODUCT_REVIEW.md](PRODUCT_REVIEW.md) | External product review (the evaluator's perspective: positives, gaps, the agent-integration direction, adoption criteria) | Adopted: tasks 15-18 compiled from its §4 asks |
| [BENCHMARKS.md](BENCHMARKS.md) / [FLAKY.md](FLAKY.md) | Baselines / flake ledger | Current; **one open row** (BTreeStress contention → B3) |

**Verified state (2026-10-21, post-release):** **2.0.0 is PUBLISHED on Maven Central**
(tag `v2.0.0` = `6224066`; namespace `io.github.xdstorage`; Central Portal pipeline after two
validation fixes — central-publishing-maven-plugin 0.12.0 for the Maven 3.10 bundle bug,
`<developers>` section for pom metadata). Full suite 714 green (release gate 677 via
`-Prelease`); CI gate + nightly live; release workflow proven end-to-end (tag → smoke →
non-stress suite + sign → Central → GitHub Release); journal writes default ON (W-3); engine
defaults t=64 / frag=128. The facade-storm, GraphPhantom, and Xml purge flake families
are **FIXED** (10/10 all-stress gate); the single open flake row is the BTreeStress
contention family (Track B3, full-suite-load only).

---

## 1. Frozen / parked items — inventory and thaw conditions

Everything below is deliberately NOT being worked on; each entry records why it is frozen and
what would justify thawing it. Do not re-attempt these blind — the evidence is in the linked
R-entries.

| # | Item | Why frozen | Thaw condition |
|---|---|---|---|
| F-1 | **Phase 3b structural MVCC of B+Tree topology** (+ the frozen cloner pass-through, R-19/R-20) | Canonical-registry compromise landed; topology versioning is the largest structural change in the engine | Only if W-4 (Step 2) evidence proves topology tears remain |
| F-2 | **RW-lock redesign** (R-26/R-28/R-29 wait-rescheduling variants) | 3 variants rejected with evidence; in-source comments forbid blind re-attempts | Contention-removal design (B3), never wait-rescheduling |
| F-3 | **Record-cache eviction** (the original W-2 half) | Eviction without disk-anchored scans silently drops rows from scans / breaks snapshot prev-versions | W-4 design round (Step 2) ships disk-anchored scans first |
| F-4 | **D-3 batching** | Superseded by Phase W; fallback design recorded in WRITE-PATH-DESIGN.md §3 | Only if the journal approach stalls |
| F-5 | ~~**JSON format**~~ — **DECIDED at Step 0 (2026-10-20): the enum constant is REMOVED in 2.0.0** — it silently wrote XML content into `.json` files; real JSON support, if demand appears, is a Step-4 feature (SPI implementation, no enum lie) | Closed | Implement JSON as a real format on demand (Step 4); a 1.x `.json` folder is an XML folder — migration recipe in EMBEDDING.md |
| F-6 | **Test layout rename** (`src/main/test`) | Churn without operational payoff | Never (documented constraint) |
| F-7 | **JMX/Micrometer adapter** | Deliberately embedder-side (O-phase verdict) | FP-8 (`xdstorage-micrometer` module) — build it only on a concrete demand signal (Step 4) |
| F-8 | **R-35/R-33 second leak path** (outside B+Tree retry loops) | Unidentified; watchdog + correlation toolkit ready | If leak events re-appear in nightly logs or B3 dumps |

**Superseded (was planned, no longer the plan):** Track C codegen items — C1 batch-compile and
C2 wrapper persistence are **superseded by FP-3 AOT wrapper generation** (build-time generation
removes cold-start javac cost and the JRE/`./gencode` landmines in one move; runtime codegen
stays as fallback). See ARCHITECT-EVALUATION.md FP-3.

---

## 2. The working plan — merged 5-step roadmap

Track statuses: **A done** (A1 fixed R-54..R-58/R-59; A2 folded into Step 0), **B split**
(B2 → the 2.1.0 train; B1/B3 → 2.2.0), **C superseded** (FP-3, Step 3), **D absorbed**
(D-1 = T-1, D-4 in the 2.1.0 train, rest done), **E done** (Step 0), **F open** (the FP
feature plan, Steps 1–4), **L and T new** (the 2.1.0 hardening tracks, defined in Step 1).

### Step 0 — Make it adoptable at all → **tag 2.0.0** (≈1–2 sessions) — **DONE: 2.0.0 PUBLISHED 2026-10-21**

The release train: Track E in full + the A2 API-freeze decisions. Nothing here waited on
anything (Track A was done).

**CLOSED 2026-10-21.** A2 DONE (JSON removed, format marker `.xdstorage.meta` + startup INFO +
loud newer-format rejection, `getVersion()`), E-1 DONE (LICENSE Apache-2.0, CHANGELOG.md,
semver, pom metadata incl. `<developers>` — Central requires it), E-2 DONE (`release` profile:
sources/javadoc/GPG/enforcer/reproducible builds + non-stress suite), E-3 DONE (Central Portal,
namespace `io.github.xdstorage` verified, plugin 0.12.0), E-4 DONE (`release.yml` proven
end-to-end), E-5 executed (RC protocol per RELEASE-PLAN.md), E-6 DONE (EMBEDDING.md §
Compatibility), D-3 DONE (Dependabot). Release lessons recorded in RELEASE-PLAN.md §3.

| # | Item | What |
|---|---|---|
| A2 | **API surface freeze** — **DONE** | JSON enum constant REMOVED (F-5 decision above); on-disk format version marker `.xdstorage.meta` + startup INFO ("created/opened storage X, format vN") + newer-format loud rejection; compatibility matrix in EMBEDDING.md; `getVersion()` on `IXdFileStorage` + `XdStorageProvider.getVersion()` |
| E-1 | **Project meta** — **DONE** | LICENSE (**Apache-2.0**), CHANGELOG.md backfilled from the R-journals, pom `<name>/<description>/<url>/<scm>/<licenses>`; semver `2.0.0` |
| E-2 | **Build-for-release hygiene** — **DONE** | `release` profile: source + javadoc plugins (doclint off at 2.0.0, tighten later), GPG signing, non-stress surefire (see E-4), enforcer (JDK 17+/Maven 3.6.3+ always-on; no-SNAPSHOT-deps + no-duplicate-versions in release), reproducible builds (`project.build.outputTimestamp`) |
| E-3 | **Distribution channel** — scaffolding DONE, activation open | Maven Central via the Central Portal (`central-publishing-maven-plugin`, server id `central`; supersedes the legacy OSSRH plan for a first-time publisher); needs namespace verification + CI secrets |
| E-4 | **Release workflow** — **DONE** | `.github/workflows/release.yml`: tag push `v*` → version/tag sanity → JMH smoke row assertion → signed `mvn clean deploy -Prelease` (build + **non-stress suite** — the `release` profile excludes `*StressTest` + `XmlDataStorageMultithreadsTest` + the `integration` package; the FULL suite runs in the nightly and the RC protocol before the tag is cut, RELEASE-PLAN.md § Quality bar) → GitHub Release with the CHANGELOG section + artifacts |
| E-5 | **Pre-release verification protocol** — documented, executes at RC | See RELEASE-PLAN.md §1/§2: suite ×3 green; kill-9 + torn-journal recovery vs the RC; `durableWrites=true` spot benchmark; 1.x migration walkthrough (`acceptMissingEofSentinel` + the JSON recipe); JMH vs BENCHMARKS.md baselines; fresh-clone README walkthrough |
| E-6 | **Compatibility & support statement** — **DONE** | EMBEDDING.md § Compatibility: JDK matrix, on-disk format compatibility matrix, JSON migration recipe, semver/deprecation policy, flake-ledger + benchmark-regression disclosure rules |
| D-3 | Dependabot — **DONE** | `.github/dependabot.yml` (maven + github-actions, weekly); log4j 2.11→2.23 history is the argument; pin, don't chase |
| D-5 | ~~Commit the staged `XdStore.docx`~~ | **DONE** (historical docs committed) |

*Exit criterion (from the evaluation): a stranger can add the dependency from Central and read
a license.*

### Step 1 — the 2.1.0 train: post-release hardening (corrected/extended 2026-10-21)

The first train after going public. The release itself surfaced two work streams the old plan
underweighted — **the released jar spams host stdout** (the bundled log4j2.xml) and **the test
estate grew to 714 tests with no grouping and no coverage map** — so the train now carries four
tracks: the original quick wins (B2/FP-1/FP-11/D-1/D-4) plus the new T and L tracks.

#### Track L — logging: SLF4J API in the jar, log4j2 test-only, quiet-by-default (P0, first — every later run benefits) ≈1–1.5 sessions

> **Work packages:** the 2.1.0 train is broken into senior-developer-sized GitHub-ready tasks
> in [tasks/2.1.0/](tasks/2.1.0/README.md); issue lifecycle is automated — the issues-sync
> workflow (`.github/workflows/issues-sync.yml`, every push to `main`) runs
> `scripts/sync-issues.sh` to create new tasks' issues and close the ones a developer has
> marked `status: done` in the task file; the track sections below remain the decision record.

The released 2.0.0 jar has **log4j-core as a required dependency** (~1.8 MB of logging backend
nobody asked for — and a CVE surface the consumer didn't sign up for) plus a bundled
`log4j2.xml` whose **Root-level INFO Console appender** puts the engine's per-operation log
(saves/commits/rollbacks — the R-24 "show-the-project log") on the consumer's own stdout: the
log-spam problem seen during the release. **Decision (2026-10-21, user's call): the library
logs through the SLF4J API — `slf4j-api` is the only main-scope logging dependency, and
log4j2 (core + binding + config) becomes test-scope only.**

**The transitive-dependency question — decision record (alternatives with reasons):**

| Option | Verdict | Reason |
|---|---|---|
| (a) **`slf4j-api` as a normal compile dependency — CHOSEN** (the industry default: Hibernate, HikariCP) | **CHOSEN** | The standard, well-understood library contract: one 65 KB artifact with no dependencies of its own — the honest minimum of the API approach; the backend choice (logback / log4j2 / JUL / none) stays entirely with the consumer |
| (b) `slf4j-api` marked `<optional>true</optional>` | rejected | Clean tree, but a consumer who forgets to declare it gets a bare `NoClassDefFoundError` at the first log call — a 65 KB saving traded for a support channel |
| (c) Internal zero-dependency logging facade with runtime detection (the Netty `InternalLoggerFactory` pattern) | rejected | ~150 lines of reflective adapter + hand-rolled JUL `{}` formatting re-implement a solved problem — a permanent maintenance and correctness surface (plus SLF4J-present-without-provider edge cases) to save 65 KB. Dependency-count purism loses to honest simplicity here |

| # | Item | What |
|---|---|---|
| L-0 | **Migrate main sources to the SLF4J API** (the train's literal first task) | Replace `org.apache.logging.log4j.Logger`/`LogManager` with `org.slf4j.Logger`/`LoggerFactory` across all main sources (measured: 39 files / ~40 logger sites, fields uniformly `log`, 141 call sites, no `trace`; verified mechanical — no log4j2-specific API anywhere in main: no ThreadContext, Markers, FluentLogger, Supplier overloads, `throwing`/`catching`; both APIs share the `{}` placeholder syntax, so log lines stay verbatim). Pom: **`slf4j-api` 2.0.x (Java 8 bytecode) becomes the ONLY main-scope logging dependency**; `log4j-core` leaves main scope |
| L-1 | **No config in the jar; log4j2 = test scope** | `log4j2.xml` moves out of `src/main/resources`; `log4j-core` + version-matched `log4j-slf4j2-impl` (2.23.1) become TEST dependencies (the suite keeps log4j2, its file-sink config lives under `src/main/test/resources` for CI diagnosability); a small test asserts main-scope artifacts carry no binding. `docs/examples/`: quiet + diagnostic-verbose consumer configs (log4j2 and logback flavors, with a note that any SLF4J backend works). EMBEDDING.md § Observability rewritten for the new contract |
| L-2 | **Quiet-by-default level discipline** | Demote the per-operation INFO flow to DEBUG (save/delete/commit/rollback/prepare traces, commit/rollback summaries, BTree root-creation); keep INFO for rare lifecycle only (storage open/close, journal checkpoint). The verbose operation trace remains one config away (L-1's diagnostic examples). This revises the R-24 "show-the-project log" decision — right for a debugging campaign, wrong as a shipped library default |
| L-3 | **Guards** | enforcer `bannedDependencies` (release profile): org.apache.logging.log4j and every SLF4J binding (log4j-slf4j2-impl, logback, slf4j-simple, slf4j-jdk14, slf4j-nop) **banned from main scope permanently** — the jar can never again ship a backend; the jar contains no `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`; CI/nightly keep their file sink; the `-Prelease` deploy build shows no console spam |

Acceptance: `mvn dependency:tree` on a fresh consumer pom shows **exactly one logging
artifact** (slf4j-api, 65 KB, no deps of its own); a consumer without any backend sees at
most SLF4J's single documented "no provider" notice — never engine INFO lines; a consumer
with any backend gets everything routed through it (suite-verified); EMBEDDING.md documents
the contract change. **User-facing behavior change — CHANGELOG "Changed", including the
dependency note (log4j-core no longer provided transitively; declare your own backend — or
none).**

#### Track T — test coverage & grouping (P0/P1) ≈2–3 sessions

Ground truth (2026-10-21): 160 test classes / 714 tests; **zero `@Tag`/`@Category` usage** —
all grouping is filename-based (`**/*StressTest.java`, `XmlDataStorageMultithreadsTest`,
`integration/**`), duplicated across the quick-gate and release profiles; the suite is JUnit 5
(154 classes; 2 JUnit-4-only + 1 mixed); the `/performance` package mixes stress families,
real unit tests, diagnostics, and benchmarks; packages `operation`(0), `updaters`(0),
`structure`(2), `trigger`(1), `observing`(2), `lock`(1), `object`(3), `index`(2) are
under-covered; `/temp` holds litter (`SpecificTest` + entities).

| # | Item | What |
|---|---|---|
| T-1 | **Coverage baseline** (the D-1 instrument, promoted to a track) | JaCoCo in CI, report published as a build artifact; produce the first coverage map and record it (docs or the CI artifact) — **measure and publish, never a percentage gate** (the honest-metrics culture applies to coverage too) |
| T-2 | **Formal test groups** | JUnit 5 `@Tag("stress")` / `@Tag("integration")` as the single source of grouping; migrate the 3 legacy-vintage classes (tiny); quick-gate and release profiles switch from filename excludes to tag excludes (filename patterns stay as a backstop); **a lint test enforces the pairing** (a class named `*StressTest` without the tag — or vice versa — fails the build) so a misnamed stress test can never silently join the release gate; the contract is codified in FLAKY.md + RELEASE-PLAN § Quality bar |
| T-3 | **Gap-filling, prioritized by public surface** | (a) suites for the under-covered packages above (`operation`, `updaters`, `structure`, `trigger`, `observing`, `lock`, `object`, `index`); (b) every `IXdFileStorage` facade method gets at least one happy-path + one failure-path test (the audit is part of the T-1 map); (c) FP-1's boundary-semantics suite (nulls, mixed types, empty/degenerate ranges) lands with the feature — one effort, two tracks; (d) litter audit: dissolve `/temp`, resolve the empty packages, decide `/performance`'s mixed content (stress classes tagged, benchmarks stay surefire-invisible by naming, diagnostics documented) |

Explicit non-goals: no coverage-percentage mandate; no directory moves (F-6 stays frozen) —
grouping is tags, not layout.

#### The engine/docs items (kept from the original Step 1, re-ordered)

1. **Track L first** (≈1–1.5 sess; **L-0 is the train's literal first task**) — the SLF4J
   API migration + quiet-by-default de-spams every subsequent CI/RC run and reduces the
   logging footprint to one 65 KB api artifact.
2. **T-1 + T-2** — instrumentation and grouping land together (JaCoCo + tags).
3. **BENCH — benchmark suite unification** (task 13, **ASAP: the next engine-lane item**):
   the benchmark estate is not unified — two harnesses (JMH classes vs the plain-Java sweep),
   two JDKs (17 developer-load TRACE-logging vs 21 quiet WARN — the PERF study admits the JMH
   numbers are logging-polluted), quick-mode rows mixed into full-mode tables, no single
   baseline, no run manifests. One canonical suite + one baseline (BENCHMARKS.md),
   machine-readable results with run manifests, and the missing full-picture scenarios
   (read-only tx, journal on/off on a data-heavy pattern, durableWrites cost, commit-batch
   scaling, cold start, N-scaling probe); the already-recorded B2/FP-1a/FP-1b addenda get
   absorbed into the canonical set. Task 11 asserts on this task's scenario list.
4. **B2 — read-only commit path** (PERF study §5.3, FP-4's quick win): skip 2PC record iteration
   for no-change transactions; ~25 ms → near-zero per read tx at N=5000 on the Hash path. **DONE
   (2.1.0): the no-change skip already shipped with the R-45/R-46 commit-path rework — verified
   0.63–0.74 ms/op flat up to N=40000 with zero write-outs; deliverable = the contract test suite
   + BENCHMARKS.md addendum, no further code change.**
5. **FP-1 — query power pack, phase 1** (`GT/GTE/LT/LTE/BETWEEN/IN` primaries, `ORDER BY`,
   `LIMIT/OFFSET`): the B+tree primary index is already ordered — range scans and ordered
   iteration are nearly free, and today their absence forces O(N) scans. Acceptance: JMH shows
   range queries scaling sub-linearly vs the scan they replace; the boundary-semantics suite
   (T-3c) is part of the feature. **PHASE 1 DONE (2.1.0):** the six range operators shipped —
   bounded ordered walk with early termination (no vertical descents for duplicate-bearing
   trees, R-46 invariant), selective ranges ~15× the scan (BENCHMARKS § FP-1a); boundary suite
   `XdStorageRangeSearchQueryTest` (8 tests). **PHASE 2 (FP-1b, task 10) DONE:** `ORDER BY`
   (primary, ASC/DESC — native key order on the walk, in-snapshot fallback sort on the scan),
   `LIMIT/OFFSET` (loud negative validation; LIMIT pushed down into the walk — paged range ~25%
   ahead of the unbounded same-window range, BENCHMARKS § FP-1b) and `count()` (record-level,
   snapshot-visible, shaping-agnostic). Composition suite `XdStorageQueryShapingTest` (12 tests,
   both id-index types).
6. **T-3 gap-filling** — guided by the T-1 map.
7. **BUG-A..E — the engine defects the train's own work discovered join the train** (tasks
   14–18; A–C migrated from the dissolved `docs/tasks/2.2.0/` directory on 2026-10-22, E
   discovered by the FP-11 example on 2026-10-23). The "2.1.0 carries no deep engine
   surgery" pinning is revised: **a defect discovered by a train's own test estate ships
   with the train that discovered it** — these are fixes with acceptance-grade
   `@Disabled` tests (un-disabling IS the acceptance), not feature surgery: BUG-A (15, P0)
   structure-journal replay NPE strands a folder after structure update + reopen — **FIXED
   `175b72a`**; BUG-C (17, P0) auto-generated ids unreachable by id after reopen —
   **FIXED `3575ffb`**; BUG-D (14, P2) the `UUID_GENERATOR` field property never
   auto-generated — **FIXED `d8d706f`**; BUG-B (16, P1) refined to name-based codegen
   wrapper identity collision across entity generations — **FIXED (2026-11-01): the
   codegen contract decision is GENERATION-AWARE WRAPPER IDENTITY** — generated wrapper
   names carry a qualifier derived from the entity's defining classloader when that
   loader is not the engine's own (engine-loaded classes keep the unqualified names,
   zero change to the common path), and the shared codegen loader delegates
   already-registered classes to the class-metadata registry instead of defining
   duplicate copies; the persisted structure record's identity is the class FQCN (not
   the Class object), so two live generations of one FQCN are ONE stored class whose
   evolution is detected (structure + index-structure ids converted to FQCN identity);
   **BUG-E (18, P0) — FIXED**: search-index key types are restored at reopen (the typed
   key/pointer/record restoration per the declared field type; `XdStorageKeyTypeRestore`).
   The defect train is fully closed (BUG-A..E all fixed with pinned tests).
8. **FP-11 — decision-guide page** (comparison vs MapDB/Xodus/RocksDB/Chronicle/EclipseStore +
   "when to choose XdStorage" + runnable example app): lands LAST so it describes the shipped
   2.1.0 (including range queries); fixes the README test-count staleness.
9. **D-4 — JMH regression alert** (nightly smoke persists per-scenario numbers; warn on >25%
   drop) — nightly-side, lands anywhere after task 13 fixes the scenario list.
10. **AUTOSAVE — `@XdStorageAutoSave` cascade-save for independent children** (task 22,
    maintainer's feature): field-level annotation on the parent; cascade-UPSERT of
    `StoreAsClassObjects`/`StoreAsSingleObject` children in the caller's transaction on
    parent save/update — orchestration over the existing per-object save path, no
    persisted-format change (train-safe). Mandatory mini-spike: hook point + the
    deferred-id/back-reference interplay (the BUG-C lesson). Cascade-delete explicitly
    deferred with its own design round.
11. **JRNLNAME** (task 24), **PWRLOSS** (task 23), **BACKUP** (19), **N50K** (20),
    **DOCSLINT** (21) — the incident- and guide-derived additions, tracked in the train
    README.
12. **MASTER-PLAN PULLS (2026-10-23 review — what the later steps can safely give this
    train):** **FP-2 `explain()`** (task 25) and **FP-5's verification core `verify()`**
    (task 26) pulled from Steps 4/3 — additive, read-only surfaces with no persisted-state
    change; **D-2's JDK-21 CI matrix** (task 27) — never train-tracked before. Everything
    else stays pinned: W-4 + B3 (Step 2 — deep surgery), FP-3 AOT + FP-5 `backupTo` +
    FP-6 CLI (Step 3 — multi-session), FP-7/8/9/10/12 (demand-driven / post-FP-3).
13. **FIELD-FEEDBACK AMENDMENT (2026-10-23, the evaluator verdict — docs-first, write-path
    honesty):** the P0 docs track **29 (README landing restructure) → 30 (benchmarks in
    context)** runs before new features; and **the W-4 DESIGN ROUND is pulled into this
    train** as task 31 (P1, ASAP) — profile the write path, ledger the bottlenecks by
    measured share, produce the solution design (index journaling + disk-anchored scans +
    checkpointing) and publish the fix plan in the README. **AMENDED
    same day (architect execution of task 31): the analysis and design are DONE
    ([W4-DESIGN.md](W4-DESIGN.md) — the wall is the hash id-index's O(N) full-file rewrite;
    BTree id-index is already near-delta at t=64), and the W-4a+c IMPLEMENTATION is pulled
    into this train as task 32 (P0) — W-4b follows W-4a's soak, W-4d stays decision-gated
    (2.2.0 if the RC timeline demands). The design-before-code rule is intact: task 32
    implements a finished design, not a sketch.
14. **NESTED-TRANSACTIONS DECISION (2026-10-26, maintainer):** nested transactions
    (`beginTransaction(parentTx, timeout)`) are **NOT RECOMMENDED for load-heavy use** —
    the machinery is critical under load (the R-40/R-41 history + the stress evidence).
    Documented in EMBEDDING/GUIDE/CHANGELOG; the isolation stress test is excluded from
    the gate/release profiles AND the nightly build; the **optional** rework task is 2.2.0 #12
    (fix or deprecate — decided inside that task).
15. **FACADE LOAD TEST DECISION (2026-10-26, maintainer):** the facade load stress test is
    excluded from the gate/release profiles AND the nightly build, and moves to 2.2.0 #13 —
    the failure-cause investigation + the facade-flow performance improvement plan (the B3
    striping and W-4b become inputs to that investigation).
16. **THE 2.2.0 TRAIN IS COMPILED** (docs/tasks/2.2.0/, from the maintainer's discovery
    document): two parallel road maps — A: Memory/Consistency/Durability (the consistency
    core on standard primitives with the disabled B+Tree tests enabled, the loud-failures
    inventory, property tests, W-4d memory, WAL hardening, the indexes/ layout, the CI
    stress-gate policy), B: platform (FP-3 JRE/AOT pulled forward per the maintainer's
    demand, server mode as a design-only go/no-go). Plus tasks 45/46 added to 2.1.0 (the
    commit-refusal diagnosis and the wrapper-observer leak — both defect-class).
17. **TASKS 47 + 38 + 07 LANDED** (parallel sessions, 2026-11): the record-identity core
    fixed (47, envelope residuals ledgered), the index-resource contracts aligned (38),
    the indexes/ subdirectory relocation shipped (07). W-4b (49) and W-4c (48) implemented
    per their design sections (docs/tasks/2.1.0/48, 49).
18. **PRODUCT_REVIEW PULL-FORWARD (2026-11-14, PRODUCT_REVIEW.md): the review's §4 asks
    pull FP-5's backup API and FP-6's tools CLI from Step 3 into the 2.2.0 train** (tasks
    15-18: backupTo API, the tools CLI, migration/schema-diff, the agent-integration
    surface). The SQL layer stays out of scope (§5.2 of the review itself names it a
    gap-and-decline); ANALYSIS-ARCHITECTURE.md (the full engine specification, 716 lines)
    joins the documentation index.

19. **LAYOUT MIGRATION (task 51, P0 BLOCKER) — DONE (2026-11-21):** the migration hardened
    on the real-2.0.0 golden fixture (generated by the released 2.0.0, relocatable): the
    bases move `data/<Class>/ → indexes/<Class>/` (fragments stay), the marker is stamped
    **v3 on success — the 2.0.0 downgrade refusal is REAL now**, crash-tolerant + LOUD
    failure policy, UTF-8 pinned across the persistence I/O, journals never touched by the
    layout migration (JRNLDATA, task 54, relocated them into `data/<Class>/journal/` — they
    are data carriers). EMBEDDING § Upgrade from 2.0.0 documents the procedure.

20. **THE 2026-11-21/22 WAVE + THE 2.2.0 INTAKE AMENDED (architect, 2026-11-22):** the
    TREEOVERLAY program landed (task 53: the B+tree per-tx overlay, the prevSnapshot role
    split, the **total acquisition order that destroyed the commit-lock convoy** — B3's
    root cause was an ACQUISITION DEADLOCK, not hold time; the stress family 7/7, the
    gate ×3 1344/0f/0e); **LOSTUPD-2 closed the lost-update window** (the commit-time
    version revalidation — the maintainer chose fix-now over document-and-defer);
    **MIXEDKEY** (A2.3) tolerant compare + designed loud bounds; **WRAPLAYER** closed the
    2 red pins (the setChildren JavaBeans premise, NOT aliasing — the setter contract is
    now documented in GUIDE/EMBEDDING) and MEASURED the read-snapshot at ~10–33%
    (accepted); LAYOUTMIG (19) + JRNLDATA (54) + **FKBENCH (50: the FK writes 23–160×,
    LINEAR in the FK-tree size — the O(relations) scan)** + JDK21 (27) + BENCHREFRESH
    (43: the 2026-11-21 baseline + the attribution method — the ONE real code regression
    is saveAndDeleteIndexedShipWithCrew −24%, the correctness wave's price, tracked in
    the W-4 ledger). **The 2.2.0 intake takes: task 21 (the FK O(relations) write path —
    the measured debt must land in a stated band) and task 22 (the lazy read-snapshot —
    read-only txs stop paying the alias net).** NOT filed as tasks, recorded here:
    **B3 as a standalone track is CLOSED** (the convoy root-cause is fixed; the residual
    contention work rides 2.2.0 task 13's investigation; the FLAKY row is re-recorded);
    the **R-56 multi-scan walk race** (latent — 0 phantoms on the overlay vs 24810 at
    HEAD) folds into task 01's consistency core / task 03's property tests as a pinning
    target; **[AMENDED 2026-11-22: FILED as its own task — 2.2.0 task 23 R56SCAN]** the
    **B-link tree upgrade** stays evidence-gated (a Step-2/05 design-round
    input, build on a nightly-logs signal). **33 BINFEAS closed 2026-11-22** (the FP-9
    gate GO + the real-codec experiment — docs/perf/binfeas-2026-11-22/RESULTS.md);
    **44 DOCSWRITER closed 2026-11-22** (the five-surface one-story pass). The 2.2.0
    announcement notes are drafted at
    docs/announcements/2.2.0-ANNOUNCEMENT-NOTES.md (the writer's input for task 11).
21. **HOTFIX 2.1.1 (2026-11-22, the procedure rehearsed end-to-end):** the post-release
    dependency CVE sweep found commons-lang3 **3.8.1 pinned in 2.0.0/2.1.0 affected by
    [CVE-2025-48924](https://nvd.nist.gov/vuln/detail/CVE-2025-48924)** (ClassUtils.getClass
    recursion DoS; fixed in 3.18.0). Route (a) per RELEASE-PLAN §2.8: branch `release/2.1`
    FROM the tag v2.1.0 → the bump + the version 2.1.1 + the CHANGELOG `## [2.1.1]` section
    (commit `f301eac`) → the full gate caught TWO version-pin collaterals of the bump (the
    walkthrough's hardcoded libraryVersion expectation — now derives from
    `XdStorageProvider.getVersion()`; the quickstart pin) → gate green 1345/0f/0e ×2 →
    the user tagged/pushed `v2.1.1` (Central-verified, the stranger test green) → merged
    back to main (`e2b0495`). The LESSON: a version bump is never one line — the
    version-pin contract tests fire by design; budget a fix round.
    **CONTINUED 2026-11-23 — 2.1.2 SHIPPED:** the property campaign's 4 serialization
    fixes + the tx-timeout default raise (15000, the interim VISBUDGET) accumulated on
    `release/2.1` per the maintainer's "no patched release now" call, then shipped as
    `v2.1.2` on the maintainer's word (the accumulation model worked: the branch held
    the fixes, the bump/tag only at ship). Merged back (`ac57bd8`; main keeps 2.2.0).
    **CONTINUED 2026-11-23 — 2.1.3 SHIPPED:** the DUPRACE targeted-delete retry + the
    R56SCAN epoch-validated walk retry accumulated on `release/2.1` and shipped as
    `v2.1.3` (Central-verified). Merged back (`e0f35e5`; main keeps 2.2.0).
22. **THE PARALLEL-WORK WAVE RECONCILED (2026-11-27, branch `parallel-work`; the
    maintainer's order — finish what is not finished, merge the six branches into one,
    check it on CI through a pull request):** six one-commit branches
    (`feature/parallel-work-{04,12,15,17,40,41}-…-main`, 2026-11-24/25) each marked its
    task done; read against their own criteria on `main`, none was. Two shipped inert
    code: 15's backup copied a folder that still read the ORIGINAL, and locked the engine
    into a deadlock (a save with a generated id racing a `checkpoint()`: 5 runs of 5);
    17's two tools never touched a folder's content (`migrate` changed no file,
    `schema-diff` read every folder as empty). 40's fix corrupted old data and was
    replaced by the `9e51a4f` implementation (cherry-picked from the top-5 wave); 12 and
    41 had rewritten their acceptance criteria after the fact; 04's Windows leg could
    never have run (PowerShell against `/tmp`, and a JVM does not start with a `-Xlog`
    file whose directory is missing). Merged — five merges and the cherry-pick — then one
    fix commit per task: `684efd2` 04, `2eb8be8` 12, `ea7a7e4` 41, `4da7dd4` 17,
    `5246879` 15. **Status after:** 15, 17, 40, 41 done; 04 open on one point (the Windows
    result — the leg runs on the pull request, nothing is triaged yet); 12 open on one
    decision (lift NOT RECOMMENDED or deprecate — the design note is written, and the
    isolation stress test turned out to be in every suite since `59ff544`, not excluded
    as FLAKY.md said until now). **Findings that outlive the wave:** (a) a storage folder
    names its files by the path it was opened with — a copy made by hand is tied to the
    original's path (EMBEDDING § Backup and restore; `backupTo` rewrites it); (b) 2.1.x
    saved a `UUID` / `java.time` field not at all and made an index over one unreadable;
    (c) the engine stores a class's structure only after `executeStructureUpdate`, so a
    schema diff of an ordinary folder has nothing to compare. Gate on `parallel-work`:
    `clean test -Pquick-gate` **1746/0f/0e/7 skipped ×3, consecutive**, BUILD SUCCESS, 0
    checkstyle violations (`main`: 1360). The push and the pull request are the maintainer's.

**2.1.0 exit (2026-11-22 status): everything landed, all 54 work packages closed
(44 DOCSWRITER landed 2026-11-22: the glossary, the FK story, the canonical numbers on
all surfaces, DOCSLINT extended with anchor resolution; suite 1345/0f/0e ×2, the
fresh-eyes round, the quickstart compile-proof).** The stress family is 7/7, the gate
is green through the whole wave; **the RC protocol runs next**: full suite ×3, kill-9/
torn-journal recovery, JMH vs the refreshed 2026-11-21 baseline, fresh-clone +
2.0.0-upgrade walkthroughs → tag `v2.1.0`.

### Step 2 — The engine's 2.x maturity (3–5 sessions, design round first)

1. **B1 / FP-4 / F-3 thaw — W-4 IMPLEMENTATION → index journaling + disk-anchored scans +
   record-cache eviction.** Removes the heap ≈ DB ceiling and the index-write amplification;
   the ≥10× write acceptance target lives here. The project's own rule holds: mandatory design
   doc, kill-9 recovery tests, honest benchmark verdicts — do not let Step 1 enthusiasm pull
   it forward. **AMENDED 2026-10-23: the design round itself was pulled into the 2.1.0 train**
   (task 31, P1 — the field feedback demands the public analysis + fix plan); what Step 2
   implements is the finished design, revisited against fresh numbers.
2. **B3 — lock striping / shallower deep-lock chains** (R-29 contention removal; the only
   approaches not yet rejected — F-2). Now also closes the **last open FLAKY.md row**
   (BTreeStress anomalies under full-suite load).
3. Gate: nightly ×3 clean, JMH write scenarios ≥10× vs the 2026-10-18 baseline, zero new flake
   ledger rows.

### Step 3 — Deployment story (2–3 sessions)

1. **FP-3 — AOT wrapper generation** (Maven plugin first, Gradle later): build-time wrapper
   compilation into the consumer jar; runtime codegen preserved as fallback; **loud** startup
   failure when neither path is available (kills the silent-JRE degradation). Unlocks JRE-only
   containers, app-server redeploys, Graal native-image, and removes the cold-start javac cost
   (supersedes Track C). Acceptance: JRE-only container runs the demo without `./gencode`;
   cold-start first-save measured before/after in BENCHMARKS.md.
2. **FP-5 — backup & verify API**: `storage.backupTo(folder)` (consistent point-in-time copy —
   MVCC freeze-copy) + `verify()` integrity walk (sentinels, index-vs-data consistency).
3. **FP-6 — admin/inspection CLI** (`xdstorage-tools.jar`): `inspect` / `dump` / `verify` /
   `compact`; shares FP-5's verification core; operates on a folder or an FP-5 backup (the
   single-writer lock stays intact). Doubles as the support/diagnosis story.

### Step 4 — Ecosystem train (2.x→3.0, demand-driven)

Each item independently shippable; **build only on a concrete demand signal** (a GitHub issue,
an embedder) — the frozen-items inventory exists precisely to prevent speculative growth.

- **FP-2** `explain()` for queries (plan + records visited + elapsed; P1)
- **FP-7** Spring Boot starter (autoconfiguration, config properties, lifecycle bean; P2)
- **FP-8** `xdstorage-micrometer` module (amends F-7; P2)
- **FP-9** binary compact format as non-default SPI (post-W-4; ~~gate = task 33's
  serialization-share measurement~~ **GATE RESOLVED 2026-11-22: GO for the design
  round** — the serialization layer is 84–86% of the cold full load, 82–86% of the cold
  FIRST lookup, 76–79% of the checkpoint write-out (the ceiling ≈ 4.2–7.4× assuming a
  zero-cost parser; the gzip bytes floor 29.6×). Warm paths are format-blind (1×).
  Numbers + caveats: docs/perf/binfeas-2026-11-22/RESULTS.md; the open design-round
  question: the journal-append serialization share (bypasses the SPI, unmeasured)
- **FP-13** after-commit asynchronous listeners (the OPTIMIZATION-PROPSAL-01 async-trigger
  idea, re-framed): event hooks that run AFTER the commit, never inside it — the in-commit
  veto contract stays untouched; failure = logged, never rollback. Demand-driven.
- **FP-10** encryption-at-rest SPI (P3)
- **FP-12** native-image + JDK 21 virtual-threads CI jobs (extends D-2; meaningful the day
  FP-3 lands)

---

## 3. Sequencing summary

| Order | Work | Effort | Unlocks | Release |
|---|---|---|---|---|
| 0 | Step 0: A2 + E-1..E-6 + D-3 → **2.0.0** — **DONE: shipped 2026-10-21** | 1–2 sess | Everything public | **2.0.0** |
| 1 | **2.1.0 train:** Track L + T + BENCH + B2 + FP-1 + BUG-A..E + PWRLOSS/JRNLNAME/BACKUP/N50K/DOCSLINT + AUTOSAVE + pulls (25/26/27) + FK + W-4a/b/c + TREEOVERLAY/LOSTUPD-2/MIXEDKEY + LAYOUTMIG/JRNLDATA + FKBENCH/BENCHREFRESH + DOCSWRITER — **SHIPPED: tagged `v2.1.0` 2026-11-22, on Maven Central (54/54 closed)** | ~20 sess actual | Quiet library default; grouped estate; queries + plans; **FK integrity; concurrent writes unlocked; the lost-update window closed; the layout migration; honest costs published** | **2.1.0** |
| 2 | **2.2.0 = the compiled train** (docs/tasks/2.2.0/): consistency core (01) + loud failures (02) + property tests (03) + W-4d memory (05) + WAL txid (06) + **FKWRITE (21) + READSNAP (22)**; platform: AOT/JRE (08), backup (15), CLI (16), schema-diff (17), agent surface (18), recovery automation (19); decisions: server mode (09, design-only), Windows (04), CI policy (10), nested tx (12, optional), facade (13), profiling (14) | per-task | Operability; JRE deployments; the FK band; the memory profile; the consistency estate | **2.2.0** |
| 3 | FP-3 AOT + FP-5/FP-6 ops tooling | 2–3 sess | JRE/native-image deployments; operability | 2.3.0 |
| 4 | FP-2/7/8/9/10/12/13 ecosystem train | 1–2 sess each | Mainstream adoption surface | 2.x→3.0 |

**Release-train decision (corrected 2026-10-21; bug-lane amendment 2026-10-22):** 2.1.0 is
the post-release hardening train — it deliberately carries no deep engine *feature* surgery
(W-4's persisted-state semantics change would force the testing work and the feature work to
share one RC risk window); the engine maturity work (W-4 + B3) is 2.2.0, the deployment story
is 2.3.0, Step 4 stays demand-driven. **Amendment: a defect discovered by a train's own test
estate ships with the train that discovered it** — T-3a (task 06) surfaced four engine
defects (BUG-A..D), each pinned with an acceptance-grade `@Disabled` test; they joined 2.1.0
as tasks 14–17 and the `docs/tasks/2.2.0/` directory was dissolved (2.2.0 keeps the W-4+B3
version pinning; its directory gets created when that train starts). 2.1.0 ships when the RC
protocol passes on the extended scope: quiet-by-default logging verified on a config-less
consumer, tag-driven grouping enforced, coverage map recorded, FP-1 acceptance met, and all
four defects fixed with their disabled tests green.

## 4. Explicitly out of scope (carried over — do not re-propose)

Structural MVCC without a W-4-class design round (F-1); the rejected RW-lock waiting variants
(F-2); JSON implementation if A2 removes it (F-5); test-directory rename (F-6); JMX adapter
outside the FP-8 module shape (F-7); caching equals/toString in wrappers and ASM bytecode
generation (CODEGEN items 7/8); and — per the evaluation, consistent with the project's own
verdicts — network/server mode, multi-process write access, SQL layer, full-text engine.
