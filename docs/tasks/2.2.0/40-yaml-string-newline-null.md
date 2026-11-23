---
title: "[2.2.0][YAMLSTR] Two silent string-corruption cases in the YAML block format: a newline in a string corrupts the record; the literal value \"null\" reads back as null"
labels: [2.2.0-train, P1, engine, bug]
status: done (the line-safe string form + the literal-null reader fix; 340 pins and the widened property model; quick-gate 1731/0/0/7 x3; the 2.1.x cherry-pick decision stays with the maintainer)
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

- [x] The two decisions recorded (escape vs loud, each with the rejected alternative).
- [x] The round-trip repros red-before / green-after (newline + "null").
- [x] The widened PropModel green ×3 (the avoidance javadoc removed): `Prop*Test`
      **13/0f/0e ×3** (PropCrudRoundTripTest is the class that consumes the model).
- [x] The docs note the compatibility rule for pre-2.2.0 folders.
- [x] The quick-gate ×3 green: **1731/0f/0e/7 skipped ×3, consecutive, BUILD SUCCESS**, on a
      quiet box (1391 + the 340 pins of this task).

Estimate: ~1–2 sessions.

## LANDED (2026-11-24) — the two decisions, the blast radius as measured, the fix

### Decision 1 — the line break: a second scalar form, not a change of the existing one

A string WITHOUT a line break keeps the original form (single quotes, `''` for an
apostrophe) — byte for byte what every version wrote. A string WITH a line break takes a
line-safe form: double quotes with the escapes `\\`, `\"`, `\n`, `\r` — one physical line.

The reader tells the forms apart by the first character, and that is unambiguous: a value
starts with `"` on disk only in the new form (strings were always single-quoted; unquoted
scalars are numbers, class names and the `null`/`~` markers), and a single-quoted string
starts with `'` whatever it contains.

Rejected alternatives:

- **The loud save-time rejection** (the task's other option). A line break is ordinary data
  (a description, an address); refusing it keeps the hole open and pushes an encoding scheme
  on every user. Rejection stays the right answer for what the form cannot carry at all —
  which is why an unknown escape or an unterminated double-quoted value IS refused.
- **Escapes inside the existing single-quoted form.** Not backward-safe: a backslash in an
  old file (`'C:\new\net'`, a regex, a JSON snippet) would start to mean a line break the
  moment the folder is opened by the new version — a silent corruption of old data.
- **A real multi-line scalar** (a quoted value spanning physical lines). It breaks the
  one-line-per-token invariant the tokenizer, the strict EOF check and the sentinel logic
  rest on, and an older reader facing it mis-parses every continuation line.

Compatibility (the C5 section): a folder written by ≤2.1.x reads identically — the reader's
new branch cannot fire on it; pinned by ten hand-written legacy documents. The opposite
direction has one rule: a 2.1.x reader on a 2.2.0 folder that contains such a string returns
that value mangled (quotes and escapes as literal text), everything else intact; the marker
stays `formatVersion=3`, so 2.1.x does not refuse on its own — EMBEDDING § On-disk format
compatibility states the rule. No migration exists or is needed.

### Decision 2 — the literal `null`: the reader was wrong, the file was right

The tokenizer already distinguishes the two on disk — an unquoted `null`/`~` becomes a null
token, a quoted `'null'` becomes the four-character string. Three readers then added
`!"null".equals(token.value)` and turned that string into a null. The check is gone
(`parseAndSetField`, `readObjectData`, `readTypedNode`). Rejected: a write-side escape for the
literal — the quoted form is already unambiguous, and an escape would change the bytes of an
existing legal value. Side effect, in the user's favour: a ≤2.1.x file that carries
`value: 'null'` now reads as the string its writer meant.

### The blast radius, as PROVEN on the unfixed code

- **The line break did not shift a few fields — it took the whole record file down.** After a
  save with a multi-line text and a reopen, the engine refused the file:
  `resource file is TRUNCATED or corrupted: no EOF sentinel after 1 record(s) in 'objects'
  block — refusing to serve a partial database` — every record of that fragment unreadable
  until repaired. A `char` holding `\n` or `\r` read back as `'`; a list lost the elements
  after the break.
- **`null`:** a field became null; a list element became null; a map value became null; a map
  KEY took its whole entry with it.
- The red-before record: `XdStorageYamlStringScalarRoundTripTest` 267 tests / 61 failures
  (rows `null` and every line-break row, in all four positions), `XdStorageMultiLineStringReopenTest`
  5 tests / 4 red; the widened property model failed on its first tries (original sample
  seed −46, jqwik shrunk it to seed 0, arm `class-hash`).

### The fix

- `XdStorageYamlScalar` (new) — the one quoting rule: `token(String)` picks the form;
  `unescapeDoubleQuoted` reads the line-safe form back and refuses anything the engine never
  writes (an unknown escape, a lone trailing backslash).
- The tokenizer: a value starting with `"` is read through the codec; an unterminated one is
  refused. The search-index journal's private tokenizer got the same branch.
- Every writer of user data goes through `token(...)`: field values, list/map/array cells,
  enum values, object ids, the commit journal's record ids and bodies, the search-index
  journal's keys.
- A broken line-safe scalar in the BASE file fails the load loudly (pinned).

### The pins

`XdStorageYamlStringScalarRoundTripTest` (267: a 50-string corpus × field / list element /
map key+value / array element, chars, the one-line-per-scalar invariant, the stored-null
neighbours, the commit-journal replay, ten legacy documents), `XdStorageYamlScalarTest` (the
byte forms, both forms through the tokenizer, the loud refusals),
`XdStorageMultiLineStringReopenTest` (a real storage: the shutdown checkpoint + reopen, the
journal bytes the engine appended, `verify()`, the loud corrupt-escape refusal, an update),
`XdStorageSearchIndexJournalScalarFormTest`, and `PropModel` widened: line breaks, a tab and
a backslash joined the alphabet, the literal `null` is generated as a value and as a map key.

### Found on the way — recorded, not fixed here

- **`verify()` said OK for a folder whose base file the reader refused as truncated** (the
  old line-break case): the verifier's walk accepted the broken line. A verify-vs-reader gap
  for the verify work (tasks 43 / the FolderVerifier).
- **The search-index journal** carries user data in its keys (indexed values) and now writes
  them through the shared rule. Its READER is another matter — see the note in task 48.
- **The XML format is untouched.** Its values live in attributes; what a raw newline in an
  attribute becomes is the format-level question of the task-42 triage.
- **A trap for the next session:** `String.valueOf(x.get(...))` over the generic
  `<T> T XdStorageObjectField.get(Object)` makes javac pick `String.valueOf(char[])` — the
  call then throws `ClassCastException` on any value but a `char[]`. The property estate
  caught it on its first run (three classes went red); the bytecode check is
  `javap -c | grep String.valueOf`.

### Cost

The codec adds two `indexOf` scans per string on write and one first-character check per
quoted value on read. No benchmark claim is made; the write path itself is untouched.

## References

- The external evaluation 2026-11-23, §3.3.6 (both defects; verified against main)
- `XdStorageYamlBlockObjectsWriter.java:231–241` (encode), `XdStorageYamlBlockObjectsReader.java:137` (null)
- `src/main/test/io/github/xdstorage/properties/PropModel.java:17–21` (the disclosed avoidance)
- Task 02 loud-failures (the loud-rejection posture for the save-time option)
