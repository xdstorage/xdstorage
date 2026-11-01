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
