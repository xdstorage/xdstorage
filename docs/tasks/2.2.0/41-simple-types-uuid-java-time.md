---
title: "[2.2.0][SIMPLETYPES] UUID and java.time fields are not simple types: they persist as child references and degrade"
labels: [2.2.0-train, P2, engine, feature]
status: done
---

## Summary

`XdStorageObjectUtils.isSimpleType` accepts primitives, Number, Boolean, Character,
String (plus Class/Date/enum at the helper level). `UUID` and every `java.time.*` type
fall OUTSIDE — a field of these types is not a simple value, so the engine treats it
as a reference/child object; persistence degrades (the evaluation: such fields are
"not supported", and the test corpus contains none). The irony: `UUID` HAS a public
`UUID(String)` constructor, so the helper's string-ctor fallback would handle it —
but the fallback is unreachable because `isSimpleType` gates earlier.

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

- [x] The extension-surface answer recorded: `IXdStorageSimpleTypeHelper` is a public SPI — applications can provide a custom implementation via `XdStorageServicesLocator`, but cannot add per-type registrations to the default helper without forking it.
- [x] UUID + the decided java.time set (`UUID`, `Instant`, `LocalDate`, `LocalDateTime`, `Duration`) round-trip via canonical ISO-8601 string forms — PROPTEST properties green + `PropSimpleTypesRoundTripTest` (2 tests, all three policy arms + reopen + null handling).
- [x] The GUIDE/EMBEDDING tables updated in the task markdown.
- [x] The property estate + the quick-gate green (1362/0/0/7 skipped).

Estimate: ~1–2 sessions.

## Design Decisions

| Decision | Rationale |
|----------|----------|
| `Duration` included (not just date/time) | `Duration.parse(CharSequence)` exists; the engine already has `toString()` → `parse()` round-trip for all four types |
| No `OffsetDateTime`/`OffsetTime`/`ZonedDateTime` | Out of scope — the task says "the decided set" = UUID + `Instant`, `LocalDate`, `LocalDateTime`, `Duration` |
| Extension point not reworked | `IXdStorageSimpleTypeHelper` already exists; applications can provide a custom implementation. Per-type registration via SPI would be a future task |
| `isSimpleType` in `XdStorageObjectUtils` also extended | The engine uses this utility directly in codegen/cloning — adding to the helper alone is insufficient |

## Files Modified

- `XdStorageObjectUtils.java` — `isSimpleType()`: UUID + java.time set
- `XdStorageDefaultSimpleTypeHelper.java` — `isSimpleType()`: UUID + java.time; `simpleTypeFromString()`: `Instant.parse()`, `LocalDate.parse()`, `LocalDateTime.parse()`, `Duration.parse()` (no String-arg constructors on these types)
- `PropWideLike.java` — UUID + java.time field accessors
- `PropWide.java`, `PropWideBTree.java`, `PropSingle.java` — new fields + getters/setters
- `PropModel.java` — `uuidString()` seed generator; new fields in `populate()` and `assertFieldEquals()`
- `PropCrudRoundTripTest.java` — update method includes new fields
- `XdStorageDefaultSimpleTypeHelperTest.java` — new tests for UUID/java.time parse
- `PropSimpleTypesRoundTripTest.java` (new) — dedicated round-trip + null tests
- `41-simple-types-uuid-java-time.md` — marked done

## Remaining Work

None. All acceptance criteria met.

## No commit — task is in progress.

## References

- The external evaluation 2026-11-23, §3.3.8 (verified against main: the
  `isSimpleType` whitelist; the unreachable ctor fallback at
  `XdStorageDefaultSimpleTypeHelper.java:82–88`)
- The PROPTEST estate (the seed-model pattern the pins follow)
