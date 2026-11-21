# W-4 Design: index journaling and the write-path fix

Status: **W-4a + W-4c IMPLEMENTED (2026-10-23, task 32)** — index journaling is the
default write path; gate numbers in docs/perf/w4a-write-scaling.md (small write at N=50k:
64.9 → 0.13 ms/tx, the ≤3× gate met at 0.48×) — canonically confirmed 2026-10-24
(`writeDataHeavy` 8,967 journal-on / 3,098 journal-off) and **re-confirmed on the
BENCHREFRESH set 2026-11-21 (task 43)**: `writeDataHeavy` 8,055 journal-on / 3,070
journal-off = the **2.6× journal ratio holds** (the W-4a gain survives the full 2.1.0
feature set); `writeDurable` fsync ratio intact (~95×). The one real regression on the
new set — `saveAndDeleteIndexedShipWithCrew` -24% code-level — belongs to the
correctness wave (the TREEOVERLAY/W-4b layers, task 53's ledger), NOT to W-4a; the
full honest delta lives in BENCHMARKS.md § the honest delta vs BOTH predecessors.
W-4b/W-4d remain gated as designed (the W-4b readiness question answered by the same
data: the search-write cost is visible and bounded).
Design delivered by task 31 (docs/tasks/2.1.0/31); the implementation task is
[docs/tasks/2.1.0/32](tasks/2.1.0/32-w4-write-path-implementation.md).
Supersedes the W-4 sections of [WRITE-PATH-DESIGN.md](WRITE-PATH-DESIGN.md) (W-1..W-3 remain
accurate there and are reused, not redesigned). Rules honored: design round before code,
kill-9 recovery tests, honest benchmark verdicts.

**2026-11-20 update**: the concurrent-write layer (B6-B8) mapped and measured
(see the ledger below + [analysis/W4_WRITE_PATH_STATUS.md](analysis/W4_WRITE_PATH_STATUS.md)
— the program status, the overlay prototype (task 53), the continuation plan,
and the pinning-test list).

---

## 1. Analysis — where a commit actually goes

### 1.1 The resource map (code-verified, 2026-10-23)

Phase-1 of the 2PC iterates the touched resources serially. What each does:

| Resource | Class package | Phase-1 write | Cost per touched commit |
|---|---|---|---|
| User data classes | — | **journal append** (W-1) + full-file rewrite only at checkpoint (>16 MB journal) | O(delta) |
| Search-index records (`search.data`) | not btree → not infra | **journal append** (rides the generic `XdStorageResource`) | O(delta) |
| Structure records (post-BUG-A) | structure | **journal append** | O(delta) |
| **Hash id-index** (the DEFAULT id index) | `index.hash` — own resource class | **full-file rewrite**: `writeObjects(cache.readUnsafe(tx))` | **O(N) IO + O(N) CPU** — the wall |
| BTree id-index / search key trees / BTree nodes | `io.github.xdstorage.btree` → infra/structural exclusion | per-node files, full rewrite of each **touched** node + tree record | O(depth) small files per structural change |
| Structure journal file / references | mixed | full-file on touch | O(size) on touch |

The journaling gate is `journalWrites && !isInfrastructureCache() && !isStructuralNodeCache()`
where infrastructure = the class *package* contains `io.github.xdstorage.btree`
(`XdStorageResourceCache.java:86`). The hash id-index resource **does not inherit the
journaling path at all** — it is its own resource class whose phase-1 is
`writeObjects(readUnsafe)` (`XdStorageHashIndexResource.java:216-222`).

### 1.2 The scaling law (the honest shape of the wall)

Per touched commit, with the DEFAULT configuration (hash id index, one search index):

```
commit ≈ C_fixed + Σ journal appends (small) + O(N_ids)·k_hash + O(depth·fields)·k_node
                             └── the hash index full rewrite ──┘
```

- **At N ≈ 10³** everything is small: the canonical save+delete measures ~731 ops/s
  (~0.7 ms/commit across 2 commits) — the wall is invisible.
- **At N = 10⁴–10⁵** the O(N_ids) term dominates: the hash index file is MBs, rewritten on
  every write commit, plus `readUnsafe` iterates all cached index records (O(N) CPU) —
  this is precisely the engine's own claimed design point (DECISION-GUIDE), which is why
  the write envelope and the heap ceiling bite together.
- **Correction to task 31's "~13 ms commit"**: that figure was TRACE-era (BENCHMARKS
  history). The canonical commit is sub-millisecond at benchmark N — the wall is a
  **scaling wall**, and the acceptance measurements below are therefore defined at
  N = 5 000 and 50 000, not at the tiny benchmark N.
