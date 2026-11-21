# 2.2.0 train — two parallel road maps (compiled from docs/tasks/discovery.md, 2026-10-26)

Compiled from the maintainer's discovery directions. One markdown file = one GitHub issue
(`scripts/sync-issues.sh` scans every `docs/tasks/<version>/` directory; the milestone =
the directory name). Frontmatter carries `title` / `labels` / `status` — the same contract
as the 2.1.0 train.

## Road Map A — engine core: Memory, Consistency, Durability

| # | File | Task | Labels | Est. |
|---|---|---|---|---|
| 01 | [consistency-core-standard-primitives](01-consistency-core-standard-primitives.md) | consistency core on j.u.c primitives + enable the disabled B+Tree/multi-tree tests | P0, engine, refactor | 5–8 sess |
| 02 | [loud-failures-no-quiet-self-heals](02-loud-failures-no-quiet-self-heals.md) | the silent-recovery inventory: LOUD / KEEP+count / KEEP+WARN per site | P0, engine, tech-debt | 2–3 sess |
| 03 | [property-based-tests](03-property-based-tests.md) | jqwik property tests over the engine invariants | P1, testing | 2–3 sess |
| 05 | [w4d-memory-train](05-w4d-memory-train.md) | disk-anchored scans + eviction + resource retention (+ the off-heap decision) | P0, engine, performance | 3–5 sess + design |
| 06 | [journal-wal-txid](journal-wal-txid.md → 06-journal-wal-txid.md) | transaction id + serial at the journal EOF (WAL hardening) | P1, engine, durability | 1 sess |
| 07 | [indexes-subdirectory](07-indexes-subdirectory.md) | indexes/ subdirectory layout + one-time migration | P2, engine, chore | 1–2 sess |
| 10 | [ci-stress-gate-policy](10-ci-stress-gate-policy.md) | CI green + the stress-in-gate/nightly policy decision | P1, ci | 0.5–1 sess |
| 12 | [nested-transactions-load-safety](nested-transactions-load-safety.md) | nested transactions rework — OPTIONAL (fix or deprecate; the isolation stress test returns to the gate) | P2, engine, bug | 2–4 sess |
| 13 | [facade-load-investigation](facade-load-investigation.md) | the facade load test: failure causes + the performance improvement plan | P1, engine, performance | 2–3 sess |
| 14 | [profiling-bottlenecks](profiling-bottlenecks.md) | engine-wide profiling → the bottleneck ledger (the evidence pack for 01/05/33) | P1, engine, performance | 2 sess |
| 21 | [fk-write-path](21-fk-write-path.md) | the FK O(relations) write-path scan → point lookups (the FKBENCH 23–160× verdict must land in a stated band) | P1, engine, performance, w4-design | 2–3 sess |
| 22 | [lazy-read-snapshot](lazy-read-snapshot.md) | lazy prevSnapshot capture (read-only txs stop paying the 10–33% task-40 net; the collection-getter bypass decided inside) | P2, engine, performance, codegen | 2–3 sess |

Gates: the design rounds before 01/05 (the house rule); property tests grow as the core
changes (03 protects 01); nightly ×3 green is the train's continuous gate.

## Road Map B — platform: JRE and server

| # | File | Task | Labels | Est. |
|---|---|---|---|---|
| 08 | [fp3-jre-aot](fp3-jre-aot.md) | FP-3 pulled forward: AOT wrapper generation — run on a JRE | P0, engine, feature | 3–4 sess |
| 09 | [server-mode-feasibility](09-server-mode-feasibility.md) | server mode feasibility/design + the positioning decision (design ONLY; implementation is a 3.0 candidate) | P2, engine, feature | 1 sess |
| 15 | [backup-api](backup-api.md) | backupTo(Path) + restore contract — FP-5's backup core pulled forward (PRODUCT_REVIEW non-negotiable) | P0, engine, feature | 2–3 sess |
| 16 | [tools-cli](tools-cli.md) | xdstorage-tools CLI: verify / backup / inspect / migrate (FP-6 pulled forward; PRODUCT_REVIEW high-priority) | P1, engine, feature | 2 sess |
| 17 | [migration-schema-diff](migration-schema-diff.md) | schema diff + the migration command (PRODUCT_REVIEW #3/#5) | P2, engine, feature | 1–2 sess |
| 18 | [agent-integration-surface](agent-integration-surface.md) | the agent memory store: JSON output + read-only inspection + the documented story | P2, engine, docs, feature | 1–2 sess |

## Train-level tasks

| # | File | Task | Labels | Est. |
|---|---|---|---|---|
Announcement input: [docs/announcements/2.2.0-ANNOUNCEMENT-NOTES.md](../../announcements/2.2.0-ANNOUNCEMENT-NOTES.md) (draft notes, 2026-11-22).

| 11 | [feature-announcement](11-feature-announcement.md) | the 2.2.0 feature announcement (landing + guide) | P1, docs | 0.5–1 d |

## Discovery mapping (what went where)

- Memory items → 05 (+ task 46 in 2.1.0 for the wrapper leak); "hold the data resource
  during transactions" is 05's retention contract.
- Consistency items → 01/02 (+ task 45 in 2.1.0 for the commit-refusal diagnosis).
- Durability: "journal for StoreAsClassObjects" is **already shipped** (W-1/W-3, 2.1.0) —
  the remaining WAL/tx-id hardening is 06.
- First-priority items 1–2 → tasks 45/46 in 2.1.0 (defect-class, in the current train);
  items 3/5 → 01; item 4 → 10 (+ tasks 35/36 in 2.1.0); item 6 → 02; item 7 → 03;
  item 8 → 04.
- "JRE not JDK" → Road Map B (FP-3 pulled forward per the maintainer's demand).
- "Server mode" → Road Map B as a DESIGN-ONLY task: it contradicts the recorded
  "no servers" positioning, so the go/no-go decision is explicit and first.
- Landing items → task 44 (2.1.0) scope: the AI-search angle, the git-friendly fixtures
  example, the why-not-SQLite pointer.
- The developer analysis documents (docs/analysis/ — the consistency and durability
  implementation plans, indexed in docs/analysis/README.md) are the step-by-step inputs
  for Road Map A tasks 01/02/06/05: P0-1/P0-2/P0-3 and P1-1/P1-2 map 1:1 to those train
  tasks; P2-1/P3-1/P3-2 are the follow-ups (tasks 19/20 in 2.1.0).
