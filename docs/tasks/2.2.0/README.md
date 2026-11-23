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
| 23 | [r56-multiscan-race](r56-multiscan-race.md) | the multi-scan walk race (intermittent wrong EMPTY reads under concurrent writes): the deterministic repro + the version-validated retry (the latch-held experiment is rejected with evidence) | P1, engine, bug, testing | 2–3 sess |
| 24 | [journal-append-syscalls](journal-append-syscalls.md) | the PROFILE ledger cut: the journal-append metadata syscalls (~34% of write) — the in-memory size/existence cache, mkdirs once | P2, engine, performance | 0.5 d |
| 25 | [parse-allocation-reduction](parse-allocation-reduction.md) | the PROFILE ledger cut: the cold-path parse garbage (the regex machinery + trim + UTF-8 ~10% of scan_cold) — the hand-rolled scalar handling; the FP-9 interim/fallback | P2, engine, performance | 1 d |
| 26 | [contention-probe-scale](contention-probe-scale.md) | the PROFILE ledger cut (feeds task 01): the lock-wait distribution at t=25/50/100 — the park-vs-conflict table | P2, engine, performance | 0.5 d |
| 27 | [profiling-end-of-train](profiling-end-of-train.md) | the end-of-train matrix re-run vs the 2026-11-23 ledger — the before/after gate for every landed 2.2.0 optimization claim (the task-43 attribution method) | P1, engine, performance | 1 sess |
| 28 | [fk-cascade-fresh-instance](fk-cascade-fresh-instance.md) | the PROPTEST finding #7: on a fresh instance the FK cascade commit reports success but load(parent) NULLs, the FK tree NPEs next — triage (engine vs harness), the fix rides both lines | P1, engine, bug | 1 sess + fix |
| 29 | [explain-tx-contract](explain-tx-contract.md) | the PROPTEST finding #5: explain() is the only tx-bound method that works without a transaction — the ruling + the alignment + the pin | P2, engine, docs | 0.5 sess |
| 30 | [property-lifecycle-discipline](property-lifecycle-discipline.md) | the PROPTEST finding #8: the property-harness reopen/re-registration pattern codified; the id never-reissue pin un-reds | P3, testing | 0.5 sess |
| 31 | [visibility-budget-redesign](visibility-budget-redesign.md) | the infra-visibility wait: poll-and-deadline → commit-notification (the incident: the cold-first-tx codegen exceeded the 3 s cap → serving absent; the interim = the timeout default raised to 15 s) | P2, engine, design | 1–2 sess |
| 32 | [dupkeys-concurrent-delete-miss](dupkeys-concurrent-delete-miss.md) | the concurrent targeted delete threw "does not exists" for a verified-existing pair — the sound retry predicate + the leaf-chain walk (the duplicate SPAN mechanism, no concurrency needed); the CI ×3 watch stays open | P1, engine, bug | 1 sess |
| 33 | [leaf-overflow-duplicates](leaf-overflow-duplicates.md) | the duplicate-range terminal leaves grew unbounded (the chain branch mutated nextLeaf, no split check) — the split fires on the mutated leaf + the R-54 deadline budget ported to inserts; the «переполнение ноды» invariants un-disabled | P2, engine, bug | 0.5 sess |

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
