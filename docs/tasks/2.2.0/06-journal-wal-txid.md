---
title: "[2.2.0][WAL] Journal/WAL hardening: transaction identity at EOF, WAL semantics for transactions"
labels: [2.2.0-train, P1, engine, durability]
status: done (the batch trailer + the exact torn-tail loss + the loud mid-file corruption; the cross-resource commit marker is task 50 — proven on real crash folders, designed, not implemented)
---

## Summary

Harden the commit journal toward true WAL semantics: the EOF sentinel line carries the
**last commit serial + transaction id** (so a torn tail's decision — discard vs keep — is
made on durable metadata, not just presence), and the transaction's journal section is
identified per transaction (the discovery's 'WAL for transactions and transaction id at
EOF in journals'). Strengthens torn-tail decisions, cross-storage audit, and the recovery
recipe's precision.

## Scope

1. Sentinel format v3: `# xdstorage-eof serial=<n> tx=<id>` (backward-compatible parsing —
   the plain sentinel still accepted; the enriched form written by default).
2. Torn-tail policy upgrade: with the enriched sentinel, the discard decision can report
   the EXACT lost serial range (better recovery reporting for task 23's recipe).
3. Format-generation note (EMBEDDING compatibility matrix) — additive, no migration.

Estimate: ~1 session.

## Scope amendment (2026-11-23, the external-evaluation intake §3.3.11 — verified against main)

The replay's catch sits around the WHOLE read loop
(`XdStorageYamlCommitJournal.readForReplay`, lines ~572–599): a parse error MID-FILE —
not a physical torn tail — silently truncates the replay at the last good sentinel.
Valid records after the corrupt one are dropped and never counted
(`discardedTailRecords` counts only the pending prefix; everything unread past the
error is invisible). A physical truncation and a mid-file corruption are different
events with different correct responses. This task owns the distinction:

- a PHYSICAL truncation (the crash mid-append) = the torn-tail discard — unchanged;
- a MID-FILE parse corruption = LOUD per task 02 (or the quarantine+count decision,
  recorded with its reason) — never the silent torn-tail equivalence;
- the counter must cover the UNREAD REMAINDER, not just the pending list;
- the sentinel v3 (scope item 1) is the mechanism: with serial+tx at EOF the two cases
  are distinguishable on durable metadata.

## LANDED (2026-11-24) — what shipped, the two decisions, and the part that is NOT this task any more

**Delivered against the scope:**

- [x] Scope 1 — the transaction identity at every sentinel (see decision 1 for the spelling).
- [x] Scope 2 — the torn tail reports the exact commit serials lost.
- [x] Scope 3 — the format note in EMBEDDING § On-disk format compatibility.
- [x] The amendment — a mid-file corruption is refused; the unread remainder is named.
- [x] The cross-resource half of the intake's §3.3.1: PROVEN, designed, re-homed as task 50 —
      NOT implemented here (see below).

### The red-before record (the original reader, `9e51a4f`)

A real crash folder — a child process commits three transactions and is killed with SIGKILL
(`XdStorageCrashCommitter`) — with the first batch of the id-index journal damaged and two
complete batches after it. The old reader logged ONE warning and served none of the three
records:

```
WARN … index-journal.yaml has a TORN TAIL (crash mid-append): discarding 0 record(s)
     after the last EOF sentinel — the interrupted commit was never reported
load(e-1) = null   load(e-2) = null   load(e-3) = null
```

The counter said **0** while three committed transactions became unreadable — the unread
remainder was invisible (the test:
`XdStorageJournalCrashRecoveryTest.aCorruptionWithLaterBatches_RefusesTheReopen` — red
before, green after).

### Decision 1 — where the serial and the transaction live: a trailer line, the sentinel untouched

The scope named the spelling `# xdstorage-eof serial=<n> tx=<id>`. Writing THAT line would
be a downgrade trap: every reader before 2.2.0 skips any `#` line that is not exactly the
plain sentinel, so a 2.1.x library opening a 2.2.0 journal would see NO sentinel for any
2.2.0 batch — "torn tail", every commit since the upgrade discarded behind one warning.
Rolling an upgrade back is exactly when that happens.

So the writer emits the same information one line earlier, as a comment, and the sentinel
stays byte-identical:

```
# xdstorage-meta serial=17 tx=7d1c3a52-…
# xdstorage-eof
```

An older reader skips the first line and recognizes the second (pinned:
`everyCommentLineIsSkippableByAnOldReader`). The reader ALSO accepts the scope's literal
spelling (read both, write the safe one). Rejected: keeping the plain sentinel and adding a
`tx:` line to every record — one append is one transaction's batch per resource, so the
identity belongs to the batch, not to each of its records.

### Decision 2 — torn tail or corruption: the test is positional, and it had to be

The amendment expected the trailer's serial to tell the two cases apart. It cannot be the
test: the trailer of a batch that failed BEFORE its trailer is exactly the trailer that was
never reached. The durable fact that separates them is whether the damaged batch is closed
by a sentinel:

- **no sentinel after the failure** → the append never finished: a torn tail. Discarded, as
  before — now with the exact serials lost, the compensation records among them, and a count
  of records cut before their own serial line (the old reader counted only records it had
  managed to parse whole).
- **a sentinel after the failure** → the batch reached the disk whole and its content is
  damaged: refused. `… is CORRUPT in batch N [serial=… tx=…] (after K complete batch(es)) …
  the damaged batch is closed by its own sentinel and M later batch(es) with R further
  record(s) follow. Refusing to replay the journal — …`

The trailer's serial does its other job: every CLOSED batch is checked against it, and a
batch whose records disagree with its own trailer is refused (they are written by one
append; only alteration can part them). Rejected: quarantine the damaged record and resync
at the next sentinel — it keeps the later commits but serves a state with a committed
change missing, behind a warning: the quiet self-heal task 02 exists to remove.

A consequence worth knowing: a broken value in the LAST batch is refused too when that
batch's sentinel is present — a crash cannot write a sentinel after a broken value; only
damage can.

### The cross-resource hole — PROVEN, and re-homed as task 50

The intake mapped the evaluation's "the commit is not atomic between classes" to this task.
It is a different mechanism from the journal's format, and executing it showed how much
bigger it is than the evaluation's wording. `XdStorageCrashAtomicityCharacterizationTest`:
one transaction over two classes is FOUR journal appends (data + id-index per class); with
any ONE of them missing after a real kill, exactly one of the two rows is readable. A
transaction over one class is not exempt. The design round — the options, the rejected
ones, the recommendation (one durable point of no return, behind a flag) — is
[COMMIT_ATOMICITY_DESIGN.md](../../analysis/COMMIT_ATOMICITY_DESIGN.md); the implementation
is [task 50](50-commit-marker-cross-resource-atomicity.md). The batch trailer delivered
here is the identity that recovery will need.

### Found on the way — recorded, not fixed here

- **The id-index records carry an ABSOLUTE path** to the data file. A folder COPY therefore
  has journals that lead back into the ORIGINAL folder (a probe read the original's record
  through a copy). This is why the crash tests kill a real process instead of copying a
  folder, and it is a constraint on the backup work (tasks 15/19).
- **`verify()` is at home with the trailer**: on the three-batch crash folder every journal
  files as `OK` and the folder's verdict is `WARNINGS` — "ORPHANED JOURNAL … un-checkpointed
  commits or crash litter; the open replays it", the right answer for a folder a crash left.
  (A first reading of the verifier's code suggested it would file any multi-batch journal as
  `TORN_TAIL`; executed, it does not — recorded so nobody re-derives it.)

### The pins

`XdStorageCommitJournalWalTest` (16: the written form, the old-reader property, the trailer
on the replay, older journals, the literal enriched spelling, the trailer/batch mismatch,
three torn-tail cuts, the unparseable last batch, three mid-file refusals, compensation
order), `XdStorageJournalCrashRecoveryTest` (6, on real crash folders: the control, two torn-tail
cuts, the mid-file refusal, two batches on one record, `verify()` with trailers), and the
characterization (2).

**On Windows (2026-11-28, the wave's first Windows run):** `XdStorageCrashedStorages` found
its journals by a spelled separator — `toString().contains("/" + classDir + "/")` — and five
of the six crash tests went red at the lookup. The lookup is by a whole path name now
(`StoragePaths.hasElement`). The child process and its kill ran there without a complaint
(the `@BeforeAll` passed); what follows the lookup had not run there when this was written.

**The gates:** the journal estate (the three classes above + `XdStorageW1JournalTest`,
`XdStorageCheckpointOnShutdownTest`, `XdStoragePowerLossRecoveryMatrixTest`, `XdStorageVerifyTest`,
`PropJournalEquivalenceTest`, `serialization.yaml.*Test`) **420/0f/0e ×3**; the quick-gate **1755/0f/0e/7
skipped ×3, consecutive, BUILD SUCCESS**, on a quiet box (1731 + the 24 pins of this task).
