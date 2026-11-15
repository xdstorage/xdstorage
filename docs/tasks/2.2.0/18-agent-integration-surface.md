---
title: "[2.2.0][AGENT] Agent-integration surface: the agent memory store positioning, JSON output, read-only inspection"
labels: [2.2.0-train, P2, engine, docs, feature]
status: open
---

## Summary

Codify the strongest new positioning direction from the PRODUCT_REVIEW: **XdStorage as
the embedded memory/state store for AI agents** (PRODUCT_REVIEW §3.3: *"the agent's
native world — a file is the universal interface"*). Ship the small API surface the
review names and write the positioning down: (a) a JSON query-output helper (the search
engine returns POJOs — agents want JSON), (b) the read-only folder-inspection entry point
shared with the tools CLI, (c) the "agent memory store" use-case documented as a first-class
story (GUIDE + landing mention + the decision guide's good-fits list).

## Why

The review's §3 alignment table is the strongest product-market fit statement the project
has: files are the agent's universal interface, YAML is agent-parseable, ACID + triggers
+ FK are the constraints an agent needs, `cp -r` is the backup, `verify` is the checksum
— and no alternative in the class combines them. The gap table (§3.2) is small and
shippable: a JSON serialization of query results, a folder-inspection entry point that
does not require the engine's lifecycle, and the documented story.

## Scope

1. **JSON output helper**: `XdStorageJson.toJson(Collection<T>)` / `fromJson(...)` over
   the search/load results (a thin, dependency-free JSON writer/reader over the existing
   simple-type model — no new dependencies; the entity fields are simple types +
   nested POJOs the model already serializes). The agent's tool reads the JSON, writes
   YAML-backed state, and the engine's ACID/MVCC guarantees hold underneath.
2. **Read-only inspection**: the static folder-reader entry point (shared with task 16's
   `inspect` core): counts, format marker, per-class entry lists — WITHOUT opening the
   engine (no locks taken, no writes) — so an agent can survey a folder from another
   process or a snapshot.
3. **The documented story**: GUIDE § "XdStorage as an agent memory store" (the use case:
   agent working memory / tool history / conversation state — all ACID, all queryable,
   `cp -r`-backupable); DECISION-GUIDE § good-fits gains the agent-workspace row; the
   landing page's capability list gains the line.
4. The `explain()` + `verify()` surfaces documented from the agent's perspective (the
   review §3.1 rows — the agent chooses plans and checks integrity).

## Out of scope

An MCP server, a network protocol, a vector store, embeddings — the review's §3.3 is
explicit: NOT a replacement for relational stores or vector DBs; the SQL layer stays out
of scope (MASTER-PLAN §4 — the criteria engine is the query surface).

## Acceptance criteria

- [ ] JSON round-trip: search/load results → JSON → parse → equals; the simple-type +
      nested-POJO model covered (pinned tests).
- [ ] The inspection entry point answers without opening the engine (pinned test on a
      snapshot copy).
- [ ] GUIDE + landing + DECISION-GUIDE carry the agent-memory-store story with the
      honest boundaries (§3.3's "not a vector store" line included).
- [ ] Full suite green ×2; zero new dependencies.

Estimate: ~1–2 sessions (the helper is thin; the docs are the point).

## References

- PRODUCT_REVIEW.md §3 (the alignment table + the agent gaps), §5.1 (the good-fits rows)
- MASTER-PLAN Step 4 (where this positioning item lands), task 26 (verify — the shared core)
