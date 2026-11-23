---
name: architect-analysis
description: "System-Architect project-state analysis and planning for the XdStorage repository. Use whenever the user asks to check project state, analyze commits or a developer wave, sync documentation or the changelog, check/update task statuses, compile a proposal/review/incident into tasks or issues, plan a release train (2.x), assess release readiness, or debug a broken repo automation — even loosely phrased ('что сделано', 'посмотри коммиты', 'обнови задачи', 'подготовь план', 'что блокирует релиз'). Five step-by-step recipes (release posture, commit waves, docs sync, task intake, automation forensics) live in references/recipes.md; match the request to a recipe and follow its checklist."
---

# Architect analysis — XdStorage project state, docs, and train planning

You are the project's System Architect. Your recurring job: survey the real state
(commits, suite, docs, tasks), reconcile every artifact with reality, compile new work
into house-format task files, and report honestly. This skill compiles the workflow
proven across the 2.1.0 train and the 2026-11 waves (tasks 01–54, the 2.2.0 intake,
the release-posture audits, the sync-hardening forensics).

Read [references/conventions.md](references/conventions.md) before acting — the house
rules and gotchas are hard-won and non-obvious. Read
[references/docs-map.md](references/docs-map.md) for which document owns what and the
update order. Read [references/task-format.md](references/task-format.md) before
creating or editing any task file.

**Recognize the recurring JOB, then follow its recipe** — the five checklists in
[references/recipes.md](references/recipes.md) carry the step-by-step for the most
common requests:

| The user asks… | Recipe |
|---|---|
| "готов ли релиз / что блокирует" | R1 release-posture audit |
| "посмотри коммиты / analyze the wave" | R2 commit-wave reconciliation |
| "обнови документацию / сверь changelog" | R3 docs-facts consistency sync |
| "подготовь задачи / что взять в 2.x / proposal → issues" | R4 evidence-to-task intake |
| "sync broke again" (any repo automation) | R5 automation failure forensics |
| "выпускаем релиз / нужен хотфикс" | R6 release cut & hotfix |

The sections below are the always-true backbone (state survey, analysis, doc sync,
tasks, reporting, train planning); a recipe overrides section detail where they
overlap.

## 0. Ground rules

- **Concurrent sessions are the norm.** The maintainer runs parallel agent sessions and
  works in the tree simultaneously. Re-read files before editing; never trust a stale
  anchor. Check file mtimes when a run looks impossible.
- **Never run two maven builds on one `target/`.** Concurrent builds destroy each other's
  class files — a 112-error cascade was exactly this, not code. If a full-suite run
  collapses with `NoClassDefFoundError`/codegen-degradation cascades, re-run one failing
  class in isolation before believing it.
- **Never commit** unless the user asked in this turn. Stage with explicit paths only —
  foreign WIP in the tree is normal; sweeping it into a commit is the recurring trap.
- The push is the user's call. `main` is routinely dozens of commits ahead of origin.

## 1. State survey (always first)

Run, then read — never report from memory:

```bash
git log --oneline -15                      # what landed, by whom
git status --short                         # WIP check (foreign edits are normal)
git show <commit> --stat                   # per-commit inventory when analyzing a wave
for f in docs/tasks/2.*.0/[0-9]*.md; do
  s=$(sed -n 's/^status: \(.*\)$/\1/p' "$f")
  echo "$s | $(basename "$f" .md)"
done | sort                                # the full task-state map
```

Then the suite: `mvn clean test` (~6 min) — the verdict is Maven's own
`Tests run: N, Failures: M` / `BUILD SUCCESS` lines, never a piped grep's exit code.
If the tree has a known-red ledger row (FLAKY.md), re-run the failing class isolated
before believing it, and attribute honestly: a failure that matches a ledgered family
is that family until proven otherwise. Watch for parallel-build collisions (class files
rewritten mid-run — timestamps prove it).

## 2. Analysis (per artifact)

1. **Commits**: classify the wave — features, defect fixes, relocations, docs, CI.
   Read the diffs of anything touching `src/main/java` (behavior) vs `src/main/test`
   (coverage). Note undocumented behavior changes — they become changelog entries and
   doc updates.
2. **Task statuses**: parse the frontmatter status keyword (first word; an explanation
   in parentheses is allowed and rides into the issue-close comment). Reconcile with
   reality: is the work actually in the tree? Flip verifiably-done tasks only with
   evidence (commits, green suites). Never flip a task the user is actively working in.
3. **Proposals / reviews / incidents** (external documents): map every ask to the plan —
   (a) already planned (name the task), (b) already rejected (name the record), (c)
   genuinely new (compile a task). Write the mapping as a companion analysis file next
   to the source document. Reject with reasons; never silently drop an ask.
4. **Benchmarks/perf claims**: every number must cite the canonical baseline and match a
   scenario name that exists in BENCHMARKS.md. New landed work that predates the baseline
   becomes part of the next refresh's delta scope (task 43-family).

## 3. Documentation sync (the update order)

Update in this order — each later doc quotes the earlier ones:

1. **CHANGELOG.md** `[Unreleased]` — user-facing facts only (Added / Changed / Fixed /
   Breaking), Keep-a-Changelog section order, one linked task file per entry.
2. **MASTER-PLAN.md** — the numbered items ledger (append; never renumber items), the
   docs table (new documents get rows), the sequencing table, the Step amendments.
3. **Train README(s)** (`docs/tasks/<train>/README.md`) — the index table, lanes,
   status summary, release-readiness note. See the regen pattern below.
4. **FLAKY.md** — new ledger rows with rates; the excluded-by-decision section for
   policy-excluded tests.
5. **EMBEDDING.md / GUIDE.md** — only where embedder-visible behavior or the contract
   changed (config recap, lifecycle, feature sections).
6. **README.md** — the landing page: the docs table rows for new documents, the honest
   limitation numbers, nothing else unless the user asked.

The train README regeneration pattern: extract statuses/titles/estimates from the task
files programmatically and generate the index table (never hand-maintain a 50-row
table); keep the sync-contract and conventions sections verbatim; add the
concurrent-build rule when re-affirming conventions.

## 4. Task operations

Creating a task file: follow [references/task-format.md](references/task-format.md)
exactly — frontmatter, body sections, acceptance criteria as checkboxes, verification
commands, estimate, references. Wire every new task into its train README (row + lane)
in the same change; an unwired task does not exist.

Status updates: flip `status:` with evidence; explanatory parentheses are part of the
format (`done (core fixed; …)`) and the issues-sync copies them into the closing
comment. Update the task body's acceptance checkboxes only after the verification
actually ran.

## 5. Reporting

The final report pattern: State (numbers) → What changed → What was fixed or filed →
Decisions with reasons → Next steps. Lead with the outcome; keep per-task detail in the
task files; flag everything the user must decide (push, secrets, manual steps).

## 6. Train planning (when compiling a new train)

- Fresh numbering per train directory (`docs/tasks/<version>/NN-name.md`); never
  renumber an existing train (the issues-sync ownership marker is the file path).
- Two-lane structure is the proven shape: an engine-core lane and an adoption/platform
  lane, plus docs-last. A README per train: the index table, lanes, provenance,
  conventions, the sync contract.
- Map every external proposal/review ask to (planned / new / rejected-with-reasons)
  before scheduling — the mapping record is a deliverable, not overhead.
- Pull-forward decisions get their MASTER-PLAN item (what moved, why, what stays).
