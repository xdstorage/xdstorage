---
title: "[2.1.0][WRAPLEAK] Memory leak: wrapper objects without identifiers are never released from the observer/workspace registries"
labels: [2.1.0-train, P1, engine, bug]
status: open
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

- [ ] The leak repro test green (reachability asserted to drop after the fix).
- [ ] All four deregistration paths covered by tests (success / no-id commit / rollback /
      abandoned sweep).
- [ ] EMBEDDING.md updated; full suite green ×2.

Estimate: ~1–2 dev-days (the audit is the careful half).

## References

- discovery.md § Memory (the reported leak), EMBEDDING § JVM-wide static state
- `XdStorageObjectUtils.getWrappedObjectOrSameObject`, the observer service, task 36 (the
  zombie sweeps that must clear the unidentified maps)
