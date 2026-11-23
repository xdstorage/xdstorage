# FK write path design — killing the O(relations) cost (FKWRITE, task 21)

The profile: async-profiler 4.4 `ctimer` collapsed on `cascadeSave` fk_on,
childrenPerParent=10, parents=10,000 (**100,000 FK pairs**), JMH steady state —
docs/perf/profile-2026-11-23/fk-write-100k-collapsed.txt.

## 1. The attribution — corrected on the stack level (2026-11-23, the same day)

The design round's FIRST read attributed the cost to the commit write-out of
`XdStorageBTreeIndexResource` (the heads-file full write). The stack-level
decomposition of the same profile **re-attributed it**: the leaf-frame buckets
(`BTreeNode.read` 25.7%, `LinkedList.toArray`+`node` 42.3%) serve TWO call sites,
and the bucket-level view blurred them. The stack split:

| Path (the full stacks) | Samples (of 3157) | Share | What it is |
|---|---|---|---|
| **`XdStorageBTree.find`** (under `registerForeignKey` / `readByChildObjectId`) | **1449** | **45.9%** | the FK tree LOOKUPS per FK operation |
| **`performFirstPhaseCommit`** (the heads-file write-out) | 526 | 16.7% | the commit persistence of the tree-head records |
| under `XdStorageBTreeNode.read` (inclusive, mostly inside find) | 1744 | 55.2% | the node deserialization (the `LinkedList` content walks) |

**The named O(relations) step (the real one):** `XdStorageBTree.findPhysical` had a
dedicated branch for the MULTI trees (the FK trees are multi — the duplicates):
instead of the vertical descent it ran `this.read(...)` — the **full horizontal walk
of the ENTIRE leaf chain from firstLeaf**, filtering `compareMixedKeys(key) == 0`
("сплиты разрывают дубликаты между ветками — запрещаем вертикальный спуск"). Every
FK operation pays O(all pairs): `registerForeignKey`'s duplicate veto + the reverse
`readByChildObjectId` → 2+ full scans per `cascadeSave` — the measured 23–160×
degradation and the 5.3–5.7× per 10× relations. The commit write-out of the heads
file (a handful of `XdStorageBTree` head records — the node records live in separate
per-node resources) is REAL but secondary (16.7%).

**The method-level lesson (→ the java-profiler skill):** the leaf-bucket (exclusive)
attribution cannot separate the call sites when a hot frame serves multiple paths —
the stack-level decomposition decides WHERE the cost lives before any fix design.

## 2. Why this shipped in 2.1.0/2.1.1

The FK feature (task 42) built the FK pair trees as multi B+trees; the full-scan
branch was the engine's pre-existing answer to the duplicate-scatter fear. The FK
A/B (task 50) measured the cost only in 2.2.0's design round — the shipped lines
carry it silently.

## 3. The fix — the keyed descent (implemented 2026-11-23)

**Delete the multi interception in `findPhysical`** and let the multi finds ride the
existing unique-descent machinery (`root.find` + the R-54 retry loop), with ONE
routing correction in `XdStorageBTreeNode.find`:

- the router rule is **the first separator `>= key` → the LEFT child** (the landing =
  the leftmost subtree that can hold the key): the strict `> key` rule routed
  `==`-separators right and landed PAST duplicates living left of an `==` separator —
  the implemented mutations place the separator key on BOTH sides (the split leaves
  the left half's last key == the separator; the borrow moves the separator key into
  the left leaf), so the left landing is the only sound choice
  (XdStorageBTreeMultipleSplitStressTest reproduced it: 4 of 6 duplicates found);
- the landing leaf starts the **leaf-chain walk with the early stop at the first
  key `> key`** — the chain is sorted (the invariant `readRange`'s early termination
  already relies on), so all `==key` pairs are consecutive and nothing beyond the
  stop can match; the multi-guard in `XdStorageBTreeNode.find` (a matching leaf
  continues the walk) keeps the transitional forms covered;
- the chain walk crosses any `==key` boundary in both directions, so the landing
  never needs to guess the split direction — **O(log n + d) per find**, the linear
  term gone by construction;
- the overlay merge (`mergeOverlay` in `find()`) and the R-54 retry/backoff contour
  are unchanged — `findPhysical`'s return contract is the same physical-match list.

**The effect:** every FK operation's lookups drop from O(all pairs) to
O(log + d). The FK reads (`fkLookup`, the reverse traversal) accelerate by the same
token. The secondary commit write-out (16.7%) is **left as-is in this pass** — the
heads file stays small; the node-record journal (the original Option A) is the
follow-up candidate only if the re-measure still shows the commit term inside the
band's way.

## 4. The invariants the fix must preserve (the veto contract)

- the three veto rules fire exactly as today — the vetoes' `find` results are the
  same sets (the descent + the chain walk collect the same pairs; pinned by
  MultipleSplitStressTest routing-symmetry, the duplicate-invariant suite, the FK
  estate);
- the deferred-id/manual-id contracts unchanged (the id resolution does not ride
  the FK-tree finds);
- the read-your-own-read of the staged FK pairs (the write-set overlay) unchanged
  — `mergeOverlay` sits above `findPhysical`;
- the R-54 find retry/backoff discipline applies to the multi finds now too (they
  share the contour).

## 5. The acceptance (mirrors the task-21 scope)

- [x] the re-attribution recorded (this file, § 1 — replacing the first read's
      commit-write-out attribution; the original is kept in the task file's design
      round record);
- [x] the implementation (the branch removal + the `<=` routing) behind the existing
      semantics — no new flag;
- [x] the gates: btree + properties 1361/0f/0e, the FK estate 377/0f/0e ×2;
- [x] the FK A/B re-measured (docs/perf/fk-ab-postfix-2026-11-23/, the full matrix):
      **the linear term GONE** — cascadeSave 100k rel 12.9→103 ops/s (8.0×), the
      degradation flat 19.7–24.2× (was 22.9–160.3×); **parentUpdate INSIDE the ≤2×
      band at every size** (1.25–1.35×, was 1.4–12.5× linear); fkLookup free. The
      remaining flat constant (the per-op write fanout) = the follow-up round;
- [x] BENCHMARKS § FK A/B + DECISION-GUIDE updated; the CHANGELOG entry — the
      band verdict: the linear claim RESOLVED; the ≤2× on cascadeSave/childDelete
      stays the follow-up (the constant-term round: the WALL/IO profile first).
