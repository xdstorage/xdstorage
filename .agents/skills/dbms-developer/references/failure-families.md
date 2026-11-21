# Failure families — the registry with signatures and proven mechanisms

Every family: the signature (what you see), the proven mechanism (what is
actually broken), the status (fixed / open / documented), and the reference.
Compiled from the 2026-11 W-4 marathon + the FK release. If a new bug matches
a family signature, check the family's known mechanism FIRST.

## 1. The commit/rollback families

### 1.1 prevObject aliasing (task-40 family) — FIXED PROTOTYPE, REVERTED WITH IT
- **See**: survivors after purge/rollback; "galaxy with ZERO commits"; the
  audit mismatch "expected 1 was 0".
- **Mechanism**: markUpdate/markDelete: prevObject = the raw reference to the
  canonical instance the caller already mutated in-place → rollback restores
  the same mutated instance → any later committer of that record makes the
  orphan reference durable.
- **Fix (verified 0 survivors ×5, reverted with the overlay)**: prevSnapshot —
  a clean clone captured at newReadRecord (BEFORE handing the instance to the
  caller). LIMITATION: materializeForCache (unwrapAndClone) throws on null-id
  nested references (the deferred-ID flow) — use the plain copier
  (XdStorageObjectUtils.cloneObject) or the lazy first-mutation snapshot.

### 1.2 The lost-update window (stage→commit) — OPEN
- **See**: "Рассинхрон! expected N was M" — the inserted galaxy wiped from the
  universe collection.
- **Mechanism**: the version check fires at STAGE time only; a foreign commit
  between the stage and the commit publishes without revalidation; the stale
  staged state wins.
- **Fix direction**: commit-time conflict revalidation in phase-1 (re-check
  the record version before writing files). Scope: the write-path core.

### 1.3 Task-41 structural unsoundness — OPEN (the overlay solves the tree half)
- **See**: "B+Tree node N is referenced but missing"; the collapse-rollback
  RED repro (XdStorageTreeStructureRollbackTest, @Disabled).
- **Mechanism**: in-place btree structure mutations (splits/moves/collapses)
  + rollback: the mutated in-memory graph cannot be repaired in place (two
  failed compensation attempts).
- **Fix**: the tree-overlay (task 53) — the staging removes the physical
  mutation until commit; rollback = discard. The prototype: 365/372.

### 1.4 The veto-path applied-undo hole — FIXED IN PROTOTYPE, re-verified needed
- **See**: a child vanishes after a vetoed delete commit (deferred-id resolver:
  load null → delete(null) → "reference cannot be null").
- **Mechanism**: commitInternal applies the overlays BEFORE the phases; the
  veto fires at the FK phase-1 → the failure → the manager's rollback-only
  EARLY-RETURN skips rollbackInternal → the applied tree deletions persist.
- **Fix (in the prototype)**: the undo+discard at every commitInternal failure
  path (exceptions[0] ×2, the RBCOMMIT gates ×2) + the manager's early-return.

### 1.5 The R-57 compensation ordering — KNOWN, ORDER-SENSITIVE
- **See**: "reference cannot be null" inside rollbackIndexKey* — the load-by-
  reference of a record the DATA resource's rollback has not restored yet.
- **Mechanism**: rollbackInternal iterates resources.values() (HASH ORDER).
- **Fix (prototype)**: sort the rollback loop (DATA before INFRA via
  isInfrastructureResource). VERIFY: the full estate after any reorder.

## 2. The concurrency families

### 2.1 The commit-lock convoy — **RESOLVED 2026-11-21 (the deadlock root)**
- **See**: "cannot be locked for commit by transaction ... (Timeout Ns
  expired)" on the SHARED trees/nodes; the waits are EXACT MULTIPLES of the
  lock timeout (12s = 4 × 3s); the sweeper cascades.
- **Mechanism (proven by the per-resource lock-wait probes)**: the acquisition
  order tied across the whole INDEX band (all the tree/node resources share
  one commit-order value) — `Collections.reverseOrder()` left the tie order
  ARBITRARY per transaction → A held node-3 waiting node-12 while B held
  node-12 waiting node-3 → a DEADLOCK broken only by the timeout cycles.
  NOT the lock hold duration (the earlier hypothesis) — the lock waits
  measured 3001ms inside ONE FK-tree phase-1 (the visibility wait) were a
  separate contributor (fixed by the live-canonical serving).
- **Fix**: the TOTAL acquisition order — the tie-break by the resource id
  (globally unique) in the commitInternal sort. The convoy became a queue.
  The FKBENCH: 0.8–1.2 → 121–158 tx/s at t=10 (the ≥100 target met); p99
  12–21s → 84–113ms; zero failures. ONE comparator line.
- **The lesson**: "a convoy" whose waits are the timeout multiples is a
  DEADLOCK, not a slow holder. Measure the wait distribution BEFORE designing
  the granularity fixes.

### 2.2 The internal-TX id split — FIXED IN PROTOTYPE
- **See**: the stage lost (apply finds empty); the undo misses.
- **Mechanism**: the btree ops run under the INTERNAL tx (R-41); the stage
  keys by the internal id; the resolution keyed by the outer id.
- **Fix**: overlayResolutionOwnerId() (global ?? own) as the single key on
  both sides; overlayResolutionIds() for the id-set iteration.

