---
title: "[2.2.0][WAL] Journal/WAL hardening: transaction identity at EOF, WAL semantics for transactions"
labels: [2.2.0-train, P1, engine, durability]
status: open
---

## Summary

Harden the commit journal toward true WAL semantics: the EOF sentinel line carries the
**last commit serial + transaction id** (so a torn tail's decision — discard vs keep — is
made on durable metadata, not just presence), and the transaction's journal section is
identified per transaction (the discovery's 'WAL for transactions and transaction id at
EOF in journals'). Strengthens torn-tail decisions, cross-storage audit, and the recovery
recipe's precision.

## Scope

1. Sentinel format v3: `# xdstorage-eof serial=<n> tx=<id>` (backward-compatible parsing —
   the plain sentinel still accepted; the enriched form written by default).
2. Torn-tail policy upgrade: with the enriched sentinel, the discard decision can report
   the EXACT lost serial range (better recovery reporting for task 23's recipe).
3. Format-generation note (EMBEDDING compatibility matrix) — additive, no migration.

Estimate: ~1 session.
