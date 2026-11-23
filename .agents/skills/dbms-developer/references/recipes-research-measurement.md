# Recipes: research, measurement, migration, and pin triage

The five recurring NON-engine-core jobs this skill's author ran to close the 2.1.0
train (2026-11-21/22, tasks 50/43/51/54/27/33 + the pin triage). Each recipe is a
checklist with the traps that cost real debugging cycles. They complement
[architect-analysis](../../../.agents/skills/architect-analysis/SKILL.md) (the
project-STATE recipes) — these are the project-MEASUREMENT and project-CHANGE recipes.

---

## R-A. The feasibility gate — "профилирование оправдывает X?"

For every external/internal claim of the form "we should build X, it will be N×
faster/better" that a plan has gated behind "if profiling justifies".

1. **Pin the claim and the already-known data** — read the proposal, the PERF
   studies, the baselines. Never measure blind; never re-measure what a §-section
   already states.
2. **Translate the vague claim into a MEASURABLE upper-bound question.** A gate
   needs the CEILING, not precision: the win ≤ the share of time the change
   eliminates. Formula: `speedup ≤ 1/(1 − share)` per shape. If the eliminated
   share is 15%, a "5–10×" claim dies without building anything.
3. **Find the seam before writing the harness.** Look for the SPI/interface where a
   measuring DECORATOR can wrap the layer the change targets (reflection into a
   private field is harness-legal; production stays untouched). The decorator
   granularity = what the change actually replaces (the whole layer, not a slice).
4. **Cross-check with an independent floor.** A raw-bytes read of the same files
   (the IO floor) validates the split: serialization vs IO vs the engine residual.
5. **Disclose the state that flatters the bound.** Page-cache-warm files maximize
   the parse share (conservative for NO-GO); disk-cold shrinks it. Medians over
   rounds; the canonical conditions (WARN logging, the manifest, the same box).
6. **The verdict with the per-scenario numbers**, then — if GO — the ceiling table
   becomes the design round's baseline expectations.
7. **OPTIONALLY (the strongest form): build the cheap real thing and measure the
   realized %** (the BinFeas codec experiment: 63–86% of the ceiling realized). The
   realized/ceiling ratio IS the codec's own cost — the model's assumption made
   measurable.

Traps: the journal replay type-checks `instanceof XdStorageYamlIOFactory` (the
codec factory must EXTEND it); journal appends bypass the SPI factory (measure the
checkpoint, disclose the rest); the id lands in `setId()`, NOT the properties (the
YAML readObjectData convention — a properties-based writer writes null ids);
surefire reports cut exception stacks at the test boundary — catch and print live.
Case: task 33 (docs/perf/binfeas-2026-11-22/RESULTS.md — the ceiling AND the
experiment with the comparison table).

---

## R-B. The benchmark delta attribution — "новый прогон vs старый архив"

For any "the new run is N% slower/faster than the archived baseline" claim.

1. **NEVER trust an archived-vs-new delta as a code delta.** The machine state
   drifts between runs (the proven case: commitBatch "−43..55%" was 100% drift).
2. **The decisive experiment: re-run the OLD revision TODAY.**
   `git worktree add /tmp/wt <old-rev>` + `mvn -q test-compile` + the same
   single-fork JMH protocol, then the same at HEAD. The same-day same-box pair
   cancels the drift.
3. **The self-check that validates the method**: does the old rev TODAY reproduce
   its own archive? If YES on shape X → a new-vs-archive gap on X is CODE. If NO →
   the archive number was environment-flattered → the gap is DRIFT. The drift is
   scenario-dependent (the batch/allocation-heavy shapes drift the most).
4. **Classify every moved row**: drift artifact (say so, with the numbers) / real
   code regression (name the suspect layers, quantify the trade) / noise (±10%).
   A >10–15% real regression on a canonical scenario is a release-notes blocker
   until explained or fixed.
5. **The tables are GENERATED** (render-bench-delta.py / render-bench-baseline.py)
   — never hand-typed; the separator row must match the header's column count
   (the GFM renders nothing on a mismatch — the silent killer).
6. **The cascade**: every doc quoting the baseline gets updated in the same sitting
   (DECISION-GUIDE / W4-DESIGN / README / the task file), each with the verdict.
Case: task 43 (the 2026-11-21 baseline + ATTRIBUTION.md beside the result set).

---

## R-C. The one-variable A/B measurement — "сколько стоит X?"

For any cost question answered by an A/B (the flag on/off, the codec A/B, the arm
vs the control).

1. **ONE variable.** The same entities, the same engine, the same protocol; the
   arms differ ONLY in the measured thing. The control arm (no annotations/no
   feature) decomposes: arm_on − arm_off = the feature; arm_off − control = the
   harness overhead (it should be ~0 — if not, the harness is measuring itself).
2. **The integrity gate inside the measurement**: every A/B arm asserts the data
   (field-by-field against the deterministic model, all N). A benchmark that
   cannot prove it read the right data proves nothing.
