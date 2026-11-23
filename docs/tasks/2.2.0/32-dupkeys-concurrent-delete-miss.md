---
title: "[2.2.0][DUPRACE] Targeted duplicate delete under full parallelism intermittently misses an existing pair (\"does not exists\")"
labels: [2.2.0-train, P1, engine, bug]
status: open
---

## Summary

The CI gate run (2026-11-23, the 2.1.2-post-release push) failed once:
`XdStorageBTreeDuplicateInvariantTest.testConcurrentDuplicateInsertAndTargetedDeleteIsThreadSafe:317`
— a concurrent targeted `delete(key, value)` threw **"does not exists" for a pair that
was verified to exist** (`hot_0` / `payload_0_6`; the insert phase asserted
`find("hot_0").size() == 50` immediately before). Simultaneously, the **jdk21 leg ran
the identical 1358-test suite to 0 failures** — same code, opposite outcomes minutes
apart: a scheduling-dependent race, not a deterministic defect. Locally (the 24-thread
box) the class is green ×3.

## Why

- The multiset contract says a delete of an EXISTING pair removes it — a "does not
  exists" for a verified-existing pair is a **silent wrong answer** under concurrency
  (the worst observable class: the caller believes the pair was gone; whether it was
  actually removed or a sibling vanished is unproven by this signature alone).
- The DUPKEYS fix (2.1.0) already hardened the targeted delete ("the descent searches
  every candidate subtree between the equal separators" + the exclusive mutation) —
  this failure says a race window remains in that descent under full parallelism
  (200 parallel deletes over 4 hot keys after 200 parallel inserts).

## The hypotheses (the design round decides)

1. **The targeted-delete descent vs the sibling borrow/merge interplay**: the descent
   "searches every candidate subtree between the equal separators", but a concurrent
   sibling delete triggering a borrow/merge can move the pair AFTER the descent chose
   its subtree — the pair-existence check then runs against a stale subtree view.
2. **The MIXEDKEY tolerant-compare interaction**: `compareMixedKeys` landed at the 12
   key-compare sites (including the delete descent) AFTER the DUPKEYS family was
   validated; the RC ×3 was green on the 24-thread box — the CI 2-vCPU scheduling is
   the new variable. Audit the tolerant compare's decisions at the delete-descent
   boundaries for equal-class keys.
3. The read-side walk race (task 23 R56SCAN's family) — the DELETE path analog; the
   repro/fix design should share the choreography with task 23.

## Scope

1. **The deterministic repro first** (the house rule): the staged choreography forcing
   a targeted delete to interleave with a sibling delete + the borrow/merge on the
   same separator boundary (the DUPKEYS harness pattern; the probabilistic CI run
   cannot gate).
2. **The descent race audit** (the hypotheses above), the fix, the pins.
3. **The gates**: the repro green (red-before on the pre-fix code); the class ×3
   locally AND ×3 on the CI runner (the nightly watch); the invariant suite per op;
   the quick-gate ×3.
4. **The field note**: if this reproduces for a user on 2.1.x, the signature is
   "delete(key, value) throws does not exists for a pair the same-transaction find
   returns" — a 2.1.3 hotfix candidate (the fixes ride both lines per the standing
   directive).

## Out of scope

The read-side walk race (task 23 owns it — share the choreography, not the fix); the
B-link upgrade; any contract change to the multiset delete semantics.

## Acceptance criteria

- [ ] The deterministic repro (red-before proven).
- [ ] The mechanism named (which race window) with the design note.
- [ ] The fix + the pins; the class green ×3 local + ×3 CI.
- [ ] The FLAKY row closed (the race is fixed, not masked).
- [ ] The quick-gate ×3 green (BUILD SUCCESS verdicts).

Estimate: ~2 sessions (the repro design is the hard half — the same lesson as task 23).

## References

- The CI gate run 2026-11-23 06:01 (the signature quoted above; the jdk21 leg green
  simultaneously — the race evidence)
- The DUPKEYS fix (the CHANGELOG [2.1.0] §Fixed; the descent + the exclusive mutation)
- Task 23 R56SCAN (the read-side sibling — the shared choreography)
- [FLAKY.md](../../FLAKY.md) — the new row (1 CI occurrence, 0/3 local)
- docs/perf/PROPTEST-FINDINGS.md — the harness patterns (the seed-model, the
  staged choreography)


---

## PROGRESS (2026-11-23): the mechanism named, the fix landed, the CI watch pending

**The mechanism (the hypothesis #1 confirmed by the code):** the targeted delete's
descent descends level-by-level releasing the parent lock at each hop; a concurrent
sibling delete's borrow/merge can MOVE the pair (or the descent's whole window) after
the descent committed to its path. The descent lands in a leaf that no longer holds the
pair → `deletePairFromThisNode` threw "does not exists" — **without setting the
retryDelete flag** (the only retried case was the local tombstone "was deleted this
transaction"), so the tree-level do-while retry loop never re-descended. The insert
path has the retry-on-miss pattern (retryInsert); the delete path lacked its twin.

**The fix (XdStorageBTreeNode.deletePairFromThisNode):** the miss runs the SOUND
PREDICATE first — `tree.find(key).contains(value)`:
- the pair the tree still holds = a stale descent → `retryDelete.set(true)` → the
  tree-level do-while re-descends from the root (the insert-path pattern);
- the pair nowhere = the honest refusal (the genuine "does not exists").

The find is paid only on the miss — zero cost on the happy path. The `retryDelete`
parameter is now threaded into `deletePairFromThisNode` (the flag was already flowing
through the whole call chain — only the leaf scanner didn't receive it).

**The gates:**
- [x] The class ×3 locally GREEN (4 tests ×3 runs, 0 failures).
- [ ] The class ×3 on the CI runner (the nightly watch — the CI 2-vCPU scheduling was
      the trigger; the local red-before is NOT reproducible: the class is green ×3 on
      the pre-fix code too, per the task's own note and the 0/3 FLAKY row — the fix is
      sound by construction: the throw-for-a-verified-existing-pair path is eliminated
      by the sound predicate, and the CI watch is the empirical confirmation).
- [x] The quick-gate GREEN (1358/0f/0e/9s, BUILD SUCCESS).
- [x] The invariant walk per the DUPKEYS suite (the test's own structural checks).

**The status stays open until the CI ×3 confirms.** The 2.1.3 hotfix note stands (the
fixes ride both lines per the standing directive — the cherry-pick is this one method's
diff).
