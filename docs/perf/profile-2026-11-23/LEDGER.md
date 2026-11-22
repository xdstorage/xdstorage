# The bottleneck ledger — engine-wide profiling (PROFILE, task 14, 2026-11-23)

The method: the scenario driver (XdStorageProfilingDriver — one scenario per JVM, the
JFR/asprof window attached AFTER the setup) × the async-profiler 4.4 `ctimer` collapsed
CPU profiles (works on this box WITHOUT the perf_events sysctls; the JFR method sampler
proved unreliable through attach on this JDK — 8 samples per 17 s — while the ctimer
produced thousands) + the JFR ThreadPark/contention pass + the alloc profiles for the
memory train. The canonical conditions (WARN logging, the manifest, the quiet box).
The parser: scripts/render-profile-ledger.py (the EXCLUSIVE bucket attribution by the
leaf frame — sums to ~100%; the inclusive method list does not — each stack frame gets
the count).

## The scenario matrix results (medians; the CPU leaf shares)

### WRITE PATH — write_heavy / save_delete / batch_commit (N=5k, N=50k)

| Share | Leaf evidence | The fix family |
|---|---|---|
| **~34%** (5k) — **file-metadata syscalls**: `_lxstat` 18.4% + `__open64` 3.9% + `xstat` 3.4% + `access` 2.6% + `write` 5.3% | the journal append path pays `parent.mkdirs()` + the exists/length probes PER COMMIT (XdStorageYamlCommitJournal.append:417 + the file probes) | **JOURNALAPPEND (new task 24)**: cache the journal size/existence in memory (the journal is append-only — track the offset), `mkdirs` once; the crash-tolerance semantics untouched |
| **~9–11%** G1 GC | the write allocations (the serialization bytes + the I/O buffers) | W-4d (task 05) / the allocation task below |
| <3% each | the yaml codec, the index, the caches | — |

The write path is **syscall-bound**, not CPU-bound: the ledger's #1 is a metadata-
syscall reduction, not a code rewrite. The naive estimate: removing the per-append
stats/mkdirs ≈ up to a quarter of the single-commit write cost.

### COLD LOAD — scan_cold (N=5k, N=50k)

| Share | Leaf evidence | The fix family |
|---|---|---|
| **~50%** (24.8% at 5k, 40.2% at 50k + the JVM native 48% dominated by the G1 workers) — **G1 GC**: the concurrent-mark oop iterate, the par-scan, the rem-set rebuild | the YAML parse garbage feeds the collector | **FP-9 (task 33: GO)** + the allocation reduction (the new PARSEALLOC task) |
| **~6–10%** — the YAML scalar machinery in JDK leaves: `regex Pattern.compile/match` 6.1%, `StringLatin1.trim` 2.8%, `UTF_8 decode` 1.4% | the codec parses scalars with freshly-compiled regexes and per-scalar trims | **PARSEALLOC (new task 25)**: pre-compile/hand-roll the scalar path |
| the engine's own leaf share is thin (~1–2%) — the codec calls into the JDK leaves | | the format work (FP-9) beats the code-level micro-opts here |

### WARM READS — point_warm (N=5k, N=50k)

| Share | Leaf evidence | The fix family |
|---|---|---|
| **~50%** — the hash-map lookups: `CHM.get` 18% + `String.hashCode` 9.7% + `computeIfAbsent` 9.1% + `String.equals` 8.2% + `HashMap.getNode` 3% + `replaceNode` 2.5% | the record cache + the index maps keyed by String ids | near the structural floor; the id-typed-key idea ties to task 16 |
| **~10%** — `AbstractStringBuilder.inflate/putStringAt` | the per-op string building | the same family |
| resource/cache leaf 9.4%, reflection 3.8% | | |

### SEARCH — equals / range / paged (range_idx, N=5k, N=50k)

No single dominant hotspot: the JDK leaves 47–53%, the native 21–37%, the btree walk
3–10%, the reflection 3–6%. The search family's cost is SPREAD across the walk
machinery — no quick win; the FP-1 shapes stay as they are.

### CONCURRENT WRITERS (t=10, N=5k)

- **The FCW conflicts concentrate on the hot workers**: t0 = 3,532, t5 = 2,522, others
  ≈ 0 (the first-committer-wins stage-time refusals — the DESIGNED response; a real
  workload retries). The evidence for the consistency-core task 01: at t=10 the
  contention manifests as CONFLICTS, not long parks (only 44 ThreadPark events / 30 s).
- The high-contention park distribution (the B3 families' scale, t=50+) needs a
  dedicated probe — filed as the follow-up (CONTENTIONPROBE).

### MEMORY / ALLOCATION (the task 05 / W-4d evidence pack)

- **write_heavy alloc profile**: `byte[]` = **69.4%** of the allocation samples (the
  serialization + the I/O buffers), String 5.1% + StringBuilder 2.0%, the cache maps
  ~5%, `java.io.File` 0.6% (the per-append File constructions).
- **scan_cold alloc profile**: the JDK leaves 47.8% + the native 47.7% — the parse
  garbage dominates (the YAML strings/arrays/buffers), confirming the FP-9 thread.
- The per-object footprint (task 20's ~1.4–2 KB estimate) still needs the live-heap
  measurement (the W-4d design round's input) — the allocation RATE is what this
  evidence adds.

## The FP-9 evidence note

The task-33 feasibility already measured the parse share on the cold paths
(84–86%) and the realized binary codec (5.2×). This profiling CONFIRMS the thread
from the live-run side: the cold-load profile is dominated by the parse garbage's
GC cost (~50%) and the codec's scalar machinery (~6–10%) — both die with the binary
format.

## The ledger → the tasks

| # | The finding | The share | The task |
|---|---|---|---|
| 1 | the journal append = the metadata syscalls per commit | ~34% of write_heavy | **NEW task 24 JOURNALAPPEND** |
| 2 | the cold load = the parse garbage's GC cost | ~50% of scan_cold | **NEW task 25 PARSEALLOC** (feeds W-4d/FP-9) |
| 3 | the warm read = the map lookups + the string ops | ~60% of point_warm | covered by task 16 (the id-addressability family) — a pointer note |
| 4 | the lock-wait distribution at scale | thin parks at t=10 | **NEW task 26 CONTENTIONPROBE** (feeds task 01) |
| 5 | the parse share on the cold paths | 84–86% | already resolved: task 33 (FP-9 GO) |
| 6 | the allocation rate profile | byte[] 69% write / the parse garbage scan | feeds task 05 (W-4d) — no new task |
