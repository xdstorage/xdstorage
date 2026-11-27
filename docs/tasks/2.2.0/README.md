# 2.2.0 train — road maps: discovery (2026-10-26) + the external-evaluation intake (2026-11-23)

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
| 12 | [nested-transactions-load-safety](12-nested-transactions-load-safety.md) | nested transactions rework — OPTIONAL (fix or deprecate; the isolation stress test returns to the gate) | P2, engine, bug | 2–4 sess |
| 13 | [facade-load-investigation](13-facade-load-investigation.md) | the facade load test: failure causes + the performance improvement plan | P1, engine, performance | 2–3 sess |
| 14 | [profiling-bottlenecks](14-profiling-bottlenecks.md) | engine-wide profiling → the bottleneck ledger (the evidence pack for 01/05/33) | P1, engine, performance | 2 sess |
| 21 | [fk-write-path](21-fk-write-path.md) | the FK O(relations) write-path scan → point lookups (the FKBENCH 23–160× verdict must land in a stated band) | P1, engine, performance, w4-design | 2–3 sess |
| 22 | [lazy-read-snapshot](22-lazy-read-snapshot.md) | lazy prevSnapshot capture (read-only txs stop paying the 10–33% task-40 net; the collection-getter bypass decided inside) | P2, engine, performance, codegen | 2–3 sess |
| 23 | [r56-multiscan-race](23-r56-multiscan-race.md) | the multi-scan walk race (intermittent wrong EMPTY reads under concurrent writes): the deterministic repro + the version-validated retry (the latch-held experiment is rejected with evidence) | P1, engine, bug, testing | 2–3 sess |
| 24 | [journal-append-syscalls](24-journal-append-syscalls.md) | the PROFILE ledger cut: the journal-append metadata syscalls (~34% of write) — the in-memory size/existence cache, mkdirs once | P2, engine, performance | 0.5 d |
| 25 | [parse-allocation-reduction](25-parse-allocation-reduction.md) | the PROFILE ledger cut: the cold-path parse garbage (the regex machinery + trim + UTF-8 ~10% of scan_cold) — the hand-rolled scalar handling; the FP-9 interim/fallback | P2, engine, performance | 1 d |
| 26 | [contention-probe-scale](26-contention-probe-scale.md) | the PROFILE ledger cut (feeds task 01): the lock-wait distribution at t=25/50/100 — the park-vs-conflict table | P2, engine, performance | 0.5 d |
| 27 | [profiling-end-of-train](27-profiling-end-of-train.md) | the end-of-train matrix re-run vs the 2026-11-23 ledger — the before/after gate for every landed 2.2.0 optimization claim (the task-43 attribution method) | P1, engine, performance | 1 sess |
| 28 | [fk-cascade-fresh-instance](28-fk-cascade-fresh-instance.md) | the PROPTEST finding #7: on a fresh instance the FK cascade commit reports success but load(parent) NULLs, the FK tree NPEs next — triage (engine vs harness), the fix rides both lines | P1, engine, bug | 1 sess + fix |
| 29 | [explain-tx-contract](29-explain-tx-contract.md) | the PROPTEST finding #5: explain() is the only tx-bound method that works without a transaction — the ruling + the alignment + the pin | P2, engine, docs | 0.5 sess |
| 30 | [property-lifecycle-discipline](30-property-lifecycle-discipline.md) | the PROPTEST finding #8: the property-harness reopen/re-registration pattern codified; the id never-reissue pin un-reds | P3, testing | 0.5 sess |
| 31 | [visibility-budget-redesign](31-visibility-budget-redesign.md) | the infra-visibility wait: poll-and-deadline → commit-notification (the incident: the cold-first-tx codegen exceeded the 3 s cap → serving absent; the interim = the timeout default raised to 15 s) | P2, engine, design | 1–2 sess |
| 32 | [dupkeys-concurrent-delete-miss](32-dupkeys-concurrent-delete-miss.md) | the concurrent targeted delete threw "does not exists" for a verified-existing pair — the sound retry predicate + the leaf-chain walk (the duplicate SPAN mechanism, no concurrency needed); the CI ×3 watch stays open | P1, engine, bug | 1 sess |
| 33 | [leaf-overflow-duplicates](33-leaf-overflow-duplicates.md) | the duplicate-range terminal leaves grew unbounded (the chain branch mutated nextLeaf, no split check) — the split fires on the mutated leaf + the R-54 deadline budget ported to inserts; the «переполнение ноды» invariants un-disabled | P2, engine, bug | 0.5 sess |
| 50 | [commit-marker-cross-resource-atomicity](50-commit-marker-cross-resource-atomicity.md) | the cross-resource commit marker: a crash between a commit's journal appends leaves the transaction HALF applied today (PROVEN on real crash folders — whichever of the four appends is missing, exactly one row survives) — one durable point of no return, the commit journal at the folder root, behind a flag | P0, engine, durability | 2–3 sess + the soak |
| 51 | [shutdown-checkpoint-commit-race](51-shutdown-checkpoint-commit-race.md) | SHUTCKPT: the shutdown checkpoint cut a commit between its phases — a commit that returned success was lost at the next open; `shutdown()` returned under a write; `transaction.commit()` was not refused after the shutdown (found by the Windows leg, run 3) | P0, engine, durability, bug | 1 sess |

