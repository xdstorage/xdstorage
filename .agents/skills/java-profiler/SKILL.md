---
name: java-profiler
description: "Profile Java/JVM applications and libraries to find CPU hotspots, allocation pressure, lock contention, and GC behavior — with the JFR-first workflow that works on locked-down boxes (jcmd + jfr view, zero kernel permissions), async-profiler as the second stage (needs perf_event_paranoid<=1), and the house measurement discipline (canonical conditions, manifests, integrity gates, attribution). Use when the user asks to profile, find hotspots/bottlenecks, explain a slowdown, measure allocation/GC pressure, produce a flame graph or a bottleneck ledger, or loosely ('что тормозит', 'запрофилируй', 'где память жрётся')."
---

# Java profiler — JFR-first, house-disciplined

You profile running JVMs to answer cost questions with MEASURED shares, not guesses.
This skill encodes the workflow proven on the XdStorage engine (Linux x64, JDK 21,
`perf_event_paranoid=4`, no root assumed) — the rules apply to any Java/JVM library.

## 0. The box reality check (run FIRST, every time)

```bash
cat /proc/sys/kernel/perf_event_paranoid   # 4 = perf_events locked (async-profiler CPU sampling won't work; JFR doesn't care)
cat /proc/sys/kernel/kptr_restrict         # 1 = kernel pointers hidden (native frames limited)
which jcmd jfr jps asprof                  # jcmd/jfr/jps ship with the JDK; asprof = async-profiler installed?
```

- **JFR** works always (it is in the JDK; JDK 11+ unlocks FlightRecorder by default).
- **async-profiler** needs `sudo sysctl kernel.perf_event_paranoid=1` (+ `--cap-add SYS_ADMIN`
  in Docker); without it: "Perf events unavailable" — either get the sysctl or use JFR.
- async-profiler 4.2+/4.4 for modern JDKs; it sees native/JIT frames and has alloc/lock
  events JFR lacks — the second stage, not the default.

## 1. The JFR workflow (default)

**A live process** (attach by PID; find it with `jcmd -l` or `jps -l`):

```bash
jcmd <pid> JFR.start name=prof settings=profile duration=<W>s filename=<run>.jfr
# ... reproduce the load DURING the window (a profile without load proves nothing) ...
# the dump happens automatically at the duration end
```

**A driver you control** (a main() whose setup must NOT pollute the profile): launch the
driver in the background, it fills the data and prints its PID + READY, then attach with
`jcmd` for exactly the measured window; the driver loops the measured op until the window
elapses.

**Parsing (JDK 21 `jfr view` — no JSON plumbing needed):**

```bash
jfr view hot-methods <run>.jfr            # the CPU hotspots by sample count
jfr view allocation-by-class <run>.jfr    # the allocation pressure by class (feeds memory trains)
jfr view allocation-by-site <run>.jfr     # the allocation call sites
jfr view gc <run.jfr>                     # pause times, counts
jfr print --events jdk.ThreadPark <run>.jfr   # ReentrantLock/LockSupport waits — THE lock-contention
                                              # evidence (JFR does NOT instrument ReentrantLock directly;
                                              # ThreadPark catches the AQS parks with durations + blockers)
```

Aggregate per-method percentages from `hot-methods` samples; rank, and account for ≥80%
of the wall (the task-31 discipline: profile to ≥80% accounted, rank by share, only then
design).

## 2. The async-profiler workflow (second stage; needs the sysctl)

```bash
asprof start -e cpu -f <run>.html <pid>    # cpu | alloc | lock | wall
asprof stop <pid>
```

Use when: native/JIT frames matter (file IO, compression), alloc/lock events with real
stacks are needed, or JFR's sampling fidelity is insufficient. 4.2+ for JDK 21+; check
the release discussion for the compatibility notes on every bump.

## 3. The house measurement discipline (non-negotiable)

1. **The canonical conditions** (the task-13 protocol): a quiet box (the loadavg gate),
   WARN-console logging, a manifest (rev, JDK, CPU, loadavg, flags), results archived
   under docs/perf/<name>-<date>/.
2. **ONE scenario per JVM run** — the profiles don't mix.
3. **The integrity gate**: the measured loop asserts it touches the right data (a profile
   of the wrong loop proves nothing).
4. **`timeout N` on every run** (the maintainer directive); a hung run is itself a finding.
5. **Reproduce the real load during the window**; an idle-window profile measures GC idle.
6. **The inlining caveat**: JIT-inlined methods merge into callers — a small method may be
   invisible; interpret the leaf-level shares with this in mind.
