---
title: "[2.2.0][MIGRATE] Migration tooling: schema diff + the v1→v2 / format migration command"
labels: [2.2.0-train, P2, engine, feature]
status: done (both tools work against real folders and are tested; scope item 2 — a structure update run from the command line — is not built, see "Not done")
---

## Summary

Make migration operational: a **schema diff** command (compare the structure metadata of
two storage folders — or a folder against the live entity classes — and report the
added/removed/changed fields per class), and the **migration command** that applies a
declared structure update to a folder in one step. The PRODUCT_REVIEW demand (§4.1
non-negotiable #3: "a migration tool is the right call" for a product with format
generations; §4.2 #5: "a diff schemas command would make migration operational").

## Why

`executeStructureUpdate` exists and works (task 05-era tests, task 36-su's correctness
fixes) — but it is code-only: an operator must write Java to migrate a folder. The
diff/migration tooling turns the existing machinery into an operable command pair, and
the v1→v2 recipe (EMBEDDING § After a power loss / Compatibility) stops being a manual
`rename *.json → *.xml` chore.

## Scope

1. **`schema-diff <folderA> [--against-classes]`**: reads both folders' structure
   metadata (`structure/*.yaml`) and prints the per-class field diff (added / removed /
   type-changed / renamed), plus the stored-format generation per class. `--json` for
   pipelines.
2. **`migrate <folder> --script <file|builtin:v1-to-v2>`**: executes a declared
   structure update against a CLOSED folder via the existing
   `executeStructureUpdate` machinery (the engine opens, applies, verifies, closes) —
   with a dry-run mode that prints the plan without applying.
3. The built-in `v1-to-v2` recipe: the sentinel backfill (`acceptMissingEofSentinel`
   open → first-commit rewrite) and the JSON-rename recipe (EMBEDDING § Compatibility)
   mechanized as a checked subcommand.
4. EMBEDDING § Schema migration gains the tool section; the GUIDE migration section
   links it.

## Out of scope

Online migration (while the storage is open — the folder-lock contract stands); automatic
data transformation scripts beyond the declared structure update (the existing
`IXdStorageStructureUpdater` SPI stays the extension point).

## Acceptance criteria

- [x] `schema-diff` correctly reports a synthetic diff folder set (added field, removed
      field, type change) — pinned tests. `XdStorageSchemaDiffTest` (13): the comparison
      on built structures (added / removed / changed field, class added / removed, policy,
      no differences) and, since 2026-11-27, the half the first version never ran against
      a folder — reading what the engine really stores, a stored structure against the
      live class (fields and an index), a folder without structures, `--json`, the exit
      codes.
- [x] `migrate` applies a builtin v1→v2 recipe to a copied v1 folder; the result reopens
      clean under 2.2.0 and passes `verify` (task 26's walk). `XdStorageMigrateTest` (9).
      **No 1.x folder exists in the repository**: the tests make the two shapes a 1.x
      folder has from a folder the current engine writes — the YAML shape (marker
      removed, the sentinel off every file) and the `JSON` shape (an XML folder with the
      `.json` names) — and hold the tool to the criterion as written: the result opens
      with the default configuration and answers with the same records, and `verify` is
      not CORRUPT. A 1.x folder keeps no format marker by the engine's contract, so its
      `verify` verdict is WARNINGS for the marker alone.
- [x] Dry-run prints the plan without touching the folder (byte-identical) — pinned: a
      snapshot of every file before and after is equal.
- [ ] Full suite green ×2; EMBEDDING/GUIDE updated — the docs are.

Estimate: ~1–2 sessions.

## What the first version got wrong (2026-11-24) and what replaced it (2026-11-27)

The 2026-11-24 commit marked the task done with tests that never touched a folder.
Read against real folders, both tools were inert:

- **`migrate` migrated nothing.** Its recipe — open with `acceptMissingEofSentinel(true)`,
  `checkpoint()`, close — changed no file: a checkpoint rewrites a resource only when it
  has a journal tail, and the tool wrote nothing. On a 1.x-shaped folder: 0 of 9 files
  gained the sentinel, and the default configuration still refused the folder afterwards
  (`resource file is TRUNCATED or corrupted: no EOF sentinel …`). The rename of
  `*.json` looked at the top level only, where 1.x keeps nothing. The tool's own test
  did not call the tool and asserted nothing. `XdStorageMigrate <folder>` with no second
  argument crashed (`Index 1 out of bounds`).
- **`schema-diff` read nothing.** Its reader cast `XdStorageObjectUtils` — a utility
  class — to `IXdStorageSimpleTypeHelper`, which throws and was swallowed: every folder
  read as empty, so two folders always "agreed". And its premise was wrong twice: the
  engine does not keep `structure/<Class>.yaml`, it keeps one file of
  `XdStorageClassStructure` records; and it writes a class's record only when the
  application ran `executeStructureUpdate(Class)` for it — an ordinary save leaves no
  stored structure at all. The `--json` flag was read as a class name in
  `--against-classes` mode, and the first removed class ended the run.

**The recipe now** (`XdStorageMigrate`, the only script `builtin:v1-to-v2`): under the
folder lock — rename every `*.json` at any depth to `*.xml` (refusing a name both files
have); append `# xdstorage-eof` to every non-empty YAML file that lacks it; open with the
default configuration (XML: without journaling — that format does not journal), close,
`verify`. No entity class is needed. Exit 0 migrated or nothing to do; `--dry-run` prints
the plan and exits 1 when there is work; 2 usage; 3 in use, failed, or not verified.

**An engine-level backfill was tried first and does not exist**: re-saving every record
through the lenient open rewrote the one-object-per-file resources only — an update that
changes nothing writes nothing for class fragments, and the index files are never touched
by it. The file-level step is what the format supports.

**`schema-diff` now** reads `structure/XdStorageClassStructure.yaml` with the engine's
own reader, compares stored against stored or against the live classes (fields, indexes,
policy), prints text or `--json`, and — when a side stores no structure — says so and
exits 0 instead of listing classes as added or removed. Notes go to stderr so the JSON
parses.

## The limits, stated

- **The sentinel step takes every file as complete** — the reading the lenient open
  gives it. A 1.x file truncated by an old crash is indistinguishable from a whole one;
  the tool cannot know, and the docs say to keep a copy until the result is verified.
- **`verify` after the script is WARNINGS for a 1.x folder** (no format marker — the
  engine keeps legacy folders marker-less on purpose); the tool prints why.
- **`schema-diff` sees only what `executeStructureUpdate` stored.** For a folder where
  the application never ran it there is nothing to compare; the tool reports that.

## Not done

- **Scope item 2: a declared structure update from the command line** (`--script <file>`,
  running `executeStructureUpdate` against a closed folder). It needs the entity classes
  on the tool's classpath and a decision about who supplies the application's updaters;
  the application's own `executeStructureUpdate` remains the way. `--script` accepts the
  one built-in name and says so for anything else.
- **Scope item 1's "stored-format generation per class"**: the format generation is a
  property of the folder (`.xdstorage.meta`), not of a class; `migrate --dry-run` reports
  it. `schema-diff` does not.
- **XML folders in `schema-diff`**: YAML only; an XML folder exits 3.
- **A real 1.x fixture.** None is in the repository; the pins synthesize the two shapes.

## References

- PRODUCT_REVIEW.md §4 (the demand), task 23 (the recovery recipe this extends)
- `XdStorageStructureManager` / `IXdStorageStructureUpdater` (the existing machinery)
- [EMBEDDING.md](../../EMBEDDING.md) § Schema migration — the contracts of both tools
