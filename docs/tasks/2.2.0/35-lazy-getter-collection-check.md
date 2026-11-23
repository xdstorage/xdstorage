---
title: "[2.2.0][WRAPGEN] The lazy-getter codegen asks the type question backwards: List/Set/Map-typed lazy fields load as single objects"
labels: [2.2.0-train, P1, engine, bug, codegen]
status: open
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

- [ ] The repro red-before / green-after, the mechanism named (the inverted assignability).
- [ ] List/Set/Map-declared lazy fields load per-element (the pin).
- [ ] The generated-source review recorded (the R-58 path preserved).
- [ ] The codegen estate ×3 + the quick-gate ×3 green.

Estimate: ~1 session + the fix.

## References

- The external evaluation 2026-11-23, §3.3.12 (verified against main: the inverted
  checks are at lines 100/103 of `XdStorageWrapperBlockGettersGenerator.java`)
- Task 22 lazy-read-snapshot (the lazy-load contract neighbor)
