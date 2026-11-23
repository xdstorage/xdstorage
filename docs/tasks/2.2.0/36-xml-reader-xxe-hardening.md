---
title: "[2.2.0][XXE] The XML readers parse DTDs and external entities: disable them (CWE-611)"
labels: [2.2.0-train, P1, security, bug]
status: open
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

- [ ] All four sites go through the hardened factory (grep-proof: no bare
      `XMLInputFactory.newInstance()` left).
- [ ] The malicious-DTD repro red-before / loud-fail-after (the pin).
- [ ] The XML estate ×3 + the quick-gate ×3 green.
- [ ] The docs (EMBEDDING/GUIDE) carry the trust-boundary note (one paragraph, task 37
      owns the full design).

Estimate: ~0.5–1 session.

## References

- The external evaluation 2026-11-23, §3.3.9 (verified against main: the four bare
  factory sites)
- Task 02 loud-failures (the refusal posture the pin follows)
- Task 37 TRUSTBDY (the sibling design round)
