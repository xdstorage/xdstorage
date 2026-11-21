# BINFEAS results — the FP-9 gate (task 33, 2026-11-22)

The feasibility measurement that resolves MASTER-PLAN Step 4's FP-9 gate
("binary compact format — if profiling justifies"). The harness:
`XdStorageBinaryFormatFeasibilityProbe` (surefire-invisible; the SPI factory wrapped
with a timing decorator via reflection — no production changes; the conditions in
`manifest.json`; the raw logs `n5000.log` / `n20000.log`).

## The measured split (medians over 7 rounds; JDK 21.0.7, Ryzen 9 5900X, WARN logging, YAML, page-cache-warm files)

| Scenario | N=5,000 (8.1 MB, 82 files) | N=20,000 (32.7 MB, 316 files) |
|---|---|---|
| **Cold full load** (reopen → first-touch of every fragment + the id-index) | wall 148 ms = **serialization 84.4%** + IO floor 1.1% + engine residual 15.6% | wall 612 ms = **serialization 86.4%** + IO floor 0.9% + engine residual 13.6% |
| **Cold FIRST point-lookup** (the one truly cold op after reopen) | 27.2 ms, serialization 82.0% inside | 96.9 ms, serialization 86.0% inside |
| Warm point-lookups (the record cache holds live instances) | 23 µs/op — no parse, nothing for a format to win | 24 µs/op |
| **Checkpoint write-out** (the bulk serialization; journal appends bypass the SPI — disclosed) | serialization 78.7% of the checkpoint wall | serialization 76.3% |
| **The bytes lever** (gzip over the YAML — a floor of what structure-aware binary achieves on size) | **29.6×** smaller | **29.6×** smaller |

## The FP-9 ceiling math

A binary format eliminates (most of) the serialization layer; the ceiling per shape,
assuming a zero-cost parser:

- cold full load: **6.5×** (N=5k) … **7.4×** (N=20k)
- cold FIRST lookup: **5.6×** … **7.1×**
- checkpoint write-out: **4.2–4.7×**
- warm paths: **1×** (nothing to win — the cache means warm reads never parse)
- disk-cold starts: the parse share SHRINKS (IO grows), and the byte reduction cuts
  the IO — the two levers partially cancel; net upside is smaller than the
  page-cache-warm numbers above (which are the conservative, parse-share-maximal view).

A real binary parser is not free (deserialization + reflection remain) — expect the
realized gains at the lower end of the ranges above.

## The verdict: GO for the FP-9 design round (2.2.0+, non-default SPI)

1. The proposal's "5–10× faster" is **the right order of magnitude on the cold paths**
   — but for the wrong reason: it is a PARSE win, not an IO win (the IO floor is ~1%
   on page-cache-warm restarts; the serialization layer is 84–86%). The claim is
   unsourced no longer: measured.
2. The dominant real-world cost this addresses is the **restart experience**: the cold
   full load and the first lookups are 82–86% serialization. A service that restarts
   and serves sparse reads pays this on every uncached touch (PERF §4's depth tax —
   the same scenario).
3. The **bytes lever is real and large** (the gzip floor is 29.6×; a structure-aware
   binary typically lands below gzip-on-text), which additionally cuts disk-cold IO
   and disk footprint.
4. The writes: the bulk write-out (checkpoints) is ~76–79% serialization — a win;
   the per-commit journal append path bypasses the SPI factory and was NOT measured
   (the follow-up question for the design round: measure the journal serialization
   share before promising write-side gains there).
5. The warm paths — the bulk of a long-running service's reads — are format-blind
   (the record cache). The binary format NEVER speeds the steady state; it is a
   restart/cold-start feature. That framing belongs in the design round's scope.

**FP-9 gate: RESOLVED — GO.** The design round (2.2.0+) inherits these numbers as its
baseline expectations and its open question (the journal-append serialization share).


---

## The EXPERIMENT (2026-11-22 follow-up): a real binary codec, measured — the ceiling vs the realized

The feasibility above computed the ceiling assuming a ZERO-cost parser. The follow-up
implements an actual compact codec and measures the REALIZED gains on the same shapes —
the honest test of the ceiling math.

**The implementation** (test-tree only, surefire-invisible): `BinFeasBinaryCodec` +
`BinFeasBinaryIOFactory` (extends the YAML factory — the journal machinery stays intact;
only the base files go compact). The format: schema-blocks (field names ONCE per block,
the values positional — the YAML repeats names per object), length-framed raw-char
strings (any unicode, no escaping), doubles as raw bits (exact round-trip), type tags
per value. The factory swap: reflection into the services locator + a live-resource
reader/writer swap (the resources capture their codec at construction — the locator swap
alone does not reach them) + the file-level conversion of the id-index base (an
idempotent update never rewrites the id→fragment routing, so the index journal gets no
tail and its checkpoint never fires — the conversion stages the data fragments through
the checkpoint-with-journal-tail and converts the index directly).

