#!/usr/bin/env python3
# FKBENCH (docs/tasks/2.1.0/50): renders the FK A/B table from the JMH result JSON
# (the same generate-don't-hand-type discipline as render-bench-baseline.py).
# Usage: python3 scripts/render-fk-bench-table.py <jmh.json> [<delete-jmh.json>]
import json
import sys

rows = {}
for path in sys.argv[1:]:
    data = json.load(open(path))
    for r in data:
        method = r["benchmark"].split(".")[-1]
        p = r["params"]
        arm = "plain" if method.startswith("plain") else ("fk_on" if p.get("fkEnabled") == "true" else "fk_off")
        key = (method, arm, int(p.get("childrenPerParent")), int(p.get("parents")))
        rows[key] = (r["primaryMetric"]["score"], r["primaryMetric"]["scoreError"] or 0.0)

def get(method, arm, ch, pa):
    return rows.get((method, arm, ch, pa))

def fmt(v):
    if v is None:
        return "—"
    return f"{v:,.1f}"

def ratio(on, off):
    if not on or not off:
        return "—"
    return f"{off / on:.2f}×"

configs = [(1, 1000), (10, 1000), (1, 10000), (10, 10000)]

print("### The A/B per shape (ops/s; higher is better)")
print()
print("| Shape | children | parents (relations) | plain (no annotations) | fk OFF | fk ON | FK degradation |")
print("|---|---|---|---|---|---|---|")
for ch, pa in configs:
    rel = f"{ch * pa // 1000}k"
    cs_p = get("plainCascadeSave", "plain", ch, pa)
    cs_off = get("cascadeSave", "fk_off", ch, pa)
    cs_on = get("cascadeSave", "fk_on", ch, pa)
    print(f"| cascadeSave | {ch} | {pa} ({rel} rel) | {fmt(cs_p[0]) if cs_p else '—'} | {fmt(cs_off[0]) if cs_off else '—'} | {fmt(cs_on[0]) if cs_on else '—'} | {ratio(cs_on[0] if cs_on else None, cs_off[0] if cs_off else None)} |")
for ch, pa in configs:
    rel = f"{ch * pa // 1000}k"
    d_off = get("childDelete", "fk_off", ch, pa)
    d_on = get("childDelete", "fk_on", ch, pa)
    dp = get("plainChildDelete", "plain", ch, pa)
    print(f"| childDelete (the contract cycle) | {ch} | {pa} ({rel} rel) | {fmt(dp[0]) if dp else '—'} | {fmt(d_off[0]) if d_off else '—'} | {fmt(d_on[0]) if d_on else '—'} | {ratio(d_on[0] if d_on else None, d_off[0] if d_off else None)} |")
for ch, pa in configs:
    rel = f"{ch * pa // 1000}k"
    u_off = get("parentUpdate", "fk_off", ch, pa)
    u_on = get("parentUpdate", "fk_on", ch, pa)
    up = get("plainParentUpdate", "plain", ch, pa)
    print(f"| parentUpdate | {ch} | {pa} ({rel} rel) | {fmt(up[0]) if up else '—'} | {fmt(u_off[0]) if u_off else '—'} | {fmt(u_on[0]) if u_on else '—'} | {ratio(u_on[0] if u_on else None, u_off[0] if u_off else None)} |")
for ch, pa in configs:
    rel = f"{ch * pa // 1000}k"
    l_off = get("fkLookup", "fk_off", ch, pa)
    l_on = get("fkLookup", "fk_on", ch, pa)
    lp = get("plainLookup", "plain", ch, pa)
    print(f"| fkLookup (child→parent read) | {ch} | {pa} ({rel} rel) | {fmt(lp[0]) if lp else '—'} | {fmt(l_off[0]) if l_off else '—'} | {fmt(l_on[0]) if l_on else '—'} | {ratio(l_on[0] if l_on else None, l_off[0] if l_off else None)} |")
