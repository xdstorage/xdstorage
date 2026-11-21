---
title: "[2.1.0][DOCSWRITER] Technical-writer pass: landing page, guide, and feature docs as one coherent story"
labels: [2.1.0-train, P1, docs]
status: done (2026-11-22 — the pass landed; the five surfaces + BENCHMARKS re-grounded to the 2026-11-21 canonical baseline; DOCSLINT extended to GUIDE/EMBEDDING/CHANGELOG + anchors; suite 1345/0f/0e; the closing STATUS block below)
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

## Discovery addition (2026-11-22): the user-facing inventory — "what you're getting into"

The train's final state (everything except this task and 33 closed) shipped more than the
checklist above covers. The writer's pass must make every one of these **visible to an
evaluator within the first two screens or one GUIDE click** — facts already verified and
published in the ledgers, needing prose and placement:

**Features (lead the what's-new):**
1. **Concurrent writes unlocked** — B+tree key mutations stage per-transaction and apply at
   commit; the commit acquisition order is total. The headline number: 10 concurrent writers
   **121–158 tx/s, p99 84–113 ms** (2.0.x collapsed the same load to ~1 tx/s through lock
   timeouts). Nothing user-facing says this today — the README/GUIDE concurrency story is
   pre-overlay. Source: BENCHMARKS/DECISION-GUIDE numbers + task 53.
2. **`verify(folder)`**, **`explain()`**, the query power pack (ranges/ORDER BY/LIMIT/count),
   `@XdStorageAutoSave` cascade + `@XdStorageParentObject` foreign keys (the secondary-key
   story per the 2026-11-21 block), the upgrade walkthrough — all shipped; check each has a
   landing mention, not just a GUIDE section.

**Bugs fixed (the "what changed for me" list):**
3. **Commit-time conflict revalidation** — first-committer-wins now covers the whole
   stage→commit window; the loser gets a LOUD conflict (the record + the foreign commit
   named) with the data intact; retry = a fresh read. The old silent-lost-update window is
   gone (EMBEDDING § Transactions now states it — keep GUIDE consistent).
4. **Mixed-type keys no longer CCE** — a tree holding `Long` and `String` keys works
   (tolerant compare); a foreign-typed range bound fails with a DESIGNED loud error at
   query construction. A real-world FK pairing (UUID parent + auto-Long child) previously
   crashed; now documented.
5. **The JavaBeans setter contract made explicit** — a field with a getter but NO setter is
   **dropped from persistence with a WARN** (nothing round-trips). GUIDE § entity rules +
   EMBEDDING § Codegen carry it now; the writer must keep it prominent — this is a
   silent-data-loss-class trap for Lombok/generated entities.
6. The defect ledger is fully closed: BUG-A..E, DUPKEYS (MULTI-tree deletes), the reopen
   key-type restoration, the record-identity envelope — each already changelogged; the
   writer's job is the digest version on the landing/docs, not new facts.

**Performance (honest, both directions):**
7. **W-4a/c journals** — 64.9 → 0.13 ms/tx small writes; checkpoint-on-clean-shutdown
   (already in CHANGELOG; verify the README numbers cite the 2026-11-21 baseline).
8. **The FK cost, honestly** — BENCHMARKS § FK A/B: cascade-save **23–160×**, child delete
   **12–73×**, parent update 1.4–12.5× — outside the ≤2× band at every size, scaling
   LINEARLY with the FK-tree size; the FK read path is free. DECISION-GUIDE already carries
   the row; the landing must too — **a user enabling FK on a hot write loop must know
   before adopting, not after**.
9. **The one regression, attributed** — `saveAndDeleteIndexedShipWithCrew` **−24%** vs the
   2026-10-24 baseline = the correctness wave's price (the overlay staging + the W-4b
   journal; machine drift ruled out by re-running the old revision today). DECISION-GUIDE
   has the row; keep it — the honesty IS the positioning.
10. **The read-snapshot cost** — eager capture ~10–33% by graph size (measured, accepted as
    the alias-safety net; the lazy capture is a 2.2.0 candidate). One sentence in the GUIDE
    performance section.
11. **Runtimes** — JDK 17 (gate) + JDK 21 (CI leg), bytecode `release=8`; README Building +
    EMBEDDING § JDK matrix already updated — verify the landing's requirements line agrees.

**Where each fact must land:** landing what's-new (items 1, 2, 8), GUIDE (3, 4, 5, 10),
EMBEDDING (3, 5, 11 — mostly landed), CHANGELOG (already carries all of it — the writer
edits prose, never the facts). The 2.2.0 forward pointer:

**The next-train announcement notes live in
[docs/announcements/2.2.0-ANNOUNCEMENT-NOTES.md](../../announcements/2.2.0-ANNOUNCEMENT-NOTES.md)**
(the writer's raw input for 2.2.0 task 11 — the per-task user-meaning notes, the honest-costs
carry-over block, and the mechanics). Do not merge its content into 2.1.0 documents; link it.

## Out of scope

