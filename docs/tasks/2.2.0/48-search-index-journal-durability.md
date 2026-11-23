---
title: "[2.2.0][SRCHJNL] The search-index journal never flushes to disk in any mode — the durability contract + the recovery story"
labels: [2.2.0-train, P2, engine, durability]
status: open
---

## Summary

`XdStorageSearchIndexJournal` appends under its lock but performs NO flush/fsync —
grep finds no flush/sync/force call in the file (only the `synchronized` appendLock).
The main journals honor `durableWrites`; the search-index journal ignores it in every
mode (the evaluation's §3.3.2 tail). The durability CONTRACT is undocumented: what a
power loss actually loses (the buffered tail? the whole journal?) and what the
recovery costs (the index rebuild from the data files — the BUG-E restoration /
`recoverInfraFromCommittedFiles` path — exists, but its trigger and price are not
written down anywhere a user can find).

## Why

- The docs sell `durableWrites=true` as power-loss safety; a reader who trusts that
  for the search index gets a silently weaker guarantee than the data files.
- The recovery story may make the journal REDUNDANT (rebuild-from-data = one source of
  truth) or make the flush CHEAP (append-only, already amortized) — but nobody decided.

## Scope

1. The verification: read the writer chain end to end — what survives a kill (the
   OS-buffered bytes usually do; the JVM-buffered tail does not), what the reopen
   triggers (replay? rebuild? both?), what the rebuild costs at the canonical sizes.
2. The decision matrix, recorded with the rejected alternatives: (a) the journal
   honors `durableWrites`; (b) the journal is DROPPED — the index always rebuilds
   from data (simpler, one source of truth; the startup cost measured); (c) the
   journal stays best-effort and the lossy window is DOCUMENTED as the contract.
3. The implementation per the decision + the EMBEDDING durability-matrix note.
4. The benchmark note: if the flush lands, the 82 ops/s durable shape must not crater
   (the honest before/after row).

## Out of scope

The main journals' durability (task 06 owns the WAL hardening); the index structure
itself.

## Acceptance criteria

- [ ] The writer-chain verification recorded (what a kill loses, what reopen does).
- [ ] The decision matrix recorded; the implementation or the documentation landed.
- [ ] The EMBEDDING durability matrix states the search-index guarantee explicitly.
- [ ] The estate ×3 + the quick-gate ×3 green (+ the benchmark row if the flush lands).

Estimate: ~1 session (the contract) + the implementation per the decision.

## References

- The external evaluation 2026-11-23, §3.3.2 (the never-flushed journal)
- `XdStorageSearchIndexJournal.java` (the append path; no flush call)
- Task 06 journal-wal-txid (the main-journal durability neighbor)
- The BUG-E index restoration + `recoverInfraFromCommittedFiles` (the rebuild path)
