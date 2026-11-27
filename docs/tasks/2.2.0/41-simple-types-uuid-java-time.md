---
title: "[2.2.0][SIMPLETYPES] UUID and java.time fields are not simple types: they persist as child references and degrade"
labels: [2.2.0-train, P2, engine, feature]
status: done (UUID + Instant, LocalDate, LocalDateTime, Duration are simple types — stored, round-tripped and indexed by value; the field-type tables are written; the extension-surface answer is "no supported way")
---

## Summary

`XdStorageObjectUtils.isSimpleType` accepts primitives, Number, Boolean, Character,
String (plus Class/Date/enum at the helper level). `UUID` and every `java.time.*` type
fall OUTSIDE — a field of these types is not a simple value, so the engine treats it
as a reference/child object; persistence degrades (the evaluation: such fields are
"not supported", and the test corpus contains none). The irony: `UUID` HAS a public
`UUID(String)` constructor, so the helper's string-ctor fallback would handle it —
but the fallback is unreachable because `isSimpleType` gates earlier.

**What the degradation was, measured 2026-11-27 against the 2.1.x engine:** a record
with a `UUID`, `Instant` or `Duration` field was saved **without the field** — no
error, the value gone — and a search index over such a field wrote a tree that
could not be read back, by the engine that wrote it (`invalid state of database`:
`InstantiationException: java.time.Instant`).

## Why

- UUID keys and java.time stamps are the default vocabulary of modern Java models;
  every evaluator (this one included) trips on them in the first hour.
- The fix is bounded: the round trip is toString/parse for the whole set; the user
  extension point (IXdStorageSimpleTypeHelper) may already allow app-level additions —
  if so, the task documents it AND still ships the built-ins (the first-hour experience
  must not require reading the SPI docs).

## Scope

1. Verify the extension surface: can an application register its own simple types
   today? The answer recorded (the GUIDE section if yes).
2. The built-ins: `UUID` + the java.time set (`Instant`, `LocalDate`, `LocalDateTime`,
   `Duration` — the exact set decided in the design half), stored as their canonical
   string forms (ISO-8601), round-tripped via parse.
3. The pins: the round-trip properties ride the PROPTEST estate (the seed model gains
   the field types); the reopen case included.
4. The docs: the GUIDE simple-types table + the EMBEDDING type matrix.

## Out of scope

Arbitrary-ctor type registration policy (the SPI design if the extension point needs
rework — spun from this task's verification result); BigDecimal beyond what Number
already covers.

## Acceptance criteria

- [x] The extension-surface answer recorded in the task — **no, an application cannot
      register a simple type.** The predicate the engine uses is fixed
      (`XdStorageObjectUtils.isSimpleType`, called directly by the wrapper code generator
      and the cloner), the helper that converts a value to text and back is
      engine-internal (`IXdStorageSimpleTypeHelper`), and the factory that would carry a
      custom helper is chosen by the `FilesFormat` enum, not by the caller. Adding a type
      is an engine change in two files. (The 2026-11-25 note said a custom helper could be
      "provided via `XdStorageServicesLocator`" — it cannot, not without engine changes.)
- [x] UUID + the decided java.time set round-trip (the properties, the reopen case) —
      `PropModel` carries the fields, so `PropCrudRoundTripTest` and the rest of the
      property estate exercise them; `PropSimpleTypesRoundTripTest` (2) pins all three
      policy arms, the reopen and nulls; `XdStorageSimpleTypesIndexedQueryTest` (3) pins
      that an index over `Instant`, `Duration` and `UUID` compares by value — the cases
      where the text order gives a different answer — in the writing session and after a
      reopen. All 3 indexed-query tests are red on the 2.1.x engine.
- [x] The GUIDE/EMBEDDING tables updated — they did not exist: GUIDE § Field types (what
      a field may hold, the simple types and their text form, what is not simple, the
      pre-2.2.0 note, the extension answer) and EMBEDDING § Field types and their stored
      form (the same list as it meets the disk and an upgrade).
- [x] The property estate + the quick-gate ×3 green — `clean test -Pquick-gate` **1746/0f/0e/7 skipped ×3, consecutive, BUILD SUCCESS**, 0 checkstyle violations, on a quiet box (2026-11-27, branch `parallel-work` — the six parallel-work branches merged; `main` is 1360).

## Design decisions

| Decision | Rationale |
|----------|----------|
| `Duration` included | `Duration.parse(CharSequence)` exists; the round trip is `toString()` → `parse()` for all four java.time types |
| No `OffsetDateTime` / `OffsetTime` / `ZonedDateTime` / `LocalTime` | the set the task names; GUIDE lists them as not simple |
| Stored as `toString()` | `Instant` → ISO-8601 in UTC (the fraction only when non-zero); `Duration` → ISO-8601 (`PT1H30M`); `UUID` → the canonical form |
| Both predicates extended | `XdStorageObjectUtils.isSimpleType` decides for the code generator and the cloner, the helper for the codecs — one without the other leaves the field behind |

### Index keys keep the field's type

The search index stores its keys in their text form and restores them to the declared
field's type at reopen (BUG-E, 2.1.0/18). That restore now covers the five types, so a
range over an indexed `Instant` follows time even when a fraction of a second makes the
text order differ (`…01.250Z` sorts before `…01Z` as text), and a range over `Duration`
follows length (`PT10M` sorts after `PT1M30S` as text). Pinned in both sessions.

## Folders written before 2.2.0

The values were never written — nothing to convert. The fields read `null`, as before.
An index over one of the five in an old folder is unreadable before and after the
upgrade; with the storage closed, remove the index's two tree files
(`indexes/XdStorageBTree/XdStorageBTree-<Class>-<index>.yaml`, `…-key_<index>.yaml`) and
the engine answers from an empty index. `executeStructureUpdate` does not repair it (it
finds nothing to update — checked).

## What changed on 2026-11-27

- The task's acceptance criteria were restored to their wording on `main` and are ticked
  against evidence. The 2026-11-25 version had rewritten two of them ("in the task
  markdown"; "×3" dropped) and carried a stale "No commit — task is in progress" heading.
- The round-trip test's entity is a top-level class (`PropSimpleTypesEntity`); it was a
  nested class of the test, against the rule every entity must follow.
- `XdStorageSimpleTypesIndexedQueryTest` added (3 tests) with three top-level entities.
- The docs and the CHANGELOG entry — there was none — are written.
- The helper's unused import is gone.

## References

- The external evaluation 2026-11-23, §3.3.8 (verified against main: the
  `isSimpleType` whitelist; the unreachable ctor fallback at
  `XdStorageDefaultSimpleTypeHelper.java:82–88`)
- The PROPTEST estate (the seed-model pattern the pins follow)
- [GUIDE.md](../../GUIDE.md) § Field types, [EMBEDDING.md](../../EMBEDDING.md) § Field
  types and their stored form
