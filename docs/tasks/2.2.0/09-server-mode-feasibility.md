---
title: "[2.2.0][SERVER] Server mode: feasibility, design, and the positioning decision"
labels: [2.2.0-train, P2, engine, feature]
status: open
---

## Summary

The discovery asks for a server mode ('I need to use it as server - additional option').
This CONTRADICTS the recorded positioning ('no servers' is a headline feature; MASTER-PLAN
§4 lists network/server mode as out of scope) — so this task is the strategic DECISION
task, not an implementation task: a feasibility/design document (transport, wire protocol
vs the facade API, multi-client transactions vs the thread-binding contract, locking, the
security surface, the competition framing vs actual client-server DBs) ending in
go/no-go. Implementation, if GO, is its own train (3.0 candidate).

## Scope

1. The demand analysis: which embedder scenarios need server mode vs the existing
   single-process answer (an app-level thin server over the facade is a day of work —
   what does the ENGINE gain?).
2. The design sketch + the honest competition framing (H2 server mode, PostgreSQL protocol
   compatibility, custom protocol).
3. Go/no-go with the positioning consequences spelled out (the landing page's 'no servers'
   pitch changes).

Estimate: ~1 session (design only).
