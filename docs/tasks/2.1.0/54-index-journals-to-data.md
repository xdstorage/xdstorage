---
title: "[2.1.0][JRNLDATA] index journals are data carriers — relocate them into data/<Class>/journal/"
labels: [2.1.0-train, P1, engine, layout]
status: done
---

## Summary

The commit journals of the INDEX resources (the hash id-index journal — W-4a, the
search-index journal — W-4b) lived under `indexes/<Class>/journal/` (and the search
variant as the same-dir sibling) — inside the index LAYOUT directory. But they are
**DATA CARRIERS**: the hash id-index journal is the sole durable location of the
un-checkpointed id→fragment addressability between checkpoints (the O(delta) write
path does NOT rewrite the base on commit; the checkpoint folds the journal at the
shutdown/threshold). Losing the tail = the recently saved objects become unreachable
(the fragments exist, the addressability is gone). A durability-critical file does not
belong inside the derived-index directory.

## Step 1 — the premise gate (2026-11-21, verified empirically)

`XdStorageIndexJournalDataCarrierProbe` (kept as the permanent pin): save 2 entities on
top of a checkpointed seed WITHOUT the shutdown → the id-index base does NOT contain the
delta (`assertFalse(baseAfter.contains("delta-1"))`) while the journal
(`indexes/<Class>/journal/index-journal.yaml` at the time) carries both UPSERTs with the
fragment-path payloads. **The premise held** — the relocation proceeded.

## Step 2 — the relocation (implemented)

- The naming service gained `getDataClassJournalDir(Class)` → `data/<Class>/journal/` —
  ALL of a class's commit journals (the fragments, the id-index, the search) live in ONE
  place next to the data they protect.
- `XdStorageYamlCommitJournal.resolveForIndexBase(baseFileName, dataClassJournalDir)` —
  the index-base journal resolution chain, SIX levels, first-existing wins:
  1. `data/<Class>/journal/<stem>-journal.yaml` — **the new primary**;
  2. `indexes/<Class>/journal/<stem>-journal.yaml` — the dev-train spelling (pre-JRNLDATA
     folders), settled lazily at the first prepare;
  3. the sibling next to the index base (`indexes/<Class>/<stem>-journal.yaml`);
  4. the stacked legacy at the index base (`indexes/<Class>/<base>.journal.yaml`);
  5. the data-anchored sibling (`data/<Class>/<stem>-journal.yaml`);
  6. the data-anchored stacked (`data/<Class>/<base>.journal.yaml`) — the journal that
     lived next to the base BEFORE the indexes/ move.
  The settle (`settleAfterReplay` with the explicit target) migrates any found spelling
  into the data location, creating the parent dir (the same NoSuchFile trap the task-51
  walkthrough caught for the data journals).
- `XdStorageHashIndexResource` and `XdStorageSearchIndexResource` resolve and settle
  through the new chain; the append path's `parent.mkdirs()` covers the fresh data dir.
- The DATA and STRUCTURE resource chains are unchanged (they already live under
  `data/<Class>/journal/` and `structure/journal/`).

## Step 3 — the migration algorithm (task 51 interplay)

The FOLDER migration (`migrateLegacyLayout`) still never touches journals — the chains
own them. The real-world migration states:
- a 2.0.0 folder: NO index journals exist (W-4a/W-4b are 2.1.0 features) — nothing to do;
- a 2.1.0-dev folder (pre-JRNLDATA): the index journals under `indexes/<Class>/journal/`
  (chain level 2) or as the siblings — resolved and settled into `data/<Class>/journal/`
  lazily at each resource's first prepare, exactly the task-24 philosophy (pinned by
  `XdStorageJournalNamingTest.indexJournalLivesInDataJournalDir` and the rewritten
  `XdStorageLayoutMigrationTest.testJournalsStayUnderIndexesClassJournal`);
- the format generation stays **v3** (2.1.0 is unreleased — the final layout is what
  ships; no v4 bump).

## Bugs found BY the tests during the relocation

- `resolveForIndexBase` initially lacked the data-anchored spellings (levels 5-6): the
  naming tests' fixtures (the journals renamed to the older namings by the test helpers)
  exposed the unresolved states — the id-index journal was lost from the chain and the
  addressability replay silently missed (the scan saw 1 object instead of 6). The chain
  now covers all six spellings.

## Acceptance

- [x] The premise verified (the probe: the journal is the sole durable location of the delta).
- [x] The relocation: the index journals live in `data/<Class>/journal/`; none under
      `indexes/` (the probe + the walkthrough pin assert it).
- [x] The dev-train spellings resolve and settle (the naming test's dev-spelling fixture:
      the zeroed base restored from the journal + the settle into data/).
- [x] The migration algorithm documented (this file + EMBEDDING "Upgrade from 2.0.0" note).
- [x] The estate 528/0f/0e; the gate ×3 (see the session journal).
