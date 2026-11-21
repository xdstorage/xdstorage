---
title: "[2.1.0][BINFEAS] Binary-format feasibility: measure the serialization share on cold paths (FP-9's gate)"
labels: [2.1.0-train, P2, docs, performance]
status: done
---

## Summary

An external proposal (docs/proposals/OPTIMIZATION-PROPSAL-01.md) asks for a binary
serialization format (Protobuf/Kryo/CBOR/MessagePack) and claims **5–10× faster IO**. The
serialization SPI already exists and FP-9 (binary compact format, non-default) is planned
behind exactly one gate: *"if profiling justifies."* This task runs that gate: measure the
serialization share on the paths where the text format costs, and produce a go/no-go
recommendation with numbers.

## Why

The known measured serialization cost is the **cold path**: after a reopen, reads parse
YAML per tree level (~35 µs per level, PERF study §4 — the 80–87 µs plateau at t≥32 is
mostly parse). Warm paths never re-parse (the record cache holds live instances). So the
binary-format upside is bounded by how much of real cold-path latency is parsing versus
IO — a number nobody has measured. The proposal's 5–10× is unsourced; the honest response
is data, not enthusiasm (and not rejection either — the proposal is a genuine demand
signal).

## Scope

1. **Instrument the cold path**: reopen a storage at N=5k (and the N=50k probe set from
   task 20 if available), measure per-phase wall time of the first-touch reads: base-file
   parse vs tree-node parse vs index parse vs IO — attribution via existing log/metrics
   hooks or micro-instrumentation (no production code changes; a measurement harness in
   the sweep family).
2. **The bound**: compute the theoretical binary-format ceiling — if parsing is X% of
   cold-path time, a zero-parse format can win at most X% (plus smaller files → fewer page
   reads). State the number per scenario.
3. **The go/no-go recommendation** for FP-9: GO (binary SPI is worth a design round, with
   the measured upside) or NO-GO/DEFER (the share is too small to justify a second
   serializer surface — the WRITE-PATH-DESIGN §3 rejection rationale stands).
