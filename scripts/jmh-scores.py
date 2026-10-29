#!/usr/bin/env python3
"""D-4 (docs/tasks/2.1.0/11): the nightly JMH regression alert's two halves.

extract  — JMH JSON result file (scripts/run-benchmarks.sh quick output) → the dated
           scores artifact: {scenario: {score, error, unit, params}}.

compare  — previous scores artifact vs the new one → a delta table for the GitHub job
           summary + `::warning::` annotations for any scenario >25% SLOWER (D-4's
           threshold). Detection, not gating: quick-mode single-iteration numbers on
           shared runners are noisy — the alert means "re-measure on a quiet machine",
           never "the build is bad". Scenario keys include their @Param dimensions, so
           A/B families (journalWrites=true vs false) compare like-for-like.

Usage:
  python3 scripts/jmh-scores.py extract <jmh-results.json> <scores-out.json>
  python3 scripts/jmh-scores.py compare <prev-scores.json> <new-scores.json> [--threshold 25]
"""
import json
import sys

THRESHOLD_PERCENT = 25.0


def key_of(bench):
    """Scenario key: simple class + method + sorted params (A/B families stay comparable)."""
    parts = bench["benchmark"].split(".")
    params = bench.get("params", {})
    ptxt = ",".join(f"{k}={v}" for k, v in sorted(params.items()))
    return parts[-2] + "." + parts[-1] + ("(" + ptxt + ")" if ptxt else "")


def extract(jmh_json_path, out_path):
    with open(jmh_json_path) as f:
        data = json.load(f)
    scores = {}
    for bench in data:
        p = bench["primaryMetric"]

        # Безопасное извлечение и обработка погрешности
        raw_error = p.get("scoreError", 0.0)
        if isinstance(raw_error, str) or raw_error is None:
            score_error = 0.0
        else:
            score_error = round(raw_error, 3)

        scores[key_of(bench)] = {
            "score": round(p["score"], 3),
            "error": score_error,
            "unit": p.get("scoreUnit", "ops/s"),
            "params": bench.get("params", {}),
        }
    with open(out_path, "w") as f:
        json.dump(scores, f, indent=2, sort_keys=True)
    print(f"scores artifact: {out_path} ({len(scores)} scenarios)")


def compare(prev_path, new_path, threshold=THRESHOLD_PERCENT):
    with open(prev_path) as f:
        prev = json.load(f)
    with open(new_path) as f:
        new = json.load(f)

    lines = ["### JMH regression watch (vs the previous nightly)", "",
             "| Scenario | prev ops/s | new ops/s | delta |", "|---|---|---|---|"]
    regressions = []
    for key in sorted(set(prev) | set(new)):
        if key not in prev:
            lines.append(f"| `{key}` | — | {new[key]['score']:,.0f} | new scenario |")
            continue
        if key not in new:
            lines.append(f"| `{key}` | {prev[key]['score']:,.0f} | — | scenario missing in this run |")
            regressions.append((key, "MISSING in this run (a trial failed and JMH skipped it silently)"))
            continue
        old_score, new_score = prev[key]["score"], new[key]["score"]
        if old_score <= 0:
            continue
        delta = (new_score - old_score) / old_score * 100.0
        arrow = "▲" if delta > 0 else ("▼" if delta < 0 else "=")
        lines.append(f"| `{key}` | {old_score:,.0f} | {new_score:,.0f} | {arrow} {delta:+.1f}% |")
        if new_score < old_score * (1 - threshold / 100.0):
            regressions.append((key, f"{delta:+.1f}% (threshold −{threshold:.0f}%)"))

    for key, why in regressions:
        # D-4: detection, not gating — the annotation is a signal to re-measure quietly.
        # stderr: the workflow pipes stdout (the table) into the job summary; the
        # ::warning:: lines flow to the log and become GitHub annotations.
        print(f"::warning::JMH regression alert: `{key}` — {why}. "
              f"Quick-mode numbers on shared runners are noisy: re-measure on a quiet "
              f"machine (scripts/run-benchmarks.sh full) before treating it as real.",
              file=sys.stderr)

    print()
    print("\n".join(lines))
    if not regressions:
        print("\nNo scenario dropped more than the threshold — no alerts.")
    return 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    if sys.argv[1] == "extract":
        extract(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "compare":
        threshold = THRESHOLD_PERCENT
        if len(sys.argv) > 4 and sys.argv[4] == "--threshold":
            threshold = float(sys.argv[5])
        compare(sys.argv[2], sys.argv[3], threshold)
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
