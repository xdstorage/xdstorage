---
title: "[2.2.0][PARSEALLOC] Cold-path allocation reduction: the YAML scalar machinery and the parse garbage"
labels: [2.2.0-train, P2, engine, performance]
status: open
---

## Summary

The PROFILE ledger (task 14): the cold full load spends **~50% in G1 GC** — the YAML
parse garbage feeds the collector — and **~6–10% in the scalar machinery**:
`regex Pattern.compile/match` (6.1% — the scalar parsing compiles/matches regexes),
`StringLatin1.trim` (2.8%), `UTF_8 decode` (1.4%). The alloc profile confirms: the
cold-load allocations are dominated by the parse strings/buffers.

## The fix sketch

1. Pre-compile the scalar-parsing patterns (static final) or replace with hand-rolled
   character scans (the scalar grammar is small);
2. trim without allocation (`String` slicing where the semantics allow, or the offset+
   length internal representation);
3. feed W-4d: the parse garbage is the allocation-rate evidence the memory train needs.

This task does NOT replace FP-9 (task 33: GO — the binary format eliminates the whole
layer); it is the cheap interim (the text-format improvements ship without the format
migration) AND the fallback if FP-9 slips.

## Acceptance

- [ ] The regex leaves gone from the cold-load profile (the ctimer re-run).
- [ ] The cold full load improves measurably (the GC share drop visible in the ledger
      re-run).
- [ ] The full suite green ×2; no format change (the YAML files stay byte-identical).

Estimate: ~1 day. Evidence: docs/perf/profile-2026-11-23/LEDGER.md § the cold load.
