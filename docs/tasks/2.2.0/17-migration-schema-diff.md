---
title: "[2.2.0][MIGRATE] Migration tooling: schema diff + the v1→v2 / format migration command"
labels: [2.2.0-train, P2, engine, feature]
status: open
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

- [ ] `schema-diff` correctly reports a synthetic diff folder set (added field, removed
      field, type change) — pinned tests.
- [ ] `migrate` applies a builtin v1→v2 recipe to a copied v1 folder; the result reopens
      clean under 2.2.0 and passes `verify` (task 26's walk).
- [ ] Dry-run prints the plan without touching the folder (byte-identical).
- [ ] Full suite green ×2; EMBEDDING/GUIDE updated.

Estimate: ~1–2 sessions.

## References

- PRODUCT_REVIEW.md §4 (the demand), task 23 (the recovery recipe this extends)
- `XdStorageStructureManager` / `IXdStorageStructureUpdater` (the existing machinery)
- [EMBEDDING.md](../../EMBEDDING.md) § Compatibility and § Schema migration
