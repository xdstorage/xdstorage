---
title: "[2.1.0][MSGHYGIENE] Message hygiene and dead parameters — the mechanical cleanup bundle from the javadoc campaign"
labels: [2.1.0-train, P2, chore, tech-debt]
status: done
---

## Summary

The small findings of the 28-javadoc-coverage campaign, bundled because each is minutes of
work: lying/mispelled messages, ignored parameters, and misleading names — all engine-internal,
zero embedder impact, but each one sends an operator or maintainer in the wrong direction.

**Messages:**
- `XdStorageSearchManager` 441, 480: `"requred index ..."` → `required` (×2);
- `XdStorageBTree` 104, 352: `"cannot be letter or equal 1"` → `"less than or equal to 1"`;
- `XdStorageBTree(X)Node`: `"with idgeneration <id>"` → `"with id <id>"` — the word
  "idgeneration" is misused for the id VALUE (BTreeNode 1031, 1613, 1684; BTree 535, 627);
  also `"caouldn't"` → `"couldn't"` (BTreeNode 1684);
- `XdStorageReadWriteLock.lockRead` 217: the timeout message says "Ресурс занят на чтение
  другим потоком" while the actual blocking condition is a foreign WRITE lock — reword to
  name the real blocker (keep it actionable: holder, timeout).

**Dead/ignored parameters and missing wiring:**
- `XdStorageReadWriteLock.logLockContention(transaction)` — the parameter is never used;
- `XdStorageAbstractClassCodeGenerator.buildMethodDefinition(modifiers)` — the parameter is
  ignored, the header is always `public` (wire it or drop it, decision recorded);
- `XdStorageSimpleWrapperClassCodeGenerator.buildClassName` lacks `@Override` (its siblings
  have it).

**Names that lie (all engine-internal — rename is safe):**
- `XdStorageObjectIdField.getIdGeneretorType` — "Generetor" typo in the method NAME;
- `XdStorageAbstractResourcesManager.getFreeResourceId` — mutates state (CAS-reserves a
  counter slot) under a getter name;
- `XdStorageObjectUtils.isWrappableObject` — returns the NEGATION of what the name reads
  (failure ⇒ true); rename to `isNotWrappable`/`failsWrapperCheck` per its real contract;
- `XdStorageClassMetadataRegistry.resolveOrThrow` — two stacked javadoc blocks, the orphan
  describing `findLoadedByName`; keep one honest block.

**Documented-only (verify the doc, change nothing):**
- `XdStorageCacheRecord.getState()`/`getRecordTimestamp()` — duplicate diagnostics,
  R-40-intentional; cross-link the docs.

## Scope / cautions

- **Grep the test estate before touching messages** — some suites assert message text (the
  mux, the range suites, the verify suite assert exact strings); update tests in the same
  commit.
- `XdStorageObjectOperationType`'s duplicate lower/upper-case constant sets (the trigger
  manager checks both spellings) are deliberately OUT of scope: consolidating them is a
  behavior change in trigger dispatch, not hygiene — file separately if ever needed.

## Acceptance criteria

- [x] Every message/name above fixed; `grep` proves zero remaining occurrences.
      (`requred`/`letter or equal`/`caouldn't`/`with idgeneration`/`getIdGeneretorType`
      = 0 across src/main; the `with idgeneration` sweep covered all 41 engine-wide
      occurrences, not only the five named btree sites.)
- [x] Tests asserting old messages updated in the same commit. — none asserted the old
      texts (grep = 0); the rename ripples were updated:
      `AbstractBTreeIndexResourceTest` (mock of the renamed reserver),
      `XdStorageObjectIdFieldTest` (the typo method).
- [x] Full suite green ×2. — 924 tests ×2, 0 failures; the single error in both runs is
      the MassFillAndClean FK veto (task 42's documented state, not this task's scope).

## Done (2026-11-01) — with one PREMISE CORRECTION

- Messages: `required` ×2; "cannot be less than or equal to 1" ×2; the `with
  idgeneration` → `with id` sweep (41 sites engine-wide — the misuse was not
  btree-local); `couldn't`; the read-lock timeout now names the real blocker
  ("заблокирован чужой WRITE-блокировкой (держатель: …)"), mirroring the write-lock
  message's shape.
- `logLockContention(transaction)` — WIRED (not dropped): the WARN now names the
  waiting transaction — the actionable half of the event.
- `buildMethodDefinition(modifiers)` — WIRED via `Modifier.toString` with `abstract`
  stripped (generated bodies have real bodies; empty falls back to `public`). Decision
  recorded: wiring is behavior-visible only for non-public reflected members, of which
  the current generators have none — the suite is green unchanged.
- `XdStorageSimpleWrapperClassCodeGenerator.buildClassName` — `@Override` added.
- Renames: `getIdGeneretorType` → `getIdGeneratorType` (18 sites); the CAS-reserving
  manager getter → `reserveFreeResourceId` — **scoped precisely**: the index
  `XdStorageIndexResourceCache.getFreeResourceId()` is a PURE LOOKUP (no mutation) and
  kept its honest name — the blanket sweep initially renamed both and was corrected.
- **PREMISE CORRECTION (`isWrappableObject`):** the flagged item claimed the name reads
  the negation of the answer. Re-verified against the generated
  `isDummyUnmodifiableWrapper` marker (real wrapper = `false`): the method answers
  {@code true} = wrappable (and true on probe failure = "assume wrappable") — the
  ORIGINAL name is correct. The confusing part was the double negation in the
  implementation (`!checker`); the javadoc now states the polarity explicitly and the
  rename was reverted. No rename for this item.
- `resolveOrThrow` — the orphaned duplicate javadoc block removed; one honest block
  (the identity-preference note folded in).
- `XdStorageCacheRecord.getState()`/`getRecordTimestamp()` — cross-linked as the
  intentional R-40 diagnostic pair; behavior untouched.

Estimate: 0.5 d.

## References

- [28-javadoc-coverage](28-javadoc-coverage.md) — the campaign that surfaced these
- the files named inline above
