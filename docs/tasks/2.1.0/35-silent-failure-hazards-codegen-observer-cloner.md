---
title: "[2.1.0][SILENTFAIL] Silent-failure hazards in codegen/observer/cloner — fix the three swallow-and-continue paths"
labels: [2.1.0-train, P2, engine, bug]
status: done
---

## Summary

Three hazards found by the 28-javadoc-coverage campaign, all in the same family — a failure is
swallowed and execution continues with a degraded or `null` result, so the real defect surfaces
far from its cause (or never):

1. **`XdStorageClassGenerator.compileGeneratedClassesCode`** (~line 171): `result.put(cl, clazz)`
   sits INSIDE the generation loop and keys EVERY generated class by the entity class — a
   multi-class generation graph (entity + generated children/wrappers compiled together) keeps
   only the last loaded class per entity key. Today's callers pass single-class compilations, so
   nothing breaks — but any future multi-class use silently loses classes.
2. **`XdStorageObserverService.getObservableWrapper`**: unsynchronized check-then-act after the
   `computeIfAbsent` — two racing threads can both generate and register a wrapper for the same
   class (last put wins). It can also return `null`: the deprecated `wrapAsObservableObject`
   swallows ALL exceptions, so a codegen failure masquerades as "no wrapper".
3. **`XdStorageCollectionCloner.cloneCollection`/`cloneMap`** swallow every `Throwable` and
   return `null` — the MVCC snapshot materialization then silently drops the collection (the
   same data-loss family the R-44 comment in `XdStorageMvccGraphCloner` describes).

## Scope

- (1) key the result map by the GENERATED class (or the class that requested it — read the
  callers and pick the contract), and add a multi-class test;
- (2) close the race (the registration already goes through a concurrent map — use its atomic
  forms end-to-end) and make the failure path loud: propagate or throw instead of returning
  `null` (callers audited);
- (3) propagate clone failures as `XdStorageException`/runtime with the offending collection
  type in the message; the test asserts a poisoned element fails LOUDLY, never a `null`
  snapshot.

## Out of scope

- The deprecated `wrapAsObservableObject` API itself (removal is a MAJOR semver event);
- any change to when wrappers are generated (only the failure/race semantics).

## Landed (2026-10-25): all three fixes + tests

1. **`XdStorageClassGenerator.compileGeneratedClassesCode`** — the result map is keyed by
   the WRAPPED class (the loaded generated class's superclass, falling back to the
   requesting class): each generated class survives under its own truthful key, and the
   single-class case is unchanged (the wrapper's superclass IS the requesting entity).
   The multi-class test compiles an entity wrapper AND a child-class wrapper in one shot
   and asserts both survive, each keyed by the class it wraps — red-before proven
   (`expected 2 but was 1` with the old entity-keyed put). Note: the discriminating
   shape uses two DIFFERENT wrapped classes — two wrappers over the same entity cannot
   coexist in a `Class`-keyed map by definition.
2. **`XdStorageObserverService.getObservableWrapper`** — the get-or-create is one
   compound action synchronized on the per-class wrapper segment: the check-then-act
   race is closed and exactly one observer registers per wrapper. The codegen-failure
   path throws `XdStorageRuntimeException` naming the class and the JDK-codegen
   requirement instead of letting `wrapAsObservableObject`'s swallowed `null` become an
   NPE downstream. Concurrency pinned by a 24-thread same-instance assertion.
3. **`XdStorageCollectionCloner.cloneCollection`/`cloneMap`** — clone failures throw
   `XdStorageRuntimeException` naming the offending collection/map type (cause chained)
   instead of returning `null` MVCC snapshots; a `null` source still clones to `null`
   (honest semantics, pinned). The stale "or {@code null} on failure" javadoc promises
   are removed.

The R-42-style caveat: infrastructural btree caches keep their own rollback semantics —
untouched here (this task's three paths are domain-side only).

## Extended (2026-10-25, user-directed): the non-instantiable-container fallback ladder

The user's follow-up fix in `XdStorageCollectionCloner` added `newCollectionInstance(src)` —
collections whose class cannot be reflectively re-instantiated (immutable/wrapper/foreign
containers) now clone into the closest MUTABLE semantic equivalent instead of failing.
Extended it into a full semantic ladder and mirrored it for maps:

- **collection fallback**: exact `Collections.synchronizedList/Set` wrapper classes
  (the user's precision checks, extended to the non-RandomAccess synchronized list),
  then SortedSet → TreeSet, Set → HashSet, Queue/Deque → LinkedList, everything else →
  ArrayList — EMPTY containers every time (the caller's element loop fills the clone;
  pre-populating duplicates every element — caught by the regression test);
- **map fallback**: `newMapInstance(src)` — the synchronized-map wrapper, SortedMap →
  TreeMap (natural order), everything else → HashMap — wired into BOTH the empty and the
  non-empty branches (the non-empty branch previously still threw on non-instantiable
  sources);
- the recorded class-coarsening contract: the clone's CLASS may be coarser than the
  source's; its CONTENTS and iteration semantics are what the snapshot preserves. A
  comparator-bearing SortedSet/SortedMap degrades to natural ordering (the comparator is
  not recoverable from the collection) — loud ClassCastException on non-comparable
  elements rather than silent loss.

Tests: 6 fallback cases in `XdStorageSilentFailureFixTest` (unmodifiable list/set/sorted
set/synchronized linked list/unmodifiable map/unmodifiable sorted map), the loud
element-failure tests re-targeted to the real failure path (a pojo without a no-arg
constructor inside the collection — PojoCloner's `cl.newInstance()` throws), and the
null-source semantics pinned.

## Acceptance criteria

- [x] Each of the three has a failing-first test that passes after the fix (the
      multi-class test's red-before was proven with the old entity-keyed put).
- [x] No silent `null` returns remain on the three paths.
- [ ] Full suite green ×2 (in validation at landing; see the commit).

Estimate: 1–1.5 d.

## References

- [28-javadoc-coverage](28-javadoc-coverage.md) — the campaign that surfaced all three
- `code/XdStorageClassGenerator.java`, `observing/XdStorageObserverService.java`,
  `utils/XdStorageCollectionCloner.java`, the R-44 note in `utils/XdStorageMvccGraphCloner.java`
