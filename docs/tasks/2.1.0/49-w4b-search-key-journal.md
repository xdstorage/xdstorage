---
title: "[2.1.0][W4B] Search-key logical-op journal: per-search-tree O(delta) journal, replay at open, soak → flip"
labels: [2.1.0-train, P0, engine, feature]
status: open
---

## Summary

W-4b: every search-index (the search-key B+Tree, not the id-index BTree) writes an
append journal (`<basefile>-journal.yaml` in the resource's `journal/` subdirectory)
with logical operations `ADDKEY(key, objectId)` and `DELKEY(key, objectId)` — **not**
a full base-file rewrite per change. The journal is replayed into the live canonical
tree at open. The net effect:

- **O(delta) writes** at commit (the same O(delta) pattern as W-1 for data and W-4a for
  the id-index): only the keys that changed are written to the journal file.
- **O(un-checkpointed ops × depth)** at open (the journal is replayed against the live
  canonical tree, which is already loaded from the base file): each ADDKEY/DELKEY is a
  tree insertion/deletion through the canonical registry (R-20), so it is O(depth)
  per operation.
- **No topology stored:** the journal never stores tree structure — only the operations
  the live tree replays against itself. Splits/rebalances happen naturally during replay.

The search-index is currently the **last O(N) wall** in the write path: every search-key
change rewrites the full base file. W-4b makes the search-index O(delta) the same way
W-1 made data O(delta) and W-4a made the id-index O(delta).

Ground truth (the task 32 W-4a implementation): W-4a's hash-id-index journal exists
in `XdStorageHashIndexResource.performFirstPhaseCommit` (append to `<base>-journal.yaml`).
The search-index path (`XdStorageSearchIndexResource`) still does `writeObjects()` (full
rewrite) on every commit. The gap is the search-index journal.

## Why (the O(N) wall for search-index writes)

The project's write-path train (W-1 → W-4a → W-4b → W-4d) is complete at the id-index
layer (W-4a) but **not** at the search-index layer. Every search-key change (insert, delete,
primary-move) currently:
1. Writes the full base file (all keys) to the journal file.
2. On the next commit: appends the next full base file.

This is O(N × depth) per commit for a search-index with N keys. The id-index W-4a
proved the O(delta) pattern (hash-id-index journal). The data W-1 proved it for data.
The search-index is the **remaining O(N) wall**.

The impact: a search-index with 10k keys rewritten per commit at 100 writes/s = 1 MB/s
of journal writes (each full-base journal entry is ~100 bytes × 10k keys). The W-4a
gains (the ~107× fsync reality, BENCHMARKS § Canonical) are only measured on the
id-index/data path. The search-index wall is unmeasured.

## Scope

1. **The logical-op format** — a YAML record block, same structure as `XdStorageYamlCommitJournal`
   (the W-1 append-only journal, `src/main/java/io/github/xdstorage/serialization/yaml/`):
   ```yaml
   - record:
       serial: 17
       treeId: 'search-tree-Order'
       op: ADDKEY
       key: 'Ship-5'
       objectId: '5'
   - record:
       serial: 18
       treeId: 'search-tree-Order'
       op: DELKEY
       key: 'Ship-3'
       objectId: '3'
   ```
   - Layout: same as W-1 — journals live in the `journal/` **subdirectory** of the
     resource's directory (`XdStorageYamlCommitJournal.JOURNAL_DIR + JOURNAL_SUFFIX`):
     `Order.yaml → <dir>/journal/Order-journal.yaml`. The search-index journal follows
     the same JRNLNAME convention (task 24).
   - The `serial` field is the commit serial (same `assignCommitSerial` from R-45),
     replayed in commit-serial order (idempotent by idempotent semantics, not by serial).
   - Replay: replayed into the **live canonical tree** at open, through the same code
     path live inserts use (splits/rebalances happen naturally, key types are built
     not parsed — the tree's own `insert`/`delete` handles key construction).
   - **Idempotent:** ADDKEY for an existing key is a no-op (the tree's own insert is
     idempotent); DELKEY for a non-existing key is a no-op. Same idempotence model as
     the W-1 UPSERT/DELETE by id.
   - **No topology stored:** the journal never stores tree structure, only the
     operations the live tree replays against itself — identical to the W-1 model.
   - The record class for the search-key journal is a new class (mirrors `XdStorageYamlCommitJournal`'n
     record type): `XdStorageSearchIndexJournalRecord` with `serial`, `treeId`, `op`,
     `key`, `objectId` fields, serialized as YAML blocks via `record: ...` (same indentation
     as the W-1 `record: {serial: ..., op: ..., id: ...}` pattern).

2. **The replay at open** — after loading the base file, replay the journal into the
   canonical tree. The replay is **not** a separate tree builder; it is the canonical
   tree's own insert/delete path. This is the critical constraint (R-20: all tree
   mutations must go through the canonical registry).

3. **The `searchIndexJournalWrites` config flag** — default **OFF** → soak → flip.
   The flag gates the new behavior:
   ```java
   private boolean searchIndexJournalWrites = false;  // default OFF → soak → flip
   ```
   When false: full-file rewrite (unchanged). When true: journal append + replay at open.

4. **The write-out in phase-1** — in `XdStorageSearchIndexResource.performFirstPhaseCommit`:
   ```java
   if (searchIndexJournalWrites && !isInfrastructureCache()) {
       journal.append(records);  // UPSERT/DELETE for each search-key change
   } else {
       writeObjects(cache.readUnsafe(tx));  // full-file rewrite (unchanged)
   }
   ```
   The journal path is the same pattern as W-4a: `<resource>/journal/<base>-journal.yaml`.

5. **The soak → flip gate (W-3 protocol)**:
   - Implement with `searchIndexJournalWrites=false` (default).
   - Run the soak: full suite with `searchIndexJournalWrites=true`.
   - If green → flip default to `true`.
   - If any failure → document the failure, defer to 2.2.0.

6. **The tests** — 6 tests pinning the behavior (see below).

## Out of scope

- **The B+Tree infra journal** (BTree id-index, BTree node files): excluded by
  `isInfrastructureCache()` (the same gate as W-4a for the hash id-index).
- **The checkpoint-on-shutdown (W-4c)**: this task is the W-4b journal; the checkpoint
  is a separate task (task 49).
- **The W-4d eviction** (disk-anchored scans + record-cache eviction): the W-4d is
  deferred because it touches the read path. It is not P0.
- **The backup API (FP-5)**: deferred to 2.3.0.
- **The search-index journal file naming** (JRNLNAME task 24): the journal file naming
  follows the same pattern as W-4a (`<base>-journal.yaml` in the resource's `journal/`
  subdirectory, via `XdStorageYamlCommitJournal.JOURNAL_DIR + JOURNAL_SUFFIX`). No new
  naming convention is needed. The search-index journal is a sibling of the id-index
  journal in the same resource directory.

## Acceptance criteria

- [ ] The logical-op format (ADDKEY/DELKEY with treeId, key, objectId) is implemented
      in the search-index journal writer.
- [ ] The replay at open replays into the **canonical tree** (R-20: no separate tree
      instance). The replay is through the canonical registry's insert/delete path.
- [ ] The replay is idempotent: replaying the same journal twice produces the same result
      (ADDKEY for an existing key is a no-op; DELKEY for a non-existing key is a no-op).
- [ ] `searchIndexJournalWrites=false` (default) → full-file rewrite (unchanged).
- [ ] `searchIndexJournalWrites=true` → journal append (W-4a pattern).
- [ ] The soak is green ×3 (the W-3 protocol: green before flip).
- [ ] After flip, all durability tests still pass.
- [ ] All 6 tests green (listed below).
- [ ] Full suite green (BUILD SUCCESS verdict).

## Tests (the 6-pinned test suite)

| Test name | What it asserts |
|---|---|
| `testReplayAddKey` | A single ADDKEY in the journal → after open + replay, the key is in the tree, the base file does not contain the key, the journal does not exist. |
| `testReplayDelKey` | A single DELKEY in the journal → after open + replay, the key is removed from the tree, the base file does not contain the key, the journal does not exist. |
| `testReplayIdempotent` | The same journal replayed twice → the tree contains exactly the same keys (ADDKEY for an existing key is a no-op, DELKEY for a non-existing key is a no-op). |
| `testReplayAgainstCanonicalTree` | The replay goes through the canonical registry, not a separate tree instance (the canonical tree after replay is the same as the tree that was live during the commit). |
| `testReplaySplitRebalance` | A large replay (10k ADDKEYs) causes the tree to split and rebalance naturally (no manual topology; the tree's own insert path handles it). |
| `testReplayWithExistingBase` | A base file + journal replay → the base file's keys are the base keys + the journal's ADDKEYs minus the journal's DELKEYs (the replay is additive, not replacing). |

## Decision record

**The soak → flip gate (W-3 protocol):** the search-index journal is the **last O(N)
wall** in the write path. The W-3 flip protocol applies: implement with the flag OFF,
soak green, then flip. The soak is the **full suite** (957+ tests), not just the
search-index tests. The soak is the gate.

**The replay cost at open:** O(un-checkpointed ops × depth). If the journal is unbounded
(because checkpoint-on-shutdown is not active), the open cost is O(N × depth). This is
**the reason checkpoint-on-shutdown (task 49) is the prerequisite**: without it, the W-4b
journal can grow unbounded, and the open cost is O(N × depth) again. The combination
(task 49 + task 50) is the complete W-4 train.

**The default: OFF → soak → flip.** The default is OFF until the soak is green. This is
the W-3 protocol. The soak is the full suite (957+ tests). If the soak fails, the
failure is documented and the flip is deferred.

Estimate: ~3 dev-days (the replay implementation is the bulk; the soak is the gate).

## References

- [WRITE-PATH-DESIGN.md](../../WRITE-PATH-DESIGN.md) (the W-4 design: search-key logical-op
  journal, O(delta) writes, replay at open)
- Task 32 (W-4a implementation: the hash-id-index journal pattern this task mirrors)
- Task 49 (W-4c checkpoint-on-shutdown: the prerequisite for W-4b's bounded journal)
- [SRE-OPTIMIZATION-PLAN.md](../../SRE-OPTIMIZATION-PLAN.md) (the W-4 design, the O(N)
  wall analysis for the search-index)
- [BENCHMARKS.md](../../BENCHMARKS.md) § Canonical (the unmeasured search-index wall;
  the W-4b delta will be measured here)
- [EMBEDDING.md](../../EMBEDDING.md) § Files and durability (the journal naming)
- Task 24 (JRNLNAME: the journal file naming convention — `<base>-journal.yaml` in `journal/`)
- R-20 (the canonical-registry constraint: all tree mutations must go through the registry)
