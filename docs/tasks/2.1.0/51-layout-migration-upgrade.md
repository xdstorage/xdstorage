---
title: "[2.1.0][LAYOUTMIG] 2.0.0→2.1.0 layout migration: crash-tolerant, cross-OS, fully tested"
labels: [2.1.0-train, P0, engine, bug, testing]
status: done
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

## The migration algorithm (IMPLEMENTED 2026-11-21 — the design sketch's two detection
## errors corrected: the released 2.0.0 folders HAVE the marker (formatVersion=2) and
## have NO top-level index/ (that was only the unreleased intermediate commit af03e2d);
## their id-index lives under data/)

```
on open(folder) — after the folder lock, before any resource (XdStorage ctor):
  1. structural detection (marker-independent): any data/<Class>/index.yaml,
     references.yaml, search base, or data/XdStorageBTree{,Node}/ present →
     migration steps needed; index/ top-level present → the interim rename
  2. the idempotent steps (each: source-exists && dest-absent → move):
     a. index/ → indexes/ (the interim dev spelling)
     b. data/<Class>/{index.yaml,references.yaml,<searchIndex>.yaml} →
        indexes/<Class>/ — the stem-based classification: a stem equal to
        <Class> or starting with <Class>- is a DATA FRAGMENT and stays (the
        id-index payload points at it); *.journal.yaml files NEVER move (the
        task-24 chain settles them lazily at the first prepare)
     c. data/XdStorageBTree{,Node}/ → indexes/
  3. crash tolerance: a half-migrated folder = some steps done, the marker still
     v2 → the next open redetects the remainder and completes it
  4. the failure policy: any unmovable file = a LOUD open refusal (LAYOUTMIG in
     the message) — never warn-and-continue (a silently empty id index + the
     first save clobbers fragment-0)
  5. on full success: the format marker rewritten 2 → 3 (the layout generation
     stamp; the 2.0.0 downgrade refusal becomes REAL via the newer-format gate)
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

---

## DONE (2026-11-21): the acceptance record

- [x] The migration audit: every 2.0.0 file class accounted for — the index bases move
      (the stem classification), the data fragments + the stacked journals + structure/
      + lock/marker stay (each with the reason), the interim index/ renames, the BTree
      infra moves. The audit found the REAL 2.0.0 signature differs from this sketch
      (see the corrected algorithm above).
- [x] The crash-tolerance tests: XdStorageLayout200UpgradeTest crashMatrix_*
      (the infra-moved-only state, the one-class-moved state) — each completes at the
      next open, the data intact. Built on the GOLDEN FIXTURE generated by the REAL
      released 2.0.0 artifact (tools/layout200-fixture, pom pinned to Central 2.0.0;
      src/main/test/resources/layout200-golden; provenance libraryVersion=2.0.0 asserted
      in the walkthrough).
- [x] The upgrade walkthrough: the golden fixture → open 2.1.0 → the migration → loads
      (Cyrillic through the journal replay), the search over the migrated trees, verify()
      no-CORRUPT + no foreign files → a save (the fragment-0 clobber guard) → reopen →
      the journals settled into journal/.
- [x] Cross-OS: renameTo → Files.move with the ATOMIC_MOVE fallback (the loud refusal on
      failure); the charset pinned to UTF-8 across ALL the persistence I/O (the base-file
      writer XdStorageAtomicFile.OutputStreamWriter was the platform default — the
      Cyrillic corruption vector; the journal/search writers+readers, the base readers,
      the verifier). The path-join audit resolved to KEEP the literal "/" concat (the
      payload compatibility with 2.0.0 outweighs the cosmetics — a File(parent,child)
      switch would change the id strings on Windows and orphan the payload paths).
- [x] EMBEDDING "Upgrade from 2.0.0" written (the procedure, the crash tolerance, the
      failure policy, the known limitations); CHANGELOG Breaking corrected (the v3 stamp
      makes the downgrade refusal REAL — the previous claim was aspirational, both
      generations wrote formatVersion=2).
- [x] The existing XdStorageLayoutMigrationTest extended (9 tests: + the loud failure,
      + the marker v3); XdStorageLayout200UpgradeTest 5 tests (the walkthrough + the
      crash matrix ×2 + the idempotency + the downgrade refusal); the verifier's
      formatVersion assert adapted to v3.
- [x] The engine bugs found and fixed BY the walkthrough: the journal settle created no
      journal/ parent dir (a 2.0.0 folder's first settle failed NoSuchFile — the naming
      tests never hit it because their fixtures pre-created the dir); the migrator's
      base classification initially moved the stacked references journal (the journal
      suffix skip added).

**The gate ×3:** see the session journal (the LAYOUTMIG entry).
