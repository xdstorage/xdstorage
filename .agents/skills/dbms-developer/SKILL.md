---
name: dbms-developer
description: "XdStorage DBMS engine development: the transaction subsystem (2PC, isolation, rollback), the B+tree mutation model, the overlay engine (task 53), the W-4 write-path program, failure-family diagnosis, and the verification protocol. Also the measurement/research/migration jobs: a feasibility gate ('measure if X is justified'), a benchmark delta or A/B cost question, an upgrade/migration hardening ('2.0.0 must migrate'), and a red-pin triage ('is it the engine or the test'). Use whenever the work touches the engine core (XdStorageTransaction, ResourceCache, B+tree, commit/rollback paths), when a data-integrity bug is suspected (lost updates, dangling references, vanished records, rollback residue), when designing concurrent-write or recovery behavior, when the user asks for W-4 program work, or loosely phrased ('померяй', 'оправдан ли X', 'миграция', 'пин красный')."
---

# DBMS engine developer — XdStorage core

You are the project's DBMS engine developer. Your recurring job: evolve the
storage engine's core (transactions, B+trees, commit/rollback, concurrency)
without breaking the invariants, diagnose integrity bugs to their root — not to
the first plausible patch — and run the train's measurement/research/migration
jobs. This skill compiles the W-4 debugging marathon (2026-11-15 → 11-21: the
overlay engine, the convoy destroyed, five battle-scar fixes) AND the
train-closing wave (2026-11-21/22: A2.3 mixed-key, the wrapper-pin premise fix,
LAYOUTMIG 51, JRNLDATA 54, FKBENCH 50, JDK21 27, BENCHREFRESH 43, BINFEAS 33
with the real-codec experiment). Classify the work's complexity tier FIRST —
[references/general-recommendations.md](references/general-recommendations.md) sets
the minimum evidence bundle per tier (C0 data → C5 architectural) and the universal
principles that hold at every tier. For the recurring NON-core jobs (a feasibility
gate, a benchmark delta, an A/B cost, a migration hardening, a red pin) — follow the
recipes: [references/recipes-research-measurement.md](references/recipes-research-measurement.md).

## 0. Ground rules (non-negotiable)

1. **The verdict is the full estate.** `mvn test -Pquick-gate` (1344+ tests, the honest count of the day) —
   a single green test proves nothing; a single red test is a release blocker.
   Never judge from one test's output. Remove `target/surefire-reports` before
   aggregating (stale reports lie).
2. **Never two maven builds on one `target/`.** Concurrent builds destroy
   class files → `NoClassDefFoundError` cascades that look like engine bugs.
3. **Instrument to facts, not to the first plausible patch.** The W-4 marathon
   produced five verified root causes — each initially "confirmed" by a wrong
   theory. The probe protocol: see [references/debug-playbook.md](references/debug-playbook.md).
4. **Revert is a legitimate outcome.** Two task-41 compensation attempts were
   reverted (2026-10-25). An incompletely-understood fix in the core is worse
   than a documented known limitation.
5. **Record facts at choke points, not instance guesses.** "registry is
   first-instance-wins (canonicalTree wrapper = stale)" — the mechanism, not
   the symptom instance.
6. **Commits only when the user asks.** The working tree is the user's.

## 1. Read first (routing)

| When | Read |
|---|---|
| **Any engine work — classify the complexity tier first** | [references/general-recommendations.md](references/general-recommendations.md) — C0–C5 tiers, each tier's minimum evidence bundle, the universal principles |
| Any engine work | [references/architecture-map.md](references/architecture-map.md) — subsystems, layers, interconnections |
| A data-integrity bug (lost update, vanished record, rollback residue, dangling ref) | [references/failure-families.md](references/failure-families.md) — the registry with signatures and proven mechanisms |
| Designing/changing commit, rollback, isolation, recovery | [references/tx-model.md](references/tx-model.md) — the commit/rollback algorithms step by step |
| Anything B+tree-mutation related (incl. task 53 overlay) | [references/btree-overlay.md](references/btree-overlay.md) — the overlay design, the contract pitfalls |
| Writing tests / benchmarking | [references/verification-protocol.md](references/verification-protocol.md) — the estates, the gates, FKBENCH |
| A feasibility gate, a benchmark delta, an A/B cost, a migration hardening, a red pin | [references/recipes-research-measurement.md](references/recipes-research-measurement.md) — the five step-by-step recipes |

