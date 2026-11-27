# General recommendations — abstract test-engineering principles

These are the **project-portable** recommendations distilled from the XdStorage test
estate. The other three reference files (test-patterns, coverage-methodology,
house-rules) instantiate them for this repository; this file states the principles so
they transfer to any codebase, feature, or suite. When a situation is not covered by
the house-specific files, reason from these.

---

## R1. Exercise real behavior, not mock paths

**Principle.** A test that verifies how your mocks were called verifies your mocks.
Persistence, transactions, veto/rejection semantics, and anything with an ordering
component must run against the real backing implementation. Mocks are for state
machines and pure in-memory units only.

**Why.** The single largest source of broken, disabled, and misleading tests in this
repo was mock infrastructure (null factories, missing resources-manager wiring): the
tests passed or failed for reasons unrelated to the feature. Eight tests sat disabled
for months because their mock harness could not represent the real flow.

**How to apply.** Before writing a test, classify it: does it exercise a *life cycle*
(save → commit → reload → verify, or request → handler → persisted state)? Then it
needs the real implementation, even if that is slower. Reserve mocks for components
with no I/O and no ordering (a state machine, a pure diff, a calculator). If a
mock-based test can only pass by encoding the real flow into the mock's stubs, that
is the signal to switch to real infrastructure.

## R2. The verdict is the tool's own verdict

**Principle.** Judge a test run by the build/test tool's own summary line — never by
a piped grep's exit code, a script's exit status, or a remembered count.

**Why.** Derived signals invert silently: a grep finds no match (→ exit 1) when the
build fails so early that no summary was printed; a `tail` hides the failure block; a
green count from yesterday describes yesterday's tree. One 112-error "code" failure
was two concurrent builds destroying each other's output directories — only the
tool's own per-test lines revealed the real shape.

**How to apply.** Read the summary line (`BUILD SUCCESS`, `Tests run: N, Failures: M,
Errors: K, Skipped: S`) from the run you just executed. Report all four numbers, in
that order, every time. If you filter output for readability, filter for *reading* —
the verdict must still come from the summary.

## R3. Isolate every run: storage, directories, build slots

**Principle.** Each test run gets fresh, uniquely named resources, and no two builds
ever share a working directory.

