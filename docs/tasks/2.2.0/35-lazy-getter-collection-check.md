---
title: "[2.2.0][WRAPGEN] The lazy-getter codegen asks the type question backwards: List/Set/Map-typed lazy fields load as single objects"
labels: [2.2.0-train, P1, engine, bug, codegen]
status: done (the two inverted checks replaced; a null lazy map no longer throws; pinned by XdStorageLazyGetterTypeDispatchTest 19 green; the 2.1.x cherry-pick decision stays with the maintainer)
---

## Summary

`XdStorageWrapperBlockGettersGenerator` (the `@XdStorageLoadByGetMethod` lazy-getter
codegen) dispatches on the RETURN type with INVERTED checks (lines 100 and 103):

```java
} else if (returnType.isAssignableFrom(Collection.class) || returnType.isAssignableFrom(List.class)) {
```

`X.isAssignableFrom(Collection.class)` answers "is X a SUPERTYPE of Collection" — true
only for `Collection`/`Iterable`/`Object` themselves. A field declared `List<X>`,
`Set<X>` or `ArrayList<X>` answers FALSE and falls into the single-object branch; the
same for a `Map` field declared as anything narrower than `Map` (line 103). The
per-element lazy load silently never happens for the most natural declarations.

## Why

- Silent wrong behavior on a DOCUMENTED pattern (the guide examples declare `List`/`Map`,
  not `Collection`): the lazy getter loads one object (or nothing) and marks the field
  loaded — the collection elements stay unwrapped and every later read of them is wrong.
- The R-58 invisible-element removal lives in the same (collection) branch — the fix
  must keep that path intact and extend it to the previously-broken declarations.

## Scope

1. The deterministic repro FIRST: an entity with `List`/`Set`/`Map`-typed lazy fields;
   assert the per-element load fired (red on the current codegen).
2. The fix: invert to `Collection.class.isAssignableFrom(returnType)` /
   `Map.class.isAssignableFrom(returnType)` (the array branch above stays first); audit
   the generated source diff for every branch.
