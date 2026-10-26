# 2.1.0 train — work packages for Senior Java Developers

One markdown file = one GitHub issue. The frontmatter (`title`, `labels`) is read by
[`scripts/sync-issues.sh`](../../../scripts/sync-issues.sh); everything below it becomes
the issue body. Scope and priorities derive from
[MASTER-PLAN.md](../../MASTER-PLAN.md) § Step 1 (the 2.1.0 train) — read Track L / Track T
there for the full decision record before starting an item.

## Index

| # | File | Track | Labels | Est. | Depends on |
|---|---|---|---|---|---|
| 01 | [slf4j-migration](01-slf4j-migration.md) | L-0/L-1 | P0, logging, refactor | 1 d | — |
| 02 | [quiet-by-default-logging](02-quiet-by-default-logging.md) | L-2 | P0, logging, refactor | 0.5 d | 01 |
| 03 | [logging-build-guards](03-logging-build-guards.md) | L-3 | P0, logging, ci | 0.5 d | 01 |
| 04 | [jacoco-coverage-baseline](04-jacoco-coverage-baseline.md) | T-1 | P1, testing, ci, chore | 0.5 d | — |
| 05 | [tag-based-test-groups](05-tag-based-test-groups.md) | T-2 | P0, testing, refactor | 1 d | — |
| 06 | [test-gap-undercovered-packages](06-test-gap-undercovered-packages.md) | T-3a | P1, testing, tech-debt | 2 d | 04, 05 |
| 07 | [facade-audit-and-litter-cleanup](07-facade-audit-and-litter-cleanup.md) | T-3b | P1, testing, chore | 1 d | 04, 05 (06 partially) |
| 08 | [read-only-commit-path](08-read-only-commit-path.md) | B2 | P0, engine, performance, feature | 1 d | — |
| 09 | [query-range-operators](09-query-range-operators.md) | FP-1a | P0, search, engine, feature | 2 d | — |
| 10 | [query-order-limit-offset](10-query-order-limit-offset.md) | FP-1b | P1, search, engine, feature | 1–2 d | 09 |
| 11 | [jmh-regression-alert](11-jmh-regression-alert.md) | D-4 | P2, ci, performance, chore | 0.5–1 d | 13 (scenario list) |
| 12 | [decision-guide-and-example-app](12-decision-guide-and-example-app.md) | FP-11 | P1, docs, chore | 1 d | 09, 10 (describes shipped capabilities) |
| 13 | [benchmark-suite-unification](13-benchmark-suite-unification.md) | BENCH | P0, performance, ci, chore | 2 d | — (**ASAP: before 08–11 record/assert numbers**) |
| 14 | [uuid-generator-field-property](14-uuid-generator-field-property.md) | BUG-D | P2, engine, bug | 0.5 d | — (decision + small fix; discovered by 06) |
| 15 | [commit-journal-replay-npe](15-commit-journal-replay-npe.md) | BUG-A | P0, engine, bug | 2–3 d | — |
| 16 | [id-addressability-after-structure-update](16-id-addressability-after-structure-update.md) | BUG-B | P1, engine, bug | 1–2 d | 15 (where masked by the journal crash) |
| 17 | [auto-generated-id-reopen-addressability](17-auto-generated-id-reopen-addressability.md) | BUG-C | P0, engine, bug | 2–3 d | — |
| 18 | [bug-search-index-key-types-after-reopen](18-bug-search-index-key-types-after-reopen.md) | BUG-E | P0, engine, bug | 1–2 d | — (discovered by 12; disabled test `XdStorageSearchRangeReopenTest`) |
| 19 | [backup-restore-walkthrough](19-backup-restore-walkthrough.md) | BACKUP | P1, testing, docs | 0.5–1 d | — (one assertion waits for 18) |
| 20 | [design-point-n50k-validation](20-design-point-n50k-validation.md) | N50K | P1, performance, chore | 1–2 d | 13 (the harness/protocol it extends) |
| 21 | [adoption-docs-contract](21-adoption-docs-contract.md) | DOCSLINT | P2, docs, ci, chore | 0.5 d | 12 (the surface it keeps honest) |
| 22 | [autosave-cascade-children](22-autosave-cascade-children.md) | AUTOSAVE | P1, engine, feature | 2–4 d | — (mini-spike first: hook point + deferred-id interplay) |
| 23 | [power-loss-recovery](23-power-loss-recovery.md) | PWRLOSS | P0, engine, testing, docs | 2–3 d | — (the "electricity-lost" incident: recovery matrix + recipe + the durableWrites default decision) |
| 24 | [journal-file-naming](24-journal-file-naming.md) | JRNLNAME | P1, engine, bug | 0.5–1 d | — (single choke point; the 2.0.0-upgrade read-compat test is the careful part) |
| 25 | [explain-query-plan](25-explain-query-plan.md) | EXPLAIN | P1, search, engine, feature | 1–2 d | — (pulled from Step 4: the routing decision extracted, not forked) |
| 26 | [verify-integrity-walk](26-verify-integrity-walk.md) | VERIFY | P1, engine, testing | 1–2 d | — (FP-5's verification core pulled from Step 3; backupTo/CLI stay 2.3.0) |
| 27 | [jdk21-ci-matrix](27-jdk21-ci-matrix.md) | JDK21 | P2, ci, chore | 0.5 d | — (D-2 pulled: the baseline is JDK-21 numbers, the gate is 17-only) |
| 28 | [javadoc-coverage](28-javadoc-coverage.md) | JAVADOC | P1, docs, chore | 3–5 d | — (Tier-1/Tier-2 split, parallelizable; closes the RELEASE-PLAN §3 doclint promise) |
| 29 | [readme-landing-restructure](29-readme-landing-restructure.md) | LANDING | P0, docs | 1 d | — (**the field-feedback P0 track, runs FIRST**: what/when/when-not + 10-line example + comparison + write-numbers-with-plan above the fold) |
| 30 | [benchmarks-context](30-benchmarks-context.md) | BMCONTEXT | P0, docs, performance | 0.5 d | 29 (the entry surface it complements) |
| 31 | [write-path-analysis-design](31-write-path-analysis-design.md) | WRTPATH | P1, engine, performance | 2–3 sess | — (profile → bottleneck ledger → the W-4 design; **impl stays 2.2.0**; feeds 29/30's fix-plan links) |
| 33 | [binary-format-feasibility](33-binary-format-feasibility.md) | BINFEAS | P2, docs, performance | 0.5–1 d | — (the OPTIMIZATION-PROPOSAL-01 demand signal; FP-9's gate) |
| 35 | [crash-residue-purge](35-crash-residue-purge.md) | CRASHPURGE | P1, engine, testing, bug | 1–2 d | — (2/3 red: the purge dies on dangling references; disabled by `b3aeacb`) |
| 36 | [zombie-latch-purge](36-zombie-latch-purge.md) | ZOMBIELATCH | P1, engine, testing, bug | 1–2 d | — (3/3 red: the purge dies on zombie record latches; disabled by `b3aeacb`) |
| 34 | [rollback-only-cause](34-rollback-only-cause.md) | RBCAUSE | P1, engine, bug | 1–2 d | — (first-cause capture on the tx; the generator site-descriptors are the careful half) |
| 32 | [w4-write-path-implementation](32-w4-write-path-implementation.md) | W4 | P0, engine, performance, feature | W-4a 2–3 sess + W-4c 1 sess (in-train); W-4b next; W-4d gated | 31 (the governing [W4-DESIGN.md](../../W4-DESIGN.md)); before-numbers FIRST |
| 34 | [cache-commit-protocol-audit](34-cache-commit-protocol-audit.md) | CACHE2PC | P2, engine, bug | 0.5–1 d | — (found by 28: phase-1/2 duplication + legacy rollback order) |
| 35 | [silent-failure-hazards-codegen-observer-cloner](35-silent-failure-hazards-codegen-observer-cloner.md) | SILENTFAIL | P2, engine, bug | 1–1.5 d | — (found by 28: three swallow-and-continue paths) |
| 36 | [structure-update-correctness](36-structure-update-correctness.md) | STRUCTFIX | P2, engine, bug | 1 d | — (found by 28; extends the 16 defect family) |
| 37 | [yaml-reader-strictness](37-yaml-reader-strictness.md) | YAMLREAD | P2, engine, bug | 1–1.5 d | — (found by 28: corruption must fail loudly) |
| 38 | [index-resource-contracts](38-index-resource-contracts.md) | IDXCONTRACT | P2, engine, tech-debt | 1 d | — (found by 28: implementations vs declared contracts) |
| 39 | [message-hygiene-dead-params](39-message-hygiene-dead-params.md) | MSGHYGIENE | P2, chore, tech-debt | 0.5 d | — (found by 28; grep tests before touching asserted messages) |
| 40 | [lost-update-first-committer-wins](40-lost-update-first-committer-wins.md) | LOSTUPD | P1, engine, bug | 2–3 d | — (PROVEN by the 2026-10-24 stress investigation: conflict check compares writer-start, not read-vs-commit; blocks a reliable full-suite gate until landed) |
| 41 | [tree-node-file-leak-rollback](41-tree-node-file-leak-rollback.md) | TREEFILE | P1, engine, bug | 2–3 d | — (PROVEN by the 2026-10-25 file-leak run: infra rollback asymmetry — aliased canonical tree records make node files permanent orphans after rollbacks; composes with 38+40) |

**RELEASE CRITICALITY TRIAGE (architect, 2026-10-26) — what 2.1.0 ships with:**

| Verdict | Task | Why |
|---|---|---|
| **BLOCKER** | 35 CRASHPURGE (2/3 red) | The release sells the post-crash recovery/cleanup story (tasks 19/23) — the release-gate test proving it is disabled and failing. 1–2 d. |
| **BLOCKER** | 36-zombie-latch (3/3 red, deterministic) | Same family: the purge cannot run while zombie latches live; also the worker-hygiene contract fix. 1–2 d. |
| **BLOCKER-or-explicit-deferral** | 16 BUG-B | Silent id-unreachability after structure update = a correctness defect. Either fix in-train (needs the wrapper-identity design decision made fast) or defer to 2.2.0 WITH a loud EMBEDDING limitation + the @Disabled acceptance test as recorded debt. Shipping silently is the only forbidden outcome. |
| **SHOULD-FIX** | 39 message-hygiene | Minutes each; misdirecting messages (incl. the `idgeneration <id>` misuse the new diagnostics emit). |
| **SHOULD-FIX** | 36-structure-update-correctness | SUCCESS reported as FAILURE in `updateStructure` — callers branch on it; equals/hashCode on persisted records. |
| **SHOULD-FIX (after 35/36 settle)** | 38 index-resource-contracts | Disk-full reported as "invalid state of database" sends operators down the corruption path; touches the fresh W-4a journal path — sequence it last. |
| **INCLUDE if capacity** | 27 JDK21 matrix | 0.5 d; the published baseline is JDK-21 numbers — the release should test on 21. |
| **SLIP to 2.1.1** | 28 JAVADOC | 3–5 d full coverage + doclint flip; the javadoc jar ships as-is for 2.1.0, the E-2 tightening moves one release (honest note in RELEASE-PLAN §3). |
| **SLIP to 2.2.0** | 25 EXPLAIN, 33 BINFEAS | Additive feature / FP-9 gate analysis — no release dependency; EXPLAIN's plan table should follow W-4b anyway. |

**Numbering collision notice:** tasks 34/35/36 exist TWICE (the code-smell ledger sequence 34–41 filed by the javadoc campaign collided with the train sequence). File paths are the sync-issues ownership key — **do not renumber** (renaming orphans issues); reference tasks by full file name; 2.2.0 starts fresh numbering at 01.

**The field-feedback track (added 2026-10-23 — "more important than new features"):**
the P0 docs track executes in order **29 → 30**, with **31 (P1, ASAP)** running in
parallel — its design/analysis is what 29/30 publish as the write-performance fix plan.
The content for 29/30 already exists (DECISION-GUIDE, quickstart, canonical baseline);
the failure was the entry surface, and these tasks fix the surface, not invent content.

**Javadoc-campaign findings (added 2026-10-24):** 34–39 are the doc/code mismatches the
28-javadoc-coverage campaign surfaced (the agents documented the code with fresh eyes and
reported what the code does differently from what any reader would assume). All are P2:
fix-with-test work on engine internals, no embedder-facing API change, no persisted-state
migration. Lane-parallel with everything; 39 is the mechanical one to sweep last.

**MASTER-PLAN pulls (added 2026-10-23):** 25–27 are what the master-plan review judged
train-safe — additive APIs and CI, no persisted-state surgery: FP-2 `explain()` (25), FP-5's
verification core `verify()` (26), and D-2's JDK-21 matrix (27). **Explicitly NOT pulled:**
W-4 + B3 (2.2.0 — deep engine surgery; the train's amendment admits fixes, not feature
surgery), FP-3 AOT (2.3.0 — a multi-session maven-plugin effort would push the train out by
weeks), FP-5 `backupTo` + FP-6 CLI (2.3.0), FP-7/8/9/10/12 (demand-driven / post-FP-3 by
their own rules).

**Guide-derived tasks (added 2026-10-23):** 19–21 were compiled from
[DECISION-GUIDE.md](../../DECISION-GUIDE.md) after its publication — each makes one sentence
of the guide true or keeps it true: the backup claim (19), the published design point (20),
and the anti-rot mechanism for the adoption surface itself (21). The big items the guide
names as gaps (W-4, FP-3 AOT, FP-5 backup API) stay pinned to 2.2.0/2.3.0 — the guide
documents them as future work; it does not re-plan them.

**Bug-lane provenance (migrated 2026-10-22):** BUG-A/B/C were originally filed against a
2.2.0 train under the "2.1.0 carries no deep engine surgery" pinning. The decision was
revised: **a defect discovered by a train's own test estate ships with the train that
discovered it** — T-3a (06) surfaced all four, each with an acceptance-grade `@Disabled`
test; un-disabling that test IS the fix's acceptance. The `docs/tasks/2.2.0/` directory is
dissolved (W-4/B3 keep their 2.2.0 version pinning; that train's directory gets created when
it starts).

### Defect snapshot (evidence in docs/tasks/2.1.0/06)

| Defect | Signature | Strands data? | Acceptance test |
|---|---|---|---|
| BUG A (15) | journal replay NPE on `structure/XdStorageClassStructure.yaml` after structure update + reopen | folder unopenable | `XdStorageSchemaEvolutionTest` BUG-A tests |
| BUG B (16) | load-by-id null after structure update on a freshly-opened storage (non-system-loader classes) | object unreachable | `XdStorageSchemaEvolutionTest` BUG-B tests |
| BUG C (17) | auto-generated ids unreachable by id after reopen (id assigned at write-out, after the tx index snapshot) | object unreachable | `XdStorageIdGenerationContractTest.generatedIdsSurviveReopen` |
| BUG D (14) | `UUID_GENERATOR` field property never auto-generates | contract never worked | contract decision + test in task 14 |
| BUG E (18) | search-index key types not restored at reopen — numeric range/EQUALS seeks crash after a close/open cycle | queries crash post-reopen | `XdStorageSearchRangeReopenTest` (3 tests, disabled) — discovered by 12 |

## Execution lanes (independent — can run in parallel)

- **Lane A (logging):** 01 → {02, 03}
- **Lane B (testing):** {04, 05} → {06, 07}
- **Lane C (engine/search):** **13 first (ASAP — the canonical benchmark suite; tasks 08/09/10
  record their numbers into it, 11 asserts on its scenario list)** → 08 ∥ 09 → 10; 22
  (AUTOSAVE — independent: mini-spike, then feature work)
- **Lane E (bug fixes, data safety & diagnostics, engine):** 15 ∥ 17 ∥ 14 (independent surfaces: structure journal vs
  write-out id assignment vs the generator contract) → 16 after 15 (the journal crash masks
  part of its repro); 18 independent (P0 — numeric search indexes are dead after any reopen);
  23 independent (P0 — the power-loss incident: recovery matrix + recipe + the
  `durableWrites` default decision); 24 independent (journal naming + 2.0.0-upgrade
  read-compat — land before the 2.x on-disk base grows); 34 independent (P1 — the rollback-only cause capture: every marking site names itself, commit failures chain the root cause)
- **Lane F (guide-derived adoption work):** 19 ∥ 20 ∥ 21 (independent; 20 after 13's harness
  is in — it already is; 21 after 12, ditto)
- **Lane G (master-plan pulls):** 25 ∥ 26 ∥ 27 (independent; 25 after the FP-1 routing it
  exposes — shipped; 26 after 19+23 whose semantics it mechanizes — shipped)
- **Lane H (API docs):** 28 — Tier-1/Tier-2 split is parallelizable between developers;
  the doclint tightening is the deliverable, the prose can land incrementally
- **Lane I (field-feedback P0/P1 — BEFORE new features):** **29 → 30** (P0, in order) ∥
  **31 → 32** (the write-path track: analysis/design DONE — W4-DESIGN.md; the W-4a+c
  implementation now in-train per the architect decision, W-4b follows W-4a's soak, W-4d
  decision-gated — may complete in 2.2.0)
- **Lane J (proposal responses):** 33 — analysis-only (the OPTIMIZATION-PROPOSAL-01
  binary-format demand signal; its other directions mapped to existing plan items —
  docs/proposals/OPTIMIZATION-PROPSAL-01-ANALYSIS.md)
- **Lane K (the disabled release-gate stress tests):** 35 ∥ 36 — un-disable is the exit
  criterion; both must pass the 10-consecutive-pass gate before closing
- **Lane D (docs, last):** 12 — after the query pack lands, so it describes what ships.

## Label glossary

| Label | Meaning |
|---|---|
| `2.1.0-train` | Work package of the 2.1.0 release train (all issues carry it; the sync derives `<version>-train` labels from the directory — a new train needs no script edit) |
| `P0` / `P1` / `P2` | Must / should / nice-to-have land in the train |
| `logging` `testing` `engine` `search` `docs` `ci` | Subsystem |
| `refactor` `feature` `chore` `tech-debt` `performance` `bug` | Work type |

## Working conventions (binding for every task)

- A run is judged by Maven's own `BUILD SUCCESS` / `Tests run: N, Failures: M` line — never by a
  piped grep's exit code ([FLAKY.md](../../FLAKY.md), the R-52 gotcha).
- Storage name + directory unique per test run; never gate test logic on `deleteDir` success.
- New tests follow the flake-ledger discipline: anything load-sensitive gets the `stress` tag
  (task 05 formalizes this) and lands in [FLAKY.md](../../FLAKY.md) if it flakes.
- User-facing changes update [CHANGELOG.md](../../../CHANGELOG.md) (`[Unreleased]` section —
  entries for 01, 02, 08, 09, 10 are already drafted there; adjust, don't re-invent).
- Performance claims in PRs cite [BENCHMARKS.md](../../BENCHMARKS.md) /
  [PERFORMANCE-RECOMMENDATIONS.md](../../PERFORMANCE-RECOMMENDATIONS.md) baselines; JMH numbers
  recorded as addenda, honestly (the W-3 precedent: report "unchanged" when it is unchanged).

## Automatic issue sync (GitHub)

Issues are managed automatically by the **issues-sync workflow**
([.github/workflows/issues-sync.yml](../../../.github/workflows/issues-sync.yml)): it runs on
**every push to `main`** (and on demand via its *Run workflow* button) and executes
[`scripts/sync-issues.sh`](../../../scripts/sync-issues.sh) — the same script runs locally
(`gh auth login && bash scripts/sync-issues.sh [--dry-run]`).

**Status marks — the developer's side.** Each task file's frontmatter carries a `status` mark:

- `status: open` *(default)* — the work package is open.
- `status: done` — set **by the developer** when the task's acceptance criteria are met and
  merged. The next push then closes the GitHub issue automatically (with a comment naming the
  file that marked it done).

**The sync's state machine** (idempotent; source of truth = the task files):

- It scans **every** `docs/tasks/<version>/` directory — a new train's directory is picked up
  automatically; the GitHub milestone = the directory name (attached at creation only if it
  exists). Task titles MUST carry the `[x.y.z]` train prefix: title matching is global across
  trains, the prefix is what prevents collisions.

| File says | GitHub says | Action |
|---|---|---|
| `open`, no issue | — | create the issue (labels + milestone `2.1.0` if it exists) |
| `open`, content unchanged | issue open | nothing — already in sync |
| `open`, **content changed** | issue open | **update** the issue: title, body, add missing labels |
| `open`, content changed | issue closed | update the body + **WARN** — left closed (a machine never reopens; fix file or issue by hand) |
| `open` | issue closed, content unchanged | **WARN** — left closed (fix file or issue by hand) |
| `done`, no issue | — | create the issue, then close it (history preserved) |
| `done`, content changed | issue open | update the body (if drifted), then close it with an explanatory comment |
| `done` | issue closed, content unchanged | nothing — already in sync |

**Content-drift detection:** every created/updated issue body ends with an invisible
ownership marker — `<!-- xdstorage-task: <task-file path> -->`. The sync matches issues by
that marker first (so a title edit in the file updates the issue's title instead of creating
a duplicate), falls back to exact-title matching for legacy issues (whose bodies gain the
marker on the first content update), and compares the stored body against the file's current
body — any edit to a task file reaches its issue on the next push. Labels are add-only at
update (a human-added label is never stripped); titles and bodies sync verbatim. **Keep task
file paths stable**: the marker is the path — renaming a task file orphans its issue by
design.

**Token setup (one-time).** The workflow authenticates with the repository secret
**`XDSTORAGE_ISSUES_TOKEN`** — *Settings → Secrets and variables → Actions → New repository
secret*: a token (fine-grained PAT or classic) with **Issues: read & write** on this
repository. The built-in `GITHUB_TOKEN` is deliberately not used. Locally the script accepts
the same name (`GH_TOKEN=…` takes precedence if both are set; `gh auth login` works too):

```bash
export XDSTORAGE_ISSUES_TOKEN=ghp_…   # or: gh auth login
bash scripts/sync-issues.sh --dry-run
```

Notes: matching is by **exact title** (fetched once per run, open + closed); feature branches
never publish or close issues (the workflow is gated to `main`); concurrent runs are
serialized by a workflow concurrency group; every run writes an action-count table to the job
summary. To preview locally without touching GitHub: `bash scripts/sync-issues.sh --dry-run`.

The milestone `2.1.0` is optional — create it in the repo UI (or
`gh api repos/:owner/:repo/milestones -f title=2.1.0`) before the first sync if you want
issues pre-assigned. Cross-links between issues should use `#N` numbers, which exist only
after creation — edit the first comment if you prefer issue-links over file links.
