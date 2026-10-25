---
title: "[2.1.0][YAMLREAD] YAML reader strictness — corruption must fail loudly, never masquerade as empty data"
labels: [2.1.0-train, P2, engine, bug]
status: done
---

## Summary

Four findings of the 28-javadoc-coverage campaign in the serialization readers. The shared
theme: a malformed or unexpected input is silently absorbed instead of failing loudly — exactly
the failure class the S-1 EOF-sentinel work (2.0) was built to eliminate.

1. **`XdStorageDefaultObjectsReader.readReferences`** accepts both `object` and `reference`
   start elements, but its `readElement(reader, field)` overload only processes `reference` —
   an `object` element in a references file silently yields nothing (references vanish, no
   error). Accept one format, refuse the other loudly.
2. **`XdStorageYamlBlockObjectsReader.parseAndSetField`** silently skips `null` values even for
   known properties (the field keeps its default instead of being assigned null — a loaded
   record can differ from the stored one). ⚠ premise corrected during implementation: the
   `idField.set(...)` branch is NOT dead — the id field is not in the properties map, so that
   branch is the legitimate id-handling path; and unknown fields have a PINNED
   ignore-and-continue forward-compat contract
   (`XdStorageYamlBlockObjectsReaderTest.testReadObject_UnknownFieldInMetaModel`) — the loud
   refusal for unknown fields was attempted and rejected by the estate the same day. Only the
   honest null assignment is the fix.
3. **`XdStorageYamlBlockObjectsReader.readReference`** returns `null` when the id value is
   absent — callers cannot distinguish "no reference" from a corrupt block (corruption
   masking).
4. **`XdStorageIOFactory.instanceIOFactory`** NPEs at the `switch` on a `null` format — guard
   with an actionable message (`FilesFormat` values only).

## Scope

- Per case: refuse loudly (`XdStorageIOException`/`XdStorageRuntimeException` with the file
  name and the offending element/field) or assign the value honestly — pick per the reader's
  contract, then pin it with a corruption-detection test (truncated field line, `object` in a
  references file, reference block without an id, null-valued property round-trip).
- The null-property question needs one decision recorded in the task: is `null` a legal stored
  value for a known property (then assign it) or not (then refuse)? Round-trip test either way.

## Out of scope

- `IXdStorageObjectsReader.read(Reader, tx)`'s unused transaction parameter (carried for cache
  identity per the in-code comment; documented in 28);
- `appendCompensation` writing serial `0` (by design; documented).

## Landed (2026-10-25) + the recorded null-property decision

**The decision: a stored `null` IS a legal value for a known property — the reader assigns
it honestly.** The writer serializes null fields as `value:` with no content; the loaded
record must reproduce the stored state, not the field's construction default. Round-trip
pinned by `storedNullProperty_LoadsAsNull_NotAsConstructionDefault` (the fixture's
construction default `"CONSTRUCTION-DEFAULT"` vs the stored null — red-before proven by
temporarily restoring the skip).

Landed in `XdStorageYamlBlockObjectsReader`/`XdStorageDefaultObjectsReader`/
`XdStorageIOFactory`:
- `parseAndSetField` assigns the null honestly for known properties (the unknown-field
  ignore contract and the id branch are unchanged — see the corrected premise above);
- `readReferences`/`read` refuse wrong-kind elements (`object` in a references file,
  `reference` in a data file) with `XdStorageIOException`;
- `readReference` refuses an id-less/class-less reference block (corruption, not
  "no reference" — safe because the writer never writes null references);
- `instanceIOFactory(null)` refuses with an actionable `FilesFormat` message.

All five pinned by `XdStorageReaderStrictnessTest` (the null-assignment test red-before
proven the same way). The resource routing (`XdStorageResource.readAsObjects`) is
kind-separated (`isReferences`), so the wrong-kind refusals cannot fire on well-formed
storages.

## Acceptance criteria

- [ ] A corruption test per case, red on the old reader, green after.
- [ ] The null-property decision recorded; the round-trip test pins it.
- [ ] Full suite green ×2.

Estimate: 1–1.5 d.

## References

- [28-javadoc-coverage](28-javadoc-coverage.md) — the campaign that surfaced all four
- `serialization/XdStorageDefaultObjectsReader.java`,
  `serialization/yaml/XdStorageYamlBlockObjectsReader.java`, `serialization/XdStorageIOFactory.java`
