---
title: "[2.1.0][DOCSWRITER] Technical-writer pass: landing page, guide, and feature docs as one coherent story"
labels: [2.1.0-train, P1, docs]
status: open
---

## Summary

A technical-writer pass over the entire user-facing documentation surface, treating it as
one story: the README landing, [docs/GUIDE.md](../../docs/GUIDE.md) (the full reference),
[docs/DECISION-GUIDE.md](../../docs/DECISION-GUIDE.md) (the evaluation page),
[docs/EMBEDDING.md](../../docs/EMBEDDING.md) (the operational contract), and
[CHANGELOG.md](../../../CHANGELOG.md). The 2.1.0 train added a wave of features and
changed several defaults — each was documented by the engineer who built it, in isolation.
This pass makes the documents read like one voice and one consistent set of facts.

## Why

The 2.1.0 train shipped: index journaling as the write-path default (W-4a/c), foreign keys
with delete-veto semantics (task 42), cascade save (`@XdStorageAutoSave`), the query power
pack (range operators, `ORDER BY`/`LIMIT`/`count`), `verify()`, the power-loss recovery
recipe, the decision guide, and the benchmark estate. Each landed with documentation, but
engineer-written docs drift in terminology, cross-link shape, and emphasis — and the field
feedback that produced the landing page (task 29) set a bar the rest of the surface should
meet: **the first screens answer what/when/when-not, numbers are honest and sourced, and
nothing an evaluator needs is buried.**

## Scope (the writer's checklist)