Gates: the design rounds before 01/05 (the house rule); property tests grow as the core
changes (03 protects 01); nightly ×3 green is the train's continuous gate.

## Road Map B — platform: JRE and server

| # | File | Task | Labels | Est. |
|---|---|---|---|---|
| 08 | [fp3-jre-aot](08-fp3-jre-aot.md) | FP-3 pulled forward: AOT wrapper generation — run on a JRE | P0, engine, feature | 3–4 sess |
| 09 | [server-mode-feasibility](09-server-mode-feasibility.md) | server mode feasibility/design + the positioning decision (design ONLY; implementation is a 3.0 candidate) | P2, engine, feature | 1 sess |
| 15 | [backup-api](15-backup-api.md) | backupTo(Path) + restore contract — FP-5's backup core pulled forward (PRODUCT_REVIEW non-negotiable) | P0, engine, feature | 2–3 sess |
| 16 | [tools-cli](16-tools-cli.md) | xdstorage-tools CLI: verify / backup / inspect / migrate (FP-6 pulled forward; PRODUCT_REVIEW high-priority) | P1, engine, feature | 2 sess |
| 17 | [migration-schema-diff](17-migration-schema-diff.md) | schema diff + the migration command (PRODUCT_REVIEW #3/#5) | P2, engine, feature | 1–2 sess |
| 18 | [agent-integration-surface](18-agent-integration-surface.md) | the agent memory store: JSON output + read-only inspection + the documented story | P2, engine, docs, feature | 1–2 sess |

## External-evaluation intake (2026-11-23 — tasks 34–49)

Compiled from the external evaluation of 2026-11-23 (a static third-party review of
commit `49f54be`). EVERY finding was re-verified against main @ `fec923d` before
filing — several were already fixed (the disabled B+tree invariants → task 33; the FK
chain-scan → task 21) or covered (the commit atomicity → task 06 amended; the polling
locks → task 01 confirmed; the JDK/CWD coupling → task 08 confirmed). The evaluation
document itself stays OUT of the repo: its §5 analyzes the developer personally
(weekend/timezone stats), which does not belong in a public archive.

| # | File | Task | Labels | Est. |
|---|---|---|---|---|
| 34 | [checkstyle-build-gate](34-checkstyle-build-gate.md) | Checkstyle in the build: the bounded ruleset, the committed baseline, the ratchet gate (+ the exec-bit sweep: 138 files) | P2, build, tech-debt | 1–2 sess |
| 35 | [lazy-getter-collection-check](35-lazy-getter-collection-check.md) | WRAPGEN: the lazy-getter codegen asks the type question backwards — List/Set/Map-typed lazy fields load as single objects (silent wrong reads) | P1, engine, bug, codegen | 1 sess + fix |
| 36 | [xml-reader-xxe-hardening](36-xml-reader-xxe-hardening.md) | XXE: the four StAX sites parse DTDs/external entities bare — harden + the loud pin (CWE-611; the 2.1.4 hotfix candidate) | P1, security, bug | 0.5–1 sess |
| 37 | [data-folder-trust-boundary](37-data-folder-trust-boundary.md) | TRUSTBDY: opening a foreign folder instantiates attacker-named classes — the trust boundary doc + the registry whitelist decision | P2, security, design | 1–2 sess |
| 38 | [find-readlock-leak](38-find-readlock-leak.md) | LKLEAK: the find leaks the tree read latch when the descent throws before the coupling point (R-35, live at BTree:1497–1500) | P1, engine, bug | 1–2 sess |
| 39 | [multi-index-point-search](39-multi-index-point-search.md) | IDXPT: the MULTI-tree point search scans the whole leaf chain (the R-46 ban) — revisit with the task-32 walk machinery; the N50k row 5219→110 ops/s is the gate | P2, engine, performance | 2–3 sess |
| 40 | [yaml-string-newline-null](40-yaml-string-newline-null.md) | YAMLSTR: the newline-in-string corrupts the record (only `'` escaped) + the literal "null" reads back as null — silent corruption; PropModel un-avoidance | P1, engine, bug | 1–2 sess |
| 41 | [simple-types-uuid-java-time](41-simple-types-uuid-java-time.md) | SIMPLETYPES: UUID + java.time are not simple types (persist as child references, degrade) — the built-ins + the extension-surface answer | P2, engine, feature | 1–2 sess |
| 42 | [audit-claims-triage](42-audit-claims-triage.md) | AUDCLAIMS: verify the three unverified audit claims (the XML end marker; the short-class-name folder collisions; the never-evicting metadata caches) | P2, engine, testing | 1 sess |
| 43 | [verify-report-api](43-verify-report-api.md) | VERIFYAPI: verify() prints to stderr unconditionally (11 sites incl. tools/Compact) — the structured report (the task-16 CLI's input) | P2, engine, tech-debt | 1 sess |
| 44 | [deprecated-newinstance-sweep](44-deprecated-newinstance-sweep.md) | REFLECT: the deprecated Class.newInstance() ×34 — getDeclaredConstructor().newInstance() with the cause-preserving unwrap (per-catch-site audited) | P3, engine, tech-debt | 0.5–1 sess |
| 45 | [docs-factual-drift](45-docs-factual-drift.md) | DOCSDRIFT: the fsync cost 8× vs 95× across docs; the lock timeout 5 s (javadoc) vs 100 ms (code); the GUIDE's stale "3 s default" — one truth + one source each | P2, docs, tech-debt | 1 sess |
| 46 | [degradation-paths-audit](46-degradation-paths-audit.md) | DEGPATHS: catch(Throwable) ×69 / catch(Exception) ×235 / Thread.sleep ×8 — the per-site DELIBERATE/SWALLOW/BUG ledger (read-only; the fixes spin) | P3, engine, tech-debt | 1–2 sess |
| 47 | [ci-red-rate-triage](47-ci-red-rate-triage.md) | CISTAB: pushes 17/66 red, nightlies 8/11, releases 4/8 — the failure-family census and elimination (the task-10 policy input; the re-census <5%) | P1, ci, tech-debt | 1–2 sess + watch |
| 48 | [search-index-journal-durability](48-search-index-journal-durability.md) | SRCHJNL: the search-index journal never flushes in any mode — the durability contract + the flush-vs-rebuild decision | P2, engine, durability | 1 sess + impl |
| 49 | [process-posture](49-process-posture.md) | PROCESS: the commit subjects ≤72+body (the journal moves to the body); the direct-commit posture recorded; the self-authored review docs honestly labeled | P3, chore | 0.5 sess |

The evaluation-finding mapping (what went where):

- §3.3.1 the commit non-atomicity → task 06 (amended: the mid-file replay corruption
  is NOT a torn tail — loud per task 02 + the remainder counter; LANDED 2026-11-24) + the
  cross-resource hole itself → task 50 (PROVEN on real crash folders; the design round
  in docs/analysis/COMMIT_ATOMICITY_DESIGN.md).
- §3.3.2 durability off by default → stays a documented product decision; the search
  journal's never-flush → task 48.
- §3.3.3 the polling locks → task 01 (independent confirmation; the javadoc mismatch → 45).
- §3.3.4 the MULTI point-search chain scan → task 39.
- §3.3.5 the read-lock leak → task 38 (R-35, confirmed live).
- §3.3.6 the YAML dialect edges → task 40.
- §3.3.7 the serialization defects → already fixed 23.11 (2.1.2; the evaluation acknowledges).
- §3.3.8 UUID/java.time → task 41.
- §3.3.9 the trust boundary + XXE → tasks 36/37.
- §3.3.10 the JDK/CWD coupling → task 08 (confirmed + the CWD design constraint;
  LANDED 2026-11-27 — build-time wrappers through a standalone plugin module; the demo
  proven on a jlink image in a read-only sandbox, the container image itself not built).
- §3.3.11 the journal replay → task 06 (amended).
- §3.3.12 the inverted lazy-getter checks → task 35.
- §3.3.13 the stderr in verify() → task 43 (+ 34's ban unblocked).
- §3.3.14 the unverified claims → task 42.
- §3.4/§3.5 the code/test metrics → tasks 34/44/46 (the disabled invariants of §3.5 —
  already un-disabled by task 33, no task).
- §4.3 the process critiques → tasks 47/49 (the CI red rate; the convention/labeling).

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