3. **The cyclic-op shape for destructive ops**: a pure one-shot delete drains the
   pool and kills the trial; use the delete+restore cycle (BOTH arms pay the same
   restore). Respect the CONTRACTS (the FK delete-child-alone veto — the CANON
   2026-11-20 — makes the naive shape refuse loudly; the diagnostic ladder finds
   whether the failure is the engine's contract or your premise).
4. **The diagnostic ladder before believing a failure**: isolate the trigger
   ((a) plain op, (b) the variant, (c) the loaded vs stub reference, ...) and test
   with the GREEN estate's entity pair — if the green pair fails too, it is the
   contract/config, not your entities. The CANON veto lived in
   XdStorageForeignKeyDeletionTest all along — grep the existing suites FIRST.
5. **The dedicated-vs-canonical invocation decision**: a benchmark whose per-trial
   preload is heavy (minutes) must NOT join the nightly smoke set (the shape
   criterion: the row counts frozen) — a dedicated documented invocation instead.
6. **The JMH gotchas**: the JSON scoreError can be NaN; the delete API takes the
   OBJECT instance; the same-id reinsert save = "is exists" (the unique invariant)
   — restore with a NEW id and REPLACE the pool entry (the naive rebuild breaks the
   next cycle); the decorator measures through the SPI seam only (the journal
   appends bypass it — disclose).
Case: task 50 (the FK A/B — 23–160× verdicts + the linear tree-size scaling found
because the matrix held the relations constant at one point).

---

## R-D. (MOSTLY OBSOLETE post-2.1.0: the layout migration exists and is hardened — LAYOUTMIG task 51 + the marker v3; consult only for a FUTURE format/layout change)  The upgrade/migration hardening — "2.0.0 → 2.1.0 должно переехать"

For any on-disk layout/format change that existing user folders must survive.

1. **Verify the REAL legacy layout against the released artifact** (the tag, the
   jar in the local repo, Central) — the task-sketch detection rules are often
   wrong (the 2.0.0 sketch missed that real folders HAVE the marker and have no
   top-level index/).
2. **The golden fixture from the REAL released artifact** (a standalone tools/ maven
   project pinned to the released version): the deterministic dataset, the dirty
   close (the journals carry the data — the real crash-ish state), the Cyrillic
   content (the charset pin), the PROVENANCE stamp asserted in tests
   (libraryVersion in the marker). Open the fixture with a RELATIVE path so the
   persisted payload paths stay relocatable (no takeover in tests, cross-OS).
3. **Idempotent steps + the loud failure policy**: each step checks
   source-exists/dest-absent; a crash mid-migration completes at the next open; an
   unmovable file REFUSES the open (silent continue = the data-loss vector — say
   which one in the comment).
4. **The classification rules stated per file class**: what moves, what stays (and
   WHY — e.g. the fragments stay because the id-index payload points at them; the
   journals never move because the naming chain owns them).
5. **The walkthrough test asserts the FILE TRUTH**: the reopen-after-migration
   checks the disk (the fragment files, the converted marker), not just the API
   results; plus the fragment-0 clobber guard (a save right after the migration
   must not erase the loaded state), the crash matrix (each half-done state
   completes), the idempotency (no file churn across 3 opens), the downgrade
   refusal.
6. **Grepping the run's WARNs is step one** of any migration debugging (the engine
   WARNs exactly what it drops: the JavaBeans-contract field drop killed three
   test fixtures before anyone read the log line).
Case: task 51 (LAYOUTMIG) + task 54 (JRNLDATA: the journals are data carriers —
the premise gate BEFORE the relocation: prove the journal is the sole durable
location of something, or stop).

---

## R-E. The red-pin triage — "пин красный: движок или премиса?"

For any failing/disabled pin whose disable-note blames a mechanism.

1. **Re-enable and run ONE pin; read WHICH assert fails.** The disable-note's
   mechanism is a HYPOTHESIS, not a fact (both W-4 pins' aliasing notes were wrong).
2. **Stash-check against HEAD** (the baseline experiment): identical failure on
   HEAD = pre-existing; different = your wave's regression.
3. **Isolate the trigger with a diagnostic ladder** — (a) the plain op, (b) the
   variant, (c) the reference-loaded-vs-stub, (d) the repeat — and run the GREEN
   estate's entity pair through the same ladder: if the green pair fails too, the
   suspicion moves to the contract/config (the FK veto lived in the deletion test's
   CANON comment).
4. **grep the run's WARN/ERROR lines BEFORE theorizing** — the engine announces
   exactly what it drops/refuses (the JavaBeans field drop, the veto refusals, the
   RECIDENT first-finalize-wins skips). The log-signal taxonomy: a designed refusal
   appears as an ERROR at the refusal site; a premise bug appears as the MISSING
   data downstream.
5. **Fix the test, not the engine** when the engine's documented contract is the
   cause (the setChildren JavaBeans fix — the MassFillSecond pattern); file an
   engine issue ONLY with a proven mechanism.
6. **The premise corrections get recorded** — the wrong disable-note text is
   replaced with the true mechanism, and the diagnostic suite is promoted to a
   permanent pin (it now guards the contract that reshaped the test).
Case: the W-4 wrapper block (the 2 pins green after setChildren; the diag suite
pinned the veto contract).
