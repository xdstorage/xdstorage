# Debug playbook — the W-4 marathon methodology

The protocols proven across the 2026-11-15 → 2026-11-20 W-4 marathon (the
overlay prototype, five core fixes, the FKBENCH measurement). The rule: every
root cause below was initially "confirmed" by a wrong theory — the probes and
the traces are what separate fact from story.

## 1. The probe protocol

1. **Bracket the mutated field at every hop** (the hop-probe method): facade
   before/after → resource read → btree entry/exit → cache record → mutator →
   phase-1 collector. The flip between two adjacent probes NAMES the culprit.
2. **Print the tx id at every probe** (`substring(0,6)`): the transaction ids
   ROTATE between the outer tx, the commit's internal tx, and the operation
   txs — a trace without ids is unreadable.
3. **Print the entry AND the exit of a suspect branch** (entered / break-reason
   / failed-exception). A branch that silently breaks is invisible in logs.
4. **`new Exception("caller-trace").printStackTrace()` at a throw site** names
   the exact caller when the stack is hidden by a message-preserving re-throw.
5. **`-XX:-OmitStackTraceInFastThrow`** via -DargLine when a repeated exception
   loses its stack (fast-throw).
6. **Probe lifecycle, not just failures**: UNDO-OP prints per replayed entry;
   APPLY ops-count print; STAGE prints per op. The 2026-11-20 veto-path bug
   was invisible until the apply/undo prints existed.
7. **Strip all probes before finalizing** (the blanket `println("[DIAG]` line
   filter) — and re-read the stripped file: a blanket strip can orphan the
   continuation line of a multi-line statement (proven twice).

## 2. The verification hierarchy (each step gates the next)

1. compile → 2. the single failing test (isolated, fresh JVM for the stack) →
3. the test CLASS → 4. the family (the FK estate / the suite) → 5. the FULL
quick-gate ×3 (the release claim) → 6. FKBENCH (the write-path claims).

Never skip 4→5: the estate green + the full gate red happened (the MassFill
convoy). Never claim ×3 green from ×1: the flake families are probabilistic
(the MassFillSecond survivors: 1-4 intermittent).

## 3. The trap registry (each cost a debugging cycle)

| Trap | Symptom | Rule |
|---|---|---|
| **CHM recursive update** | fast-thrown `IllegalStateException: Recursive update`, no stack | a `computeIfAbsent` lambda must NOT put into the same map |
| **Merge later-wins** | the merged read shows a pair the op-replay removes | stageDelete prunes stagedInserts (and vice versa) |
| **Blank final in try+catch** | "variable might already have been assigned" | non-final temp + final alias |
| **Interface method missing** | mocks fail to compile | new interface methods = default (the mocks inherit) |
| **storage.load inside phases** | "stayed invisible past the wait budget (3000ms)" ×N, 3s stalls | infra READ COMMITTED: never load what you can take from the instance registry |
| **The manager rollback-only early-return** | undo hooks in rollbackInternal never run for vetoed txs | resolution hooks must ALSO live in commitInternal failure paths |
| **The apply before the veto** | the apply's physical deletions persist after a vetoed rollback | the undo must fire in the commit failure path BEFORE rethrow |
| **The overlay keyed by the wrong tx id** | apply finds empty; stage lost | key by overlayResolutionOwnerId() (global ?? own) — the internal/outer split |
| **The recovery skipped under concurrency** | the applied deletions persist (in-memory tree) | overlay trees are EXCLUDED from recovery — discard+undo instead |
| **save-as-upsert** | "is exists" on a re-save | update() for existing ids; the cascade already upserts |
| **toString-vs-id assertions** | `String.valueOf(entity)` never equals the id | assert entity.getId() |

## 4. The failure-family decision tree

```
Data loss / vanished record?
├─ after a vetoed/failed commit → the apply-undo hole (overlay: APPLIED_UNDOS
│   replay; pre-overlay: the in-place tree mutation survived — task-41 family)
├─ after a successful commit + a purge → unreachable subtree: a parent link
│   was contaminated by a rolled-back in-place collection mutation (task-40
│   family: prevObject aliasing; the read-time prevSnapshot prototype fixes)
└─ intermittent, load-dependent → the commit convoy (lockForCommit timeouts)
    or the starved branch (a window too short for the op mix)

Dangling reference / double entry?
├─ expected 1 was 2 → the merge later-wins missing (stageDelete prunes
│   stagedInserts)
└─ expected N was 0 → the lost-update window (a stale staged state won)

Reads return null for existing records?
├─ the in-memory tree mutated by an applied-then-not-undone batch → the
│   overlay-undo hole
└─ the id-index file vs cache divergence → the fresh-parse recovery gate
    (single-thread only)
```