**The integrity gate**: every loaded entity asserted FIELD-BY-FIELD against the
deterministic source model (all N, both arms); every lookup's payload asserted; the
bases magic-verified (`BF1`); the round trip is exact (doubles as bits).

**The protocol**: the same box/JDK/logging as the feasibility; the YAML arm measured
FIRST in the same JVM, then the conversion, then the binary arm — one variable (the
codec), the engine identical. The YAML FIRST-lookup numbers reproduce the feasibility
probe's (26.2 ms vs 26.2 ms at N=5k) — the protocol cross-validates.

### The comparison: the theoretical ceiling vs the realized (the same shapes)

| Shape | The ceiling (task 33: 1/(1 − serialization share)) | **The realized (the experiment codec)** | The ceiling realized |
|---|---|---|---|
| cold full load, N=5k | 6.49× (serialization was 84.4%) | **5.28×** (145 → 27 ms) | 81% |
| cold full load, N=20k | 7.35× (86.4%) | **5.20×** (569 → 109 ms) | 71% |
| cold FIRST lookup, N=5k | 5.56× (82.0%) | **4.68×** (26.2 → 5.6 ms) | 84% |
| cold FIRST lookup, N=20k | 7.14× (86.0%) | **4.53×** (96.3 → 21.3 ms) | 63% |
| checkpoint write-out, N=5k | 4.68× (78.7%) | **3.00×** (102 → 34 ms) | 64% |
| checkpoint write-out, N=20k | ~4.3× | **3.68×** (336 → 91 ms) | 86% |
| storage bytes | the gzip floor 29.6× (a LOWER bound of the binary shrink) | **3.12–3.16×** | the naive char-frame codec is far from the gzip floor; a byte-level format would shrink further |
| warm lookups | 1× (format-blind) | **3–5× faster anyway** at these sizes (22 → 4–7 µs): the "warm" lookups here are index-warm but fragment-cold-ish — every miss reads a fragment, and the smaller binary fragments read faster | — |

**The reading:**

1. **The ceiling math is CONFIRMED as an upper bound**: the realized gains never
   exceeded it, landing at 63–86% of the ceiling with a NAIVE codec (full-buffer
   slurp + per-char decode + the shared reflection). The gap IS the codec's own cost —
   exactly the model's assumption ("a real binary parser is not free").
2. **The proposal's "5–10×" verdict, now complete**: on the cold paths a NAIVE
   binary codec realizes **4.5–5.3×** — the right order, the lower end. A production
   codec (byte-level IO, no char-stream detour, schema evolution, no full-buffer
   slurp) would close part of the remaining gap; 10× is not reachable on these shapes
   (the engine residual — 13–16% — bounds it).
3. **The bytes lever underdelivered for the naive codec** (3.1× vs the 29.6× gzip
   floor): the experiment's codec keeps numbers as ASCII decimals and length-prefixes
   strings — the size is dominated by the decimal/text values, not the structure. A
   true binary encoding (varints, bit-packed doubles) shrinks much further. The disk-
   cold-start lever is therefore underestimated by this experiment.
4. **The write-out realized 3.0–3.7×** — the checkpoint serialization share confirmed
   from the write side too.
5. **The warm-path nuance**: the true cache-hit reads are format-blind (as designed);
   the measured "warm" 22 → 4–7 µs are INDEX-warm/fragment-cold reads — they still
   parse a fragment per miss, and the compact fragments read faster. Steady-state
   fully-warmed reads (the §4 PERF plateau) remain format-blind.

**The FP-9 design-round inputs (updated with the experiment):** the expected realized
gains for a PRODUCTION codec ≈ 5–7× on cold loads and cold lookups, ~3.5–4.5× on the
write-out, 3–10× on the footprint (the encoding-dependent); the open questions stand
(the journal-append share; the char-stream SPI constraint — the experiment ran through
the SPI's character streams, a byte-level SPI extension is part of the design round).
The integrity methodology (the field-by-field model assert) is the acceptance template
for the real implementation.

The raw logs: `experiment-n5000.log`, `experiment-n20000.log`; the codec/factory/probe:
`src/main/test/io/github/xdstorage/performance/BinFeas*` (surefire-invisible).