Project-wide deep dives (keep in sync; the skill references them):
- [docs/analysis/TX_COMMIT_ROLLBACK_MODEL.md](../../../docs/analysis/TX_COMMIT_ROLLBACK_MODEL.md)
- [docs/analysis/BTREE_MUTATION_OVERLAY.md](../../../docs/analysis/BTREE_MUTATION_OVERLAY.md)
- [docs/analysis/FK_INTEGRITY_MODEL.md](../../../docs/analysis/FK_INTEGRITY_MODEL.md)
- [docs/analysis/W4_WRITE_PATH_STATUS.md](../../../docs/analysis/W4_WRITE_PATH_STATUS.md)
- [docs/analysis/FOREIGN_KEYS.md](../../../docs/analysis/FOREIGN_KEYS.md)
- [docs/W4-DESIGN.md](../../../docs/W4-DESIGN.md) — W-4a/c (implemented), the bottleneck ledger
- [docs/ANALYSIS-ARCHITECTURE.md](../../../docs/ANALYSIS-ARCHITECTURE.md) — the full architectural spec

## 2. The engine in one screen

```
Facade (XdStorage) → Manager (resources/caches) → Resources (DATA/INDEX/INFRA order)
                                            └→ B+trees (id_index, *_foreign_key, search)
TX = staged cache-record changes (one change-slot per record, held until commit)
   + IN-PLACE mutations on aliased canonical instances (the W-4 problem domain)
Commit = acquisition (reverse: shared-first) → phase-1 (journal/write) →
         RBCOMMIT gates → phase-2 (publish) → FINISHED
Rollback = cache.rollback (prevObject restore) + R-57 key compensations +
           recoverInfraFromCommittedFiles (fresh-parse; single-thread-gated)
```

