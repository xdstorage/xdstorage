---
title: "[2.2.0][FKFRESH] FK cascade on a FRESH storage instance: save(parent-with-children) commits but load(parent) returns NULL, the FK tree NPEs on the next op"
labels: [2.2.0-train, P1, engine, bug]
status: open
---

## Summary

The property campaign's probe (PROPTEST-FINDINGS.md #7): on a **freshly created storage
instance**, `save(parent-with-@XdStorageAutoSave-children)` → `commit` reports success,
but `load(parent)` returns **NULL** (even in-storage, before any reopen), and the FK
engine's `treeParentChild` **NPEs on the next operation**. The SAME shape passes in the
FK estate (`ForeignKeyUpdateTest`) — so the divergence is in the fresh-instance path
(the config diff, the deferred-id/FK registration on a cold instance, or the property
harness's lifecycle usage — the triage decides).

## Why

- If this is a real engine path (not a harness artifact), it is a **silent data-loss
  class**: the commit reports success while the parent is not loadable. The release/2.1
  line inherits it — the fix cherry-picks there too (the maintainer's directive: the
  2.2.0 findings' fixes ride both lines; no new patched version is cut until the
  maintainer calls one).
- The property harness's red pin (`PropCoreContractsTest`, findings #7) holds this
  reproducible; the estate's example tests pin the contracts meanwhile.

## Scope

1. **The triage**: reproduce outside the property harness (a plain main-style driver);
   diff the fresh-instance path against the estate's setup (the config, the transaction
   lifecycle, the FK registration timing, the wrapper/codegen state on a cold JVM
   instance); name the mechanism.
2. If engine: fix + the red-before pin promoted green; if harness: fix the harness
   pattern and close the pin with the explanation.
3. **The cherry-pick to `release/2.1`** (the fix rides both lines per the maintainer's
   directive; the 2.1.2 bump/tag happens at the maintainer's call — the fixes
   accumulate on the branch meanwhile).
4. The full gate + the FK estate ×3.

## Out of scope

The property-harness lifecycle discipline in general (task 26 owns the
reopen-re-registration pattern this triage will likely inform).

## Acceptance criteria

- [ ] The mechanism named (engine vs harness) with the evidence.
- [ ] Engine: the fix + the green pin + the FK estate ×3 + the quick-gate ×3.
      Harness: the corrected pattern + the closed pin with the explanation.
- [ ] The fix cherry-picked to `release/2.1` (if engine).
- [ ] The findings row #7 closed in PROPTEST-FINDINGS.md.

Estimate: ~1 session (the triage) + the fix.

## References

- docs/perf/PROPTEST-FINDINGS.md §7 (the probe and the observed behavior)
- `PropCoreContractsTest.fkVetoAndLegalCycle_HoldUnderRandomShapes` (the red pin)
- The FK estate (`ForeignKeyUpdateTest` — the passing same-shape example)
