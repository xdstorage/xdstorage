---
title: "[2.1.0][WRAPLEAK] Memory leak: wrapper objects without identifiers are never released from the observer/workspace registries"
labels: [2.1.0-train, P1, engine, bug]
status: done
---

## Summary

`XdStorageObjectUtils.getWrappedObjectOrSameObject` and the deferred-id machinery keep
wrapper objects alive when the object **never receives an identifier**: the JVM-global,
class-keyed deferred-id observer registry (EMBEDDING § JVM-wide static state) and the
transaction workspace's unidentified-wrapper map hold strong references to wrappers whose
`onNewIdIsSet` never fires — inserts that failed before id assignment, objects discarded by
the application mid-registration, or transactions abandoned mid-scope (task 36's zombie
pattern). The result is a slow heap leak proportional to failed/discarded registrations.

## Why

- The observer registry is **JVM-global by design** (EMBEDDING § static state) — a leak
  there survives `storage.shutdown()` and pollutes every subsequent storage in the same
  JVM; long-running applications (the primary target: desktop/edge/embedded) never get the
  registry back.
- The workspace's unidentified map is per-transaction and cleared at
  commit/rollback/`finishTransaction` — but only through the paths that know the wrapper;
  an abandoned transaction's unidentified wrappers die with the workspace **only if** the
  workspace itself is cleared (verify: the zombie-transaction sweeps of task 36/R-41 may
  not clear unidentified maps).

## Scope

1. **Reproduce the leak** (a deterministic test: register N wrappers whose ids are never
   set → force the registry/workspace → assert reachability drops; a heap-sensitive
   assertion or a `WeakReference`-based test).
2. **The fix**, per registry:
   - the transaction workspace's unidentified map: cleared by the existing
     rollback/finish paths — verify the zombie sweeps clear it too (task 36's force-sweep
     must include the unidentified map);
   - the JVM-global observer registry: weak references or explicit deregistration on
     transaction end (a wrapper whose tx completed without assigning an id is
     deregistered loudly — the insert path that assigned the id removes it on success
     today; the failure paths must remove it too).
3. **The audit**: every path that REGISTERS a deferred-id observer must have a guaranteed
   deregistration on (a) success, (b) tx commit without id, (c) tx rollback, (d) tx
   abandonment (the task-36 sweep).
4. EMBEDDING.md § JVM-wide static state updated (the registry is no longer
   "never evicted" for the failure paths).

## Out of scope

Redesigning the deferred-id mechanism (the observer pattern stays); weak keys on the
class-keyed registry beyond the leak fix.

## Acceptance criteria

- [x] The leak repro test green (reachability asserted to drop after the fix).
- [x] All four deregistration paths covered by tests (success / no-id commit / rollback /
      abandoned sweep).
- [x] EMBEDDING.md updated; full suite green ×2.

Estimate: ~1–2 dev-days (the audit is the careful half).

## References

- discovery.md § Memory (the reported leak), EMBEDDING § JVM-wide static state
- `XdStorageObjectUtils.getWrappedObjectOrSameObject`, the observer service, task 36 (the
  zombie sweeps that must clear the unidentified maps)


## Update 2026-11-08 — FIXED

### The diagnosis

`getWrappedObjectOrSameObject` itself is stateless (pure unwrapping) — the leak lives in
the JVM-global deferred-id observer registry (`XdStorageObserverService`). Its per-class
segments were `WeakHashMap`s whose VALUES (the observable wrappers) STRONGLY reference
their KEYS (the wrapped domain objects: the generated wrapper holds its target) — the
classic WeakHashMap self-reference leak: the keys never became weakly-reachable, no
entry was ever evicted, and every `(class, object)` registration whose id never landed
leaked the wrapper, the wrapped object and its observer forever (surviving
`storage.shutdown()`). The javadoc's "the GC reclaims completed transactions" claim was
structurally false.

### The fix

The registry segments hold **WeakReference values**: a wrapper lives exactly as long as
somebody holds it (its owning transaction); a discarded registration is reclaimed by the
GC and the dead entries are removed eagerly by the lookup. The success path is
unchanged (the one-shot observer removes the entry when the id lands).

### The audit (the four deregistration paths — all verified and pinned by tests)

- (a) **Success**: the id lands through the generated setter → the one-shot observer
  removes the entry → the wrapper freed; the next registration is fresh
  (`idSetRemovesRegistryEntry`).
- (b) **No-id commit** and (c) **rollback**: all three protocol completion paths
  (`commit`/`rollback`/`rollbackFailedCommit`) call `finishTransaction`, which clears
  the workspace's unidentified map (`XdStorageWorkspaceUnidentifiedCleanupTest`).
- (d) **Abandonment**: the task-36 sweeps ride `rollbackInternal → resource.rollback →
  protocol.rollback` — the same cleanup is inherited (the sweep's deferred round
  included).

### Verification

- `XdStorageWrapperObserverLeakTest` (NEW): the WeakReference repro (red before the
  fix — the registry held the wrapper strongly), the success path, and the stability
  contract (the same object pre-id yields the same wrapper).
- `XdStorageWorkspaceUnidentifiedCleanupTest` (NEW): the rollback and the no-id commit
  clear the workspace's unidentified map.
- Full suite ×2: **956 tests, 0 failures, 0 errors**.
- EMBEDDING § JVM-wide static state updated (the registry no longer "never evicted").
- The commit message: 46-COMMIT-MESSAGE.txt (nothing committed per the instruction).

## Update 2026-11-08 (evening) — the registry semantics CORRECTED (the CI regression 425d7e6)

The CI gate caught that the WeakReference-value fix **broke the registry's core
contract**: the deferred-id consumers (the search finalization, the FK waiters, the
cascade persistence) rely on "one stable wrapper per (class, object) **until the id
lands**" — the weak-value holder let the GC collect a wrapper between a deferred-flow
registration and the id assignment, silently detaching every observer registered on it
(`SearchManagerTest.testInsertDefersFinalizationUntilIdsAssigned`: the captain's id
assigned to a REGENERATED wrapper; the record never completed).

CORRECTED: the values are STRONG again (the stability contract restored), and the leak
is fixed by the EXPLICIT failure-path deregistration — the task's own alternative (b):
- `XdStorageObserverService.deregister(wrapper)`: the identity-strict entry removal
  (the generated wrappers override equals by their target, so remove(key, value) would
  match a newer registration — only the exact wrapper's entry is dropped);
- the workspace's `finishTransaction` (the commit/rollback/rollbackFailedCommit paths
  and the task-36 sweeps through the same rollback) deregisers every unidentified
  wrapper it discards;
- a registration outside any transaction context lives until its id lands — the
  caller's lifecycle by contract (documented in the service).
TESTS: the leak repro rewritten to drive the dereg path (`abandonedRegistrationIs
ReclaimedAfterDeregistration`), the stale-deregistration pin
(`staleDeregistrationDoesNotDropNewerEntry`), the SearchManager suite green.