7. **Feed the ledger, not the ego**: every profile lands as ranked shares mapped to fix
   families, each with its evidence artifact — the format of the task-31/14 bottleneck
   ledgers (docs/W4-DESIGN.md § Bottleneck ledger).
8. **The pre-measured-share check**: before profiling for a feasibility question, check
   whether the share is already measured (the ceiling may already exist — the task-33
   pattern: serialization 84–86% was measured before anyone profiled the cold load again).

## 4. The trap registry (each cost a cycle — 2026-11-23, the XdStorage ledger session)

- `perf_event_paranoid=4` (Debian/Ubuntu hardened default) kills async-profiler CPU
  sampling silently for unprivileged users — JFR is the fallback that always works.
- **THE JFR-ATTACH SAMPLER VALIDATION RULE**: the JFR method sampling through `jcmd
  attach` proved UNRELIABLE on JDK 21.0.7/Linux — 8 samples per 17 s on a 100%-CPU
  loop, while the SAME profile.jfc on a pure burn loop gave 795 per 8 s. ALWAYS run
  the pure-burn control first (`while nanoTime<deadline: x+=rnd.nextInt()`): if the
  control samples fine but your driver doesn't, switch the CPU evidence to
  async-profiler `ctimer` (works without root/perf_events — JDK 18+ cooperative timer).
- **asprof start-without-stop loses the data**: `asprof start -f f.html` then the JVM
  exits = an empty file. Use the duration form: `asprof -e ctimer -d <sec> -o collapsed
  -f out.txt <pid>`; `-o collapsed` feeds the ledger parsers (each line
  `frame;frame;...;count`), the HTML feeds humans.
- **The collapsed output needs normalization**: the frames are JVM-slash names
  (`io/github/...`, `G1ParScanThreadState::...`) — normalize `/`→`.` before the
  classification; the GC frames (G1*) must be their own bucket (they legitimately
  dominate allocation-heavy workloads — that IS a finding); the ledger attribution is
  EXCLUSIVE by the leaf frame (sums to ~100%) — an inclusive per-frame attribution
  sums to N×100% and reads as nonsense. The ready parser:
  scripts/render-profile-ledger.py.
- **THE LEAF BUCKET CANNOT SEPARATE THE CALL SITES** (the FKWRITE mis-attribution,
  2026-11-23): a hot leaf frame serving MULTIPLE call paths (e.g. `BTreeNode.read`
  fired under both the tree finds AND the commit write-out) lands in ONE bucket —
  the bucket-level view tells the WHAT (the node deserialization dominates) but not
  the WHERE (which path owns it). Before designing a fix from a bucket share, do the
  STACK-LEVEL decomposition on the raw collapsed file: `grep "<the-path-frame>;"`
  and sum the counts per candidate ancestor chain (the real case: "the commit
  write-out is THE cost" 46/17 flipped to "the finds are 46%, the commit is 17%" —
  the journal design was aimed at the secondary path until the stack re-read).
- **The contention scenarios must RETRY the designed conflicts**: a worker that dies
  on the engine's first-committer-wins refusal kills the whole run AND loses the
  contention evidence; the retry loop's CONFLICT COUNTER is itself the contention
  finding (the hot-worker concentration = the designed response working).
- **The deadline-vs-attach race**: the driver's measured window must start AFTER the
  profiler attaches (the wrapper detects the driver's READY line, then attaches) —
  a driver-side deadline counted from its own start races the attach and truncates
  the window.
- **A session that only LOADED holds the folder lock** — the reopen rounds of the
  same folder block forever on it; shut the fill session down before the rounds (or
  profile the open-scan-close cycle the workload actually does).
- A JFR window with no load = an idle profile (GC logs of a sleeping JVM).
- `jfr view` view names vary by JDK (check `jfr view` bare for the list on THIS JDK);
  the contention/allocation views aggregate jdk.ThreadPark/ObjectAllocationSample —
  the EVENT-based evidence (not sample-based) survives where the method sampler
  doesn't.
- The same-name reopen trap (the provider registry returns the live instance) — the
  profiling driver must use unique storage names per reopen or it profiles a warm cache.
- The driver's setup phase (the fill) pollutes the profile unless the JFR window is
  attached AFTER the setup (the jcmd attach, not the JVM flag with a guessed delay).
- GC allocations during the measured loop are dominated by the loop's own temporary
  objects unless the loop reuses buffers — design the loop to expose the LIBRARY's
  allocation, not the harness's.
