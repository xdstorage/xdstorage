---
title: "[2.1.0][IDXCONTRACT] Align the index-resource implementations with their declared contracts"
labels: [2.1.0-train, P2, engine, tech-debt]
status: open
---

## Summary

Four contract/code divergences found by the 28-javadoc-coverage campaign in the id-index
resource layer (`index/`, `index/hash/`, `index/btree/`):

1. **Undeclared unchecked escape on the journal path.** `XdStorageHashIndexResource.
   performFirstPhaseCommit` / `rollbackPerformingFirstPhaseCommit` declare only
   `XdStorageException` but throw `XdStorageRuntimeException` on a journal-append failure.
   The checked signature is a lie in the failure case that matters most — either convert to
   the checked type or declare the runtime contract (`@throws` + docs) and keep it unchecked.
2. **The broad `catch (Throwable)` → "invalid state of database"** in `readAsData`/`prepare`
   (hash and btree equivalents) wraps plain `IOException`s — a full disk reads as "invalid
   state", sending the operator down the corruption path instead of the I/O path. Narrow the
   catch to what is genuinely a state problem; pass I/O failures through as
   `XdStorageConnectionException`.
3. **Snapshot-invisible-skip asymmetry.** The btree `read(transaction)` skips records invisible
   in the reader's snapshot; the hash `read` answers from committed-state with no such skip.
   The interface doc stayed neutral in 28 — decide the intended semantics per implementation
   and pin BOTH with tests (document, don't necessarily change behavior).
4. **Search-tree key DELETEs have no rollback compensation** (found 2026-10-24, the
   multithreads-stability investigation; the ID-INDEX twin of this gap is FIXED in the same
   session — `registerIndexKeyDeleteForRollback` on the btree id-index path): the search
   trees' `delete`/primary-move paths (`XdStorageSearchIndexResource` :325/:355/:369) remove
   keys in place with no R-57-style rollback re-insert. A rolled-back transaction that
   deleted search-index keys leaves them consumed — indexed objects become unresolvable the
   same way. Needs the value-capturing fix (find-before-delete or captured old records) at
   all three sites.
5. **Dead/misleading surface on `XdStorageSearchIndexResource` and friends:** `rollbacks`,
   `reader`, `writer` fields are initialized and never read; `getObjectsCount()` hard-returns
   `0` (right in spirit — the payload lives in the trees — but it contradicts the interface
   contract a caller would trust); the `fragmentSize` constructor parameter is accepted and
   unused. Remove the dead state, wire or drop the parameter, and give `getObjectsCount` an
   honest contract (documented zero-with-reason or a real count).

## Scope

- Fix (1) and (2) with failure-path tests (journal-append failure surfaces as the declared
  type; a simulated `IOException` surfaces as `XdStorageConnectionException`).
- (3): two tests pinning hash-vs-btree snapshot semantics + the interface doc stating the
  difference explicitly.
- (4): dead-field/parameter removal (internal API — no embedder impact), `getObjectsCount`
  contract decision documented.

## Acceptance criteria

- [ ] Failure-path tests for (1)/(2); snapshot-semantics tests for (3); dead code from (4)
      removed.
- [ ] No `catch (Throwable)` left on the readAsData/prepare paths.
- [ ] Full suite green ×2.

Estimate: 1 d.

## References

- [28-javadoc-coverage](28-javadoc-coverage.md) — the campaign that surfaced all four
- `index/hash/XdStorageHashIndexResource.java`, `index/btree/XdStorageBTreeIndexResource.java`,
  `search/XdStorageSearchIndexResource.java`
