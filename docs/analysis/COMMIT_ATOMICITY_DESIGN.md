# Cross-resource crash atomicity of a commit — the hole, the options, the recommendation

**Status:** design round of task 06 (docs/tasks/2.2.0/06), 2026-11-24. The measurements
below were EXECUTED on the tree of that day (`9e51a4f` + the task-06 change), not read off
the code: every claim marked PROVEN has a test behind it. Nothing of the mechanism
recommended here is implemented; the implementation is task 50
([50-commit-marker-cross-resource-atomicity](../tasks/2.2.0/50-commit-marker-cross-resource-atomicity.md)).
Complements [TX_COMMIT_ROLLBACK_MODEL.md](TX_COMMIT_ROLLBACK_MODEL.md) (the algorithms).

---

## 1. The question

A transaction that touches more than one resource is appended to more than one journal. A
process that dies while those appends are in flight leaves some of them on disk and not the
others. Does the engine, after the restart, show the transaction either wholly or not at all?

Today: **no.** It shows whichever part reached the disk.

## 2. The protocol as it is

`XdStorageTransaction.commitInternal` (the phase loop) and `XdStorageResource.performFirstPhaseCommit`:

```
phase 1, for each resource the transaction changed (DATA before INDEX, ties by resource id):
    append the transaction's records to THAT resource's own journal   ← one file write,
    … closed by its own EOF sentinel                                   its own durability
phase 2: publish into the in-memory caches
```

There is no step that records "this transaction, as a whole, reached its point of no return".
Each journal is complete in itself, and each is replayed on its own, when its resource is
first used after the restart (`XdStorageResource.replayCommitJournal` — lazily, per resource,
with no knowledge of the other journals).

The R-45 commit serial gives the IN-MEMORY visibility its all-or-nothing order. It does not
reach the disk as a decision: a record's `serial` line says which commit it belongs to, never
whether that commit completed.

## 3. The hole — PROVEN

`XdStorageCrashAtomicityCharacterizationTest` (reliability). The state is real: a child
process (`XdStorageCrashCommitter`) opens a storage, commits ONE transaction over two
classes, and is killed with SIGKILL — no shutdown, no checkpoint; the commits exist in their
journals only. One transaction over two classes is **four** journal appends:

```
data/BenchmarkEntity/journal/BenchmarkEntity-c-1-journal.yaml    the customer's data
data/BenchmarkEntity/journal/index-journal.yaml                   the customer's id-index
data/ValueChild/journal/ValueChild-1-journal.yaml                 the order's data
data/ValueChild/journal/index-journal.yaml                        the order's id-index
```

Each of the four is then removed in turn from its own folder — the state a crash ONE append
earlier would have left — and the folder is reopened:

| the append that did not reach the disk | rows readable after the restart |
|---|---|
| the customer's data journal | the order only |
| the customer's id-index journal | the order only |
| the order's data journal | the customer only |
| the order's id-index journal | the customer only |

**Whichever single append is missing, exactly one of the two rows survives.** No committed
transaction produces that state, and no aborted one does. Note the two rows in the middle of
the table: when an id-index append is missing, the other class's BODY is durably in its data
journal and unreachable by any read path — `load(Clazz, id)` answers `null` and
`load(Clazz)` returns nothing, while the record sits in the file.

The same seam exists inside a transaction over ONE class: its data and its id-index are two
of these appends. A kill between them loses the record for every reader, with its body on
disk.

Two facts the probe turned up on the way, for whoever implements the fix:

- **The id-index records carry an ABSOLUTE path** (`resourceId` =
  `/…/data/BenchmarkEntity/BenchmarkEntity-c-1.yaml`). A COPY of a folder therefore has
  journals that lead back into the original — which is why this test kills a real process
  instead of copying a folder, and why any recovery design must not depend on a copy being
  self-contained (the backup work, tasks 15/19, meets the same fact).
- **The process is idle at the kill.** These seams stand for a crash BETWEEN appends; a
  crash INSIDE an append is the torn tail task 06 handles.

## 4. What a fix has to satisfy

1. **All or nothing after a crash**, for every seam: before the first append, between any
   two, after the last, after the commit returned.
2. **A commit that returned is there after the restart** (with `durableWrites`, also after a
   power loss).
3. **One writer per folder stays the contract** — no locks across processes, no daemon.
4. **Nothing breaks for an older library or an older folder**: journals written before the
   fix replay as they did; the folder marker does not move.
5. **The commit path stays inside its budget**: FKBENCH at 10 threads ≥ 100 tx/s and
   p99 < 100 ms (the task-53 numbers to hold), and the durable shape (82 ops/s in
   BENCHMARKS) does not regress beyond a stated band.
6. **Soundness over cleverness**: a failure mode of "refuse and say so", never "half applied".

## 5. The options

### A. A commit journal at the folder root — the single point of no return

One more file, `<folder>/tx-commit.yaml`, append-only, one tiny record per commit:

```
- commit:
    serial: 17
    tx: '7d1c3a52-…'
# xdstorage-eof
```

- **Write path.** Phase 1 appends every resource's batch as today, its trailer now reading
  as *prepared* (`serial=17 tx=…`). When the LAST append has returned, ONE record is appended
  to the commit journal — that append IS the commit point. Only then phase 2 runs and
  `commit()` returns. With `durableWrites` the record is fsynced before phase 2.
