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

- [ ] The design doc: the O(relations) step named with a profile, the fix designed, the
      invariants listed, the band target stated.
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
