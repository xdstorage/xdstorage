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

## Scope amendment (2026-11-23, the external-evaluation intake §3.3.11 — verified against main)

The replay's catch sits around the WHOLE read loop
(`XdStorageYamlCommitJournal.readForReplay`, lines ~572–599): a parse error MID-FILE —
not a physical torn tail — silently truncates the replay at the last good sentinel.
Valid records after the corrupt one are dropped and never counted
(`discardedTailRecords` counts only the pending prefix; everything unread past the
error is invisible). A physical truncation and a mid-file corruption are different
events with different correct responses. This task owns the distinction:

- a PHYSICAL truncation (the crash mid-append) = the torn-tail discard — unchanged;
- a MID-FILE parse corruption = LOUD per task 02 (or the quarantine+count decision,
  recorded with its reason) — never the silent torn-tail equivalence;
- the counter must cover the UNREAD REMAINDER, not just the pending list;
- the sentinel v3 (scope item 1) is the mechanism: with serial+tx at EOF the two cases
  are distinguishable on durable metadata.
