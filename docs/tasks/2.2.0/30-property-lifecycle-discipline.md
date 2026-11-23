---
title: "[2.2.0][PROPLIFE] The property-harness lifecycle discipline: one driver-owned storage, the explicit reopen re-registration"
labels: [2.2.0-train, P3, testing]
status: open
---

## Summary

The property campaign's harness lesson (PROPTEST-FINDINGS.md #8): the reopen-burn
pattern hits `"storage pt-id-reopen-N is shut down"` after the storage reference is
reassigned — the OLD instance vs the provider registry. The wave loop's post-reopen
save failed; the red pin (`generatedIds_NeverReissued_AcrossRandomWavesAndReopens`)
holds the reproducible shape. This task codifies the property-harness lifecycle
discipline and un-reds the pin.

## Why

- The discipline exists in the house rules for TESTS generally (the thread-binding +
  the registry semantics — EMBEDDING § Transactions/JVM-wide static state), but the
  property loops reopen aggressively and need the pattern codified ONCE so every
  future property reuses it instead of re-tripping the same registry edge.
- Un-reding the id never-reissue pin restores a full property (currently counted as
  the honest skip).

## Scope

1. The codified pattern (a small helper or a documented convention in the properties
   package): ONE driver-owned storage per property; the explicit
   reopen-re-registration (the same name → the NEW instance from the provider); the
   @AfterTry cleanup vs the registry.
2. Apply to `generatedIds_NeverReissued_AcrossRandomWavesAndReopens` → the pin goes
   green (the id never-reissue assertion actually running).
3. The other reopen-using properties aligned (the CRUD reopen, the journal
   equivalence, the facade count/reopen — audit and conform).
4. The suite green (`mvn test -Dtest="Prop*Test"`, 13 tests, 0 skipped ideally —
   the two honest skips resolved or re-pinned with the updated reasons).

## Out of scope

The engine's provider/registry semantics (if the triage of task 24 finds an engine
defect here, it moves to that task); any new property families (task 03's follow-ups
own those).

## Acceptance criteria

- [ ] The pattern codified and applied to the reopen-using properties.
- [ ] The id never-reissue pin green (or honestly re-pinned with the updated reason).
- [ ] `mvn test -Dtest="Prop*Test"` green with the skips resolved/reduced.
- [ ] The findings rows #7/#8 closed (the cross-links to task 24 where relevant).

Estimate: ~0.5 session.

## References

- docs/perf/PROPTEST-FINDINGS.md §8 (the observed behavior)
- `PropCoreContractsTest.generatedIds_NeverReissued_AcrossRandomWavesAndReopens` (the pin)
- EMBEDDING § JVM-wide static state (the provider registry semantics)