- Writing NEW tutorials beyond what exists (the quickstart example covers the demo need);
- API javadoc coverage (task 28's separate track);
- translating the documentation (a separate decision);
- changing any code, configuration default, or benchmark number (facts come from 43).

## Acceptance criteria

- [x] The five surfaces read as one story: glossary-conformant terminology, no
      contradicted numbers, no dead links (DOCSLINT extended to GUIDE + EMBEDDING).
- [x] Every 2.1.0 feature is present in GUIDE § Features and cross-referenced from the
      landing page.
- [x] The landing page still meets the task-29 bar (first screens answer the three
      questions; fresh-eyes reviewer named in the PR).
- [x] No code changes; full suite green (BUILD SUCCESS verdict); DOCSLINT green.

Estimate: ~1–2 dev-days. Runs AFTER task 43 (the numbers it must agree with land there).

## STATUS (2026-11-22): DONE

Landed on main (one commit — DOCSWRITER 44). The pass covered the five surfaces (README,
GUIDE, DECISION-GUIDE, EMBEDDING, CHANGELOG) plus docs/BENCHMARKS.md (the numbers source
three of them cite).

**What landed:**

- **Terminology** — GUIDE § Terminology (the 13-term glossary) added; every surface
  conformed: "storage policy" (never "placement policy"), the journal family
  (data / id-index / search-key under "commit journal"), "secondary keys / FK relation",
  "veto".
- **The feature story is complete** — GUIDE gained § Foreign keys and the secondary keys
  (the ONE user-facing FK story: the annotation pair, the front/reverse trees, the triplet
  identity, the maintenance rules and the three vetoes, `enableForeignKeys`, the embedded
  bypass, read-your-own-read, the honest cost), § explain() (query plans), § Operability
  (verify/backups/recovery), the concurrency-overlay story (10 writers 121–158 tx/s, total
  acquisition order) and the commit-time revalidation contract; the README gained
  § What's new in 2.1.0, the AI-angle positioning line and the fixtures bullet.
- **Numbers agree everywhere** — README/DECISION-GUIDE/EMBEDDING/GUIDE re-grounded to the
  2026-11-21 canonical baseline (reads ~217k/~225k, ranges ~24k/~15k, scan ~1.5k, paged
  ~15.9k, readOnlyTx ~71k, rename ~1.7k, save+delete ~600 with the −24% attribution,
  writes ~8.1k/~3.1k with the 2.6× journal ratio, fsync ~95×, p99 84–113 ms exact);
  BENCHMARKS's intro re-anchored to 2026-11-21; the § In context / § Design point /
  § Field-frame sources sections RESTORED (lost in the task-13 rework while
  DECISION-GUIDE still cited them — recovered from git, honestly re-dated: the design
  point's write rows labeled pre-W-4a with the 0.13 ms/tx update).
- **CHANGELOG restructured** — the duplicate "Changed (planned)" sections merged,
  "(planned)" dropped (the train is complete), the duplicate entries deleted
  (verify/AutoSave/W-4a-c/javadoc each listed twice); BUG-A/C/D/E and EXPLAIN moved from
  [2.0.0] to Unreleased (git-verified: all fix commits + 49aa582 are descendants of
  v2.0.0 = 6224066 — the released section held post-release fixes); BUG-B → a new
  "### Known issues (carried)"; the section order normalized to
  Breaking → Added → Changed → Fixed in both releases; ~10 root-relative dead links
  fixed.
- **DOCSLINT extended** (the test delta, this task's own scope item 5): the link sweep
  now covers GUIDE + EMBEDDING + CHANGELOG, and a new `anchorsResolve` test checks every
  `file.md#anchor` target (GitHub slug rules) and same-document anchors.
- **The landing bar holds** — first screens answer what/when/when-not; the honest
  FK cost (1.4–160×, the three-shape split), the −24% regression and fsync ~95× are on
  the landing BEFORE the adoption decision; the quickstart example compiles as written.

**The fresh-eyes reviewer:** a general-purpose review subagent (in-session, read-only;
17 findings, 7 P1). Every claim was verified against the sources before fixing; the
notable verified-and-fixed: the README's one-arg `XdStorageSearchQuery` call did not
compile (only `()` and `(primary, criterion)` exist) and its import block was wrong and
incomplete — proven red-before/green-after by the compile-proof; the GUIDE
`UUID_GENERATOR` row contradicted the BUG-D matrix; the fsync figure said "modest"
against the published ~95×; CHANGELOG carried dead `[W4-DESIGN.md](W4-DESIGN.md)` links.

**Gate:** full suite **1345/0f/0e, BUILD SUCCESS** (twice — once with the extended
DOCSLINT in-run), DOCSLINT green standalone, README quickstart compile-proof OK
(fence extraction + entity stubs → javac). "No code changes" held for production code —
the only code delta is the DOCSLINT test extension this task itself mandates.

**Out of scope, held:** no new tutorials, no translation, no benchmark-number edits
(facts from task 43; the restored § In context / § Design point sections carry their
own dated sources).

## References

- [MASTER-PLAN.md](../../MASTER-PLAN.md) Step 1 (the train whose features this documents)
- Task 29 (the landing bar), task 21 (DOCSLINT — extend here), task 43 (the numbers)
- Field feedback 2026-10-23 (the origin of the landing bar)
