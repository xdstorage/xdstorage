---
title: "[2.2.0][SIMPLETYPES] UUID and java.time fields are not simple types: they persist as child references and degrade"
labels: [2.2.0-train, P2, engine, feature]
status: open
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

- [ ] The extension-surface answer recorded in the task.
- [ ] UUID + the decided java.time set round-trip (the properties, the reopen case).
- [ ] The GUIDE/EMBEDDING tables updated.
- [ ] The property estate + the quick-gate ×3 green.

Estimate: ~1–2 sessions.

## References

- The external evaluation 2026-11-23, §3.3.8 (verified against main: the
  `isSimpleType` whitelist; the unreachable ctor fallback at
  `XdStorageDefaultSimpleTypeHelper.java:82–88`)
- The PROPTEST estate (the seed-model pattern the pins follow)