## 5. The HEAD-baseline experiment (the stash method)

When a failure looks like a regression: **measure the TRUE HEAD baseline** —
`git stash push` (the scoped paths) → the test run → `git stash pop`. The
2026-11-21 case: the BTreeStress phantoms looked like an overlay explosion
(334 → 27705) — the stash experiment showed the HEAD baseline = **24810** (the
334 was a lucky draw; the same race's variance). Without the baseline the
session would have chased a non-regression. Rules: the stash scoped to the
touched paths; verify the pop restored everything (the status count); the
experiment run BETWEEN the stashes only.

## 6. The trap registry, part 2 (the convoy session)

| Trap | Symptom | Rule |
|---|---|---|
| **The intra-band tie order** | the lock waits = the EXACT timeout multiples (12s = 4 × 3s); the same resource named by many waiters | the acquisition sort with the equal commit-order values MUST tie-break by the resource id — otherwise the multi-resource acquisition deadlocks per the arbitrary tie order |
| **The prevSnapshot single-point capture** | a record restored to NULL after a foreign rollback (the load → null → "reference cannot be null") | the per-lifecycle capture: the read-record creation OR the lazy mark-time fallback (never null for a live object); clear at the publish AND the rollback (the staleness) |
| **The identity-vs-content tension** | the invariant tests pin assertSame vs the anti-aliasing needing a clone | the ROLE SPLIT: the reference field keeps the identity contracts; the snapshot field serves only the in-place content restore (fillObject) |
| **The RW-latch writer starvation** | the latch-held-walk experiment: the phantoms exploded, the throughput halved | never hold a RW-latch across an IO-bearing walk under a write storm; the prewarm stays outside the latch |
| **The substring on the mock ids** | StringIndexOutOfBounds in the PROBE code (the mock txs have 4-char ids) | the probes use the length/null-safe helpers (shortTx) |
| **The unbounded future.get()** | the whole gate hangs forever on a stuck worker | every future.get has a timeout; a hang = the loud TimeoutException = the analysis material |

## 7. The benchmark protocol (FKBENCH, task 50)

`XdStorageFkContentionBenchmark`: FK on/off × 1/5/10 threads; each worker owns
a universe (zero root-record contention — the only shared choke = the class
btrees); serial warmup (the first write ≈ 950ms infra serialization); 5s
window; only in-window commits counted; the stall/sweeper/gate counters.

Read: fk=OFF t=1 ≈ the base write path (WRTPATH domain); fk=ON t=10 vs fk=ON
t=1 = the choke price. The 2026-11-20 measurement: **167 → 1.2 tx/s (140×
collapse), FK-independent** (the id-index change-slot choke).

## 8. The measurement trap registry (the research/experiment wave, 2026-11-22)

1. **Surefire cuts exception stacks at the test boundary** (a CCE showed ONE frame);
   `-XX:-OmitStackTraceInFastThrow` through assertDoesNotThrow does NOT help —
   catch the exception LIVE in the test and print getStackTrace() (the true
   75-frame stacks).
2. **grep the run's WARN/ERROR lines FIRST** — the engine announces what it drops
   (the JavaBeans field drop WARN was in every run before anyone read it).
3. **surefire -Dtest substring patterns silently miss classes** (a short pattern
   matched nothing and the phase "passed" empty); stale
   `target/surefire-reports/` lie in aggregations — rm before reading.
4. **Measuring the "cold FIRST lookup" inside the scan's session measures the
   PREPARED CACHE (50µs), not the cold path (13ms)** — the lookup rounds must
   REOPEN fresh (the feasibility probe's protocol was right; the experiment's
   first draft wasn't).
5. **The resources capture their codec at construction** — the factory swap reaches
   only the not-yet-created resources; the conversion swaps the live resources'
   reader/writer fields too.
6. **The shutdown checkpoint fires per resource and only with a journal tail** —
   a load-only session has nothing to rewrite; stage a per-fragment-stride
   idempotent touch to give every resource's journal a tail.
7. **The char-stream SPI shapes any binary format**: the pipes are UTF-8 Readers/
   Writers — a byte-level format needs the SPI extended (the FP-9 design round's
   item); a char-framed codec (length-framed strings, bit doubles) measures the
   honest achievable number through the existing pipe.
8. **An idempotent update never rewrites the id→fragment routing** — the index
   journal stays empty and its checkpoint never fires; the index base converts at
   the file level (readData → the records → the codec).
