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

Gates: the design rounds before 01/05 (the house rule); property tests grow as the core
changes (03 protects 01); nightly ×3 green is the train's continuous gate.

## Road Map B — platform: JRE and server

| # | File | Task | Labels | Est. |
|---|---|---|---|---|
| 08 | [fp3-jre-aot](fp3-jre-aot.md) | FP-3 pulled forward: AOT wrapper generation — run on a JRE | P0, engine, feature | 3–4 sess |
| 09 | [server-mode-feasibility](09-server-mode-feasibility.md) | server mode feasibility/design + the positioning decision (design ONLY; implementation is a 3.0 candidate) | P2, engine, feature | 1 sess |

## Train-level tasks

| # | File | Task | Labels | Est. |
|---|---|---|---|---|
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