**Why.** Fixed resource names meet stale state from a previous run (an IDE holding a
file lock, a crashed run's leftovers) and the test then asserts against garbage — the
failure points at the wrong code. Shared build directories are worse: the failure is
nondeterministic and looks like a code regression.

**How to apply.** Unique names from a UUID or timestamp per run; temp directories per
run; a strict one-build-per-output-directory rule (parallel sessions included —
coordinate, don't share). Cleanup is best-effort: never gate test *logic* on whether
deletion succeeded.

## R4. Cover the combination matrix, not a happy path

**Principle.** When behavior depends on N orthogonal configuration dimensions, the
test estate must cover the cross product — or an explicitly documented, prioritized
subset of it.

**Why.** Bugs live at combinations: a path that works for indexed entities breaks for
file-based ones; a feature tested on one index type silently diverges on the other.
The 4-policies × 2-indexes matrix here found divergence that no single-configuration
suite would have surfaced.

**How to apply.** Enumerate the dimensions (storage policy, index type, wire format,
locale, whatever the feature branches on), generate the matrix mechanically (same
skeleton, parameterized fixtures), and keep every cell identical in scenario content —
only the configuration differs. If the full product is too large, slice it
deliberately and record the unsliced cells as gaps, not as "covered".

## R5. Decompose scenarios into boundary pairs

**Principle.** Every behavioral rule yields at least a success case and a veto
(rejection) case; every multi-object operation yields a mixed case. Fixed naming for
these keeps the matrix auditable.

**Why.** Suites that only test successes cannot distinguish "the rule works" from
"the rule is never evaluated". The mixed case (delete one child + keep another +
insert a third in one transaction) is where diffing, staging, and resolution bugs
hide — it is always the hardest and the most valuable test.

**How to apply.** For each rule, write: the success path, the violation path, the
partial/mixed path. Name them systematically (`<Operation><Scenario><Outcome>`), and
give every test in a generated class the same skeleton so a missing cell is visible
by counting.

## R6. One test, one behavior; tests are independent

**Principle.** Each test asserts exactly one behavior and builds its own world. No
test may depend on another test's side effects, execution order, or shared mutable
fixtures.

**Why.** Multi-behavior tests fail ambiguously (which of the three things broke?),
and order-dependent tests produce flakes that vanish in isolation — the most
expensive failure class to diagnose.

**How to apply.** Fresh entities, fresh transaction, fresh storage per test (per the
R3 name uniqueness). If a test body contains two commits covering two rules, it is
two tests. Shared setup is allowed only for immutable, per-class fixtures.

## R7. Disabled tests are ledgered debt, never silence

**Principle.** A skipped test must carry a written reason, live in a documented
inventory, and be counted in every report — as skipped, explicitly.

**Why.** Silent exclusion converts an unknown into a number that looks like health.
The estate here is trusted precisely because "Skipped: 8" is a *documented* 8 — each
one has a reason and a fix pattern; any skip outside the list is a blocker.

**How to apply.** Every `@Disabled`/`skip` carries a reason string naming the root
cause. A report lists: total, passing, failed, skipped — with the skipped itemized
against the inventory. The fix pattern for infrastructure-disabled tests goes in the
inventory too, so the debt is actionable.

## R8. The release gate is N consecutive green runs

**Principle.** "Green" for release purposes means N consecutive fully-green runs of
the relevant suite (here: ×3), each judged by R2's verdict line, with skips matching
the documented inventory exactly.

**Why.** Concurrency defects, ordering flakes, and build collisions are
probabilistic; one green run proves the code can work, three prove it reliably does.

**How to apply.** Fix the command sequence in the skill/report (same suite, same
flags, all N runs), run them back-to-back on a quiet machine, and record each run's
summary. Any failure that is not in the disabled inventory blocks the release —
diagnose it (R9) before re-running.

## R9. Hunt bugs by reading tests backwards

**Principle.** An existing test is a machine-readable promise. To audit a feature or
validate a bug report: find the test that exercises the path, read what it *assumes*,
then walk the real code path and diff assumption against implementation.

**Why.** The failure often isn't in the asserted line — it's in an assumption two
steps earlier (an annotation the fixture doesn't carry, an ordering the test implies,
a configuration the harness doesn't set). Reading the code path against the test's
assumptions names the divergence precisely.

**How to apply.** The walk: test → fixture/annotations → entry point → each layer's
contract → the asserted line. At each layer ask "does the test's setup actually
satisfy this layer's precondition?" The first "no" is the bug (or the test's defect —
both are findings; record which).

## R10. Flakes get isolated, tagged, and ledgered — never ignored

**Principle.** A load-sensitive or ordering-sensitive test is explicitly tagged so
gates can exclude it deliberately, and its rate and cause live in a flake ledger.
"Passed on re-run" is not a diagnosis.

**Why.** Untagged flakes poison every gate decision (is main broken, or is it the
known flake?), and unledgered known-flakes get re-diagnosed from scratch each time.

**How to apply.** Tag tests that depend on timing/load/thread counts. Re-run any
failure in isolation first (R3/R2) before diagnosing code. When a flake is
characterized, write it down: name, trigger, rate, and the exclusion decision. An
excluded-by-decision test has an owner task that owns its return.

## R11. Coverage reports answer the asked question, in a fixed shape

**Principle.** A coverage answer is: the counts, the matrix (✅/❌ per cell), the
disabled inventory, the known gaps, and a one-line verdict — in that order, every
time.

**Why.** Free-form coverage answers drift toward "looks thorough" instead of naming
what is untested. The fixed shape makes gaps comparable across reports and makes the
verdict auditable against the matrix above it.

**How to apply.** Counts from the live run (R2); matrix from the scenario inventory
(R4/R5); gaps stated as *which cell is missing and why*; verdict one line ("green
×3", "112/112 green, 2 cells uncovered"). Never pad the report with prose that the
matrix already says.

## R12. Update the skill when reality moves

**Principle.** Numbers, counts, paths, and API names inside a skill are claims about
the repository. Before relying on one, verify it against the tree; after a wave that
changes one, update the skill in the same sitting.

**Why.** A skill that says "~957 tests" in a 1.2k-test repo misleads every future
session that trusts it — stale skills are worse than no skill, because they carry
authority.

**How to apply.** Prefer invariants ("take the live count") over snapshots in skill
text. When a snapshot is unavoidable, date it. Every time you check a skill claim
against the repo, that check either confirmed it (do nothing) or found drift (fix it
now, in this sitting).

## R13. Green on one OS is green on one OS

**Principle.** A suite that runs on several CI legs must be written against the file
system and the JVM the test can SEE, not against the habits of the OS it was written
on: the separator is `Path`'s business, a lock is the OS's business, and a thread is
identified by what it ran, not by its name.

**Why.** The XdStorage suite went from green to 25 red the first time the same
commit ran on Windows (2026-11-27) — with no engine defect among them. Three test
habits, each invisible on Linux: copying or dumping the whole folder of an OPEN
storage, lock file included (POSIX locks are advisory and the read succeeds; Windows
refuses it); spelling `/` as the separator in a `contains`/`split`/expected string;
and judging every thread of the JVM by a name prefix that is not unique per instance.
The class order differs between OSes, too, which turns "another test left something
open" into an order-dependent failure.

**How to apply.** Spell expectations as paths (`Paths.get(...)`), or normalize
(`toString().replace('\\', '/')`) before a string comparison. Never read a locked
file from a test fixture: copy around it, through the repo's helper. Ask a thread
about itself from inside the task it ran. And when a leg on another OS goes red,
reproduce the mechanism at home before fixing — an emulation of the OS semantics
(for the mandatory lock: a held file made unreadable) turns "Windows only" into a
red/green pair you can actually run.

---

**Cross-references:** the XdStorage-specific instantiations —
[test-patterns.md](test-patterns.md) (the FK-6 matrix, annotation gotchas),
[coverage-methodology.md](coverage-methodology.md) (the report recipe),
[house-rules.md](house-rules.md) (the repo-specific gotchas and env facts).
The requirements source for coverage audits: `docs/analysis/FOREIGN_KEYS.md` and the
task specs under `docs/tasks/`.