- **Recovery.** The commit journal is read once per open into a set of transaction ids. A
  resource's replay applies a batch whose trailer names a transaction only if that
  transaction is in the set; otherwise the batch is skipped and counted — the transaction
  never reached its point of no return. A batch with no trailer (every journal written before
  the fix) and a compensation batch are applied exactly as today.
- **Why it is sound.** There is exactly one durable write between "some of the transaction is
  on disk" and "all of it is committed". Before it: no resource may apply the transaction.
  After it: every resource's batch is already on disk (phase 1 completed), so every resource
  does. The failure modes are "nothing" and "everything".
- **Cost.** One small append per commit — one fsync more when `durableWrites` is on. For a
  transaction over one class the appends go from two to three.
- **New surface.** A file kind the folder did not have: `verify()`, the backup API (task 15),
  the tools CLI (task 16) and the EMBEDDING layout must learn it. An older library ignores
  the file — and, having no marker, replays prepared batches as committed (today's behavior,
  no worse).
- **Compaction.** An entry is needed while some resource journal still holds that
  transaction's batch. The simple, safe rule: rewrite the commit journal without every entry
  whose serial is below the lowest serial found in ANY resource journal of the folder — the
  trailers of task 06 make that a scan of comment lines. Journals without trailers (pre-2.2.0)
  block compaction until a checkpoint sweeps them, which is rare and harmless.

### B. The last resource's journal carries the decision

No new file: the commit point is one more line appended to the journal of the transaction's
LAST resource in the phase-1 order, and every batch trailer names that resource
(`serial=17 tx=… last=<resource id>`). I/O is the same as A (N + 1 small writes).

Rejected for now, not forever: a resource's replay then has to consult ANOTHER resource's
journal to decide whether a prepared batch is committed — and replays are lazy and
independent today, so either every replay reads foreign journals on demand or the engine
grows a cross-resource recovery pass. It also makes correctness depend on the phase-1 order
being stable across versions. A costs one file and avoids all of it.

### C. A commit line in every resource's journal

After phase 1, a one-line "committed" batch is appended to each of the N journals (2N
writes). Rejected: it does not close the hole. A crash after the commit line reached resource
A and before it reached resource C leaves A applying the transaction and C skipping it — the
same problem one level up. Only a SINGLE durable write gives a single point of no return.

### D. One write-ahead log for the whole folder

The textbook shape: every change of every resource goes to one log, one commit record ends a
transaction. Rejected for this train: it replaces the per-resource journals and checkpoints
the W-4 program is built on; it is a storage-engine redesign, not a fix.

### E. Document it and leave it

Honest, cheap, and what this document does in the meantime (EMBEDDING § Files and durability
now says it). Rejected as the end state: the engine advertises ACID transactions, and the
test in §3 shows an `I` that does not hold under a crash.

## 6. The recommendation

**A, behind a config flag, default off, with the house soak-then-flip protocol** (flag on →
the full suite and the nightly ×3 green → the flip as its own disclosed change).

The order of work, each step with its own gate:

1. **The fail-point seam.** The crash tests need a kill BETWEEN two appends of one commit;
   today's harness can only kill before or after. A test-scope hook called between the
   phase-1 appends (an engine-internal observer, not public API) lets the child process
   halt, throw, or `Runtime.halt()` at an exact seam.
2. **The write path + the flag.** Off: byte-for-byte today's behavior. On: the trailer reads
   `prepared`, the commit record is appended after the last resource append.
3. **Recovery under the flag.** The committed-transaction set, the skip-and-count for
   prepared batches of transactions not in it.
4. **Turn the characterization around.** `XdStorageCrashAtomicityCharacterizationTest` becomes
   the requirement: for EVERY seam — before the first append, between each pair, after the
   last, after the commit returned — either both rows or none are readable. Plus the
   one-class case (data and id-index), an update over three classes, a delete, and the
   rollback-only-between-phases path.
5. **The neighbors.** `verify()` reports the commit journal; the EMBEDDING layout, durability
   matrix and compatibility table gain the file; the backup copy includes it.
6. **The measurements, before any claim.** FKBENCH (t=1/5/10) and the durable shape, the
   flag on against off, the same protocol — the numbers decide whether the flip is free.

## 7. What the maintainer decides

1. **A or B.** A is recommended; B saves a file at the price of a coupled recovery.
2. **When the flag flips to on.** In 2.2.0 after the soak, or in 2.3.0.
3. **Whether compensation appends stay.** Under A they are no longer needed for crash safety
   (an uncommitted transaction's batches are skipped anyway); they still undo a failed
   commit's durable appends in the same process. Keeping them in the first version is the
   smaller change.
4. **The priority.** Filed as P0: an atomicity hole under crash, in a storage that promises
   ACID, outranks every performance item in the train.

## 8. What task 06 put in place for this

- Every batch of every journal now names its commit serial and its transaction in a trailer
  the tokenizer hands to the reader with the sentinel — the identity recovery under A needs.
- A journal damaged in its middle is refused instead of emptied (§ "the hole" of a different
  kind: one damaged record used to cost every later commit in the file).
- The crash harness: `XdStorageCrashCommitter`, `XdStorageCrashedStorages`.
