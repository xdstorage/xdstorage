# Documentation map — owners, update order, cross-links

What each document owns, when it changes, and the order to update them in. Every
engineer-written doc drifts in isolation — sync them as one story.

## The documentation inventory

| Document | Owns | Update triggers |
|---|---|---|
| `README.md` (repo root) | The landing page: what/when/when-not above the fold, the ≤10-line example, the comparison mini-table, the honest limitation block with numbers, the docs table, the release status | New user-facing feature; a limitation changed; a new doc appears; the landing bar (task 29) re-verified |
| `CHANGELOG.md` | The user-facing history. `[Unreleased]` = the current train, Keep-a-Changelog section order: Added / Changed / Fixed / Breaking, with task-file links | Every user-facing change at merge time (not at release); on-disk format generations recorded in the header |
| `docs/MASTER-PLAN.md` | The roadmap ledger: the numbered items (append-only), the sequencing table, the docs table, the frozen-items inventory with thaw conditions, the Step amendments | Any train decision, pull-forward, scope change, or new-train compilation |
| `docs/GUIDE.md` | The full reference manual: features at a glance, data model, lifecycle, transactions, CRUD, search engine, tuning | Feature behavior changes; new query operators; the FK/cascade contracts |
| `docs/EMBEDDING.md` | The operational contract: lifecycle, thread binding, nested-tx status, codegen requirements, durability, compatibility matrix, JVM-wide static state, the recovery recipe | Contract changes (lifecycle, durability, compatibility), new config flags |
| `docs/BENCHMARKS.md` | The canonical baseline: generated tables (from `scripts/render-bench-baseline.py` + archived result sets), before/after sections, the reading notes, the historical superseded sections | Any benchmark re-run (task 43/13 protocol); NEVER hand-type a baseline number |
| `docs/PERFORMANCE-RECOMMENDATIONS.md` | The configuration-tuning study (edition-specific data + the canonical-banner disclaimer) + §8 config guidance + §10 monitoring thresholds | The study re-runs; config defaults change; the storage/hardware profile changes |
| `docs/DECISION-GUIDE.md` | The evaluation page: comparison table, choose/don't-choose, design point, the numbers table | Positioning changes; the baseline numbers move materially; a competitor fact needs a source |
| `docs/W4-DESIGN.md` / `docs/WRITE-PATH-DESIGN.md` | The write-path design + status | Write-path work lands or is designed |
| `docs/FLAKY.md` | The flake ledger + the excluded-by-decision section + conventions (verdict rule, grouping contract, unique-storage rule) | Any flake family changes state (fixed/residual/excluded) |
| `docs/RELEASE-PLAN.md` | The release procedure: quality bar (RC protocol vs tag pipeline), the step-by-step, standing recommendations | The release procedure changes |
| `docs/ANALYSIS-ARCHITECTURE.md` | The full engine specification (architecture, modules, transaction model, indexes, serialization, FK, migration, codegen, cache) | Major architectural changes; keep synchronized with the code it describes |
| `docs/tasks/<train>/README.md` | The train's index table, lanes/road maps, provenance, conventions, sync contract | Task statuses change (regenerate the index from the files); new tasks; policy decisions |
| `docs/tasks/discovery.md` | The maintainer's discovery/planning input | Annotated per direction after each compilation (inline [brackets] + the acceptance boxes) |
| `docs/proposals/*.md` + `*-ANALYSIS.md` | External proposals and the architect's mapping responses | Each proposal mapped to (planned / new / rejected-with-reasons) |

## The update order (dependencies)

1. The **facts first**: the suite state, the benchmark results, the commit inventory.
2. **BENCHMARKS.md** (numbers) → **DECISION-GUIDE.md** (claims) → **README** limitation
   block → **PERFORMANCE-RECOMMENDATIONS** banner — the quoting chain, in this order.
3. **CHANGELOG** `[Unreleased]` — drafted at merge time, sections reordered only at
   release draft.
4. **MASTER-PLAN** — the items ledger and the sequencing table last (they summarize).
5. **FLAKY.md** — whenever a family changes state; the ledger is a ticket, not a
   tolerance.

## Cross-link discipline

- The DOCSLINT contract test (task 21) checks links + cited scenario names +
  version agreement for DECISION-GUIDE/README/quickstart — extend its sweep before
  relying on new links.
- Task files link to their plan documents relatively
  (`../../MASTER-PLAN.md` from `docs/tasks/2.1.0/…`).
- The master-plan docs table must contain every document an evaluator is expected to
  read; a doc missing from the table is the same rot as a stale number.
