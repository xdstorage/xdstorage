---
title: "[2.2.0][XXE] The XML readers parse DTDs and external entities: disable them (CWE-611)"
labels: [2.2.0-train, P1, security, bug]
status: done (the four parse sites refuse a DOCTYPE loudly and resolve no entity; pinned by XdStorageXmlExternalEntityHardeningTest 12 green; the 2.1.x cherry-pick decision stays with the maintainer)
---

## Summary

All four StAX entry points create the factory bare:

`XdStorageDefaultObjectsReader.java` lines 60, 85, 110, 605 —
`XMLInputFactory.newInstance().createXMLStreamReader(...)` with NO hardening: DTD
support and external entity resolution are left at the JDK defaults. A storage folder
authored by anyone but the opening application can declare a DOCTYPE with external
entities (file:// exfiltration, SSRF, entity-expansion bombs) and the reader honors it.

## Why

- The README actively markets data folders as portable/git-committable artifacts —
  the "open a folder you did not create" scenario is a DESIGNED use, not an exotic one.
- The fix is small, standard, and behavior-preserving for every legitimate document
  (none of the engine's own writers emit a DTD).

## Scope

1. One shared hardened-factory helper (the four sites call it): `SUPPORT_DTD=false`,
   `IS_SUPPORTING_EXTERNAL_ENTITIES=false`, `XMLAccessExternalDTD=forbid` /
   external-general+parameter-entities=false, secure-processing on.
2. The pin: a malicious-DTD storage folder fails LOUDLY with a clear parse error (the
   task-02 posture — no silent stripping); the benign corpus (the engine's own output,
   the test estate) unchanged.
3. The gates: the repro green, the XML-format estate ×3, the quick-gate ×3.
4. The both-lines decision: CVE-class, trivially cherry-pickable — recommend the 2.1.4
   hotfix ride (the fixes ride both lines per the standing directive).

## Out of scope

The YAML format's own surface (task 40); the reflective class-instantiation trust
boundary and the whitelist design (task 37).

## Acceptance criteria

- [x] All four sites go through the hardened factory (grep-proof: no bare
      `XMLInputFactory.newInstance()` left).
- [x] The malicious-DTD repro red-before / loud-fail-after (the pin).
- [x] The XML estate ×3 + the quick-gate ×3 green: the XML estate (`serialization.**.*Test` +
      `XmlDataStorageMultithreadsTest`, `XdStorageW1JournalTest`) **78/0f/0e/1 skipped ×3**; the
      quick-gate (the full 1391) **1379 + 12 = 1391/0f/0e/7 skipped ×3, consecutive, BUILD
      SUCCESS**, on a quiet box.
- [x] The docs (EMBEDDING/GUIDE) carry the trust-boundary note (one paragraph, task 37
      owns the full design).

Estimate: ~0.5–1 session.

## LANDED (2026-11-24) — the hole as it really was, the fix, the pins

**Red-before on the unhardened reader** (`XdStorageXmlExternalEntityHardeningTest`, 11 tests /
10 failures; only the benign-corpus test was green):

- **The exfiltration, end to end.** A document naming a REMOTE DTD: the stock JDK 21 parser
  requested `/evil.dtd` from the test's local HTTP origin, and the DTD's parameter entities
  sent the local secret file back — the test recorded
  `requested: [/evil.dtd?null, /leak?x=XXE-CANARY-…]`.
- **The folder on disk.** A saved XML folder edited to carry a DTD: `storage.load` returned
  `ValueChild{id=1, label='ATTACKER-CONTROLLED-…'}` — a DTD-defined value in place of a stored
  one, no error.
- All four parse sites returned the DTD-defined value; the entity expansion returned the
  expanded text; an external parameter entity was read from disk
  (`/nonexistent-xdstorage-entity (No such file or directory)`); an external DTD FILE was read
  and its callback attempted (`Connection refused`); the source scan found four bare factories.

**The exposure, corrected against the intake's static reading.** An external GENERAL entity
placed directly in a `value` attribute — the engine's grammar keeps every scalar in an
attribute — is refused by the stock parser anyway (`The external entity reference "&xxe;" is
not permitted in an attribute value`). The live routes were the INTERNAL entities and the
EXTERNAL subset / parameter entities, not a plain `&xxe;` in an attribute.

**The first attempt, recorded because the pins rejected it.** `SUPPORT_DTD=false` plus
external entities off closes every fetch — but the stock parser then SKIPS the `<!DOCTYPE>`
silently: a document with an external DTD returned `[]` (the record vanished), an internal
subset failed later with `The entity "…" was referenced, but not declared`. A quiet skip is
the posture task 02 exists to remove; it did not land.

**The fix (`XdStorageDefaultObjectsReader`).**

- `newHardenedInputFactory()` — the only place the engine creates a parser:
  `SUPPORT_DTD=false`, `IS_SUPPORTING_EXTERNAL_ENTITIES=false` (both mandatory for every StAX
  implementation, set unconditionally), a resolver that throws, and the JAXP
  `ACCESS_EXTERNAL_DTD` / `ACCESS_EXTERNAL_SCHEMA` restrictions set to "none" where supported.
- `nextEvent(parser)` — the four parse loops advance through it, and it REFUSES the DTD event
  with the engine's own message: `a storage file must not carry a DOCTYPE declaration — DTD
  processing and external entities are disabled in the XML reader (CWE-611); …`.
  The facade wraps it (`XdStorageException: invalid state of database: error by reading
  resource …`) with the refusal on the cause chain.
- The probe facts behind the choice (JDK 21): with external entities off the parser reads
  neither an external parameter entity from the internal subset nor the external subset —
  until a resolver is installed, which it then consults (the resolver refuses); the Xerces
  `disallow-doctype-decl` feature cannot be set through the StAX factory.

**The pins (12 tests):** the four parse sites (a DTD-defined value must not get through); the
entity expansion; the external parameter entity; the remote DTD (zero requests to its origin,
no callback); the external DTD file; the benign corpus (predefined entities, character
references, comments, the word "DOCTYPE" inside DATA); the factory's own properties + the
resolver's refusal; the source scan (exactly one `XMLInputFactory.newInstance()` in
`src/main/java`); the real XML folder (cold reopen, `storage.load`).

**The 2.1.x cherry-pick:** the production diff is the reader file alone; CVE-class and
trivially cherry-pickable — the standing directive says it rides both lines; the 2.1.4 cut is
the maintainer's call.

## References

- The external evaluation 2026-11-23, §3.3.9 (verified against main: the four bare
  factory sites)
- Task 02 loud-failures (the refusal posture the pin follows)
- Task 37 TRUSTBDY (the sibling design round)
