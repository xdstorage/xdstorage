# House conventions — XdStorage architect analysis

Hard-won rules from the 2.1.0/2.2.0 trains. Each exists because something broke.

## Verification and verdicts

- A suite run is judged by **Maven's own** `Tests run: N, Failures: M` +
  `BUILD SUCCESS`/`BUILD FAILURE` lines. Never by a piped grep's exit code (the R-52
  gotcha: a failed build masqueraded as success until launch).
- Re-run a failing test class **in isolation** before diagnosing: full-suite reds are
  frequently contention or ordering artifacts, not code (the S4 daemon-flag failure was
  MassFill's shadow; the 112-error cascade was a concurrent build).
- Storage name + directory must be **unique per test run** (the R-50 stale-dir trap:
  `File.delete` legally fails on IDE-held files → the storage parses stale disk state →
  confusing first-save failures). Never gate test logic on `deleteDir` success.
- **Never run two maven builds on one `target/`.** Concurrent builds delete/replace class
  files mid-run — the 2026-10-26 112-error cascade was exactly this (NoClassDefFoundError
  for nested classes + a wrapper-codegen degradation cascade). Timestamps prove it.
- The engine log sink (`xdstorage.log`) is **append-only across surefire forks** — verify
  log-based fixes in a clean tail window, not from the file head.

## Honest numbers

- Every performance number cites **BENCHMARKS.md § Canonical baseline** and a scenario
  name that exists in the generated tables. Never hand-type a baseline table.
- Deltas carry error bars and a same-protocol comparison; report "unchanged" honestly
  (the W-3 precedent: the journal was ≈neutral on the index-heavy shape, and the doc said
  so). A regression >10–15% on a scenario blocks the release notes' performance claims
  until explained or fixed.
- JMH trials that fail are **silently skipped** — count the rows in the final table
  (gotcha #1). The nightly asserts the row count for exactly this reason.
- New default flips (config defaults) follow the **soak-then-flip** protocol: flag
  default-off → soak (full suite green with the flag ON) → flip with data. A flip without
  a green soak is forbidden (the W-3 rule).

## Design and scope discipline

- **Design round before code** for persisted-state semantics (W-4-class changes): the
  design doc first, kill-9/recovery tests, honest verdicts. Task 32/31 are the template.
- **Rejected alternatives are deliverables**: every design decision records the rejected
  options with reasons (the alternatives tables in W4-DESIGN / MASTER-PLAN). An ask that
  maps to a rejected alternative gets the rejection cited, not a silent decline.
- **Proposals/reviews map to (planned / new / rejected-with-reasons)** before scheduling —
  write the mapping as a companion analysis file next to the source document (the
  OPTIMIZATION-PROPSAL-01-ANALYSIS.md pattern).
- **Scope discipline on trains**: each external ask is worth doing when there is one
  concrete demand signal (a GitHub issue, an embedder, the maintainer). The
  frozen-items inventory (MASTER-PLAN § frozen) exists to prevent speculative growth —
  a thaw requires its stated condition, not enthusiasm.
- **SQL/server/multi-process stay out of scope** unless a dedicated go/no-go design task
  decides otherwise (the SERVER feasibility task pattern: decision first, never code).

## Task-file discipline

- Frontmatter `status:` is **keyword + optional explanation**
  (`done (core fixed; the envelope residuals → …)`). The keyword (open|done,
  case-insensitive) drives sync-issues.sh; the explanation rides into the issue's
  closing comment.
- A task file with `status: done` **without the work in the tree** is a false record —
  flip with evidence (commits, green runs), or leave it and say what remains.
- **Never renumber or rename a task file** after its issue exists: the issues-sync
  ownership marker is the file path (`<!-- xdstorage-task: <path> -->`); renames orphan
  issues. Numbering collisions between sequences are documented, not fixed.
- Issue numbers are shared with PRs and manually-created issues — never assume
  issue-number == task-number.

## Commits

- Commit only what the turn asked; **stage with explicit paths** — the tree routinely
  holds foreign WIP (the maintainer's own edits) and `.zcodeignore` (which has been
  swept into commits three times and amended out each time).
- Commit messages: house style — a subject line naming the task, a body with the
  evidence (before/after numbers, the repro, the rejected attempts). The dffaf9b / 64d33f5
  commits are the templates.
- The push is the user's call. `main` is routinely dozens of commits ahead of origin;
  the issues-sync workflow runs on push and reconciles the issues.

## Known environment facts (2026-11)

- Benchmarks run on: AMD Ryzen 9 5900X (12C/24T), 64 GB RAM, NVMe SSD (970 EVO Plus),
  ext4, Ubuntu 20.04, kernel 5.15. The durability figures (~107× fsync) are
  NVMe-ext4-specific — an HDD shifts them materially.
- The suite is ~957+ tests; `quick-gate` excludes the five documented-flaky families by
  name; `release` excludes by `@Tag("stress")/("integration")` with filename backstops;
  the nightly runs the full suite ×3 (minus the two decision-excluded tests via the
  surefire `!` exclusion).
- `gh` CLI is not installed on the local workstation — issues-sync runs on push; local
  script checks are syntax + logic-harness only.
- `docs/tasks/discovery.md` is the maintainer's discovery/planning input — new trains
  are compiled FROM it, and the file gets inline status annotations after compilation.
