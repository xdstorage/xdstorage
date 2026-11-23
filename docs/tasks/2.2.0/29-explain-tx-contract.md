---
title: "[2.2.0][EXPLAINTX] The explain() transaction contract: the only tx-bound facade method that works without a transaction — decide and align"
labels: [2.2.0-train, P2, engine, docs]
status: done
---

## Summary

The property campaign's facade sweep (PROPTEST-FINDINGS.md #5): `explain()` is the ONLY
transaction-bound facade method that silently works on the no-tx path — every other
tx-bound method (load/count/save/delete/...) refuses loudly with
`"method must be executed in transaction"`. One behavior is right; the other is a
contract asymmetry. This task decides and aligns.

## Why

- A contract asymmetry on the facade is a documented-behavior debt: users learn the
  tx discipline from the refusals, and `explain()` quietly breaks the pattern.
- The task-07 facade-audit finding-table format exists exactly for this kind of
  recorded decision.

## Scope

1. **The decision** (the maintainer's ruling recorded in this task + the audit table):
   (a) `explain()` joins the tx refusals (a behavior change — a CHANGELOG Changed entry
   + a release-notes mention), or (b) the docs declare `explain()` tx-free by design
   (a read-only metadata probe: GUIDE § query plans + EMBEDDING § Transactions).
   The property campaign's recommendation: (b) is defensible — the plan does not touch
   records — but the ruling is not the campaign's to make.
2. **The alignment**: implement (a) the refusal, or write (b) into GUIDE + EMBEDDING
   (+ the facade-audit table row).
3. **The pin**: `PropFacadeContractTest.everyTxBoundMethod_RefusesWithoutTransaction`
   updated to assert the CHOSEN behavior for `explain()` (both branches get a pin).
4. The full quick-gate green.

## Out of scope

Any query-engine behavior change beyond the contract alignment.

## Acceptance criteria

- [ ] The decision recorded (this task + the task-07 audit table).
- [ ] The alignment implemented (code or docs per the ruling).
- [ ] The facade property pins the chosen behavior.
- [ ] The quick-gate green (BUILD SUCCESS verdict).

Estimate: ~0.5 session.

## References

- docs/perf/PROPTEST-FINDINGS.md §5 (the asymmetry)
- `PropFacadeContractTest.everyTxBoundMethod_RefusesWithoutTransaction` (the sweep)
- Task 07's facade-audit finding table (the format)


---

## RESOLVED (2026-11-23): the ruling — (b) tx-free by design, documented and pinned

**The ruling (the maintainer's call via the completion pass):** option (b) — `explain`
is tx-free **by design**: the plan is static index metadata (no snapshot is needed; the
internal probe transaction is rolled back immediately — the task-25 design comment in
`XdStorage.explain`), while the CRUD/search reads need the snapshot and refuse without
one.

**The alignment landed:**
- GUIDE § Query plans: "tx-free **by design** ... pinned by the property estate";
- EMBEDDING § Transactions: the deliberate exception noted after the CRUD refusal
  statement;
- The pin: `PropFacadeContractTest.explain_WorksWithoutTransaction_ByDesign` — the
  no-tx explain returns the plan + the sibling load(search) refusal for contrast;
- The no-tx facade sweep excludes `explain` with the decision comment.

**Acceptance:**
- [x] The decision recorded (this task + the task-07 audit-table format's row).
- [x] The docs aligned (GUIDE + EMBEDDING).
- [x] The behavior pinned by the property assertion.