4. The recommendation lands in MASTER-PLAN Step 4 (FP-9's gate resolved) + a summary in
   BENCHMARKS or the proposal's analysis file.

## Out of scope

- Implementing any binary format (FP-9 stays SPI-shaped, non-default, and gated);
- benchmarking third-party serializers (Kryo/protobuf) — that is the implementation
  phase's work IF the gate says go;
- warm-path optimizations (the record cache means warm reads never parse — nothing to win
  there by format alone).

## Acceptance criteria

- [ ] The measurement harness runs under the canonical conditions protocol (task 13) and
      its results are archived with a manifest.
- [ ] The cold-path serialization share is stated per scenario (parse vs IO vs other), at
      N=5k and one larger N.
- [ ] The go/no-go recommendation for FP-9 is written with the numbers, and MASTER-PLAN
      Step 4's FP-9 entry is updated (gate resolved either way).
- [ ] No production code changes; full suite green (BUILD SUCCESS verdict).

Estimate: ~0.5–1 dev-day.

## References

- [OPTIMIZATION-PROPSAL-01.md](../OPTIMIZATION-PROPSAL-01.md) + its
  [analysis](../OPTIMIZATION-PROPSAL-01-ANALYSIS.md) (the mapping and verdicts)
- [PERFORMANCE-RECOMMENDATIONS.md](../../PERFORMANCE-RECOMMENDATIONS.md) §4 (the cold-path
  parse cost data)
- [MASTER-PLAN.md](../../MASTER-PLAN.md) Step 4, FP-9 (the gate this resolves)


---

## DONE (2026-11-22): the gate executed — GO for the FP-9 design round

**The method (no production changes):** a timing DECORATOR over the serialization SPI
factory, swapped into the services locator via reflection from the harness; the reader
calls = parse+build, the writer calls = serialization; the IO floor = raw page-cache-warm
reads of the same files; the engine = the residual; the bytes lever = gzip over the YAML
(the floor of the structure-aware size reduction); medians over 7 rounds; the canonical
conditions (WARN-console logging, quiet box, manifest.json beside the results).

**The measured split (docs/perf/binfeas-2026-11-22/RESULTS.md):**

| Scenario | N=5k | N=20k |
|---|---|---|
| cold full load | serialization **84.4%**, IO 1.1%, engine 15.6% | serialization **86.4%**, IO 0.9%, engine 13.6% |
| cold FIRST lookup | 82.0% serialization inside 27 ms | 86.0% inside 97 ms |
| checkpoint write-out | 78.7% serialization | 76.3% |
| gzip bytes floor | 29.6× | 29.6× |
| warm lookups | 23 µs/op (format-blind) | 24 µs/op |

**The ceiling**: 4.2–7.4× assuming a zero-cost parser (the realized gains land at the
lower end). **The proposal's "5–10×" verdict: the right order of magnitude on the cold
paths, for the wrong reason — a PARSE win, not an IO win (the IO floor is ~1%).**

**The verdict: GO** — FP-9 proceeds to a design round (2.2.0+, non-default SPI) with
these numbers as the baseline expectations. The framing the design round inherits: the
binary format is a RESTART/COLD-START feature (the warm paths are format-blind); the
open question: the per-commit journal-append serialization share (bypasses the SPI
factory — unmeasured here).

**The disclosures:** the structure-metadata reads at open precede the decorator install
(tiny); journal appends bypass the SPI (the write arm = the checkpoint write-out);
page-cache-warm = the conservative parse-share-maximal view (disk-cold shrinks it, and
the byte lever partially cancels there).

**Acceptance criteria:**
- [x] The harness runs under the canonical conditions; the results archived
      (docs/perf/binfeas-2026-11-22/ — manifest, RESULTS.md, the raw logs).
- [x] The serialization share stated per scenario at N=5k and N=20k.
- [x] The go/no-go written with the numbers; MASTER-PLAN Step 4's FP-9 entry updated
      (GATE RESOLVED — GO); the proposal-analysis mapping row updated.
- [x] No production code changes (the probe + the entity live in the test tree,
      surefire-invisible); the suite green (BUILD SUCCESS — see the session record).


---

## THE EXPERIMENT ADDENDUM (2026-11-22, the same night): a real binary codec implemented and measured — the ceiling CONFIRMED as an upper bound

The user's directive: "напиши ридер и райтер для бинарного формата с фабрикой, подмены в тесте,
прокрути, убедись что данные не теряются, дополни документ сравнением теории с экспериментом."

**Implemented** (test-tree only): BinFeasBinaryCodec (the schema-block compact format over the
SPI's char streams: the field names once per block + the positional values, the length-framed
raw-char strings, the doubles as raw bits — the exact round trip), BinFeasBinaryIOFactory
(extends XdStorageYamlIOFactory — the journal machinery intact, the base files compact),
XdStorageBinaryFormatExperiment (the YAML arm → the conversion → the binary arm, one variable).

**The engineering findings (each a lesson):**
1. The journal-replay path type-checks `instanceof XdStorageYamlIOFactory` — the codec factory
   MUST extend the concrete YAML factory (an interface implementation NPEs the replay).
2. The resources CAPTURE their reader/writer at construction — the locator swap alone does not
   reach the live resources; the conversion must swap them too (or convert before any touch).
3. The shutdown checkpoint fires PER RESOURCE and only with a journal tail — a session that
   only loaded has nothing to rewrite (the staged-load + the per-fragment idempotent-touch
   recipe converts the data fragments through the checkpoint).
4. An idempotent update NEVER rewrites the id→fragment routing — the index journal gets no
   tail and the index checkpoint never fires; the index base converts at the file level
   (the YAML readData → the neutral records → the codec's record-block writer).
5. THE CONVENTION TRAP: the YAML readObjectData puts the id into setId() and NOT into the
   properties — a record-block writer reading properties would write null ids (the NPE that
   exposed it: fillCache's putIfAbsent(null-key)).

**The measured (docs/perf/binfeas-2026-11-22/RESULTS.md § the experiment — the full
comparison table):** cold full load **5.28×/5.20×** (the ceiling 6.49×/7.35× — 71–81%
realized), cold FIRST lookup **4.68×/4.53×** (the ceiling 5.56×/7.14×), checkpoint
**3.00×/3.68×**, bytes **3.12–3.16×** (vs the 29.6× gzip floor — the naive char-frame
codec underdelivers on size; a byte-level encoding shrinks further), the warm
index-warm/fragment-cold reads 3–5×. INTEGRITY: every entity field-equal to the source
model in both arms, all N; the bases magic-verified; the YAML FIRST-lookup numbers
reproduce the feasibility probe's — the protocol cross-validates.

**The complete FP-9 verdict:** the ceiling math CONFIRMED as an upper bound (nothing
exceeded it); the proposal's 5–10× lands at 4.5–5.3× realized with a NAIVE codec on the
cold paths (the lower end; a production codec closes part of the gap; 10× unreachable —
the engine residual bounds it). The design round inherits: the realized-gain expectations,
the char-stream SPI constraint (a byte-level SPI extension = the design round's item),
the journal-append share question, and the integrity methodology as the acceptance
template.
