# Architect's analysis: OPTIMIZATION-PROPSAL-01

Response to [OPTIMIZATION-PROPSAL-01.md](OPTIMIZATION-PROPSAL-01.md) (2026-10-23).
Verdict first: **the proposal is sound and current** (its write-ceiling figure matches the
fresh baseline — the authors read the post-W-4a docs), and every one of its five
optimization directions already has a home in the plan — two were designed in detail this
week, two were planned before the proposal existed, and one (async triggers) is a genuinely
new idea whose performance framing must be rejected while its feature core is adopted.
**Nothing from this proposal enters the 2.1.0 train as implementation work**; one
analysis-only task (33) does, because the proposal is a demand signal for FP-9 and its
central claim ("5–10× faster") is currently unsourced.

## The mapping

| # | Proposal direction | Where it already lives | Architect verdict | Train |
|---|---|---|---|---|
| 1 | **Binary serialization format** (Protobuf/Kryo/CBOR/MessagePack; claims 5–10× faster IO, smaller files, less GC) | MASTER-PLAN Step 4: **FP-9** — binary compact format as a non-default SPI, "post-W-4, if profiling justifies" | **Endorsed as a demand signal.** The serialization SPI already exists, and the proposal is the demand signal FP-9 waited for. But the 5–10× figure is unsourced: the known measured cost is the cold-path YAML parse (~35 µs per tree level, PERF study §4) — plausible, unproven. Measure before building. | **Task 33 (P2, in-train): DONE 2026-11-22 — GO for the FP-9 design round**: the serialization layer measured at 84–86% of the cold full load / 82–86% of the cold FIRST lookup / 76–79% of the checkpoint write-out; the ceiling ≈ 4.2–7.4× (docs/perf/binfeas-2026-11-22/RESULTS.md). Implementation stays gated (2.2.0+, non-default SPI) |
| 2a | **Group commit** (batch parallel txs into one journal write) | W4-DESIGN §3: designed as the **fallback valve** if journaling stalls — rejected as the end state ("still O(file) per flush; latency spikes") | Already designed and recorded. The W-4a numbers (writes 8.9–24×, `commitBatch` objects/s flat ~24k–31k at every batch size) show the valve is not currently needed | No task — recorded |
| 2b | **LSM structures for search-key indexes** ("random key rewrites → sequential appends") | W4-DESIGN §3: LSM-style per-delta structures **REJECTED** (rebuilds the index engine; the B+Tree is load-bearing for FP-1 range queries). **80% of the benefit ships anyway**: the W-4b logical-op journal turns search-key mutations into sequential journal appends while keeping the B+Tree for queries | The proposal's instinct (sequential writes beat random rewrites) is exactly why W-4b exists — but without the LSM rebuild | No task — W-4b (next phase) delivers it |
| 3 | **Off-heap caching** (Direct ByteBuffers / FFM API; the GC-pause concern) | MASTER-PLAN Step 2: **W-4d** (disk-anchored scans + record-cache eviction) — currently sketched as on-heap LRU | **Genuine alternative worth recording**: off-heap is how MapDB/Chronicle solve the same ceiling. Constraints the proposal misses: the library is `release=8` bytecode (the modern FFM API is Java 22+; DirectByteBuffer lifecycle management on 8 is `sun.misc` territory), and W-4d's own gate (task 20's heap data) has not yet forced the design round | Recorded as a W-4d design-round input; no task |
| 4 | **Build-time codegen** (APT / Maven-Gradle plugin; removes the runtime-JDK requirement; enables Graal) | MASTER-PLAN Step 3: **FP-3** — AOT wrapper generation, Maven plugin first, with acceptance criteria already written (JRE-only container demo, cold-start measured, native-image smoke) | Exactly FP-3, independently reinvented — the strongest confirmation of the Step-3 plan. Multi-session work (a build plugin is a shipped artifact of its own) | Stays 2.3.0 — too large for this train's tail |
| 5 | **Async/parallel triggers** (sync triggers split from async ones on a RingBuffer/Disruptor) | **NEW — not in any plan** | The performance framing is **rejected**: triggers are user code executed inside the committing transaction with veto power — that contract IS the feature (README § Triggers). Making them asynchronous does not speed the engine up (the write costs live in the commit path, now largely fixed); it *removes* their ACID meaning: no veto, no rollback coverage, no ordering, post-commit visibility. The legitimate core is a **different feature**: after-commit asynchronous listeners (the JPA transaction-synchronization / outbox pattern) — valuable, but additive and demand-driven | **FP-13 added** to MASTER-PLAN Step 4 (demand-driven, with the semantic boundary recorded). Not in 2.1.0 |

## What the proposal gets right (acknowledged)

- The competitive table is current and fair — the write-ceiling figure is the fresh
  canonical number, and the "wins/loses" lists match DECISION-GUIDE §3–§4 almost verbatim.
  The positioning work (task 12/29) is reaching readers.
- The off-heap direction is a real alternative the plan had not recorded — now it is
  (W-4d's design round will weigh on-heap LRU vs off-heap with the release=8 constraint
  stated).
- The async-trigger instinct points at a real gap: the engine has in-commit triggers but no
  after-commit hook — FP-13 closes it, as a feature rather than an optimization.

## Train decision

- **2.1.0:** task 33 only (analysis: measure the serialization share on cold paths — the
  data that turns FP-9's "if profiling justifies" gate into a yes/no). Everything else is
  recorded, not scheduled: the train already carries W-4b/W-4d (this proposal's items 2b/3
  live there), and items 1/4 are gated on their own plan criteria, not on enthusiasm.
- **Later (2.2.0+):** FP-9 implementation if task 33 justifies it; W-4d's off-heap-vs-LRU
  decision; FP-13 on a demand signal.
- **Rejected, recorded:** async triggers as a performance optimization (contract-breaking);
  LSM index rebuild (W-4b delivers the benefit without the rewrite); group commit as an end
  state (the fallback valve).
