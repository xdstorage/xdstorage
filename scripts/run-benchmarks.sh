#!/usr/bin/env bash
# ============================================================================
# BENCH (docs/tasks/2.1.0/13): the ONE canonical entry point of the benchmark
# estate. Runs the JMH suite (the canonical CORE scenarios + the search-engine
# scenarios) and/or the config sweep under the canonical CONDITIONS PROTOCOL,
# and archives machine-readable results with a run manifest.
#
# Usage: scripts/run-benchmarks.sh [quick|full|sweep] [--force]
#
#   quick  — 1 iteration per scenario (ballpark; the nightly smoke shape)
#   full   — the canonical default warmup/measurement of each class (baseline
#            tables in BENCHMARKS.md are generated from FULL runs only)
#   sweep  — the plain-Java config sweep (X dStorageConfigSweep; JSON+CSV into
#            target/sweep, copied into the results directory)
#
# Conditions protocol (the script OWNS it, so it cannot drift):
#   - quiet-machine gate: loadavg must be below BENCH_MAX_LOADAVG (default 2.0)
#     before the run; --force overrides, the measured loadavg lands in the
#     manifest either way;
#   - one explicit logging config (docs/perf/benchmark-log4j2.xml, WARN console,
#     no hot-path file I/O) passed via -Dlog4j.configurationFile — immune to the
#     suite's own logging config reshuffles;
#   - every result set is archived under target/bench-results/<utc>-<mode>/
#     together with manifest.json (git rev, JDK, CPU model, loadavg, date,
#     mode, config flags, scenario list).
#
# Baseline tables in docs/BENCHMARKS.md are GENERATED from full-run JSON by
# scripts/render-bench-baseline.py — never hand-typed.
# ============================================================================
set -euo pipefail

MODE="${1:-quick}"
FORCE="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAX_LOADAVG="${BENCH_MAX_LOADAVG:-2.0}"
RESULTS_ROOT="$ROOT/target/bench-results"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
OUT="$RESULTS_ROOT/$STAMP-$MODE"

case "$MODE" in
  quick|full|sweep|n50k) ;;
  *) echo "usage: $0 [quick|full|sweep|n50k] [--force]" >&2; exit 1 ;;
esac

mkdir -p "$OUT"

# --- conditions protocol: quiet-machine gate --------------------------------
LOADAVG=$(awk '{print $1}' /proc/loadavg)
LOAD_OK=$(awk -v l="$LOADAVG" -v m="$MAX_LOADAVG" 'BEGIN {print (l < m) ? "yes" : "no"}')
if [ "$LOAD_OK" != "yes" ] && [ "$FORCE" != "--force" ]; then
  echo "REFUSING: loadavg $LOADAVG >= $MAX_LOADAVG (BENCH_MAX_LOADAVG). Wait for a quiet machine or pass --force." >&2
  exit 2
fi

# --- conditions protocol: manifest -------------------------------------------
CPU_MODEL=$(grep -m1 "model name" /proc/cpuinfo | cut -d: -f2 | sed 's/^ *//' || echo "unknown")
CPU_CORES=$(grep -m1 "siblings" /proc/cpuinfo | awk '{print $4}' || echo "?")
RAM_GB=$(awk '/MemTotal/ {printf "%.0f", $2/1024/1024}' /proc/meminfo || echo "?")
REPO_FS=$(df -T "$ROOT" 2>/dev/null | awk 'NR==2 {print $2}' || echo "?")
ROOT_DISK=$(findmnt -no SOURCE "$ROOT" 2>/dev/null | sed 's/[0-9]*$//' | sed 's|/dev/||' || echo "?")
DISK_KIND=$( [ -f "/sys/block/$(basename $ROOT_DISK 2>/dev/null)/queue/rotational" ] && cat "/sys/block/$(basename $ROOT_DISK 2>/dev/null)/queue/rotational" 2>/dev/null | sed 's/0/non-rotational (SSD\/NVMe)/;s/1/rotational (HDD)/' || echo "?")
GIT_REV=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_DIRTY=$(git -C "$ROOT" status --porcelain 2>/dev/null | wc -l)

