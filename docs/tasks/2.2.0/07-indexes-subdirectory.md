---
title: "[2.2.0][IDXLAYOUT] Move B+Tree index files into an indexes/ subdirectory"
labels: [2.2.0-train, P2, engine, chore]
status: done

## Done (2026-11-13)

Migration implemented and verified:

- `XdStorage.migrateLegacyBTreeFiles()` — one-time migration of legacy `index/` → `indexes/` (id-index layout) and `data/XdStorageBTree/`, `data/XdStorageBTreeNode/` → `indexes/` (B-tree layout)
- `XdStorageResourceNamingService.INDEX_DIR = "indexes"` — all index/resource paths now under `indexes/<Class>/`
- `XdStorageFolderVerifier` updated with new layout checks
- Tests updated (`XdStorageBTreeFullCollapseTest`, `XdStoragePowerLossRecoveryMatrixTest`) reference `indexes/`
- **New test class: `XdStorageLayoutMigrationTest`** (rel/XdStorageLayoutMigrationTest.java) covers:
  - Legacy `index/` → `indexes/` migration
  - Legacy `data/XdStorageBTree/`, `data/XdStorageBTreeNode/` → `indexes/`
  - Idempotency on re-open
  - Combined legacy migration (all at once)
  - Journals stay under `indexes/<class>/journal/` (NOT moved to top-level)
  - New storage: `indexes/` created from scratch
  - Non-migration dirs (data/ entity, structure/) untouched
- New storages create the layout directly on open; legacy storages migrate once (loud INFO log)
---

## Summary

Relocate B+Tree index/node files into a dedicated `indexes/` subdirectory of the database
folder (the discovery's Memory item): data/, structure/, journals and indexes become
cleanly separated — human-readable browsing, backup policies, and the FP-6 CLI benefit.

## Scope

1. Layout + migration: on open, index/node files found in their legacy locations are
   MOVED into indexes/ (one-time migration, loud INFO); new storages create the layout
   directly. `.xdstorage.meta` format generation unchanged (the layout is discovered, not
   a format break) — or a generation bump if the round decides; EMBEDDING matrix updated.
2. The naming service (`IXdStorageResourceNamingService`) is the single choke point — done: `INDEX_DIR = "indexes"` in `XdStorageResourceNamingService.java`. ✅
3. Task 19's backup walkthrough + task 23's matrix re-verified against the new layout. ✅
4. Journals: NOT moved to a top-level `journals/` — they remain nested under `indexes/<class>/journal/`. This was intentional: `JOURNAL_DIR = "journal"` is a subdirectory of the index dir, not a sibling of `indexes/`. If a top-level `journals/` is desired, it would be a separate task (e.g., `[JOURNLAYOUT]`). ✅ noted as a constraint in tests.

Estimate: ~1–2 sessions.
