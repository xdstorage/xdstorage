# 33. OVERFLOW: terminal leaves of duplicate ranges grew unbounded (no split in the chain branch)

**Priority:** P2 (structure correctness + the write-path cost; the pre-existing debt
behind the two @Disabled duplicate invariants)
**Status:** RESOLVED (2026-11-23, the same session; the full structural gates green)
**Found by:** the DUPRACE Invariant-5 test (the occupancy gate) + the throwaway
chain-dump probe; the debt was known as «Известная проблема с переполнением ноды»
(the @Disabled annotations on Инварианты 1/3 in XdStorageBTreeDuplicateInvariantTest)

## Summary

The multi-index duplicate inserts let a leaf grow past 2t−1 WITHOUT BOUND: the probe
(30+30 shuffled pairs, t=5) showed a leaf of 27 keys (and one of 13) right after the
insert phase — the maximum is 9. Every insert INTO the key's duplicate chain range
walked the chain branch to the terminal leaf; the terminal leaf received
`getKeys().add(key)` with **no split check at all** and kept growing with the series
length.

## Why (the mechanism)

The ONLY split check lived in `insert()`'s leaf frame — on the **landing leaf**
(`if (this.getKeys().size() >= 2*T) split...`). The chain branch in
`insertIntoThisNode` mutates **nextLeaf** (recursively), so the landing leaf's size
stays unchanged while the terminal leaf overflows. No code path ever checked the
terminal.

The overflow was also an ACCIDENTAL contention-avoidance mechanism: zero splits on
the hot duplicate ranges = zero deep-lock churn — which is why the concurrent
duplicate-insert test passed pre-fix and burned its retry budget the moment the
splits started firing honestly.

## The fix

1. **The split on the mutated node** (`XdStorageBTreeNode.insertIntoThisNode`, the
   chain branch): after `nextLeaf.insertIntoThisNode(...)` returns true, check
   `nextLeaf.getKeys().size() >= 2*T` and split — **the receiver AND the argument are
   `nextLeaf`** (`nextLeaf.splitAndInsertIntoParent(nextLeaf, ...)`): the split
   methods operate on `this`; the deepLockedNode parameter only registers locks. The
   deep lock is already held (`tryDeepLockForInsert` captured nextLeaf + its
   ancestors — the split's parent machinery is safe). A deeper hop covers itself at
   its own frame: when the insert landed deeper, this frame's nextLeaf is unchanged
   and the check is a no-op.
2. **The insert retry budget: the R-54 pattern ported** (`XdStorageBTree.doInsert`):
   with honest splits firing, the parallel storm on the hot duplicate keys burned the
   old ATTEMPT budget (REBALANCE_RETRY_BUDGET=100) the same way the finds did before
   R-54 (each re-descent re-acquires the tree write lock and loses to the next deep
   lock holder). The attempt counter + the every-5th `wait(50)` replaced by the
   DEADLINE (3/4 of the transaction's timeout, floored at 500 ms, capped at 10 s —
   the R-54 formulas) + the exponential backoff 1→1000 ms.

**The trap recorded (the first attempt's own bug):** the split call used the LANDING
leaf as the receiver (`splitAndInsertIntoParent(nextLeaf, ...)` on `this`) — the
methods split `this`, so it spurious-split UNDERFULL landing leaves, degraded the
routers and caused exactly the retry storm it was meant to fix (the budget
exhaustions in a synchronized burst). The receiver==target rule (the mirror of
insert()'s `split(this, ...)`) resolved both the correctness and the storm.

## The gates

- [x] Инвариант 5: the FULL structural gate restored (verifyMultiset/deepScan — the
      occupancy, the balance, children==keys+1, the chain parity/sort) — GREEN;
- [x] **Инварианты 1 and 3 UN-DISABLED** (the «переполнение ноды» debt retired): the
      duplicate-series splits/joins scan and the 20×9 full purge — GREEN;
- [x] the concurrent duplicate insert/delete storm — GREEN (no budget exhaustions);
- [x] btree + properties + the FK estate: 483/0f/0e ×2 rounds; MassFill First+Second
      GREEN.

## The impact

- The B+tree leaf capacity invariant now holds for the duplicate-heavy multi trees
  (the FK pair trees, the search indexes): the leaves stay ≤ 2t−1 regardless of the
  series length — the serialization/deserialization cost per leaf access is bounded;
- the fat-leaf reads were part of the FK write-path constant term (task 21) — the
  follow-up round measures on the now-healthy structure;
- the task-50 FKBENCH numbers (2026-11-23) were taken on the OVERFLOWED structure —
  the next benchmark round re-baselines.

## Out of scope

- The delete-path budgets (REBALANCE_RETRY_BUDGET attempt-based at :1052/:1169/:1353)
  — the same R-54 port candidate, but the delete storms were not reported; left
  as-is (disclosed).
