# W-4 Write Path — program status, completion plan, pinning tests

**Status:** the write core is the top priority after the 2.1.0 release.
Today's session (2026-11-20): FKBENCH Step 0 (the collapse measurement), the
overlay prototype (task 53) at 365/372, three integration layers diagnosed
with traces. The completion plan is below; the full history lives in
[task 53](../tasks/2.1.0/53-tree-overlay.md).

---

## 1. The measured state (FKBENCH Step 0, task 50)

The concurrent block-write (the MassFill profile), each worker owns a universe
(the contention only on the class-level B+tree resources):

```
fk=OFF t=1: 167.4 tx/s p50=5ms   →  fk=OFF t=10: 1.2 tx/s p50=12s   (a ~140× collapse)
fk=ON  t=1: 138.8 tx/s p50=7ms   →  fk=ON  t=10: 0.8 tx/s p50=18s1
```

**The attribution (by the failures — the class-level btree resource files in
the FK-off arm):** the choke = the class-level B+tree change-slot held by the
contour until commit. FK-independent (the id-indexes — the core); the FK trees
— the second layer of the same mechanism. The single-threaded write is
healthy (167 tx/s; the FK overhead ≈17% — within the W-4a band ≤2×).

The full methodology and the numbers:
[task 50](../tasks/2.1.0/50-fk-cost-benchmark.md), the class
`XdStorageFkContentionBenchmark`.

## 2. The three W-4 layers (diagnosed, traces in task 53)

### Layer 1: the internal-TX id split
The btree operations stage under the id of the **internal transaction**
(R-41); the resolution (apply/undo/discard) runs under the id of the **outer**
one. The veto path: the apply before the phases, the rollback-only
early-return of the manager skips rollbackInternal → the applied tree
deletions persist → the deferred-id child vanishes.
**The fix skeleton (implemented in the prototype)**: overlayResolutionOwnerId()
(getGlobalTransactionId ?? getTransactionId) — the single key for the staging
and the resolution. The remainder: the interaction with the rollback
compensations (layer 2 of the plan below).

### Layer 2: the lost-update window stage→commit
A stage without the commit-time version revalidation: a stale staged state
wins ("Рассинхрон expected 1 was 0" — a late insert wiped).
**The fix**: the commit-time conflict revalidation in phase-1 — re-check the
record versions before writing the files (the write core; task 40-adjacent).

### Layer 3: the commit-lock convoy
Each commit holds the shared trees through both phases → the queues to the
8-20s timeouts.
**The fix**: the granularity (holding only during own apply segment) — the
design is required.

### Layer 4 (after 1–3): the read-side snapshot cost
Every load clones the pre-tx state (~2× the read cost) — the mandatory price
for the soundness until layer 2. The optimization: the lazy snapshot on the
first mutation (the wrapper interception — the generated code).

## 3. The completion plan (the sequence with the verification after each step)

| Step | Work | Criterion | Estimate |
|---|---|---|---|
| 1 | Restore the overlay from the patch + the **internal-TX bridge** (the owner-id keying on the stage and the resolution; the probes: STAGE-D/STAGE-I/APPLY/UNDO-OP) | the FK estate 372/0/0; the resolver 5/5; the veto path: the applied undo visible in the trace | 1 session |
| 2 | **The rollback compensations under the overlay**: the R-57 key compensations for the overlay trees → no-op (the discard covers); order the resources-rollback (DATA → INFRA) | the deferred-id resolver green; the R-57 compensations stop failing on load-by-reference | +0.5 |
| 3 | **The veto-path undo**: the RBCOMMIT gates and exceptions[0] — the undo of the applied ops before the rethrow | the resolver: after the veto the child loads (not null); "reference cannot be null" disappears | +0.5 |
| 4 | MassFillSecond: remove the Semaphore (the real contention) | deletes > 0 stable; orphans = 0 | +0.5 |
| 5 | **The lost-update revalidation** (layer 2): the phase-1 version re-check of the staged records | the concurrent insert+update: no silent data loss (one tx fails loudly, not silently) | 1–2 sessions |
| 6 | **The convoy** (layer 3): the commit-lock granularity | FKBENCH t=10 ≥ 100 tx/s | 1–2 sessions |
| 7 | **The lazy snapshot** (layer 4): the wrapper interception of the first mutation | the read cost back to ≤1.2× of the base | 1 session |
| 8 | The benchmarks (task 50 full + the BENCHMARKS.md update) | the canonical numbers on the new engine | 0.5 |

**Total: 5–8 sessions.** Each step = a separate branch/commit + the full estate.

## 4. The pinning tests (the list by step)

### The base estate (pinned at EVERY step)
| Suite | Count | What it pins |
|---|---|---|
| The FK estate (`io.github.xdstorage.foreignkey.**`) | 372 | the veto semantics, the dissolutions, the reopen, the deferred-ID |
| MassFillFirst | 1 | the purge integrity, the concurrent fill |
| MassFillSecond | 1 | the subtree delete, the audit model |
| `XdStorageForeignKeyReopenKeyTypeTest` | 3 | the reopen key types |
| The cache suites (CACHE2PC ×11 + others) | ~100 | the cache contracts, the 2PC |
| The full quick-gate | 1316 | everything above + the regressions |

### The layer-targeted tests
| Layer | Test | Assert |
|---|---|---|
| 1 internal-TX | `FxForeignKeyDeferredIdResolverTest` 5/5 | deferred-ID: parent-first resolves; child-first vetoes; the rollback restores |
| 1 internal-TX | NEW `TreeOverlayInternalTxTest` | the staging under the internal id → the apply at the commit → the data intact |
| 2 lost-update | NEW `ConcurrentUpdateLostUpdateTest` | 2 txs: update+update of one record → the second fails LOUDLY (not silently) |
| 3 convoy | FKBENCH t=10 | tx/s ≥ 100, p99 < 100ms |
| 4 snapshot | MassFillSecond | deletes > 0 stable at the 10s window (not 15s) |
| final | MassFillFirst ×3 + Second ×3 + the quick-gate ×3 | the release gate |

### The negative pins (the regressions that must not happen)
| Test | The anti-pattern |
|---|---|
| `XdStorageTreeStructureRollbackTest` (remove @Disabled) | the collapse-rollback must be GREEN (rollback=discard) |
| `XdStorageBTreeFullCollapseTest` | the full collapse → the files gone, no resurrection |
| `XdStorageFirstCommitterWinsTest` | the stale write rejected; no over-rejection |
| MassFillSecond | deletes=0 (starvation) is NOT a success even with survivors=0 |

## 5. Related documents

- [BTREE_MUTATION_OVERLAY.md](BTREE_MUTATION_OVERLAY.md) — the overlay design and prototype
- [TX_COMMIT_ROLLBACK_MODEL.md](TX_COMMIT_ROLLBACK_MODEL.md) — the commit/rollback/isolation
- [FK_INTEGRITY_MODEL.md](FK_INTEGRITY_MODEL.md) — the integrity canons
- [task 53](../tasks/2.1.0/53-tree-overlay.md) — the working journal
- [task 50](../tasks/2.1.0/50-fk-cost-benchmark.md) — FKBENCH
- [W4-DESIGN.md](../W4-DESIGN.md) — the original W-4 design (W-4a/c implemented)
