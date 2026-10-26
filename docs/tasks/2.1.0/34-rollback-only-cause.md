---
title: "[2.1.0][RBCAUSE] \"marked as rollback only\" must say WHO marked it, WHEN and WHY — first-cause capture + cause-chained commit failures"
labels: [2.1.0-train, P1, engine, bug]
status: open
---

## Summary

A transaction marked rollback-only carries **exactly one bit of state**
(`XdStorageTransaction.rollbackOnly` — an `AtomicBoolean`). When `commit()` later fails with
*"transaction will be rolled back because it is marked as rollback only"*, the application
has no way to learn **who** marked it, **when**, or **why**. Design and implement
first-cause capture: the first mark records its cause (site, root exception, moment), the
commit failures chain and surface it, and the mark itself becomes a one-time observable
event.

## Why — the analysis (code-verified, 2026-10-23)

**The marking sites** (all funnel into `markRollbackOnly()` / `markSelfAndChildrenRollbackOnly()`):

| # | Who marks | Where | What the mark means |
|---|---|---|---|
| 1 | **Generated wrapper lazy getters/setters** (`XdStorageWrapperBlockGettersGenerator:114`, `XdStorageUnmodifiableBlockGetters/SettersGenerator`) | catch `Throwable` around a lazy load/set → mark + throw `XdStorageRuntimeException("couldn't load objects of field X…", e)` — the root `e` is typically a transient lock/latch timeout | **The classic silent poisoning**: the user may catch that exception and CONTINUE (the README-documented asymmetry: CRUD failures do not auto-rollback, wrapper accessors DO mark) — the commit then fails much later, and the original exception is gone |
| 2 | **Search/read paths** (`XdStorageSearchManager:982` and siblings) | catch `XdStorageException` → mark + rethrow wrapped | Same poisoning pattern via search errors |
| 3 | **Timeout sweeper** (`XdStorageTransactionManager:246`) | expired transaction marked rollback-only | correct, but invisible |
| 4 | **Parent sweeps of abandoned nested txs** (`XdStorageTransaction:300-313`, R-40/R-41 machinery) | zombie/expired nested tx force-swept (WARN-logged) + nested-rollback cascade | the cascade direction (downward-only, R-41) matters and is invisible to the victim |
| 5 | Nested-rollback cascade (`markRollbackOnly()` delegation to the global tx) | `XdStorageTransaction:884-890` | the parent learns it is poisoned only from a nested failure — again, one bit |

**The failure sites** — four different generic messages, none carrying a cause:
`"transaction will be rolled back because it is marked as rollback only"` (manager:241),
`"…global transaction is marked as rollback only"` (manager:247),
`"transaction {id} marked as rollback only and will be rolled back"` (tx:393, tx:480).

The diagnosability gap is exactly the field-feedback class ("not enough to understand what
has happened"): the information EXISTS at the mark site (the caught exception, the field
being loaded, the operation) and is **discarded** — the transaction cannot remember it.

## Scope

1. **First-cause record** (`XdStorageRollbackOnlyCause`, immutable value object):
   site descriptor (who/where), root exception (nullable — cascade marks have none),
   wall-clock + tx-age at the mark, marking tx id, a `cascadedFrom` field for
   `markSelfAndChildrenRollbackOnly`/global-delegation, and a repeat-mark counter.
   **First mark wins** (atomically); later marks only increment the counter.
2. **Mark-site overloads**: `markRollbackOnly(String site, Throwable cause)` (and the
   cascade variant). The no-arg forms stay (source/binary compatible) and default the site
   to `"unspecified"`. Every internal site passes a real descriptor — the generated
   wrappers know the entity class and field at generation time and must emit it
   (`"lazy-load of field 'crew' on Ship#ship-5"`), the search manager knows the operation,
   the sweepers know which sweep fired.
3. **Cause-chained failures**: all four throw sites include the cause in the message
   (`"…marked as rollback only by <site> at tx-age <n> ms: <root message>"`) and chain the
   root exception via `initCause` — so `e.getCause()` at the commit failure hands back the
   original lock-timeout/parse-failure.
4. **Observability at the mark**: one WARN per transaction at the FIRST mark (site + root
   exception + tx id); repeat marks increment the counter, never the log.
5. **Public API**: `IXdStorageTransaction.getRollbackOnlyCause()` (nullable); documented in
   EMBEDDING.md § Transactions (the asymmetry paragraph gains: "and the commit failure
   tells you who poisoned the transaction").
6. **Tests**: lazy-getter escalation → commit exception names the getter, the field and the
   root cause (and `getCause()` is the original exception); search-failure mark → named;
   timeout-sweeper mark → named; nested cascade → names the nested tx and the cascade
   direction; first-cause-wins (a second mark does not overwrite); the no-arg mark works
   with the "unspecified" site; metrics counter unchanged.

## Out of scope

- Changing the asymmetry semantics (which failures auto-rollback vs mark) — that is a
  contract decision, not a diagnostics one; the cause capture is neutral to it;
- per-cause metrics dimensions (the single `rollbackOnlyMarks` counter stays);
- async/different trigger semantics (FP-13 territory, re-framed separately).

## Acceptance criteria

- [ ] The cause-capture suite green: every marking site (#1-#5) is reproduced and the
      commit failure names the site, carries the root exception as the cause, and states
      the tx age at the mark.
- [ ] First-cause-wins pinned by test; repeat marks counted not logged (log volume
      assertion).
- [ ] `getRollbackOnlyCause()` documented in EMBEDDING.md § Transactions; the README
      asymmetry paragraph links it.
- [ ] No-arg `markRollbackOnly()` still compiles everywhere (source-compatible);
      generated-wrapper output contains the site descriptors.
- [ ] Full suite green ×2 (BUILD SUCCESS verdict).

Estimate: ~1–2 dev-days (the generator change is the careful half).

## References

- The five marking sites (table above) — `XdStorageTransaction.java:884-905`,
  `XdStorageTransactionManager.java:241-247`, `XdStorageSearchManager.java:982`,
  the code generators (`code/*BlockGetters/SettersGenerator`)
- [EMBEDDING.md](../../EMBEDDING.md) § Transactions (the asymmetry paragraph to extend)
- R-31/R-40/R-41 history (OPTIMIZATION-PLAN.md) — the sweeper and nested-cascade semantics
  this diagnostics layer observes
