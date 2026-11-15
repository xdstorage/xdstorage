# Task files and the issues-sync contract

The task-file system: format, statuses, the sync state machine, and the rules that keep
50+ work packages reconciled with GitHub issues across multiple trains.

## Task file format

Location: `docs/tasks/<train>/NN-name.md` (NN = zero-padded sequence, fresh per train;
never renumber after issues exist).

```markdown
---
title: "[<train>][<TRACK>] <imperative summary>"
labels: [<train>-train, P0|P1|P2, <subsystem>, <type>, ...]
status: open        # open | done — plus an optional explanation: done (core fixed; …)
---

## Summary
One paragraph: what and the acceptance shape.

## Why
The evidence and the demand signal, with links to the plan documents.

## Scope
Numbered, concrete, with real class/package names. Out of scope explicitly listed.

## Acceptance criteria
- [ ] Checkbox criteria — each verifiable (test, benchmark, doc state).

## Verification
```bash
mvn clean test                     # BUILD SUCCESS verdict (never a piped grep)
```

## References
Plan documents, related tasks, commits.

Estimate: ~N dev-days.
```

## The status contract

- `status: open` — the work is pending.
- `status: done` — the work is merged and verified. **An explanatory parenthesis is part
  of the format**: `done (core fixed; the envelope residuals → FLAKY …)`. The keyword
  drives the sync; the explanation is copied into the issue's closing comment.
- Unknown keywords are warned and skipped — never guessed. Absent status defaults to
  `open`.
- The file path is the ownership key: **keep paths stable**, the marker is the path
  (`<!-- xdstorage-task: <path> -->` inside the issue body). Renaming a task file
  orphans its issue by design.
- Titles MUST carry the `[<train>][<TRACK>]` prefix — title matching is the legacy
  fallback and is global across trains; the prefix prevents collisions.

## The sync state machine (scripts/sync-issues.sh)

Ownership match order: **marker-in-body first** (stable across title edits), then exact
title (the legacy fallback). One issue list fetch per run (bodies base64 through
`@tsv`); a failed fetch aborts the run loudly — an empty list would mass-create
duplicates.

| File says | GitHub says | Action |
|---|---|---|
| `open`, no issue | — | create (labels + milestone = the train dir, if it exists) |
| `open`, unchanged | issue open | nothing — in sync |
| `open`, content changed | issue open | update title/body, ADD missing labels (never remove) |
| `open`, content changed | issue closed | update the body + WARN (a machine never reopens) |
| `open` | issue closed, unchanged | WARN only |
| `done`, no issue | — | create, then close (history preserved) |
| `done` | issue open | update (if drifted), then close with the explanation comment |
| `done` | issue closed | nothing — in sync |

- The close comment carries the status explanation: *"…marked `status: done`
  (the developer's completion mark). Completion note: <explanation>"*.
- Labels are **add-only** at update (a human-added label is never stripped).
- Milestones are attached at creation only if the milestone (the train dir name)
  exists.
- A failed `gh` fetch aborts the run loudly — an empty list must never fall through to
  mass-creation.
- `--dry-run` plans every action without touching GitHub.

## Multi-train rules

- The sync scans **every** `docs/tasks/<version>/` directory; the milestone = the
  directory name; the train label `<version>-train` is derived per directory.
- Task titles carry the `[<train>][<TRACK>]` prefix — title matching is global, the
  prefix prevents cross-train collisions.
- A train README (`docs/tasks/<train>/README.md`) is part of the contract: the index
  table (regenerated from the files), the lanes/road maps, the conventions, the
  pointers to MASTER-PLAN and the proposal analyses.

## The workflow commands

```bash
# sync (locally; the workflow runs it on push to main)
export XDSTORAGE_ISSUES_TOKEN=ghp_…      # or: gh auth login
bash scripts/sync-issues.sh --dry-run    # plan only
bash scripts/sync-issues.sh              # execute

# benchmark refresh (task 43 protocol)
BENCH_JDK=/path/to/jdk bash scripts/run-benchmarks.sh full
python3 scripts/render-bench-baseline.py <result-json>
```

## Conventions inherited by every train

- Verdict = Maven's own `BUILD SUCCESS`/`Tests run: N` lines (the R-52 gotcha).
- Unique storage name + dir per test run; never gate on `deleteDir` success.
- Load-sensitive tests carry `@Tag("stress")` AND the naming contract — the pairing is
  lint-enforced (XdStorageTestGroupingContractTest).
- User-facing changes update CHANGELOG `[Unreleased]` at merge time.
- Performance claims cite the canonical baseline; "unchanged" is reported honestly.
- The gate excludes the stress/integration tags; the nightly runs the full suite and
  owns the flake-rate tracking; the RC protocol runs the full suite ×3 before tagging.