- **Surprise from the data**: the BTree id-index at t=64 is already near-delta on inserts
  (PERF study §4: 138 node write-outs for a 5 000-object insert) — **the hash id-index,
  the default, is the wall**, not the BTree.

### 1.3 Bottleneck ledger (ranked; shares to be confirmed by the W-4a spike)

| # | Bottleneck | Evidence | Fix family |
|---|---|---|---|
| B1 | Hash id-index full-file rewrite per touched commit — O(N) IO **and** O(N) `readUnsafe` CPU | code (§1.1) + PERF §4/§5 index behavior | **W-4a: journal it** |
| B2 | Search key-tree node rewrites — O(depth × indexed fields) touched-node rewrites per write | PERF §7 (indexed insert 5× swing by t) | **W-4b: logical-op journal** |
| B3 | Phase-1 serialization across resources is serial (per-resource lock order) | WRITE-PATH-DESIGN §1 | accepted for now; re-evaluate after W-4a/b measurements |
| B4 | Heap ≈ DB: no eviction; open parses everything; scans need the cache | SRE plan §2.3; F-3 | **W-4d: disk-anchored scans** (separate section, highest risk) |
| B5 | `durableWrites=false` — the durability knob | task 23 decision (kept off with data) | not a perf bug; documented |
| B6 | **Concurrent-write collapse ~140×** (measured 2026-11-20, FKBENCH Step 0): the class-btree change-slot held per-tx until commit — id-indexes, FK-independent | [task 50 Step 0](tasks/2.1.0/50-fk-cost-benchmark.md) + [task 53 overlay prototype](tasks/2.1.0/53-tree-overlay.md) | **the tree-overlay program** — the prototype 365/372, three integration layers remain (see [BTREE_MUTATION_OVERLAY.md](analysis/BTREE_MUTATION_OVERLAY.md), [W4_WRITE_PATH_STATUS.md](analysis/W4_WRITE_PATH_STATUS.md)) |
| B7 | **The commit-lock convoy** — each commit holds shared trees through both phases → queues to 8-20s timeouts under ≥5 concurrent commits | MassFillSecond diagnostics 2026-11-20 | the commit-lock granularity design (post-overlay) |
| B8 | **The lost-update window stage→commit** — no commit-time version revalidation | MassFillSecond "Рассинхрон 1 vs 0" 2026-11-20 | the commit-time conflict revalidation (phase-1) |

---

## 2. The design

### W-4a — Hash id-index journaling (the big lever, lowest risk)

**Mechanism**: the hash id-index resource joins the W-1 journal machinery: phase-1 appends
`UPSERT(id → locator)` / `DELETE(id)` redo records to `<indexbase>-journal.yaml`
(JRNLNAME/task-24 naming); open replays the journal onto the freshly-parsed index
(last-writer-wins by id — idempotent); checkpoint = full rewrite + journal reset (existing
machinery, `journalCheckpointBytes` shared or per-resource — design note in impl).

**Why it is low-risk where W-3's infra exclusion was right**: the W-3 soak excluded
*btree* infrastructure because replaying serialized **node-graph snapshots** diverged from
the canonical live graphs (R-20). The hash index is a **flat record file** — replay is
idempotent by id with no graph identity to diverge. The one replay hazard is known and has
a landed pattern: **BUG-C's key-type restoration** (`restoreKeyValueType` via
`objectClInfo`) must run on replayed index records — numeric ids must not come back as
raw Strings (BUG-C fixed it for the fill path; the journal replay path reuses it).

