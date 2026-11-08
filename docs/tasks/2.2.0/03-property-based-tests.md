---
title: "[2.2.0][PROPTEST] Property-based tests for the whole engine"
labels: [2.2.0-train, P1, testing]
status: open
---

## Summary

Introduce property-based testing (jqwik for JUnit 5) over the engine's core invariants:
CRUD round-trips under randomized operation mixes, MVCC snapshot isolation under random
concurrent schedules, B+Tree invariants under random insert/delete/balance sequences,
journal replay equivalence (base+journal == base-written-directly), index-vs-data
consistency. Complements the existing example-based suites with generated cases the
hand-written tests never think of.

## Why (discovery § First priority 7)

The stress families found real defects only when load shapes nobody wrote by hand occurred.
Property tests generate those shapes deterministically (shrinking = minimized failing
cases), turning "run the storm and hope" into reproducible minimized failures.

## Scope

1. jqwik dependency + the first properties: (a) randomized CRUD round-trip on all three
   policies; (b) random B+Tree operation sequences hold the invariant suite; (c) journal
   replay equivalence; (d) MVCC snapshot linearizability on a two-thread schedule model.
2. Deterministic seeds recorded per failing property; the quick-gate budget respected
   (property runs are sized to fit; the heavy ones tagged stress).
3. Every shrunk failing case becomes a pinned regression test.

Estimate: ~2–3 sessions to the first meaningful property set.
