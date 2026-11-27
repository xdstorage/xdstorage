---
title: "[2.2.0][MIGRATE] Migration tooling: schema diff + the v1→v2 / format migration command"
labels: [2.2.0-train, P2, engine, feature]
status: done
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
      field, type change) — pinned tests.
- [x] `migrate` applies a builtin v1→v2 recipe to a copied v1 folder; the result reopens
      clean under 2.2.0 and passes `verify` (task 26's walk).
- [x] Dry-run prints the plan without touching the folder (byte-identical).
- [x] Full suite green ×2; EMBEDDING/GUIDE updated.

Estimate: ~1–2 sessions.

## Implementation details

1. **`XdStorageSchemaDiff`** (`io.github.xdstorage.tools.XdStorageSchemaDiff`):
   - `readFolder(Path)` reads `<folder>/structure/<ClassName>.yaml` files using the
     engine's `XdStorageYamlObjectsReader` (the same reader the engine uses internally).
     Also reads `.xdstorage.meta` for the on-disk format version (v1 = no marker,
     v2 = YAML sentinel, v3 = `indexes/` layout).
   - `buildLiveMap(List<String>)` introspects live Java classes via
     `XStorageObjectUtils.getClassInfo(cl)` — same logic as the engine's
     `XdStorageStructureManager.buildClassStructureSnapshot()`. Reports per-class
     `type` (FQCN), `policy` (StoreAsClassObjects/StoreAsSingleObject), `fields` (name → type),
     `indexes` (name → indexed fields with types, child fields with child-class routing).
   - `computeDiffs(a, b)` compares two structure maps: CLASS ADDED/REMOVED, FIELD
     ADDED/REMOVED/CHANGED, IDX ADDED/REMOVED/CHANGED (with field-level details).
   - `printTextDiffs` / `printJsonDiffs` output one line per change or a JSON array.
   - Exit codes: 0 = no diffs, 1 = diffs found, 2 = usage error, 3 = I/O error.
   - `--json` flag for pipeline consumption (outputs a `[{"class":...,"kind":"FIELD","action":"ADDED",...}]` array).

2. **`XdStorageMigrate`** (`io.github.xdstorage.tools.XdStorageMigrate`):
   - `--dry-run`: prints the migration plan (format version, JSON→XML rename count,
     `indexes/` layout status) without modifying the folder. Byte-identical after.
   - `--script builtin:v1-to-v2`:
     a) Opens folder with `acceptMissingEofSentinel(true)` (legacy YAML reader);
     b) `storage.checkpoint()` + `tx.commit()` — the first commit after open rewrites
        all touched resources with the S-1 EOF sentinel (per `EMBEDDING` S-1 contract);
     c) Renames all `*.json` → `*.xml` in the folder (1.x JSON→XML recipe from EMBEDDING);
     d) Upgrades `.xdstorage.meta` to `formatVersion=3` (task 51 LAYOUTMIG stamp).
   - The `v1-to-v2` recipe is a single-step idempotent migration: safe to re-run on a
     folder already at v2 (the sentinel rewrite is a no-op, the JSON→XML rename skips
     already-renamed files, the format marker upgrade skips if already v3).

## Test matrix

| Test | Scope |
|---|---|
| `testAddedField` | FIELD ADDED detected |
| `testRemovedField` | FIELD REMOVED detected |
| `testTypeChange` | FIELD CHANGED (Long → Integer) detected |
| `testNoDifferences` | Identity comparison → exit 0 |
| `testClassAdded` | CLASS ADDED detected |
| `testClassRemoved` | CLASS REMOVED detected |
| `testMigrateDryRun` | `--dry-run` byte-identical, `.json` not renamed |
| `testMigrateV1toV2` | Full v1→v2 migration + reopen clean under 2.2.0 |

## References

- PRODUCT_REVIEW.md §4 (the demand), task 23 (the recovery recipe this extends)
- `XdStorageStructureManager` / `IXdStorageStructureUpdater` (the existing machinery)
- [EMBEDDING.md](../../EMBEDDING.md) § Compatibility and § Schema migration
