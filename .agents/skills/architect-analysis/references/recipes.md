# Recipes — the recurring architect jobs as checklists

Five recipes covering the jobs this skill exists for. Each is self-contained: trigger →
inputs → steps → deliverable → traps. The steps name the exact commands; the numbers
inside are PATTERN placeholders — take live counts from the artifacts you are reading,
never from this file. House rules ([conventions.md](conventions.md)) apply everywhere;
each recipe lists only its extra traps.

---

## R1. Release-posture audit — "что блокирует релиз?"

**Trigger:** "готов ли релиз", "что осталось до тега", "критичные задачи".

**Inputs:** the task-state sweep (SKILL §1), FLAKY.md, RELEASE-PLAN §2 (the RC protocol's
requirements), the train README's open-work table.

**Steps:**
1. Sweep statuses (`grep -L "^status: done" docs/tasks/<train>/[0-9]*.md`) and the train
   README's open table — **cross-check both**: the README is regenerated and drifts
   (the real case: "COMPLETE 52/52, 0 open" above a table listing done tasks).
2. For each open task classify: **BLOCKS TAG** (the release-contract class: migration/
   compatibility, the RC protocol's inputs), **RC INPUT** (feeds the protocol: benchmark
   refresh, docs pass), **MEASUREMENT-ONLY** (never a blocker), **POST-RELEASE** (the
   next train's ledger).
3. Verify every "blocks" claim against the tree — a P0 label is a claim, not a fact
   (the LAYOUTMIG case: blocker by triage, and separately verified landed).
4. Build the verdict table: | item | blocks tag? | why |. One row per open item —
   "no" with a reason is as important as "yes".
5. Surface the **decision forks** explicitly: items with (a)-fix-now vs (b)-document-
   and-defer options get both options + a recommendation + "your call" (the
   lost-update-window pattern). A fork the user hasn't decided is an open item, not a
   hidden default.
6. Close with the RC-protocol delta: what remains between the current state and "run
   RC now" (usually: the RC inputs + the protocol itself).

**Deliverable:** the verdict table + the fork list + the RC delta.

**Traps:** a done task left in an open-table (README drift); a `status: in-progress`-
style UNKNOWN keyword (sync-issues warns and skips — flip it to `done (…)` or `open`);
a known-red ledger row quoted as a blocker without re-checking whether the family was
since fixed (the B3/BTreeStress case: RESOLVED by the convoy fix, row needed an update).

---

## R2. Commit-wave reconciliation — "посмотри коммиты / что сделал разработчик"

**Trigger:** "analyze the commits", "developer wave", "check the last commit(s)".

**Inputs:** `git log --oneline -N`, `git show <c> --stat` per commit, the touched task
files, the docs the commits claim to update.

**Steps:**
1. Classify the wave per commit: feature / defect fix / relocation / docs / CI / bench.
   Read `--stat` first; read the diff only where the message makes a behavior claim.
2. **Verify claims against the tree** — a "landed" message is a hypothesis: the real
   case was "TREEOVERLAY landed" where the engine lived INSIDE existing classes (no new
   package) — only the tests were in the new package. Grep the named symbols.
3. **Diff the claims vs the ledgers:** for each commit touching docs/tasks — did the
   status flip match the work? For each commit touching docs/ — did every quoting
   surface get the fact (R3 takes over here)?
4. Collect the **undocumented behavior changes** (src/main diffs with no changelog row)
   and the **stale statuses** (the task says in-progress/done wrongly) — these are the
   reconciliation deliverable.
5. Report: the wave arc (what the sequence of commits tells), verified vs claimed, the
   drift list, what to fix now.

**Deliverable:** the wave analysis + the drift/fix list.

**Traps:** rebases rewrite history mid-session (the reflog showed tips moving under me —
re-check `git log` when a file vanishes); a branch switch (main vs feature) changes what
exists — check `git branch --show-current` FIRST; commit messages accumulate — the last
message of a squash chain is the record, earlier ones vanish.

---

## R3. Docs-facts consistency sync — "обнови документацию / сверь changelog"

**Trigger:** "update the docs", "sync the changelog", "что нового для пользователя".

**Inputs:** the fact ledger = task files (done, with explanations) + commit inventory +
BENCHMARKS.md (numbers) + FLAKY.md (fixes).

**Steps:**
1. Build the **fact ledger** first: every user-visible change as one line — feature /
   fix / perf (with the number + its source) / contract change / breaking. Facts from
   the ledgers only; never from memory.
2. For each fact, walk the **quoting surfaces** (docs-map.md's update order) and mark
   present/missing. The recurring gaps: a contract discovered late (the JavaBeans
   setter rule) missing from GUIDE+EMBEDDING; a fixed defect family missing from
   CHANGELOG §Fixed; a measured cost missing from DECISION-GUIDE.
3. Update in the docs-map order: CHANGELOG → task/plan docs → GUIDE/EMBEDDING →
   README. Every number cites its source surface; no invented numbers (the FK-cost
   rule: quote none until the benchmark lands).
4. **Honest-cost placement:** a measured cost (the FK 23–160×, the −24% regression)
   goes where the adopter sees it BEFORE adopting (DECISION-GUIDE/landing), not buried
   in the benchmark appendix. The one-regression row stays visible — the honesty is
   the positioning.
5. Contract-style discoveries (a silent-data-loss trap) go to BOTH the reference (GUIDE)
   and the operational contract (EMBEDDING) — one surface is not enough.
6. Link-check every new link (`../../`-depth from `docs/tasks/<train>/` is the recurring
   miss) before finishing.

**Deliverable:** the updated surfaces + the "verified no gap" list for the rest.

**Traps:** parallel sessions updated BENCHMARKS/DECISION-GUIDE already — diff before
writing (stale anchors); the CHANGELOG carries facts, prose edits only — never let the
writer pass change numbers; a "planned" section header left over from draft-phase
CHANGELOG entries (the `### Added (planned)` artifact) needs the "(planned)" swept once
the train completes.

---

## R4. Evidence-to-task intake + train reconciliation — "что взять в 2.x? подготовь задачи"

**Trigger:** "prepare issues", "дополни план", "что в 2.2.0", a proposal/review/incident
document, a measured debt with no owner task.

**Inputs:** MASTER-PLAN (the items ledger, the frozen inventory), the train READMEs,
the measurement records (BENCHMARKS, task ledgers), the source document if external.

**Steps:**
1. Build the **coverage matrix** for the question "what does the plan already say": for
   each candidate item — already-planned (name the task) / already-rejected (name the
   record, MASTER-PLAN §4) / closed-as-a-decision (recorded in a MASTER-PLAN item, NOT
   filed — the B3-closure pattern) / genuinely missing → file.
2. **File only items with a demand signal or a measurement** — a measured debt (the FK
   O(relations) scan) is a signal; a hunch is not (the frozen-inventory rule).
3. Task shape for measured debts: the **measurement IS the acceptance gate** (the FK
   task's gate = re-run task 50's benchmark; the band target stated; "an honest
   interim verdict is acceptable if ≤2× needs a format change — say so, never
   silently"). Decision forks inside the task get their own numbered option list with
   a pin requirement (the read-snapshot's collection-getter bypass).
4. Follow [task-format.md](task-format.md) exactly; wire into the train README the same
   change; the new-train numbering is fresh (never renumber an existing train).
5. Reconcile the plan documents: MASTER-PLAN item appended (never renumbered), the
   stale rows updated (Bug-E "open" → fixed), duplicated blocks deleted (a real case:
   items 14/15/16 twice). A reconciliation without a deletion pass is half-done.
6. For external documents: the mapping record is a deliverable next to the source
   (planned / new / rejected-with-reasons) — every ask accounted for.

**Deliverable:** the filed task files + the README rows + the MASTER-PLAN item + the
mapping record (for external docs).

**Traps:** filing a task the maintainer explicitly decided against (check the decision
records first); filing a task whose owner already exists under a different number
(the 34/35/36 numbering-collision lesson — reference by full file name); superseded
tasks left live (the 2.2.0 indexes-subdirectory task vs 2.1.0's LAYOUTMIG — mark
superseded, never double-announce/double-plan).

---

## R5. Automation failure forensics — "sync broke again"

**Trigger:** a repo automation fails (issues-sync, a build script, a workflow).

**Inputs:** the error (or the user's description of it), the script, the data it parses.

**Steps:**
1. **Reproduce the parser locally first** — run the exact extraction command against
   the actual data (`sed -n 's/^title: …/p'` against the real task file). The two real
   breakages were BOTH blamed on the wrong suspect (the task-53 title) until the
   parser was run: the real cause was non-ensured LABELS × `set -euo pipefail`.
2. Classify: parse-layer (frontmatter regex, keywords) vs GitHub-API layer (labels,
   milestones, token) vs environment (gh missing, branch state). The API layer is
   provable statically when the data is local: does `gh issue create --label X`
   reference an entity the script never ensured?
3. Fix **structurally, not point-wise**: the recurring-failure class gets a mechanism
   (auto-derive labels from the data files — the drift-proof net; per-file error
   isolation so one bad file can't kill the run), not another entry in a manual list.
4. **Prove with a fake-gh sandbox** (gh is not installed locally): a stub script
   logging its calls + failing selectively (`create` fails on a name containing
   FAILER) + 3–4 scenario files (valid open, done-explanation, the failure case, the
   invalid input). Assert: the run completes, the counters are right, the failure was
   WARNed not fatal. The sandbox is ~30 lines and reusable.
5. Document the hardening in the consumer-facing README section (the train README's
   sync contract) and in the memory — the next session must not re-diagnose.

**Deliverable:** the structural fix + the sandbox proof + the doc note.

**Traps:** `set -e` + a failing command mid-list kills the run before the interesting
part — the reported "last file processed" is the ABORT SITE, not the cause; `cond &&
action` is safe under set -e (the failure is inside the && list) — do not "fix" that
pattern; `gh` availability differs local vs CI — test logic locally, live-verify on
push.
