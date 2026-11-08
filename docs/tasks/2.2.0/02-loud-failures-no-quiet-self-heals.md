---
title: "[2.2.0][LOUD] Replace quiet self-heals with loud failures — the silent-recovery inventory"
labels: [2.2.0-train, P0, engine, tech-debt]
status: open
---

## Summary

Inventory every silent-recovery/self-heal path in the engine and decide per site: keep
(documented + counted) or make LOUD (throw). The known silent paths: the R-40 structural
ghost re-materialization, the R-53 stub restoration and canonical adoption WARNs, the W-1
torn-journal-tail discard, the search-manager null fallbacks ("do not skip object doesn't
exist" — the discovery's explicit ask), the index-cut tolerance. Each site gets: what it
recovers, what damage it can hide, and the decision.

## Why (discovery § First priority 6 + § Consistency 1)

Quiet self-heals trade operator visibility for uptime — the project already drew this line
twice (the S-1 sentinel is loud BY DESIGN; task 23's matrix asserts "never a silently
partial open"). The inventory makes the remaining quiet paths explicit and deliberately
chosen instead of accidental.

## Scope

1. The inventory (grep-driven, per package): site, trigger, current behavior, damage
   class, decision (LOUD / KEEP+count / KEEP+WARN).
2. Implement the LOUD decisions as exceptions at the recovery point; the KEEP decisions
   get metrics counters (a silent recovery that cannot be seen cannot be tuned).
3. The index-cut case specifically: a cut index must throw (loud) — never silently
   self-heal a structure whose damage the operator needs to know about.
4. Tests: each LOUD site pinned; each KEEP site pins its counter.

Estimate: ~2–3 sessions.
