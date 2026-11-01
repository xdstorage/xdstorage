---
title: "[2.1.0][BUG-B] Objects unreachable by id after a structure update on a freshly-opened storage"
labels: [2.1.0-train, P1, engine, bug]
status: done
---

## Summary

With an entity class loaded through a non-system classloader (the schema-evolution
suite's runtime-compiled V2 generation), the sequence save → reopen →
`executeStructureUpdate(V2)` → load-by-id returns **null**: the object written by the
previous storage instance is no longer addressable. The same sequence with a plain
compiled entity (`RangeOpsEntity`) works — verified by probes during T-3a — so the
defect lives in the interplay of the structure-update pass with the id-index/data
resolution for non-system-loader classes.

## Why

Discovered by the T-3a gap-fill (docs/tasks/2.1.0/06). Schema evolution is the feature
this engine's structure-update machinery exists for; an entity that vanishes from
id-addressability after its structure is migrated is silent data unavailability. The
dual-classloader scenario is not exotic: it is exactly what an application upgrade
(older entity class version on disk, newer class in the running JVM) looks like.

## Root cause (precisely characterized by the BUG-A session, 2026-10-23)

The journal replay and identity resolution are FIXED (docs/tasks/2.1.0/15): after the
fixes, the reopened storage OPENS, the id-index resolves, the data is found — and the
object still cannot be MATERIALIZED. The remaining seam is the codegen wrapper identity:

- every stored entity is wrapped (`XdStorageSimpleWrapperClassCodeGenerator`) under a
  NAME-based wrapper class (`<Entity>SimpleWrapper`) in one shared code directory, loaded
  through one shared classloader (`XdStorageClassGenerator.sharedClassLoader`);
- two LIVE generations of one FQCN (V1 saved by storage-1, V2 loaded by the app through
  its own loader) collide on that one wrapper name: the first generation's wrapper class
  wins `Class.forName`, and the second generation's object fails instantiation with
  `NoSuchMethodException: <Wrapper>.<init>(Object, evolution.EvoEntity, IXdStorage)` —
  exactly reproducible with `XdStorageSchemaEvolutionTest`'s dual-classloader scenarios
  (their `@Disabled` annotations carry the signature).

The fix is a DESIGN decision, not a patch: generation-aware wrapper identity (loader-
or-content-qualified wrapper names with per-generation loading), or wrapper-less
materialization for non-system-loader classes. Both touch the codegen contract that the
whole read path depends on — hence the task stays open and scoped to the 2.2.0-class
surgery tier alongside W-4.

## Scope

1. Decide the wrapper identity model (generation-qualified names vs wrapper-less
   materialization fallback) — record it in [MASTER-PLAN.md](../../MASTER-PLAN.md) § codegen.
2. Implement + make the four `@Disabled` dual-classloader scenarios in
   `XdStorageSchemaEvolutionTest` pass un-disabled (change detection fires; the migrated
   object is readable through the V2 class with its persisted fields intact).
3. A regression: the plain-class (system-loader) probe sequence stays green.

## Out of scope

- BUG A (the journal replay NPE — filed separately; where it masks this defect, split
  the repro along the probe evidence).
- Implementing full schema-evolution UX beyond restoring the invariants.

## Fixed (2026-11-01)

- **GENERATION-AWARE WRAPPER IDENTITY** (the decision, recorded in MASTER-PLAN § codegen):
  generated wrapper names carry a qualifier derived from the entity's DEFINING
  CLASSLOADER when that loader is not the engine's own
  (`XdStorageAbstractClassCodeGenerator.generationQualifier` — applied to the simple,
  unmodifiable, observable and infra wrapper generators); engine-loaded classes keep
  the unqualified names (zero change to the common path).
- **The shared codegen loader no longer defines duplicate entity classes**: its
  `loadClass` delegates already-registered classes to the class-metadata registry's
  exact instance (previously the bridged entity URLs let it define a SECOND copy of the
  entity — every generated wrapper linked against the shared copy and failed the
  application's `instanceof`/field access, "object is not an instance of declaring
  class").
- **The class-metadata registry is LAST-REGISTERED-WINS per FQCN**
  (`latestByName` consulted by `findLoadedClassByName` before the hash-order scan):
  with two live generations registered, the reopened storage now always materializes
  the application's CURRENT generation.
- **The persisted structure record's identity is the class FQCN** (was the Class
  object — the V1-era record keyed on the V1 Class instance was INVISIBLE to the V2
  generation's read, so the evolution was never detected;
  `XdStorageClassStructure.type` and `XdStorageClassIndexStructureId.type` are FQCN
  strings now; the read sites pass `cl.getName()`).
- The regression `get()` helper unwraps the generated SimpleWrapper before field
  reflection (the wrapper is the live lazy-loading view; its own fields are delegation
  plumbing). Each scenario also compiles its entity into its OWN package — the static
  metadata registry was a cross-test pollution channel under a shared FQCN.

## Acceptance criteria

- [x] The two disabled tests green. (`reopenSurvivesStructureAndData`,
      `evolutionDetectsChangeAndReportsUpdaterOutcomes` — un-disabled and green; the
      `updaterLedgerIdempotencyAndRetry` scenario was additionally restructured to pin
      BOTH ledger halves — SUCCESS-record skip and FAILURE-record retry, whose original
      assertions were internally contradictory and never ran.)
- [x] The plain-class probe sequence still green (no regression in the working path).
- [x] Full suite green ×2 (BUILD SUCCESS verdict). — 926 tests ×2, 0 failures; the
      single error in both runs is the task-42-documented MassFillAndClean FK veto
      (the concurrent-fill divergence, out of this task's scope).

## Verification

```bash
mvn -Dtest=XdStorageSchemaEvolutionTest test          # un-disable the BUG B tests first
mvn clean test && mvn clean test
```

## References

- Discovered in docs/tasks/2.1.0/06 (T-3a) — probe history isolates the defect from BUG A
- Depends conceptually on 01-commit-journal-replay-npe (where the journal crash masks it)

Estimate: 1–2 dev-days.
