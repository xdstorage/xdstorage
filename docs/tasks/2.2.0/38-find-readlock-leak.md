---
title: "[2.2.0][LKLEAK] The B+tree find leaks the tree read latch when the descent throws before the coupling point (R-35)"
labels: [2.2.0-train, P1, engine, bug]
status: done (2026-11-28: the audit — the named hole covered at HEAD (the stale R-35 comment); the structural release-on-unwind delta guard landed at all three tree entries + the pins ×4; full suite 1833/0f/0e)
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

- [x] The repro red-before / green-after — AMENDED by the audit (2026-11-28): red-before NOT producible at HEAD (the piecemeal guards already cover — the probes prove it); the pins lock the observable contract + the structural guard enforces it; the tree provably writable post-exception (×4 pins).
- [x] The design decision recorded (option (b), the delta guard; why (a)/(c) lost — the PROGRESS block).
- [x] The sibling-entry audit result recorded (the per-entry table — the PROGRESS block).
- [x] The btree estate ×3 + the invariant suite + the FULL suite 1833/0f/0e green.

Estimate: ~1–2 sessions.

## References

- The external evaluation 2026-11-23, §3.3.5 (the same comment, their line refs 1445–1448)
- `XdStorageBTree.java:1494–1531` — the find entry, the R-35/R-36/R-54 comment block
- Task 23 R56SCAN (the double-release lesson that constrains the fix)
- Task 01 consistency-core (the lock-primitive migration neighbor)

## PROGRESS (2026-11-28): the audit executed — the named hole is COVERED at HEAD; the fix = the structural guard

### The audit result (scope item 3, per entry)

| Entry | The throw-before-coupling window | The verdict |
|---|---|---|
| `findPhysical` → `node.find` | NOTHING runs between the tree `lockRead` and hop-1's `finally { toUnlock.unlockRead(); }` (only the root-null check with its own unlock) — the window is EMPTY by construction | covered (hop-1's finally is unconditional — even a `tryLockRead` throw releases the tree latch) |
| `tree.read()` (the task-23 epoch walk) → `node.read` | the parity barrier CAN throw at iteration 1 (previous = the tree) and at iteration k (previous = the k−1 node); `current.lockRead()` (timeout) throws outside the inner try | covered by the TRANSFER DISCIPLINE: the outer `finally` releases `previous` (the tree at iteration 1), the inner catch releases `current`; **verified empirically** — the corrupt-first-leaf and corrupt-second-leaf probes leave the tree writable from another thread |
| `tree.restoreSearchKeyTypes` → `node.restoreSearchKeyTypes` | the same walk structure | covered the same way — the probe confirms |
| the task-32 delete chain walk | write locks with the `deepUnlock`-in-finally discipline | a different lock family, covered; no read-latch exposure |

**THE HONEST VERDICT (corrects the intake's premise):** the "throw before the coupling
point leaks the tree read latch" defect is NOT REPRODUCIBLE at HEAD — red-before cannot
be produced even via the corrupt-node path (the deterministic probes:
XdStorageBTreeReadLatchLeakTest, all arms green on the pre-fix code; the writer from a
SECOND thread succeeds — the same thread would mask the leak via the lone-reader
upgrade). The guards grew piecemeal (R-33/R-38/R-59 reworks) and the R-35 "open"
comment went STALE — it described a hole that the reworks had already closed, while
PROHIBITING the honest entry-level try/finally (the double-release concern was real).

**The residual risk that made this task worth landing anyway:** the discipline is
INVISIBLE and UNENFORCED — nothing but scattered finallys prevents the next walk
reshuffle (R-59 already reshaped these methods once) from silently reopening the hole.

### The fix (option (b) of the scope — decided with the evidence)

- `XdStorageReadWriteLock.readHoldCount()` — the current thread's reader count accessor
  (the counters already existed; the map lookup only);
- the release-on-unwind DELTA guard at all three tree entries (`findPhysical`'s retry
  loop, `read()`'s epoch loop, `restoreSearchKeyTypes`): snapshot `holdsBefore` before
  `lockRead`, on unwind release iff `readHoldCount() > holdsBefore` — exact under any
  reentrancy, double-release impossible (the walk consumed its acquire → no-op). The
  stale R-35 comment replaced by the invariant description;
- the option (a) lost: threading a held-latch obligation through every node walk's
  exceptional paths = invasive across many methods, easy to get wrong again; the option
  (c) lost: the walks legitimately do IO before the coupling (cannot be non-throwing).
- **The task-01 compatibility:** the guard uses only the readable hold count — any
  lock primitive with hold accounting (the task-01 migration target) carries it as-is.

### The gates

- [x] the deterministic pins ×4 (XdStorageBTreeReadLatchLeakTest: the corrupt first
      leaf, the corrupt second leaf, the restore walk, the viewer throw after the
      coupling — each asserts the tree stays writable from ANOTHER thread);
- [x] the btree + properties estate ×3 green (rounds silent);
- [x] the FULL suite **1833/0f/0e/7sk BUILD SUCCESS** (DOCSLINT included).
