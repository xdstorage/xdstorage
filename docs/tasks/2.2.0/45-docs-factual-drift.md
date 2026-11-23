---
title: "[2.2.0][DOCSDRIFT] The factual drift: the fsync cost 8× vs 95× across docs; the lock timeout 5 s (javadoc) vs 100 ms (code); the GUIDE still says the 3 s default"
labels: [2.2.0-train, P2, docs, tech-debt]
status: open
---

## Summary

Three verified drifts, each contradicting another in-repo source:

1. **The fsync cost of `durableWrites=true`.** `XdStorageConfig` javadoc says "measured
   ~8x write cost" (lines 82 and 208) and PRODUCT_REVIEW:259 says "~8× (47 vs 367
   ops/s)" — while DECISION-GUIDE (lines 63, 66, 131, 139) and EMBEDDING:174 say
   "~95× on the canonical shape". Either the shapes differ (then EVERY source must name
   its shape) or one generation of measurements is stale. One canonical number + one
   dated source, everywhere.
2. **The lock acquisition default.** `XdStorageReadWriteLock` javadoc: "Without a
   transaction a default 5 s timeout applies" (line 165); the code: `100L` ms
   (line 172). A 50× doc-vs-code gap on a lock timeout — decide which is INTENDED (the
   100 ms no-transaction default looks like the trap the evaluation flagged; if the
   intent is 5 s, the FIX is code — coordinate with task 01's migration, do not fork
   the behavior here).
3. **The transaction default timeout.** GUIDE.md:372: "default timeout: 3 s" and the
   performance-study section repeats the "3 s default" advice ("raising the default to
   10–30 s is reasonable") — both stale since the task-31 interim raised the default
   to 15 s (2.1.2/2.2.0). The GUIDE line 372 fix is one word; the study section needs
   the reframe.

## Scope

1. The drift sweep method (the repeatable part): for every config constant with a
   default, grep the javadoc + the docs for the stated number; for every CHANGELOG
   §Changed entry, grep the old value's remnants. The sweep's result table lives in
   this task.
2. Each drift → one truth + one canonical source; the javadoc-vs-code conflicts go to
   the CODE owner (the docs describe the code, never the reverse) unless the code is
   the bug (the lock default case — the decision recorded).
3. The GUIDE/EMBEDDING/DECISION-GUIDE/Config alignment pass.

## Out of scope

New documentation structure; the announcement texts (task 11); the lock behavior
change itself (task 01 decides and lands it if the code is the bug).

## Acceptance criteria

- [ ] The sweep table committed (every constant × every source × the verdict).
- [ ] The three named drifts resolved (the grep-proof: the conflicting numbers gone).
- [ ] The lock-default decision recorded (docs-fixed or spun into task 01).

Estimate: ~1 session.

## References

- The external evaluation 2026-11-23, §4.3 (the drift class; their refs GUIDE:899,
  Config:82)
- The verified in-repo sites: XdStorageConfig.java:82,208; PRODUCT_REVIEW.md:259;
  DECISION-GUIDE.md:63/66/131/139; EMBEDDING.md:174; XdStorageReadWriteLock.java:165/172;
  GUIDE.md:372 + the study section
- Task 31 visibility-budget (the 15 s interim whose docs trail this task mops)
- Task 01 consistency-core (the lock-default behavior owner)
