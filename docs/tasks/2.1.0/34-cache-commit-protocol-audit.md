---
title: "[2.1.0][CACHE2PC] Audit the cache commit protocol — phase-1/phase-2 body duplication and the legacy rollback order"
labels: [2.1.0-train, P2, engine, bug]
status: done
---

## Summary

Two findings of the 28-javadoc-coverage campaign (the doc-writing pass reads code with fresh
eyes) inside `XdStorageCacheCommitProtocol` — the two-phase heart of the resource-cache commit:

1. **`prepareCommit` and `performCommit` have near-identical bodies.** The phase-1 loop looks
   copy-pasted into phase 2; the only visible difference is the flag each phase sets. If the
   two phases were designed to diverge (they usually do in a 2PC), the duplication is a defect
   waiting to mislead the next editor; if they are genuinely identical, that itself needs a
   written reason in the class doc.
2. **The legacy no-journal rollback path rolls back in map-iteration order, not mutation
   order.** The documented guarantee (and the journaled path's behavior) is
   reverse-chronology rollback; the legacy `else if (map != null)` branch silently iterates
   whatever order the map holds — a weaker, order-dependent undo.

## Scope

- Investigate the two bodies: diff them, name what phase 2 should do differently, then either
  fix the divergence or delete the duplication (one loop parameterized by the phase flag) —
  with the decision recorded in the class doc.
- Make the legacy rollback path honor the same ordering contract as the journaled path (or
  prove map order is safe here and pin THAT with a test).
- Contract tests: a multi-resource rollback under the legacy path undoes in reverse mutation
  order; a phase-1-then-phase-2 sequence exercises both loops and asserts the flags/order.

## Out of scope

- The journaled path's behavior (already covered by the W-1/W-3 suites);
- `XdStorageCacheRecord`'s duplicate `getState()`/`getRecordTimestamp()` diagnostics
  (R-40-intentional; docs cross-linked in 28) and the mutable `XdStorageCacheObjectChange.object`
  field (caller-synchronized by design, documented in 28).

## Landed (2026-10-25): both findings resolved

**Finding 1 — the duplication is deliberate, now collapsed and documented.** Diffing the
two bodies confirmed they are byte-identical except the record flag and the debug label.
The decision, recorded in the class javadoc: the phases share ONE implementation
(`runCommitPhase(tx, collector, prepare)`), and the DOUBLE FEED into the collector is not
duplication but the refresh mechanism — the second feed carries state that advanced after
the prepare phase (deferred ids landing on unidentified wrappers, records advanced by
`prepareCommit`). `prepareCommit`/`performCommit` are thin wrappers.

**Finding 2 — the legacy map-order path is a foreign-contour fallback, now documented and
pinned.** The branch is reachable only for contours WITHOUT an undo journal; every engine
transaction populates the journal at its first mutation (`undoLog(...).addLast(record)` in
`registerMutation`), so the reverse-chronology path is the live one and map order fires
only for foreign/mock contours that carry no chronology to replay — deterministic order is
unachievable and unnecessary there. The branch comment and the `rollback` javadoc say so.

**Tests added** (`XdStorageCacheCommitProtocolInvariantTest`, 9 → 11):
- `bothPhasesSetFlagsOnEveryRecordInOrder` — two records through both phases: phase 1
  feeds two change objects with no record committed, phase 2 feeds two more (the refresh
  feed pinned), finalization commits both;
- `legacyNoJournalContourStillRollsBackEveryRecord` — the journal peeled manually
  (simulating a foreign contour): the legacy map-order rollback still reverts every
  record to its read state and clears the write-set.

## Acceptance criteria

- [x] The phase-1/phase-2 question is answered: duplication collapsed into
      `runCommitPhase`, decision in the class javadoc.
- [x] Legacy-path contract corrected to match actual behavior (map order, foreign contour
      only) with the reason; the branch pinned by
      `legacyNoJournalContourStillRollsBackEveryRecord`.
- [x] Full suite green ×2.

Estimate: 0.5–1 d.

## References

- [28-javadoc-coverage](28-javadoc-coverage.md) — the campaign that surfaced both findings
- `src/main/java/io/github/xdstorage/resource/cache/XdStorageCacheCommitProtocol.java`