**Recovery**: W-1's torn-tail policy verbatim (discard after last sentinel + WARN); kill-9
mid-journal tests are the gate. **Migration**: additive — existing folders have no index
journal (checkpoint-serial-0 semantics, W-1's legacy story); no format bump.

**Config**: `indexJournalWrites` (default **off** → soak → default on in the same train if
the soak is green; the flip is a MINOR config-default change per RELEASE-PLAN).

### W-4b — Search key-tree logical-op journal (the harder lever)

**Mechanism**: journal **logical operations** per search tree — `(treeId, op=ADDKEY|DELKEY,
keyValue, objectId)` — and replay them **into the live canonical tree** at open, through the
same code path live inserts use (so splits/rebalances happen naturally and BUG-E-class key
types are built, not parsed). This is deliberately NOT the W-3 approach (replaying serialized
node snapshots — the divergence W-3's soak caught): the journal never stores tree topology,
only the operations the live tree replays against itself.

**Risks**: replay cost at open is O(un-checkpointed ops × depth) — bounded by checkpoint
frequency (W-4c); the replay must go through the canonical registry's trees (R-20) and be
idempotent under re-replay (an ADDKEY for an existing pair is a no-op — the tree's own
idempotent insert, verified in W-4b's suite); triggers none (trees are infra).

**Config**: `searchIndexJournalWrites`, same flag lifecycle as W-4a.

### W-4c — Checkpointing bounds

- **Checkpoint on clean shutdown** (task 23's deferred item — now designed in: at
  `shutdown()`, after quiescing, run the existing full-file checkpoint for every resource
  with a non-empty journal). Effect: after any clean close, base files are authoritative
  and every journal is empty — a power loss *after* a clean shutdown loses nothing even
  with `durableWrites=false`.
- **Journal growth bounds**: size-threshold checkpoints (the existing 16 MB knob,
  evaluated per-resource-class); open-time guard: a journal larger than the threshold at
  open triggers a checkpoint after replay (self-healing).

### W-4d — Disk-anchored scans + record-cache eviction (the heap ceiling)

The F-3 thaw. **Kept as a separate design section with its own gate** — it touches the
read path (scans iterating records not in cache must re-anchor to base+journal), which is
the riskiest surface in the engine. v1 design sketch: LRU over committed, unlocked,
non-infra records; a scan that meets an evicted record re-loads it from the base file
(+ journal replay state already in memory); the per-transaction materialization budget
(W-2) already bounds the clone side. **Design-round input (OPTIMIZATION-PROPSAL-01, see its
analysis file): off-heap caching** (Direct ByteBuffers / FFM — the MapDB/Chronicle approach)
is the alternative to on-heap LRU; the release=8 bytecode constraint rules out the modern
FFM API and makes DirectByteBuffer lifecycle management fragile on 8, so the round must weigh
it honestly against this on-heap sketch before choosing. **Decision point in W-4d's own design note**: land in
this train only if W-4a/b land cleanly and the heap data (task 20's N50K results) demands
it — otherwise it is 2.2.0's opening item.

### Alternatives evaluated and rejected

| Alternative | Verdict | Reason |
|---|---|---|
| Group commit (batch txs → one write-out) | fallback, not the fix | still O(file) per flush; latency spikes; kept as the emergency valve if journaling stalls (WRITE-PATH-DESIGN §3 stands) |
| Full reindex-on-open instead of index journals | rejected | O(N) open cost contradicts restart-safety; worse for the 10⁴–10⁵ design point |
| LSM-style per-delta index structures | rejected | rebuilds the index engine; the B+Tree is load-bearing for range queries (FP-1) |
| Node-snapshot journal replay for trees (W-3-style) | rejected | the exact divergence the W-3 soak caught; logical-op replay (W-4b) is the divergence-safe form |

### Acceptance targets (against the canonical baseline, measured at N=5k and 50k)

- New canonical scenarios: `saveAndDeleteIndexed@N` and `updateRename@N` (N=5k, 50k)
  recorded BEFORE W-4a lands (the honest before-number — today only the tiny-N ~731 ops/s
  exists canonically).
- W-4a gate: the O(N_ids) term eliminated — save/delete at N=50k within a **constant
  factor ≤3×** of the N=1k number (vs today's linear degradation), or the honest
  re-scoping with the measured residual.
- W-4b gate: indexed insert/write scenarios lose the O(depth·fields) full-node rewrites;
  measured and reported honestly (the BTree t=64 data predicts a moderate, not 10×, win —
  say so).
- kill-9 + torn-tail + BUG-C-class replay tests per phase; full suite green ×2; the flake
  ledger untouched.

### Implementation estimate (2.2.0's baseline, refined by task 32's gates)

W-4a ~2–3 sessions (machinery reuse is high); W-4b ~3–4 (the live-tree replay is the
novel part); W-4c ~1; W-4d ~3–4 + its own design round. W-4a+c are the 2.1.0-train
candidates per task 32's phasing; W-4b/d follow the gates, not the calendar.

---

## 3. Risks

1. **Replay divergence** — mitigated by design (flat records for W-4a; logical ops for
   W-4b), gated by kill-9 + reopen-divergence suites per phase (the W-3 soak is the
   template, including its soak-caught-exclusion humility: any phase can end with a
   documented exclusion instead of a forced default flip).
2. **BUG-C-class typing on replay** — the known pattern; a dedicated test per phase.
3. **Journal sprawl** — every index resource gains a journal file; JRNLNAME (task 24)
   naming applies; checkpoint bounds (W-4c) keep them bounded.
4. **Flag lifecycle** — default-off → soak → flip is the rule; a flip without a green
   soak is forbidden (the W-3 rule, verbatim).