### 2.3 The infra visibility budget — BY DESIGN
- **See**: "stayed invisible past the wait budget (3000ms) — serving absent".
- **Mechanism**: infra caches are READ COMMITTED ALWAYS (the 2026-10-25 gate):
  own uncommitted infra writes are invisible to their own tx's READS.
- **Rule**: never storage.load(tree/record) inside phases; use the in-memory
  instance or the instance registry. The load-based overlay wire-ups cost 3s
  stalls per call (the proven overlay lesson).

## 3. The FK-specific families

### 3.1 The delete-child-alone veto — CANON (C1)
- **Signature**: "Not all foreign keys are deleted" at commit after
  delete(child) without the parent's collection update.
- **Mechanism**: the del-counter armed by waitForParentDelete; the phase-1
  fires at ≠0.
- **The overlay rule**: the flow does NOT touch the trees (the veto is the
  counter) — if the trace shows STAGE/DELETE ops for it, the flow diverged.

### 3.2 The dangling-reference end-state veto — CANON (C2)
- **Signature**: the same counter veto from a different op order (update after
  delete, remove-one-delete-another).
- **The arbitration key**: the END STATE (who references the deleted record at
  commit), not the operation order.

### 3.3 The orphan-insert veto — CANON (C7)
- **Signature**: "parent record does not exist — parent must be persisted
  before the child".
- **Mechanism**: the FK phase-1 semantic check; the deferred-ID machinery
  resolves DATABASE_GENERATOR ids at the parent's save.

### 3.4 The MassFillSecond starvation family — PARTIALLY FIXED
- **See**: deletes=0; the DEL branch never entered (audit-empty break) or the
  commit-convoy timeouts.
- **Mechanism**: the convoy of commit locks + the window too short for the op
  mix; the warmup phase + the backoff + the sharding are the mitigations.
- **The endgame**: the overlay (the tree hold shrinks to the apply segment) +
  possibly the commit-lock granularity.

### 3.5 The BTreeStress phantom family (the R-56/multi-scan race) — OPEN (pre-existing)
- **See**: "🚨 ФАНТOМ/ПOТEРЯ Данных! Ожидали стабильных 200, а получили: 0"
  (XdStorageBTreeStressTest) — the parallel finds against the heavy splits
  intermittently return 0 for a key whose 200 duplicates never left the tree.
- **Mechanism**: the multi-tree scan = the hand-over-hand walk over the leaf
  chain; the walk releases the TREE read latch at the first leaf (node.read's
  toUnlock=tree); the prewarm window (the firstLeaf capture → the stub
  resolution → the walk) lets a split move the keys to the new leaves while
  the walk traverses the STALE chain from the captured firstLeaf → 0.
- **Measured**: the HEAD stash-verified baseline **24810 phantoms** (the
  overlay state 27.7k — the same race's variance; NOT an overlay regression).
- **The failed experiment (do not repeat blindly)**: holding the tree read
  latch through the walk (toUnlock=null) → 27k phantoms — the RW-latch writer
  starvation + the node latch interplay. The proper fix = the version-validated
  retry with a resettable viewer, or the snapshot-leaf-chain — the dedicated
  design work.
- **Test hygiene landed**: the bounded future.get(60s) — the intermittent hang
  (the workers stuck in the latch waits + the unbounded get) now fails loudly.

## 4. The registry rules

1. A family moves to "fixed" ONLY with the verification chain done (the
   estate ×1 + the target tests ×3) and the memory/task updated.
2. A "fixed" family that re-opens = revert the fix, document the interaction,
   re-scope. (The 2026-11-20 overlay revert: 30f/46e broad regression.)
3. New families: add the signature, the mechanism, the repro, and the probes
   that found it — the next debugger starts from the registry, not zero.

## 5. The persistence/migration/codec families (LAYOUTMIG 51 + JRNLDATA 54 + BINFEAS 33)

| Signature | Mechanism |
|---|---|
| "Field 'X' ... has a getter but no matching setter — DROPPED from persistence" (WARN) | the JavaBeans contract: the field silently vanishes from persistence; NOTHING round-trips (no seed, no commit, no rollback) — the test-entity premise bug; the setter's param TYPE must match the declared field type exactly |
| "uses on-disk format vN, but this library supports up to vM" | the format-marker gate: v3 = the 2.1.0 layout generation (LAYOUTMIG rewrites 2→3 after the migration; the downgrade refusal is REAL) |
| "the stream is not BF1 (got 'objects:')" | a YAML base read by the binary reader — the conversion did not cover that file class (the index base converts at the file level: an idempotent update never gives the index journal a tail, its checkpoint never fires) |
| NPE `putIfAbsent` in fillCache after a codec round trip | the YAML readObjectData convention: the id lands in setId(), NOT the properties — a record writer reading properties writes null ids |
| "cannot be replayed ... factory is null" | the journal replay type-checks `instanceof XdStorageYamlIOFactory` — a codec factory must EXTEND the concrete YAML factory, not implement the interface |
| "I/O error reading resource <indexes/...>" right after a conversion | the resources CAPTURE their reader/writer at construction — the locator swap alone doesn't reach live resources; swap their fields or convert before any touch |
| "layout migration failed at '...' — the storage is NOT opened" | the LOUD failure policy (LAYOUTMIG): an unmovable file refuses the open rather than continuing with a silently empty id index (the first save would clobber fragment-0) |
