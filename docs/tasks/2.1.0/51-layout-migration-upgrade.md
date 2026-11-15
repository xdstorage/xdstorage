---
title: "[2.1.0][LAYOUTMIG] 2.0.0→2.1.0 layout migration: crash-tolerant, cross-OS, fully tested"
labels: [2.1.0-train, P0, engine, bug, testing]
status: open
status: open
---

## Summary

The 2.1.0 layout introduced `indexes/` as a dedicated subdirectory for id-index, B+Tree,
and search-index files (previously scattered across `index/` and `data/`). The migration
code exists (`migrateLegacyBTreeFiles` in `XdStorage.java`, the
`XdStorageLayoutMigrationTest` 348-line contract test) but needs **hardening** before
the release: crash-during-migration tolerance, cross-OS path handling, and the full
2.0.0→2.1.0 upgrade walkthrough.

## Why (the P0 blocker)

A 2.0.0 user who upgrades to 2.1.0 opens their existing folder — the migration runs
automatically at open (`migrateLegacyBTreeFiles` at `XdStorage.java:90`) — and the
storage must work. The current migration covers the basic file relocations but has
gaps:

1. **No crash tolerance**: if the process crashes mid-migration (some files moved, some
   not), the next open must complete the migration — the half-migrated folder is a
   valid state that the open path must recognize and finish.
2. **No cross-OS verification**: the migration uses `File.renameTo()` — which is
   not atomic and may fail on Windows (target exists, cross-device locks). The
   fallback path is not tested.
3. **No charset pinning**: file names are ASCII (class names + UUID ids) — safe — but
   the charset used to read/write the files must be pinned to UTF-8 (not the platform
   default).
4. **The EMBEDDING upgrade procedure is missing**: a 2.0.0 user needs to know: close
   the old version, open with 2.1.0, the migration runs automatically, the old layout
   is not preserved.

## Scope

1. **The migration audit**: walk the full 2.0.0 folder layout — every file class (data
   fragments, index/, structure/, `.xdstorage.lock`, `.xdstorage.meta`, journal files,
   `.tmp` litter) — and verify the migration handles each: moved, kept in place, or
   explicitly documented as not applicable.
2. **Crash tolerance**: the migration is idempotent — after a crash mid-migration, the
   next open completes the remaining steps. The test simulates a crash between each
   migration step (copy the folder at each intermediate state → open → assert).
3. **Cross-OS hardening**: replace `File.renameTo()` with `Files.move(...)` (with
   `ATOMIC_MOVE` fallback to non-atomic), pin the charset to UTF-8 in the reader/writer
   (the default-charset writes are the Windows divergence), and add a path-separator
   audit (all file joins use `Path.resolve()` or `File(parent, child)`, not string
   concatenation).
4. **The 2.0.0→2.1.0 upgrade walkthrough test**: create a folder with the 2.0.0 layout
   (a fixture: the pre-relocation file structure with real content), open with 2.1.0,
   verify the migration (the files in the new locations, the content intact, the
   indexes functional, the search answers), close, reopen, verify again.
5. **EMBEDDING § Upgrade from 2.0.0**: the upgrade procedure (close 2.0.0 → open 2.1.0
   → the migration runs automatically → the old layout is not preserved), the rollback
   procedure (the 2.1.0 layout cannot be opened by 2.0.0 — the format marker prevents
   it), and the known limitations.
6. **CHANGELOG**: the Breaking entry ("the on-disk layout changed: indexes/ is a
   dedicated subdirectory; the migration runs automatically at open").

## The migration algorithm (design)

```
on open(folder):
  1. detect the layout version:
     - `.xdstorage.meta` present → read the format generation
     - no `.xdstorage.meta` + `indexes/` absent + `index/` present → legacy 2.0.0
     - `indexes/` present → already migrated (skip the migration)
  2. migrate the legacy layout (one-time, idempotent):
     a. rename index/ → indexes/ (the id-index directory)
     b. move data/XdStorageBTree/ → indexes/XdStorageBTree/
     c. move data/XdStorageBTreeNode/ → indexes/XdStorageBTreeNode/
     d. verify: all files accounted for (no orphaned dirs)
  3. the migration is idempotent: after a crash mid-migration, the next open
     detects the half-migrated state (the legacy dirs still present with partial
     content) and completes the remaining steps
  4. the format marker `.xdstorage.meta` is written AFTER the migration (the
     marker's absence signals "migration needed")
```

## Out of scope

Cross-version downgrade (2.1.0 → 2.0.0 — the layout is not backward-compatible);
multi-folder migration; the FP-6 CLI `migrate` command (a separate task).

## Acceptance criteria

- [ ] The migration audit: every 2.0.0 file class accounted for (moved / kept / N/A).
- [ ] The crash-tolerance test: a crash between each migration step → the next open
      completes the migration (pinned by tests on pre-built fixture folders).
- [ ] The 2.0.0→2.1.0 upgrade walkthrough test: create a 2.0.0-layout fixture with
      real content → open with 2.1.0 → the migration runs → the content intact, the
      indexes functional, the search answers, the verify verdict OK.
- [ ] Cross-OS: `Files.move` with the `ATOMIC_MOVE` fallback tested; the charset
      pinned to UTF-8 in the reader/writer (no default-charset writes).
- [ ] EMBEDDING § Upgrade from 2.0.0 written; CHANGELOG Breaking entry.
- [ ] Full suite green ×2 (BUILD SUCCESS verdict); the existing
      `XdStorageLayoutMigrationTest` extended (not replaced).

Estimate: ~2–3 dev-days (the migration audit + the crash-tolerance tests + the
cross-OS hardening + the docs).

## References

- `XdStorage.java:90` (`migrateLegacyBTreeFiles` — the existing migration entry point)
- `XdStorageLayoutMigrationTest` (348 lines, 7 tests — the existing coverage)
- Task 23 (the power-loss matrix — the crash-simulation methodology)
- Task 26 (verify — the integrity walk that validates the migrated folder)
- EMBEDDING § Compatibility (the format generations, the upgrade procedure to extend)
- discovery.md § First priority item 8 (OS-dependent paths — the cross-OS audit)
