---
title: "[2.2.0][FKWRITE] FK write path: kill the O(relations) scan — the measured 23–160× degradation must land inside a stated band"
labels: [2.2.0-train, P1, engine, performance, w4-design]
status: open
---

## Summary

The 2.1.0 FK-cost benchmark (task 50, the canonical JMH A/B) measured the foreign-key
write overhead honestly: **cascadeSave 23–160×, childDelete 12–73×, parentUpdate
1.4–12.5×** vs the same graph with FK off — outside the engine's ≤2× constant-factor
band at EVERY size, and the delta grows **LINEARLY with the FK-tree size** (fk_off stays
flat; fk_on loses 5.3–5.7× per 10× of relations). The linear term means an
O(relations)-per-mutation scan in the FK write path. This task designs and lands the
index-shaped fix so a user enabling foreign keys (default ON) on a write-heavy graph pays
a bounded, published cost.

## Why (the measured evidence)

- BENCHMARKS.md § FK A/B + DECISION-GUIDE carry the table (generated from the task-50 JMH
  JSON; conditions protocol per task 13). The read path is **free (~1.0×)** — the debt is
  write-side only.
- The FK feature is default-ON, so this is not an opt-in cost: every adopter whose model
  has `@XdStorageAutoSave` relations pays it on every save/update/delete of a related
  object.
- The linear scaling makes it WORSE over time: the FK trees grow with the relation count,
  so the same workload degrades as data accumulates — the anti-pattern the W-4 program
  exists to kill (the O(N) → O(delta) lesson of W-4a).

## Scope

1. **The design round first (the house rule):** profile `registerForeignKey` /
   dissolution on a large FK tree — name the O(relations) step (the pair-existence
   checks, the `readBy*` scans, the dedup lookups, the front/reverse tree shape) and
   design the fix: per-pair point lookups instead of scans, the write-set as the dedup
   authority, batched existence checks. The design doc precedes the code and records the
   veto-contract invariants it must preserve.
2. **Implementation** behind the existing semantics (no new flag; `enableForeignKeys`
   stays; the three veto rules and the deferred-id/manual-id contracts unchanged).
3. **The acceptance gate is the FK A/B itself**: re-run task 50's benchmark, regenerate
   BENCHMARKS § FK A/B + DECISION-GUIDE. Target: the relation-write shapes inside a
   stated band — **≤2× is the north star; an honest interim verdict (e.g. ≤4× flat, the
   linear term gone) is acceptable if the design round proves ≤2× needs a persisted-format
   change** — say so in the design doc, never silently.
4. **The correctness estate is unchanged and mandatory:** the FK estate (372+ tests) ×3,
   the diagnostic contract pins (the delete-child-alone veto cycle), MassFill ×2, the
   full quick-gate ×3.

## Out of scope

The FK read path (already ~1.0×); the veto semantics redesign (2.2.0 task 01's
consistency core is the neighbor, not this); B-link trees (evidence-gated, MASTER-PLAN
§ item 20).

## Acceptance criteria

- [x] The design doc: the O(relations) step named with a profile, the fix designed, the
      invariants listed, the band target stated →
      **docs/analysis/FK_WRITE_PATH_DESIGN.md (2026-11-23)**: the step = the
      `XdStorageBTreeIndexResource` snapshot full-file write-out per commit (the
      read-back 25.7% + the LinkedList re-serialization walk 42.3% of the cascadeSave
      profile at 100k pairs; both FK trees per commit); the fix = Option A (the
      node-record commit journal, the W-4a pattern — no format change; Option B the
      hash-resource revisit = 2.3.0); the band = the linear term gone by construction
      (O(delta)), ≤2× predicted inside reach.
- [ ] The FK A/B re-measured and regenerated: the linear term gone (flat in the FK-tree
      size) and the verdict honestly recorded inside or below the stated band.
- [ ] FK estate ×3 + MassFill ×2 + quick-gate ×3 green (BUILD SUCCESS verdicts).
- [ ] BENCHMARKS.md § FK A/B + DECISION-GUIDE updated; CHANGELOG Changed entry.

