---
title: "[2.2.0][IDXLAYOUT] Move B+Tree index files into an indexes/ subdirectory"
labels: [2.2.0-train, P2, engine, chore]
status: open
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
2. The naming service (`IXdStorageResourceNamingService`) is the single choke point.
3. Task 19's backup walkthrough + task 23's matrix re-verified against the new layout.

Estimate: ~1–2 sessions.
