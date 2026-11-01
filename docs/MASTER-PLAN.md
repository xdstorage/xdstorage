# XdStorage — Master Roadmap

Compiled 2026-10-19; updated 2026-10-20 (architect's evaluation integrated); **updated
2026-10-21 post-release: the 2.1.0 train is extended with Track T (test coverage & grouping)
and Track L (log hygiene) — the two things the 2.0.0 release itself surfaced; W-4/B3 move
explicitly to 2.2.0**. Detailed journals stay authoritative per scope:

| Plan | Scope | State |
|---|---|---|
| [OPTIMIZATION-PLAN.md](OPTIMIZATION-PLAN.md) | Engine campaign journal R-1..R-59 | Phases A/B/D done; **Track A family FIXED (R-54..R-58)**; R-59 SOE fix |
| [SRE-OPTIMIZATION-PLAN.md](SRE-OPTIMIZATION-PLAN.md) | Whole-project SRE plan (Phases 0/S/W/O/C) | 0, S, O, C **DONE**; W-1..W-3 **DONE**; W-4 open |
| [WRITE-PATH-DESIGN.md](WRITE-PATH-DESIGN.md) | Phase W design (journal + checkpoint) | W-1..W-3 implemented; W-4 = the deferred design round |
| [CODEGEN-OPTIMIZATION-PLAN.md](CODEGEN-OPTIMIZATION-PLAN.md) | Wrapper codegen | Items 1/2/5 applied; **items 3/4 superseded by FP-3 (AOT)** |
| [PERFORMANCE-RECOMMENDATIONS.md](PERFORMANCE-RECOMMENDATIONS.md) | Config study (2nd ed, post-R-53) | Recommendations APPLIED (t=64, frag=128); §5.3 **CLOSED** (2.1.0 B2: verified 0.6–0.7 ms/op flat, BENCHMARKS addendum) |
| [ARCHITECT-EVALUATION.md](ARCHITECT-EVALUATION.md) | Outside-architect review, competitive comparison, FP-0..FP-12 feature plan | Adopted; FP-0..FP-12 tracked here as Track F |
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
   **BUG-E (18, P0, OPEN)** search-index key types not restored at reopen — numeric
   range/EQUALS seeks crash after a close/open cycle (`XdStorageSearchRangeReopenTest`
   disabled).
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

**2.1.0 exit:** RC protocol per RELEASE-PLAN §2 (full suite ×3 green, kill-9/torn-journal
recovery, JMH vs baselines, fresh-clone README walkthrough) → tag `v2.1.0`.

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
- **FP-9** binary compact format as non-default SPI (post-W-4; gate = task 33's
  serialization-share measurement — the OPTIMIZATION-PROPSAL-01 demand signal)
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
| 1 | **2.1.0 train:** Track L + Track T + BENCH + B2 + FP-1 + **BUG-A..E** + **PWRLOSS/JRNLNAME/BACKUP/N50K/DOCSLINT** + AUTOSAVE + **master-plan pulls: FP-2 explain (25), FP-5-core verify (26), D-2 JDK-21 matrix (27)** — **IN PROGRESS (pom at 2.1.0)** | 5–7 sess | Quiet library default; a measured, grouped test estate; query expressiveness + plan visibility; read-heavy workloads; **no data-stranding defects; self-service integrity verification** | **2.1.0** |
| 2 | W-4 (B1/F-3) + B3 | 3–5 sess | Large datasets; ≥10× writes; zero stress flakes | **2.2.0** |
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