Estimate: ~2–3 sessions (design round + implementation + the re-measure).

## References

- Task 50 (the FKBENCH A/B — the evidence and the benchmark gate)
- BENCHMARKS.md § FK A/B; DECISION-GUIDE § numbers
- [FOREIGN_KEYS.md](../../analysis/FOREIGN_KEYS.md) §4 (the write-set) — the dedup
  authority the fix builds on
- W4-DESIGN.md (the O(N) → O(delta) pattern this follows)


---

## PROGRESS (2026-11-23): the design round COMPLETE — the implementation is the next session(s)

**The profile evidence** (asprof 4.4 ctimer on cascadeSave fk_on, 100k pairs, the JMH
steady state — docs/perf/profile-2026-11-23/fk-write-100k-collapsed.txt): the exclusive
leaves = `BTreeNode.read` 25.7% (the FK tree nodes read back FROM DISK during the
commit), `LinkedList.toArray`+`node` 42.3% (the re-serialization walk over every
node's keys/objects lists), the YAML emit + the atomic-file write ≈ 8%, the B+tree
logic itself thin. **The named step: the `XdStorageBTreeIndexResource` snapshot
full-file write-out per commit** — O(every node record) read-back + re-serialization
per transaction touching any FK pair, both FK trees (front + reverse) per commit.

**The cross-check from the code** (`XdStorageBTreeIndexResource.performFirstPhaseCommit`):
`cache.readUnsafe(transaction)` (touches/loads every node record) → `writeObjects`
(the full re-serialization + the atomic rename). The shipped 2.1.x lines carry it
(verified in v2.1.1's source).

**The design**: Option A (recommended) — the node-record commit journal for
XdStorageBTreeIndexResource (the W-4a pattern: the delta appends per commit, the
checkpoint folds, the prepare replays; no format change); Option B (the FK pairs on
the journaled hash resource) = the 2.3.0 revisit (the format migration). The band:
the linear term gone by construction (O(delta)); ≤2× predicted in reach.

**The next steps (the implementation sessions):**
1. The journal write path in XdStorageBTreeIndexResource (the phase-1 delta appends +
   the checkpoint fold + the prepare replay — mirroring XdStorageHashIndexResource's
   W-4a implementation);
2. The tests: the replay equivalence, the torn tail, the checkpoint fold (the W-1/W-4a
   patterns) + the FK estates ×3 (the FK trees' journals exercised exactly);
3. The FK A/B re-run (the task-50 benchmark) + the tables regeneration (the task-43
   discipline: the old-rev-today attribution for any moved share).

## PROGRESS 2 (2026-11-23, the same day): the attribution CORRECTED — the real O(relations) is the FIND, not the commit

**The stack-level re-read of the same profile re-attributed the cost.** The leaf-frame
buckets (`BTreeNode.read` 25.7%, `LinkedList.toArray`+`node` 42.3%) serve TWO call
sites; the stack decomposition split them:

- **`XdStorageBTree.find` — 45.9%** (1449/3157): under `registerForeignKey` and
  `readByChildObjectId` — the FK tree LOOKUPS;
- **`performFirstPhaseCommit` — 16.7%** (526/3157): the heads-file write-out (the
  resource persists only the few `XdStorageBTree` HEAD records — the node records
  live in separate per-node resources; the first read's "snapshot of node records"
  premise was wrong for this file).

**The real O(relations) step:** `findPhysical`'s dedicated MULTI branch ran
`this.read(...)` — the full horizontal walk of the ENTIRE leaf chain from firstLeaf,
filtering `== key` ("сплиты разрывают дубликаты — запрещаем вертикальный спуск").
Every FK operation paid O(all pairs): the duplicate veto + the reverse read → 2+ full
scans per cascadeSave — the 23–160× and the 5.3–5.7× per 10× relations.

**The method-level lesson** (→ the java-profiler skill): the leaf-bucket attribution
cannot separate the call sites when a hot frame serves multiple paths — the
stack-level decomposition decides where the cost lives BEFORE the fix design. The
first read designed a journal for the wrong (secondary) path.

## PROGRESS 3 (2026-11-23): THE FIX LANDED — the keyed descent

**The change (two hunks):**
1. `XdStorageBTree.findPhysical`: the MULTI interception branch DELETED — the multi
   finds ride the existing unique-descent machinery (`root.find` + the R-54
   retry/backoff contour) + the leaf-chain walk with the early stop.
2. `XdStorageBTreeNode.find`: the router rule `> key` → **`>= key` → the LEFT child**
   (the landing = the leftmost subtree that can hold the key). The strict rule landed
   PAST duplicates left of an `==` separator (the mutations place the separator key on
   both sides: the split leaves the left half's last key == the separator; the borrow
   moves the separator key into the left leaf) — `MultipleSplitStressTest` reproduced
   it (4 of 6 duplicates found). With the `<=` landing + the sorted-chain walk (the
   early stop at the first key `> key`; the multi-guard continues past a matching
   leaf) the landing direction never matters: O(log + d) per find, the linear term
   gone by construction.

**The gates (all GREEN):**
- [x] btree + properties: **1361/0f/0e** (the 9-failure round after the branch removal
      alone = the routing-symmetry pin catching the strict-rule landing — the `<=`
      fix resolved all);
- [x] the FK estate: **377/0f/0e ×2** (the vetoes, the cascades, the FK concurrency);
- [x] the design doc corrected (docs/analysis/FK_WRITE_PATH_DESIGN.md § 1 — the
      re-attribution; § 3 — the implemented fix);
- [x] the FK A/B re-run (the task-50 benchmark, the full matrix) — **DONE**
      (docs/perf/fk-ab-postfix-2026-11-23/fk-ab.json; the same-day same-protocol
      re-run vs the 2026-11-21-074706 set — the task-43 attribution: the deltas are
      CODE, not drift):
      - cascadeSave c=10 @100k rel: **12.9 → 103 ops/s = 8.0× faster**; the
        degradation **160.3× → 19.7×**;
      - the degradation now FLAT at every size (cascadeSave 19.7–24.2×, childDelete
        12.4–19.1×) — **the O(relations) term is GONE**;
      - **parentUpdate INSIDE the ≤2× band at every size** (1.25–1.35×; was
        1.4–12.5× linear);
      - fkLookup still free (0.82–1.00×);
      - the remaining flat constant = the per-op FK write fanout (the node/head file
        writes + the 2PC per cascade) — the WALL-time cost the CPU profile
        underrepresents (the commit term was 17% CPU; the IO waits don't show as
        CPU).
- [x] BENCHMARKS § FK A/B + DECISION-GUIDE updated (the new table + the honest
      reading); the CHANGELOG entry below.

**The band verdict (the honest scope statement):** the task's NAMESAKE — the
O(relations) scan — is KILLED and proven by the flat degradation curve. The ≤2×
north star on cascadeSave/childDelete is NOT met by this pass: the remaining gap is
the CONSTANT per-op write fanout, a different mechanism (the linear-scan work is
done; the constant-term lever needs the WALL/IO profile first — the CPU profile
cannot rank it). **The task stays open with the re-scoped remainder: the
constant-term round** (the WALL/IO profile → the node-write consolidation or the
journal — the original Option A returns as the lever for the CONSTANT, not the
linear term). Option B unchanged (2.3.0 revisit).

**The CHANGELOG entry (§Fixed, 2.2.0):**
`Fixed: the FK/multi-index lookups no longer scan the whole tree — the keyed
descent O(log + d) replaces the full leaf-chain walk per lookup; the FK write
degradation is flat in the tree size (cascadeSave at 100k relations 8× faster,
160× → 20×; parentUpdate inside the ≤2× band at every size).`

**The post-benchmark note (2026-11-23, the same session):** the benchmark set was
taken on the OVERFLOWED tree structure (the duplicate-range terminal leaves grew
unbounded — task 33, fixed in the same session). The constant-term follow-up round
measures on the now-healthy structure; the FKBENCH re-baseline rides the next
benchmark run.
