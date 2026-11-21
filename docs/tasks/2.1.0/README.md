# 2.1.0 tasks train — **53 work packages filed; 47 done, 6 open** (RC-ready once they close)

One markdown file = one GitHub issue (the frontmatter `title`/`labels`/`status` is the
issues-sync contract; see [the sync section](#automatic-issue-sync-github)). Scope derives
from [MASTER-PLAN.md](../../MASTER-PLAN.md) Step 1; the deep decision records live in the
linked plan documents (SRE/WRITE-PATH/W4-DESIGN/ARCHITECT-EVALUATION) and per-task files.

## Current status (verified 2026-11-21)

- **The engine cleared the release bar it set for itself:** the TREEOVERLAY program
  (task 53) landed — the B+tree per-tx overlay, the prevSnapshot role split, and the
  total acquisition order that destroyed the commit-lock convoy. The stress family is
  **7/7 green** (MassFillSecond ×3 WITHOUT the semaphore; W2Reopen ×3; BTreeStress 0
  phantoms vs the HEAD baseline 24810), the full quick-gate is **1321/0f/0e ×3**
  (BUILD SUCCESS ×3), and FKBENCH hit its target: **fk=ON t=10 = 121–158 tx/s**
  (was 0.8–1.2 — the ~150× collapse cured), p99 84–113ms, no t=1 regression.
- **Suite size:** 1321+ tests in the gate (the 2.0.0 baseline was ~370; the FK estate
  alone is 372).
- **Gate/release/nightly:** the stress/integration tags are excluded from the gate and
  release profiles (they run in the nightly ×3); the isolation-stress and facade-stress
  exclusions are re-include candidates now that the convoy is gone (2.2.0 tasks 12/13).
- **Post-release W-4 remainder** (ledgered in task 53 §STATUS and the dbms-developer
  skill): the lost-update window (stage→commit revalidation), the read-snapshot cost
  (the lazy capture), the wrapper de-aliasing (2 @Disabled pins), the BTreeStress
  R-56/multi-scan follow-up, and the A2.3 mixed-key CCE (blocks the FKBENCH fk=ON arm
  until the typed key normalization lands — the fk=OFF numbers stand).
- **The RC protocol is the next step** (RELEASE-PLAN §2): full suite ×3 green, JMH vs
  the refreshed baseline (task 43), fresh-clone walkthrough → tag `v2.1.0`. Task 51
  (the layout migration) gates the release itself.

## Open work (6)

| # | Task | What remains |
|---|---|---|
| 27 | [JDK21 CI matrix](27-jdk21-ci-matrix.md) | prove the suite on 21 (the baseline is 21 numbers) |
| 33 | [BINFEAS](33-binary-format-feasibility.md) | measure the serialization share on cold paths — FP-9's go/no-go |
| 43 | [BENCHREFRESH](43-benchmark-refresh.md) | full canonical re-run post-FK + post-overlay (senior dev; pin `BENCH_JDK`) |
| 44 | [DOCSWRITER](44-technical-writer-pass.md) | the one-story documentation pass (technical writer; now includes the AutoSave/ParentObject + secondary-keys block, 2026-11-21) |
| 50 | [FKBENCH](50-fk-cost-benchmark.md) | the FK-cost A/B table (Step 0 done: the concurrency axis measured; the fk=ON arm waits on A2.3) |
| 51 | [layout-migration-upgrade](51-layout-migration-upgrade.md) | **LAYOUTMIG — BLOCKING RELEASE**: the 2.0.0→2.1.0 layout migration (crash-tolerant, cross-OS, fully tested) |

**Release-readiness note (updated 2026-11-21):** every defect-class task of the train
is closed with pinned tests (BUG-A..E, 34–47, 53 — the last reds W2Reopen/BTreeStress
either fixed or stash-proven pre-existing). The remaining six opens are: the layout
migration (**51, P0 — a 2.0.0 user must be able to upgrade**), the benchmark refresh
(43) and the FK A/B (50) that make the docs' numbers true, DOCSWRITER (44) that makes
the docs one story, JDK21 (27) and BINFEAS (33). The RC protocol runs after these
close. The historical MassFill/convoy reds are GONE (7/7 stress families green).

## Index (all 53 work packages)

| # | File | Track | Status | Est. |
|---|---|---|---|---|
| 01 | [01-slf4j-migration](01-slf4j-migration.md) | L-0/L-1 | done | 1 dev-day |
| 02 | [02-quiet-by-default-logging](02-quiet-by-default-logging.md) | L-2 | done | 0 |
| 03 | [03-logging-build-guards](03-logging-build-guards.md) | L-3 | done | 0 |
| 04 | [04-jacoco-coverage-baseline](04-jacoco-coverage-baseline.md) | T-1 | done | 0 |
| 05 | [05-tag-based-test-groups](05-tag-based-test-groups.md) | T-2 | done | 1 dev-day |
| 06 | [06-test-gap-undercovered-packages](06-test-gap-undercovered-packages.md) | T-3a | done | 2 dev-days |
| 07 | [07-facade-audit-and-litter-cleanup](07-facade-audit-and-litter-cleanup.md) | T-3b | done | 1 dev-day |
| 08 | [08-read-only-commit-path](08-read-only-commit-path.md) | B2 | done | 1 dev-day |
| 09 | [09-query-range-operators](09-query-range-operators.md) | FP-1a | done | 2 dev-days |
| 10 | [10-query-order-limit-offset](10-query-order-limit-offset.md) | FP-1b | done | 1–2 dev-days |
| 11 | [11-jmh-regression-alert](11-jmh-regression-alert.md) | D-4 | done | 0 |
| 12 | [12-decision-guide-and-example-app](12-decision-guide-and-example-app.md) | FP-11 | done | 1 dev-day |
| 13 | [13-benchmark-suite-unification](13-benchmark-suite-unification.md) | BENCH | done | 2 dev-days |
| 14 | [14-uuid-generator-field-property](14-uuid-generator-field-property.md) | BUG-D | done | 0 |
| 15 | [15-commit-journal-replay-npe](15-commit-journal-replay-npe.md) | BUG-A | done | 2–3 dev-days |
| 16 | [16-id-addressability-after-structure-update](16-id-addressability-after-structure-update.md) | BUG-B | done | 1–2 dev-days |
| 17 | [17-auto-generated-id-reopen-addressability](17-auto-generated-id-reopen-addressability.md) | BUG-C | done | 2–3 dev-days |
| 18 | [18-bug-search-index-key-types-after-reopen](18-bug-search-index-key-types-after-reopen.md) | BUG-E | done | 1–2 dev-days |
| 19 | [19-backup-restore-walkthrough](19-backup-restore-walkthrough.md) | BACKUP | done | 0 |
| 20 | [20-design-point-n50k-validation](20-design-point-n50k-validation.md) | N50K | done | 1–2 dev-days |
| 21 | [21-adoption-docs-contract](21-adoption-docs-contract.md) | DOCSLINT | done | 0 |
| 22 | [22-autosave-cascade-children](22-autosave-cascade-children.md) | AUTOSAVE | done | 2–4 dev-days (spik |
| 23 | [23-power-loss-recovery](23-power-loss-recovery.md) | PWRLOSS | done | 2–3 dev-days (the  |
| 24 | [24-journal-file-naming](24-journal-file-naming.md) | JRNLNAME | done | 0 |
| 25 | [25-explain-query-plan](25-explain-query-plan.md) | EXPLAIN | done | 1–2 dev-days (the  |
| 26 | [26-verify-integrity-walk](26-verify-integrity-walk.md) | VERIFY | done | 1–2 dev-days (the  |
| 27 | [27-jdk21-ci-matrix](27-jdk21-ci-matrix.md) | JDK21 | open | 0 |
| 28 | [28-javadoc-coverage](28-javadoc-coverage.md) | JAVADOC | done | 3–5 dev-days (para |
| 29 | [29-readme-landing-restructure](29-readme-landing-restructure.md) | LANDING | done | 1 dev-day (restruc |
| 30 | [30-benchmarks-context](30-benchmarks-context.md) | BMCONTEXT | done | 0 |
| 31 | [31-write-path-analysis-design](31-write-path-analysis-design.md) | WRTPATH | done | 2–3 sessions (prof |
| 32 | [32-w4-write-path-implementation](32-w4-write-path-implementation.md) | W4 | done | W-4a ~2–3 sessions |
| 33 | [33-binary-format-feasibility](33-binary-format-feasibility.md) | BINFEAS | open | 0 |
| 34 | [34-cache-commit-protocol-audit](34-cache-commit-protocol-audit.md) | CACHE2PC | done | 0 |
| 34 | [34-rollback-only-cause](34-rollback-only-cause.md) | RBCAUSE | done | 1–2 dev-days (the  |
| 35 | [35-crash-residue-purge](35-crash-residue-purge.md) | CRASHPURGE | done | 1–2 dev-days |
| 35 | [35-silent-failure-hazards-codegen-observer-cloner](35-silent-failure-hazards-codegen-observer-cloner.md) | SILENTFAIL | done | 1–1 |
| 36 | [36-structure-update-correctness](36-structure-update-correctness.md) | STRUCTFIX | done | 1 d |
| 36 | [36-zombie-latch-purge](36-zombie-latch-purge.md) | ZOMBIELATCH | done | 1–2 dev-days |
| 37 | [37-yaml-reader-strictness](37-yaml-reader-strictness.md) | YAMLREAD | done | 1–1 |
| 38 | [38-index-resource-contracts](38-index-resource-contracts.md) | IDXCONTRACT | done (implemented; the plan suite + the execution-agreement test green) | 1 d |
| 39 | [39-message-hygiene-dead-params](39-message-hygiene-dead-params.md) | MSGHYGIENE | done | 0 |
| 40 | [40-lost-update-first-committer-wins](40-lost-update-first-committer-wins.md) | LOSTUPD | done | 2-3 d (the semanti |
| 41 | [41-tree-node-file-leak-rollback](41-tree-node-file-leak-rollback.md) | TREEFILE | done | 2–3 d |
| 42 | [42-foreign-keys](42-foreign-keys.md) | ? | done | ? |
| 43 | [43-benchmark-refresh](43-benchmark-refresh.md) | BENCHREFRESH | open | 0 |
| 44 | [44-technical-writer-pass](44-technical-writer-pass.md) | DOCSWRITER | open | 1–2 dev-days |
| 45 | [45-illegal-state-commit-diagnosis](45-illegal-state-commit-diagnosis.md) | RBCOMMIT | done | 1–2 dev-days (the  |
| 46 | [46-wrapper-observer-leak](46-wrapper-observer-leak.md) | WRAPLEAK | done | 1–2 dev-days (the  |
| 47 | [47-record-identity-disarmed-phases](47-record-identity-disarmed-phases.md) | RECIDENT | done (core fixed; the envelope residuals → FLAKY R-STORM-ENVELOPE + 2.2.0 task 10) | 2–4 dev-days + the |
| 48 | [48-checkpoint-on-shutdown](48-checkpoint-on-shutdown.md) | W-4c | done | 0 (06007c9) |
| 49 | [49-w4b-search-key-journal](49-w4b-search-key-journal.md) | W-4b | done | 1–2 d (64d33f5) |
| 50 | [50-fk-cost-benchmark](50-fk-cost-benchmark.md) | FKBENCH | open (Step 0 done: the concurrency axis measured) | 2–3 dev-days |
| 51 | [51-layout-migration-upgrade](51-layout-migration-upgrade.md) | LAYOUTMIG | **open — P0 BLOCKER** | 2–3 dev-days |
| 53 | [53-tree-overlay](53-tree-overlay.md) | TREEOVERLAY | done (2026-11-21: the overlay + the convoy fix; the stress family 7/7, the gate ×3 1321/0f/0e) | 3–4 sessions |

## Provenance (compact history)

- **L-track (01–03):** the SLF4J/logging defaults wave. **T-track (04–07):** coverage,
  grouping, gap-fill, facade audit. **B2/FP-1 (08–10):** the read-path and query pack.
- **Field feedback (2026-10-23):** 29 (landing) → 30 (benchmarks context) — ran before
  new features per the evaluator verdict.
- **W-4 write path:** 31 (design) → 32 (W-4a/c implementation) — the write-scaling wall
  is fixed (docs/perf/w4a-write-scaling.md); W-4b/d are 2.2.0 tasks 05/06's neighbors.
- **BUG-A..E + 45/46/47:** the defect ledger — every entry closed with a pinned test
  (36-zombie's MassFill signature is the train's one open red).
- **Guide-derived (19–21), master-plan pulls (25–27), proposal response (33):** mapped in
  docs/proposals/OPTIMIZATION-PROPSAL-01-ANALYSIS.md and MASTER-PLAN items 11–16.
- **The defect ledger (34–47) + W-4b/c (48/49):** every entry closed with a pinned test.
- **TREEOVERLAY (53, 2026-11-21):** the B+tree per-tx overlay + the total acquisition order destroyed the commit-lock convoy — the stress family 7/7, the gate ×3 1321/0f/0e, FKBENCH t=10 121–158 tx/s (the rationale in the task file + docs/analysis/BTREE_MUTATION_OVERLAY.md + the dbms-developer skill).
- **NUMBERING COLLISION:** 34/35/36 exist twice (the javadoc-campaign ledger 34–41 vs the
  train sequence). Do not renumber — the issues-sync ownership marker is the file path.
  Reference tasks by full file name; 2.2.0 renumbers fresh.

## Conventions (binding for every task)

- A run is judged by Maven's own `BUILD SUCCESS` / `Tests run: N, Failures: M` line — never
  by a piped grep's exit code ([FLAKY.md](../../FLAKY.md), the R-52 gotcha).
- **Never run two maven builds on one `target/`** — concurrent builds destroy each other's
  class files (the 112-error cascade of 2026-10-26 was exactly this, not code).
- Storage name + directory unique per test run; never gate test logic on `deleteDir` success.
- Load-sensitive tests carry `@Tag("stress")` (task 05's contract, lint-enforced) and land
  in FLAKY.md with a rate if they flake.
- User-facing changes update [CHANGELOG.md](../../../CHANGELOG.md) `[Unreleased]`;
  performance claims cite [BENCHMARKS.md](../../BENCHMARKS.md), honestly.


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

**Status format:** `status: open` or `status: done` — and since 2026-10-26 the status may
carry a short **explanation**: `status: done (core fixed; the envelope residuals → FLAKY)`.
The keyword drives the sync; the parenthetical explanation is copied into the issue's
closing comment, so the issue records WHY it closed. Unknown keywords are warned and
skipped (never guessed).

**Content-drift detection:** every created/updated issue body ends with an invisible
ownership marker — `<!-- xdstorage-task: <task-file path> -->`. The sync matches issues by
that marker first (so a title edit in the file updates the issue's title instead of creating
a duplicate), falls back to exact-title matching for legacy issues (whose bodies gain the
marker on the first content update), and compares the stored body against the file's current
body — any edit to a task file reaches its issue on the next push. Labels are add-only at
update (a human-added label is never stripped); titles and bodies sync verbatim. **Keep task
file paths stable**: the marker is the path — renaming a task file orphans its issue by
design.

**Robustness (2026-11-21).** Two hardening layers keep the run alive: (1) **label
auto-ensure** — before anything else, every label found in any task file's frontmatter
is ensured to exist (a new label in a task file can no longer abort the run — the
recurring 2026-11 breakage; the curated block above still gives the well-known labels
their colors); (2) **per-file failure isolation** — each file syncs in its own
function: a gh failure on one file warns, counts as the `sync actions failed` row in
the job summary, and retries on the next push. Only the issue-list fetch aborts the
run loudly (a partial list would create duplicate issues).

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
