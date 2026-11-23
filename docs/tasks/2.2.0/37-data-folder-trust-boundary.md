---
title: "[2.2.0][TRUSTBDY] Opening a foreign data folder instantiates attacker-named classes: the trust boundary + the whitelist decision"
labels: [2.2.0-train, P2, security, design]
status: open
---

## Summary

Class names are read FROM the data files and instantiated reflectively — no whitelist,
no confinement (the YAML reader's `newInstance` path; `XdStorageDefaultObjectsReader`
line 525 for XML). Combined with the XXE surface (task 36) the external evaluation's
verdict stands: opening a folder created by another application is unsafe, while the
README markets exactly that workflow (git-committed folders, `cp -r` backups). The
project needs an explicit trust-boundary statement and a real mitigation.

## Why

- "Treat a data folder as code" is a true statement nobody can find in the docs today —
  the README's git-friendly positioning makes it a LOAD-BEARING omission.
- The mitigation is cheap because the registry already exists:
  `XdStorageClassMetadataRegistry` knows every registered entity class — a reader-side
  check against it is a one-gate design, not a security framework.

## Scope

1. The design round: (a) the documented trust boundary (README/GUIDE/EMBEDDING: what is
   safe to open, what is not); (b) the whitelist option — default = registered-classes-
   only at read time, the config escape hatch for deliberate foreign-folder reads;
   (c) the refusal posture per task 02 (LOUD, named, with the offending class name).
2. The thin implementation behind the default-on check + the config opt-out.
3. The pin: a data folder referencing an unregistered class reads loudly (the repro);
   the legitimate reopen estate green ×3.
4. The docs: the security note lands in the three consumer documents.

## Out of scope

The XXE fix (task 36); any sandboxing beyond the whitelist (a class that IS registered
but misbehaves is the application's own dependency — in scope of nothing here).

## Acceptance criteria

- [ ] The design note recorded (the decision: default whitelist on, the opt-out named).
- [ ] The foreign-class repro red-before / loud-after; the reopen estate ×3 green.
- [ ] README + GUIDE + EMBEDDING carry the trust-boundary statement.
- [ ] The quick-gate ×3 green.

Estimate: ~1–2 sessions (the design half + the thin check).

## References

- The external evaluation 2026-11-23, §3.3.9 (the instantiation sites; the README
  contradiction)
- Task 36 XXE (the reader-hardening sibling — same trust boundary, the other half)
- Task 02 loud-failures (the refusal posture)