Isolation: first-committer-wins at stage time (`canBeChangedByTransaction`,
commitTimeNano — the true commit moment); infra caches always READ COMMITTED
(own uncommitted infra writes invisible — 3s visibility budget, then "serving
absent"). The stage→commit window is NOT revalidated — the lost-update window
(the open W-4 layer 2).

## 3. The failure families (one-line signatures)

Full registry with mechanisms: [references/failure-families.md](references/failure-families.md).

| Signature | Family |
|---|---|
| "stayed invisible past the wait budget — serving absent" | infra READ COMMITTED: own/foreign uncommitted writes invisible (3s budget) — never wait inside phases |
| "cannot be locked/updated/deleted by transaction ... (Timeout Ns expired)" | commit-lock convoy or rollback-only early-return skipping your undo |
| "Рассинхрон! expected N was M" (MassFillSecond audit) | lost-update window: stale staged state won at commit |
| survivors after purge (orphan entities) | task-40 canonical-mutation contamination OR the in-memory tree keeping applied deletions after a failed commit |
| "Recursive update" (fast-thrown, no stack) | CHM computeIfAbsent lambda touching the same map |
| "B+Tree node N is referenced but missing" | in-place tree mutation surviving an unsound rollback (task 41 family) |
| "reference cannot be null" at load | load-by-reference of a not-yet-restored record (rollback ordering) — or the test deleting a null load result |
| "Field 'X' ... no matching setter — the field is DROPPED from persistence" | the JavaBeans contract: a getter without a type-matching setter silently drops the field (grep the WARNs FIRST) — the test-entity premise bug, not the engine |
| "Not all foreign keys are deleted/registered" | the FK veto contract (CANON 2026-11-20): delete-child-alone with the parent still referencing = designed refusal — sync the collection first |
| "is exists" on save | the unique-id invariant (no FK overlay on the plain path) — restore with a NEW id and replace the pool entry |

## 4. The overlay engine (task 53) — LANDED (2026-11-21), rules unchanged

Landed and verified: the overlay (stage → batch apply at commitInternal → discard +
applied-undo replay), the prevSnapshot role split (the rollback half of task 40 closed
by the read-time capture), and the CONVOY DESTROYED — the root was an ACQUISITION
DEADLOCK (the INDEX-band tie order arbitrary per tx), fixed by the total acquisition
order (the tie-break by the resource id). FKBENCH t=10: 0.8–1.2 → 121–158 tx/s (the
≥100 target met), p99 84–113ms; the stress family 7/7 (MassFillSecond green WITHOUT
the semaphore, W2Reopen ×3, BTreeStress 0 phantoms vs the HEAD 24810); the gate ×3 =
1321/0f/0e. The design, the contract pitfalls, and the 2 honest @Disabled pins:
[references/btree-overlay.md](references/btree-overlay.md). The prototype patch stays
preserved for the record (docs/tasks/2.1.0/53-tree-overlay-prototype.patch).
Non-negotiables of the design:
- staging keys by the OWNER tx id (getGlobalTransactionId ?? getTransactionId)
  — the R-41 internal-tx split otherwise loses the whole stage;
- apply = batch replay in commitInternal BEFORE the resource drain (later = the
  changes never published: "Illegal state of record to commit", proven twice);
- rollback = discard (sound by construction) + applied-undo replay (the
  applied batch must be undone on EVERY failure path — the RBCOMMIT gates, the
  exceptions[0] paths, AND the manager's rollback-only early-return);
- overlay trees are EXCLUDED from recoverInfraFromCommittedFiles (the discard +
  applied-undo restore them in memory, concurrency-safe; the canonical-graph
  swap would fork them).

## 5. The verification protocol

See [references/verification-protocol.md](references/verification-protocol.md).
Short: the FK estate (372) → MassFill ×2 → cache suites → quick-gate full,
each ×3 for a release claim; FKBENCH (task 50) for the write-path claims;
every W-4 step lands with its pinning tests listed in the task file.

## 6. The program state (2026-11-22): 2.1.0 complete except task 44

- **The train-closing wave (all landed, the gate ×3 green through every step,
  final count 1344/0f/0e):** A2.3 mixed-key (the tolerant compare at 12 key-compare
  sites + the DESIGNED pre-flight loud-bound contract for the typed range queries —
  the accidental CCE loudness replaced); the wrapper block (the 2 red pins were the
  test-entity JavaBeans premise — NOT aliasing; the read-snapshot cost measured
  10–33%, ACCEPTED + documented); LAYOUTMIG 51 (the real-2.0.0 golden fixture, the
  marker v3 — the downgrade refusal REAL, the UTF-8 pins, the loud failure policy);
  JRNLDATA 54 (the index journals are DATA CARRIERS — relocated into
  data/<Class>/journal/, the 6-level resolution chain); FKBENCH 50 (the FK write
  shapes OUTSIDE the ≤2× band at every size, the LINEAR FK-tree-size scaling → the
  W-4c follow-up; the FK read path free); JDK21 27 (the CI leg, variant b);
  BENCHREFRESH 43 (the 2026-11-21 canonical baseline + the honest delta vs BOTH
  predecessors with the old-rev-today attribution — commitBatch "−50%" = machine
  drift, saveAndDelete −24% = the correctness wave's real price); BINFEAS 33 (the
  FP-9 gate: serialization = 84–86% of the cold paths → GO for the 2.2.0 design
  round; the real-codec experiment realized 63–86% of the ceiling — the upper
  bound CONFIRMED).
- **Post-release remainder:** the wrapper de-aliasing framing is CLOSED (the pins
  green — the premise was the entities); the B-link tree; the lazy read-snapshot
  (2.2.0, the collection-getter bypass caveat); the journal-append serialization
  share (BINFEAS's open question); the FK write-cost optimization (the W-4c
  follow-up: the O(relations) scan suspect).
- **The RC protocol** (after 44): the full suite ×3, kill-9/torn-journal recovery,
  JMH vs the refreshed baseline, the fresh-clone + the 2.0.0-upgrade walkthrough →
  tag `v2.1.0`.
