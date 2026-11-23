---
title: "[2.2.0][YAMLSTR] Two silent string-corruption cases in the YAML block format: a newline in a string corrupts the record; the literal value \"null\" reads back as null"
labels: [2.2.0-train, P1, engine, bug]
status: open
---

## Summary

Two verified round-trip defects in the block YAML format:

1. **Newline in a string.** `XdStorageYamlBlockObjectsWriter.encode` (lines 238–241)
   escapes ONLY the apostrophe (`'` → `''`). The format is line-based (one token per
   line, the nesting = the tab count); a `\n` inside a string value breaks the record's
   line structure — the remaining fields shift/are lost on read. Any multi-line text
   (descriptions, addresses, serialized snippets) corrupts the folder.
2. **The literal "null".** `XdStorageYamlBlockObjectsReader` line 137:
   `"null".equals(rawScalarValue)` → the field reads back null. A String field whose
   VALUE is the four characters `null` does not round-trip.

Both are silent. The property-test model deliberately avoids both (PropModel.java
17–21, the avoidance is disclosed in its javadoc — "a newline-in-string round trip is a
format-capability question").

## Why

- The data-corruption class: the write succeeds, the read is wrong, nothing throws.
- The avoidance note in PropModel is honest but means the property estate PROVES
  nothing about the most common real-world string content.

## Scope

1. The design round FIRST: (a) the newline decision — an escape scheme additive to the
   line format (e.g. the `\n` → a token-safe escape pair, the apostrophe rule stays)
   OR the loud save-time rejection per task 02; the compatibility matrix for folders
   written by ≤2.1.x (an escape changes the reader; the old folders with ALREADY-
   embedded newlines are corrupt on disk — the verify()/repair story must say so);
   (b) the "null" decision — the same two options (an escape form vs the documented
   limitation + the loud option). One decision per case, recorded with the rejected
   alternatives.
2. The implementation + the PropModel un-avoidance: the seed model lifts the
   single-line restriction (and pins the "null" literal), the round-trip properties
   extended.
3. The migration/verify note: what EMBEDDING/GUIDE tell users about pre-existing
   corrupted records and the verify(folder) behavior on them.
4. The gates: the property estate ×3 (the widened model), the serialization estate,
   the quick-gate ×3; the both-lines decision (a silent-corruption fix — recommend the
   hotfix ride if the escape is chosen, the reader must accept BOTH forms anyway).

## Out of scope

The XML format (its own text-node handling — the task-42 triage covers the format
level); the format swap (a YAML-library migration is nobody's decision today).

## Acceptance criteria

- [ ] The two decisions recorded (escape vs loud, each with the rejected alternative).
- [ ] The round-trip repros red-before / green-after (newline + "null").
- [ ] The widened PropModel green ×3 (the avoidance javadoc removed).
- [ ] The docs note the compatibility rule for pre-2.2.0 folders.
- [ ] The quick-gate ×3 green.

Estimate: ~1–2 sessions.

## References

- The external evaluation 2026-11-23, §3.3.6 (both defects; verified against main)
- `XdStorageYamlBlockObjectsWriter.java:231–241` (encode), `XdStorageYamlBlockObjectsReader.java:137` (null)
- `src/main/test/io/github/xdstorage/properties/PropModel.java:17–21` (the disclosed avoidance)
- Task 02 loud-failures (the loud-rejection posture for the save-time option)