cat > "$OUT/manifest.json" << EOF
{
  "mode": "$MODE",
  "dateUtc": "$STAMP",
  "gitRev": "$GIT_REV",
  "gitDirtyFiles": $GIT_DIRTY,
  "jdk": "${JDK_VERSION//\"/\\\"}",
  "cpuModel": "${CPU_MODEL//\"/\\\"}",
  "cpuCores": "$CPU_CORES",
  "ramGB": "$RAM_GB",
  "rootFilesystem": "$REPO_FS",
  "rootDiskKind": "$DISK_KIND",
  "loadavg1m": $LOADAVG,
  "maxLoadavg": $MAX_LOADAVG,
  "loadGateForced": $([ "$FORCE" = "--force" ] && echo true || echo false),
  "loggingConfig": "docs/perf/benchmark-log4j2.xml (WARN console, no hot-path file I/O)",
  "format": "YAML",
  "configFlags": {
    "journalWrites": "default true (writeDataHeavy A/B overrides via @Param)",
    "indexJournalWrites": "default true (W-4a: the hash id-index commit journal)",
    "durableWrites": "default false (writeDurable A/B overrides via @Param)",
    "fragmentSize": 25,
    "note": "canonical CORE scenarios are fragment-heavy by design (disclosed)"
  }
}
EOF
echo "manifest: $OUT/manifest.json (loadavg $LOADAVG, rev $GIT_REV)"

mvn -q -B test-compile -f "$ROOT/pom.xml"

LOG_CONF="file:$ROOT/docs/perf/benchmark-log4j2.xml"
CP="$ROOT/target/test-classes:$ROOT/target/classes:$(mvn -B -q dependency:build-classpath -f "$ROOT/pom.xml" -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=test 2>/dev/null)"

case "$MODE" in
  quick|full)
    if [ "$MODE" = "quick" ]; then
      JMH_ARGS=( -f 1 -wi 1 -w 1s -i 1 -r 1s )
    else
      JMH_ARGS=( )   # full: each class's own annotations (canonical defaults)
    fi
    # the unified JMH set: the canonical CORE scenarios + the search-engine scenario family
    java -Dlog4j.configurationFile="$LOG_CONF" -Xmx2g -cp "$CP" org.openjdk.jmh.Main \
      "XdStorageCanonicalBenchmark.*" "XdStorageSearchEngineBenchmark.*" \
      "${JMH_ARGS[@]}" -rf json -rff "$OUT/jmh-$MODE.json" \
      > "$OUT/jmh-$MODE.log" 2>&1 || { tail -40 "$OUT/jmh-$MODE.log"; exit 1; }
    grep -E "^XdStorage" "$OUT/jmh-$MODE.log" | tail -40 || true
    echo "results: $OUT/jmh-$MODE.json"
    echo "render the baseline rows: python3 scripts/render-bench-baseline.py $OUT/jmh-$MODE.json"
    ;;

  sweep)
    java -Dlog4j.configurationFile="$LOG_CONF" -Xmx2g -cp "$CP" \
      io.github.xdstorage.performance.sweep.XdStorageConfigSweep \
      > "$OUT/sweep.log" 2>&1 || { tail -40 "$OUT/sweep.log"; exit 1; }
    if [ -d "$ROOT/target/sweep" ]; then
      cp "$ROOT"/target/sweep/* "$OUT/" 2>/dev/null || true
    fi
    echo "results: $OUT (sweep json+csv)"
    ;;

  n50k)
    # N50K (docs/tasks/2.1.0/20): the design-point probe — headline scenarios + heap-after-load
    # at N=5k vs N=50k. Probe-mode methodology (plain-Java single run, sweep-harness family):
    # heap MXBean snapshots need exact phase control, not JMH error bars. Each scenario is
    # budget-capped (120s) INSIDE the probe; a budget overrun is recorded as the finding.
    # Separate from quick/full — the <=15min quick budget is unaffected (task 20, acceptance).
    java -Dlog4j.configurationFile="$LOG_CONF" -Xmx2g -cp "$CP" \
      io.github.xdstorage.performance.XdStorageDesignPointProbe "$OUT" \
      > "$OUT/design-point.log" 2>&1 || { tail -40 "$OUT/design-point.log"; exit 1; }
    grep -E "^\[design-point\]" "$OUT/design-point.log" | tail -30 || true
    echo "results: $OUT/design-point.json"
    echo "render: python3 scripts/render-bench-baseline.py $OUT/design-point.json"
    ;;
esac

echo "done: $OUT"