1. **Terminology consistency** across all five surfaces: one name per concept
   ("index journal" vs "commit journal" vs "id-index journal"; "foreign key" vs "relation
   entry"; "placement policy" vs "storage policy"). Build a mini-glossary in GUIDE.md and
   conform every document to it.
2. **The feature story is complete**: every user-facing feature of 2.1.0 appears in
   GUIDE § Features at a glance, has a GUIDE section (or a clearly-scoped stub with the
   contract stated), and is reflected in EMBEDDING's operational contract where it changes
   embedder behavior (foreign-key veto semantics, index journaling + shutdown checkpoint,
   `verify()`, the recovery recipe).
3. **Numbers agree everywhere**: the performance figures quoted in README/DECISION-GUIDE/
   EMBEDDING match [BENCHMARKS.md](../../BENCHMARKS.md)'s canonical baseline (after task
   43's refresh — coordinate: this task runs AFTER 43 lands).
4. **The landing page bar** (task 29's contract, re-verified): first screens answer
   what/when/when-not; the example is ≤10 lines and compiles as written; the honest
   limitations block is above the fold; nothing an evaluator needs is buried.
5. **Cross-links and anchors**: every relative link resolves (the DOCSLINT test checks
   DECISION-GUIDE/README/example — extend its link sweep to GUIDE.md and EMBEDDING.md in
   this task); heading anchors match the links pointing at them.
6. **English quality pass**: grammar, consistent code formatting in prose (backticks on
   identifiers), no untranslated fragments in user-facing sections.
7. **CHANGELOG style**: entries read as user-facing facts (not internal task references
   alone); the Keep-a-Changelog section order is consistent.

## Discovery additions (2026-10-26, the maintainer's landing asks)

- **The AI angle in the first screen**: "a new way for AI to search for data" — the
  human-readable storage IS machine-readable for LLM agents; state it as a first-class
  positioning line (it is a genuinely differentiating 2026-era argument and the format
  already delivers it).
- **The git-friendly fixtures example**: test fixtures and transactional configurations
  stored as YAML — committed, diffed, reviewed; a concrete worked example (a fixture file
  + its diff in the GUIDE or the decision guide's good-fits section).
- **"Readable data without ORM and transformation layers"** and the **why-not-SQLite**
  answer: both exist (GUIDE § data model; DECISION-GUIDE § comparison) — verify they are
  cross-linked from the landing's first screens, not just from the deep docs.

## Discovery addition (2026-11-21): the AutoSave + ParentObject story and the secondary-key rules

The cascade-save pair (`@XdStorageAutoSave` + `@XdStorageParentObject`) is documented
today as two thin sections (GUIDE § Cascade save and § Parent references), and the
foreign-key machinery behind them — the **automatic secondary keys** — is spread across
analysis docs an adopter will never read (`docs/analysis/FOREIGN_KEYS.md`, the engine
quartet). This pass must tell it as ONE user-facing story: annotating two fields buys
you (a) cascade save of independent children, (b) a persistent bidirectional
parent↔child navigation, and (c) referential-integrity vetoes — with explicit rules
and costs. The writer's checklist for the block:

1. **The annotation pair and what each side does** (GUIDE):
   - `@XdStorageAutoSave` on the PARENT's child field — the cascade upsert contract
     (insert/update per child id, cycles terminate, removed children are NOT deleted,
     re-save is idempotent and write-amplifying; maps cascade VALUES; embedded no-op);
   - `@XdStorageParentObject` on the CHILD's back-reference — populated at child load;
     the marker that makes the child participate in the FK relation (without it the
     relation does not exist even with AutoSave on the parent).
2. **The secondary keys — what the engine maintains per relation** (GUIDE, then
   EMBEDDING for the operational contract):
   - each relation is TWO persistent B+trees: the **front** tree (parent id → child
     ids) and the **reverse** tree (child id → parent id); created at first save,
     maintained automatically in the SAME transaction as the data write — never by
     the application;
   - the trees ARE the navigation index: "load all children of P" / "load the parent
     of C" answer from the trees without scanning the data;
   - naming/identity: the resource id derives from (parent class, child class, parent
     field name) — one canonical relation per triplet, duplicates rejected.
3. **The maintenance rules** (state these as rules, one example each):
   - save(parent) with N children → 2×N secondary-key entries; save(child) → 2; an
     update diffing the parent's collection → deletions + insertions per changed pair;
   - **deletion ordering**: children are deleted first, the parent after; a
     secondary-key entry is removed with the child deletion in the same transaction;
   - **the three vetoes** (hard failures at commit): a duplicate (parent, child) pair;
     deleting a child whose parent still references it; deleting a parent that still
     has live children — the commit rolls back, no partial state;
   - id timing is engine-handled: auto-generated (deferred) child ids wait via the
     deferred-id waiters; manual-id parents are validated at commit (a parent saved in
     the same transaction clears the pending check).
4. **Features and honest boundaries** (EMBEDDING contract; DECISION-GUIDE where
   relevant):
   - the `enableForeignKeys` flag (default ON) — what OFF skips (the trees and the
     vetoes; the cascade itself still runs);
   - embedded children (`StoreWithParentObject`) bypass FK validation by design;
   - in-transaction reads see the transaction's own secondary-key changes before
     commit (read-your-own-read) — the task-53 overlay made the trees per-tx staged;
   - the cost model, honestly: proportional to the RELATION count per mutation, not
     to the dataset; **the canonical A/B numbers land only with task 50** — until
     then the docs quote no invented FK figure and point at task 50's ledger.
5. **Cross-links**: the block cross-links FOREIGN_KEYS.md (the deep subsystem record)
   for maintainers, but the user-facing text must stand alone without it. CHANGELOG's
   FK/cascade entries get one pass for the same terminology (front/reverse, secondary
   key, veto).

## Out of scope

- Writing NEW tutorials beyond what exists (the quickstart example covers the demo need);
- API javadoc coverage (task 28's separate track);
- translating the documentation (a separate decision);
- changing any code, configuration default, or benchmark number (facts come from 43).

## Acceptance criteria

- [ ] The five surfaces read as one story: glossary-conformant terminology, no
      contradicted numbers, no dead links (DOCSLINT extended to GUIDE + EMBEDDING).
- [ ] Every 2.1.0 feature is present in GUIDE § Features and cross-referenced from the
      landing page.
- [ ] The landing page still meets the task-29 bar (first screens answer the three
      questions; fresh-eyes reviewer named in the PR).
- [ ] No code changes; full suite green (BUILD SUCCESS verdict); DOCSLINT green.

Estimate: ~1–2 dev-days. Runs AFTER task 43 (the numbers it must agree with land there).

## References

- [MASTER-PLAN.md](../../MASTER-PLAN.md) Step 1 (the train whose features this documents)
- Task 29 (the landing bar), task 21 (DOCSLINT — extend here), task 43 (the numbers)
- Field feedback 2026-10-23 (the origin of the landing bar)
