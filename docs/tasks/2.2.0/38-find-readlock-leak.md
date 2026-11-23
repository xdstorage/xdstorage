---
title: "[2.2.0][LKLEAK] The B+tree find leaks the tree read latch when the descent throws before the coupling point (R-35)"
labels: [2.2.0-train, P1, engine, bug]
status: open
---

## Summary

`XdStorageBTree.find` (the comment at lines 1497–1500, marked **R-35 open**): the read
latch is released by the node descent itself at the coupling point (lock-coupling), and
an explicit outer try/finally is PROHIBITED there — it would double-release (the
R56SCAN-era IllegalMonitorState lesson). The known hole: a descent that throws BEFORE
reaching the coupling point leaks the tree read latch. Every later writer then waits
out its lock budget; with the 100 ms no-transaction default the damage surfaces fast.

## Why

- A leaked read latch is a whole-tree availability event triggered by ONE exception in
  one descent — the exception classes are real (corrupt page, CCE on a mixed-key tree,
  the metrics-path failures).
- The fix is structural, not mechanical: the code cannot "just add finally" — the
  latch-ownership discipline itself must express the release-on-throw.

## Scope

1. The deterministic repro: a descent throw before the coupling point (the injected
   failure seam or the corrupt-node path); assert the tree is still writable after the
   caught exception (red-before: the lock budget exhausts).
2. The design options, decided with evidence: (a) the node returns/releases through a
   held-latch obligation on the exceptional path; (b) a per-thread held-latch counter
   with a release-on-unwind guard; (c) restructure the entry so the pre-coupling phase
   cannot throw. The double-release prohibition (the R56SCAN comment) constrains all
   three.
3. The audit: the OTHER lock-coupled entries (findPhysical, the task-23 walk, the
   task-32 delete chain walk) get the same before-coupling-throw review — the result
   recorded here, the fixes spun if they share the hole.
4. The gates: the repro green, the btree estate ×3, the invariant suite, the
   quick-gate ×3.

## Out of scope

The lock-primitive replacement (task 01 owns it — this task's fix must survive the 01
migration or be folded into it, the design notes says which).

## Acceptance criteria

- [ ] The repro red-before / green-after; the tree provably writable post-exception.
- [ ] The design decision recorded (which option, why the others lost).
- [ ] The sibling-entry audit result recorded.
- [ ] The btree estate ×3 + the invariant suite + the quick-gate ×3 green.

Estimate: ~1–2 sessions.

## References

- The external evaluation 2026-11-23, §3.3.5 (the same comment, their line refs 1445–1448)
- `XdStorageBTree.java:1494–1531` — the find entry, the R-35/R-36/R-54 comment block
- Task 23 R56SCAN (the double-release lesson that constrains the fix)
- Task 01 consistency-core (the lock-primitive migration neighbor)