3. The pins: the property/round-trip coverage for the lazy declarations (the PROPTEST
   estate's seed model gains the declaration variants).
4. The gates: the repro green, the codegen estate ×3, the quick-gate ×3.
5. The both-lines decision: the usage-severity triage for the 2.1.x cherry-pick (the
   fixes ride both lines per the standing directive when the defect class is silent
   wrong reads).

## Out of scope

The lazy-load transaction contract (task 22's snapshot work); the non-lazy wrapper
generators' checks (audit them in the same grep, but the fix files separately if they
share the inverted pattern — record the grep result in this task either way).

## Acceptance criteria

- [x] The repro red-before / green-after, the mechanism named (the inverted assignability).
- [x] List/Set/Map-declared lazy fields load per-element (the pin).
- [x] The generated-source review recorded (the R-58 path preserved).
- [x] The codegen estate ×3 + the quick-gate ×3 green: the codegen estate (`code.*Test` +
  `XdStorageRollbackOnlyCauseTest`, `XdStorageSilentFailureFixTest`,
  `XdStorageWrapperObserverLeakTest`, `XdStorageFacadeMethodAuditTest`,
  `XdStorageObjectModelContractTest`) **115/0f/0e ×3**; the quick-gate (the full 1379) **green ×3**
  — `1379/0f/0e/7 skipped`, BUILD SUCCESS, on runs 2, 3 and 4. Run 1 went red on ONE class:
  `XdStorageNestedTransactionIsolationStressTest` (the R-STORM-ENVELOPE family, FLAKY.md
  "excluded by decision") while a second Maven build was running on the box — the same tree
  passed the three quiet runs after it.

Estimate: ~1 session + the fix.

## LANDED (2026-11-24) — the fix + the pins

**The mechanism (proven on the unfixed generator first):** the branch condition was asked
backwards. `returnType.isAssignableFrom(Collection.class)` is true only when the declared
type is a SUPERTYPE of `Collection` — `Collection`, `Iterable`, `Object` themselves; the twin
`List.class` check added exactly `List`, the `Map.class` one exactly `Map`. Every other
declaration (`Set`, `Queue`, `ArrayList`, `HashSet`, `HashMap`, `SortedMap`, ...) fell into
the single-object branch: nothing was loaded, the loaded flag was set anyway, and the
children stayed bare reference stubs. The line numbers moved with the formatting pass:
120 and 136 at `d1f36d1`.

**The red-before record** (`XdStorageLazyGetterTypeDispatchTest` on the unfixed generator,
the helpers present but not yet wired): 19 tests / 8 failures, ALL on the three rows
`Set` / `ArrayList` / `HashMap`; the `Collection` control green:

- the mock level — `Wanted but not invoked: iXdStorage.load(...)` ×6 (two stubs × three
  declarations); the invisible element not removed (3 rows); the generated source lacking the
  element branch;
- the real storage, cold reopen — `Set: the getter must have loaded the child 11 ==>
  expected: <false> but was: <true>` (the same for the `ArrayList` and `HashMap` children).

**The fix (`XdStorageWrapperBlockGettersGenerator`):** two predicates, `isCollectionType`
(`Iterable.class.isAssignableFrom(type)`) and `isMapType` (`Map.class.isAssignableFrom(type)`),
replace the inverted checks; the array branch stays first. A side effect in the right
direction: an `Object`-declared lazy field used to be sent to the collection branch, whose
generated source (`Object toLoadCollection ... toLoadCollection.iterator()`) did not compile —
it now takes the single-object branch.

**Found by the pin, in the same branch:** a NULL lazy map threw — `NullPointerException` on
`.values()`, turned by the `catch (Throwable)` into a rollback-only mark +
`XdStorageRuntimeException`. Pre-existing for exactly-`Map`-declared fields; the fix would
have spread it to every map subtype. The map branch now reads the field into a local behind
a null guard (`toLoadMap == null ? null : toLoadMap.values()`).

**The generated-source review** (the fixture's wrapper dumped: 268 lines): all four lazy
getters take the element branch; each carries the R-58 `catch
(XdStorageReferenceInvisibleException)` + `toLoadIterator.remove()` and the rollback-only
site descriptor. Counted by the source pin: 4 removals, 4 descriptors.

**The audit — the inverted pattern across main** (`grep -rn isAssignableFrom
src/main/java`): it existed ONLY at the two generator sites; every other use has the
`X.class.isAssignableFrom(cl)` direction. One separate observation, NOT this task's pattern:
`XdStorageUnmodifiableBlockGettersGenerator` carries no type dispatch at all — its lazy
getter always loads the field as ONE object (`storage.load(Object, tx)`), so a lazy
collection or map inside an unmodifiable view is never loaded element by element. Not
verified against a real storage; the views are a utility surface
(`wrapAsUnmodifiableObject`), not a facade method — left for the maintainer to triage.

**Why the estate never saw it:** every `@XdStorageLoadByGetMethod` fixture in the tree
declares the field `Collection<...>` (`XdStarSystem.planets`, `XdStarSystemAutoSave.planets`)
or the exact `List` / `Map` — the only declarations the old check accepted. The new fixture
`LazyTypedCollectionsBean` declares `Set`, `ArrayList`, `HashMap` + the `Collection` control.

**The pins (`XdStorageLazyGetterTypeDispatchTest`, 19 tests):** per declaration — the first
getter access loads every wrapped element exactly once (mock-verified), plain elements are
never loaded, an invisible element is dropped without a rollback-only mark, a null field
stays null; the generated-source pin; the dispatch table over 19 declared types; the
real-storage cold-reopen pin (distinct children per field — the engine memoizes one wrapper
per (transaction, class, id), so a shared child would mask a missing load).

**The 2.1.x cherry-pick:** the production diff is the generator file alone (the pins are
their own test files). The defect class is silent wrong reads — the standing directive says
such a fix rides both lines; the 2.1.4 cut itself is the maintainer's call.

## References

- The external evaluation 2026-11-23, §3.3.12 (verified against main: the inverted
  checks are at lines 100/103 of `XdStorageWrapperBlockGettersGenerator.java`)
- Task 22 lazy-read-snapshot (the lazy-load contract neighbor)
